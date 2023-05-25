/*
 *  Copyright 2014 AT&T
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package com.lipisoft.toyshark;

import android.util.Log;

import com.lipisoft.toyshark.network.ip.IPv4Header;
import com.lipisoft.toyshark.socket.ICloseSession;
import com.lipisoft.toyshark.transport.tcp.TCPHeader;
import com.lipisoft.toyshark.transport.udp.UDPHeader;
import com.lipisoft.toyshark.util.PacketUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.util.Arrays;

/**
 * store information about a socket connection from a VPN client.
 * Each session is used by background worker to server request from client.
 * @author Borey Sao
 * Date: May 19, 2014
 */
public class Session {
	private final String TAG = "Session";

	private AbstractSelectableChannel channel;

	private final int destIp;
	private final int destPort;

	private final int sourceIp;
	private final int sourcePort;

	private int appUid;

	//sequence received from client
	private long recSequence = 0;

	//track ack we sent to client and waiting for ack back from client
	private long sendUnack = 0;
	private boolean isacked = false;//last packet was acked yet?

	//the next ack to send to client
	private long sendNext = 0;
	private int sendWindow = 0; //window = windowsize x windowscale
	private int sendWindowSize = 0;
	private int sendWindowScale = 0;

	private int bytesReceived =0;
	private int bytesSent =0;

	private int packetsReceived =0;
	private int packetsSent =0;

	//track how many byte of data has been sent since last ACK from client
	private volatile int sendAmountSinceLastAck = 0;

	//sent by client during SYN inside tcp options
	private int maxSegmentSize = 0;

	//indicate that 3-way handshake has been completed or not
	private boolean isConnected = false;

	//receiving buffer for storing data from remote host
	private final ByteArrayOutputStream receivingStream;

	//sending buffer for storing data from vpn client to be send to destination host
	private final ByteArrayOutputStream sendingStream;

	private boolean hasReceivedLastSegment = false;

	//last packet received from client
	private IPv4Header lastIpHeader;
	private TCPHeader lastTcpHeader;
	private UDPHeader lastUdpHeader;

	//true when connection is about to be close
	private boolean closingConnection = false;

	//indicate data from client is ready for sending to destination
	private volatile boolean isDataForSendingReady = false;

	//store data for retransmission
	private byte[] unackData = null;

	//in ACK packet from client, if the previous packet was corrupted, client will send flag in options field
	private boolean packetCorrupted = false;

	//track how many time a packet has been retransmitted => avoid loop
	private int resendPacketCounter = 0;

	private int timestampSender = 0;
	private int timestampReplyto = 0;

	//indicate that vpn client has sent FIN flag and it has been acked
	private boolean ackedToFin = false;

	//closing session and aborting connection, will be done by background task
	private volatile boolean abortingConnection = false;

	private SelectionKey selectionkey = null;

	public long connectionStartTime = 0;

	private final ICloseSession sessionCloser;

	Session(
			int sourceIp,
			int sourcePort,
			int destinationIp,
			int destinationPort,
			ICloseSession sessionCloser
	) {
		receivingStream = new ByteArrayOutputStream();
		sendingStream = new ByteArrayOutputStream();

		this.sourceIp = sourceIp;
		this.sourcePort = sourcePort;
		this.destIp = destinationIp;
		this.destPort = destinationPort;

		this.sessionCloser = sessionCloser;
	}

	/**
	 * decrease value of sendAmountSinceLastAck so that client's window is not full
	 * @param amount Amount
	 */
	synchronized void decreaseAmountSentSinceLastAck(long amount){
		sendAmountSinceLastAck -= amount;
		if(sendAmountSinceLastAck < 0){
			Log.e(TAG, "Amount data to be decreased is over than its window.");
			sendAmountSinceLastAck = 0;
		}
	}

	/**
	 * determine if client's receiving window is full or not.
	 * @return boolean
	 */
	public boolean isClientWindowFull(){
		return (sendWindow > 0 && sendAmountSinceLastAck >= sendWindow) ||
				(sendWindow == 0 && sendAmountSinceLastAck > 65535);
	}

	/**
	 * append more data
	 * @param data Data
	 */
	public synchronized void addReceivedData(byte[] data){
		try {
			receivingStream.write(data);
		} catch (IOException e) {
			Log.e(TAG, e.toString());
		}
	}

	/**
	 * get all data received in the buffer and empty it.
	 * @return byte[]
	 */
	public synchronized byte[] getReceivedData(int maxSize){
		byte[] data = receivingStream.toByteArray();
		receivingStream.reset();
		if(data.length > maxSize){
			byte[] small = new byte[maxSize];
			System.arraycopy(data, 0, small, 0, maxSize);
			int len = data.length - maxSize;
			receivingStream.write(data, maxSize, len);
			data = small;
		}
		return data;
	}

	public int getAppUid() {
		return appUid;
	}

	public void setAppUid(int appUid) {
		this.appUid = appUid;
	}

	/**
	 * buffer has more data for vpn client
	 * @return boolean
	 */
	public boolean hasReceivedData(){
		return receivingStream.size() > 0;
	}

	/**
	 * set data to be sent to destination server
	 * @param data Data to be sent
	 * @return boolean Success or not
	 */
	public synchronized int setSendingData(ByteBuffer data) {
		final int remaining = data.remaining();
		sendingStream.write(data.array(), data.position(), data.remaining());
		return remaining;
	}

	int getSendingDataSize(){
		return sendingStream.size();
	}

	/**
	 * dequeue data for sending to server
	 * @return byte[]
	 */
	public synchronized byte[] getSendingData(){
		byte[] data = sendingStream.toByteArray();
		sendingStream.reset();
		return data;
	}
	/**
	 * buffer contains data for sending to destination server
	 * @return boolean
	 */
	public boolean hasDataToSend(){
		return sendingStream.size() > 0;
	}

	public int getDestIp() {
		return destIp;
	}

	public int getDestPort() {
		return destPort;
	}

	long getSendUnack() {
		return sendUnack;
	}

	void setSendUnack(long sendUnack) {
		this.sendUnack = sendUnack;
	}

	public long getSendNext() {
		return sendNext;
	}

	public void setSendNext(long sendNext) {
		this.sendNext = sendNext;
	}

	int getSendWindow() {
		return sendWindow;
	}

	public int getMaxSegmentSize() {
		return maxSegmentSize;
	}

	void setMaxSegmentSize(int maxSegmentSize) {
		this.maxSegmentSize = maxSegmentSize;
	}

	public boolean isConnected() {
		return isConnected;
	}

	public void setConnected(boolean isConnected) {
		this.isConnected = isConnected;
	}

	public int getSourceIp() {
		return sourceIp;
	}

	public int getSourcePort() {
		return sourcePort;
	}

	void setSendWindowSizeAndScale(int sendWindowSize, int sendWindowScale) {
		this.sendWindowSize = sendWindowSize;
		this.sendWindowScale = sendWindowScale;
		this.sendWindow = sendWindowSize * sendWindowScale;
	}

	int getSendWindowScale() {
		return sendWindowScale;
	}

	void setAcked(boolean isacked) {
		this.isacked = isacked;
	}

	public long getRecSequence() {
		return recSequence;
	}

	void setRecSequence(long recSequence) {
		this.recSequence = recSequence;
	}

	public AbstractSelectableChannel getChannel() {
		return channel;
	}

	public void setChannel(AbstractSelectableChannel channel) {
		this.channel = channel;
	}

	public boolean hasReceivedLastSegment() {
		return hasReceivedLastSegment;
	}
	public void setHasReceivedLastSegment(boolean hasReceivedLastSegment) {
		this.hasReceivedLastSegment = hasReceivedLastSegment;
	}
	public synchronized IPv4Header getLastIpHeader() {
		return lastIpHeader;
	}
	synchronized void setLastIpHeader(IPv4Header lastIpHeader) {
		this.lastIpHeader = lastIpHeader;
	}
	public synchronized TCPHeader getLastTcpHeader() {
		return lastTcpHeader;
	}
	synchronized void setLastTcpHeader(TCPHeader lastTcpHeader) {
		this.lastTcpHeader = lastTcpHeader;
	}

	public synchronized UDPHeader getLastUdpHeader() {
		return lastUdpHeader;
	}
	synchronized void setLastUdpHeader(UDPHeader lastUdpHeader) {
		this.lastUdpHeader = lastUdpHeader;
	}
	boolean isClosingConnection() {
		return closingConnection;
	}
	void setClosingConnection(boolean closingConnection) {
		this.closingConnection = closingConnection;
	}
	public boolean isDataForSendingReady() {
		return isDataForSendingReady;
	}
	public void setDataForSendingReady(boolean isDataForSendingReady) {
		this.isDataForSendingReady = isDataForSendingReady;
	}
	public void setUnackData(byte[] unackData) {
		this.unackData = unackData;
	}

	void setPacketCorrupted(boolean packetCorrupted) {
		this.packetCorrupted = packetCorrupted;
	}
	public void setResendPacketCounter(int resendPacketCounter) {
		this.resendPacketCounter = resendPacketCounter;
	}
	public int getTimestampSender() {
		return timestampSender;
	}
	void setTimestampSender(int timestampSender) {
		this.timestampSender = timestampSender;
	}
	public int getTimestampReplyto() {
		return timestampReplyto;
	}
	void setTimestampReplyto(int timestampReplyto) {
		this.timestampReplyto = timestampReplyto;
	}
	boolean isAckedToFin() {
		return ackedToFin;
	}

	public boolean isAbortingConnection() {
		return abortingConnection;
	}
	public void setAbortingConnection(boolean abortingConnection) {
		this.abortingConnection = abortingConnection;
	}
	public SelectionKey getSelectionKey() {
		return selectionkey;
	}
	public void setSelectionKey(SelectionKey selectionkey) {
		this.selectionkey = selectionkey;
	}

	public void cancelKey() {
		synchronized (this.selectionkey) {
			if (!this.selectionkey.isValid()) return;
			this.selectionkey.cancel();
		}
	}

	public void subscribeKey(int OP) {
		synchronized (this.selectionkey) {
			if (!this.selectionkey.isValid()) return;
			this.selectionkey.interestOps(this.selectionkey.interestOps() | OP);
		}
	}

	public void unsubscribeKey(int OP) {
		synchronized (this.selectionkey) {
			if (!this.selectionkey.isValid()) return;
			this.selectionkey.interestOps(this.selectionkey.interestOps() & ~OP);
		}
	}

	public void closeSession() {
		this.sessionCloser.closeSession(this);
	}

	public String getSessionKey() {
		return Session.getSessionKey(this.destIp, this.destPort, this.sourceIp, this.sourcePort);
	}

	public static String getSessionKey(int destIp, int destPort, int sourceIp, int sourcePort) {
		return PacketUtil.intToIPAddress(sourceIp) + ":" + sourcePort + "-" +
				PacketUtil.intToIPAddress(destIp) + ":" + destPort;
	}

	public int getBytesReceived() {
		return bytesReceived;
	}

	public void addBytesAndIncrementPacketsReceived(int bytesReceived) {
		Log.e(TAG, "addBytesReceived: before: " + this.bytesReceived + " after: " + (Integer.sum(this.bytesReceived, bytesReceived))  );

		this.bytesReceived += bytesReceived;
		addPacketsReceived();
	}

	public int getBytesSent() {
		return bytesSent;
	}

	//bytes sent from client to vpnServer
	public void addBytesSent(int bytesSent) {
		Log.e(TAG, "addBytesSent: before: " + this.bytesSent + " after: " + (Integer.sum(this.bytesSent, bytesSent))  );
		this.bytesSent += bytesSent;
	}

	public int getPacketsReceived() {
		return packetsReceived;
	}

	public void addPacketsReceived() {
		Log.e(TAG, "addPacketsReceived: before incr " + this.packetsReceived );

		this.packetsReceived++;
	}

	public int getPacketsSent() {
		return packetsSent;
	}

	public void addPacketsSent() {
		Log.e(TAG, "addPacketsSent: before incr " + this.packetsSent );
		this.packetsSent++;
	}

	@Override
	public String toString() {
		return "Session{" +
				"TAG='" + TAG + '\'' +
				", FROM->TO= " + PacketUtil.intToIPAddress(sourceIp)+":"+ sourcePort + " -> "+ PacketUtil.intToIPAddress(destIp) +":"+ destPort +
				", channel=" + channel +
				", destIp=" + destIp +
				", destPort=" + destPort +
				", sourceIp=" + sourceIp +
				", sourcePort=" + sourcePort +
				", appUid=" + appUid +
				", recSequence=" + recSequence +
				", sendUnack=" + sendUnack +
				", isacked=" + isacked +
				", sendNext=" + sendNext +
				", sendWindow=" + sendWindow +
				", sendWindowSize=" + sendWindowSize +
				", sendWindowScale=" + sendWindowScale +
				", sendAmountSinceLastAck=" + sendAmountSinceLastAck +
				", maxSegmentSize=" + maxSegmentSize +
				", isConnected=" + isConnected +
				", receivingStream Size=" + receivingStream.size() +
				", sendingStream=" + sendingStream +
				", hasReceivedLastSegment=" + hasReceivedLastSegment +
				", lastIpHeader=" + lastIpHeader +
				", lastTcpHeader=" + lastTcpHeader +
				", lastUdpHeader=" + lastUdpHeader +
				", closingConnection=" + closingConnection +
				", isDataForSendingReady=" + isDataForSendingReady +
				", unackData Length=" + Arrays.toString(unackData).length() +
				", packetCorrupted=" + packetCorrupted +
				", resendPacketCounter=" + resendPacketCounter +
				", timestampSender=" + timestampSender +
				", timestampReplyto=" + timestampReplyto +
				", ackedToFin=" + ackedToFin +
				", abortingConnection=" + abortingConnection +
				", selectionkey=" + selectionkey +
				", connectionStartTime=" + connectionStartTime +
				", sessionCloser=" + sessionCloser +
				'}';
	}
}
