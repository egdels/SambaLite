/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.util;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Advanced error handling and crash reporting system for SambaLite. Provides intelligent error
 * categorization, crash analytics, and recovery suggestions.
 */
public class SmartErrorHandler {

  private static final String TAG = "SmartErrorHandler";
  private static final String PREFS_NAME = "error_prefs";
  private static final int MAX_ERROR_HISTORY = 50;

  private static SmartErrorHandler instance;
  private final ConcurrentLinkedQueue<ErrorRecord> errorHistory;
  private final AtomicInteger totalErrors = new AtomicInteger(0);
  private final AtomicInteger criticalErrors = new AtomicInteger(0);
  private final AtomicInteger networkErrors = new AtomicInteger(0);
  private final AtomicInteger smbErrors = new AtomicInteger(0);

  private SmartErrorHandler() {
    this.errorHistory = new ConcurrentLinkedQueue<>();
    LogUtils.i(TAG, "SmartErrorHandler initialized");
  }

  /** Singleton instance retrieval. Ensures thread-safe lazy initialization. */
  public static synchronized @NonNull SmartErrorHandler getInstance() {
    if (instance == null) {
      instance = new SmartErrorHandler();
    }
    return instance;
  }

  /** Records an error with intelligent categorization. */
  public void recordError(
      @NonNull Throwable error, @NonNull String context, @NonNull ErrorSeverity severity) {
    if (error == null) return;

    try {
      ErrorCategory category = categorizeError(error);
      ErrorRecord record =
          new ErrorRecord(
              System.currentTimeMillis(),
              error.getClass().getSimpleName(),
              error.getMessage(),
              getStackTrace(error),
              context,
              severity,
              category);

      // Add to history
      errorHistory.offer(record);
      if (errorHistory.size() > MAX_ERROR_HISTORY) {
        errorHistory.poll(); // Remove oldest
      }

      // Update counters
      totalErrors.incrementAndGet();
      switch (severity) {
        case CRITICAL:
          criticalErrors.incrementAndGet();
          break;
        default:
          break;
      }

      switch (category) {
        case NETWORK:
          networkErrors.incrementAndGet();
          break;
        case SMB_PROTOCOL:
          smbErrors.incrementAndGet();
          break;
        default:
          break;
      }

      // Log the error
      String logMessage =
          "Error recorded: "
              + record.errorType
              + " in "
              + context
              + " - "
              + error.getMessage()
              + " ("
              + severity
              + ", "
              + category
              + ")";

      switch (severity) {
        case CRITICAL:
          LogUtils.e(TAG, logMessage);
          break;
        case HIGH:
          LogUtils.w(TAG, logMessage);
          break;
        case MEDIUM:
        case LOW:
          LogUtils.d(TAG, logMessage);
          break;
      }

    } catch (Exception e) {
      LogUtils.e(TAG, "Failed to record error: " + e.getMessage());
    }
  }

  /** Gets error statistics. */
  public @NonNull ErrorStats getErrorStats() {
    return new ErrorStats(
        totalErrors.get(),
        criticalErrors.get(),
        networkErrors.get(),
        smbErrors.get(),
        errorHistory.size(),
        getErrorTrends());
  }

  /** Saves error statistics to preferences. */
  public void saveErrorStats(@NonNull Context context) {
    if (context == null) return;

    try {
      SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
      ErrorStats stats = getErrorStats();

      prefs
          .edit()
          .putInt("total_errors", stats.totalErrors)
          .putInt("critical_errors", stats.criticalErrors)
          .putInt("network_errors", stats.networkErrors)
          .putInt("smb_errors", stats.smbErrors)
          .putLong("last_update", System.currentTimeMillis())
          .apply();

      LogUtils.d(TAG, "Error stats saved: " + stats);

    } catch (Exception e) {
      LogUtils.e(TAG, "Failed to save error stats: " + e.getMessage());
    }
  }

  /** Sets up default uncaught exception handler. */
  public void setupGlobalErrorHandler() {
    final Thread.UncaughtExceptionHandler previousHandler =
        Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, exception) -> {
          recordError(exception, "UncaughtException", ErrorSeverity.CRITICAL);
          LogUtils.e(
              TAG,
              "Uncaught exception in thread " + thread.getName() + ": " + exception.getMessage());

          // For SQLiteFullException on WorkManager threads, swallow the exception
          // to prevent app crash. WorkManager internal DB operations (e.g.
          // SystemIdInfoDao.removeSystemIdInfo) can throw SQLITE_FULL when disk is
          // full, and we cannot prevent WorkManager from attempting these operations.
          if (isSQLiteFullOnWorkerThread(thread, exception)) {
            LogUtils.w(
                TAG,
                "Swallowing SQLiteFullException on WorkManager thread "
                    + thread.getName()
                    + " to prevent app crash");
            return;
          }

          // Call previous handler
          if (previousHandler != null) {
            previousHandler.uncaughtException(thread, exception);
          }
        });

    LogUtils.i(TAG, "Global error handler set up");
  }

  private boolean isSQLiteFullOnWorkerThread(Thread thread, Throwable exception) {
    if (exception == null) return false;
    String exName = exception.getClass().getSimpleName();
    boolean isSQLiteFull = exName.contains("SQLiteFull") || exName.contains("SQLiteDiskIO");
    if (!isSQLiteFull) {
      String msg = exception.getMessage();
      isSQLiteFull = msg != null && (msg.contains("SQLITE_FULL") || msg.contains("disk is full"));
    }
    boolean isWorkerThread =
        thread.getName().startsWith("WM.") || thread.getName().contains("WorkManager");
    return isSQLiteFull && isWorkerThread;
  }

  private ErrorCategory categorizeError(Throwable error) {
    if (error == null) return ErrorCategory.UNKNOWN;

    String className = error.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    String message = error.getMessage() != null ? error.getMessage().toLowerCase(Locale.ROOT) : "";

    // Network-related errors
    if (className.contains("socket")
        || className.contains("connect")
        || className.contains("network")
        || className.contains("timeout")
        || message.contains("network")
        || message.contains("connection")) {
      return ErrorCategory.NETWORK;
    }

    // SMB protocol errors
    if (className.contains("smb")
        || message.contains("smb")
        || message.contains("authentication")
        || message.contains("share")
        || message.contains("cifs")) {
      return ErrorCategory.SMB_PROTOCOL;
    }

    // File system errors
    if (className.contains("file")
        || className.contains("io")
        || message.contains("access denied")
        || message.contains("space")) {
      return ErrorCategory.FILE_SYSTEM;
    }

    // Security errors
    if (className.contains("security")
        || className.contains("permission")
        || message.contains("denied")) {
      return ErrorCategory.SECURITY;
    }

    // Memory errors
    if (className.contains("memory")
        || className.contains("outofmemory")
        || message.contains("memory")) {
      return ErrorCategory.MEMORY;
    }

    return ErrorCategory.UNKNOWN;
  }

  private String getStackTrace(Throwable error) {
    try {
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter(sw);
      error.printStackTrace(pw);
      return sw.toString();
    } catch (Exception e) {
      return "Unable to get stack trace: " + e.getMessage();
    }
  }

  private String getErrorTrends() {
    if (errorHistory.isEmpty()) return "No data";

    long oneHourAgo = System.currentTimeMillis() - (60 * 60 * 1000);
    int recentErrors = 0;

    for (ErrorRecord record : errorHistory) {
      if (record.timestamp > oneHourAgo) {
        recentErrors++;
      }
    }

    if (recentErrors == 0) {
      return "Stable";
    } else if (recentErrors <= 2) {
      return "Low activity";
    } else if (recentErrors <= 5) {
      return "Moderate activity";
    } else {
      return "High activity";
    }
  }

  // Enums and data classes

  public enum ErrorSeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
  }

  public enum ErrorCategory {
    NETWORK,
    SMB_PROTOCOL,
    FILE_SYSTEM,
    SECURITY,
    MEMORY,
    UNKNOWN
  }

  public static class ErrorRecord {
    public final long timestamp;
    public final String errorType;
    public final String message;
    public final String stackTrace;
    public final String context;
    public final ErrorSeverity severity;
    public final ErrorCategory category;

    ErrorRecord(
        long timestamp,
        String errorType,
        String message,
        String stackTrace,
        String context,
        ErrorSeverity severity,
        ErrorCategory category) {
      this.timestamp = timestamp;
      this.errorType = errorType;
      this.message = message;
      this.stackTrace = stackTrace;
      this.context = context;
      this.severity = severity;
      this.category = category;
    }
  }

  public static class ErrorStats {
    public final int totalErrors;
    public final int criticalErrors;
    public final int networkErrors;
    public final int smbErrors;
    public final int recentErrors;
    public final String trends;

    ErrorStats(
        int totalErrors,
        int criticalErrors,
        int networkErrors,
        int smbErrors,
        int recentErrors,
        String trends) {
      this.totalErrors = totalErrors;
      this.criticalErrors = criticalErrors;
      this.networkErrors = networkErrors;
      this.smbErrors = smbErrors;
      this.recentErrors = recentErrors;
      this.trends = trends;
    }

    @Override
    public String toString() {
      return String.format(
          Locale.ROOT,
          "ErrorStats{total=%d, critical=%d, network=%d, smb=%d, recent=%d, trends=%s}",
          totalErrors,
          criticalErrors,
          networkErrors,
          smbErrors,
          recentErrors,
          trends);
    }
  }
}
