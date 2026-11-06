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
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.sync.SyncActionLog;
import de.schliweb.sambalite.sync.SyncHealth;
import de.schliweb.sambalite.sync.SyncRunResult;

/** Fills the shared views of the System Monitor: status chip, counters, live progress, card. */
public final class SyncMonitorUi {

  private SyncMonitorUi() {}

  static void bindChip(@NonNull TextView chip, @NonNull SyncHealth health) {
    Context context = chip.getContext();
    chip.setText(SyncStatusTexts.statusLabel(health.getKind()));
    chip.setBackgroundTintList(
        ColorStateList.valueOf(
            ContextCompat.getColor(context, SyncStatusTexts.chipBackground(health))));
    chip.setTextColor(ContextCompat.getColor(context, SyncStatusTexts.chipForeground(health)));
  }

  /**
   * Shows the four main counters of a result; always all four, so that "0 errors" is a statement.
   * Without a result, dashes are shown.
   */
  static void bindCounters(@NonNull View counters, @Nullable SyncRunResult result) {
    if (result == null) {
      TextView errors = counters.findViewById(R.id.counter_errors);
      ((TextView) counters.findViewById(R.id.counter_uploaded)).setText("–");
      ((TextView) counters.findViewById(R.id.counter_downloaded)).setText("–");
      ((TextView) counters.findViewById(R.id.counter_skipped)).setText("–");
      errors.setText("–");
      errors.setTextColor(
          MaterialColors.getColor(counters, com.google.android.material.R.attr.colorOnSurface));
      counters.setContentDescription(null);
      return;
    }
    bindCounters(
        counters,
        result.getCount(SyncActionLog.Action.UPLOADED),
        result.getCount(SyncActionLog.Action.DOWNLOADED),
        result.getCount(SyncActionLog.Action.SKIPPED),
        result.getErrorCount());
  }

  /** Shows the counters of the running run, so they are never the previous run's while it runs. */
  static void bindCounters(@NonNull View counters, @NonNull SyncMonitorState.Progress progress) {
    bindCounters(
        counters, progress.uploaded, progress.downloaded, progress.skipped, progress.errors);
  }

  private static void bindCounters(
      @NonNull View counters, int uploaded, int downloaded, int skipped, int errors) {
    Context context = counters.getContext();
    ((TextView) counters.findViewById(R.id.counter_uploaded))
        .setText(SyncStatusTexts.number(uploaded));
    ((TextView) counters.findViewById(R.id.counter_downloaded))
        .setText(SyncStatusTexts.number(downloaded));
    ((TextView) counters.findViewById(R.id.counter_skipped))
        .setText(SyncStatusTexts.number(skipped));
    TextView errorsView = counters.findViewById(R.id.counter_errors);
    errorsView.setText(SyncStatusTexts.number(errors));
    errorsView.setTextColor(
        errors > 0
            ? ContextCompat.getColor(context, R.color.sync_status_error_dot)
            : MaterialColors.getColor(counters, com.google.android.material.R.attr.colorOnSurface));
    counters.setContentDescription(
        context.getString(
            R.string.sync_result_counts,
            SyncStatusTexts.number(uploaded),
            SyncStatusTexts.number(downloaded),
            SyncStatusTexts.number(skipped),
            SyncStatusTexts.number(errors)));
  }

  /** Shows the progress of a running sync, or hides the view. */
  static void bindLive(@NonNull View live, @Nullable SyncMonitorState.Progress progress) {
    if (progress == null) {
      live.setVisibility(View.GONE);
      return;
    }
    live.setVisibility(View.VISIBLE);
    Context context = live.getContext();
    TextView count = live.findViewById(R.id.live_count);
    LinearProgressIndicator bar = live.findViewById(R.id.live_bar);
    TextView path = live.findViewById(R.id.live_path);
    if (progress.expected > 0) {
      count.setText(
          context.getString(
              R.string.sync_live_progress,
              SyncStatusTexts.number(progress.processed),
              SyncStatusTexts.number(progress.expected)));
      bar.setIndeterminate(false);
      bar.setProgressCompat(
          (int) Math.min(1000, 1000L * progress.processed / Math.max(1, progress.expected)), true);
    } else {
      count.setText(
          context.getString(
              R.string.sync_live_progress_unknown, SyncStatusTexts.number(progress.processed)));
      if (!bar.isIndeterminate()) {
        bar.setVisibility(View.INVISIBLE);
        bar.setIndeterminate(true);
        bar.setVisibility(View.VISIBLE);
      }
    }
    path.setText(progress.path);
    path.setVisibility(progress.path.isEmpty() ? View.GONE : View.VISIBLE);
  }

  /** Fills a card of item_sync_status_card. */
  public static void bindCard(@NonNull View card, @NonNull SyncMonitorState.Entry entry, long now) {
    Context context = card.getContext();
    TextView name = card.findViewById(R.id.sync_name);
    TextView chip = card.findViewById(R.id.sync_status_chip);
    TextView route = card.findViewById(R.id.sync_route);
    TextView when = card.findViewById(R.id.sync_when);
    View live = card.findViewById(R.id.sync_live);
    View counters = card.findViewById(R.id.sync_counters);
    View historyRow = card.findViewById(R.id.sync_history_row);
    RunHistoryStripView strip = card.findViewById(R.id.sync_history);
    TextView historyLabel = card.findViewById(R.id.sync_history_label);

    name.setText(SyncStatusTexts.configName(entry.config));
    bindChip(chip, entry.health);
    route.setText(
        SyncStatusTexts.route(entry.config)
            + " · "
            + SyncStatusTexts.schedule(context, entry.config));

    boolean running = entry.health.getKind() == SyncHealth.Kind.RUNNING;
    if (running) {
      when.setVisibility(View.GONE);
      SyncMonitorState.Progress progress =
          entry.progress != null ? entry.progress : SyncMonitorState.Progress.NONE;
      bindLive(live, progress);
      bindCounters(counters, progress);
    } else {
      when.setVisibility(View.VISIBLE);
      when.setText(
          SyncStatusTexts.whenLine(context, entry.config, entry.health, entry.latestRun, now));
      live.setVisibility(View.GONE);
      bindCounters(counters, entry.latestRun);
    }

    if (entry.history.isEmpty() && !running) {
      historyRow.setVisibility(View.GONE);
    } else {
      historyRow.setVisibility(View.VISIBLE);
      strip.setRuns(entry.history);
      if (running) strip.addRunning();
      int n = Math.min(SyncRunHistoryStoreLimits.STRIP, entry.history.size());
      // During the very first run there is only the running dot, and "last 0 runs" says nothing
      historyLabel.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
      historyLabel.setText(
          context.getResources().getQuantityString(R.plurals.sync_history_label, n, n));
      strip.setContentDescription(historyDescription(context, entry));
    }
    card.setContentDescription(null);
  }

  @NonNull
  private static String historyDescription(
      @NonNull Context context, @NonNull SyncMonitorState.Entry entry) {
    int ok = 0;
    int warn = 0;
    int failed = 0;
    int skipped = 0;
    int n = Math.min(SyncRunHistoryStoreLimits.STRIP, entry.history.size());
    for (int i = 0; i < n; i++) {
      switch (entry.history.get(i).getStatus()) {
        case COMPLETED:
          ok++;
          break;
        case COMPLETED_WITH_ERRORS:
          warn++;
          break;
        case FAILED:
        case ABORTED_LOW_SPACE:
          failed++;
          break;
        default:
          skipped++;
          break;
      }
    }
    return context.getString(R.string.sync_history_description, n, ok, warn, failed, skipped);
  }

  /** The overall state at the top of the System Monitor. */
  public static final class Banner {
    @NonNull public final String title;
    @NonNull public final String detail;
    @androidx.annotation.ColorRes public final int background;
    @androidx.annotation.ColorRes public final int foreground;
    @androidx.annotation.DrawableRes public final int icon;

    Banner(
        @NonNull String title, @NonNull String detail, int background, int foreground, int icon) {
      this.title = title;
      this.detail = detail;
      this.background = background;
      this.foreground = foreground;
      this.icon = icon;
    }
  }

  /**
   * Sums up all syncs in one line: the worst problem as the title, the other problems below it.
   * Without problems it says that all syncs are up to date, or which one runs.
   */
  @NonNull
  public static Banner banner(@NonNull Context context, @NonNull SyncMonitorState state) {
    java.util.List<SyncMonitorState.Entry> errors = new java.util.ArrayList<>();
    java.util.List<SyncMonitorState.Entry> warnings = new java.util.ArrayList<>();
    java.util.List<String> running = new java.util.ArrayList<>();
    int active = 0;
    long lastRun = 0;
    boolean anyFinished = false;
    for (SyncMonitorState.Entry e : state.entries) {
      switch (e.health.getLevel()) {
        case ERROR:
          errors.add(e);
          break;
        case WARNING:
          warnings.add(e);
          break;
        case RUNNING:
          running.add(SyncStatusTexts.configName(e.config));
          break;
        default:
          break;
      }
      if (e.health.getLevel() != SyncHealth.Level.PAUSED) active++;
      if (e.latestRun != null) lastRun = Math.max(lastRun, e.latestRun.getEndedAt());
      lastRun = Math.max(lastRun, e.config.getLastSyncTimestamp());
      if (e.latestRun != null || e.config.getLastSyncTimestamp() > 0) anyFinished = true;
    }
    if (state.entries.isEmpty() || active == 0) {
      return new Banner(
          context.getString(
              state.entries.isEmpty()
                  ? R.string.monitor_banner_no_syncs
                  : R.string.monitor_banner_all_paused),
          context.getString(R.string.monitor_banner_no_syncs_detail),
          R.color.sync_status_neutral_container,
          R.color.sync_status_neutral_on_container,
          R.drawable.ic_sync);
    }
    java.util.List<SyncMonitorState.Entry> problems = new java.util.ArrayList<>(errors);
    problems.addAll(warnings);
    if (problems.isEmpty()) {
      String detail;
      if (!running.isEmpty()) {
        detail = context.getString(R.string.monitor_banner_running, String.join(", ", running));
      } else if (lastRun > 0) {
        detail =
            context.getString(
                R.string.monitor_banner_last_run,
                SyncStatusTexts.relative(context, lastRun, state.now));
      } else {
        detail = "";
      }
      // Before any sync has finished, "up to date" would claim what nothing has shown yet,
      // e.g. while the very first run goes
      if (!anyFinished) {
        return new Banner(
            context.getString(R.string.monitor_banner_not_run_yet),
            detail,
            R.color.sync_status_neutral_container,
            R.color.sync_status_neutral_on_container,
            R.drawable.ic_sync);
      }
      return new Banner(
          context.getString(R.string.monitor_banner_all_ok),
          detail,
          R.color.sync_status_ok_container,
          R.color.sync_status_ok_on_container,
          R.drawable.ic_check_circle);
    }
    SyncMonitorState.Entry first = problems.get(0);
    StringBuilder detail = new StringBuilder();
    if (problems.size() > 1) {
      java.util.List<String> others = new java.util.ArrayList<>();
      for (int i = 1; i < problems.size(); i++) {
        others.add(phrase(context, problems.get(i)));
      }
      detail.append(context.getString(R.string.monitor_banner_also, String.join("; ", others)));
    } else {
      detail.append(context.getString(R.string.monitor_banner_tap_for_details));
    }
    if (!running.isEmpty()) {
      detail
          .append(' ')
          .append(context.getString(R.string.monitor_banner_running, String.join(", ", running)));
    }
    boolean error = !errors.isEmpty();
    return new Banner(
        phrase(context, first),
        detail.toString(),
        error ? R.color.sync_status_error_container : R.color.sync_status_warning_container,
        error ? R.color.sync_status_error_on_container : R.color.sync_status_warning_on_container,
        error ? R.drawable.ic_error_outline : R.drawable.ic_warning);
  }

  /** "Photos has 23 files with errors", "Documents does not sync". */
  @NonNull
  static String phrase(@NonNull Context context, @NonNull SyncMonitorState.Entry entry) {
    String name = SyncStatusTexts.configName(entry.config);
    switch (entry.health.getKind()) {
      case COMPLETED_WITH_ERRORS:
        int count = entry.latestRun != null ? entry.latestRun.getErrorCount() : 0;
        return context
            .getResources()
            .getQuantityString(R.plurals.monitor_phrase_errors, count, name, count);
      case LOW_SPACE:
        return context.getString(R.string.monitor_phrase_low_space, name);
      case OVERDUE:
        return context.getString(R.string.monitor_phrase_overdue, name);
      case WAITING_FOR_WIFI:
        return context.getString(R.string.monitor_phrase_waiting_wifi, name);
      case FAILED:
      default:
        return context.getString(R.string.monitor_phrase_failed, name);
    }
  }

  /** Limits of the views. */
  static final class SyncRunHistoryStoreLimits {
    static final int STRIP = 20;

    private SyncRunHistoryStoreLimits() {}
  }
}
