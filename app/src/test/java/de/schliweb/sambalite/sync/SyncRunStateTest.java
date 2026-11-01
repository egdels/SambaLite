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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.work.WorkInfo;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

/** Tests which folder syncs count as running, which decides between "Sync Now" and "Stop Sync". */
public class SyncRunStateTest {

  private static WorkInfo work(WorkInfo.State state, String... tags) {
    return new WorkInfo(
        UUID.randomUUID(),
        state,
        new HashSet<>(Arrays.asList(tags)),
        androidx.work.Data.EMPTY,
        androidx.work.Data.EMPTY,
        0,
        0);
  }

  private static SyncConfig config(String id, int intervalMinutes) {
    SyncConfig config = new SyncConfig();
    config.setId(id);
    config.setEnabled(true);
    config.setIntervalMinutes(intervalMinutes);
    return config;
  }

  @Test
  public void nothingRunning_noConfigIsRunning() {
    SyncRunState state = SyncRunState.from(null);
    assertFalse(state.isRunning(config("a", 60)));
  }

  @Test
  public void syncNow_onlyItsConfigIsRunning_andItIsNotThePeriodicRun() {
    List<WorkInfo> infos =
        Arrays.asList(
            work(WorkInfo.State.RUNNING, SyncManager.UNIQUE_WORK_NAME, "manual_sync", "config_id:a"));
    SyncRunState state = SyncRunState.from(infos);

    assertTrue(state.isRunning(config("a", 0)));
    assertFalse(state.isRunning(config("b", 60)));
    assertFalse(state.isRunningInPeriodicRun(config("a", 0)));
  }

  @Test
  public void periodicRun_coversConfigsWithAnInterval() {
    List<WorkInfo> infos = Arrays.asList(work(WorkInfo.State.RUNNING, SyncManager.UNIQUE_WORK_NAME));
    SyncRunState state = SyncRunState.from(infos);

    assertTrue(state.isRunning(config("a", 60)));
    assertTrue(state.isRunningInPeriodicRun(config("a", 60)));
    assertFalse("manual-only configs are skipped by the periodic run", state.isRunning(config("m", 0)));
  }

  @Test
  public void queuedOrFinishedWork_doesNotCount() {
    List<WorkInfo> infos =
        Arrays.asList(
            work(WorkInfo.State.ENQUEUED, SyncManager.UNIQUE_WORK_NAME),
            work(WorkInfo.State.SUCCEEDED, "manual_sync", "config_id:a"),
            work(WorkInfo.State.CANCELLED, "manual_sync", "config_id:b"));
    SyncRunState state = SyncRunState.from(infos);

    assertFalse(state.isRunning(config("a", 60)));
    assertFalse(state.isRunning(config("b", 60)));
  }
}
