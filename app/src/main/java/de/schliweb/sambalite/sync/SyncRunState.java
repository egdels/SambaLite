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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.WorkInfo;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which folder syncs are running right now, derived from the WorkManager infos of the sync work
 * (tag {@link SyncManager#UNIQUE_WORK_NAME}).
 *
 * <p>A running "Sync Now" carries the tag {@code config_id:<id>}. The periodic run (and "sync all")
 * has no config tag and syncs every enabled config with an interval. As on the main screen, a
 * running "Sync Now" takes precedence: while one runs, only its config counts as running.
 */
public final class SyncRunState {

  private static final String TAG_MANUAL = "manual_sync";
  private static final String TAG_CONFIG_PREFIX = "config_id:";

  private final Set<String> syncNowConfigIds;
  private final boolean periodicRunning;

  private SyncRunState(@NonNull Set<String> syncNowConfigIds, boolean periodicRunning) {
    this.syncNowConfigIds = syncNowConfigIds;
    this.periodicRunning = periodicRunning;
  }

  /** Derives the running state from the infos of the sync work; null means nothing runs. */
  @NonNull
  public static SyncRunState from(@Nullable List<WorkInfo> workInfos) {
    Set<String> ids = new HashSet<>();
    boolean periodic = false;
    if (workInfos != null) {
      for (WorkInfo info : workInfos) {
        if (info.getState() != WorkInfo.State.RUNNING) {
          continue;
        }
        String configId = null;
        boolean manual = false;
        for (String tag : info.getTags()) {
          if (tag.equals(TAG_MANUAL)) {
            manual = true;
          } else if (tag.startsWith(TAG_CONFIG_PREFIX)) {
            configId = tag.substring(TAG_CONFIG_PREFIX.length());
          }
        }
        if (configId != null) {
          ids.add(configId);
        } else if (!manual) {
          periodic = true;
        }
      }
    }
    return new SyncRunState(ids, periodic);
  }

  /** Returns whether the given config is being synced right now. */
  public boolean isRunning(@NonNull SyncConfig config) {
    if (syncNowConfigIds.isEmpty()) {
      return periodicRunning && config.isEnabled() && config.getIntervalMinutes() > 0;
    }
    return syncNowConfigIds.contains(config.getId());
  }

  /**
   * Returns whether the given config is synced by the periodic (or "sync all") run rather than by
   * its own "Sync Now", which decides how {@link SyncManager#stopSync} has to stop it.
   */
  public boolean isRunningInPeriodicRun(@NonNull SyncConfig config) {
    return !syncNowConfigIds.contains(config.getId());
  }
}
