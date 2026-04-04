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
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
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
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.transfer.db.PendingTransfer;
import de.schliweb.sambalite.ui.adapters.TransferItemAdapter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Activity displaying the persistent transfer queue matching the FileBrowser design. */
public class TransferQueueActivity extends AppCompatActivity
    implements TransferItemAdapter.TransferActionCallback {

  private static final String TAG = "TransferQueueActivity";

  private TransferQueueViewModel viewModel;
  private TransferItemAdapter adapter;
  private MaterialToolbar toolbar;

  private RecyclerView transferList;
  private View emptyState;
  private View statsCard;
  private TextView countPending;
  private TextView countActive;
  private TextView countCompleted;
  private TextView countFailed;
  private TextView queueInfoText;

  private static final String PREFS_NAME = "transfer_queue_prefs";
  private static final String PREF_SORT_MODE = "sort_mode";
  private static final String PREF_HIDE_COMPLETED = "hide_completed";

  /** Current sort mode: 0 = name, 1 = date, 2 = status. */
  private int currentSort = 1; // default: by date (newest first)

  private boolean hideCompleted = false;

  private List<PendingTransfer> lastTransfers;

  private FloatingActionButton fabSelectAll;
  private FloatingActionButton fabClearSelection;
  private FloatingActionButton fabMultiOptions;

  private boolean selectionMode = false;
  private final Set<Long> selectedIds = new HashSet<>();

  public static @NonNull Intent createIntent(@NonNull Context context) {
    return new Intent(context, TransferQueueActivity.class);
  }

  @Override
  protected void onCreate(@NonNull Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_transfer_queue);

    toolbar = findViewById(R.id.toolbar);
    toolbar.setNavigationOnClickListener(v -> finish());

    transferList = findViewById(R.id.transfer_list);
    emptyState = findViewById(R.id.empty_state);
    statsCard = findViewById(R.id.stats_card);
    countPending = findViewById(R.id.count_pending);
    countActive = findViewById(R.id.count_active);
    countCompleted = findViewById(R.id.count_completed);
    countFailed = findViewById(R.id.count_failed);
    queueInfoText = findViewById(R.id.queue_info_text);

    SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    currentSort = prefs.getInt(PREF_SORT_MODE, 1);
    hideCompleted = prefs.getBoolean(PREF_HIDE_COMPLETED, false);

    findViewById(R.id.sort_button).setOnClickListener(v -> showSortDialog());

    adapter = new TransferItemAdapter(this);
    transferList.setAdapter(adapter);

    fabSelectAll = findViewById(R.id.fab_select_all);
    fabClearSelection = findViewById(R.id.fab_clear_selection);
    fabMultiOptions = findViewById(R.id.fab_multi_options);

    fabSelectAll.setOnClickListener(v -> selectAllTransfers());
    fabClearSelection.setOnClickListener(v -> clearSelection());
    fabMultiOptions.setOnClickListener(v -> showMultiSelectOptionsDialog());

    viewModel = new ViewModelProvider(this).get(TransferQueueViewModel.class);
    viewModel
        .getActiveTransfers()
        .observe(
            this,
            transfers -> {
              Log.d(
                  TAG,
                  "observe: activeTransfers changed, size="
                      + (transfers != null ? transfers.size() : 0));
              this.onTransfersChanged(transfers);
            });
  }

  private void selectAllTransfers() {
    Log.d(TAG, "selectAllTransfers: selectionMode=" + selectionMode);
    if (lastTransfers != null) {
      for (PendingTransfer transfer : lastTransfers) {
        if (!hideCompleted || !"COMPLETED".equals(transfer.status)) {
          selectedIds.add(transfer.id);
        }
      }
      updateSelectionUI();
    }
  }

  private void clearSelection() {
    Log.d(
        TAG,
        "clearSelection: previous selectionMode=" + selectionMode + ", size=" + selectedIds.size());
    selectionMode = false;
    selectedIds.clear();
    updateSelectionUI();
  }

  private void updateSelectionUI() {
    Log.d(
        TAG, "updateSelectionUI: selectionMode=" + selectionMode + ", selectedIds=" + selectedIds);
    if (selectedIds.isEmpty()) {
      selectionMode = false;
    }
    adapter.setSelectionMode(selectionMode);

    int visibility = selectionMode ? View.VISIBLE : View.GONE;
    fabSelectAll.setVisibility(visibility);
    fabClearSelection.setVisibility(visibility);
    fabMultiOptions.setVisibility(visibility);

    if (selectionMode) {
      toolbar.setSubtitle(
          getResources()
              .getQuantityString(
                  R.plurals.transfer_selection_count, selectedIds.size(), selectedIds.size()));
    } else {
      toolbar.setSubtitle(null);
      selectedIds.clear();
    }
    adapter.setSelectedIds(selectedIds);
  }

  private void showMultiSelectOptionsDialog() {
    String[] items = {
      getString(R.string.transfer_retry),
      getString(R.string.transfer_cancel),
      getString(R.string.transfer_remove)
    };

    new MaterialAlertDialogBuilder(this)
        .setTitle(R.string.transfer_actions_title)
        .setItems(
            items,
            (dialog, which) -> {
              Log.d(TAG, "multiSelectAction: which=" + which + ", ids=" + selectedIds);
              switch (which) {
                case 0:
                  viewModel.retryTransfers(selectedIds);
                  break;
                case 1:
                  viewModel.cancelTransfers(selectedIds);
                  break;
                case 2:
                  viewModel.removeTransfers(selectedIds);
                  break;
              }
              clearSelection();
            })
        .setNegativeButton(R.string.cancel, null)
        .show();
  }

  private void onTransfersChanged(List<PendingTransfer> transfers) {
    Log.d(TAG, "onTransfersChanged: size=" + (transfers != null ? transfers.size() : 0));
    lastTransfers = transfers;

    boolean empty = transfers == null || transfers.isEmpty();
    transferList.setVisibility(empty ? View.GONE : View.VISIBLE);
    emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
    statsCard.setVisibility(empty ? View.GONE : View.VISIBLE);

    if (!empty) {
      // Sync selection with current items
      Set<Long> currentIds =
          transfers.stream().map(t -> t.id).collect(java.util.stream.Collectors.toSet());
      boolean changed = selectedIds.retainAll(currentIds);
      if (changed) {
        Log.d(TAG, "onTransfersChanged: selection updated after items removed");
        if (selectedIds.isEmpty()) {
          selectionMode = false;
        }
        updateSelectionUI();
      }
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

              getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                  .edit()
                  .putInt(PREF_SORT_MODE, currentSort)
                  .putBoolean(PREF_HIDE_COMPLETED, hideCompleted)
                  .apply();

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
    if (selectionMode) {
      if (selectedIds.contains(transfer.id)) {
        selectedIds.remove(transfer.id);
        if (selectedIds.isEmpty()) {
          selectionMode = false;
        }
      } else {
        selectedIds.add(transfer.id);
      }
      updateSelectionUI();
    } else {
      showTransferActionDialog(transfer);
    }
  }

  @Override
  public void onItemLongClick(@NonNull PendingTransfer transfer) {
    if (!selectionMode) {
      selectionMode = true;
      selectedIds.add(transfer.id);
      adapter.setSelectionMode(true);
      updateSelectionUI();
    } else {
      if (selectedIds.contains(transfer.id)) {
        selectedIds.remove(transfer.id);
        if (selectedIds.isEmpty()) {
          selectionMode = false;
        }
      } else {
        selectedIds.add(transfer.id);
      }
      updateSelectionUI();
    }
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
