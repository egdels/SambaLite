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
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.sync.SyncErrorCategory;
import de.schliweb.sambalite.sync.SyncHealth;
import de.schliweb.sambalite.sync.SyncManager;
import de.schliweb.sambalite.sync.SyncProblemNotifier;
import de.schliweb.sambalite.sync.SyncRepository;
import de.schliweb.sambalite.sync.SyncRunHistoryStore;
import de.schliweb.sambalite.sync.SyncRunResult;
import de.schliweb.sambalite.sync.SyncRunState;
import de.schliweb.sambalite.ui.MainActivity;
import de.schliweb.sambalite.util.BatteryOptimizationUtils;
import de.schliweb.sambalite.util.EnhancedUIUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Details of one folder sync (#50): its state and what to do about it, the counters of the last
 * run, its errors grouped by cause, its history and whether it notifies about problems.
 */
public class SyncStatusDetailActivity extends AppCompatActivity {

  private static final String EXTRA_CONFIG_ID = "config_id";

  /** Files shown per error group; the rest are counted. */
  static final int FILES_PER_GROUP = 5;

  private String configId;
  @Nullable private List<WorkInfo> workInfos;
  @Nullable private SyncErrorCategory openGroup;
  private boolean openGroupChosen;
  private SyncRunHistoryStore store;

  @NonNull
  public static Intent createIntent(@NonNull Context context, @NonNull String configId) {
    return new Intent(context, SyncStatusDetailActivity.class).putExtra(EXTRA_CONFIG_ID, configId);
  }

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    configId = getIntent().getStringExtra(EXTRA_CONFIG_ID);
    if (configId == null) {
      finish();
      return;
    }
    EdgeToEdge.enable(this);
    setContentView(R.layout.activity_sync_status_detail);
    store = new SyncRunHistoryStore(this);

    androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
    de.schliweb.sambalite.ui.utils.UIHelper.applyHorizontalSystemInsets(toolbar);
    setSupportActionBar(toolbar);
    if (getSupportActionBar() != null) {
      getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }
    View scroll = findViewById(R.id.scroll_view);
    ViewCompat.setOnApplyWindowInsetsListener(
        scroll,
        (v, windowInsets) -> {
          Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
          v.setPadding(
              v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), insets.bottom + 16);
          return windowInsets;
        });

    MaterialSwitch notify = findViewById(R.id.notify_switch);
    notify.setChecked(!store.isMuted(configId));
    notify.setOnCheckedChangeListener((b, checked) -> store.setMuted(configId, !checked));

    findViewById(R.id.show_log_button)
        .setOnClickListener(v -> startActivity(SyncLogActivity.createIntent(this, configId)));

    SyncProblemNotifier.cancel(this, configId);

    WorkManager.getInstance(this)
        .getWorkInfosByTagLiveData(SyncManager.UNIQUE_WORK_NAME)
        .observe(
            this,
            infos -> {
              workInfos = infos;
              render();
            });
  }

  @Override
  protected void onResume() {
    super.onResume();
    render();
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      getOnBackPressedDispatcher().onBackPressed();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  private void render() {
    SyncMonitorState state = SyncMonitorState.load(this, workInfos);
    SyncMonitorState.Entry entry = state.find(configId);
    if (entry == null) {
      // The sync was deleted meanwhile
      finish();
      return;
    }
    if (getSupportActionBar() != null) {
      getSupportActionBar().setTitle(SyncStatusTexts.configName(entry.config));
    }
    SyncMonitorUi.bindChip(findViewById(R.id.detail_status_chip), entry.health);
    boolean running = entry.health.getKind() == SyncHealth.Kind.RUNNING;
    TextView when = findViewById(R.id.detail_when);
    when.setText(
        SyncStatusTexts.whenLine(this, entry.config, entry.health, entry.latestRun, state.now));
    when.setVisibility(running ? View.GONE : View.VISIBLE);
    ((TextView) findViewById(R.id.detail_route))
        .setText(
            SyncStatusTexts.route(entry.config)
                + " · "
                + SyncStatusTexts.schedule(this, entry.config));
    SyncMonitorState.Progress progress =
        running ? (entry.progress != null ? entry.progress : SyncMonitorState.Progress.NONE) : null;
    SyncMonitorUi.bindLive(findViewById(R.id.detail_live), progress);

    renderFix(entry, state.now);
    // While it runs, the counters are the running run's; the extras belong to the last run and
    // would mix with them, so they wait until it is done.
    if (progress != null) {
      SyncMonitorUi.bindCounters(findViewById(R.id.detail_counters), progress);
    } else {
      SyncMonitorUi.bindCounters(findViewById(R.id.detail_counters), entry.latestRun);
    }
    TextView extras = findViewById(R.id.detail_extras);
    String extrasText =
        entry.latestRun != null && progress == null
            ? SyncStatusTexts.extras(this, entry.latestRun)
            : null;
    extras.setText(extrasText);
    extras.setVisibility(extrasText != null ? View.VISIBLE : View.GONE);

    ((TextView) findViewById(R.id.last_run_title))
        .setText(
            progress != null ? R.string.sync_detail_current_run : R.string.sync_detail_last_run);
    // The errors listed are the last run's; while a run goes, its own count is in the counters.
    renderErrors(progress == null ? entry.latestRun : null);
    renderHistory(entry, state.now);
    renderSyncButton(entry);
  }

  private void renderSyncButton(@NonNull SyncMonitorState.Entry entry) {
    MaterialButton button = findViewById(R.id.sync_now_button);
    boolean running = entry.health.getKind() == SyncHealth.Kind.RUNNING;
    button.setEnabled(entry.config.isEnabled());
    button.setText(running ? R.string.sync_stop : R.string.sync_now);
    button.setOnClickListener(
        v -> {
          SyncManager manager = new SyncManager(this, new SyncRepository(this));
          if (running) {
            boolean periodic = SyncRunState.from(workInfos).isRunningInPeriodicRun(entry.config);
            manager.stopSync(configId, periodic);
            EnhancedUIUtils.showInfo(this, getString(R.string.sync_stopped));
          } else {
            manager.triggerImmediateSync(configId);
            EnhancedUIUtils.showInfo(this, getString(R.string.sync_running));
          }
        });
  }

  // ---------------------------------------------------------------------------
  // What to do
  // ---------------------------------------------------------------------------

  private void renderFix(@NonNull SyncMonitorState.Entry entry, long now) {
    View card = findViewById(R.id.fix_card);
    TextView title = findViewById(R.id.fix_title);
    TextView text = findViewById(R.id.fix_text);
    TextView reason = findViewById(R.id.fix_reason);
    MaterialButton action = findViewById(R.id.fix_action);
    SyncRunResult run = entry.latestRun;
    String technical = run != null ? run.getReason() : null;
    action.setVisibility(View.VISIBLE);

    switch (entry.health.getKind()) {
      case FAILED:
        SyncErrorCategory category =
            run != null && run.getFailureCategory() != null
                ? run.getFailureCategory()
                : SyncErrorCategory.OTHER;
        title.setText(SyncStatusTexts.categoryTitle(this, category));
        text.setText(SyncStatusTexts.categoryHint(this, category));
        if (category == SyncErrorCategory.LOCAL_FOLDER_MISSING) {
          action.setText(R.string.sync_edit_option);
          action.setOnClickListener(
              v -> startActivity(MainActivity.createEditSyncIntent(this, configId)));
        } else if (category == SyncErrorCategory.AUTH
            || category == SyncErrorCategory.SHARE_NOT_FOUND) {
          action.setVisibility(View.GONE);
        } else {
          bindSyncNow(action);
        }
        break;
      case LOW_SPACE:
        title.setText(SyncStatusTexts.categoryTitle(this, SyncErrorCategory.LOCAL_SPACE));
        text.setText(SyncStatusTexts.categoryHint(this, SyncErrorCategory.LOCAL_SPACE));
        bindSyncNow(action);
        break;
      case WAITING_FOR_WIFI:
        title.setText(R.string.sync_fix_wifi_title);
        text.setText(
            getString(
                R.string.sync_fix_wifi_text,
                SyncStatusTexts.duration(this, now - entry.config.getLastSyncTimestamp())));
        action.setText(R.string.sync_fix_wifi_action);
        action.setOnClickListener(
            v -> {
              new SyncManager(this, new SyncRepository(this)).triggerImmediateSync(configId, true);
              EnhancedUIUtils.showInfo(this, getString(R.string.sync_running));
            });
        technical = null;
        break;
      case OVERDUE:
        title.setText(R.string.sync_fix_overdue_title);
        boolean batteryOptimized = !BatteryOptimizationUtils.isIgnoringBatteryOptimizations(this);
        text.setText(
            getString(
                batteryOptimized
                    ? R.string.sync_fix_overdue_text_battery
                    : R.string.sync_fix_overdue_text,
                SyncStatusTexts.duration(this, now - entry.config.getLastSyncTimestamp()),
                SyncStatusTexts.intervalLabel(this, entry.config.getIntervalMinutes())));
        if (batteryOptimized) {
          action.setText(R.string.monitor_battery_action);
          action.setOnClickListener(
              v -> BatteryOptimizationUtils.openBatteryOptimizationSettings(this));
        } else {
          bindSyncNow(action);
        }
        technical = null;
        break;
      default:
        card.setVisibility(View.GONE);
        return;
    }
    card.setVisibility(View.VISIBLE);
    reason.setText(technical);
    reason.setVisibility(technical != null && !technical.isEmpty() ? View.VISIBLE : View.GONE);
  }

  private void bindSyncNow(@NonNull MaterialButton action) {
    action.setText(R.string.sync_now);
    action.setOnClickListener(
        v -> {
          new SyncManager(this, new SyncRepository(this)).triggerImmediateSync(configId);
          EnhancedUIUtils.showInfo(this, getString(R.string.sync_running));
        });
  }

  // ---------------------------------------------------------------------------
  // Errors
  // ---------------------------------------------------------------------------

  private void renderErrors(@Nullable SyncRunResult run) {
    View title = findViewById(R.id.errors_title);
    View card = findViewById(R.id.errors_card);
    TextView capped = findViewById(R.id.errors_capped);
    LinearLayout groups = findViewById(R.id.error_groups);
    if (run == null || run.getErrorCount() == 0) {
      title.setVisibility(View.GONE);
      card.setVisibility(View.GONE);
      capped.setVisibility(View.GONE);
      return;
    }
    title.setVisibility(View.VISIBLE);
    card.setVisibility(View.VISIBLE);
    if (run.getErrorCount() > run.getErrors().size()) {
      capped.setVisibility(View.VISIBLE);
      capped.setText(
          getString(
              R.string.sync_detail_errors_shown,
              SyncStatusTexts.number(run.getErrors().size()),
              SyncStatusTexts.number(run.getErrorCount())));
    } else {
      capped.setVisibility(View.GONE);
    }

    List<Map.Entry<SyncErrorCategory, Integer>> categories =
        new ArrayList<>(run.getErrorCategories().entrySet());
    categories.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
    if (!openGroupChosen && !categories.isEmpty()) {
      openGroup = categories.get(0).getKey();
      openGroupChosen = true;
    }

    groups.removeAllViews();
    LayoutInflater inflater = LayoutInflater.from(this);
    for (Map.Entry<SyncErrorCategory, Integer> c : categories) {
      View group = inflater.inflate(R.layout.item_sync_error_group, groups, false);
      bindGroup(group, inflater, run, c.getKey(), c.getValue());
      groups.addView(group);
    }
  }

  private void bindGroup(
      @NonNull View group,
      @NonNull LayoutInflater inflater,
      @NonNull SyncRunResult run,
      @NonNull SyncErrorCategory category,
      int count) {
    boolean open = category == openGroup;
    String title = SyncStatusTexts.categoryTitle(this, category);
    ((TextView) group.findViewById(R.id.group_count)).setText(SyncStatusTexts.number(count));
    ((TextView) group.findViewById(R.id.group_title)).setText(title);
    group.findViewById(R.id.group_chevron).setRotation(open ? 90 : 0);
    View header = group.findViewById(R.id.group_header);
    header.setContentDescription(
        getString(R.string.sync_error_count_times, count, title)
            + ", "
            + getString(open ? R.string.monitor_expanded : R.string.monitor_collapsed));
    header.setOnClickListener(
        v -> {
          openGroup = open ? null : category;
          renderErrors(run);
        });
    View body = group.findViewById(R.id.group_body);
    body.setVisibility(open ? View.VISIBLE : View.GONE);
    if (!open) return;

    ((TextView) group.findViewById(R.id.group_hint))
        .setText(SyncStatusTexts.categoryHint(this, category));
    LinearLayout files = group.findViewById(R.id.group_files);
    int shown = 0;
    for (SyncRunResult.RunError e : run.getErrors()) {
      if (e.getCategory() != category) continue;
      if (shown == FILES_PER_GROUP) break;
      View row = inflater.inflate(R.layout.item_sync_error_file, files, false);
      ((TextView) row.findViewById(R.id.file_path)).setText(e.getPath());
      TextView streak = row.findViewById(R.id.file_streak);
      if (e.getStreak() > 1) {
        streak.setVisibility(View.VISIBLE);
        streak.setText(
            getResources()
                .getQuantityString(R.plurals.sync_error_streak, e.getStreak(), e.getStreak()));
      } else {
        streak.setVisibility(View.GONE);
      }
      row.setContentDescription(
          e.getPath() + (e.getMessage().isEmpty() ? "" : ", " + e.getMessage()));
      row.setOnLongClickListener(
          v -> {
            EnhancedUIUtils.showInfo(this, e.getMessage().isEmpty() ? e.getPath() : e.getMessage());
            return true;
          });
      files.addView(row);
      shown++;
    }
    TextView more = group.findViewById(R.id.group_more);
    int rest = count - shown;
    if (rest > 0) {
      more.setVisibility(View.VISIBLE);
      more.setText(getResources().getQuantityString(R.plurals.sync_error_more, rest, rest));
    } else {
      more.setVisibility(View.GONE);
    }
  }

  // ---------------------------------------------------------------------------
  // History
  // ---------------------------------------------------------------------------

  private void renderHistory(@NonNull SyncMonitorState.Entry entry, long now) {
    View title = findViewById(R.id.history_title);
    View card = findViewById(R.id.history_card);
    LinearLayout list = findViewById(R.id.history_list);
    list.removeAllViews();
    if (entry.history.isEmpty()) {
      title.setVisibility(View.GONE);
      card.setVisibility(View.GONE);
      return;
    }
    title.setVisibility(View.VISIBLE);
    card.setVisibility(View.VISIBLE);
    LayoutInflater inflater = LayoutInflater.from(this);
    for (SyncRunResult r : entry.history) {
      View row = inflater.inflate(R.layout.item_sync_run, list, false);
      row.findViewById(R.id.run_dot)
          .setBackgroundTintList(
              ColorStateList.valueOf(
                  ContextCompat.getColor(this, SyncStatusTexts.dotColor(r.getStatus()))));
      ((TextView) row.findViewById(R.id.run_when))
          .setText(SyncStatusTexts.clock(this, r.getStartedAt(), now));
      ((TextView) row.findViewById(R.id.run_summary))
          .setText(SyncStatusTexts.resultSummary(this, r));
      list.addView(row);
    }
  }
}
