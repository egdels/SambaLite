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

/**
 * The state of one sync config as the System Monitor shows it (#50): a level for the traffic light
 * and the reason behind it. Derived from the config, its stored results and whether it runs now.
 */
public final class SyncHealth {

  /** How urgent the state is; the order is from best to worst. */
  public enum Level {
    /** Paused by the user; does not count for the overall state. */
    PAUSED,
    /** Everything fine (or never run yet). */
    OK,
    /** Running right now. */
    RUNNING,
    /** Works, but needs a look: errors in files, overdue. */
    WARNING,
    /** Does not sync. */
    ERROR
  }

  /** What the state is about. */
  public enum Kind {
    PAUSED,
    RUNNING,
    NEVER_RUN,
    /** Synced before run results were stored; only the time of the last sync is known. */
    COMPLETED_EARLIER,
    COMPLETED,
    STOPPED,
    COMPLETED_WITH_ERRORS,
    OVERDUE,
    WAITING_FOR_WIFI,
    LOW_SPACE,
    FAILED
  }

  /** Extra time after which a sync counts as overdue, on top of twice its interval. */
  static final long OVERDUE_GRACE_MS = 60L * 60 * 1000;

  @NonNull private final Level level;
  @NonNull private final Kind kind;
  private final boolean overdue;

  private SyncHealth(@NonNull Level level, @NonNull Kind kind, boolean overdue) {
    this.level = level;
    this.kind = kind;
    this.overdue = overdue;
  }

  @NonNull
  public Level getLevel() {
    return level;
  }

  @NonNull
  public Kind getKind() {
    return kind;
  }

  /** Whether the last successful sync is clearly longer ago than the interval. */
  public boolean isOverdue() {
    return overdue;
  }

  /**
   * Evaluates a config.
   *
   * @param latest the newest stored result, a run or a WiFi skip
   * @param latestRun the newest stored result that was an actual run
   * @param running whether the config syncs right now
   */
  @NonNull
  public static SyncHealth evaluate(
      @NonNull SyncConfig config,
      @Nullable SyncRunResult latest,
      @Nullable SyncRunResult latestRun,
      boolean running,
      long now) {
    if (!config.isEnabled()) {
      return new SyncHealth(Level.PAUSED, Kind.PAUSED, false);
    }
    boolean overdue = isOverdue(config, now);
    if (running) {
      return new SyncHealth(Level.RUNNING, Kind.RUNNING, overdue);
    }
    if (latestRun != null) {
      switch (latestRun.getStatus()) {
        case FAILED:
          return new SyncHealth(Level.ERROR, Kind.FAILED, overdue);
        case ABORTED_LOW_SPACE:
          return new SyncHealth(Level.ERROR, Kind.LOW_SPACE, overdue);
        default:
          break;
      }
    }
    if (overdue) {
      boolean waitingForWifi =
          latest != null && latest.getStatus() == SyncRunResult.Status.SKIPPED_NO_WIFI;
      return new SyncHealth(
          Level.WARNING, waitingForWifi ? Kind.WAITING_FOR_WIFI : Kind.OVERDUE, true);
    }
    if (latestRun == null) {
      return new SyncHealth(
          Level.OK,
          config.getLastSyncTimestamp() > 0 ? Kind.COMPLETED_EARLIER : Kind.NEVER_RUN,
          false);
    }
    switch (latestRun.getStatus()) {
      case COMPLETED_WITH_ERRORS:
        return new SyncHealth(Level.WARNING, Kind.COMPLETED_WITH_ERRORS, false);
      case STOPPED:
        return new SyncHealth(Level.OK, Kind.STOPPED, false);
      default:
        return new SyncHealth(Level.OK, Kind.COMPLETED, false);
    }
  }

  /**
   * Returns whether a periodic config has not synced successfully for clearly longer than its
   * interval: twice the interval plus an hour, which leaves room for Android delaying background
   * work. Manual-only and paused configs, and configs that never synced, are never overdue.
   */
  public static boolean isOverdue(@NonNull SyncConfig config, long now) {
    if (!config.isEnabled() || config.getIntervalMinutes() <= 0) return false;
    long last = config.getLastSyncTimestamp();
    if (last <= 0) return false;
    long intervalMs = config.getIntervalMinutes() * 60L * 1000L;
    return now - last > 2 * intervalMs + OVERDUE_GRACE_MS;
  }
}
