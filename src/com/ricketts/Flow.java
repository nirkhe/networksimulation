package com.ricketts;

import org.jfree.data.xy.XYSeries;

import java.util.*;

/**
 * Flows are used to describe a desire to move Data from one Host to another.
 * They describe how much data is going to be moved, and also provide the logistics for
 * generating the necessary packets. As well as containing the necessary data about the flow for calculations.
 */
public class Flow {

    /**
     * The Starting Window Size and what we drop to at an RTO
     */
    public final static Integer initWindowSize = 1;
    public final static Integer timeoutLength = 600;

    public Integer windowSize;

    /**
     * In reality the window size = W + n/W where n is an integer less than W. This is that n (fractional component)
     * We keep track of it because with incremental change  of 1 / W will lead to an integer change.
     *
     * This is used in Reno Congestion Avoidance phase when each ACK increases cwnd by 1 / cwnd
     */
    public Integer partialWindowSize;

    public Integer lastPacketID;
    /**
     * Indicates whether or not we're in the slow start phase.
     */
    public boolean slowStart;
    /**
     * Indicates whether or not we're waiting for a retransmit.
     */
    public boolean awaitingRetransmit;

    public int slowStartThreshold;
    /**
     * Monotonically increasing count of how many times we have recieved an ACK of ID = the largest ACK ID recieved yet
     * If this is 3, we go into retransmitting
     */
    public Integer numberOfLatestACKIDRecieved;
    /**
     * Packets to be sent in this flow
     */
    public ArrayList<DataPacket> packets;
    public int mostRecentRetransmittedPacketID;
    public int mostRecentQueuedID;
    /**
     * The number of packets in the window
     */
    public int numbPacketsInWindow;
    /**
     * A Hashmap of PacketID to the sendTime of that packet (in milliseconds)
     * Used to keep track of dropped packets
     */
    public HashMap<Integer, Integer> sendTimes;

    /**
     * Index of the first not received ACK
     */
    public Integer firstNotRecievedPacketIndex;

    /**
     * Sum of roundtrip times, used for averaging.
     */
    public Integer totalRoundTripTime;
    /**
     * Used for averaging.
     */
    public Integer numbRoundTrips;

    public Integer minRoundTripTime;
    public Double avgRoundTripTime;

    /**
     * Bits sent within this update session
     */
    public Integer currBitsSent;
    public Integer totalBitsSent;

    private Integer id;
    private Host source;
    private Host destination;
    public FlowAnalyticsCollector flowAnalyticsCollector;
    /**
     * dataSize is measured in bits. Total data sent over all packets.
     */
    private Integer dataSize;

    /**
     * Protocol (FAST or RENO)
     */
    private int protocol;

    /**
     * Measured in milliseconds. Denotes when relative to the global time this flow should initiate.
     */
    private Integer startTime;

    /**
     * Indicates whether this flow is transmitting or is dormant)
     */
    public boolean activated;

    public Flow(Integer id, Host source, Host destination, Integer dataSize, Integer startTime, int protocol) {
        this.id = id;
        this.source = source;
        this.destination = destination;
        this.dataSize = dataSize;
        this.startTime = startTime;
        this.flowAnalyticsCollector = new FlowAnalyticsCollector(this.id);
        this.protocol = protocol;
        this.totalBitsSent = 0;

        activated = false;
    }

    /**
     * Initialize flow for sending packets
     */
    public void activateFlow() {
        this.activated = true;
        this.windowSize = initWindowSize;
        this.packets = generateDataPackets(0);
        this.lastPacketID = packets.size() - 1;
        this.numberOfLatestACKIDRecieved = 0;
        this.sendTimes = new HashMap<>();
        this.totalRoundTripTime = 0;
        this.numbRoundTrips = 0;
        this.minRoundTripTime = Integer.MAX_VALUE;
        this.avgRoundTripTime = null;
        this.currBitsSent = 0;
        this.partialWindowSize = 0;
        this.slowStart = true;
        this.awaitingRetransmit = false;
        this.slowStartThreshold = Integer.MAX_VALUE;
        this.mostRecentRetransmittedPacketID = 0;
        this.mostRecentQueuedID = -1;
        this.numbPacketsInWindow = 0;
        this.firstNotRecievedPacketIndex = 0;
    }

    public Host getSource() { return this.source; }
    public Host getDestination() { return this.destination; }
    public Integer getID() { return this.id; }
    public Integer getStartTime() {
        return startTime;
    }

    /**
     * This method generates a LinkedList of DataPackets corresponding to the size of data of the flow.
     * The packets are produced with sequential id numbers starting with the initialID number.
     * @param initID The first ID number for the corresponding sequence of DataPackets
     * @return LinkedList of all the data packets in sequential order.
     */
    private ArrayList<DataPacket> generateDataPackets(Integer initID) {
        Integer dataPacketSize = DataPacket.DataPacketSize;
        //We have to take the intValue or we're manipulating that data point itself (Objects not Primitives)
        Integer dataToPacketSize = this.dataSize.intValue();

        ArrayList<DataPacket> dataPackets = new ArrayList<>(dataToPacketSize / dataPacketSize + 1);
        Integer packetID = initID;
        while (dataToPacketSize - dataPacketSize > 0) {
            DataPacket newPacket = new DataPacket(packetID, this);
            dataPackets.add(newPacket);
            dataToPacketSize -= dataPacketSize;
            packetID++;
        }

        //In the case that the data cannot be evenly placed in all the packets
        //We need to send an extra packet
        //Note: Due to the parameters of the project, this should never be necessary but is good coding practice
        if (dataToPacketSize > 0) {
            DataPacket lastNewPacket = new DataPacket(packetID, this);
            dataPackets.add(lastNewPacket);
        }

        return dataPackets;
    }

    /**
     * Returns the data for graphing
     * @return Graphing Data
     */
    public ArrayList<XYSeries> getDatasets() {
        return flowAnalyticsCollector.getDatasets();
    }
}