package com.lipisoft.toyshark;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.lipisoft.toyshark.util.PacketUtil;

import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ConnectionListAdapter extends RecyclerView.Adapter<ConnectionViewHolder> {
    @NonNull
    private final List<Session> list;

    ConnectionListAdapter(@NonNull final List<Session> list) {
        this.list = list;
    }

    @Override
    public ConnectionViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        if (parent != null) {
            final View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.packet_info, parent, false);
            return new ConnectionViewHolder(view);
        }
        return null;
    }

    @Override
    public void onBindViewHolder(ConnectionViewHolder holder, int position) {
        if (holder != null) {
            final Session session = list.get(position);
            final TextView time = holder.getTime();
            final TextView protocol = holder.getProtocol();
            final TextView address = holder.getAddress();
            final TextView port = holder.getPort();
            final TextView packageName = holder.getPackageName();

            time.setText(new Date().toString());
            /*
            final byte protocolType = session.getProtocol();
            if (protocolType == TCP) {
                protocol.setText(R.string.tcp);
            } else if (protocolType == UDP) {
                protocol.setText(R.string.udp);
            }
            */
            protocol.setText(String.valueOf(session.getLastIpHeader().getProtocol())); // all sessions seem to be TCP
            address.setText(PacketUtil.intToIPAddress(session.getDestIp()));
            port.setText(String.format(Locale.getDefault(), "%d", session.getDestPort()));
            packageName.setText("" + session.getAppUid());
        }
    }

    @Override
    public int getItemCount() {
        return list.size();
    }

}