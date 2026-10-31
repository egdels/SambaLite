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

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.color.MaterialColors;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.util.LogUtils;
import java.util.ArrayList;
import java.util.List;

/** Adapter for displaying available SMB shares in the connection dialog. */
public class SharesAdapter extends RecyclerView.Adapter<SharesAdapter.ShareViewHolder> {

  private static final String TAG = "SharesAdapter";

  List<String> shares = new ArrayList<>();
  String selectedShare = null;
  OnShareSelectedListener listener;

  public void setOnShareSelectedListener(@Nullable OnShareSelectedListener listener) {
    this.listener = listener;
  }

  public void setShares(@NonNull List<String> shares) {
    List<String> newShares = new ArrayList<>(shares);
    DiffUtil.DiffResult result =
        DiffUtil.calculateDiff(new SharesDiffCallback(this.shares, newShares));
    this.shares = newShares;
    this.selectedShare = null; // Reset selection
    result.dispatchUpdatesTo(this);
    LogUtils.d(TAG, "Updated shares list with " + shares.size() + " shares");
  }

  public void clearShares() {
    int oldSize = this.shares.size();
    this.shares.clear();
    this.selectedShare = null;
    notifyItemRangeRemoved(0, oldSize);
    LogUtils.d(TAG, "Cleared shares list");
  }

  @NonNull
  @Override
  public ShareViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    View view =
        LayoutInflater.from(parent.getContext()).inflate(R.layout.item_share, parent, false);
    return new ShareViewHolder(view);
  }

  @Override
  public void onBindViewHolder(@NonNull ShareViewHolder holder, int position) {
    String share = shares.get(position);
    holder.bind(share, share.equals(selectedShare));
  }

  @Override
  public int getItemCount() {
    return shares.size();
  }

  public interface OnShareSelectedListener {
    void onShareSelected(@NonNull String shareName);
  }

  class ShareViewHolder extends RecyclerView.ViewHolder {
    private final TextView shareName;

    public ShareViewHolder(@NonNull View itemView) {
      super(itemView);
      shareName = itemView.findViewById(R.id.share_name);

      itemView.setOnClickListener(
          v -> {
            int position = getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION) return;
            String share = shares.get(position);
            selectShare(share);
          });
    }

    public void bind(String share, boolean isSelected) {
      shareName.setText(share);
      itemView.setSelected(isSelected);

      // Selected share: M3 secondary container, like selected files and transfers
      Context context = itemView.getContext();
      if (isSelected) {
        itemView.setBackgroundColor(
            MaterialColors.getColor(
                context, com.google.android.material.R.attr.colorSecondaryContainer, Color.LTGRAY));
        shareName.setTextColor(
            MaterialColors.getColor(
                context,
                com.google.android.material.R.attr.colorOnSecondaryContainer,
                Color.BLACK));
      } else {
        itemView.setBackgroundColor(Color.TRANSPARENT);
        shareName.setTextColor(
            MaterialColors.getColor(
                context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK));
      }
    }

    private void selectShare(String share) {
      String previousSelection = selectedShare;
      selectedShare = share;

      // Update UI for previous selection
      if (previousSelection != null) {
        int previousIndex = shares.indexOf(previousSelection);
        if (previousIndex != -1) {
          notifyItemChanged(previousIndex);
        }
      }

      // Update UI for new selection
      int newIndex = shares.indexOf(share);
      if (newIndex != -1) {
        notifyItemChanged(newIndex);
      }

      // Notify listener
      if (listener != null) {
        listener.onShareSelected(share);
      }

      LogUtils.d(TAG, "Selected share: " + share);
    }
  }
}
