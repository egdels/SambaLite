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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility class for timestamp preservation during file downloads. Provides deterministic timestamp
 * setting for {@code java.io.File} objects and logging with the {@code [TIMESTAMP]} prefix for
 * consistent Logcat filtering.
 */
public class TimestampUtils {

  private static final String TAG = "TimestampUtils";
  private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT =
      ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US));

  /**
   * Authorities for which both timestamp strategies have already failed in this session. Once an
   * authority is recorded here, further attempts are skipped (with a debug log) to avoid repeated
   * syscalls and warning spam, e.g. during large folder syncs.
   */
  private static final Set<String> FAILED_AUTHORITIES = ConcurrentHashMap.newKeySet();

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
   * Attempts to set the last-modified timestamp on a document URI. Two strategies are tried in
   * order:
   *
   * <ol>
   *   <li>{@code utimensat()} via {@code /proc/self/fd/} on the opened file descriptor
   *   <li>Resolving the URI to a real filesystem path (MediaStore {@code DATA} column or external
   *       storage document ID) and calling {@code File.setLastModified()} on it
   * </ol>
   *
   * <p>This is a best-effort operation: success is not guaranteed and depends on the storage
   * provider and device. The metadata database should always be updated independently of this
   * result.
   *
   * <p>If both strategies fail for a given URI authority, that authority is remembered for the rest
   * of the session and subsequent calls are skipped with a debug log to avoid repeated syscalls and
   * warning spam.
   *
   * @param context the application context for content resolver access
   * @param uri the document URI
   * @param timestampMillis the remote timestamp in epoch milliseconds
   * @return {@code true} if the timestamp was set successfully, {@code false} otherwise
   */
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

    String authority = uri.getAuthority();
    if (authority != null && FAILED_AUTHORITIES.contains(authority)) {
      LogUtils.d(
          TAG,
          "[TIMESTAMP] skipped (authority known to fail this session): "
              + authority
              + " - "
              + uri.getLastPathSegment());
      return false;
    }

    if (trySetViaProcFd(context, uri, timestampMillis)) {
      return true;
    }

    if (trySetViaResolvedPath(context, uri, timestampMillis)) {
      return true;
    }

    if (authority != null && FAILED_AUTHORITIES.add(authority)) {
      LogUtils.w(
          TAG,
          "[TIMESTAMP] all strategies failed for authority '"
              + authority
              + "' - suppressing further attempts for this session");
    }
    return false;
  }

  /**
   * First strategy: {@code utimensat()} via {@code /proc/self/fd/} on the opened file descriptor.
   *
   * @param context the application context for content resolver access
   * @param uri the document URI
   * @param timestampMillis the remote timestamp in epoch milliseconds
   * @return {@code true} if the timestamp was set successfully, {@code false} otherwise
   */
  @SuppressWarnings("resource")
  private static boolean trySetViaProcFd(
      @NonNull Context context, @NonNull Uri uri, long timestampMillis) {
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
   * Second strategy: resolve the URI to a real filesystem path (MediaStore {@code DATA} column,
   * external storage document ID or {@code file://} URI) and call {@code File.setLastModified()} on
   * it. Works for files owned by the app that are reachable via the FUSE-backed path on Android
   * 10+.
   *
   * @param context the application context for content resolver access
   * @param uri the document URI
   * @param timestampMillis the remote timestamp in epoch milliseconds
   * @return {@code true} if the timestamp was set successfully, {@code false} otherwise
   */
  private static boolean trySetViaResolvedPath(
      @NonNull Context context, @NonNull Uri uri, long timestampMillis) {
    try {
      java.io.File resolved = MediaStorePathResolver.resolveToFile(context, uri);
      if (resolved == null || !resolved.exists()) {
        LogUtils.d(
            TAG,
            "[TIMESTAMP] path fallback not applicable: "
                + uri.getLastPathSegment()
                + " (resolved="
                + (resolved != null ? resolved.getAbsolutePath() : "null")
                + ")");
        return false;
      }
      boolean success = resolved.setLastModified(timestampMillis);
      String dateStr = formatTimestamp(timestampMillis);
      if (success) {
        LogUtils.i(
            TAG,
            "[TIMESTAMP] path fallback succeeded: "
                + resolved.getAbsolutePath()
                + " (remote="
                + dateStr
                + ", ms="
                + timestampMillis
                + ")");
      } else {
        LogUtils.w(
            TAG,
            "[TIMESTAMP] path fallback FAILED: "
                + resolved.getAbsolutePath()
                + " (remote="
                + dateStr
                + ", set=false)");
      }
      return success;
    } catch (Exception e) {
      LogUtils.w(
          TAG,
          "[TIMESTAMP] path fallback failed: "
              + uri.getLastPathSegment()
              + " - "
              + e.getClass().getSimpleName()
              + ": "
              + e.getMessage());
      SmartErrorHandler.getInstance()
          .recordError(
              e, "TimestampUtils.trySetLastModified(path)", SmartErrorHandler.ErrorSeverity.LOW);
      return false;
    }
  }

  /**
   * Formats a timestamp in epoch milliseconds to a human-readable date string.
   *
   * @param timestampMillis the timestamp in epoch milliseconds
   * @return a formatted date string in {@code yyyy-MM-dd HH:mm:ss} format
   */
  @androidx.annotation.NonNull
  public static String formatTimestamp(long timestampMillis) {
    return DATE_FORMAT.get().format(new Date(timestampMillis));
  }
}
