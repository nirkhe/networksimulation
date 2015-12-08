package com.ricketts;

import java.io.PrintWriter;
import java.util.LinkedList;
import java.util.HashMap;
import java.util.ListIterator;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A Host is a Node meant to simulate a source or sink of data. Hosts have only one Link. Flows begin at Hosts.
 */
public class Host extends Node {

    private final static Integer initWindowSize = 1;
    private final static Integer initTimeoutLength = 3000;
    private final static Double timeoutLengthCatchupFactor = 0.1;

    private Link link;
    /**
     * A count of how many packets have been generated by this Host. Constantly updates.
     */
    private Integer totalGenPackets;
    /**
     * A LinkedList of Packets that have been scheduled to send out but have yet to be sent.
     * These are the priority packets to send out (generally ACKs).
     */
    private LinkedList<Packet> immediatePacketsToSend;
    /**
     * Mapping of Hosts to all their Active Flows.
     */
    private HashMap<Host, LinkedList<ActiveFlow>> flowsByDestination;
    /**
     * Downloads indexed by Source Host
     */
    private HashMap<Host, LinkedList<Download>> downloadsBySource;

    private PrintWriter writer;

    /**
     * Protocol we're using
     */
    private int protocol;

    /**
     * An Active Flow is one that is currently transmitting packets.
     * This data structure is used to ease keeping tracking of all of these data points.
     * Note all components are public but the object is private to the class so they can only be accessed publically
     * from methods in Host.
     */
    private class ActiveFlow {
        public Flow flow;
        public Integer windowSize;
        public Integer timeoutLength;
        // In Reno CA phase, every ACK increases cwnd by 1/cwnd. We're keeping
        // track of these partial windows added and then adding 1 to windowSize once
        // once partialWindowSize == windowSize.
        public Integer partialWindowSize;
        public Integer maxPacketID;
        // Indicates whether or not we're in the slow start phase.
        public boolean slowStart;
        // Indicates whether or not we're waiting for a retransmit.
        public boolean awaitingRetransmit;
        // Slow start threshhold
        public int ssthresh;
        /**
         * Monotonically increasing count of last ACK received
         */
        public Integer lastACKCount;
        public LinkedList<DataPacket> packets;
        public int mostRecentRetransmittedPacket;
        public int mostRecentQueued;
        public int mostRecentSent;
        public int windowOccupied;
        /**
         * A Hashmap of PacketID to the sendTime of that packet (in milliseconds)
         * Used to keep track of dropped packets
         */
        public HashMap<Integer, Integer> sendTimes;


        /**
         * A set of information on round trip times of packets
         */
        public Integer minRoundTripTime;
        public Double avgRoundTripTime;
        public Double stdDevRoundTripTime;

        /**
         * Bits sent withn this update session
         */
        private AtomicInteger currBitsSent;

        public ActiveFlow(Host host, Flow flow) {
            this.flow = flow;
            this.windowSize = initWindowSize;
            this.timeoutLength = initTimeoutLength;
            this.packets = flow.generateDataPackets(host.totalGenPackets);
            host.totalGenPackets += this.packets.size();
            this.maxPacketID = host.totalGenPackets - 1;
            this.lastACKCount = 0;
            this.sendTimes = new HashMap<>();
            this.minRoundTripTime = Integer.MAX_VALUE;
            this.avgRoundTripTime = null;
            this.stdDevRoundTripTime = null;
            this.currBitsSent = new AtomicInteger(0);
            this.partialWindowSize = 0;
            this.slowStart = true;
            this.awaitingRetransmit = false;
            this.ssthresh = Integer.MAX_VALUE;
            this.mostRecentRetransmittedPacket = 0;
            this.mostRecentQueued = -1;
            this.mostRecentSent = 0;
            this.windowOccupied = 0;
        }
    }

    /**
     * A Download represents a Flow incoming from another Host
     * As we are only simulating, no track of the actual packets is kept, just the packetIds
     */
    private class Download {
        /**
         * Last packet ID in the download
         */
        public Integer maxPacketID;
        /**
         * Next expected packet ID in the download
         * */
        public Integer nextPacketID;

        public Download(Integer minPacketID, Integer maxPacketID) {
            this.nextPacketID = minPacketID;
            this.maxPacketID = maxPacketID;
        }
    }

    /**
     * Complete Constructor
     */
    public Host(String address, Link link, Integer totalGenPackets, LinkedList<Packet> immediatePacketsToSend,
        HashMap<Host, LinkedList<ActiveFlow>> flowsByDestination, HashMap<Host, LinkedList<Download>> downloadsBySource,
        int protocol) {
        super(address);
        this.link = link;
        this.totalGenPackets = totalGenPackets;
        this.immediatePacketsToSend = immediatePacketsToSend;
        this.flowsByDestination = flowsByDestination;
        this.downloadsBySource = downloadsBySource;
        this.protocol = protocol;
        try {
            this.writer = new PrintWriter("logging_file_" + address + ".txt", "UTF-8");
        } catch (Exception e) {
            System.out.println(e);
        }
    }

    /**
     * Construct a Host from a link
     */
    public Host(String address, Link link, int protocol) {
        this(address, link, 0, new LinkedList<Packet>(), new HashMap<Host, LinkedList<ActiveFlow>>(),
                new HashMap<Host, LinkedList<Download>>(), protocol);
    }

    /**
     * Construct a Host by itself
     */
    public Host(String address, int protocol) {
        this(address, null, protocol);
    }

    public Link getLink() { return this.link; }
    public void setLink(Link link) {
        this.link = link;
    }

    /**
     * Add a flow starting from this Host
     * This involves checking if we already have flows going to that destination,
     * and getting the flowsByDestination HashMap set accordingly
     * Lastly, we send a setup packet to initiate the flow
     * @param flow The flow to be added
     */
    public void addFlow(Flow flow) {
        // Look for the destination host in our HashMap
        LinkedList<ActiveFlow> flows = this.flowsByDestination.get(flow.getDestination());
        //Check if HashMap key is defined
        if (flows == null) {
            flows = new LinkedList<>();
            flowsByDestination.put(flow.getDestination(), flows);
        }
        ActiveFlow newFlow = new ActiveFlow(this, flow);
        flows.add(newFlow);
        //Send a setup packet to initiate flow
        this.immediatePacketsToSend.add(new SetupPacket(newFlow.packets.peek().getID(), this,
                flow.getDestination(), newFlow.maxPacketID));
    }

    /**
     * Handles the reception of an ACK packet.
     * @param ackPacket the ACK received
     */
    private void receiveACKPacket(ACKPacket ackPacket) {
        writer.println("ack received" + ackPacket.getID());
        Integer packetID = ackPacket.getID();
        //Check to make sure the source of the ACK is from one which we are sending flows to
        LinkedList<ActiveFlow> flows = this.flowsByDestination.get(ackPacket.getSource());
        if (flows != null) {
             // Loop through all its active flows...
            for (ActiveFlow flow : flows) {
                Integer nextPacketID = flow.packets.peek().getID();
                // If the ACK is for a new packet, we know the destination has
                // received packets at least up to that one
                writer.write(packetID + " " + nextPacketID + " packet ids");
                if (packetID > nextPacketID && packetID - 1 <= flow.maxPacketID) {
                    writer.println("this is bs");
                    writer.println(packetID);
                    flow.windowOccupied--;
                    flow.mostRecentSent = packetID;
                    if (protocol == Main.Protocol.RENO) {
                        if (flow.slowStart) {
                            // If we're in slow start & Reno, cwnd <- cwnd + 1
                            flow.windowSize++;
                            if (flow.windowSize > flow.ssthresh) {
                                flow.slowStart = false;
                            }
                        }
                        // If we're in CA phase for Reno, cwnd <- cwnd + 1/cwnd. In out
                        // implementation we add to partialWindowSize.
                        else {
                            writer.println("Partial window size is " + flow.partialWindowSize);
                            writer.println("Window size is " + flow.windowSize);
                            flow.partialWindowSize++;
                            // If we've received enough acks to increment the window size, do so.
                            if (flow.partialWindowSize >= flow.windowSize) {
                                writer.println("does this ever happen :( :( :(");
                                flow.windowSize++;
                                flow.partialWindowSize = 0;
                            }
                        }
                    }
                    // If that was the last ACK, discard the flow
                    if (nextPacketID.equals(flow.maxPacketID))
                        flows.remove(flow);
                    // Remove all the packets we know to have be received from
                    // the flow's queue
                    else {
                        while (flow.packets.peek().getID() < packetID) {
                            Integer newRoundTripTime =
                                    RunSim.getCurrentTime() - flow.sendTimes.get(flow.packets.peek().getID());
                            // update minRoundTripTime
                            flow.minRoundTripTime = Math.min(flow.minRoundTripTime, newRoundTripTime);
                            // update avgRoundTripTime
                            if (flow.avgRoundTripTime == null) {
                                flow.avgRoundTripTime = newRoundTripTime * 1.0;
                            } else {
                                flow.avgRoundTripTime = flow.avgRoundTripTime * (1 - timeoutLengthCatchupFactor)
                                        + newRoundTripTime * timeoutLengthCatchupFactor;
                            }
                            // update stdDevRoundTripTime
                            if (flow.stdDevRoundTripTime == null) {
                                flow.stdDevRoundTripTime = newRoundTripTime * 1.0;
                            } else {
                                flow.stdDevRoundTripTime = flow.stdDevRoundTripTime * (1 - timeoutLengthCatchupFactor)
                                        + Math.abs(newRoundTripTime - flow.avgRoundTripTime) * timeoutLengthCatchupFactor;
                            }

                            // update timeoutLength
                            // flow.timeoutLength = (int) (flow.avgRoundTripTime + 4 * flow.stdDevRoundTripTime);
                            // flow.timeoutLength = initTimeoutLength;

                            flow.sendTimes.remove(flow.packets.remove().getID());
                        }
                    }
                    break;
                }
                // Otherwise the destination is still expecting the first
                //  packet in the queue
                else if (packetID.equals(nextPacketID)) {
                    writer.println("we gotta retransmit " + (flow.lastACKCount + 1) + " " + flow.mostRecentRetransmittedPacket);
                    // Increase the number of times the destination has reported
                    // a packet out of order
                    flow.lastACKCount++;
                    // If this packet has been ACKed three or more time, assume
                    // it's been dropped and retransmit (TCP FAST)
                    if (flow.lastACKCount >= 3 && flow.mostRecentRetransmittedPacket != packetID) {
                        writer.println(flow.mostRecentRetransmittedPacket + " most recent");
                        flow.mostRecentRetransmittedPacket = packetID;
                        writer.println("retransmitted pcket" + packetID);
                        System.out.println("FAST RETRANSMIT");
                        DataPacket packet = flow.packets.peek();
                        flow.sendTimes.put(packet.getID(), Main.currentTime);
                        this.link.clearBuffer(this);
                        this.link.addPacket(packet, this);
                        // Since everything we sent won't go through, reset the window size to
                        // 1 (since we just retransmitted a packet).
                        flow.windowOccupied = 1;
                        flow.mostRecentQueued = packet.getID();
                        if (protocol == Main.Protocol.RENO && !flow.awaitingRetransmit) {
                            // Enter FR/FR.
                            if (flow.windowSize / 2 < 2) {
                                flow.ssthresh = 2;
                            }
                            else {
                                flow.ssthresh = flow.windowSize / 2;
                            }
                            // Wait for packet retransmit, at that point we will deflate the
                            // window.
                            flow.awaitingRetransmit = true;
                            // cwnd <- ssthresh + ndup (temp window inflation)
                            flow.windowSize = flow.ssthresh + flow.lastACKCount;
                            flow.slowStart = false;
                        }
                        flow.lastACKCount = 0;
                    }
                    break;
                }
            }
        }
    }

    /**
     * Handles the setup of receiving a flow upon reach of a setup packet
     * @param packet The Setup packet
     */
    private void receiveSetupPacket(SetupPacket packet) {
        // Look for the source host in our HashMap
        LinkedList<Download> downloads = this.downloadsBySource.get(packet.getSource());
        // If there have already been downloads from this host, add another to the queue
        if (downloads != null)
            downloads.add(new Download(packet.getID() + 1, packet.getMaxPacketID()));
        // Otherwise create a queue and then add the download
        else {
            downloads = new LinkedList<Download>();
            downloads.add(new Download(packet.getID(), packet.getMaxPacketID()));
            this.downloadsBySource.put((Host) packet.getSource(), downloads);
        }
    }

    /**
     * Handles the reception and resending of an ACK packet upon recieving a DataPacket
     * @param packet The Setup packet
     */
    private void receiveDataPacket(DataPacket packet) {
        writer.println("Data packet " + packet.getID() + " received at host " + address);
        // Look for the source host in our HashMap
        LinkedList<Download> downloads = this.downloadsBySource.get(packet.getSource());
        Integer packetID = packet.getID();
        // If we have existing downloads from this host...
        if (downloads != null) {
            // Look through them all for the one this packet's a part of
            for (Download download : downloads) {
                writer.println("step 2");
                writer.println(download.nextPacketID + " next");
                writer.println(packetID + " curr");
                writer.println(download.maxPacketID + " max");
                if (download.nextPacketID <= packetID && packetID <= download.maxPacketID) {
                    // If this was the next packet in the download...
                    if (download.nextPacketID.equals(packetID)) {
                        // Start expecting the following one
                        download.nextPacketID++;
                        // Or if this was the last packet in the download, discard it
                        if (download.maxPacketID.equals(packetID))
                            downloads.remove(download);
                    }
                    writer.println("sending ack!!!!!!!!!!!!!" + download.nextPacketID);
                    // Add an ACK packet to the queue of packets to send immediately
                    immediatePacketsToSend.add(new ACKPacket(download.nextPacketID,
                            (Host) packet.getDestination(), (Host) packet.getSource()));
                    break;
                }
            }
        }
    }

    /**
     * Calls the appropriate Packet Receiving subroutine based on packet data type
     * @param packet Packet received
     * @param receivingLink The link that the packet came on
     */
    public void receivePacket(Packet packet, Link receivingLink) {
        if (packet instanceof ACKPacket)
            this.receiveACKPacket((ACKPacket) packet);
        else if (packet instanceof SetupPacket)
            this.receiveSetupPacket((SetupPacket) packet);
        else if (packet instanceof DataPacket)
            this.receiveDataPacket((DataPacket) packet);
        //else if (packet instanceof RoutingTablePacket)
            //Do nothing
    }

    /**
     * Updates a Host so that it sends the packets it currently has available
     * to the link buffer.
     * @param intervalTime The time step of the simulation
     * @param overallTime Overall simulation time
     */
    public void update(Integer intervalTime, Integer overallTime) {
        writer.println("update");
        // If this host is connected
        if (this.link != null) {
            // While there are packets to send immediately (e.g. ACKs), add them
            while (this.immediatePacketsToSend.peek() != null)
                this.link.addPacket(this.immediatePacketsToSend.remove(), this);

            // For each set of flows, indexed by destination...
            for (LinkedList<ActiveFlow> flows : this.flowsByDestination.values()) {
                // For each flow...
                for (ActiveFlow flow : flows) {
                    flow.currBitsSent.set(0);
                    // For each currently outstanding packet, check if the
                    // timeout time has elapsed since it was sent, and
                    // retransmit if so
                    for (Integer packetID : flow.sendTimes.keySet()) {
                        if (flow.sendTimes.get(packetID) + flow.timeoutLength <
                            Main.currentTime)
                        {
                            /*
                            // If we retransmit, we re-enter slow start with ssthresh = half window size
                            if (protocol == Main.Protocol.RENO) {
                                if (flow.windowSize / 2 < 2) {
                                    flow.ssthresh = 2;
                                }
                                else {
                                    flow.ssthresh = flow.windowSize / 2;
                                }
                                flow.slowStart = true;
                                flow.windowSize = initWindowSize;
                            }*/
                            System.out.println("TCP RETRANSMIT");
                            flow.sendTimes.put(packetID, Main.currentTime);
                            flow.windowOccupied = 1;
                            flow.mostRecentQueued = packetID;
                            link.clearBuffer(this);
                            this.link.addPacket(flow.packets.get(packetID -
                                flow.packets.peek().getID()), this);
                        }
                    }
                    writer.println("did we get here");
                    // Packets are ACKed sequentially, so the outstanding
                    // packets have to be at the front of the flow's queue. Thus
                    // we can jump past them and fill up the rest of the window.
                    int next = flow.mostRecentQueued + 1;
                    ListIterator<DataPacket> it = flow.packets.listIterator(next - flow.packets.peek().getID());
                    if (it.hasNext()) {
                        writer.println("has next");
                        DataPacket packet = it.next();
                        writer.println(packet.getID());
                        //Integer nextPacketID = flow.packets.peek().getID();
                        writer.println(flow.mostRecentQueued + " " + flow.windowOccupied + " " + flow.windowSize);
                        while (flow.windowSize > flow.windowOccupied) {
                            writer.println("we're doing the thing");
                            // If we're in FR/FR and we're retransmitting, we need to deflate the window.
                            if (protocol == Main.Protocol.RENO && flow.awaitingRetransmit) {
                                flow.windowSize = flow.ssthresh;
                                flow.awaitingRetransmit = false;
                            }
                            flow.windowOccupied++;
                            writer.println("adding packet " + packet.getID());
                            this.link.addPacket(packet, this);
                            writer.println("Added packet " + packet.getID());
                            flow.sendTimes.put(packet.getID(), Main.currentTime);
                            flow.mostRecentQueued = packet.getID();
                            flow.currBitsSent.addAndGet(packet.getSize());
                            if (it.hasNext())
                                packet = it.next();
                            else
                                break;
                        }
                    }
                    flow.flow.flowAnalyticsCollector.addToFlowRates(((double) flow.currBitsSent.get() / 100000) / ((double) intervalTime / 1000), overallTime);
                    flow.flow.flowAnalyticsCollector.addToWindowSize(flow.windowSize, overallTime);
                    // not sure how to do packet delay
                }
            }
        }
    }
}