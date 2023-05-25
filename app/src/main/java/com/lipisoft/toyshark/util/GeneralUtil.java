package com.lipisoft.toyshark.util;

import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.List;

public class GeneralUtil {

    public static String getDnsServer(ConnectivityManager cm, Network net) {
        LinkProperties props = cm.getLinkProperties(net);

        if(props != null) {
            List<InetAddress> dns_servers = props.getDnsServers();

            for(InetAddress addr : dns_servers) {
                // Get the first IPv4 DNS server
                if(addr instanceof Inet4Address) {
                    return addr.getHostAddress();
                }
            }
        }

        return null;
    }
}
