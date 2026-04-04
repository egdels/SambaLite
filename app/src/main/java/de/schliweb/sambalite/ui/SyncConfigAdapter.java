/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.sync.SyncConfig;
import de.schliweb.sambalite.sync.SyncDirection;
import de.schliweb.sambalite.util.LogUtils;
import java.util.ArrayList;
import java.util.List;

/** Adapter for displaying Sync configurations in a RecyclerView. */
public class SyncConfigAdapter
    extends RecyclerView.Adapter<SyncConfigAdapter.SyncConfigViewHolder> {

  private List<SyncConfig> syncConfigs = new ArrayList<>();
  private List<SmbConnection> connections = new ArrayList<>();
  private OnSyncClickListener listener;
  private Context context;

  /**
   * Updates the list of sync configurations.
   *
   * @param configs The new list of sync configurations
   */
  @SuppressLint("NotifyDataSetChanged")
  public void setSyncConfigs(@NonNull List<SyncConfig> configs) {
    LogUtils.d("SyncConfigAdapter", "Setting sync configs: " + configs.size() + " items");
    this.syncConfigs = configs;
    notifyDataSetChanged();
  }

  /**
   * Updates the list of connections used for resolving server paths.
   *
   * @param connections The list of SMB connections
   */
  @SuppressLint("NotifyDataSetChanged")
  public void setConnections(@NonNull List<SmbConnection> connections) {
    this.connections = connections;
    notifyDataSetChanged();
  }

  public void setOnSyncClickListener(@Nullable OnSyncClickListener listener) {
    this.listener = listener;
  }

  @NonNull
  @Override
  public SyncConfigViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    this.context = parent.getContext();
    View view = LayoutInflater.from(context).inflate(R.layout.item_sync_config, parent, false);
    return new SyncConfigViewHolder(view);
  }

  @Override
  public void onBindViewHolder(@NonNull SyncConfigViewHolder holder, int position) {
    SyncConfig config = syncConfigs.get(position);
    holder.bind(config);
  }

  @Override
  public int getItemCount() {
    return syncConfigs.size();
  }

  /** Interface for sync click events. */
  public interface OnSyncClickListener {
    void onSyncClick(@NonNull SyncConfig config);

    void onOptionsClick(@NonNull View view, @NonNull SyncConfig config);
  }

  /**
   * Extracts a human-readable path from a content URI.
   *
   * @param uriString the content URI string
   * @return a readable path, or the original string if parsing fails
   */
  private static String extractReadablePath(@Nullable String uriString) {
    if (uriString == null || uriString.isEmpty()) {
      return "";
    }
    try {
      Uri uri = Uri.parse(uriString);
      String lastSegment = uri.getLastPathSegment();
      if (lastSegment != null && lastSegment.contains(":")) {
        // Format: "primary:Documents/scans" -> "/Documents/scans"
        String path = lastSegment.substring(lastSegment.indexOf(':') + 1);
        return "/" + path;
      }
      if (lastSegment != null) {
        return lastSegment;
      }
    } catch (Exception e) {
      LogUtils.w("SyncConfigAdapter", "Failed to parse URI: " + e.getMessage());
    }
    return uriString;
  }

  /**
   * Builds a full server path from the connection and remote path.
   *
   * @param config the sync configuration
   * @return a path like "//server/share/remotePath"
   */
  private String buildServerPath(@NonNull SyncConfig config) {
    for (SmbConnection conn : connections) {
      if (conn.getId().equals(config.getConnectionId())) {
        StringBuilder sb = new StringBuilder("//");
        sb.append(conn.getServer());
        if (conn.getShare() != null && !conn.getShare().isEmpty()) {
          sb.append("/").append(conn.getShare());
        }
        if (config.getRemotePath() != null && !config.getRemotePath().isEmpty()) {
          sb.append("/").append(config.getRemotePath());
        }
        return sb.toString();
      }
    }
    return config.getRemotePath();
  }

  /** ViewHolder for a sync configuration item. */
  class SyncConfigViewHolder extends RecyclerView.ViewHolder {

    private final TextView localNameTextView;
    private final TextView localPathTextView;
    private final TextView remotePathTextView;
    private final TextView statusTextView;
    private final TextView directionTextView;
    private final View moreOptionsButton;
    private final View syncIcon;
    private final View syncProgressBar;

    SyncConfigViewHolder(@NonNull View itemView) {
      super(itemView);
      localNameTextView = itemView.findViewById(R.id.sync_local_name);
      localPathTextView = itemView.findViewById(R.id.sync_local_path);
      remotePathTextView = itemView.findViewById(R.id.sync_remote_path);
      statusTextView = itemView.findViewById(R.id.sync_status);
      directionTextView = itemView.findViewById(R.id.sync_direction);
      moreOptionsButton = itemView.findViewById(R.id.sync_more_options);
      syncIcon = itemView.findViewById(R.id.sync_icon);
      syncProgressBar = itemView.findViewById(R.id.sync_progress);

      itemView.setOnClickListener(
          v -> {
            int position = getBindingAdapterPosition();
            if (position != RecyclerView.NO_POSITION && listener != null) {
              listener.onSyncClick(syncConfigs.get(position));
            }
          });

      moreOptionsButton.setOnClickListener(
          v -> {
            int position = getBindingAdapterPosition();
            if (position != RecyclerView.NO_POSITION && listener != null) {
              listener.onOptionsClick(v, syncConfigs.get(position));
            }
          });
    }

    void bind(SyncConfig config) {
      localNameTextView.setText(config.getLocalFolderDisplayName());
      localPathTextView.setText(extractReadablePath(config.getLocalFolderUri()));
      String serverPath = buildServerPath(config);
      remotePathTextView.setText(serverPath);

      // Set last sync status
      if (config.getLastSyncTimestamp() > 0) {
        CharSequence timeAgo =
            DateUtils.getRelativeTimeSpanString(
                config.getLastSyncTimestamp(),
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS);
        statusTextView.setText(context.getString(R.string.sync_last_sync, timeAgo));
      } else {
        statusTextView.setText(context.getString(R.string.sync_never_synced));
      }

      // Set sync direction
      String directionText;
      SyncDirection direction = config.getDirection();
      if (direction == SyncDirection.LOCAL_TO_REMOTE) {
        directionText = context.getString(R.string.sync_direction_local_to_remote);
      } else if (direction == SyncDirection.REMOTE_TO_LOCAL) {
        directionText = context.getString(R.string.sync_direction_remote_to_local);
      } else {
        directionText = context.getString(R.string.sync_direction_bidirectional);
      }
      directionTextView.setText(directionText);

      // Update running status
      if (config.isRunning()) {
        syncIcon.setVisibility(View.GONE);
        syncProgressBar.setVisibility(View.VISIBLE);
        statusTextView.setText(R.string.sync_running);
      } else {
        syncIcon.setVisibility(View.VISIBLE);
        syncProgressBar.setVisibility(View.GONE);
      }
    }
  }
}
