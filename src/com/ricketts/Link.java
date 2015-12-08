package com.ricketts;

import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.xy.XYSeries;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Link is unlike a physical Link. Instead think of a Link as the physical link plus the buffers on either end.
 * A link is defined as LEFT to RIGHT (the naming is arbitrary) but packets are sent in 1 direction at a time.
 */
public class Link implements Updatable {

    private final Integer BUFFER_DELAY_PERIOD = 2000;

    private Integer timeSinceReBufferDelay;

    /**
     * Orientations for Packets flowing on the link
     */
    public enum Direction {LEFT, RIGHT}

    private final Integer linkID;
    /**
     * Link rate in bits per millisecond
     */
    private final Integer linkRate;
    /**
     * Link delay in milliseconds
     */
    private final Integer linkDelay;
    /**
     * Link buffer in bits
     */
    private final Integer linkBuffer;

    private Node leftNode, rightNode;

    private Integer numbLeftPktsThruBuffer;
    private Integer numbRightPktsThruBuffer;

    private Double sumLeftBufferTime;
    private Double sumRightBufferTime;

    private Double latestLeftBufferDelayEstimate;
    private Double latestRightBufferDelayEstimate;

    /**
     * Packet drops for current interval
     */
    private Integer packetDrops;
    private LinkAnalyticsCollector linkAnalyticsCollector;

    /**
     * Data class associating a direction and start time with a packet being
     * transmitted on a link.
     */
    private class TransmittingPacket {
        public Packet packet;
        public Direction direction;
        public Integer transmissionStartTime;

        public TransmittingPacket(Packet packet, Direction direction, Integer transmissionStartTime) {
            this.packet = packet;
            this.direction = direction;
            this.transmissionStartTime = transmissionStartTime;
        }
    }

    /**
     * Packet buffers on either end of the link
     */
    private LinkedList<TransmittingPacket> leftPacketBuffer, rightPacketBuffer;
    /**
     * Remaining capacity in the two buffers, in bits
     */
    private Integer leftBufferRemainingCapacity, rightBufferRemainingCapacity;

    /**
     * Packet currently being transmitted
     */
    private TransmittingPacket currentlyTransmittingPacket;
    /**
     * How much of the packet has been transmitted
     */
    private Integer bitsTransmitted;

    /**
     * How many bits have been transmitted in the total period
     */
    private AtomicInteger totalBitsTransmitted;

    /**
     * Which protocol we're using right now
     */
    private int protocol;

    private PrintWriter writer_left;
    private PrintWriter writer_right;
    /**
     * Complete Constructor
     */
    public Link(Integer linkID, Integer linkRate, Integer linkDelay,
        Integer linkBuffer, Node leftNode, Node rightNode, String name, int protocol) {
        this.linkID = linkID;
        this.linkRate = linkRate;
        this.linkDelay = linkDelay;
        this.linkBuffer = linkBuffer;
        this.leftNode = leftNode;
        this.rightNode = rightNode;

        this.leftPacketBuffer = new LinkedList<>();
        this.rightPacketBuffer = new LinkedList<>();
        this.leftBufferRemainingCapacity = linkBuffer;
        this.rightBufferRemainingCapacity = linkBuffer;
        this.packetDrops = 0;
        this.totalBitsTransmitted = new AtomicInteger(0);
        this.linkAnalyticsCollector = new LinkAnalyticsCollector(linkID, name);
        this.protocol = protocol;
        try {
            this.writer_left = new PrintWriter("logger_link_left.txt", "UTF-8");
            this.writer_right = new PrintWriter("logger_link_right.txt", "UTF-8");
        } catch(Exception e) {
            System.out.println(e);
        }

        numbLeftPktsThruBuffer = 0;
        numbRightPktsThruBuffer = 0;
        sumLeftBufferTime = 0.0;
        sumRightBufferTime = 0.0;
        timeSinceReBufferDelay = 0;

        latestLeftBufferDelayEstimate = 0.0;
        latestRightBufferDelayEstimate = 0.0;
    }

    /**
     * Constructor without nodes defined
     */
    public Link(Integer linkID, Integer linkRate, Integer linkDelay,
            Integer linkBuffer, String name, int protocol) {
        this(linkID,linkRate, linkDelay, linkBuffer, null, null, name, protocol);
    }

    public Integer getID() { return this.linkID; }
    public Node getLeftNode() { return this.leftNode; }
    public Node getRightNode() { return this.rightNode; }
    public Integer getLinkDelay() { return this.linkDelay; }
    public void setLeftNode(Node node) { this.leftNode = node; }
    public void setRightNode(Node node) { this.rightNode = node; }


    public Double getBufferDelay(Direction direction) {
        if(direction == Direction.LEFT) {
            return latestLeftBufferDelayEstimate;
        }
        else {
            return latestRightBufferDelayEstimate;
        }
    }

    private Double getBufferDelay(Node node) {
        if(node == this.leftNode) {
            return getBufferDelay(Direction.RIGHT);
        } else if (node == this.rightNode) {
            return getBufferDelay(Direction.LEFT);
        } else
            return 0.0;
    }

    public Double getDelay(Node node) {
        //return getLinkDelay().doubleValue();
        return getLinkDelay() + getBufferDelay(node);
    }


    public Node getOtherEnd(Node oneEnd) {
        if (oneEnd == this.leftNode) {
            return this.rightNode;
        } else if (oneEnd == this.rightNode) {
            return this.leftNode;
        } else {
            return null;
        }
    }

    /**
     * Check if the packet can fit in the buffer otherwise drop it
     * Return a Boolean if the Packet was added to the buffer
     * @param packet the packet being sent across the node
     * @param sendingNode the node sending the packet
     * @return false if Dropped Packet or true if Successfully Added to Buffer
     */
    public Boolean addPacket(Packet packet, Node sendingNode) {
        Integer newRemainingCapacity;
        String type = (packet instanceof ACKPacket) ? "ack" : "data";
        // If packet is coming from the left
        if (sendingNode == leftNode) {
            writer_left.println("adding " + type + " packet " + packet.getID());
            writer_left.println("remaining capacity " + leftBufferRemainingCapacity);
            // Check if it fits in the buffer
            newRemainingCapacity = leftBufferRemainingCapacity - packet.getSize();
            if (newRemainingCapacity >= 0) {
                // If so, add it and update the remaining capacity
                leftPacketBuffer.add(new TransmittingPacket(packet, Direction.RIGHT, Main.currentTime.intValue()));
                leftBufferRemainingCapacity = newRemainingCapacity;
                writer_left.println("sent packet on left");
                return true;
            }
        }
        // Likewise if coming from right
        else if (sendingNode == rightNode) {
            writer_right.println("adding " + type + " packet " + packet.getID());
            writer_right.println("remaining capacity " + rightBufferRemainingCapacity);
            newRemainingCapacity = rightBufferRemainingCapacity - packet.getSize();
            if (newRemainingCapacity >= 0) {
                rightPacketBuffer.add(new TransmittingPacket(packet, Direction.LEFT, Main.currentTime.intValue()));
                rightBufferRemainingCapacity = newRemainingCapacity;
                writer_right.println("sent packet on right");
                return true;
            }
        }
        // If it came from somewhere else, something is wrong
        else {
            System.out.println("addPacket() from unconnected node");
        }

        // We dropped this packet
        this.packetDrops = this.packetDrops + 1;
        return false;
    }

    /**
     * Clears the buffers (we want to do this if we have a timeout or retransmit to avoid sending
     * a lot of unnecessary packets).
     * @param sendingNode: the node we're sending from
     */
    public void clearBuffer(Node sendingNode) {
        // We want to clear the buffer we're sending from
        if (sendingNode == leftNode) {
            leftPacketBuffer.clear();
            leftBufferRemainingCapacity = linkBuffer;
        }
        else if (sendingNode == rightNode) {
            rightPacketBuffer.clear();
            rightBufferRemainingCapacity = linkBuffer;
        }
        else {
            System.out.println("Something went terribly wrong");
        }
    }

    /**
     * Updates the state of the Link. This involves any of the following:
     * (a) Updating the state of packets currently in transmission
     * (b) Removing transmitted packets
     * (c) Adding packets to the link from the link buffer
     * @param intervalTime The time step of the simulation
     * @param overallTime Overall simulation time
     */
    public void update(Integer intervalTime, Integer overallTime) {

        if(timeSinceReBufferDelay < 0) {
            timeSinceReBufferDelay = BUFFER_DELAY_PERIOD;

            if(numbLeftPktsThruBuffer == 0)
                latestLeftBufferDelayEstimate = 0.0;
            else
                latestLeftBufferDelayEstimate = sumLeftBufferTime / numbLeftPktsThruBuffer;

            if(numbRightPktsThruBuffer == 0)
                latestRightBufferDelayEstimate = 0.0;
            else
                latestRightBufferDelayEstimate = sumRightBufferTime / numbRightPktsThruBuffer;

            numbLeftPktsThruBuffer = 0;
            numbRightPktsThruBuffer = 0;
            sumLeftBufferTime = 0.0;
            sumRightBufferTime = 0.0;

        } else {
            timeSinceReBufferDelay -= intervalTime;
        }


        // Reset packets drops and total bits transmitted for new interval
        totalBitsTransmitted.set(0);
        // While there's time left in the interval,,,
        Integer usageLeft = intervalTime * this.linkRate, packetBits, endOfDelay;

        while (usageLeft > 0) {
            // If there's no packet being currently transmitted, fetch one from
            // the left or right buffer. Preference is given to whichever buffer
            // has the packet at the front of its queue that's been waiting
            // longer.
            if (this.currentlyTransmittingPacket == null) {
                TransmittingPacket leftPacket = this.leftPacketBuffer.peek();
                TransmittingPacket rightPacket = this.rightPacketBuffer.peek();
                if (leftPacket == null) {
                    if (rightPacket == null) {
                        return;
                    }
                    else {
                        this.currentlyTransmittingPacket = rightPacketBuffer.remove();
                        sumRightBufferTime += Main.currentTime.intValue() - currentlyTransmittingPacket.transmissionStartTime;
                        numbRightPktsThruBuffer++;
                        this.rightBufferRemainingCapacity += rightPacket.packet.getSize();
                    }
                }
                else if (rightPacket == null ||
                    leftPacket.transmissionStartTime < rightPacket.transmissionStartTime)
                {    
                    this.currentlyTransmittingPacket = leftPacketBuffer.remove();
                    sumLeftBufferTime += Main.currentTime.intValue() - currentlyTransmittingPacket.transmissionStartTime;
                    numbLeftPktsThruBuffer++;
                    this.leftBufferRemainingCapacity += leftPacket.packet.getSize();
                }
                else {
                    this.currentlyTransmittingPacket = rightPacketBuffer.remove();
                    sumRightBufferTime += Main.currentTime.intValue() - currentlyTransmittingPacket.transmissionStartTime;
                    numbRightPktsThruBuffer++;
                    this.rightBufferRemainingCapacity += rightPacket.packet.getSize();
                }

                this.currentlyTransmittingPacket.transmissionStartTime =
                    Main.currentTime.intValue();
                this.bitsTransmitted = 0;
            }

            // Figure out when this packet's propagation delay would be over and
            // it could start transferring to the node
            endOfDelay = this.currentlyTransmittingPacket.transmissionStartTime + linkDelay;
            if (endOfDelay > overallTime)
                usageLeft = (overallTime + intervalTime - endOfDelay) * this.linkRate;

            // If it reaches the node before the timestep is over, transmit as
            // much as possible
            if (usageLeft > 0) {
                // We can either transmit a chunk of the packet, or all that
                // remains of it
                packetBits = Math.min(usageLeft,
                    this.currentlyTransmittingPacket.packet.getSize() - this.bitsTransmitted);
                this.bitsTransmitted += packetBits;
                // If we've transmitted the entire packet, transfer it to the
                // host
                if (this.bitsTransmitted.equals(this.currentlyTransmittingPacket.packet.getSize())) {
                    if (this.currentlyTransmittingPacket.direction == Direction.RIGHT) {
                        this.rightNode.receivePacket(this.currentlyTransmittingPacket.packet, this);
                    }
                    else {
                        this.leftNode.receivePacket(this.currentlyTransmittingPacket.packet, this);
                    }
                    
                    // We're done transmitting this packet
                    this.totalBitsTransmitted.addAndGet(this.bitsTransmitted);
                    this.currentlyTransmittingPacket = null;
                    this.bitsTransmitted = 0;

                    // Presumably we're in the "remainder" case, and we have to
                    // figure out how long transferring the end of the packet
                    // would take
                    usageLeft -= packetBits;
                }
            }
        }
        // Want rates per second
        linkAnalyticsCollector.addToLeftBuffer((linkBuffer - leftBufferRemainingCapacity) / ((double) intervalTime / 1000), overallTime);
        //System.out.println((linkBuffer - leftBufferRemainingCapacity));
        linkAnalyticsCollector.addToRightBuffer((linkBuffer - rightBufferRemainingCapacity) / ((double) intervalTime / 1000), overallTime);
        linkAnalyticsCollector.addToPacketLoss(packetDrops, overallTime);
        //packetDrops = 0;
        // Want link rates in Mbps
        linkAnalyticsCollector.addToLinkRates(((double) totalBitsTransmitted.get() / 100000) / ((double) intervalTime / 1000), overallTime);
    }

    public ArrayList<XYSeries> getDatasets() {
        return linkAnalyticsCollector.getDatasets();
    }
}