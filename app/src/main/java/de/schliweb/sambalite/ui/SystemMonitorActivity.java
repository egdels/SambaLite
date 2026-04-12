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
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;
import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.SambaLiteApp;
import de.schliweb.sambalite.cache.IntelligentCacheManager;
import de.schliweb.sambalite.cache.statistics.CacheStatistics;
import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.sync.SyncActionLog;
import de.schliweb.sambalite.sync.db.SyncStateStore;
import de.schliweb.sambalite.ui.operations.TransferActionLog;
import de.schliweb.sambalite.ui.utils.LoadingIndicator;
import de.schliweb.sambalite.util.EnhancedFileUtils;
import de.schliweb.sambalite.util.LogUtils;
import de.schliweb.sambalite.util.SimplePerformanceMonitor;
import de.schliweb.sambalite.util.SmartErrorHandler;
import java.util.Locale;
import javax.inject.Inject;

/**
 * Advanced system monitoring and administration activity for SambaLite. Provides comprehensive
 * insights into app performance, cache, network, and errors.
 */
public class SystemMonitorActivity extends AppCompatActivity {

  private static final String TAG = "SystemMonitorActivity";

  private TextView statusOverview;
  private LoadingIndicator loadingIndicator;
  private SmartErrorHandler errorHandler;

  @Inject BackgroundSmbManager backgroundSmbManager;

  public static @Nullable Intent createIntent(@NonNull Context context) {
    return new Intent(context, SystemMonitorActivity.class);
  }

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    // Inject dependencies
    ((SambaLiteApp) getApplication()).getAppComponent().inject(this);

    super.onCreate(savedInstanceState);

    // Initialize loading indicator
    loadingIndicator = new LoadingIndicator(this);
    LogUtils.d(TAG, "Loading indicator initialized");

    // Configure edge-to-edge display using backward-compatible helper (Android 15+ safe)
    EdgeToEdge.enable(this);

    LogUtils.d(TAG, "SystemMonitorActivity created");

    // Initialize SmartErrorHandler
    errorHandler = ((SambaLiteApp) getApplication()).getErrorHandler();

    // Use XML layout instead of programmatic creation
    setContentView(R.layout.activity_system_monitor);

    // Set up the toolbar
    androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
    if (toolbar != null) {
      setSupportActionBar(toolbar);
      if (getSupportActionBar() != null) {
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle(getString(R.string.system_monitor));
      }
    }

    // Apply window insets for navigation bar
    androidx.core.widget.NestedScrollView scrollView = findViewById(R.id.scroll_view);
    if (scrollView != null) {
      ViewCompat.setOnApplyWindowInsetsListener(
          scrollView,
          (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(
                v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), insets.bottom + 16);
            return windowInsets;
          });
    }

    // Get the TextView from layout
    statusOverview = findViewById(R.id.status_overview);

    if (statusOverview == null) {
      LogUtils.e(TAG, "status_overview TextView not found in layout, using fallback");

      // Record UI error
      if (errorHandler != null) {
        errorHandler.recordError(
            new RuntimeException("status_overview TextView not found"),
            "SystemMonitorActivity.onCreate",
            SmartErrorHandler.ErrorSeverity.HIGH);
      }
      createSystemMonitorLayoutFallback();
    } else {
      LogUtils.d(TAG, "status_overview TextView found successfully");
    }

    refreshSystemStatus();
  }

  private void createSystemMonitorLayoutFallback() {
    // Fallback method for programmatic layout creation if XML layout fails
    LogUtils.w(TAG, "Using fallback programmatic layout creation");

    androidx.core.widget.NestedScrollView scrollView =
        new androidx.core.widget.NestedScrollView(this);
    scrollView.setFillViewport(true);
    scrollView.setPadding(16, 16, 16, 16);
    scrollView.setBackgroundColor(getResources().getColor(android.R.color.background_light, null));

    statusOverview = new TextView(this);
    statusOverview.setPadding(20, 20, 20, 20);
    statusOverview.setTextSize(14);
    statusOverview.setTypeface(android.graphics.Typeface.MONOSPACE);
    // Use theme-aware colors instead of hardcoded ones
    int textColor = android.graphics.Color.BLACK;
    int backgroundColor = android.graphics.Color.WHITE;

    try {
      // Try to get theme-aware colors
      android.util.TypedValue typedValue = new android.util.TypedValue();
      getTheme().resolveAttribute(android.R.attr.textColorPrimary, typedValue, true);
      if (typedValue.data != 0) {
        textColor = typedValue.data;
      }

      getTheme().resolveAttribute(android.R.attr.colorBackground, typedValue, true);
      if (typedValue.data != 0) {
        backgroundColor = typedValue.data;
      }
    } catch (Exception e) {
      LogUtils.w(TAG, "Could not resolve theme colors, using defaults: " + e.getMessage());

      // Record theme error (low severity)
      if (errorHandler != null) {
        errorHandler.recordError(
            e, "SystemMonitorActivity.setupFallbackTextView", SmartErrorHandler.ErrorSeverity.LOW);
      }
    }

    statusOverview.setTextColor(textColor);
    statusOverview.setBackgroundColor(backgroundColor);

    scrollView.addView(statusOverview);
    setContentView(scrollView);

    if (getSupportActionBar() != null) {
      getSupportActionBar().setDisplayHomeAsUpEnabled(true);
      getSupportActionBar().setTitle("System Monitor");
    }

    LogUtils.d(TAG, "Fallback layout created");
  }

  private void refreshSystemStatus() {
    LogUtils.d(TAG, "Refreshing system status");

    if (statusOverview == null) {
      LogUtils.e(TAG, "statusOverview TextView is null, cannot refresh status");

      // Record critical UI error
      if (errorHandler != null) {
        errorHandler.recordError(
            new RuntimeException("statusOverview TextView is null"),
            "SystemMonitorActivity.refreshSystemStatus",
            SmartErrorHandler.ErrorSeverity.CRITICAL);
      }
      return;
    }

    // Show loading indicator while gathering system information
    loadingIndicator.show("Gathering system information...");

    try {
      SambaLiteApp app = (SambaLiteApp) getApplication();

      String fullStatus =
          "=== SambaLite System Monitor ===\n"
              + "Generated: "
              + getCurrentTimeString()
              + "\n\n"
              +

              // Update system overview
              getSystemOverview(app)
              + "\n\n"
              +

              // Update performance metrics
              getPerformanceMetrics()
              + "\n\n"
              +

              // Update cache statistics
              getCacheStatistics(app)
              + "\n\n"
              +

              // Update network status
              getNetworkStatus()
              + "\n\n"
              +

              // Update error summary
              getErrorSummary(app)
              + "\n\n"
              +

              // Update sync activity log
              getSyncActivityLog()
              + "\n\n"
              +

              // Update transfer activity log
              getTransferActivityLog()
              + "\n\n"
              +

              // Update timestamp preservation status
              getTimestampStatus();

      statusOverview.setText(fullStatus);

      // Hide loading indicator after successful update
      loadingIndicator.hide();

      LogUtils.i(TAG, "System status updated successfully");

    } catch (Exception e) {
      LogUtils.e(TAG, "Failed to refresh system status: " + e.getMessage());

      // Record system monitoring error
      if (errorHandler != null) {
        errorHandler.recordError(
            e, "SystemMonitorActivity.refreshSystemStatus", SmartErrorHandler.ErrorSeverity.HIGH);
      }

      String errorStatus =
          "=== System Monitor Error ===\n\n"
              + "Failed to load system information:\n"
              + e.getMessage()
              + "\n\n"
              + "Time: "
              + getCurrentTimeString()
              + "\n\n"
              + "Please try refreshing the page or restart the app.";
      statusOverview.setText(errorStatus);

      // Hide loading indicator after error
      loadingIndicator.hide();
    }
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
    metrics.append("Memory: ").append(SimplePerformanceMonitor.getMemoryInfo()).append("\n");
    metrics.append("Device: ").append(SimplePerformanceMonitor.getDeviceInfo()).append("\n");
    metrics
        .append("Performance: ")
        .append(SimplePerformanceMonitor.getPerformanceStats())
        .append("\n");

    // Disk space information
    metrics.append("\n=== Disk Space ===\n");

    // Internal storage
    java.io.File internalDir = getFilesDir();
    long internalFree = internalDir.getFreeSpace();
    long internalTotal = internalDir.getTotalSpace();
    long internalUsed = internalTotal - internalFree;
    metrics
        .append("Internal Storage:\n")
        .append("- Used: ")
        .append(EnhancedFileUtils.formatFileSize(internalUsed))
        .append(" / ")
        .append(EnhancedFileUtils.formatFileSize(internalTotal))
        .append("\n")
        .append("- Free: ")
        .append(EnhancedFileUtils.formatFileSize(internalFree))
        .append("\n");

    // External storage
    java.io.File externalDir = android.os.Environment.getExternalStorageDirectory();
    long externalFree = externalDir.getFreeSpace();
    long externalTotal = externalDir.getTotalSpace();
    long externalUsed = externalTotal - externalFree;
    metrics
        .append("External Storage:\n")
        .append("- Used: ")
        .append(EnhancedFileUtils.formatFileSize(externalUsed))
        .append(" / ")
        .append(EnhancedFileUtils.formatFileSize(externalTotal))
        .append("\n")
        .append("- Free: ")
        .append(EnhancedFileUtils.formatFileSize(externalFree))
        .append("\n");

    // Minimum required space warning
    boolean internalOk = EnhancedFileUtils.hasEnoughDiskSpace(internalDir);
    boolean externalOk = EnhancedFileUtils.hasEnoughDiskSpace(externalDir);
    if (!internalOk || !externalOk) {
      metrics.append("\n⚠ WARNING: Low disk space");
      if (!internalOk) metrics.append(" (internal)");
      if (!externalOk) metrics.append(" (external)");
      metrics.append("\n");
    }

    return metrics.toString();
  }

  private String getCacheStatistics(SambaLiteApp app) {
    StringBuilder stats = new StringBuilder();

    stats.append("=== Cache Statistics ===\n");

    // Open-File Cache size
    java.io.File openFileCacheDir =
        de.schliweb.sambalite.util.OpenFileCacheManager.getCacheDir(app);
    java.io.File[] openFiles = openFileCacheDir.listFiles();
    long openFileCacheSize = 0;
    int openFileCount = 0;
    if (openFiles != null) {
      for (java.io.File f : openFiles) {
        openFileCacheSize += f.length();
        openFileCount++;
      }
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
      SyncStateStore store = new SyncStateStore(this);
      int total = store.getTotalCount();
      int preserved = store.getTimestampPreservedCount();
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
  protected void onResume() {
    super.onResume();
    refreshSystemStatus();
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    // Inflate the menu; this adds items to the action bar if it is present.
    getMenuInflater().inflate(R.menu.menu_system_monitor, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    // Handle action bar item clicks here.
    int id = item.getItemId();

    if (id == R.id.action_refresh) {
      // Refresh action
      LogUtils.d(TAG, "Refresh menu item clicked");
      refreshSystemStatus();
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
