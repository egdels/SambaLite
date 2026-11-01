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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.WorkInfo;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Decides which finished sync work the main screen still has to announce. WorkManager keeps
 * finished work for a while and the observer receives the whole list on every change, so without
 * this every start of the screen and every later change repeated "Sync completed" for old runs.
 *
 * <p>Work that had already finished when the screen first received the list counts as announced.
 * Each finished work is announced at most once.
 */
final class SyncWorkReporter {

  private final Set<UUID> announced = new HashSet<>();
  private boolean baselineTaken;

  /**
   * Returns the work in {@code workInfos} that finished (SUCCEEDED or FAILED) since the last call
   * and has not been announced yet. On the first call nothing is returned.
   */
  @NonNull
  synchronized List<WorkInfo> takeNewlyFinished(@Nullable List<WorkInfo> workInfos) {
    List<WorkInfo> result = new ArrayList<>();
    if (workInfos != null) {
      for (WorkInfo info : workInfos) {
        WorkInfo.State state = info.getState();
        if (state != WorkInfo.State.SUCCEEDED && state != WorkInfo.State.FAILED) {
          continue;
        }
        if (announced.add(info.getId()) && baselineTaken) {
          result.add(info);
        }
      }
    }
    baselineTaken = true;
    return result;
  }
}
