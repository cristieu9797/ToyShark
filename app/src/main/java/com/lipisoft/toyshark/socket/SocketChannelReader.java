package com.lipisoft.toyshark.socket;

import androidx.annotation.NonNull;
import android.util.Log;

import com.lipisoft.toyshark.Session;
import com.lipisoft.toyshark.SessionManager;
import com.lipisoft.toyshark.network.ip.IPPacketFactory;
import com.lipisoft.toyshark.network.ip.IPv4Header;
import com.lipisoft.toyshark.packetRebuild.PCapFileWriter;
import com.lipisoft.toyshark.transport.tcp.PacketHeaderException;
import com.lipisoft.toyshark.transport.tcp.TCPHeader;
import com.lipisoft.toyshark.transport.tcp.TCPPacketFactory;
import com.lipisoft.toyshark.transport.udp.UDPHeader;
import com.lipisoft.toyshark.transport.udp.UDPPacketFactory;
import com.lipisoft.toyshark.util.PacketUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.util.Date;

/**
 * Takes a session, and reads all available upstream data back into it.
 *
 * Used by the NIO thread, and run synchronously as part of that non-blocking loop.
 */
class SocketChannelReader {
	private final String TAG = "SocketChannelReader";
	private final ClientPacketWriter writer;

	public SocketChannelReader(ClientPacketWriter writer) {
		this.writer = writer;
	}

	//data read from final destination server by the VPN server
	public void read(Session session) {
		AbstractSelectableChannel channel = session.getChannel();

		Log.d(TAG, "Reading from session " + session);

		if(channel instanceof SocketChannel) {
			readTCP(session);
		} else if(channel instanceof DatagramChannel){
			readUDP(session);
		} else {
			return;
		}

		// Resubscribe to reads, so that we're triggered again if more data arrives later.
		session.subscribeKey(SelectionKey.OP_READ);

		if(session.isAbortingConnection()) {
			Log.d(TAG,"removing aborted connection -> "+ session);
			session.cancelKey();
			if (channel instanceof SocketChannel){
				try {
					SocketChannel socketChannel = (SocketChannel) channel;
					if (socketChannel.isConnected()) {
						socketChannel.close();
					}
				} catch (IOException e) {
					Log.e(TAG, e.toString());
				}
			} else {
				try {
					DatagramChannel datagramChannel = (DatagramChannel) channel;
					if (datagramChannel.isConnected()) {
						datagramChannel.close();
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			session.closeSession();
		}
	}

	private void readTCP(@NonNull Session session) {
		if(session.isAbortingConnection()){
			return;
		}

		SocketChannel channel = (SocketChannel) session.getChannel();
		ByteBuffer buffer = ByteBuffer.allocate(DataConst.MAX_RECEIVE_BUFFER_SIZE);
		int len;

		try {
			do {
				if(!session.isClientWindowFull()) {
					len = channel.read(buffer);
					if(len > 0) { //-1 mean it reach the end of stream
						sendToRequester(buffer, len, session);
						buffer.clear();
					} else if(len == -1) {
						Log.d(TAG,"End of data from remote server, will send FIN to client");
						Log.d(TAG,"send FIN to: " + session);
						sendFin(session);
						session.setAbortingConnection(true);
					}
				} else {
					Log.e(TAG,"*** client window is full, now pause for " + session);
					break;
				}
			} while(len > 0);
		}catch(NotYetConnectedException e){
			Log.e(TAG,"socket not connected");
		}catch(ClosedByInterruptException e){
			Log.e(TAG,"ClosedByInterruptException reading SocketChannel: "+ e.getMessage());
		}catch(ClosedChannelException e){
			Log.e(TAG,"ClosedChannelException reading SocketChannel: "+ e.getMessage());
		} catch (IOException e) {
			Log.e(TAG,"Error reading data from SocketChannel: "+ e.getMessage());
			session.setAbortingConnection(true);
		}
	}

	private void sendToRequester(ByteBuffer buffer, int dataSize, @NonNull Session session){
		//last piece of data is usually smaller than MAX_RECEIVE_BUFFER_SIZE
		if(dataSize < DataConst.MAX_RECEIVE_BUFFER_SIZE)
			session.setHasReceivedLastSegment(true);
		else
			session.setHasReceivedLastSegment(false);

		buffer.limit(dataSize);
		buffer.flip();
		// TODO should allocate new byte array?
		byte[] data = new byte[dataSize];
		System.arraycopy(buffer.array(), 0, data, 0, dataSize);
		session.addReceivedData(data);
		//pushing all data to vpn client
		while(session.hasReceivedData()){
			pushDataToClient(session);
		}
	}
	/**
	 * create packet data and send it to VPN client
	 * @param session Session
	 */
	private void pushDataToClient(@NonNull Session session){
		Log.e(TAG, "pushDataToClient: " + session);
		if(!session.hasReceivedData()){
			//no data to send
			Log.d(TAG,"no data for vpn client");
		}

		IPv4Header ipHeader = session.getLastIpHeader();
		TCPHeader tcpheader = session.getLastTcpHeader();
		// TODO What does 60 mean?
		int max = session.getMaxSegmentSize() - 60;

		if(max < 1) {
			max = 1024;
		}

		byte[] packetBody = session.getReceivedData(max);
		if(packetBody != null && packetBody.length > 0) {
			long unAck = session.getSendNext();
			long nextUnAck = session.getSendNext() + packetBody.length;
			session.setSendNext(nextUnAck);
			//we need this data later on for retransmission
			session.setUnackData(packetBody);
			session.setResendPacketCounter(0);

			byte[] data = TCPPacketFactory.createResponsePacketData(ipHeader,
					tcpheader, packetBody, session.hasReceivedLastSegment(),
					session.getRecSequence(), unAck,
					session.getTimestampSender(), session.getTimestampReplyto());

			//TODO: add data.length to dataReceivedFromOutside field in the Session
			session.addBytesAndIncrementPacketsReceived(data.length);
			writer.write(data);
		}
	}
	private void sendFin(Session session){
		final IPv4Header ipHeader = session.getLastIpHeader();
		final TCPHeader tcpheader = session.getLastTcpHeader();
		final byte[] data = TCPPacketFactory.createFinData(ipHeader, tcpheader,
				session.getSendNext(), session.getRecSequence(),
				session.getTimestampSender(), session.getTimestampReplyto());

		writer.write(data);
	}

	private void readUDP(Session session){
		DatagramChannel channel = (DatagramChannel) session.getChannel();
		ByteBuffer buffer = ByteBuffer.allocate(DataConst.MAX_RECEIVE_BUFFER_SIZE);
		int len;

		try {
			do{
				if(session.isAbortingConnection()){
					break;
				}
				len = channel.read(buffer);
				if(len > 0){
					Date date = new Date();
					long responseTime = date.getTime() - session.connectionStartTime;

					buffer.limit(len);
					buffer.flip();
					//create UDP packet
					byte[] data = new byte[len];
					System.arraycopy(buffer.array(),0, data, 0, len);
					byte[] packetData = UDPPacketFactory.createResponsePacket(
							session.getLastIpHeader(), session.getLastUdpHeader(), data);

					//write to client
					writer.write(packetData);

					Log.d(TAG,"SDR: sent " + len + " bytes to UDP client, packetData.length: "
							+ packetData.length);
					buffer.clear();

					try {
						final ByteBuffer stream = ByteBuffer.wrap(packetData);
						IPv4Header ip = IPPacketFactory.createIPv4Header(stream);
						UDPHeader udp = UDPPacketFactory.createUDPHeader(stream);
						String str = PacketUtil.getUDPoutput(ip, udp);
						Log.d(TAG,"++++++ SD: packet sending to client ++++++++");
						Log.d(TAG,"got response time: " + responseTime);
						Log.d(TAG,str);
						Log.d(TAG,"++++++ SD: end sending packet to client ++++");
					} catch (PacketHeaderException e) {
						e.printStackTrace();
					}
				}
			} while(len > 0);
		}catch(NotYetConnectedException ex){
			Log.e(TAG,"failed to read from unconnected UDP socket");
		} catch (IOException e) {
			e.printStackTrace();
			Log.e(TAG,"Failed to read from UDP socket, aborting connection");
			session.setAbortingConnection(true);
		}
	}
}
