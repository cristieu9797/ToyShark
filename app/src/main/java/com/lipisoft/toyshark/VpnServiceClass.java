package com.lipisoft.toyshark;

import android.annotation.SuppressLint;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.collection.ArraySet;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.lipisoft.toyshark.socket.IProtectSocket;
import com.lipisoft.toyshark.socket.SocketProtector;
import com.lipisoft.toyshark.util.GeneralUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;


public class VpnServiceClass extends VpnService implements IProtectSocket {
    private static final String TAG = "VpnServiceClass";

    private static final String START_VPN_ACTION = "tech.httptoolkit.android.START_VPN_ACTION";
    private static final String STOP_VPN_ACTION = "tech.httptoolkit.android.STOP_VPN_ACTION";

    private static final String VPN_STARTED_BROADCAST = "tech.httptoolkit.android.VPN_STARTED_BROADCAST";
    private static final String VPN_STOPPED_BROADCAST = "tech.httptoolkit.android.VPN_STOPPED_BROADCAST";
    private static final int NOTIFICATION_ID = 45456;

    private static VpnServiceClass currentService = null;
    private LocalBroadcastManager localBroadcastManager = null;

    private ProxyVpnRunnable vpnRunnable = null;
    private ParcelFileDescriptor vpnInterface = null;


    private static boolean isVpnActive() {
        if(currentService == null) {
            return false;
        } else if(currentService.isActive()){
            return true;
        } else {
            return false;
        }
    }


    @Override
    public void onCreate() {
        super.onCreate();
        currentService = this;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        currentService = null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        currentService = this;
        Log.i(TAG, "onStartCommand called");
        if (localBroadcastManager == null) {
            localBroadcastManager = LocalBroadcastManager.getInstance(this);
        }

        if(intent.getAction().equals(START_VPN_ACTION)) {
            boolean vpnStarted = false;

            try {
                if(isActive()) {
                    vpnStarted = restartVpn();
                } else {
                    vpnStarted = startVpn();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            if(vpnStarted) {
                // If the system briefly kills us for some reason (memory, the user, whatever) whilst
                // running the VPN, it should redeliver the VPN setup intent ASAP.
                return Service.START_REDELIVER_INTENT;
            } else {
                // We failed to start somehow - cleanup
                stopVpn();
            }
        } else if (intent.getAction().equals(STOP_VPN_ACTION)) {
            stopVpn();
        }

        // Shouldn't matter (we should've stopped already), but in general: if we're not running a
        // VPN, then the service doesn't need to be sticky.
        return Service.START_NOT_STICKY;
    }

    private void showServiceNotification(){
        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingActivityIntent = PendingIntent.getActivity(this, 0, activityIntent, 0);

        Intent serviceIntent = new Intent(this, VpnServiceClass.class);
        PendingIntent pendingServiceIntent = PendingIntent.getService(this, 1, serviceIntent, 0);


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            NotificationChannel notificationChannel = new NotificationChannel(
                    "NOTIFICATION_CHANNEL_ID",
                    "VPN Status",
                    NotificationManager.IMPORTANCE_DEFAULT
            );

            notificationManager.createNotificationChannel(notificationChannel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "NOTIFICATION_CHANNEL_ID")
                .setContentIntent(pendingActivityIntent)
                .setContentTitle("Interception active")
                .setContentText("content text for notif")
                .setSmallIcon(R.drawable.ic_android)
                .addAction(0, "stop interception", pendingServiceIntent);

        startForeground(NOTIFICATION_ID, builder.build());
    }

    private boolean startVpn() throws IOException {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Service.CONNECTIVITY_SERVICE);
        Network net = cm.getActiveNetwork();
        String defaultDnsServer = GeneralUtil.getDnsServer(cm, net);
        Log.e(TAG, "startVpn: DEFALT DNS " + defaultDnsServer );

        if (this.vpnInterface != null) return false;
        List<AppDescriptor> appDescriptors = MyApp.getInstance().getAppsList();
        Log.e("TAGDEBUG", "startVpn: " + appDescriptors.size() );

        Builder builder = new Builder()
                .addAddress("10.120.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("10.120.0.2")
//				.addDnsServer("8.8.8.8")
                .setMtu(1500)
                .setBlocking(true)
                .setSession("QuickTestVpn");


        //USEFUL FOR TESTING
//        for (AppDescriptor appDescriptor : appDescriptors) {
//
//			if(!appDescriptor.getPackageName().equals("com.example.apicalltestforvpnapp")) {
//				try {
//					builder.addDisallowedApplication(appDescriptor.getPackageName());
//				} catch (PackageManager.NameNotFoundException e) {
//					e.printStackTrace();
//				}
//			}
//
//		}

        try {
            builder.addDisallowedApplication(getPackageName());
        } catch (PackageManager.NameNotFoundException ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
        }
        vpnInterface = builder.establish();
        // establish() returns null if we no longer have permissions to establish the VPN somehow
        if (vpnInterface == null) {
            return false;
        } else {
            this.vpnInterface = vpnInterface;
        }

        showServiceNotification();
        localBroadcastManager.sendBroadcast(new Intent(VPN_STARTED_BROADCAST));
        SocketProtector.getInstance().setProtector(this);

        vpnRunnable = new ProxyVpnRunnable(vpnInterface, "", 234);

        new Thread(vpnRunnable, "Vpn thread").start();
        return true;

    }

    private boolean restartVpn() throws IOException {
        if(vpnRunnable != null) {
            vpnRunnable.stop();
            vpnRunnable = null;
        }

        try {
            vpnInterface.close();
            vpnInterface = null;
        } catch (IOException e) {
            e.printStackTrace();
        }

        stopForeground(true);
        return startVpn();
    }

    private void stopVpn(){
        Log.i(TAG, "VPN stopping...");

        if (vpnRunnable != null) {
            vpnRunnable.stop();
            vpnRunnable = null;
        }

        try {
            vpnInterface.close();
            vpnInterface = null;
        } catch (IOException e) {
            e.printStackTrace();
        }

        stopForeground(true);
        localBroadcastManager.sendBroadcast(new Intent(VPN_STOPPED_BROADCAST));
        stopSelf();

        currentService = null;
    }

    @Override
    public void onRevoke() {
        super.onRevoke();
        Log.i(TAG, "onRevoke called");
        stopVpn();
    }

    private boolean isActive(){
        return this.vpnInterface != null;
    }
}
