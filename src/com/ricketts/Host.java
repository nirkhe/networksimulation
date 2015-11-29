package com.ricketts;

import java.util.LinkedList;
import java.util.HashMap;
import java.util.ListIterator;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A Host is a Node meant to simulate a source or sink of data. Hosts have only one Link. Flows begin at Hosts.
 */
public class Host extends Node {

    private final static Integer initWindowSize = 50;
    private final static Integer timeoutLength = 3000;

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

    /**
     * An Active Flow is one that is currently transmitting packets.
     * This data structure is used to ease keeping tracking of all of these data points.
     * Note all components are public but the object is private to the class so they can only be accessed publically
     * from methods in Host.
     */
    private class ActiveFlow {
        public Flow flow;
        public Integer windowSize;
        public Integer maxPacketID;
        /**
         * Monotonically increasing count of last ACK received
         */
        public Integer lastACKCount;
        public LinkedList<DataPacket> packets;
        /**
         * A Hashmap of PacketID to the sendTime of that packet (in milliseconds)
         * Used to keep track of dropped packets
         */
        public HashMap<Integer, Integer> sendTimes;

        /**
         * Bits sent withn this update session
         */
        private AtomicInteger currBitsSent;

        public ActiveFlow(Host host, Flow flow) {
            this.flow = flow;
            this.windowSize = initWindowSize;
            this.packets = flow.generateDataPackets(host.totalGenPackets);
            host.totalGenPackets += this.packets.size();
            this.maxPacketID = host.totalGenPackets - 1;
            this.lastACKCount = 0;
            this.sendTimes = new HashMap<>();
            currBitsSent = new AtomicInteger(0);
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
        HashMap<Host, LinkedList<ActiveFlow>> flowsByDestination, HashMap<Host, LinkedList<Download>> downloadsBySource) {
        super(address);
        this.link = link;
        this.totalGenPackets = totalGenPackets;
        this.immediatePacketsToSend = immediatePacketsToSend;
        this.flowsByDestination = flowsByDestination;
        this.downloadsBySource = downloadsBySource;
    }

    /**
     * Construct a Host from a link
     */
    public Host(String address, Link link) {
        this(address, link, 0, new LinkedList<Packet>(), new HashMap<Host, LinkedList<ActiveFlow>>(), new HashMap<Host, LinkedList<Download>>());
    }

    /**
     * Construct a Host by itself
     */
    public Host(String address) {
        this(address, null);
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
        Integer packetID = ackPacket.getID();
        //Check to make sure the source of the ACK is from one which we are sending flows to
        LinkedList<ActiveFlow> flows = this.flowsByDestination.get(ackPacket.getSource());
        if (flows != null) {
             // Loop through all its active flows...
            for (ActiveFlow flow : flows) {
                Integer nextPacketID = flow.packets.peek().getID();
                // If the ACK is for a new packet, we know the destination has
                // received packets at least up to that one
                if (packetID > nextPacketID && packetID - 1 <= flow.maxPacketID) {
                    // If that was the last ACK, discard the flow
                    if (nextPacketID.equals(flow.maxPacketID))
                        flows.remove(flow);
                    // Remove all the packets we know to have be received from
                    // the flow's queue
                    else {
                        while (flow.packets.peek().getID() < packetID)
                            flow.sendTimes.remove(flow.packets.remove().getID());
                    }
                    break;
                }
                // Otherwise the destination is still expecting the first
                //  packet in the queue
                else if (packetID.equals(nextPacketID)) {
                    // Increase the number of times the destination has reported
                    // a packet out of order
                    flow.lastACKCount++;
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
        System.out.println("Setup packet " + packet.getID() + " received at host " + address);

        // Look for the source host in our HashMap
        LinkedList<Download> downloads = this.downloadsBySource.get(packet.getSource());
        // If there have already been downloads from this host, add another to the queue
        if (downloads != null)
            downloads.add(new Download(packet.getID(), packet.getMaxPacketID()));
        // Otherwise create a queue and then add the download
        else {
            downloads = new LinkedList<Download>();
            downloads.add(new Download(packet.getID(), packet.getMaxPacketID()));
            this.downloadsBySource.put(packet.getSource(), downloads);
        }
    }

    /**
     * Handles the reception and resending of an ACK packet upon recieving a DataPacket
     * @param packet The Setup packet
     */
    private void receiveDataPacket(DataPacket packet) {
        System.out.println("Data packet " + packet.getID() + " received at host " + address);

        // Look for the source host in our HashMap
        LinkedList<Download> downloads = this.downloadsBySource.get(packet.getSource());
        Integer packetID = packet.getID();
        // If we have existing downloads from this host...
        if (downloads != null) {
            // Look through them all for the one this packet's a part of
            for (Download download : downloads) {
                if (download.nextPacketID <= packetID && packetID <= download.maxPacketID) {
                    // If this was the next packet in the download...
                    if (download.nextPacketID.equals(packetID)) {
                        // Start expecting the following one
                        download.nextPacketID++;
                        // Or if this was the last packet in the download, discard it
                        if (download.maxPacketID.equals(packetID))
                            downloads.remove(download);
                    }
                    // Add an ACK packet to the queue of packets to send immediately
                    immediatePacketsToSend.add(new ACKPacket(download.nextPacketID,
                        packet.getDestination(), packet.getSource()));
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
    }

    /**
     * Updates a Host so that it sends the packets it currently has available
     * to the link buffer.
     * @param intervalTime The time step of the simulation
     * @param overallTime Overall simulation time
     */
    public void update(Integer intervalTime, Integer overallTime) {
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
                    // If this packet has been ACKed three or more time, assume
                    // it's been dropped and retransmit (TCP FAST)
                    if (flow.lastACKCount >= 3) {
                        System.out.println("TCP FAST");
                        DataPacket packet = flow.packets.peek();
                        flow.sendTimes.put(packet.getID(), RunSim.getCurrentTime());
                        this.link.addPacket(packet, this);
                        flow.lastACKCount = 0;
                    }
                    // For each currently outstanding packet, check if the
                    // timeout time has elapsed since it was sent, and
                    // retransmit if so
                    for (Integer packetID : flow.sendTimes.keySet()) {
                        if (flow.sendTimes.get(packetID) + this.timeoutLength <
                            RunSim.getCurrentTime())
                        {
                            System.out.println("TCP RETRANSMIT");
                            flow.sendTimes.put(packetID, RunSim.getCurrentTime());
                            this.link.addPacket(flow.packets.get(packetID -
                                flow.packets.peek().getID()), this);
                        }
                    }
                    
                    // Packets are ACKed sequentially, so the outstanding
                    // packets have to be at the front of the flow's queue. Thus
                    // we can jump past them and fill up the rest of the window.
                    ListIterator<DataPacket> it =
                        flow.packets.listIterator(flow.sendTimes.size());
                    if (it.hasNext()) {
                        DataPacket packet = it.next();
                        Integer nextPacketID = flow.packets.peek().getID();
                        while (packet.getID() < nextPacketID + flow.windowSize) {
                            this.link.addPacket(packet, this);
                            flow.sendTimes.put(packet.getID(), RunSim.getCurrentTime());
                            flow.currBitsSent.addAndGet(packet.getSize());
                            if (it.hasNext())
                                packet = it.next();
                            else
                                break;
                        }
                    }
                    flow.flow.flowAnalyticsCollector.addToFlowRates(flow.currBitsSent.get() * 100000 / ((double) intervalTime / 1000), intervalTime);
                    flow.flow.flowAnalyticsCollector.addToFlowRates(flow.windowSize, intervalTime);
                    // not sure how to do packet delay
                }
            }
        }
    }
}