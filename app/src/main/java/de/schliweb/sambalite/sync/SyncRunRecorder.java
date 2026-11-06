/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * Collects what happens in one sync run of one config (#50). Every action goes to the detailed run
 * log of the config and is counted; everything except skipped files also goes to the shared {@link
 * SyncActionLog}, prefixed with the config name. At the end, {@link #finish} turns the counts into
 * a {@link SyncRunResult}.
 *
 * <p>Not thread-safe; one recorder belongs to one run on the worker thread.
 */
public final class SyncRunRecorder {

  /**
   * Told about the progress of the run, at most every {@link #PROGRESS_INTERVAL_MS}. The recorder
   * passes itself, so the listener can publish the live counters with the progress.
   */
  public interface ProgressListener {
    void onProgress(@NonNull SyncRunRecorder run, @NonNull String currentPath);
  }

  static final long PROGRESS_INTERVAL_MS = 750;

  @Nullable private final SyncActionLog sharedLog;
  @Nullable private final SyncRunLog runLog;
  @NonNull private final String configId;
  @NonNull private final String configName;
  @Nullable private final ProgressListener progressListener;
  private final long startedAt;

  private final EnumMap<SyncActionLog.Action, Integer> counts =
      new EnumMap<>(SyncActionLog.Action.class);
  private final EnumMap<SyncErrorCategory, Integer> errorCategories =
      new EnumMap<>(SyncErrorCategory.class);
  private final List<SyncRunResult.RunError> errors = new ArrayList<>();
  private int errorCount;
  private int processedFiles;
  private long lastProgressAt;

  public SyncRunRecorder(
      @Nullable SyncActionLog sharedLog,
      @Nullable SyncRunLog runLog,
      @NonNull String configId,
      @NonNull String configName,
      @Nullable ProgressListener progressListener,
      long startedAt) {
    this.sharedLog = sharedLog;
    this.runLog = runLog;
    this.configId = configId;
    this.configName = configName;
    this.progressListener = progressListener;
    this.startedAt = startedAt;
  }

  /** Logs an action on {@code path} (relative to the sync root). */
  public void log(@NonNull SyncActionLog.Action action, @NonNull String path) {
    log(action, path, null);
  }

  /** Logs an action on {@code path} with a detail, such as the reason a file was skipped. */
  public void log(
      @NonNull SyncActionLog.Action action, @NonNull String path, @Nullable String detail) {
    long now = System.currentTimeMillis();
    counts.merge(action, 1, Integer::sum);
    if (runLog != null) {
      runLog.append(now, action, path, detail);
    }
    if (sharedLog != null && action != SyncActionLog.Action.SKIPPED) {
      String label = configName + " › " + path;
      if (detail != null && !detail.isEmpty()) {
        sharedLog.log(action, label, detail);
      } else {
        sharedLog.log(action, label);
      }
    }
    if (isFileAction(action)) {
      processedFiles++;
      reportProgress(path, now);
    }
  }

  /**
   * Logs an action on the run as a whole, such as an abort for low disk space. In the shared log
   * the entry is named after the config.
   */
  public void logRun(@NonNull SyncActionLog.Action action, @NonNull String detail) {
    if (action != SyncActionLog.Action.ERROR) {
      // A failed run is told by its status; it must not count as a failed file
      counts.merge(action, 1, Integer::sum);
    }
    if (runLog != null) {
      runLog.append(System.currentTimeMillis(), action, "", detail);
    }
    if (sharedLog != null) {
      sharedLog.log(action, configName, detail);
    }
  }

  /**
   * Logs a failed file. The category comes from the exception and, when the server gives no telling
   * status, from the name ({@link SyncErrorCategory#classify(Throwable, String)}).
   */
  public void error(@NonNull String path, @Nullable String fileName, @Nullable Throwable error) {
    SyncErrorCategory category = SyncErrorCategory.classify(error, fileName);
    String message = SyncErrorCategory.describe(error);
    addError(path, message, category);
    log(SyncActionLog.Action.ERROR, path, message);
  }

  /**
   * Logs a local file or folder that is not uploaded because its name contains {@code forbidden}, a
   * character SMB does not allow. This is certain, not guessed from an error, so it is always
   * {@link SyncErrorCategory#INVALID_NAME}. A folder is skipped with its content and counts as one
   * error, not as a file.
   */
  public void invalidName(@NonNull String path, boolean isFolder, char forbidden) {
    String message =
        forbidden < 0x20
            ? "name contains a control character, which SMB does not allow"
            : "name contains '" + forbidden + "', which SMB does not allow";
    if (!isFolder) {
      addError(path, message, SyncErrorCategory.INVALID_NAME);
      log(SyncActionLog.Action.ERROR, path, message);
      return;
    }
    String folderPath = path.endsWith("/") ? path : path + "/";
    addError(folderPath, message, SyncErrorCategory.INVALID_NAME);
    if (runLog != null) {
      runLog.append(System.currentTimeMillis(), SyncActionLog.Action.ERROR, folderPath, message);
    }
    if (sharedLog != null) {
      sharedLog.log(SyncActionLog.Action.ERROR, configName + " › " + folderPath, message);
    }
  }

  /**
   * Logs a folder that could not be listed. Its content was not synced in this run, which counts as
   * one error, so that such a run is not reported as completed without errors.
   */
  public void folderSkipped(@NonNull String folderPath, @Nullable Throwable error) {
    String path =
        folderPath.isEmpty() ? "/" : (folderPath.endsWith("/") ? folderPath : folderPath + "/");
    String message = SyncErrorCategory.describe(error);
    addError(path, message, SyncErrorCategory.FOLDER_SKIPPED);
    // Counted in the errors, but not as a file: a folder is not a processed file
    long now = System.currentTimeMillis();
    if (runLog != null) {
      runLog.append(now, SyncActionLog.Action.ERROR, path, "folder skipped: " + message);
    }
    if (sharedLog != null) {
      sharedLog.log(
          SyncActionLog.Action.ERROR, configName + " › " + path, "folder skipped: " + message);
    }
  }

  /** The number of errors so far. */
  public int getErrorCount() {
    return errorCount;
  }

  /** How often {@code action} was logged so far. */
  public int getCount(@NonNull SyncActionLog.Action action) {
    Integer n = counts.get(action);
    return n != null ? n : 0;
  }

  /** The number of files looked at so far. */
  public int getProcessedFiles() {
    return processedFiles;
  }

  @NonNull
  public String getConfigName() {
    return configName;
  }

  /** Returns the result of the run, ended now with {@code status}. */
  @NonNull
  public SyncRunResult finish(
      @NonNull SyncRunResult.Status status,
      @Nullable String reason,
      @Nullable SyncErrorCategory failureCategory) {
    return finish(status, reason, failureCategory, System.currentTimeMillis());
  }

  @NonNull
  SyncRunResult finish(
      @NonNull SyncRunResult.Status status,
      @Nullable String reason,
      @Nullable SyncErrorCategory failureCategory,
      long endedAt) {
    return new SyncRunResult(
        configId,
        startedAt,
        endedAt,
        status,
        reason,
        failureCategory,
        counts,
        errorCount,
        errorCategories,
        errors,
        1);
  }

  /**
   * Returns the status of a run that went through: with errors if anything failed, stopped if the
   * worker was stopped.
   */
  @NonNull
  public SyncRunResult.Status completedStatus(boolean stopped) {
    if (stopped) return SyncRunResult.Status.STOPPED;
    return errorCount > 0
        ? SyncRunResult.Status.COMPLETED_WITH_ERRORS
        : SyncRunResult.Status.COMPLETED;
  }

  private void addError(
      @NonNull String path, @NonNull String message, @NonNull SyncErrorCategory category) {
    errorCount++;
    errorCategories.merge(category, 1, Integer::sum);
    if (errors.size() < SyncRunResult.MAX_STORED_ERRORS) {
      errors.add(new SyncRunResult.RunError(path, message, category, 1));
    }
  }

  private void reportProgress(@NonNull String path, long now) {
    if (progressListener == null) return;
    if (now - lastProgressAt < PROGRESS_INTERVAL_MS) return;
    lastProgressAt = now;
    progressListener.onProgress(this, path);
  }

  private static boolean isFileAction(@NonNull SyncActionLog.Action action) {
    return action == SyncActionLog.Action.UPLOADED
        || action == SyncActionLog.Action.DOWNLOADED
        || action == SyncActionLog.Action.SKIPPED
        || action == SyncActionLog.Action.ERROR;
  }
}
