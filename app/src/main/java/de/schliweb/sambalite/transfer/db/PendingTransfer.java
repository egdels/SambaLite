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
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Room entity representing a queued file transfer (upload or download). Persists transfer state to
 * enable resume after app kill and provides the data source for the transfer queue UI.
 */
@Entity(
    tableName = "pending_transfer",
    indices = {@Index(value = {"status"}), @Index(value = {"connection_id", "status"})})
public class PendingTransfer {

  @PrimaryKey(autoGenerate = true)
  public long id;

  /** Transfer direction: "UPLOAD" or "DOWNLOAD". */
  @ColumnInfo(name = "transfer_type")
  @NonNull
  public String transferType = "";

  /** SAF-URI of the local file (source for upload, target for download). */
  @ColumnInfo(name = "local_uri")
  @NonNull
  public String localUri = "";

  /** Full remote SMB path. */
  @ColumnInfo(name = "remote_path")
  @NonNull
  public String remotePath = "";

  /** Connection ID referencing the stored SmbConnection. */
  @ColumnInfo(name = "connection_id")
  @NonNull
  public String connectionId = "";

  /** Display name shown in the queue UI. */
  @ColumnInfo(name = "display_name")
  @NonNull
  public String displayName = "";

  /** MIME type of the file. */
  @ColumnInfo(name = "mime_type")
  @NonNull
  public String mimeType = "";

  /** Total file size in bytes (0 if unknown). */
  @ColumnInfo(name = "file_size")
  public long fileSize;

  /** Number of bytes successfully transferred so far. */
  @ColumnInfo(name = "bytes_transferred")
  public long bytesTransferred;

  /** Status: PENDING, ACTIVE, COMPLETED, FAILED, CANCELLED. */
  @ColumnInfo(name = "status")
  @NonNull
  public String status = "PENDING";

  /** Number of retry attempts so far. */
  @ColumnInfo(name = "retry_count")
  public int retryCount;

  /** Maximum allowed retries before giving up. */
  @ColumnInfo(name = "max_retries")
  public int maxRetries = 3;

  /** Last error message (null if no error). */
  @ColumnInfo(name = "last_error")
  @Nullable
  public String lastError;

  /** Timestamp when this transfer was enqueued (epoch millis). */
  @ColumnInfo(name = "created_at")
  public long createdAt;

  /** Timestamp of the last status or progress update (epoch millis). */
  @ColumnInfo(name = "updated_at")
  public long updatedAt;

  /** Batch ID grouping related transfers (e.g. folder upload). */
  @ColumnInfo(name = "batch_id")
  @NonNull
  public String batchId = "";

  /** Sort order within a batch. */
  @ColumnInfo(name = "sort_order")
  public int sortOrder;
}
