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

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.card.MaterialCardView;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.transfer.db.PendingTransfer;
import de.schliweb.sambalite.util.EnhancedFileUtils;

/** RecyclerView adapter for displaying transfer queue items matching the FileBrowser item style. */
public class TransferItemAdapter
    extends ListAdapter<PendingTransfer, TransferItemAdapter.ViewHolder> {

  /** Callback interface for transfer item clicks. */
  public interface TransferActionCallback {
    void onItemClick(@NonNull PendingTransfer transfer);
  }

  private final TransferActionCallback callback;

  public TransferItemAdapter(@NonNull TransferActionCallback callback) {
    super(DIFF_CALLBACK);
    this.callback = callback;
  }

  private static final DiffUtil.ItemCallback<PendingTransfer> DIFF_CALLBACK =
      new DiffUtil.ItemCallback<>() {
        @Override
        public boolean areItemsTheSame(
            @NonNull PendingTransfer oldItem, @NonNull PendingTransfer newItem) {
          return oldItem.id == newItem.id;
        }

        @Override
        public boolean areContentsTheSame(
            @NonNull PendingTransfer oldItem, @NonNull PendingTransfer newItem) {
          return oldItem.bytesTransferred == newItem.bytesTransferred
              && oldItem.status.equals(newItem.status)
              && oldItem.retryCount == newItem.retryCount;
        }
      };

  @NonNull
  @Override
  public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    View view =
        LayoutInflater.from(parent.getContext()).inflate(R.layout.item_transfer, parent, false);
    return new ViewHolder(view);
  }

  @Override
  public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
    PendingTransfer item = getItem(position);
    holder.bind(item, callback);
  }

  static class ViewHolder extends RecyclerView.ViewHolder {
    final TextView name;
    final TextView status;
    final TextView size;
    final View detailDot;
    final ImageView icon;
    final View statusBadge;
    final ImageView statusIcon;
    final MaterialCardView rootCard;

    ViewHolder(@NonNull View itemView) {
      super(itemView);
      name = itemView.findViewById(R.id.transfer_name);
      status = itemView.findViewById(R.id.transfer_status);
      size = itemView.findViewById(R.id.transfer_size);
      detailDot = itemView.findViewById(R.id.detail_dot);
      icon = itemView.findViewById(R.id.transfer_icon);
      statusBadge = itemView.findViewById(R.id.status_badge);
      statusIcon = itemView.findViewById(R.id.transfer_status_icon);
      rootCard = itemView instanceof MaterialCardView ? (MaterialCardView) itemView : null;
    }

    void bind(PendingTransfer item, TransferActionCallback callback) {
      name.setText(item.displayName);

      // Icon based on transfer type
      icon.setImageResource(
          "DOWNLOAD".equals(item.transferType)
              ? android.R.drawable.stat_sys_download
              : android.R.drawable.stat_sys_upload);

      // Click handler opens action dialog
      itemView.setOnClickListener(v -> callback.onItemClick(item));

      switch (item.status) {
        case "PENDING":
          status.setText(R.string.transfer_status_pending);
          statusBadge.setVisibility(View.GONE);
          bindSize(item);
          break;
        case "ACTIVE":
          bindActiveState(item);
          statusBadge.setVisibility(View.VISIBLE);
          statusIcon.setImageResource(R.drawable.ic_sync_active);
          break;
        case "COMPLETED":
          status.setText(R.string.transfer_status_completed);
          statusBadge.setVisibility(View.VISIBLE);
          statusIcon.setImageResource(R.drawable.ic_check_circle_green);
          bindSize(item);
          break;
        case "FAILED":
          status.setText(R.string.transfer_status_failed);
          statusBadge.setVisibility(View.VISIBLE);
          statusIcon.setImageResource(R.drawable.ic_cancel_red);
          bindSize(item);
          break;
        case "CANCELLED":
          status.setText(R.string.transfer_status_cancelled);
          statusBadge.setVisibility(View.VISIBLE);
          statusIcon.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
          bindSize(item);
          break;
        default:
          status.setText(item.status);
          statusBadge.setVisibility(View.GONE);
          bindSize(item);
          break;
      }
    }

    private void bindActiveState(PendingTransfer item) {
      if (item.fileSize > 0) {
        status.setText(R.string.transfer_status_active);
        size.setVisibility(View.VISIBLE);
        detailDot.setVisibility(View.VISIBLE);
        size.setText(
            itemView
                .getContext()
                .getString(
                    R.string.transfer_progress_format,
                    EnhancedFileUtils.formatFileSize(item.bytesTransferred),
                    EnhancedFileUtils.formatFileSize(item.fileSize)));
      } else {
        status.setText(R.string.transfer_status_active);
        size.setVisibility(View.GONE);
        detailDot.setVisibility(View.GONE);
      }
    }

    private void bindSize(PendingTransfer item) {
      if (item.fileSize > 0) {
        size.setVisibility(View.VISIBLE);
        detailDot.setVisibility(View.VISIBLE);
        size.setText(EnhancedFileUtils.formatFileSize(item.fileSize));
      } else {
        size.setVisibility(View.GONE);
        detailDot.setVisibility(View.GONE);
      }
    }
  }
}
