/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui.adapters;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.util.DiscoveredServers.Entry;
import de.schliweb.sambalite.util.LogUtils;
import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for the servers found by the network search dialog: hosts found by the port scan and SMB
 * services announced via mDNS, merged into one list. Entries that are still being resolved are
 * shown but cannot be selected.
 */
public class DiscoveredServerAdapter
    extends RecyclerView.Adapter<DiscoveredServerAdapter.ServerViewHolder> {

  private static final String TAG = "DiscoveredServerAdapter";

  List<Entry> servers = new ArrayList<>();
  int selectedKey = -1;
  OnServerSelectedListener listener;

  public void setOnServerSelectedListener(@Nullable OnServerSelectedListener listener) {
    this.listener = listener;
  }

  /**
   * Replaces the list. The selection is kept if its entry is still there and usable, otherwise it
   * is cleared.
   */
  @SuppressLint("NotifyDataSetChanged") // Small list, rebuilt as results come in
  public void setServers(@NonNull List<Entry> servers) {
    this.servers = new ArrayList<>(servers);
    if (selectedKey >= 0 && getSelectedServer() == null) {
      selectedKey = -1;
    }
    notifyDataSetChanged();
    LogUtils.d(TAG, "Updated server list with " + servers.size() + " servers");
  }

  /** The selected entry, or null if none is selected or it can no longer be used. */
  public @Nullable Entry getSelectedServer() {
    for (Entry e : servers) {
      if (e.getKey() == selectedKey) {
        return e.isUsable() ? e : null;
      }
    }
    return null;
  }

  @NonNull
  @Override
  public ServerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    View view =
        LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_discovered_server, parent, false);
    return new ServerViewHolder(view);
  }

  @Override
  public void onBindViewHolder(@NonNull ServerViewHolder holder, int position) {
    holder.bind(servers.get(position));
  }

  @Override
  public int getItemCount() {
    return servers.size();
  }

  public interface OnServerSelectedListener {
    void onServerSelected(@NonNull Entry server);
  }

  class ServerViewHolder extends RecyclerView.ViewHolder {
    private final TextView serverName;
    private final TextView serverDetails;
    private final ImageView selectionIndicator;

    public ServerViewHolder(@NonNull View itemView) {
      super(itemView);
      serverName = itemView.findViewById(R.id.server_name);
      serverDetails = itemView.findViewById(R.id.server_details);
      selectionIndicator = itemView.findViewById(R.id.selection_indicator);

      itemView.setOnClickListener(
          v -> {
            int position = getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION) return;
            Entry server = servers.get(position);
            if (!server.isUsable()) return; // still resolving
            selectServer(server);
          });
    }

    public void bind(Entry server) {
      serverName.setText(server.getDisplayName());
      serverDetails.setText(details(server));

      boolean isSelected = server.getKey() == selectedKey;
      selectionIndicator.setVisibility(isSelected ? View.VISIBLE : View.GONE);
      itemView.setSelected(isSelected);
      itemView.setAlpha(server.isUsable() ? 1f : 0.6f);
    }

    /** E.g. "nas.local • 192.168.1.10 • Port 4455 • mDNS" or "SMB: ✓ NetBIOS: ✗ (12ms)". */
    private String details(Entry server) {
      if (!server.isUsable()) {
        return itemView.getContext().getString(R.string.discovery_resolving);
      }
      List<String> parts = new ArrayList<>();
      if (server.getMdnsHostName() != null) {
        parts.add(server.getMdnsHostName());
      }
      if (server.getAddress() != null && !server.getAddress().equals(server.getDisplayName())) {
        parts.add(server.getAddress());
      }
      if (server.getPortToUse() > 0) {
        parts.add(itemView.getContext().getString(R.string.port) + " " + server.getPortToUse());
      }
      if (server.isFoundByMdns()) {
        parts.add("mDNS");
      }
      StringBuilder details = new StringBuilder(String.join(" • ", parts));
      if (server.isFoundByScan()) {
        if (details.length() > 0) details.append('\n');
        details
            .append("SMB: ")
            .append(server.isSmbPortOpen() ? "✓ " : "✗ ")
            .append("NetBIOS: ")
            .append(server.isNetbiosPortOpen() ? "✓ " : "✗ ")
            .append("(")
            .append(server.getResponseTime())
            .append("ms)");
      }
      return details.toString();
    }

    @SuppressLint("NotifyDataSetChanged") // The previous selection may be anywhere in the list
    void selectServer(Entry server) {
      selectedKey = server.getKey();
      notifyDataSetChanged();
      if (listener != null) {
        listener.onServerSelected(server);
      }
      LogUtils.d(TAG, "Selected server: " + server.getDisplayName());
    }
  }
}
