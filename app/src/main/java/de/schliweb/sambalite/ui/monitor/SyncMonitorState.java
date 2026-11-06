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

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.Data;
import androidx.work.WorkInfo;
import de.schliweb.sambalite.sync.FolderSyncWorker;
import de.schliweb.sambalite.sync.SyncConfig;
import de.schliweb.sambalite.sync.SyncHealth;
import de.schliweb.sambalite.sync.SyncRepository;
import de.schliweb.sambalite.sync.SyncRunHistoryStore;
import de.schliweb.sambalite.sync.SyncRunResult;
import de.schliweb.sambalite.sync.SyncRunState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the System Monitor shows about the folder syncs at one moment (#50): per config its
 * stored results, its state and, while it runs, its progress.
 */
public final class SyncMonitorState {

  /** Progress of a running config, as published by {@link FolderSyncWorker}. */
  public static final class Progress {
    /** A run that has not published anything yet. */
    static final Progress NONE = new Progress(0, 0, "", 0, 0, 0, 0);

    public final int processed;
    public final int expected;
    @NonNull public final String path;

    /** The counters of the running run so far, shown instead of the last run's while it runs. */
    public final int uploaded;

    public final int downloaded;
    public final int skipped;
    public final int errors;

    Progress(
        int processed,
        int expected,
        @NonNull String path,
        int uploaded,
        int downloaded,
        int skipped,
        int errors) {
      this.processed = processed;
      this.expected = expected;
      this.path = path;
      this.uploaded = uploaded;
      this.downloaded = downloaded;
      this.skipped = skipped;
      this.errors = errors;
    }
  }

  /** One config. */
  public static final class Entry {
    @NonNull public final SyncConfig config;
    @NonNull public final SyncHealth health;
    @NonNull public final List<SyncRunResult> history;
    @Nullable public final SyncRunResult latest;
    @Nullable public final SyncRunResult latestRun;
    @Nullable public final Progress progress;

    Entry(
        @NonNull SyncConfig config,
        @NonNull SyncHealth health,
        @NonNull List<SyncRunResult> history,
        @Nullable SyncRunResult latestRun,
        @Nullable Progress progress) {
      this.config = config;
      this.health = health;
      this.history = history;
      this.latest = history.isEmpty() ? null : history.get(0);
      this.latestRun = latestRun;
      this.progress = progress;
    }
  }

  @NonNull public final List<Entry> entries;
  public final long now;

  private SyncMonitorState(@NonNull List<Entry> entries, long now) {
    this.entries = entries;
    this.now = now;
  }

  /** Loads the state of all configs; {@code workInfos} are the infos of the sync work. */
  @NonNull
  public static SyncMonitorState load(
      @NonNull Context context, @Nullable List<WorkInfo> workInfos) {
    long now = System.currentTimeMillis();
    SyncRunState runState = SyncRunState.from(workInfos);
    Map<String, Progress> progress = progressByConfig(workInfos);
    SyncRunHistoryStore store = new SyncRunHistoryStore(context);
    List<Entry> entries = new ArrayList<>();
    for (SyncConfig config : new SyncRepository(context).getAllSyncConfigs()) {
      List<SyncRunResult> history = store.getHistory(config.getId());
      SyncRunResult latestRun = null;
      for (SyncRunResult r : history) {
        if (r.getStatus().isRun()) {
          latestRun = r;
          break;
        }
      }
      // The progress names the config a worker is on; a periodic run would otherwise count every
      // config with an interval as running while it works through them one by one.
      boolean running =
          progress.isEmpty() ? runState.isRunning(config) : progress.containsKey(config.getId());
      SyncHealth health =
          SyncHealth.evaluate(
              config, history.isEmpty() ? null : history.get(0), latestRun, running, now);
      entries.add(
          new Entry(
              config, health, history, latestRun, running ? progress.get(config.getId()) : null));
    }
    return new SyncMonitorState(entries, now);
  }

  @Nullable
  public Entry find(@NonNull String configId) {
    for (Entry e : entries) {
      if (e.config.getId().equals(configId)) return e;
    }
    return null;
  }

  @NonNull
  private static Map<String, Progress> progressByConfig(@Nullable List<WorkInfo> workInfos) {
    Map<String, Progress> map = new HashMap<>();
    if (workInfos == null) return map;
    for (WorkInfo info : workInfos) {
      if (info.getState() != WorkInfo.State.RUNNING) continue;
      Data data = info.getProgress();
      String id = data.getString(FolderSyncWorker.KEY_PROGRESS_CONFIG_ID);
      if (id == null) continue;
      String path = data.getString(FolderSyncWorker.KEY_PROGRESS_PATH);
      map.put(
          id,
          new Progress(
              data.getInt(FolderSyncWorker.KEY_PROGRESS_PROCESSED, 0),
              data.getInt(FolderSyncWorker.KEY_PROGRESS_EXPECTED, 0),
              path != null ? path : "",
              data.getInt(FolderSyncWorker.KEY_PROGRESS_UPLOADED, 0),
              data.getInt(FolderSyncWorker.KEY_PROGRESS_DOWNLOADED, 0),
              data.getInt(FolderSyncWorker.KEY_PROGRESS_SKIPPED, 0),
              data.getInt(FolderSyncWorker.KEY_PROGRESS_ERRORS, 0)));
    }
    return map;
  }
}
