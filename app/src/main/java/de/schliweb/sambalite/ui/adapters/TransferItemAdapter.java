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

  /** Speeds of the running transfers, measured from their saved progress (#7). */
  private final TransferRateTracker rates = new TransferRateTracker();

  /** Payload for {@link #refreshRates()}: rebinds without the change animation. */
  private static final Object PAYLOAD_RATE = new Object();

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

  @Override
  public void onCurrentListChanged(
      @NonNull java.util.List<PendingTransfer> previousList,
      @NonNull java.util.List<PendingTransfer> currentList) {
    java.util.Set<Long> active = new java.util.HashSet<>();
    for (PendingTransfer t : currentList) {
      if ("ACTIVE".equals(t.status)) {
        rates.record(t.id, t.bytesTransferred, t.updatedAt);
        active.add(t.id);
      }
    }
    rates.retainOnly(active);
  }

  /**
   * Rebinds the running transfers so that their speed, or "stalled", stays current while no new
   * progress arrives. Called about once per second by the transfer queue.
   */
  public void refreshRates() {
    java.util.List<PendingTransfer> list = getCurrentList();
    for (int i = 0; i < list.size(); i++) {
      if ("ACTIVE".equals(list.get(i).status)) {
        notifyItemChanged(i, PAYLOAD_RATE);
      }
    }
  }

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
    holder.bind(
        item,
        callback,
        selectionMode,
        selectedIds,
        rates.bytesPerSecond(item.id, System.currentTimeMillis()));
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
        java.util.Set<Long> selectedIds,
        long bytesPerSecond) {
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
      status.setText(detailLine(context, item, statusText, bytesPerSecond));
      bindProgress(item);
    }

    /**
     * Builds the detail line: status, size (progress and speed while running) and the remote
     * folder, so that files of the same name from different folders can be told apart.
     */
    static String detailLine(
        Context context, PendingTransfer item, String statusText, long bytesPerSecond) {
      StringBuilder line = new StringBuilder(statusText);
      boolean active = "ACTIVE".equals(item.status);
      if (item.fileSize > 0) {
        line.append(" · ");
        if (active) {
          line.append(
              context.getString(
                  R.string.transfer_progress_format,
                  Formatter.formatFileSize(context, item.bytesTransferred),
                  Formatter.formatFileSize(context, item.fileSize)));
        } else {
          line.append(Formatter.formatFileSize(context, item.fileSize));
        }
      }
      if (active) {
        String speed = speedText(context, bytesPerSecond);
        if (speed != null) {
          line.append(" · ").append(speed);
        }
      }
      String folder = remoteFolder(item.remotePath);
      if (!folder.isEmpty()) {
        line.append(" · ").append(folder);
      }
      return line.toString();
    }

    /** "11.2 MB/s", "stalled", or null while no speed is known yet. */
    @Nullable
    static String speedText(Context context, long bytesPerSecond) {
      if (bytesPerSecond == TransferRateTracker.STALLED) {
        return context.getString(R.string.transfer_stalled);
      }
      if (bytesPerSecond < 0) {
        return null;
      }
      // A no-break space keeps number and unit together when the line wraps ("12 MB/s")
      return context.getString(
          R.string.transfer_rate_format,
          Formatter.formatFileSize(context, bytesPerSecond).replace(' ', '\u00A0'));
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
