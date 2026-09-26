/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.sync.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Room entity representing the last successfully synchronized state of a file. Stores metadata for
 * both sides so the worker can distinguish a local update from a remote timestamp mismatch.
 */
@Entity(
    tableName = "file_sync_state",
    indices = {
      @Index(
          value = {"root_uri", "relative_path"},
          unique = true)
    })
public class FileSyncState {

  @PrimaryKey(autoGenerate = true)
  public long id;

  /** Root URI of the sync target (e.g., SAF tree URI). */
  @ColumnInfo(name = "root_uri")
  @NonNull
  public String rootUri = "";

  /** Relative path within the sync root. */
  @ColumnInfo(name = "relative_path")
  @NonNull
  public String relativePath = "";

  /** Full remote SMB path. */
  @ColumnInfo(name = "remote_path")
  @NonNull
  public String remotePath = "";

  /** Local file size in bytes at the last successful sync. */
  @ColumnInfo(name = "local_size", defaultValue = "-1")
  public long localSize = -1;

  /** Local last modified time at the last successful sync. */
  @ColumnInfo(name = "local_last_modified", defaultValue = "-1")
  public long localLastModified = -1;

  /** Remote file size in bytes. */
  @ColumnInfo(name = "remote_size")
  public long remoteSize;

  /** Remote last modified time in epoch millis. */
  @ColumnInfo(name = "remote_last_modified")
  public long remoteLastModified;

  /** Timestamp when this file was last synced (epoch millis). */
  @ColumnInfo(name = "synced_at")
  public long syncedAt;

  /** Whether the local timestamp was successfully preserved to match remote. */
  @ColumnInfo(name = "timestamp_preserved")
  public boolean timestampPreserved;
}
