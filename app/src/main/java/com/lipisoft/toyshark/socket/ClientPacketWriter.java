package com.lipisoft.toyshark.socket;

import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * write packet data back to VPN client stream. This class is thread safe.
 * @author Borey Sao
 * Date: May 22, 2014
 */
public class ClientPacketWriter implements Runnable {

    private static final String TAG = "ClientPacketWriter";

    private final FileOutputStream clientWriter;

    private volatile boolean shutdown = false;
    private final BlockingDeque<byte[]> packetQueue = new LinkedBlockingDeque<>();

    public ClientPacketWriter(FileOutputStream clientWriter) {
        this.clientWriter = clientWriter;
    }

    //ce trimite VPN server catre client (ce este trimis aici practic este primit de noi fara sa il vedem)
    public void write(byte[] data) {
        Log.i(TAG, "Putting " + data.length + " bytes on the write queue");
        if (data.length > 30000) throw new Error("Packet too large");
        packetQueue.addLast(data);
    }

    public void shutdown() {
        this.shutdown = true;
    }

    @Override
    public void run() {
        while (!this.shutdown) {
            try {
                byte[] data = this.packetQueue.take();
                try {
                    this.clientWriter.write(data);
                } catch (IOException e) {
                    Log.e(TAG, "Error writing " + data.length + " bytes to the VPN");
                    e.printStackTrace();

                    this.packetQueue.addFirst(data); // Put the data back, so it's resent
                    Thread.sleep(10); // Add an arbitrary tiny pause, in case that helps
                }
            } catch (InterruptedException e) { }
        }
    }
}