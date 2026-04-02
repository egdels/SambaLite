/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.transfer.db;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import java.util.List;

/** Data Access Object for {@link PendingTransfer} entities. */
@Dao
public interface PendingTransferDao {

  /** Observes all transfers for the queue UI (active + recently completed within 1 hour). */
  @Query(
      "SELECT * FROM pending_transfer WHERE status NOT IN ('CANCELLED')"
          + " AND (status != 'COMPLETED' OR updated_at > :cutoff)"
          + " ORDER BY CASE status"
          + "   WHEN 'ACTIVE' THEN 0"
          + "   WHEN 'PENDING' THEN 1"
          + "   WHEN 'FAILED' THEN 2"
          + "   WHEN 'COMPLETED' THEN 3"
          + "   ELSE 4 END, sort_order, created_at")
  @NonNull
  LiveData<List<PendingTransfer>> observeActiveTransfers(long cutoff);

  /** Observes all transfers belonging to a specific batch. */
  @Query("SELECT * FROM pending_transfer WHERE batch_id = :batchId ORDER BY sort_order")
  @NonNull
  LiveData<List<PendingTransfer>> observeBatch(@NonNull String batchId);

  /** Returns the next transfer to process (PENDING or retryable FAILED). */
  @Query(
      "SELECT * FROM pending_transfer WHERE status IN ('PENDING', 'FAILED')"
          + " AND retry_count < max_retries ORDER BY sort_order, created_at LIMIT 1")
  @Nullable
  PendingTransfer getNextPending();

  /** Returns all pending transfers for a given connection (for Connection-Reuse batching). */
  @Query(
      "SELECT * FROM pending_transfer WHERE connection_id = :connectionId"
          + " AND status IN ('PENDING', 'FAILED') AND retry_count < max_retries"
          + " ORDER BY sort_order, created_at")
  @NonNull
  List<PendingTransfer> getPendingForConnection(@NonNull String connectionId);

  /** Returns distinct connection IDs that have pending work. */
  @Query(
      "SELECT DISTINCT connection_id FROM pending_transfer"
          + " WHERE status IN ('PENDING', 'FAILED') AND retry_count < max_retries")
  @NonNull
  List<String> getConnectionsWithPendingWork();

  /** Observes the count of pending/active transfers (for badge display). */
  @Query("SELECT COUNT(*) FROM pending_transfer WHERE status IN ('PENDING', 'ACTIVE')")
  @NonNull
  LiveData<Integer> observePendingCount();

  /**
   * Returns the count of pending/failed retryable transfers synchronously (for app-start resume).
   */
  @Query(
      "SELECT COUNT(*) FROM pending_transfer WHERE status IN ('PENDING', 'FAILED')"
          + " AND retry_count < max_retries")
  int getPendingCountSync();

  /** Observes total bytes transferred for a batch (for overall progress). */
  @Query("SELECT SUM(bytes_transferred) FROM pending_transfer WHERE batch_id = :batchId")
  @NonNull
  LiveData<Long> observeBatchBytesTransferred(@NonNull String batchId);

  /** Observes total file size for a batch (for overall progress). */
  @Query("SELECT SUM(file_size) FROM pending_transfer WHERE batch_id = :batchId")
  @NonNull
  LiveData<Long> observeBatchTotalBytes(@NonNull String batchId);

  /** Inserts a single transfer and returns its generated ID. */
  @Insert
  long insert(@NonNull PendingTransfer transfer);

  /** Inserts multiple transfers (e.g. folder upload batch). */
  @Insert
  @NonNull
  List<Long> insertAll(@NonNull List<PendingTransfer> transfers);

  /** Updates a transfer entity. */
  @Update
  void update(@NonNull PendingTransfer transfer);

  /** Updates the status of a transfer. */
  @Query("UPDATE pending_transfer SET status = :status, updated_at = :now WHERE id = :id")
  void updateStatus(long id, @NonNull String status, long now);

  /**
   * Resets a single transfer to PENDING with retry count and progress reset (manual retry from UI).
   */
  @Query(
      "UPDATE pending_transfer SET status = 'PENDING', retry_count = 0, bytes_transferred = 0, updated_at = :now"
          + " WHERE id = :id")
  void resetToPending(long id, long now);

  /** Updates the bytes-transferred progress of a transfer. */
  @Query("UPDATE pending_transfer SET bytes_transferred = :bytes, updated_at = :now WHERE id = :id")
  void updateProgress(long id, long bytes, long now);

  /** Marks a transfer as failed, increments retry count, and records the error. */
  @Query(
      "UPDATE pending_transfer SET status = 'FAILED', last_error = :error,"
          + " retry_count = retry_count + 1, updated_at = :now WHERE id = :id")
  void markFailed(long id, @NonNull String error, long now);

  /** Cancels all pending/active transfers. */
  @Query(
      "UPDATE pending_transfer SET status = 'CANCELLED', updated_at = :now"
          + " WHERE status IN ('PENDING', 'ACTIVE')")
  void cancelAll(long now);

  /** Cancels a single transfer. */
  @Query("UPDATE pending_transfer SET status = 'CANCELLED', updated_at = :now WHERE id = :id")
  void cancel(long id, long now);

  /** Deletes completed/cancelled transfers older than the given timestamp. */
  @Query(
      "DELETE FROM pending_transfer WHERE status IN ('COMPLETED', 'CANCELLED')"
          + " AND updated_at < :olderThan")
  int cleanupOld(long olderThan);

  /**
   * Resets ACTIVE transfers back to PENDING with retry count and progress reset (crash recovery on
   * app start). Setting bytes_transferred to 0 is intentional for the current phase — it ensures
   * that uploads and downloads always restart from the beginning after an unclean interruption
   * (crash/reboot). This effectively disables the resume logic in {@code
   * TransferWorker.processUpload()}.
   */
  @Query(
      "UPDATE pending_transfer SET status = 'PENDING', retry_count = 0, bytes_transferred = 0, updated_at = :now"
          + " WHERE status = 'ACTIVE'")
  int resetActiveToRetry(long now);

  /**
   * Resets FAILED transfers back to PENDING with retry count and progress reset (for retry after
   * reboot). Setting bytes_transferred to 0 is intentional for the current phase — it ensures that
   * uploads and downloads always restart from the beginning. This effectively disables the resume
   * logic in {@code TransferWorker.processUpload()}.
   */
  @Query(
      "UPDATE pending_transfer SET status = 'PENDING', retry_count = 0, bytes_transferred = 0, updated_at = :now"
          + " WHERE status = 'FAILED'")
  int resetFailedToRetry(long now);

  /** Updates status only if the transfer is currently ACTIVE (avoids overwriting CANCELLED). */
  @Query(
      "UPDATE pending_transfer SET status = :status, updated_at = :now"
          + " WHERE id = :id AND status = 'ACTIVE'")
  void updateStatusIfActive(long id, @NonNull String status, long now);

  /** Returns the current status of a transfer (for cancellation checks during active transfer). */
  @Query("SELECT status FROM pending_transfer WHERE id = :id")
  @Nullable
  String getStatus(long id);

  /** Deletes a single transfer by ID. */
  @Query("DELETE FROM pending_transfer WHERE id = :id")
  void deleteById(long id);

  /** Counts PENDING/ACTIVE transfers for a given remote path (duplicate detection). */
  @Query(
      "SELECT COUNT(*) FROM pending_transfer WHERE remote_path = :remotePath"
          + " AND status IN ('PENDING', 'ACTIVE')")
  int countActiveForPath(@NonNull String remotePath);

  /** Returns the total count of all transfer entries. */
  @Query("SELECT COUNT(*) FROM pending_transfer")
  int countAll();

  /** Returns active/pending uploads for a given connection (for FileBrowser upload indicators). */
  @Query(
      "SELECT * FROM pending_transfer WHERE connection_id = :connectionId"
          + " AND transfer_type = 'UPLOAD' AND status IN ('PENDING', 'ACTIVE')")
  @NonNull
  List<PendingTransfer> getActiveUploadsForConnection(@NonNull String connectionId);

  /**
   * Returns active/pending downloads for a given connection (for FileBrowser download indicators).
   */
  @Query(
      "SELECT * FROM pending_transfer WHERE connection_id = :connectionId"
          + " AND transfer_type = 'DOWNLOAD' AND status IN ('PENDING', 'ACTIVE')")
  @NonNull
  List<PendingTransfer> getActiveDownloadsForConnection(@NonNull String connectionId);
}
