/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui.monitor;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import de.schliweb.sambalite.sync.SyncConfig;
import de.schliweb.sambalite.sync.SyncErrorCategory;
import de.schliweb.sambalite.sync.SyncHealth;
import de.schliweb.sambalite.sync.SyncRunResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** Tests the overall banner and the card order of the System Monitor with many syncs (#40). */
@RunWith(RobolectricTestRunner.class)
public class SyncMonitorUiTest {

  private static final long NOW = 1_800_000_000_000L;

  private Context context;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
  }

  private static SyncConfig config(String name, boolean enabled) {
    SyncConfig config = new SyncConfig();
    config.setId(name);
    config.setLocalFolderDisplayName(name);
    config.setRemotePath("remote/" + name);
    config.setEnabled(enabled);
    config.setIntervalMinutes(0);
    return config;
  }

  private static SyncMonitorState.Entry failed(String name) {
    SyncConfig config = config(name, true);
    SyncRunResult run =
        SyncRunResult.notStarted(
            name, SyncRunResult.Status.FAILED, "no server", SyncErrorCategory.NETWORK, NOW - 1000);
    return new SyncMonitorState.Entry(
        config,
        SyncHealth.evaluate(config, run, run, false, NOW),
        Collections.singletonList(run),
        run,
        null);
  }

  private static SyncMonitorState.Entry neverRun(String name, boolean enabled, boolean running) {
    SyncConfig config = config(name, enabled);
    return new SyncMonitorState.Entry(
        config,
        SyncHealth.evaluate(config, null, null, running, NOW),
        new ArrayList<>(),
        null,
        null);
  }

  private SyncMonitorUi.Banner banner(List<SyncMonitorState.Entry> entries) {
    return SyncMonitorUi.banner(context, new SyncMonitorState(entries, NOW));
  }

  private static List<SyncMonitorState.Entry> failedEntries(int count) {
    List<SyncMonitorState.Entry> entries = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      entries.add(failed("S" + i));
    }
    return entries;
  }

  @Test
  public void bannerNamesUpToThreeFurtherProblems() {
    SyncMonitorUi.Banner banner = banner(failedEntries(4));

    assertEquals("S0 does not sync", banner.title);
    assertEquals("Also: S1 does not sync; S2 does not sync; S3 does not sync.", banner.detail);
  }

  @Test
  public void bannerCountsTheRestOfManyProblems() {
    SyncMonitorUi.Banner banner = banner(failedEntries(25));

    assertEquals("S0 does not sync", banner.title);
    assertEquals("Also: S1 does not sync; S2 does not sync; 22 more.", banner.detail);
  }

  @Test
  public void bannerOfASingleProblemPointsToItsCard() {
    assertEquals("Tap the sync below for details.", banner(failedEntries(1)).detail);
  }

  @Test
  public void cardsShowProblemsFirstAndPausedLastInConfigOrder() {
    List<SyncMonitorState.Entry> entries = new ArrayList<>();
    entries.add(neverRun("paused", false, false));
    entries.add(neverRun("ok1", true, false));
    entries.add(failed("failed1"));
    entries.add(neverRun("running", true, true));
    entries.add(neverRun("ok2", true, false));
    entries.add(failed("failed2"));

    List<String> names = new ArrayList<>();
    for (SyncMonitorState.Entry e : SyncMonitorUi.inCardOrder(entries)) {
      names.add(e.config.getId());
    }

    assertEquals(List.of("failed1", "failed2", "ok1", "running", "ok2", "paused"), names);
    assertEquals("paused", entries.get(0).config.getId());
  }
}
