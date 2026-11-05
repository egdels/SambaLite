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
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;
import de.schliweb.sambalite.BuildConfig;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.repository.ConnectionRepositoryImpl;
import de.schliweb.sambalite.sync.SyncActionLog;
import de.schliweb.sambalite.sync.SyncConfig;
import de.schliweb.sambalite.sync.SyncErrorCategory;
import de.schliweb.sambalite.sync.SyncRunLog;
import de.schliweb.sambalite.sync.SyncRunResult;
import de.schliweb.sambalite.util.BatteryOptimizationUtils;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A plain-text report of the sync state for bug reports (#50): app and device, per sync its
 * settings, stored results, errors and the end of its last run log, and the technical details of
 * the System Monitor. It contains server and share names and file paths, never usernames or
 * passwords.
 */
public final class DiagnosticsReport {

  /** Lines of each run log that go into the report (skipped files are only counted). */
  static final int LOG_TAIL_LINES = 200;

  private DiagnosticsReport() {}

  @NonNull
  public static String build(
      @NonNull Context context,
      @NonNull SyncMonitorState state,
      @Nullable String technicalDetails) {
    SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    StringBuilder sb = new StringBuilder();
    sb.append("SambaLite diagnostics\n");
    sb.append("Generated: ").append(time.format(new Date(state.now))).append('\n');
    sb.append("App: ")
        .append(BuildConfig.VERSION_NAME)
        .append(" (")
        .append(BuildConfig.VERSION_CODE)
        .append(")\n");
    sb.append("Android: ")
        .append(Build.VERSION.RELEASE)
        .append(" (SDK ")
        .append(Build.VERSION.SDK_INT)
        .append("), ")
        .append(Build.MANUFACTURER)
        .append(' ')
        .append(Build.MODEL)
        .append('\n');
    sb.append("Notifications enabled: ")
        .append(NotificationManagerCompat.from(context).areNotificationsEnabled())
        .append('\n');
    sb.append("Battery optimization ignored: ")
        .append(BatteryOptimizationUtils.isIgnoringBatteryOptimizations(context))
        .append("\n\n");

    Map<String, SmbConnection> connections = new HashMap<>();
    try {
      for (SmbConnection c : new ConnectionRepositoryImpl(context).getAllConnections()) {
        connections.put(c.getId(), c);
      }
    } catch (RuntimeException e) {
      sb.append("(connections could not be read: ").append(e.getMessage()).append(")\n");
    }

    sb.append("=== Folder syncs (").append(state.entries.size()).append(") ===\n");
    for (SyncMonitorState.Entry entry : state.entries) {
      appendConfig(context, sb, time, entry, connections.get(entry.config.getConnectionId()));
    }
    if (technicalDetails != null) {
      sb.append("\n").append(technicalDetails).append('\n');
    }
    return sb.toString();
  }

  private static void appendConfig(
      @NonNull Context context,
      @NonNull StringBuilder sb,
      @NonNull SimpleDateFormat time,
      @NonNull SyncMonitorState.Entry entry,
      @Nullable SmbConnection connection) {
    SyncConfig c = entry.config;
    sb.append("\n--- ").append(SyncStatusTexts.configName(c)).append(" ---\n");
    sb.append("Id: ").append(c.getId()).append('\n');
    if (connection != null) {
      sb.append("Server: ")
          .append(connection.getServer())
          .append(':')
          .append(connection.getPort())
          .append(", share: ")
          .append(connection.getShare())
          .append(", encrypt: ")
          .append(connection.isEncryptData())
          .append('\n');
    } else {
      sb.append("Server: (connection not found)\n");
    }
    sb.append("Route: ").append(SyncStatusTexts.route(c)).append('\n');
    sb.append("Direction: ")
        .append(c.getDirection())
        .append(", interval: ")
        .append(c.getIntervalMinutes())
        .append(" min, wifiOnly: ")
        .append(c.isWifiOnly())
        .append(", mirror: ")
        .append(c.isMirror())
        .append(", enabled: ")
        .append(c.isEnabled())
        .append('\n');
    sb.append("Last successful sync: ")
        .append(
            c.getLastSyncTimestamp() > 0
                ? time.format(new Date(c.getLastSyncTimestamp()))
                : "never")
        .append('\n');
    sb.append("State: ")
        .append(entry.health.getLevel())
        .append(" / ")
        .append(entry.health.getKind())
        .append(entry.health.isOverdue() ? " (overdue)" : "")
        .append('\n');

    sb.append("Runs (newest first):\n");
    if (entry.history.isEmpty()) {
      sb.append("  none stored\n");
    }
    for (SyncRunResult r : entry.history) {
      sb.append("  ")
          .append(time.format(new Date(r.getStartedAt())))
          .append(" – ")
          .append(time.format(new Date(r.getEndedAt())))
          .append("  ")
          .append(r.getStatus());
      if (r.getOccurrences() > 1) sb.append(" ×").append(r.getOccurrences());
      if (r.getFailureCategory() != null)
        sb.append(" [").append(r.getFailureCategory()).append(']');
      sb.append("  up=")
          .append(r.getCount(SyncActionLog.Action.UPLOADED))
          .append(" down=")
          .append(r.getCount(SyncActionLog.Action.DOWNLOADED))
          .append(" skip=")
          .append(r.getCount(SyncActionLog.Action.SKIPPED))
          .append(" err=")
          .append(r.getErrorCount());
      if (r.getReason() != null) sb.append("  reason: ").append(r.getReason());
      sb.append('\n');
    }

    if (entry.latestRun != null && !entry.latestRun.getErrors().isEmpty()) {
      sb.append("Errors of the last run:\n");
      for (Map.Entry<SyncErrorCategory, Integer> e :
          entry.latestRun.getErrorCategories().entrySet()) {
        sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
      }
      for (SyncRunResult.RunError e : entry.latestRun.getErrors()) {
        sb.append("  ")
            .append(e.getPath())
            .append("  [")
            .append(e.getCategory())
            .append(", ")
            .append(e.getStreak())
            .append(" run(s)]  ")
            .append(e.getMessage())
            .append('\n');
      }
    }

    List<SyncRunLog.Entry> log = SyncRunLog.read(context, c.getId());
    int skipped = 0;
    for (SyncRunLog.Entry e : log) {
      if (e.getAction() == SyncActionLog.Action.SKIPPED) skipped++;
    }
    int shown = 0;
    StringBuilder tail = new StringBuilder();
    for (int i = log.size() - 1; i >= 0 && shown < LOG_TAIL_LINES; i--) {
      SyncRunLog.Entry e = log.get(i);
      if (e.getAction() == SyncActionLog.Action.SKIPPED) {
        continue;
      }
      tail.insert(
          0,
          "  "
              + time.format(new Date(e.getTime()))
              + "  "
              + e.getAction()
              + "  "
              + e.getPath()
              + (e.getDetail().isEmpty() ? "" : "  " + e.getDetail())
              + '\n');
      shown++;
    }
    sb.append("Last run log: ")
        .append(log.size())
        .append(" entries, ")
        .append(skipped)
        .append(" skipped files not listed\n")
        .append(tail);
  }
}
