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

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.schliweb.sambalite.util.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;

/**
 * Keeps the results of the last {@link #MAX_RUNS} runs per sync config (#50), newest first, plus
 * the notification state of each config.
 *
 * <p>The results live in their own preferences file rather than in the config JSON of {@link
 * SyncRepository}: the worker writes a result after every run, and writing the whole config list
 * from the worker could overwrite an edit the user saves at the same moment.
 */
public final class SyncRunHistoryStore {

  private static final String TAG = "SyncRunHistoryStore";
  private static final String PREFS_NAME = "sync_run_history";
  private static final String KEY_RUNS_PREFIX = "runs_";
  private static final String KEY_MUTED_PREFIX = "muted_";
  private static final String KEY_OVERDUE_NOTIFIED_PREFIX = "overdue_notified_";

  /** The number of results kept per config. */
  public static final int MAX_RUNS = 20;

  private static final Object LOCK = new Object();

  private final SharedPreferences prefs;

  public SyncRunHistoryStore(@NonNull Context context) {
    this.prefs =
        context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  /** What {@link #record} did. */
  public static final class Recorded {
    @Nullable private final SyncRunResult previousRun;
    private final boolean merged;

    Recorded(@Nullable SyncRunResult previousRun, boolean merged) {
      this.previousRun = previousRun;
      this.merged = merged;
    }

    /** The newest result before this one that was an actual run, or null. */
    @Nullable
    public SyncRunResult getPreviousRun() {
      return previousRun;
    }

    /** True if a WiFi skip was merged into the skip before it. */
    public boolean isMerged() {
      return merged;
    }
  }

  /**
   * Stores {@code result} as the newest result of its config. A WiFi skip right after another WiFi
   * skip is merged into it. For a run, each error gets its streak: one more than in the previous
   * run if the same path failed there too.
   */
  @NonNull
  public Recorded record(@NonNull SyncRunResult result) {
    synchronized (LOCK) {
      List<SyncRunResult> runs = load(result.getConfigId());
      SyncRunResult previousRun = firstRun(runs);
      if (result.getStatus() == SyncRunResult.Status.SKIPPED_NO_WIFI
          && !runs.isEmpty()
          && runs.get(0).getStatus() == SyncRunResult.Status.SKIPPED_NO_WIFI) {
        runs.get(0).mergeSkip(result.getEndedAt());
        save(result.getConfigId(), runs);
        return new Recorded(previousRun, true);
      }
      if (result.getStatus().isRun() && previousRun != null) {
        Map<String, Integer> previousStreaks = new HashMap<>();
        for (SyncRunResult.RunError e : previousRun.getErrors()) {
          previousStreaks.put(e.getPath(), e.getStreak());
        }
        for (SyncRunResult.RunError e : result.getErrors()) {
          Integer before = previousStreaks.get(e.getPath());
          e.setStreak(before != null ? before + 1 : 1);
        }
      }
      if (result.getStatus().isRun()) {
        setOverdueNotified(result.getConfigId(), false);
      }
      runs.add(0, result);
      while (runs.size() > MAX_RUNS) {
        runs.remove(runs.size() - 1);
      }
      save(result.getConfigId(), runs);
      return new Recorded(previousRun, false);
    }
  }

  /** Returns the stored results of a config, newest first. */
  @NonNull
  public List<SyncRunResult> getHistory(@NonNull String configId) {
    synchronized (LOCK) {
      return load(configId);
    }
  }

  /** Returns the newest result of a config (a run or a skip), or null. */
  @Nullable
  public SyncRunResult getLatest(@NonNull String configId) {
    List<SyncRunResult> runs = getHistory(configId);
    return runs.isEmpty() ? null : runs.get(0);
  }

  /** Returns the newest result of a config that was an actual run, or null. */
  @Nullable
  public SyncRunResult getLatestRun(@NonNull String configId) {
    return firstRun(getHistory(configId));
  }

  /** Removes everything stored for a config, e.g. when the config is deleted. */
  public void delete(@NonNull String configId) {
    synchronized (LOCK) {
      prefs
          .edit()
          .remove(KEY_RUNS_PREFIX + configId)
          .remove(KEY_MUTED_PREFIX + configId)
          .remove(KEY_OVERDUE_NOTIFIED_PREFIX + configId)
          .apply();
    }
  }

  /** Returns whether problem notifications are turned off for a config. */
  public boolean isMuted(@NonNull String configId) {
    return prefs.getBoolean(KEY_MUTED_PREFIX + configId, false);
  }

  public void setMuted(@NonNull String configId, boolean muted) {
    prefs.edit().putBoolean(KEY_MUTED_PREFIX + configId, muted).apply();
  }

  /** Returns whether the user was already told that the config is overdue since its last run. */
  public boolean isOverdueNotified(@NonNull String configId) {
    return prefs.getBoolean(KEY_OVERDUE_NOTIFIED_PREFIX + configId, false);
  }

  public void setOverdueNotified(@NonNull String configId, boolean notified) {
    prefs.edit().putBoolean(KEY_OVERDUE_NOTIFIED_PREFIX + configId, notified).apply();
  }

  @Nullable
  private static SyncRunResult firstRun(@NonNull List<SyncRunResult> runs) {
    for (SyncRunResult r : runs) {
      if (r.getStatus().isRun()) return r;
    }
    return null;
  }

  @NonNull
  private List<SyncRunResult> load(@NonNull String configId) {
    List<SyncRunResult> runs = new ArrayList<>();
    String json = prefs.getString(KEY_RUNS_PREFIX + configId, null);
    if (json == null) return runs;
    try {
      JSONArray array = new JSONArray(json);
      for (int i = 0; i < array.length(); i++) {
        runs.add(SyncRunResult.fromJson(array.getJSONObject(i)));
      }
    } catch (JSONException e) {
      LogUtils.w(TAG, "Could not read the run history of " + configId + ": " + e.getMessage());
    }
    return runs;
  }

  private void save(@NonNull String configId, @NonNull List<SyncRunResult> runs) {
    try {
      JSONArray array = new JSONArray();
      for (SyncRunResult r : runs) {
        array.put(r.toJson());
      }
      prefs.edit().putString(KEY_RUNS_PREFIX + configId, array.toString()).apply();
    } catch (JSONException e) {
      LogUtils.w(TAG, "Could not save the run history of " + configId + ": " + e.getMessage());
    }
  }
}
