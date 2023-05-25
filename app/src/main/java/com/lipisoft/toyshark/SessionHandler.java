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

import static android.system.OsConstants.IPPROTO_TCP;
import static android.system.OsConstants.IPPROTO_UDP;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.lipisoft.toyshark.ConnectionAnalysis.Collector;
import com.lipisoft.toyshark.ConnectionAnalysis.Detector;
import com.lipisoft.toyshark.ConnectionAnalysis.Report;
import com.lipisoft.toyshark.network.ip.IPPacketFactory;
import com.lipisoft.toyshark.network.ip.IPv4Header;
import com.lipisoft.toyshark.socket.ClientPacketWriter;
import com.lipisoft.toyshark.socket.SocketData;
import com.lipisoft.toyshark.socket.SocketNIODataService;
import com.lipisoft.toyshark.transport.icmp.ICMPPacket;
import com.lipisoft.toyshark.transport.icmp.ICMPPacketFactory;
import com.lipisoft.toyshark.transport.tcp.PacketHeaderException;
import com.lipisoft.toyshark.transport.tcp.TCPHeader;
import com.lipisoft.toyshark.transport.tcp.TCPPacketFactory;
import com.lipisoft.toyshark.transport.ITransportHeader;
import com.lipisoft.toyshark.transport.udp.UDPHeader;
import com.lipisoft.toyshark.transport.udp.UDPPacketFactory;
import com.lipisoft.toyshark.util.PacketUtil;

import androidx.annotation.NonNull;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Build;
import android.util.Log;

/**
 * handle VPN client request and response. it create a new session for each VPN client.
 * @author Borey Sao
 * Date: May 22, 2014
 */
class SessionHandler {
	private static final String TAG = "SessionHandler";


	private final SessionManager manager;
	private final SocketNIODataService nioService;
	private final ClientPacketWriter writer;

	private final ExecutorService pingThreadpool;


	public SessionHandler(SessionManager manager, SocketNIODataService nioService, ClientPacketWriter writer) {
		this.manager = manager;
		this.nioService = nioService;
		this.writer = writer;

		// Pool of threads to synchronously proxy ICMP ping requests in the background. We need to
		// carefully limit these, or a ping flood can cause us big big problems.
		this.pingThreadpool = new ThreadPoolExecutor(
				1, 20, // 1 - 20 parallel pings max
				60L, TimeUnit.SECONDS,
				new SynchronousQueue<Runnable>(),
				new ThreadPoolExecutor.DiscardPolicy() // Replace running pings if there's too many
		);
	}

	private void handleUDPPacket(ByteBuffer clientPacketData, IPv4Header ipHeader, UDPHeader udpheader){
		int dataLength = clientPacketData.limit() - clientPacketData.position();
		int sourceIP = ipHeader.getSourceIP();
		int destinationIP = ipHeader.getDestinationIP();
		int sourcePort = udpheader.getSourcePort();
		int destinationPort = udpheader.getDestinationPort();

		int uid = getUidQ(PacketUtil.intToIPAddress(ipHeader.getSourceIP()), udpheader.getSourcePort(), PacketUtil.intToIPAddress(ipHeader.getDestinationIP()), udpheader.getDestinationPort(), IPPROTO_UDP);
		Log.e("TAGDEBUG", "handleUDPPacket: route: " + PacketUtil.intToIPAddress(ipHeader.getSourceIP())+":"+ udpheader.getSourcePort() + " -> "+ PacketUtil.intToIPAddress(ipHeader.getDestinationIP()) +":"+ udpheader.getDestinationPort() + "    limit, pos " + clientPacketData.limit() + " " +  clientPacketData.position()  + " dataLen=" + dataLength + " " + udpheader.toString());
		Log.e("TAGDEBUG", "handleUDPPacket: TOTAL= " + ipHeader.getTotalLength() + " ipHead LEN= " + ipHeader.getIPHeaderLength() + " udpHead LEN= " + udpheader.getLength() + " UID= " + uid + " " + MyApp.getInstance().getAppByUid(uid) );
		if (udpheader.getDestinationPort() == 53 || udpheader.getSourcePort() == 53) {
			Log.i(TAG, "Found a DNS Packet");
			Log.i(TAG, "Dns payload:" + clientPacketData.toString());
		}

		int udpDataSize = ipHeader.getTotalLength() - ipHeader.getIPHeaderLength() - udpheader.getLength();
//		int dataOffsetTest = tcpheader.getDataOffset() + tcpheader.getTCPHeaderLength();
		int dataOffsetTest = ipHeader.getIPHeaderLength() + udpheader.getLength();

		if(udpDataSize > 10) {
			Log.e(TAG, "handleUDPPacket: AFTER -> udpDataSize=" + udpDataSize + " dataOffset=" + dataOffsetTest );

			try {
				HttpRequestHeaderParser.parseHttpRequestHeader(clientPacketData.array(), dataOffsetTest,
						udpDataSize);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		Session session = manager.getSession(
				ipHeader.getDestinationIP(), udpheader.getDestinationPort(),
				ipHeader.getSourceIP(), udpheader.getSourcePort()
		);

		if (session == null){
			session = manager.createNewUDPSession(
					ipHeader.getDestinationIP(), udpheader.getDestinationPort(),
					ipHeader.getSourceIP(), udpheader.getSourcePort()
			);
		}

		if(session == null){
			return;
		}

		synchronized (session) {
			session.setLastIpHeader(ipHeader);
			session.setLastUdpHeader(udpheader);
			int len = manager.addClientData(clientPacketData, session);
			session.setDataForSendingReady(true);

			// Ping the NIO thread to write this, when the session is next writable
			session.subscribeKey(SelectionKey.OP_WRITE);
			nioService.refreshSelect(session);

			Log.d(TAG,"Added " + len + " bytes of UDP data, ready to send");
		}

		manager.keepSessionAlive(session);
	}

	//packet received from device app's
	private void handleTCPPacket(ByteBuffer clientPacketData, IPv4Header ipHeader, TCPHeader tcpheader){
		int dataLength = clientPacketData.limit() - clientPacketData.position();
		int sourceIP = ipHeader.getSourceIP();
		int destinationIP = ipHeader.getDestinationIP();
		int sourcePort = tcpheader.getSourcePort();
		int destinationPort = tcpheader.getDestinationPort();



		int uid = getUidQ(PacketUtil.intToIPAddress(ipHeader.getSourceIP()), tcpheader.getSourcePort(), PacketUtil.intToIPAddress(ipHeader.getDestinationIP()), tcpheader.getDestinationPort(), IPPROTO_TCP);
		Log.e("TAGDEBUG", "handleTCPPacket: route: " + PacketUtil.intToIPAddress(ipHeader.getSourceIP())+":"+ tcpheader.getSourcePort() + " -> "+ PacketUtil.intToIPAddress(ipHeader.getDestinationIP()) +":"+ tcpheader.getDestinationPort() + "    limit, pos " + clientPacketData.limit() + " " +  clientPacketData.position()  + " dataLen=" + dataLength + " " + tcpheader.toString());
		Log.e("TAGDEBUG", "handleTCPPacket: TOTAL= " + ipHeader.getTotalLength() + " ipHead LEN= " + ipHeader.getIPHeaderLength() + " tcpHead LEN= " + tcpheader.getTCPHeaderLength() + " UID= " + uid + " " + MyApp.getInstance().getAppByUid(uid) );

		if (tcpheader.getDestinationPort() == 53 || tcpheader.getSourcePort() == 53) {
			Log.i(TAG, "Found a DNS Packet");
			Log.i(TAG, "Dns payload:" + clientPacketData.toString());
		}

		int tcpDataSize = ipHeader.getTotalLength() - ipHeader.getIPHeaderLength() - tcpheader.getTCPHeaderLength();
//		int dataOffsetTest = tcpheader.getDataOffset() + tcpheader.getTCPHeaderLength();
		int dataOffsetTest = ipHeader.getIPHeaderLength() + tcpheader.getTCPHeaderLength();

		if(tcpDataSize > 10) {
			Log.e(TAG, "handleTCPPacket: AFTER -> tcpDataSize=" + tcpDataSize + " dataOffset=" + dataOffsetTest );

			try {
				HttpRequestHeaderParser.parseHttpRequestHeader(clientPacketData.array(), dataOffsetTest,
						tcpDataSize);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		if(tcpheader.isSYN()) {
			//3-way handshake + create new session
			//set windows size and scale, set reply time in options
			replySynAck(ipHeader,tcpheader);
		} else if(tcpheader.isACK()) {
			String key = Session.getSessionKey(destinationIP, destinationPort, sourceIP, sourcePort);
			Session session = manager.getSessionByKey(key);

			if(session == null) {
				if (tcpheader.isFIN()) {
					sendLastAck(ipHeader, tcpheader);
				} else if (!tcpheader.isRST()) {
					sendRstPacket(ipHeader, tcpheader, dataLength);
				}
				else {
					Log.e(TAG,"**** ==> Session not found: " + key);
				}
				return;
			}

			synchronized (session) {
				session.setLastIpHeader(ipHeader);
				session.setLastTcpHeader(tcpheader);
				//any data from client?
				if (dataLength > 0) {
					//accumulate data from client
					if (session.getRecSequence() == 0 || tcpheader.getSequenceNumber() >= session.getRecSequence()) {
						int addedLength = manager.addClientData(clientPacketData, session);
						//send ack to client only if new data was added
						sendAck(ipHeader, tcpheader, addedLength, session);
					} else {
						sendAckForDisorder(ipHeader, tcpheader, dataLength, session);
					}
				} else {
					//an ack from client for previously sent data
					acceptAck(tcpheader, session);

					if (session.isClosingConnection()) {
						sendFinAck(ipHeader, tcpheader, session);
					} else if (session.isAckedToFin() && !tcpheader.isFIN()) {
						//the last ACK from client after FIN-ACK flag was sent
						manager.closeSession(destinationIP, destinationPort, sourceIP, sourcePort);
						Log.d(TAG, "got last ACK after FIN, session is now closed.");
					}
				}
				//received the last segment of data from vpn client
				if (tcpheader.isPSH()) {
					// Tell the NIO thread to immediately send data to the destination
					pushDataToDestination(session, tcpheader);
				} else if (tcpheader.isFIN()) {
					//fin from vpn client is the last packet
					//ack it
					Log.d(TAG, "FIN from vpn client, will ack it.");
					ackFinAck(ipHeader, tcpheader, session);
				} else if (tcpheader.isRST()) {
					resetConnection(ipHeader, tcpheader);
				}

				if (!session.isClientWindowFull() && !session.isAbortingConnection()) {
					manager.keepSessionAlive(session);
				}
			}
		} else if(tcpheader.isFIN()){
			//case client sent FIN without ACK
			Session session = manager.getSession(destinationIP, destinationPort, sourceIP, sourcePort);
			if(session == null)
				ackFinAck(ipHeader, tcpheader, null);
			else
				manager.keepSessionAlive(session);

		} else if(tcpheader.isRST()){
			resetConnection(ipHeader, tcpheader);
		} else {
			Log.d(TAG,"unknown TCP flag");
			String str1 = PacketUtil.getOutput(ipHeader, tcpheader, clientPacketData.array());
			Log.d(TAG,">>>>>>>> Received from client <<<<<<<<<<");
			Log.d(TAG,str1);
			Log.d(TAG,">>>>>>>>>>>>>>>>>>>end receiving from client>>>>>>>>>>>>>>>>>>>>>");
		}
	}

	/**
	 * handle each packet from each vpn client
	 * @param stream ByteBuffer to be read
	 */
	public void handlePacket(@NonNull ByteBuffer stream) throws PacketHeaderException {
		//handle ip packet received
		final byte[] rawPacket = new byte[stream.limit()];
		stream.get(rawPacket, 0, stream.limit());
		stream.rewind();

		final IPv4Header ipHeader = IPPacketFactory.createIPv4Header(stream);

		//TODO: block outgoing connections to specific addresses or on specific ports, you just need to throw away the packets after you receive them

		final ITransportHeader transportHeader;
		if(ipHeader.getProtocol() == 6) {
			transportHeader = TCPPacketFactory.createTCPHeader(stream);
		} else if(ipHeader.getProtocol() == 17) {
			transportHeader = UDPPacketFactory.createUDPHeader(stream);
		} else {
			Log.e(TAG, "******===> Unsupported protocol: " + ipHeader.getProtocol());
			return;
		}

		if (transportHeader instanceof TCPHeader) {
			handleTCPPacket(stream, ipHeader, (TCPHeader) transportHeader);
		} else if (ipHeader.getProtocol() == 17){
			handleUDPPacket(stream, ipHeader, (UDPHeader) transportHeader);
		}
//		else if(ipHeader.getProtocol() == 1) {
//			handleICMPPacket(stream, ipHeader);
//		}
	}

	private void sendRstPacket(IPv4Header ip, TCPHeader tcp, int dataLength){
		byte[] data = TCPPacketFactory.createRstData(ip, tcp, dataLength);

		writer.write(data);
		Log.d(TAG,"Sent RST Packet to client with dest => " +
				PacketUtil.intToIPAddress(ip.getDestinationIP()) + ":" +
				tcp.getDestinationPort());
	}

	private void sendLastAck(IPv4Header ip, TCPHeader tcp){
		byte[] data = TCPPacketFactory.createResponseAckData(ip, tcp, tcp.getSequenceNumber()+1);

		writer.write(data);
		Log.d(TAG,"Sent last ACK Packet to client with dest => " +
				PacketUtil.intToIPAddress(ip.getDestinationIP()) + ":" +
				tcp.getDestinationPort());
	}

	private void ackFinAck(IPv4Header ip, TCPHeader tcp, Session session){
		long ack = tcp.getSequenceNumber() + 1;
		long seq = tcp.getAckNumber();
		byte[] data = TCPPacketFactory.createFinAckData(ip, tcp, ack, seq, true, true);

		writer.write(data);
		if(session != null){
			session.cancelKey();
			manager.closeSession(session);
			Log.d(TAG,"ACK to client's FIN and close session => "+PacketUtil.intToIPAddress(ip.getDestinationIP())+":"+tcp.getDestinationPort()
					+"-"+PacketUtil.intToIPAddress(ip.getSourceIP())+":"+tcp.getSourcePort());
		}
	}
	private void sendFinAck(IPv4Header ip, TCPHeader tcp, Session session){
		final long ack = tcp.getSequenceNumber();
		final long seq = tcp.getAckNumber();
		final byte[] data = TCPPacketFactory.createFinAckData(ip, tcp, ack, seq,true,false);
		final ByteBuffer stream = ByteBuffer.wrap(data);

		writer.write(data);
		Log.d(TAG,"00000000000 FIN-ACK packet data to vpn client 000000000000");
		IPv4Header vpnip = null;
		try {
			vpnip = IPPacketFactory.createIPv4Header(stream);
		} catch (PacketHeaderException e) {
			e.printStackTrace();
		}

		TCPHeader vpntcp = null;
		try {
			if (vpnip != null)
				vpntcp = TCPPacketFactory.createTCPHeader(stream);
		} catch (PacketHeaderException e) {
			e.printStackTrace();
		}

		if(vpnip != null && vpntcp != null){
			String sout = PacketUtil.getOutput(vpnip, vpntcp, data);
			Log.d(TAG,sout);
		}
		Log.d(TAG,"0000000000000 finished sending FIN-ACK packet to vpn client 000000000000");

		session.setSendNext(seq + 1);
		//avoid re-sending it, from here client should take care the rest
		session.setClosingConnection(false);
	}

	// send the request to the final destination
	private void pushDataToDestination(Session session, TCPHeader tcp){
		session.setDataForSendingReady(true);
		session.setTimestampReplyto(tcp.getTimeStampSender());
		session.setTimestampSender((int)System.currentTimeMillis());

		// Ping the NIO thread to write this, when the session is next writable
		session.subscribeKey(SelectionKey.OP_WRITE);
		nioService.refreshSelect(session);

		Log.d(TAG,"set data ready for sending to dest, bg will do it. data size: "
				+ session.getSendingDataSize());
	}

	/**
	 * send acknowledgment packet to VPN client
	 * @param ipheader IP Header
	 * @param tcpheader TCP Header
	 * @param acceptedDataLength Data Length
	 * @param session Session
	 */
	private void sendAck(IPv4Header ipheader, TCPHeader tcpheader, int acceptedDataLength, Session session){
		long acknumber = session.getRecSequence() + acceptedDataLength;
		Log.d(TAG,"sent ack, ack# "+session.getRecSequence()+" + "+acceptedDataLength+" = "+acknumber);
		session.setRecSequence(acknumber);
		byte[] data = TCPPacketFactory.createResponseAckData(ipheader, tcpheader, acknumber);

		session.addBytesSent(data.length);
		session.addPacketsSent();
		writer.write(data);
	}

	private void sendAckForDisorder(IPv4Header ipHeader, TCPHeader tcpheader, int acceptedDataLength, Session session) {
		long ackNumber = tcpheader.getSequenceNumber() + acceptedDataLength;
		Log.d(TAG,"sent ack disorder, ack# " + tcpheader.getSequenceNumber() +
				" + " + acceptedDataLength + " = " + ackNumber);
		byte[] data = TCPPacketFactory.createResponseAckData(ipHeader, tcpheader, ackNumber);
		session.addBytesSent(data.length);
		session.addPacketsSent();
		writer.write(data);
	}

	/**
	 * acknowledge a packet and adjust the receiving window to avoid congestion.
	 * @param tcpHeader TCP Header
	 * @param session Session
	 */
	private void acceptAck(TCPHeader tcpHeader, Session session){
		boolean isCorrupted = PacketUtil.isPacketCorrupted(tcpHeader);
		session.setPacketCorrupted(isCorrupted);
		if(isCorrupted){
			Log.e(TAG,"prev packet was corrupted, last ack# " + tcpHeader.getAckNumber());
		}
		if(tcpHeader.getAckNumber() > session.getSendUnack() ||
				tcpHeader.getAckNumber() == session.getSendNext()){
			session.setAcked(true);

			if(tcpHeader.getWindowSize() > 0){
				session.setSendWindowSizeAndScale(tcpHeader.getWindowSize(), session.getSendWindowScale());
			}
			session.setSendUnack(tcpHeader.getAckNumber());
			session.setRecSequence(tcpHeader.getSequenceNumber());
			session.setTimestampReplyto(tcpHeader.getTimeStampSender());
			session.setTimestampSender((int) System.currentTimeMillis());
		} else {
			Log.d(TAG,"Not Accepting ack# "+tcpHeader.getAckNumber() +" , it should be: "+session.getSendNext());
			Log.d(TAG,"Prev sendUnack: "+session.getSendUnack());
			session.setAcked(false);
		}
	}
	/**
	 * set connection as aborting so that background worker will close it.
	 * @param ip IP
	 * @param tcp TCP
	 */
	private void resetConnection(IPv4Header ip, TCPHeader tcp){
		Session session = manager.getSession(
				ip.getDestinationIP(), tcp.getDestinationPort(),
				ip.getSourceIP(), tcp.getSourcePort()
		);
		if(session != null){
			synchronized (session) {
				session.setAbortingConnection(true);
			}
		}
	}

	/**
	 * create a new client's session and SYN-ACK packet data to respond to client
	 * @param ip IP
	 * @param tcp TCP
	 */
	private void replySynAck(IPv4Header ip, TCPHeader tcp){
		int bytesSentInSyn = ip.getTotalLength();
		ip.setIdentification(0);
		Packet packet = TCPPacketFactory.createSynAckPacketData(ip, tcp);
		Log.e(TAG, "replySynAck: " + packet.getIpHeader().getTotalLength() + " " + packet.getBuffer().length );
		TCPHeader tcpheader = (TCPHeader) packet.getTransportHeader();

		Session session = manager.createNewTCPSession(
				ip.getDestinationIP(), tcp.getDestinationPort(),
				ip.getSourceIP(), tcp.getSourcePort()
		);
		if(session == null)
			return;

		synchronized (session) {
			//add the bytes that we sent for the SYN tcp call + 1 packet
			session.addBytesSent(bytesSentInSyn);
			session.addPacketsSent();

			int windowScaleFactor = (int) Math.pow(2, tcpheader.getWindowScale());
			session.setSendWindowSizeAndScale(tcpheader.getWindowSize(), windowScaleFactor);
			Log.d(TAG, "send-window size: " + session.getSendWindow());
			session.setMaxSegmentSize(tcpheader.getMaxSegmentSize());
			session.setSendUnack(tcpheader.getSequenceNumber());
			session.setSendNext(tcpheader.getSequenceNumber() + 1);
			//client initial sequence has been incremented by 1 and set to ack
			session.setRecSequence(tcpheader.getAckNumber());

			Log.e(TAG, "replySynAck: " + tcpheader.toString() );
			session.addBytesAndIncrementPacketsReceived(packet.getBuffer().length);


			writer.write(packet.getBuffer());
			Log.d(TAG,"Send SYN-ACK to client");
		}
	}

	public int getUidQ(String saddr, int sport, String daddr, int dport, int protocol) {
//		Log.e("TAGDEBUG", "getUidQ : " +saddr + " " + sport + " " + daddr + " " + dport + " protocol: " + (protocol == IPPROTO_TCP ? "TCP" : "UDP")  );

//
		InetSocketAddress local = new InetSocketAddress(saddr, sport);
		InetSocketAddress remote = new InetSocketAddress(daddr, dport);



//		InetSocketAddress remoteInetSocketAddress = new InetSocketAddress(finalHost, srcPort);
//		InetSocketAddress localInetSocketAddress = new InetSocketAddress(1234);

		ConnectivityManager connectivityManager = (ConnectivityManager) MyApp.getInstance().getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
		int uid = 0;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			uid = connectivityManager.getConnectionOwnerUid(protocol, local, remote);
		}
//		Method method = null;
//		try {
//			method = ConnectivityManager.class.getMethod("getConnectionOwnerUid", int.class, InetSocketAddress.class, InetSocketAddress.class);
//		} catch (NoSuchMethodException e) {
//			e.printStackTrace();
//		}
//		int uid = 0;
//		try {
//			uid = (int) method.invoke(connectivityManager, IPPROTO_TCP, local, remote);
//		} catch (IllegalAccessException e) {
//			e.printStackTrace();
//		} catch (InvocationTargetException e) {
//			e.printStackTrace();
//		}
//		Log.e(TAG, "getUidQ: " + uid + " uidTest:" + uidTest );
		///

//		Log.i(TAG, "Get uid=" + uid);
		return uid;
	}

	private void handleICMPPacket(ByteBuffer clientPacketData, final IPv4Header ipHeader) throws PacketHeaderException {
		final ICMPPacket requestPacket = ICMPPacketFactory.parseICMPPacket(clientPacketData);
		Log.d(TAG, "Got an ICMP ping packet, type " + requestPacket.toString());

		if (requestPacket.type == ICMPPacket.DESTINATION_UNREACHABLE_TYPE) {
			// This is a packet from the phone, telling somebody that a destination is unreachable.
			// Might be caused by issues on our end, but it's unclear what kind of issues. Regardless,
			// we can't send ICMP messages ourselves or react usefully, so we drop these silently.
			return;
		} else if (requestPacket.type != ICMPPacket.ECHO_REQUEST_TYPE) {
			// We only actually support outgoing ping packets. Loudly drop anything else:
			throw new PacketHeaderException(
					"Unknown ICMP type (" + requestPacket.type + "). Only echo requests are supported"
			);
		}

		pingThreadpool.execute(new Runnable() {
			@Override
			public void run() {
				try {
					if (!isReachable(PacketUtil.intToIPAddress(ipHeader.getDestinationIP()))) {
						Log.d(TAG, "Failed ping, ignoring");
						return;
					}

					ICMPPacket response = ICMPPacketFactory.buildSuccessPacket(requestPacket);

					// Flip the address
					int destination = ipHeader.getDestinationIP();
					int source = ipHeader.getSourceIP();
					ipHeader.setSourceIP(destination);
					ipHeader.setDestinationIP(source);

					byte[] responseData = ICMPPacketFactory.packetToBuffer(ipHeader, response);

					Log.d(TAG, "Successful ping response");
					writer.write(responseData);
				} catch (PacketHeaderException e) {
					Log.w(TAG, "Handling ICMP failed(PacketHeaderException) with " + e.getMessage());
					return;
				} catch (Exception e) {
					Log.w(TAG, "Handling ICMP failed(IOException) with " + e.getMessage());
					return;
				}
			}

			private boolean isReachable(String ipAddress) {
				try {
					return InetAddress.getByName(ipAddress).isReachable(10000);
				} catch (IOException e) {
					return false;
				}
			}
		});
	}

}//end class
