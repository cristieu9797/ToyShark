package com.lipisoft.toyshark;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.lipisoft.toyshark.ConnectionAnalysis.Collector;
import com.lipisoft.toyshark.ConnectionAnalysis.Detector;
import com.lipisoft.toyshark.ConnectionAnalysis.Report;
import com.lipisoft.toyshark.socket.ClientPacketWriter;
import com.lipisoft.toyshark.socket.SocketNIODataService;
import com.lipisoft.toyshark.transport.tcp.PacketHeaderException;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

class ProxyVpnRunnable implements Runnable {

    private static final String TAG = "ProxyVpnRunnable";
    private volatile boolean running = false;
    private static final int MAX_PACKET_LEN = 1500;


    private ParcelFileDescriptor vpnInterface;
    private String proxyHost;
    private Integer proxyPort;
    private FileInputStream vpnReadStream;
    private FileOutputStream vpnWriteStream;
    private ClientPacketWriter vpnPacketWriter;
    private Thread vpnPacketWriterThread, dataServiceThread;
    private SocketNIODataService nioService;

    private SessionManager manager;
    private SessionHandler handler;

    public ProxyVpnRunnable(ParcelFileDescriptor vpnInterface, String proxyHost, Integer proxyPort) throws IOException {
        this.vpnInterface = vpnInterface;
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;

        // Packets from device apps downstream, heading upstream via this VPN (primite de la aplicatii si urmeaza a fi procesate de VPN server)
        vpnReadStream = new FileInputStream(vpnInterface.getFileDescriptor());

        // Packets from upstream servers, received by this VPN (procesate de VPN server si trimise catre device network)
        vpnWriteStream = new FileOutputStream(vpnInterface.getFileDescriptor());
        vpnPacketWriter = new ClientPacketWriter(vpnWriteStream);
        vpnPacketWriterThread = new Thread(vpnPacketWriter);

        //Background service & task for non-blocking socket
        nioService = new SocketNIODataService(vpnPacketWriter);
        dataServiceThread = new Thread(nioService, "Socket NIO thread");

        manager = new SessionManager(nioService);
        handler = new SessionHandler(manager, nioService, vpnPacketWriter);


        new Timer().scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                //your methods
//                Detector.updateReportMap();
                Map<String, Session> table = manager.table;
                for(Map.Entry<String, Session> entry: table.entrySet()) {

                    String mapKey = entry.getKey();
                    Session session = entry.getValue();
//                    Log.e("TAGDEBUG", "run: " + mapKey + " " + session.isConnected() + " bytes received= " + session.getBytesReceived() + " packets received= " + session.getPacketsReceived()
//                            + " bytes sent= " + session.getBytesSent() + " packets sent= " + session.getPacketsSent() + " "
//                            + session.toString());
                }


//                for (Map.Entry<Integer, Report> entry : Detector.sReportMap.entrySet()) {
//                    Log.e("TAGDEBUG", "run: " + entry.getKey() + " " + entry.getValue());
//                }

            }
        }, 0, 10000);



    }



    @Override
    public void run() {
        if (running) {
            Log.w(TAG, "Vpn runnable started, but it's already running");
            return;
        }

        Log.i(TAG, "Vpn thread starting");

//        manager.setTcpPortRedirections(portRedirections);
        dataServiceThread.start();
        vpnPacketWriterThread.start();
        // Allocate the buffer for a single packet.
        ByteBuffer packet = ByteBuffer.allocate(MAX_PACKET_LEN);
        byte[] data;
        int length;
        running = true;

        while (running) {
            data = packet.array();

            try {
                length = vpnReadStream.read(data);
                if (length > 0) {
                    try {
                        packet.limit(length);
                        handler.handlePacket(packet);
                    } catch (PacketHeaderException e) {
                        Log.e(TAG, e.getMessage());
                    }

                    packet.clear();
                } else {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Log.d(TAG, "Failed to sleep: " + e.getMessage());
                    }

                }
            } catch (IOException e) {
                Log.d(TAG, "Failed to read stream: " + e.getMessage());
            }

        }

        Log.i(TAG, "Vpn thread shutting down");
    }

    public void stop(){
        if (running) {
            running = false;
            nioService.shutdown();
            dataServiceThread.interrupt();

            vpnPacketWriter.shutdown();
            vpnPacketWriterThread.interrupt();
        } else {
            Log.w(TAG, "Vpn runnable stopped, but it's not running");
        }
    }
}