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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.work.WorkInfo;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

/** Finished sync work must be announced once, and old runs not at all (risk R8). */
public class SyncWorkReporterTest {

  private static WorkInfo work(UUID id, WorkInfo.State state) {
    return new WorkInfo(id, state, Collections.singleton("sambalite_folder_sync"));
  }

  @Test
  public void runsFinishedBeforeTheFirstListAreNotAnnounced() {
    SyncWorkReporter reporter = new SyncWorkReporter();
    UUID old = UUID.randomUUID();

    assertTrue(
        reporter
            .takeNewlyFinished(Collections.singletonList(work(old, WorkInfo.State.SUCCEEDED)))
            .isEmpty());
    assertTrue(
        reporter
            .takeNewlyFinished(Collections.singletonList(work(old, WorkInfo.State.SUCCEEDED)))
            .isEmpty());
  }

  @Test
  public void aRunIsAnnouncedOnceWhenItFinishes() {
    SyncWorkReporter reporter = new SyncWorkReporter();
    UUID old = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    reporter.takeNewlyFinished(Collections.singletonList(work(old, WorkInfo.State.SUCCEEDED)));

    assertTrue(
        reporter
            .takeNewlyFinished(
                Arrays.asList(
                    work(old, WorkInfo.State.SUCCEEDED), work(run, WorkInfo.State.RUNNING)))
            .isEmpty());

    List<WorkInfo> finished =
        reporter.takeNewlyFinished(
            Arrays.asList(work(old, WorkInfo.State.SUCCEEDED), work(run, WorkInfo.State.FAILED)));
    assertEquals(1, finished.size());
    assertEquals(run, finished.get(0).getId());

    // Later emissions (e.g. another Sync Now) must not repeat it.
    assertTrue(
        reporter
            .takeNewlyFinished(
                Arrays.asList(
                    work(old, WorkInfo.State.SUCCEEDED),
                    work(run, WorkInfo.State.FAILED),
                    work(UUID.randomUUID(), WorkInfo.State.ENQUEUED)))
            .isEmpty());
  }

  @Test
  public void cancelledAndRetriedRunsAreNotAnnouncedAsFinished() {
    SyncWorkReporter reporter = new SyncWorkReporter();
    reporter.takeNewlyFinished(null);

    assertTrue(
        reporter
            .takeNewlyFinished(
                Arrays.asList(
                    work(UUID.randomUUID(), WorkInfo.State.CANCELLED),
                    work(UUID.randomUUID(), WorkInfo.State.ENQUEUED)))
            .isEmpty());
  }
}
