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
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import androidx.annotation.NonNull;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Utility class for timestamp preservation during file downloads. Provides deterministic timestamp
 * setting for {@code java.io.File} objects and logging with the {@code [TIMESTAMP]} prefix for
 * consistent Logcat filtering.
 */
public class TimestampUtils {

  private static final String TAG = "TimestampUtils";
  private static final SimpleDateFormat DATE_FORMAT =
      new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

  /** Private constructor to prevent instantiation. */
  private TimestampUtils() {
    // Private constructor to prevent instantiation
  }

  /**
   * Sets the last-modified timestamp on a local file after download. This is the deterministic fix
   * for {@code java.io.File}-based downloads where {@code setLastModified()} is reliable.
   *
   * @param file the local file whose timestamp should be set
   * @param timestampMillis the remote timestamp in epoch milliseconds
   * @return {@code true} if the timestamp was set successfully, {@code false} otherwise
   */
  public static boolean setLastModified(@NonNull java.io.File file, long timestampMillis) {
    if (timestampMillis <= 0) {
      LogUtils.w(
          TAG,
          "[TIMESTAMP] SKIPPED: "
              + file.getName()
              + " (invalid remote timestamp: "
              + timestampMillis
              + "ms)");
      return false;
    }

    boolean success = file.setLastModified(timestampMillis);
    String dateStr = formatTimestamp(timestampMillis);

    if (success) {
      LogUtils.i(
          TAG,
          "[TIMESTAMP] preserved: "
              + file.getName()
              + " (remote="
              + dateStr
              + ", ms="
              + timestampMillis
              + ", set=true)");
    } else {
      LogUtils.w(
          TAG,
          "[TIMESTAMP] FAILED: "
              + file.getName()
              + " (remote="
              + dateStr
              + ", ms="
              + timestampMillis
              + ", set=false, path="
              + file.getAbsolutePath()
              + ")");
      SmartErrorHandler.getInstance()
          .recordError(
              new RuntimeException(
                  "Timestamp preservation failed: "
                      + file.getName()
                      + " at "
                      + file.getAbsolutePath()),
              "TimestampUtils.setLastModified",
              SmartErrorHandler.ErrorSeverity.LOW);
    }
    return success;
  }

  /**
   * Attempts to set the last-modified timestamp on a SAF document URI using {@code utimensat()} via
   * {@code /proc/self/fd/}. This is a best-effort operation: success is not guaranteed and depends
   * on the storage provider and device. The metadata database should always be updated
   * independently of this result.
   *
   * @param context the application context for content resolver access
   * @param uri the SAF document URI
   * @param timestampMillis the remote timestamp in epoch milliseconds
   * @return {@code true} if the timestamp was set successfully, {@code false} otherwise
   */
  @SuppressWarnings("resource")
  public static boolean trySetLastModified(
      @NonNull Context context, @NonNull Uri uri, long timestampMillis) {
    if (timestampMillis <= 0) {
      LogUtils.w(
          TAG,
          "[TIMESTAMP] SAF SKIPPED: "
              + uri.getLastPathSegment()
              + " (invalid remote timestamp: "
              + timestampMillis
              + "ms)");
      return false;
    }

    try {
      ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "rw");
      if (pfd != null) {
        try {
          Path procPath = Paths.get("/proc/self/fd/" + pfd.getFd());
          Files.setLastModifiedTime(procPath, FileTime.fromMillis(timestampMillis));
          String dateStr = formatTimestamp(timestampMillis);
          LogUtils.i(
              TAG,
              "[TIMESTAMP] SAF best-effort succeeded: "
                  + uri.getLastPathSegment()
                  + " (remote="
                  + dateStr
                  + ", ms="
                  + timestampMillis
                  + ")");
          return true;
        } finally {
          pfd.close();
        }
      }
    } catch (Exception e) {
      LogUtils.w(
          TAG,
          "[TIMESTAMP] SAF best-effort failed (expected on some devices): "
              + uri.getLastPathSegment()
              + " - "
              + e.getClass().getSimpleName()
              + ": "
              + e.getMessage());
      SmartErrorHandler.getInstance()
          .recordError(
              e, "TimestampUtils.trySetLastModified(SAF)", SmartErrorHandler.ErrorSeverity.LOW);
    }
    return false;
  }

  /**
   * Formats a timestamp in epoch milliseconds to a human-readable date string.
   *
   * @param timestampMillis the timestamp in epoch milliseconds
   * @return a formatted date string in {@code yyyy-MM-dd HH:mm:ss} format
   */
  @androidx.annotation.NonNull
  public static String formatTimestamp(long timestampMillis) {
    synchronized (DATE_FORMAT) {
      return DATE_FORMAT.format(new Date(timestampMillis));
    }
  }
}
