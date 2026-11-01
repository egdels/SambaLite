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
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.transfer.db.PendingTransfer;
import de.schliweb.sambalite.ui.utils.TransferErrorText;

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
          return oldItem.id == newItem.id;
        }

        @Override
        public boolean areContentsTheSame(
            @NonNull PendingTransfer oldItem, @NonNull PendingTransfer newItem) {
          return oldItem.id == newItem.id
              && oldItem.bytesTransferred == newItem.bytesTransferred
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
    final LinearProgressIndicator progress;
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
      progress = itemView.findViewById(R.id.transfer_progress);
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
      boolean download = !"UPLOAD".equals(item.transferType);
      icon.setImageResource(download ? R.drawable.ic_download : R.drawable.ic_upload);
      // Screen readers announce the direction; the status is already in the text below the name
      icon.setContentDescription(
          itemView
              .getContext()
              .getString(
                  download ? R.string.transfer_type_download : R.string.transfer_type_upload));

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

      Context context = itemView.getContext();
      String statusText;
      switch (item.status) {
        case "PENDING":
          statusText = context.getString(R.string.transfer_status_pending);
          statusBadge.setVisibility(View.GONE);
          break;
        case "ACTIVE":
          statusText = context.getString(R.string.transfer_status_active);
          statusBadge.setVisibility(View.VISIBLE);
          statusIcon.setImageResource(R.drawable.ic_sync_active);
          break;
        case "COMPLETED":
          statusText = context.getString(R.string.transfer_status_completed);
          statusBadge.setVisibility(View.VISIBLE);
          statusIcon.setImageResource(R.drawable.ic_check_circle_green);
          break;
        case "FAILED":
          // A readable reason; the full error is under "Error details" in the item's actions
          String reason = TransferErrorText.shortReason(context, item.lastError);
          statusText =
              reason != null
                  ? context.getString(R.string.transfer_status_failed) + " – " + reason
                  : context.getString(R.string.transfer_status_failed);
          statusBadge.setVisibility(View.VISIBLE);
          statusIcon.setImageResource(R.drawable.ic_cancel_red);
          break;
        case "CANCELLED":
          statusText = context.getString(R.string.transfer_status_cancelled);
          statusBadge.setVisibility(View.VISIBLE);
          statusIcon.setImageResource(R.drawable.ic_close);
          break;
        default:
          statusText = item.status;
          statusBadge.setVisibility(View.GONE);
          break;
      }
      status.setText(detailLine(context, item, statusText));
      bindProgress(item);
    }

    /**
     * Builds the detail line: status, size (progress while running) and the remote folder, so that
     * files of the same name from different folders can be told apart.
     */
    private static String detailLine(Context context, PendingTransfer item, String statusText) {
      StringBuilder line = new StringBuilder(statusText);
      if (item.fileSize > 0) {
        line.append(" · ");
        if ("ACTIVE".equals(item.status)) {
          line.append(
              context.getString(
                  R.string.transfer_progress_format,
                  Formatter.formatFileSize(context, item.bytesTransferred),
                  Formatter.formatFileSize(context, item.fileSize)));
        } else {
          line.append(Formatter.formatFileSize(context, item.fileSize));
        }
      }
      String folder = remoteFolder(item.remotePath);
      if (!folder.isEmpty()) {
        line.append(" · ").append(folder);
      }
      return line.toString();
    }

    /** The name of the remote folder a transfer goes to or comes from, empty for the share root. */
    static String remoteFolder(@Nullable String remotePath) {
      if (remotePath == null) {
        return "";
      }
      String path = remotePath.replace('\\', '/');
      while (path.endsWith("/")) {
        path = path.substring(0, path.length() - 1);
      }
      int last = path.lastIndexOf('/');
      if (last <= 0) {
        return "";
      }
      String parent = path.substring(0, last);
      int previous = parent.lastIndexOf('/');
      return previous >= 0 ? parent.substring(previous + 1) : parent;
    }

    private void bindProgress(PendingTransfer item) {
      if (progress == null) {
        return;
      }
      if (!"ACTIVE".equals(item.status)) {
        progress.setVisibility(View.GONE);
        return;
      }
      progress.setVisibility(View.VISIBLE);
      if (item.fileSize > 0) {
        progress.setIndeterminate(false);
        progress.setProgressCompat(
            (int) Math.min(1000, item.bytesTransferred * 1000 / item.fileSize), false);
      } else {
        progress.setIndeterminate(true);
      }
    }
  }
}
