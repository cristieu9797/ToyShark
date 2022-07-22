package com.lipisoft.toyshark;

import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

public class ConnectionViewHolder extends RecyclerView.ViewHolder {
    private final TextView time;
    private final TextView protocol;
    private final TextView address;
    private final TextView port;
    private final TextView packageName;

    ConnectionViewHolder(View itemView) {
        super(itemView);
        time = itemView.findViewById(R.id.time);
        protocol = itemView.findViewById(R.id.protocol);
        address = itemView.findViewById(R.id.address);
        port = itemView.findViewById(R.id.port);
        packageName = itemView.findViewById(R.id.package_name);
    }

    public TextView getTime() {
        return time;
    }

    public TextView getProtocol() {
        return protocol;
    }

    public TextView getAddress() {
        return address;
    }

    public TextView getPort() {
        return port;
    }

    public TextView getPackageName() {
        return packageName;
    }
}