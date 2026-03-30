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

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import de.schliweb.sambalite.transfer.TransferWorker;
import de.schliweb.sambalite.transfer.db.PendingTransfer;
import de.schliweb.sambalite.transfer.db.PendingTransferDao;
import de.schliweb.sambalite.transfer.db.TransferDatabase;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * ViewModel for the transfer queue UI. Exposes LiveData for the list of active transfers and
 * pending count (badge), and provides actions to cancel, retry, or remove transfers.
 */
public class TransferQueueViewModel extends AndroidViewModel {
  private final PendingTransferDao dao;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final LiveData<List<PendingTransfer>> activeTransfers;
  private final LiveData<Integer> pendingCount;

  public TransferQueueViewModel(@NonNull Application application) {
    super(application);
    dao = TransferDatabase.getInstance(application).pendingTransferDao();
    long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1);
    activeTransfers = dao.observeActiveTransfers(cutoff);
    pendingCount = dao.observePendingCount();
  }

  /** Observes all non-completed transfers for the queue list. */
  @NonNull
  public LiveData<List<PendingTransfer>> getActiveTransfers() {
    return activeTransfers;
  }

  /** Observes the count of pending/active transfers (for toolbar badge). */
  @NonNull
  public LiveData<Integer> getPendingCount() {
    return pendingCount;
  }

  /** Cancels a single transfer. */
  public void cancelTransfer(long id) {
    executor.execute(() -> dao.cancel(id, System.currentTimeMillis()));
  }

  /** Cancels all pending/active transfers and stops the running worker. */
  public void cancelAll() {
    executor.execute(
        () -> {
          dao.cancelAll(System.currentTimeMillis());
          WorkManager.getInstance(getApplication()).cancelUniqueWork("transfer_queue");
        });
  }

  /** Resets a failed transfer back to PENDING for retry and starts the worker. */
  public void retryTransfer(long id) {
    executor.execute(
        () -> {
          dao.resetToPending(id, System.currentTimeMillis());
          startTransferWorker();
        });
  }

  /** Resets all failed transfers back to PENDING and starts the worker. */
  public void retryAllFailed() {
    executor.execute(
        () -> {
          dao.resetActiveToRetry(System.currentTimeMillis());
          startTransferWorker();
        });
  }

  /** Removes a transfer from the database permanently. */
  public void removeTransfer(long id) {
    executor.execute(() -> dao.deleteById(id));
  }

  /** Removes multiple transfers by their IDs. */
  public void removeTransfers(@NonNull Set<Long> ids) {
    executor.execute(
        () -> {
          for (long id : ids) {
            dao.deleteById(id);
          }
        });
  }

  /** Retries multiple transfers by resetting them to PENDING and starts the worker. */
  public void retryTransfers(@NonNull Set<Long> ids) {
    executor.execute(
        () -> {
          long now = System.currentTimeMillis();
          for (long id : ids) {
            dao.resetToPending(id, now);
          }
          startTransferWorker();
        });
  }

  /** Starts the TransferWorker to process pending transfers. */
  private void startTransferWorker() {
    OneTimeWorkRequest request =
        new OneTimeWorkRequest.Builder(TransferWorker.class)
            .setConstraints(
                new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build();
    WorkManager.getInstance(getApplication())
        .enqueueUniqueWork("transfer_queue", ExistingWorkPolicy.KEEP, request);
  }

  /** Cancels multiple transfers by their IDs. */
  public void cancelTransfers(@NonNull Set<Long> ids) {
    executor.execute(
        () -> {
          long now = System.currentTimeMillis();
          for (long id : ids) {
            dao.cancel(id, now);
          }
          WorkManager.getInstance(getApplication()).cancelUniqueWork("transfer_queue");
        });
  }
}
