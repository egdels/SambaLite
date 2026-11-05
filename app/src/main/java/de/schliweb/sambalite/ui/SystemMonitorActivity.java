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

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.SambaLiteApp;
import de.schliweb.sambalite.cache.IntelligentCacheManager;
import de.schliweb.sambalite.cache.statistics.CacheStatistics;
import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.sync.SyncActionLog;
import de.schliweb.sambalite.sync.SyncConfig;
import de.schliweb.sambalite.sync.SyncManager;
import de.schliweb.sambalite.sync.SyncRepository;
import de.schliweb.sambalite.sync.db.SyncStateStore;
import de.schliweb.sambalite.ui.monitor.DiagnosticsReport;
import de.schliweb.sambalite.ui.monitor.SyncLogActivity;
import de.schliweb.sambalite.ui.monitor.SyncMonitorState;
import de.schliweb.sambalite.ui.monitor.SyncMonitorUi;
import de.schliweb.sambalite.ui.monitor.SyncStatusDetailActivity;
import de.schliweb.sambalite.ui.operations.TransferActionLog;
import de.schliweb.sambalite.util.BatteryOptimizationUtils;
import de.schliweb.sambalite.util.EnhancedFileUtils;
import de.schliweb.sambalite.util.LogUtils;
import de.schliweb.sambalite.util.SimplePerformanceMonitor;
import de.schliweb.sambalite.util.SmartErrorHandler;
import de.schliweb.sambalite.util.StorageStatusReport;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Inject;

/**
 * The System Monitor (#50). It answers "do my syncs work, and if not, what do I do?": an overall
 * state at the top, a card per folder sync with its last run, history and live progress, a search
 * for why a file was not synced, and the technical details of the app collapsed at the bottom.
 */
public class SystemMonitorActivity extends AppCompatActivity {

  private static final String TAG = "SystemMonitorActivity";

  /** How often relative times ("6 minutes ago") are refreshed while the screen is shown. */
  private static final long TICK_MS = 30_000;

  @Inject BackgroundSmbManager backgroundSmbManager;

  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable tick = this::tick;

  private TextView statusOverview;
  private View techCard;
  private ImageView techChevron;
  private LinearLayout syncCards;
  private MaterialCardView bannerCard;
  private ImageView bannerIcon;
  private TextView bannerTitle;
  private TextView bannerDetail;
  private View batteryCard;

  @Nullable private List<WorkInfo> workInfos;
  private boolean techLoaded;

  public static @NonNull Intent createIntent(@NonNull Context context) {
    return new Intent(context, SystemMonitorActivity.class);
  }

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    ((SambaLiteApp) getApplication()).getAppComponent().inject(this);
    super.onCreate(savedInstanceState);
    EdgeToEdge.enable(this);
    setContentView(R.layout.activity_system_monitor);

    androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
    de.schliweb.sambalite.ui.utils.UIHelper.applyHorizontalSystemInsets(toolbar);
    setSupportActionBar(toolbar);
    if (getSupportActionBar() != null) {
      getSupportActionBar().setDisplayHomeAsUpEnabled(true);
      getSupportActionBar().setTitle(getString(R.string.system_monitor));
    }

    androidx.core.widget.NestedScrollView scrollView = findViewById(R.id.scroll_view);
    ViewCompat.setOnApplyWindowInsetsListener(
        scrollView,
        (v, windowInsets) -> {
          Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
          v.setPadding(
              v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), insets.bottom + 16);
          return windowInsets;
        });

    statusOverview = findViewById(R.id.status_overview);
    techCard = findViewById(R.id.tech_card);
    techChevron = findViewById(R.id.tech_chevron);
    syncCards = findViewById(R.id.sync_cards);
    bannerCard = findViewById(R.id.banner_card);
    bannerIcon = findViewById(R.id.banner_icon);
    bannerTitle = findViewById(R.id.banner_title);
    bannerDetail = findViewById(R.id.banner_detail);
    batteryCard = findViewById(R.id.battery_card);

    findViewById(R.id.battery_button)
        .setOnClickListener(v -> BatteryOptimizationUtils.openBatteryOptimizationSettings(this));
    findViewById(R.id.row_search)
        .setOnClickListener(v -> startActivity(SyncLogActivity.createIntent(this, null)));
    findViewById(R.id.row_tech).setOnClickListener(v -> toggleTechnicalDetails());

    WorkManager.getInstance(this)
        .getWorkInfosByTagLiveData(SyncManager.UNIQUE_WORK_NAME)
        .observe(
            this,
            infos -> {
              workInfos = infos;
              render();
            });
    render();
  }

  @Override
  protected void onResume() {
    super.onResume();
    render();
    if (techLoaded) loadTechnicalDetails();
    handler.postDelayed(tick, TICK_MS);
  }

  @Override
  protected void onPause() {
    super.onPause();
    handler.removeCallbacks(tick);
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    executor.shutdownNow();
  }

  private void tick() {
    render();
    handler.postDelayed(tick, TICK_MS);
  }

  /** Shows the current state of all syncs. */
  private void render() {
    SyncMonitorState state;
    try {
      state = SyncMonitorState.load(this, workInfos);
    } catch (RuntimeException e) {
      LogUtils.e(TAG, "Could not load the sync state: " + e.getMessage());
      return;
    }
    renderBanner(state);
    renderBatteryHint(state);
    renderCards(state);
  }

  private void renderBanner(@NonNull SyncMonitorState state) {
    SyncMonitorUi.Banner banner = SyncMonitorUi.banner(this, state);
    bannerTitle.setText(banner.title);
    bannerDetail.setText(banner.detail);
    bannerDetail.setVisibility(banner.detail.isEmpty() ? View.GONE : View.VISIBLE);
    bannerCard.setCardBackgroundColor(ContextCompat.getColor(this, banner.background));
    int foreground = ContextCompat.getColor(this, banner.foreground);
    bannerTitle.setTextColor(foreground);
    bannerDetail.setTextColor(foreground);
    bannerIcon.setImageResource(banner.icon);
    bannerIcon.setImageTintList(ColorStateList.valueOf(foreground));
  }

  private void renderBatteryHint(@NonNull SyncMonitorState state) {
    boolean periodic = false;
    for (SyncMonitorState.Entry e : state.entries) {
      if (e.config.isEnabled() && e.config.getIntervalMinutes() > 0) {
        periodic = true;
        break;
      }
    }
    boolean show = periodic && !BatteryOptimizationUtils.isIgnoringBatteryOptimizations(this);
    batteryCard.setVisibility(show ? View.VISIBLE : View.GONE);
  }

  private void renderCards(@NonNull SyncMonitorState state) {
    LayoutInflater inflater = LayoutInflater.from(this);
    // Reuse the cards so that a refresh every few seconds does not rebuild the views.
    while (syncCards.getChildCount() > state.entries.size()) {
      syncCards.removeViewAt(syncCards.getChildCount() - 1);
    }
    for (int i = 0; i < state.entries.size(); i++) {
      SyncMonitorState.Entry entry = state.entries.get(i);
      View card;
      if (i < syncCards.getChildCount()) {
        card = syncCards.getChildAt(i);
      } else {
        card = inflater.inflate(R.layout.item_sync_status_card, syncCards, false);
        syncCards.addView(card);
      }
      SyncMonitorUi.bindCard(card, entry, state.now);
      String configId = entry.config.getId();
      card.setOnClickListener(
          v -> startActivity(SyncStatusDetailActivity.createIntent(this, configId)));
    }
  }

  // ---------------------------------------------------------------------------
  // Technical details (the former System Monitor text)
  // ---------------------------------------------------------------------------

  private void toggleTechnicalDetails() {
    boolean show = techCard.getVisibility() != View.VISIBLE;
    techCard.setVisibility(show ? View.VISIBLE : View.GONE);
    techChevron.setRotation(show ? 90 : 0);
    findViewById(R.id.row_tech)
        .setContentDescription(
            getString(R.string.monitor_tech_title)
                + ", "
                + getString(show ? R.string.monitor_expanded : R.string.monitor_collapsed));
    if (show) {
      techLoaded = true;
      loadTechnicalDetails();
    }
  }

  private void loadTechnicalDetails() {
    statusOverview.setText(R.string.loading_system_information);
    executor.execute(
        () -> {
          String text = buildTechnicalDetails();
          runOnUiThread(() -> statusOverview.setText(text));
        });
  }

  /** The technical details as text; runs off the main thread. */
  @NonNull
  private String buildTechnicalDetails() {
    try {
      SambaLiteApp app = (SambaLiteApp) getApplication();
      return "=== SambaLite System Monitor ===\n"
          + "Generated: "
          + getCurrentTimeString()
          + "\n\n"
          + getSystemOverview(app)
          + "\n\n"
          + getPerformanceMetrics()
          + "\n\n"
          + getCacheStatistics(app)
          + "\n\n"
          + getNetworkStatus()
          + "\n\n"
          + getErrorSummary(app)
          + "\n\n"
          + getSyncActivityLog()
          + "\n\n"
          + getTransferActivityLog()
          + "\n\n"
          + getTimestampStatus();
    } catch (Exception e) {
      LogUtils.e(TAG, "Failed to build the technical details: " + e.getMessage());
      SmartErrorHandler errorHandler = ((SambaLiteApp) getApplication()).getErrorHandler();
      if (errorHandler != null) {
        errorHandler.recordError(
            e, "SystemMonitorActivity.buildTechnicalDetails", SmartErrorHandler.ErrorSeverity.HIGH);
      }
      return "=== System Monitor Error ===\n\n" + e.getMessage();
    }
  }

  // ---------------------------------------------------------------------------
  // Diagnostics
  // ---------------------------------------------------------------------------

  private void confirmShareDiagnostics() {
    new MaterialAlertDialogBuilder(this)
        .setTitle(R.string.monitor_share_diagnostics)
        .setMessage(R.string.monitor_share_diagnostics_message)
        .setPositiveButton(R.string.monitor_share_action, (d, w) -> shareDiagnostics())
        .setNegativeButton(R.string.cancel, null)
        .show();
  }

  private void shareDiagnostics() {
    List<WorkInfo> infos = workInfos;
    executor.execute(
        () -> {
          try {
            SyncMonitorState state = SyncMonitorState.load(this, infos);
            String report = DiagnosticsReport.build(this, state, buildTechnicalDetails());
            File dir = new File(getCacheDir(), "open_files");
            if (!dir.exists() && !dir.mkdirs()) {
              throw new java.io.IOException("Could not create " + dir);
            }
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File file = new File(dir, "sambalite-diagnostics-" + stamp + ".txt");
            try (OutputStreamWriter w =
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
              w.write(report);
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent send =
                new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, "SambaLite diagnostics")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            runOnUiThread(
                () ->
                    startActivity(
                        Intent.createChooser(send, getString(R.string.monitor_share_diagnostics))));
          } catch (Exception e) {
            LogUtils.e(TAG, "Could not share diagnostics: " + e.getMessage());
            runOnUiThread(
                () ->
                    de.schliweb.sambalite.util.EnhancedUIUtils.showError(
                        this, getString(R.string.monitor_share_diagnostics_failed)));
          }
        });
  }

  private String getSystemOverview(SambaLiteApp app) {
    StringBuilder overview = new StringBuilder();

    overview.append("=== SambaLite System Status ===\n");
    overview.append("Last Updated: ").append(getCurrentTimeString()).append("\n\n");

    SambaLiteApp.ApplicationHealthStatus health = app.getHealthStatus();
    overview
        .append("System Health: ")
        .append(health.overallHealthy ? "✓ HEALTHY" : "⚠ ISSUES")
        .append("\n");
    overview
        .append("- Dependency Injection: ")
        .append(health.dependencyInjectionReady ? "✓" : "✗")
        .append("\n");
    overview
        .append("- Performance Monitoring: ")
        .append(health.performanceMonitoringReady ? "✓" : "✗")
        .append("\n");
    overview.append("- Cache System: ").append(health.cacheSystemReady ? "✓" : "✗").append("\n");
    overview.append("- Error Handler: ").append(health.errorHandlerReady ? "✓" : "✗").append("\n");

    return overview.toString();
  }

  private String getPerformanceMetrics() {
    StringBuilder metrics = new StringBuilder();
    metrics.append("=== Performance Metrics ===\n");
    // The monitor's texts already start with "Memory:", "Device:" and "Performance:"
    metrics.append(SimplePerformanceMonitor.getMemoryInfo()).append("\n");
    metrics.append(SimplePerformanceMonitor.getDeviceInfo()).append("\n");
    metrics.append(SimplePerformanceMonitor.getPerformanceStats()).append("\n");

    metrics.append("\n").append(StorageStatusReport.build(this, getSyncTargets()));

    return metrics.toString();
  }

  /** The local folders of all folder syncs, so the disk space section can show their volumes. */
  private List<StorageStatusReport.Target> getSyncTargets() {
    List<StorageStatusReport.Target> targets = new ArrayList<>();
    try {
      for (SyncConfig config : new SyncRepository(this).getAllSyncConfigs()) {
        String uri = config.getLocalFolderUri();
        String local = config.getLocalFolderDisplayName();
        if (local == null || local.isEmpty()) local = uri;
        targets.add(
            new StorageStatusReport.Target(
                local + " \u2190 " + config.getRemotePath(),
                uri == null || uri.isEmpty() ? null : Uri.parse(uri)));
      }
    } catch (Exception e) {
      LogUtils.w(TAG, "Could not load sync configs for disk space report: " + e.getMessage());
    }
    return targets;
  }

  private String getCacheStatistics(SambaLiteApp app) {
    StringBuilder stats = new StringBuilder();

    stats.append("=== Cache Statistics ===\n");

    // Open-File Cache size
    long openFileCacheSize = 0;
    int openFileCount = 0;
    for (java.io.File f : de.schliweb.sambalite.util.OpenFileCacheManager.listCacheFiles(app)) {
      openFileCacheSize += f.length();
      openFileCount++;
    }
    stats.append("\nOpen-File Cache:\n");
    stats
        .append("- Size: ")
        .append(EnhancedFileUtils.formatFileSize(openFileCacheSize))
        .append("\n");
    stats.append("- Files: ").append(openFileCount).append("\n");
    stats.append("- Max Size: 100 MB (evicts to 50 MB)\n");

    // Thumbnail Cache size
    java.io.File thumbnailCacheDir = new java.io.File(app.getCacheDir(), "thumbnails");
    java.io.File[] thumbFiles = thumbnailCacheDir.listFiles();
    long thumbCacheSize = 0;
    int thumbFileCount = 0;
    int thumbNoCoverCount = 0;
    if (thumbFiles != null) {
      for (java.io.File f : thumbFiles) {
        if (f.getName().endsWith(".thumb")) {
          thumbCacheSize += f.length();
          thumbFileCount++;
        } else if (f.getName().endsWith(".nocover")) {
          thumbNoCoverCount++;
        }
      }
    }
    stats.append("\nThumbnail Cache:\n");
    stats.append("- Size: ").append(EnhancedFileUtils.formatFileSize(thumbCacheSize)).append("\n");
    stats.append("- Thumbnails: ").append(thumbFileCount).append("\n");
    stats.append("- No-Cover Markers: ").append(thumbNoCoverCount).append("\n");
    stats.append("- Max Size: 50 MB (LRU eviction)\n");

    IntelligentCacheManager cacheManager = app.getCacheManager();
    if (cacheManager != null) {
      // Run a cache performance test to verify hit rate calculation
      cacheManager.testCachePerformance();

      // Perform maintenance to trigger debug logging
      cacheManager.performMaintenance();

      CacheStatistics.CacheStatisticsSnapshot cacheStats = cacheManager.getStatistics();

      // Memory usage statistics
      stats.append("\nMemory Usage:\n");
      stats.append("- Memory Entries: ").append(cacheStats.getMemoryEntries()).append("\n");
      stats
          .append("- Disk Size: ")
          .append(EnhancedFileUtils.formatFileSize(cacheStats.getDiskSizeBytes()))
          .append("\n");
      stats.append("- Valid Entries: ").append(cacheStats.getValidEntries()).append("\n");
      stats.append("- Expired Entries: ").append(cacheStats.getExpiredEntries()).append("\n");

      // Cache performance statistics
      stats.append("\nCache Performance:\n");
      stats.append("- Total Requests: ").append(cacheStats.getCacheRequests()).append("\n");
      stats.append("- Cache Hits: ").append(cacheStats.getCacheHits()).append("\n");
      stats.append("- Cache Misses: ").append(cacheStats.getCacheMisses()).append("\n");
      stats
          .append("- Hit Rate: ")
          .append(String.format(Locale.US, "%.2f%%", cacheStats.getHitRate() * 100))
          .append("\n");

      // Operation statistics
      stats.append("\nOperations:\n");
      stats.append("- Put Operations: ").append(cacheStats.getPutOperations()).append("\n");
      stats.append("- Get Operations: ").append(cacheStats.getGetOperations()).append("\n");
      stats.append("- Remove Operations: ").append(cacheStats.getRemoveOperations()).append("\n");

      // Error statistics
      int totalErrors = cacheStats.getTotalErrors();
      stats.append("\nErrors:\n");
      if (totalErrors > 0) {
        stats.append("- Total Errors: ").append(totalErrors).append("\n");
        stats
            .append("- Serialization Errors: ")
            .append(cacheStats.getSerializationErrors())
            .append("\n");
        stats
            .append("- Deserialization Errors: ")
            .append(cacheStats.getDeserializationErrors())
            .append("\n");
        stats.append("- Disk Write Errors: ").append(cacheStats.getDiskWriteErrors()).append("\n");
        stats.append("- Disk Read Errors: ").append(cacheStats.getDiskReadErrors()).append("\n");
      } else {
        stats.append("✓ No errors detected\n");
      }
    } else {
      stats.append("Cache system not available\n");
    }

    return stats.toString();
  }

  private String getNetworkStatus() {
    StringBuilder status = new StringBuilder();

    status.append("=== Network Status ===\n");
    status.append("Network monitoring handled by system ConnectivityManager\n");

    return status.toString();
  }

  private String getErrorSummary(SambaLiteApp app) {
    StringBuilder summary = new StringBuilder();

    summary.append("=== Error Summary ===\n");

    // Use SmartErrorHandler for comprehensive error reporting
    SmartErrorHandler errorHandler = app.getErrorHandler();

    if (errorHandler != null) {
      SmartErrorHandler.ErrorStats stats = errorHandler.getErrorStats();

      // Get recent errors (if any)
      if (stats.totalErrors > 0) {
        summary.append("Error Details:\n");
        summary.append("- Total Errors: ").append(stats.totalErrors).append("\n");
        summary.append("- Critical Errors: ").append(stats.criticalErrors).append("\n");
        summary.append("- Network Errors: ").append(stats.networkErrors).append("\n");
        summary.append("- SMB Errors: ").append(stats.smbErrors).append("\n");
        summary.append("- Recent Entries: ").append(stats.recentErrors).append("\n");
        summary.append("\nError tracking is active and automatically collecting data.\n");
        summary.append("Errors are categorized by type and severity for better debugging.\n");
      } else {
        summary.append("✓ No errors recorded since app start - System running smoothly!\n");
      }
    } else {
      summary.append("SmartErrorHandler not available\n");
    }

    return summary.toString();
  }

  private String getSyncActivityLog() {
    SyncActionLog actionLog = new SyncActionLog(this);
    return actionLog.getFormattedLog(25);
  }

  private String getTransferActivityLog() {
    TransferActionLog actionLog = new TransferActionLog(this);
    return actionLog.getFormattedLog(25);
  }

  private String getTimestampStatus() {
    StringBuilder sb = new StringBuilder();
    sb.append("=== Timestamp Preservation Status ===\n");

    try {
      int[] counts = readSyncStateCounts(new SyncStateStore(this));
      int total = counts[0];
      int preserved = counts[1];
      int notPreserved = total - preserved;

      sb.append("Tracked files: ").append(total).append("\n");
      if (total > 0) {
        double rate = (preserved * 100.0) / total;
        sb.append(
            String.format(
                Locale.US, "Timestamp preserved: %d of %d (%.1f%%)\n", preserved, total, rate));
        sb.append("Timestamp not preserved (SAF): ").append(notPreserved).append("\n");
      } else {
        sb.append("No sync state data available.\n");
      }
    } catch (Exception e) {
      sb.append("Error loading timestamp status: ").append(e.getMessage()).append("\n");
    }

    return sb.toString();
  }

  /**
   * Returns {total, timestamp preserved} of the tracked sync states. Room refuses queries on the
   * main thread (the store then reports 0), so the counts are read on a background thread.
   */
  static int[] readSyncStateCounts(SyncStateStore store) throws Exception {
    return java.util.concurrent.CompletableFuture.supplyAsync(
            () -> new int[] {store.getTotalCount(), store.getTimestampPreservedCount()})
        .get(5, java.util.concurrent.TimeUnit.SECONDS);
  }

  private String getCurrentTimeString() {
    return android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", System.currentTimeMillis())
        .toString();
  }

  @Override
  public boolean onSupportNavigateUp() {
    getOnBackPressedDispatcher().onBackPressed();
    return true;
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.menu_system_monitor, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    int id = item.getItemId();
    if (id == R.id.action_refresh) {
      render();
      if (techLoaded) loadTechnicalDetails();
      return true;
    } else if (id == R.id.action_share_diagnostics) {
      confirmShareDiagnostics();
      return true;
    } else if (id == R.id.action_quit) {
      handleQuit();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  private void handleQuit() {
    if (backgroundSmbManager != null && backgroundSmbManager.hasActiveOperations()) {
      int count = backgroundSmbManager.getActiveOperationCount();
      new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
          .setTitle(R.string.quit_app_confirm_title)
          .setMessage(
              getResources().getQuantityString(R.plurals.quit_app_confirm_message, count, count))
          .setPositiveButton(R.string.quit_app_confirm_positive, (dialog, which) -> performQuit())
          .setNegativeButton(R.string.cancel, null)
          .show();
    } else {
      performQuit();
    }
  }

  private void performQuit() {
    if (backgroundSmbManager != null) {
      backgroundSmbManager.requestStopService();
    }
    finishAffinity();
  }
}
