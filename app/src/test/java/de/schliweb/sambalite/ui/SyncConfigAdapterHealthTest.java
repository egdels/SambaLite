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

import de.schliweb.sambalite.R;
import de.schliweb.sambalite.sync.SyncConfig;
import de.schliweb.sambalite.sync.SyncErrorCategory;
import de.schliweb.sambalite.sync.SyncHealth;
import de.schliweb.sambalite.sync.SyncRunResult;
import org.junit.Test;

/** Tests which syncs are marked as a problem on their card of the start screen (#40). */
public class SyncConfigAdapterHealthTest {

  private static final long NOW = 1_800_000_000_000L;

  private static SyncConfig config() {
    SyncConfig config = new SyncConfig();
    config.setId("c");
    config.setEnabled(true);
    config.setIntervalMinutes(0);
    return config;
  }

  @Test
  public void failedSyncIsMarkedAsError() {
    SyncRunResult run =
        SyncRunResult.notStarted(
            "c", SyncRunResult.Status.FAILED, "no server", SyncErrorCategory.NETWORK, NOW - 1000);

    assertEquals(
        R.color.sync_status_error_on_container,
        SyncConfigAdapter.problemColor(SyncHealth.evaluate(config(), run, run, false, NOW)));
  }

  @Test
  public void syncWithoutProblemKeepsTheNormalColor() {
    assertEquals(
        0, SyncConfigAdapter.problemColor(SyncHealth.evaluate(config(), null, null, false, NOW)));
    assertEquals(0, SyncConfigAdapter.problemColor(null));
  }
}
