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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.schliweb.sambalite.sync.SyncActionLog;
import de.schliweb.sambalite.sync.SyncRunLog;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Answers "why was file X not synced?" (#50) from the run logs of the syncs and, for files the last
 * run did not touch, from the stored sync state of each file.
 */
public final class SyncLogSearch {

  /** At most this many results are returned. */
  public static final int MAX_RESULTS = 500;

  /** The run log of one sync. */
  public static final class Source {
    @NonNull final String configName;
    @NonNull final List<SyncRunLog.Entry> entries;

    public Source(@NonNull String configName, @NonNull List<SyncRunLog.Entry> entries) {
      this.configName = configName;
      this.entries = entries;
    }
  }

  /** A file that is tracked as synced but not in the last run log of its sync. */
  public static final class Tracked {
    @NonNull final String configName;
    @NonNull final String path;
    final long syncedAt;

    public Tracked(@NonNull String configName, @NonNull String path, long syncedAt) {
      this.configName = configName;
      this.path = path;
      this.syncedAt = syncedAt;
    }
  }

  /** One result. {@link #action} is null for a file known only from the sync state. */
  public static final class Result {
    @NonNull public final String configName;
    @NonNull public final String path;
    @Nullable public final SyncActionLog.Action action;
    @NonNull public final String detail;
    public final long time;

    Result(
        @NonNull String configName,
        @NonNull String path,
        @Nullable SyncActionLog.Action action,
        @NonNull String detail,
        long time) {
      this.configName = configName;
      this.path = path;
      this.action = action;
      this.detail = detail;
      this.time = time;
    }
  }

  private SyncLogSearch() {}

  /**
   * Searches the logs. With a query, every entry whose path contains it (ignoring case) is found,
   * skipped files included, since their reason is often the answer. Without a query, the entries of
   * the last runs are listed, skipped files only if {@code showSkipped}. Errors come first, then
   * the newest entries.
   */
  @NonNull
  public static List<Result> search(
      @NonNull List<Source> sources,
      @NonNull List<Tracked> tracked,
      @Nullable String query,
      boolean showSkipped) {
    String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    boolean includeSkipped = showSkipped || !q.isEmpty();
    List<Result> errors = new ArrayList<>();
    List<Result> others = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (Source source : sources) {
      for (SyncRunLog.Entry e : source.entries) {
        if (e.getPath().isEmpty()) continue;
        if (!includeSkipped && e.getAction() == SyncActionLog.Action.SKIPPED) continue;
        if (!q.isEmpty() && !e.getPath().toLowerCase(Locale.ROOT).contains(q)) continue;
        Result r =
            new Result(source.configName, e.getPath(), e.getAction(), e.getDetail(), e.getTime());
        if (e.getAction() == SyncActionLog.Action.ERROR) {
          errors.add(r);
        } else {
          others.add(r);
        }
        seen.add(source.configName + '\n' + e.getPath());
      }
    }
    if (!q.isEmpty()) {
      for (Tracked t : tracked) {
        if (!t.path.toLowerCase(Locale.ROOT).contains(q)) continue;
        if (seen.contains(t.configName + '\n' + t.path)) continue;
        others.add(new Result(t.configName, t.path, null, "", t.syncedAt));
      }
    }
    errors.sort((a, b) -> Long.compare(b.time, a.time));
    others.sort((a, b) -> Long.compare(b.time, a.time));
    List<Result> results = new ArrayList<>(errors);
    results.addAll(others);
    return results.size() > MAX_RESULTS
        ? new ArrayList<>(results.subList(0, MAX_RESULTS))
        : results;
  }
}
