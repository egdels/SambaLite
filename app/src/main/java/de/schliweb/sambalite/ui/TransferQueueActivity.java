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

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.transfer.db.PendingTransfer;
import de.schliweb.sambalite.ui.adapters.TransferItemAdapter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Activity displaying the persistent transfer queue matching the FileBrowser design. */
public class TransferQueueActivity extends AppCompatActivity
    implements TransferItemAdapter.TransferActionCallback {

  private TransferQueueViewModel viewModel;
  private TransferItemAdapter adapter;

  private RecyclerView transferList;
  private View emptyState;
  private View statsCard;
  private TextView countPending;
  private TextView countActive;
  private TextView countCompleted;
  private TextView countFailed;
  private TextView queueInfoText;

  /** Current sort mode: 0 = name, 1 = date, 2 = status. */
  private int currentSort = 1; // default: by date (newest first)

  private boolean hideCompleted = false;

  private List<PendingTransfer> lastTransfers;

  public static @NonNull Intent createIntent(@NonNull Context context) {
    return new Intent(context, TransferQueueActivity.class);
  }

  @Override
  protected void onCreate(@NonNull Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_transfer_queue);

    MaterialToolbar toolbar = findViewById(R.id.toolbar);
    toolbar.setNavigationOnClickListener(v -> finish());

    transferList = findViewById(R.id.transfer_list);
    emptyState = findViewById(R.id.empty_state);
    statsCard = findViewById(R.id.stats_card);
    countPending = findViewById(R.id.count_pending);
    countActive = findViewById(R.id.count_active);
    countCompleted = findViewById(R.id.count_completed);
    countFailed = findViewById(R.id.count_failed);
    queueInfoText = findViewById(R.id.queue_info_text);

    findViewById(R.id.sort_button).setOnClickListener(v -> showSortDialog());

    adapter = new TransferItemAdapter(this);
    transferList.setAdapter(adapter);

    viewModel = new ViewModelProvider(this).get(TransferQueueViewModel.class);
    viewModel.getActiveTransfers().observe(this, this::onTransfersChanged);
  }

  private void onTransfersChanged(List<PendingTransfer> transfers) {
    lastTransfers = transfers;

    boolean empty = transfers == null || transfers.isEmpty();
    transferList.setVisibility(empty ? View.GONE : View.VISIBLE);
    emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
    statsCard.setVisibility(empty ? View.GONE : View.VISIBLE);

    if (!empty) {
      long pending = transfers.stream().filter(t -> "PENDING".equals(t.status)).count();
      long active = transfers.stream().filter(t -> "ACTIVE".equals(t.status)).count();
      long completed = transfers.stream().filter(t -> "COMPLETED".equals(t.status)).count();
      long failed = transfers.stream().filter(t -> "FAILED".equals(t.status)).count();

      countPending.setText(String.valueOf(pending));
      countActive.setText(String.valueOf(active));
      countCompleted.setText(String.valueOf(completed));
      countFailed.setText(String.valueOf(failed));

      queueInfoText.setText(
          getResources()
              .getQuantityString(
                  R.plurals.transfer_queue_count, transfers.size(), transfers.size()));

      List<PendingTransfer> filtered =
          hideCompleted
              ? transfers.stream()
                  .filter(t -> !"COMPLETED".equals(t.status))
                  .collect(java.util.stream.Collectors.toList())
              : transfers;
      adapter.submitList(sortTransfers(filtered));
    } else {
      queueInfoText.setText(getString(R.string.transfer_queue_empty));
      adapter.submitList(null);
    }
  }

  private List<PendingTransfer> sortTransfers(List<PendingTransfer> transfers) {
    List<PendingTransfer> sorted = new ArrayList<>(transfers);
    switch (currentSort) {
      case 0: // by name
        sorted.sort(Comparator.comparing(t -> t.displayName.toLowerCase(Locale.ROOT)));
        break;
      case 1: // by date (newest first)
        sorted.sort(Comparator.comparingLong((PendingTransfer t) -> t.updatedAt).reversed());
        break;
      case 2: // by status
        sorted.sort(Comparator.comparingInt(this::statusOrder));
        break;
    }
    return sorted;
  }

  private int statusOrder(PendingTransfer t) {
    switch (t.status) {
      case "ACTIVE":
        return 0;
      case "PENDING":
        return 1;
      case "FAILED":
        return 2;
      case "COMPLETED":
        return 3;
      case "CANCELLED":
        return 4;
      default:
        return 5;
    }
  }

  private void showSortDialog() {
    View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_sort_transfers, null);
    RadioGroup sortGroup = dialogView.findViewById(R.id.sort_type_radio_group);
    CompoundButton hideCompletedSwitch = dialogView.findViewById(R.id.hide_completed_switch);

    // Set initial values
    if (currentSort == 0) {
      sortGroup.check(R.id.radio_name);
    } else if (currentSort == 1) {
      sortGroup.check(R.id.radio_date);
    } else if (currentSort == 2) {
      sortGroup.check(R.id.radio_status);
    }

    hideCompletedSwitch.setChecked(hideCompleted);

    new MaterialAlertDialogBuilder(this)
        .setView(dialogView)
        .setPositiveButton(
            R.string.ok,
            (dialog, which) -> {
              int checkedId = sortGroup.getCheckedRadioButtonId();
              if (checkedId == R.id.radio_name) {
                currentSort = 0;
              } else if (checkedId == R.id.radio_date) {
                currentSort = 1;
              } else if (checkedId == R.id.radio_status) {
                currentSort = 2;
              }

              hideCompleted = hideCompletedSwitch.isChecked();
              if (lastTransfers != null) {
                onTransfersChanged(lastTransfers);
              }
            })
        .setNegativeButton(R.string.cancel, null)
        .show();
  }

  // --- TransferActionCallback ---

  @Override
  public void onItemClick(@NonNull PendingTransfer transfer) {
    showTransferActionDialog(transfer);
  }

  private void showTransferActionDialog(PendingTransfer transfer) {
    String[] items = {
      getString(R.string.transfer_retry),
      getString(R.string.transfer_cancel),
      getString(R.string.transfer_remove)
    };

    new MaterialAlertDialogBuilder(this)
        .setTitle(transfer.displayName)
        .setItems(
            items,
            (dialog, which) -> {
              switch (which) {
                case 0:
                  viewModel.retryTransfer(transfer.id);
                  break;
                case 1:
                  viewModel.cancelTransfer(transfer.id);
                  break;
                case 2:
                  viewModel.removeTransfer(transfer.id);
                  break;
              }
            })
        .setNegativeButton(R.string.cancel, null)
        .show();
  }
}
