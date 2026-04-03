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
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.sync.SyncConfig;
import de.schliweb.sambalite.sync.SyncDirection;
import de.schliweb.sambalite.util.LogUtils;
import java.util.ArrayList;
import java.util.List;

/** Adapter for displaying Sync configurations in a RecyclerView. */
public class SyncConfigAdapter
    extends RecyclerView.Adapter<SyncConfigAdapter.SyncConfigViewHolder> {

  private List<SyncConfig> syncConfigs = new ArrayList<>();
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
   * Sets the click listener for sync configurations.
   *
   * @param listener The listener to set
   */
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

    void onSyncNowClick(@NonNull SyncConfig config);

    void onOptionsClick(@NonNull View view, @NonNull SyncConfig config);
  }

  /** ViewHolder for a sync configuration item. */
  class SyncConfigViewHolder extends RecyclerView.ViewHolder {

    private final TextView localNameTextView;
    private final TextView remotePathTextView;
    private final TextView statusTextView;
    private final TextView directionTextView;
    private final MaterialButton syncNowButton;
    private final View moreOptionsButton;
    private final View syncIcon;
    private final View syncProgressBar;

    SyncConfigViewHolder(@NonNull View itemView) {
      super(itemView);
      localNameTextView = itemView.findViewById(R.id.sync_local_name);
      remotePathTextView = itemView.findViewById(R.id.sync_remote_path);
      statusTextView = itemView.findViewById(R.id.sync_status);
      directionTextView = itemView.findViewById(R.id.sync_direction);
      syncNowButton = itemView.findViewById(R.id.sync_now_button);
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

      syncNowButton.setOnClickListener(
          v -> {
            int position = getBindingAdapterPosition();
            if (position != RecyclerView.NO_POSITION && listener != null) {
              listener.onSyncNowClick(syncConfigs.get(position));
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
      remotePathTextView.setText(config.getRemotePath());

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

      // Update sync now button state based on enabled status
      syncNowButton.setEnabled(config.isEnabled() && !config.isRunning());

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
