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

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.schliweb.sambalite.util.LogUtils;
import java.util.List;

/**
 * High-level store for sync state metadata. Wraps the Room DAO and provides convenient methods for
 * saving and querying remote file state after sync operations.
 */
public class SyncStateStore {

  private static final String TAG = "SyncStateStore";
  private final FileSyncStateDao dao;

  public SyncStateStore(@NonNull Context context) {
    this.dao = SyncDatabase.getInstance(context).fileSyncStateDao();
  }

  /** Constructor for testing with injected DAO. */
  SyncStateStore(@NonNull FileSyncStateDao dao) {
    this.dao = dao;
  }

  /**
   * Saves the remote state after a successful sync (download or upload).
   *
   * @param rootUri the root URI of the sync target
   * @param relativePath the relative path within the sync root
   * @param remotePath the full remote SMB path
   * @param remoteSize the remote file size in bytes
   * @param remoteLastModified the remote last modified time in epoch millis
   * @param timestampPreserved whether the local timestamp was successfully set
   */
  public void saveRemoteState(
      @NonNull String rootUri,
      @NonNull String relativePath,
      @NonNull String remotePath,
      long remoteSize,
      long remoteLastModified,
      boolean timestampPreserved) {
    try {
      FileSyncState state = new FileSyncState();
      state.rootUri = rootUri;
      state.relativePath = relativePath;
      state.remotePath = remotePath;
      state.remoteSize = remoteSize;
      state.remoteLastModified = remoteLastModified;
      state.syncedAt = System.currentTimeMillis();
      state.timestampPreserved = timestampPreserved;

      // Preserve existing ID for upsert (REPLACE strategy needs matching rowid)
      FileSyncState existing = dao.findByPath(rootUri, relativePath);
      if (existing != null) {
        state.id = existing.id;
      }

      dao.upsert(state);
      LogUtils.d(
          TAG,
          "[TIMESTAMP] Saved sync state: "
              + relativePath
              + " (size="
              + remoteSize
              + ", remoteModified="
              + remoteLastModified
              + ", preserved="
              + timestampPreserved
              + ")");
    } catch (Exception e) {
      LogUtils.e(
          TAG, "[TIMESTAMP] Failed to save sync state: " + relativePath + ": " + e.getMessage());
    }
  }

  /**
   * Returns the stored remote state for a file, or null if not tracked.
   *
   * @param rootUri the root URI of the sync target
   * @param relativePath the relative path within the sync root
   * @return the stored sync state, or null
   */
  @Nullable
  public FileSyncState getRemoteState(@NonNull String rootUri, @NonNull String relativePath) {
    try {
      return dao.findByPath(rootUri, relativePath);
    } catch (Exception e) {
      LogUtils.e(
          TAG, "[TIMESTAMP] Failed to get sync state: " + relativePath + ": " + e.getMessage());
      return null;
    }
  }

  /**
   * Returns all stored sync states for a given root URI.
   *
   * @param rootUri the root URI of the sync target
   * @return list of sync states
   */
  @NonNull
  public List<FileSyncState> getAllForRoot(@NonNull String rootUri) {
    try {
      return dao.findByRootUri(rootUri);
    } catch (Exception e) {
      LogUtils.e(
          TAG,
          "[TIMESTAMP] Failed to get sync states for root: " + rootUri + ": " + e.getMessage());
      return List.of();
    }
  }

  /**
   * Deletes the sync state for a specific file.
   *
   * @param rootUri the root URI of the sync target
   * @param relativePath the relative path within the sync root
   */
  public void deleteState(@NonNull String rootUri, @NonNull String relativePath) {
    try {
      dao.deleteByPath(rootUri, relativePath);
    } catch (Exception e) {
      LogUtils.e(
          TAG, "[TIMESTAMP] Failed to delete sync state: " + relativePath + ": " + e.getMessage());
    }
  }

  /**
   * Deletes all sync states for a given root URI (e.g., when a sync config is removed).
   *
   * @param rootUri the root URI of the sync target
   */
  public void deleteAllForRoot(@NonNull String rootUri) {
    try {
      int deleted = dao.deleteByRootUri(rootUri);
      LogUtils.i(TAG, "[TIMESTAMP] Deleted " + deleted + " sync states for root: " + rootUri);
    } catch (Exception e) {
      LogUtils.e(
          TAG,
          "[TIMESTAMP] Failed to delete sync states for root: " + rootUri + ": " + e.getMessage());
    }
  }

  /** Returns the count of files where timestamp was preserved. */
  public int getTimestampPreservedCount() {
    try {
      return dao.countTimestampPreserved();
    } catch (Exception e) {
      return 0;
    }
  }

  /** Returns the total count of tracked sync states. */
  public int getTotalCount() {
    try {
      return dao.countAll();
    } catch (Exception e) {
      return 0;
    }
  }
}
