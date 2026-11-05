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
import android.content.res.Resources;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.sync.SyncActionLog;
import de.schliweb.sambalite.sync.SyncConfig;
import de.schliweb.sambalite.sync.SyncDirection;
import de.schliweb.sambalite.sync.SyncErrorCategory;
import de.schliweb.sambalite.sync.SyncHealth;
import de.schliweb.sambalite.sync.SyncRunResult;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Texts and colors of the sync status in the System Monitor and its notifications (#50). */
public final class SyncStatusTexts {

  private SyncStatusTexts() {}

  /** The name of a sync: its local folder, else its remote path. */
  @NonNull
  public static String configName(@NonNull SyncConfig config) {
    String name = config.getLocalFolderDisplayName();
    if (name == null || name.isEmpty()) name = config.getRemotePath();
    return name == null || name.isEmpty() ? "?" : name;
  }

  @NonNull
  public static String number(int value) {
    return NumberFormat.getIntegerInstance().format(value);
  }

  // ---------------------------------------------------------------------------
  // State
  // ---------------------------------------------------------------------------

  @StringRes
  public static int statusLabel(@NonNull SyncHealth.Kind kind) {
    switch (kind) {
      case PAUSED:
        return R.string.sync_status_paused;
      case RUNNING:
        return R.string.sync_status_running;
      case NEVER_RUN:
        return R.string.sync_status_never;
      case STOPPED:
        return R.string.sync_status_stopped;
      case COMPLETED_WITH_ERRORS:
        return R.string.sync_status_with_errors;
      case OVERDUE:
        return R.string.sync_status_overdue;
      case WAITING_FOR_WIFI:
        return R.string.sync_status_waiting_wifi;
      case LOW_SPACE:
        return R.string.sync_status_low_space;
      case FAILED:
        return R.string.sync_status_failed;
      case COMPLETED:
      case COMPLETED_EARLIER:
      default:
        return R.string.sync_status_completed;
    }
  }

  /** Container color of the status chip. */
  @ColorRes
  public static int chipBackground(@NonNull SyncHealth health) {
    if (health.getKind() == SyncHealth.Kind.STOPPED
        || health.getKind() == SyncHealth.Kind.NEVER_RUN) {
      return R.color.sync_status_neutral_container;
    }
    switch (health.getLevel()) {
      case ERROR:
        return R.color.sync_status_error_container;
      case WARNING:
        return R.color.sync_status_warning_container;
      case RUNNING:
        return R.color.sync_status_running_container;
      case PAUSED:
        return R.color.sync_status_neutral_container;
      case OK:
      default:
        return R.color.sync_status_ok_container;
    }
  }

  /** Text color of the status chip. */
  @ColorRes
  public static int chipForeground(@NonNull SyncHealth health) {
    if (health.getKind() == SyncHealth.Kind.STOPPED
        || health.getKind() == SyncHealth.Kind.NEVER_RUN) {
      return R.color.sync_status_neutral_on_container;
    }
    switch (health.getLevel()) {
      case ERROR:
        return R.color.sync_status_error_on_container;
      case WARNING:
        return R.color.sync_status_warning_on_container;
      case RUNNING:
        return R.color.sync_status_running_on_container;
      case PAUSED:
        return R.color.sync_status_neutral_on_container;
      case OK:
      default:
        return R.color.sync_status_ok_on_container;
    }
  }

  /** Dot color of a result in the history strip and the history list. */
  @ColorRes
  public static int dotColor(@NonNull SyncRunResult.Status status) {
    switch (status) {
      case COMPLETED:
        return R.color.sync_status_ok_dot;
      case COMPLETED_WITH_ERRORS:
        return R.color.sync_status_warning_dot;
      case FAILED:
      case ABORTED_LOW_SPACE:
        return R.color.sync_status_error_dot;
      case STOPPED:
      case SKIPPED_NO_WIFI:
      default:
        return R.color.sync_status_neutral_dot;
    }
  }

  // ---------------------------------------------------------------------------
  // Config description
  // ---------------------------------------------------------------------------

  /** "DCIM/Camera → /fotos": local folder, direction arrow, remote path. */
  @NonNull
  public static String route(@NonNull SyncConfig config) {
    String local = config.getLocalFolderDisplayName();
    if (local == null || local.isEmpty()) local = "?";
    String remote = config.getRemotePath();
    if (remote == null || remote.isEmpty()) remote = "/";
    String arrow;
    SyncDirection direction = config.getDirection();
    if (direction == SyncDirection.LOCAL_TO_REMOTE) {
      arrow = " → ";
    } else if (direction == SyncDirection.REMOTE_TO_LOCAL) {
      arrow = " ← ";
    } else {
      arrow = " ↔ ";
    }
    return local + arrow + remote;
  }

  /** "Every hour · WiFi only". */
  @NonNull
  public static String schedule(@NonNull Context context, @NonNull SyncConfig config) {
    String interval = intervalLabel(context, config.getIntervalMinutes());
    if (config.isWifiOnly()) {
      return interval + " · " + context.getString(R.string.sync_wifi_only);
    }
    return interval;
  }

  @NonNull
  public static String intervalLabel(@NonNull Context context, int minutes) {
    switch (minutes) {
      case 0:
        return context.getString(R.string.sync_interval_manual);
      case 15:
        return context.getString(R.string.sync_interval_15min);
      case 30:
        return context.getString(R.string.sync_interval_30min);
      case 60:
        return context.getString(R.string.sync_interval_1h);
      case 360:
        return context.getString(R.string.sync_interval_6h);
      case 720:
        return context.getString(R.string.sync_interval_12h);
      case 1440:
        return context.getString(R.string.sync_interval_24h);
      default:
        return minutes < 0
            ? context.getString(R.string.sync_interval_manual)
            : context.getString(R.string.sync_interval_every, duration(context, minutes * 60_000L));
    }
  }

  /** "3 days", "2 hours", "25 minutes": the largest whole unit. */
  @NonNull
  public static String duration(@NonNull Context context, long millis) {
    Resources res = context.getResources();
    long minutes = Math.max(1, millis / 60_000L);
    if (minutes < 120) {
      return res.getQuantityString(R.plurals.sync_duration_minutes, (int) minutes, (int) minutes);
    }
    long hours = minutes / 60;
    if (hours < 48) {
      return res.getQuantityString(R.plurals.sync_duration_hours, (int) hours, (int) hours);
    }
    int days = (int) (hours / 24);
    return res.getQuantityString(R.plurals.sync_duration_days, days, days);
  }

  /** "6 minutes ago". */
  @NonNull
  public static String relative(@NonNull Context context, long time, long now) {
    if (Math.abs(now - time) < DateUtils.MINUTE_IN_MILLIS) {
      return context.getString(R.string.monitor_just_now);
    }
    return DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS).toString();
  }

  /** The time of day, or date and time if not today. */
  @NonNull
  public static String clock(@NonNull Context context, long time, long now) {
    if (DateUtils.isToday(time)) {
      return DateFormat.getTimeFormat(context).format(time);
    }
    return DateUtils.formatDateTime(
        context,
        time,
        DateUtils.FORMAT_SHOW_DATE
            | DateUtils.FORMAT_SHOW_TIME
            | DateUtils.FORMAT_ABBREV_MONTH
            | (Math.abs(now - time) < 6 * DateUtils.DAY_IN_MILLIS
                ? DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_WEEKDAY
                : 0));
  }

  /** The line under the route: when it ran, or since when it does not run. */
  @NonNull
  public static String whenLine(
      @NonNull Context context,
      @NonNull SyncConfig config,
      @NonNull SyncHealth health,
      @Nullable SyncRunResult latestRun,
      long now) {
    long lastSuccess = config.getLastSyncTimestamp();
    switch (health.getKind()) {
      case NEVER_RUN:
        return context.getString(R.string.sync_never_synced);
      case COMPLETED_EARLIER:
        return context.getString(R.string.sync_last_sync, relative(context, lastSuccess, now));
      case OVERDUE:
      case WAITING_FOR_WIFI:
        return context.getString(
            R.string.sync_when_overdue,
            duration(context, now - lastSuccess),
            intervalLabel(context, config.getIntervalMinutes()));
      case FAILED:
      case LOW_SPACE:
        if (lastSuccess > 0 && latestRun != null) {
          return context.getString(
              R.string.sync_when_failed_since,
              relative(context, latestRun.getEndedAt(), now),
              relative(context, lastSuccess, now));
        }
        break;
      default:
        break;
    }
    if (latestRun == null) {
      return lastSuccess > 0
          ? context.getString(R.string.sync_last_sync, relative(context, lastSuccess, now))
          : context.getString(R.string.sync_never_synced);
    }
    return context.getString(
        R.string.sync_when_run,
        relative(context, latestRun.getEndedAt(), now),
        clock(context, latestRun.getStartedAt(), now),
        DateFormat.getTimeFormat(context).format(latestRun.getEndedAt()));
  }

  // ---------------------------------------------------------------------------
  // Results
  // ---------------------------------------------------------------------------

  /** "12 uploaded, 0 downloaded, 1,988 skipped, 23 errors", or what stopped the run. */
  @NonNull
  public static String resultSummary(@NonNull Context context, @NonNull SyncRunResult result) {
    switch (result.getStatus()) {
      case SKIPPED_NO_WIFI:
        return result.getOccurrences() > 1
            ? context.getString(R.string.sync_result_skipped_wifi_times, result.getOccurrences())
            : context.getString(R.string.sync_result_skipped_wifi);
      case FAILED:
        if (result.getProcessedFiles() == 0) {
          return context.getString(
              R.string.sync_result_failed, failureLabel(context, result.getFailureCategory()));
        }
        break;
      case ABORTED_LOW_SPACE:
        if (result.getProcessedFiles() == 0) {
          return context.getString(R.string.sync_result_low_space);
        }
        break;
      default:
        break;
    }
    String counts =
        context.getString(
            R.string.sync_result_counts,
            number(result.getCount(SyncActionLog.Action.UPLOADED)),
            number(result.getCount(SyncActionLog.Action.DOWNLOADED)),
            number(result.getCount(SyncActionLog.Action.SKIPPED)),
            number(result.getErrorCount()));
    if (result.getStatus() == SyncRunResult.Status.STOPPED) {
      return context.getString(R.string.sync_result_stopped, counts);
    }
    if (result.getStatus() == SyncRunResult.Status.ABORTED_LOW_SPACE) {
      return context.getString(R.string.sync_result_low_space_after, counts);
    }
    if (result.getStatus() == SyncRunResult.Status.FAILED) {
      return context.getString(
          R.string.sync_result_failed_after,
          failureLabel(context, result.getFailureCategory()),
          counts);
    }
    return counts;
  }

  /** The counters that are not zero apart from the four main ones, or null if there are none. */
  @Nullable
  public static String extras(@NonNull Context context, @NonNull SyncRunResult result) {
    Resources res = context.getResources();
    List<String> parts = new ArrayList<>();
    addExtra(
        res, parts, R.plurals.sync_extra_dirs, result.getCount(SyncActionLog.Action.CREATED_DIR));
    addExtra(
        res, parts, R.plurals.sync_extra_deleted, result.getCount(SyncActionLog.Action.DELETED));
    addExtra(
        res,
        parts,
        R.plurals.sync_extra_mirror_deleted,
        result.getCount(SyncActionLog.Action.MIRROR_DELETED));
    addExtra(
        res,
        parts,
        R.plurals.sync_extra_mirror_trashed,
        result.getCount(SyncActionLog.Action.MIRROR_TRASHED));
    if (result.getCount(SyncActionLog.Action.MIRROR_ABORTED) > 0) {
      parts.add(context.getString(R.string.sync_extra_mirror_skipped));
    }
    addExtra(
        res,
        parts,
        R.plurals.sync_extra_timestamps_set,
        result.getCount(SyncActionLog.Action.TIMESTAMP_SET));
    addExtra(
        res,
        parts,
        R.plurals.sync_extra_timestamps_failed,
        result.getCount(SyncActionLog.Action.TIMESTAMP_FAILED));
    addExtra(
        res,
        parts,
        R.plurals.sync_extra_discarded,
        result.getCount(SyncActionLog.Action.DISCARDED));
    if (parts.isEmpty()) return null;
    return context.getString(R.string.sync_extras, String.join(", ", parts));
  }

  private static void addExtra(Resources res, List<String> parts, int pluralRes, int count) {
    if (count > 0) parts.add(res.getQuantityString(pluralRes, count, count));
  }

  // ---------------------------------------------------------------------------
  // Error categories
  // ---------------------------------------------------------------------------

  @NonNull
  public static String categoryTitle(@NonNull Context context, @NonNull SyncErrorCategory c) {
    return context.getString(categoryTitleRes(c));
  }

  @StringRes
  private static int categoryTitleRes(@NonNull SyncErrorCategory c) {
    switch (c) {
      case ACCESS_DENIED:
        return R.string.sync_error_access_denied;
      case INVALID_NAME:
        return R.string.sync_error_invalid_name;
      case NAME_TOO_LONG:
        return R.string.sync_error_name_too_long;
      case SERVER_FULL:
        return R.string.sync_error_server_full;
      case FILE_IN_USE:
        return R.string.sync_error_file_in_use;
      case NOT_FOUND:
        return R.string.sync_error_not_found;
      case NETWORK:
        return R.string.sync_error_network;
      case AUTH:
        return R.string.sync_error_auth;
      case SHARE_NOT_FOUND:
        return R.string.sync_error_share_not_found;
      case LOCAL_ACCESS:
        return R.string.sync_error_local_access;
      case LOCAL_FOLDER_MISSING:
        return R.string.sync_error_local_folder_missing;
      case LOCAL_SPACE:
        return R.string.sync_error_local_space;
      case FOLDER_SKIPPED:
        return R.string.sync_error_folder_skipped;
      case OTHER:
      default:
        return R.string.sync_error_other;
    }
  }

  /** What the user can do about errors of this category. */
  @NonNull
  public static String categoryHint(@NonNull Context context, @NonNull SyncErrorCategory c) {
    switch (c) {
      case ACCESS_DENIED:
        return context.getString(R.string.sync_error_access_denied_hint);
      case INVALID_NAME:
        return context.getString(R.string.sync_error_invalid_name_hint);
      case NAME_TOO_LONG:
        return context.getString(R.string.sync_error_name_too_long_hint);
      case SERVER_FULL:
        return context.getString(R.string.sync_error_server_full_hint);
      case FILE_IN_USE:
        return context.getString(R.string.sync_error_file_in_use_hint);
      case NOT_FOUND:
        return context.getString(R.string.sync_error_not_found_hint);
      case NETWORK:
        return context.getString(R.string.sync_error_network_hint);
      case AUTH:
        return context.getString(R.string.sync_error_auth_hint);
      case SHARE_NOT_FOUND:
        return context.getString(R.string.sync_error_share_not_found_hint);
      case LOCAL_ACCESS:
        return context.getString(R.string.sync_error_local_access_hint);
      case LOCAL_FOLDER_MISSING:
        return context.getString(R.string.sync_error_local_folder_missing_hint);
      case LOCAL_SPACE:
        return context.getString(R.string.sync_error_local_space_hint);
      case FOLDER_SKIPPED:
        return context.getString(R.string.sync_error_folder_skipped_hint);
      case OTHER:
      default:
        return context.getString(R.string.sync_error_other_hint);
    }
  }

  @NonNull
  public static String failureLabel(@NonNull Context context, @Nullable SyncErrorCategory c) {
    return categoryTitle(context, c != null ? c : SyncErrorCategory.OTHER);
  }

  /** "8× Access denied, 14× Invalid file name". */
  @NonNull
  public static String categorySummary(@NonNull Context context, @NonNull SyncRunResult result) {
    List<String> parts = new ArrayList<>();
    for (Map.Entry<SyncErrorCategory, Integer> e : result.getErrorCategories().entrySet()) {
      parts.add(
          context.getString(
              R.string.sync_error_count_times, e.getValue(), categoryTitle(context, e.getKey())));
    }
    return String.join(", ", parts);
  }

  // ---------------------------------------------------------------------------
  // Notifications
  // ---------------------------------------------------------------------------

  @NonNull
  public static String problemTitle(
      @NonNull Context context, @NonNull String name, @NonNull SyncRunResult result) {
    switch (result.getStatus()) {
      case COMPLETED_WITH_ERRORS:
        return context
            .getResources()
            .getQuantityString(
                R.plurals.sync_notify_errors_title,
                result.getErrorCount(),
                name,
                result.getErrorCount());
      case ABORTED_LOW_SPACE:
        return context.getString(R.string.sync_notify_low_space_title, name);
      case FAILED:
      default:
        return context.getString(R.string.sync_notify_failed_title, name);
    }
  }

  @NonNull
  public static String problemText(@NonNull Context context, @NonNull SyncRunResult result) {
    if (result.getStatus() == SyncRunResult.Status.COMPLETED_WITH_ERRORS) {
      return categorySummary(context, result);
    }
    SyncErrorCategory c =
        result.getStatus() == SyncRunResult.Status.ABORTED_LOW_SPACE
            ? SyncErrorCategory.LOCAL_SPACE
            : result.getFailureCategory();
    return categoryHint(context, c != null ? c : SyncErrorCategory.OTHER);
  }

  // ---------------------------------------------------------------------------
  // Log entries
  // ---------------------------------------------------------------------------

  @StringRes
  public static int actionLabel(@NonNull SyncActionLog.Action action) {
    switch (action) {
      case UPLOADED:
        return R.string.sync_action_uploaded;
      case DOWNLOADED:
        return R.string.sync_action_downloaded;
      case SKIPPED:
        return R.string.sync_action_skipped;
      case CREATED_DIR:
        return R.string.sync_action_created_dir;
      case ERROR:
        return R.string.sync_action_error;
      case DELETED:
        return R.string.sync_action_deleted;
      case MIRROR_DELETED:
        return R.string.sync_action_mirror_deleted;
      case MIRROR_TRASHED:
        return R.string.sync_action_mirror_trashed;
      case MIRROR_ABORTED:
        return R.string.sync_action_mirror_aborted;
      case TIMESTAMP_SET:
        return R.string.sync_action_timestamp_set;
      case TIMESTAMP_FAILED:
        return R.string.sync_action_timestamp_failed;
      case ABORTED_LOW_SPACE:
        return R.string.sync_action_low_space;
      case DISCARDED:
      default:
        return R.string.sync_action_discarded;
    }
  }

  /** Translates the skip reasons the worker writes; other details are returned as they are. */
  @NonNull
  public static String detail(@NonNull Context context, @NonNull String detail) {
    switch (detail) {
      case "same (DB state)":
      case "unchanged (stored metadata)":
        return context.getString(R.string.sync_skip_unchanged);
      case "same (size+timestamp)":
        return context.getString(R.string.sync_skip_same);
      case "remote newer or within tolerance":
        return context.getString(R.string.sync_skip_remote_newer);
      case "local newer or within tolerance":
        return context.getString(R.string.sync_skip_local_newer);
      default:
        return detail;
    }
  }
}
