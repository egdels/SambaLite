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

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.transfer.db.PendingTransfer;
import de.schliweb.sambalite.util.EnhancedFileUtils;

/** RecyclerView adapter for displaying transfer queue items matching the FileBrowser item style. */
public class TransferItemAdapter
    extends ListAdapter<PendingTransfer, TransferItemAdapter.ViewHolder> {
  private static final String TAG = "TransferItemAdapter";

  /** Callback interface for transfer item clicks. */
  public interface TransferActionCallback {
    void onItemClick(@NonNull PendingTransfer transfer);

    void onItemLongClick(@NonNull PendingTransfer transfer);
  }

  private final TransferActionCallback callback;

  private boolean selectionMode = false;
  private java.util.Set<Long> selectedIds = new java.util.HashSet<>();

  public TransferItemAdapter(@NonNull TransferActionCallback callback) {
    super(DIFF_CALLBACK);
    this.callback = callback;
  }

  public void setSelectionMode(boolean enabled) {
    this.selectionMode = enabled;
    if (!enabled) {
      selectedIds.clear();
    }
    notifyDataSetChanged();
  }

  public void setSelectedIds(java.util.Set<Long> selectedIds) {
    this.selectedIds = new java.util.HashSet<>(selectedIds);
    notifyDataSetChanged();
  }

  private static final DiffUtil.ItemCallback<PendingTransfer> DIFF_CALLBACK =
      new DiffUtil.ItemCallback<>() {
        @Override
        public boolean areItemsTheSame(
            @NonNull PendingTransfer oldItem, @NonNull PendingTransfer newItem) {
          boolean same = oldItem.id == newItem.id;
          Log.v(TAG, "areItemsTheSame: old=" + oldItem.id + ", new=" + newItem.id + " -> " + same);
          return same;
        }

        @Override
        public boolean areContentsTheSame(
            @NonNull PendingTransfer oldItem, @NonNull PendingTransfer newItem) {
          boolean same =
              oldItem.id == newItem.id
                  && oldItem.bytesTransferred == newItem.bytesTransferred
                  && oldItem.status.equals(newItem.status)
                  && oldItem.retryCount == newItem.retryCount;
          Log.v(TAG, "areContentsTheSame: id=" + oldItem.id + " -> " + same);
          return same;
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
    boolean isSelected = selectedIds.contains(item.id);
    Log.d(
        TAG,
        "onBindViewHolder: pos="
            + position
            + ", id="
            + item.id
            + ", selected="
            + isSelected
            + ", mode="
            + selectionMode);
    holder.bind(item, callback, selectionMode, selectedIds);
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
    final ImageButton moreOptions;
    final View selectionIndicator;

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
      moreOptions = itemView.findViewById(R.id.more_options);
      selectionIndicator = itemView.findViewById(R.id.selection_indicator);
    }

    void bind(
        PendingTransfer item,
        TransferActionCallback callback,
        boolean selectionMode,
        java.util.Set<Long> selectedIds) {
      name.setText(item.displayName);

      // Icon based on transfer type
      icon.setImageResource(
          "DOWNLOAD".equals(item.transferType)
              ? android.R.drawable.stat_sys_download
              : android.R.drawable.stat_sys_upload);

      // Selection state visualization
      boolean isSelected = selectedIds.contains(item.id);
      itemView.setActivated(isSelected);

      /*if (selectionIndicator != null) {
          selectionIndicator.setVisibility(isSelected ? View.VISIBLE : View.GONE);
      }*/
      if (rootCard != null) {
        int bgColor =
            MaterialColors.getColor(
                itemView,
                isSelected
                    ? com.google.android.material.R.attr.colorSecondaryContainer
                    : com.google.android.material.R.attr.colorSurface);
        rootCard.setCardBackgroundColor(bgColor);
      }

      // Click handler
      itemView.setOnClickListener(v -> callback.onItemClick(item));

      // More options click handler
      if (moreOptions != null) {
        moreOptions.setOnClickListener(v -> callback.onItemClick(item));
      }

      // Long click handler
      itemView.setOnLongClickListener(
          v -> {
            callback.onItemLongClick(item);
            return true;
          });

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
