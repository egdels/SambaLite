/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite;

import android.app.Application;
import android.os.StatFs;
import androidx.annotation.NonNull;
import androidx.work.Configuration;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import de.schliweb.sambalite.cache.IntelligentCacheManager;
import de.schliweb.sambalite.di.AppComponent;
import de.schliweb.sambalite.di.DaggerAppComponent;
import de.schliweb.sambalite.transfer.TransferWorker;
import de.schliweb.sambalite.transfer.db.PendingTransferDao;
import de.schliweb.sambalite.transfer.db.TransferDatabase;
import de.schliweb.sambalite.util.LogUtils;
import de.schliweb.sambalite.util.SambaLiteLifecycleTracker;
import de.schliweb.sambalite.util.SimplePerformanceMonitor;
import de.schliweb.sambalite.util.SmartErrorHandler;
import java.io.File;
import java.util.concurrent.Executors;
import lombok.Getter;

/**
 * Enhanced main Application class for SambaLite. Initializes Dagger component and other app-wide
 * configurations.
 */
public class SambaLiteApp extends Application implements Configuration.Provider {

  /** -- GETTER -- Gets the Dagger application component. */
  @Getter AppComponent appComponent;

  /** -- GETTER -- Gets the cache manager instance. */
  @Getter private IntelligentCacheManager cacheManager;

  /** -- GETTER -- Gets the error handler instance. */
  @Getter private SmartErrorHandler errorHandler;

  private static final String TRANSFER_WORK_NAME = "transfer_queue";

  SambaLiteLifecycleTracker lifecycleTracker;

  @NonNull
  @Override
  public Configuration getWorkManagerConfiguration() {
    return new Configuration.Builder()
        .setMinimumLoggingLevel(android.util.Log.INFO)
        .setInitializationExceptionHandler(
            throwable ->
                LogUtils.e(
                    "SambaLiteApp",
                    "WorkManager initialization failed (disk full?): " + throwable.getMessage()))
        .build();
  }

  /**
   * Checks for pending or interrupted transfers after app restart/device reboot and starts the
   * TransferWorker to resume them.
   */
  private void resumePendingTransfers() {
    Executors.newSingleThreadExecutor()
        .execute(
            () -> {
              try {
                // Skip DB access when disk is (nearly) full to avoid SQLite crashes
                File dataDir = getFilesDir();
                if (dataDir != null) {
                  StatFs stat = new StatFs(dataDir.getPath());
                  long availableBytes = stat.getAvailableBytes();
                  if (availableBytes < 1_048_576) { // < 1 MB
                    LogUtils.w(
                        "SambaLiteApp",
                        "Skipping transfer resume – low disk space: " + availableBytes + " bytes");
                    return;
                  }
                }

                PendingTransferDao dao = TransferDatabase.getInstance(this).pendingTransferDao();

                // Reset any transfers stuck in ACTIVE state (interrupted by kill/reboot)
                long now = System.currentTimeMillis();
                int resetActive = dao.resetActiveToRetry(now);
                if (resetActive > 0) {
                  LogUtils.i(
                      "SambaLiteApp", "Reset " + resetActive + " interrupted transfers to PENDING");
                }

                // Reset FAILED transfers for fresh retry after app restart/reboot
                int resetFailed = dao.resetFailedToRetry(now);
                if (resetFailed > 0) {
                  LogUtils.i(
                      "SambaLiteApp", "Reset " + resetFailed + " failed transfers for retry");
                }

                // Check if there are any pending transfers to process
                int pendingCount = dao.getPendingCountSync();
                LogUtils.i("SambaLiteApp", "Pending transfer count: " + pendingCount);
                if (pendingCount > 0) {
                  LogUtils.i(
                      "SambaLiteApp",
                      "Found " + pendingCount + " pending transfers, starting worker");
                  OneTimeWorkRequest request =
                      new OneTimeWorkRequest.Builder(TransferWorker.class)
                          .setConstraints(
                              new Constraints.Builder()
                                  .setRequiredNetworkType(NetworkType.CONNECTED)
                                  .build())
                          .build();
                  WorkManager.getInstance(this)
                      .enqueueUniqueWork(TRANSFER_WORK_NAME, ExistingWorkPolicy.REPLACE, request);
                } else {
                  LogUtils.i("SambaLiteApp", "No pending transfers found");
                }
              } catch (Exception e) {
                LogUtils.e("SambaLiteApp", "Error resuming pending transfers: " + e.getMessage());
              }
            });
  }

  @Override
  public void onCreate() {
    super.onCreate();

    LogUtils.i("SambaLiteApp", "Starting SambaLite application initialization");

    try {
      // Initialize Timber logging first
      LogUtils.init(BuildConfig.DEBUG);
      LogUtils.i("SambaLiteApp", "Logging system initialized");

      // Initialize performance monitoring (static methods)
      LogUtils.i("SambaLiteApp", "Memory: " + SimplePerformanceMonitor.getMemoryInfo());
      LogUtils.i("SambaLiteApp", "Device: " + SimplePerformanceMonitor.getDeviceInfo());
      LogUtils.i("SambaLiteApp", "Performance monitoring initialized");

      // Initialize advanced systems
      IntelligentCacheManager.initialize(this);
      cacheManager = IntelligentCacheManager.getInstance();
      errorHandler = SmartErrorHandler.getInstance();

      // Setup global error handling
      errorHandler.setupGlobalErrorHandler();

      LogUtils.i("SambaLiteApp", "Advanced systems initialized");

      // Initialize Dagger dependency injection
      appComponent = DaggerAppComponent.builder().application(this).build();
      appComponent.inject(this);
      LogUtils.i("SambaLiteApp", "Dependency injection initialized");

      // Initialize background-aware connection management
      lifecycleTracker = SambaLiteLifecycleTracker.getInstance();
      registerActivityLifecycleCallbacks(lifecycleTracker);

      lifecycleTracker.setLifecycleListener(
          new SambaLiteLifecycleTracker.LifecycleListener() {
            @Override
            public void onAppMovedToBackground() {
              LogUtils.i(
                  "SambaLiteApp",
                  "Eva's app moved to background - SMB connections may be affected");
              // SMB connections will be automatically recreated on next use
            }

            @Override
            public void onAppMovedToForeground() {
              LogUtils.i(
                  "SambaLiteApp", "Eva's app moved to foreground - ready for SMB operations");
              // Connections will be automatically restored when needed

              // Clean up expired and oversized open-file cache entries
              de.schliweb.sambalite.util.OpenFileCacheManager.cleanupOnAppStart(SambaLiteApp.this);
              de.schliweb.sambalite.util.OpenFileCacheManager.enforceMaxSize(SambaLiteApp.this);
            }
          });

      LogUtils.i("SambaLiteApp", "Background-aware connection management initialized");

      // Clean up expired open-file cache entries
      de.schliweb.sambalite.util.OpenFileCacheManager.cleanupOnAppStart(this);
      LogUtils.i("SambaLiteApp", "Open-file cache cleanup completed");

      // Clean up stale temporary upload/download files left behind by swipe-kill
      cleanupStaleTempFiles();
      LogUtils.i("SambaLiteApp", "Stale temp file cleanup completed");

      // Initialize WorkManager with custom configuration (default initializer is disabled)
      try {
        WorkManager.initialize(this, getWorkManagerConfiguration());
      } catch (IllegalStateException alreadyInitialized) {
        // WorkManager was already initialized – safe to ignore
      }

      // Resume pending transfers after device reboot or app restart
      resumePendingTransfers();
      LogUtils.i("SambaLiteApp", "Pending transfer resume check completed");

      LogUtils.i("SambaLiteApp", "SambaLite application fully initialized");

    } catch (Exception e) {
      LogUtils.e("SambaLiteApp", "Critical error during app initialization: " + e.getMessage());

      // Record critical error
      if (errorHandler != null) {
        errorHandler.recordError(
            e, "SambaLiteApp.onCreate", SmartErrorHandler.ErrorSeverity.CRITICAL);
      }

      throw e; // Re-throw to ensure app doesn't start in broken state
    }
  }

  @Override
  public void onTerminate() {
    LogUtils.i("SambaLiteApp", "Application terminating");

    // Save statistics before termination
    try {
      if (cacheManager != null) {
        cacheManager.shutdown();
      }
      if (errorHandler != null) {
        errorHandler.saveErrorStats(this);
      }

      LogUtils.i("SambaLiteApp", "App statistics saved successfully");
    } catch (Exception e) {
      LogUtils.e("SambaLiteApp", "Error saving app statistics: " + e.getMessage());
    }

    super.onTerminate();
  }

  @Override
  public void onLowMemory() {
    super.onLowMemory();
    LogUtils.w("SambaLiteApp", "Low memory condition detected");

    // Clear cache to free memory
    if (cacheManager != null) {
      cacheManager.clearAll();
      LogUtils.i("SambaLiteApp", "Cache cleared due to low memory");
    }

    // Force garbage collection
    System.gc();
  }

  @Override
  public void onTrimMemory(int level) {
    super.onTrimMemory(level);
    LogUtils.d("SambaLiteApp", "Memory trim requested with level: " + level);

    // Perform cleanup for significant memory pressure using non-deprecated levels
    if (level == TRIM_MEMORY_UI_HIDDEN || level >= TRIM_MEMORY_BACKGROUND) {
      System.gc();
      LogUtils.w("SambaLiteApp", "Performed memory cleanup");
    }
  }

  /** Gets application health status for monitoring. */
  public @NonNull ApplicationHealthStatus getHealthStatus() {
    ApplicationHealthStatus status = new ApplicationHealthStatus();

    // Check if all systems are initialized
    status.dependencyInjectionReady = (appComponent != null);
    status.performanceMonitoringReady = true; // Performance monitoring is now static
    status.cacheSystemReady = (cacheManager != null);
    status.errorHandlerReady = (errorHandler != null);
    status.overallHealthy =
        status.dependencyInjectionReady
            && status.performanceMonitoringReady
            && status.cacheSystemReady
            && status.errorHandlerReady;

    return status;
  }

  /**
   * Removes stale temporary files (upload*.tmp, download*.tmp) from the cache directory. These
   * files can be left behind when the app process is killed (e.g. swipe-kill) before the normal
   * cleanup in finally-blocks can run.
   */
  private void cleanupStaleTempFiles() {
    try {
      File cacheDir = getCacheDir();
      File[] tempFiles =
          cacheDir.listFiles(
              f ->
                  f.isFile()
                      && f.getName().endsWith(".tmp")
                      && (f.getName().startsWith("upload") || f.getName().startsWith("download")));
      if (tempFiles != null) {
        for (File f : tempFiles) {
          if (f.delete()) {
            LogUtils.d("SambaLiteApp", "Deleted stale temp file: " + f.getName());
          }
        }
      }
    } catch (Exception e) {
      LogUtils.w("SambaLiteApp", "Error cleaning up stale temp files: " + e.getMessage());
    }
  }

  /** Data class for application health monitoring. */
  public static class ApplicationHealthStatus {
    public boolean overallHealthy;
    public boolean dependencyInjectionReady;
    public boolean performanceMonitoringReady;
    public boolean cacheSystemReady;
    public boolean errorHandlerReady;

    @Override
    public String toString() {
      return "ApplicationHealthStatus{"
          + "dependencyInjection="
          + dependencyInjectionReady
          + ", performanceMonitoring="
          + performanceMonitoringReady
          + ", cacheSystem="
          + cacheSystemReady
          + ", errorHandler="
          + errorHandlerReady
          + ", overall="
          + overallHealthy
          + '}';
    }
  }
}
