/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.sync;

import de.schliweb.sambalite.util.LogUtils;

/**
 * Compares local and remote file metadata to determine whether a file needs to be synced.
 *
 * <p>Uses a robust comparison strategy:
 *
 * <ol>
 *   <li>Size comparison (primary criterion)
 *   <li>Timestamp comparison with configurable tolerance (secondary criterion)
 * </ol>
 *
 * <p>This avoids unnecessary re-downloads/re-uploads caused by minor timestamp differences (e.g.
 * due to SAF limitations, filesystem rounding, or failed setLastModified calls).
 */
public class SyncComparator {

  private static final String TAG = "SyncComparator";

  /** Default timestamp tolerance: 3 seconds. */
  static final long DEFAULT_TIMESTAMP_TOLERANCE_MS = 3000;

  private final long timestampToleranceMs;

  /** Creates a SyncComparator with the default tolerance of 3 seconds. */
  public SyncComparator() {
    this(DEFAULT_TIMESTAMP_TOLERANCE_MS);
  }

  /**
   * Creates a SyncComparator with a custom tolerance.
   *
   * @param timestampToleranceMs tolerance in milliseconds for timestamp comparison
   */
  public SyncComparator(long timestampToleranceMs) {
    this.timestampToleranceMs = timestampToleranceMs;
  }

  /**
   * Determines whether two files are considered the same (no sync needed).
   *
   * @param localSize local file size in bytes
   * @param localLastModified local file last modified timestamp (epoch millis)
   * @param remoteSize remote file size in bytes
   * @param remoteLastModified remote file last modified timestamp (epoch millis)
   * @return true if the files are considered identical
   */
  public boolean isSame(
      long localSize, long localLastModified, long remoteSize, long remoteLastModified) {
    if (localSize != remoteSize) {
      LogUtils.d(
          TAG,
          "[SYNC] Size differs: local=" + localSize + " remote=" + remoteSize + " → needs sync");
      return false;
    }

    long diff = Math.abs(localLastModified - remoteLastModified);
    if (diff <= timestampToleranceMs) {
      LogUtils.d(
          TAG,
          "[SYNC] Same size ("
              + localSize
              + "), timestamp diff="
              + diff
              + "ms (tolerance="
              + timestampToleranceMs
              + "ms) → same");
      return true;
    }

    LogUtils.d(
        TAG,
        "[SYNC] Same size ("
            + localSize
            + "), but timestamp diff="
            + diff
            + "ms exceeds tolerance="
            + timestampToleranceMs
            + "ms → needs sync");
    return false;
  }

  /**
   * Determines whether the remote file is newer than the local file, considering tolerance.
   *
   * @param localLastModified local file last modified timestamp (epoch millis)
   * @param remoteLastModified remote file last modified timestamp (epoch millis)
   * @return true if remote is newer (beyond tolerance)
   */
  public boolean isRemoteNewer(long localLastModified, long remoteLastModified) {
    return remoteLastModified - localLastModified > timestampToleranceMs;
  }

  /**
   * Determines whether the local file is newer than the remote file, considering tolerance.
   *
   * @param localLastModified local file last modified timestamp (epoch millis)
   * @param remoteLastModified remote file last modified timestamp (epoch millis)
   * @return true if local is newer (beyond tolerance)
   */
  public boolean isLocalNewer(long localLastModified, long remoteLastModified) {
    return localLastModified - remoteLastModified > timestampToleranceMs;
  }
}
