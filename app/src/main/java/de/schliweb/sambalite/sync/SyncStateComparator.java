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

import androidx.annotation.Nullable;
import de.schliweb.sambalite.sync.db.FileSyncState;

/** Compares current file metadata with the last successfully synchronized state. */
final class SyncStateComparator {

  private SyncStateComparator() {}

  static boolean hasLocalBaseline(@Nullable FileSyncState state) {
    return state != null && state.localSize >= 0 && state.localLastModified >= 0;
  }

  static boolean localMatches(
      @Nullable FileSyncState state, long localSize, long localLastModified) {
    return hasLocalBaseline(state)
        && state.localSize == localSize
        && Math.abs(state.localLastModified - localLastModified)
            <= SyncComparator.DEFAULT_TIMESTAMP_TOLERANCE_MS;
  }

  static boolean remoteMatches(
      @Nullable FileSyncState state, long remoteSize, long remoteLastModified) {
    return state != null
        && state.remoteSize == remoteSize
        && Math.abs(state.remoteLastModified - remoteLastModified)
            <= SyncComparator.DEFAULT_TIMESTAMP_TOLERANCE_MS;
  }

  static boolean bothMatch(
      @Nullable FileSyncState state,
      long localSize,
      long localLastModified,
      long remoteSize,
      long remoteLastModified) {
    return localMatches(state, localSize, localLastModified)
        && remoteMatches(state, remoteSize, remoteLastModified);
  }

  static boolean localChangedWhileRemoteMatches(
      @Nullable FileSyncState state,
      long localSize,
      long localLastModified,
      long remoteSize,
      long remoteLastModified) {
    return hasLocalBaseline(state)
        && !localMatches(state, localSize, localLastModified)
        && remoteMatches(state, remoteSize, remoteLastModified)
        && localLastModified - remoteLastModified > SyncComparator.DEFAULT_TIMESTAMP_TOLERANCE_MS;
  }
}
