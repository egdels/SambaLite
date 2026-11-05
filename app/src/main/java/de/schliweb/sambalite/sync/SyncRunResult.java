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
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The outcome of one sync run of one config (#50): when it ran, how it ended, how many files it
 * uploaded, downloaded, skipped and so on, and the first errors with their path relative to the
 * sync root. Unlike the shared action log, a result is kept per config and also for runs that
 * failed or were stopped.
 *
 * <p>A config that was due but not started because it may only sync over WiFi is recorded as {@link
 * Status#SKIPPED_NO_WIFI}; consecutive skips are merged into one result (see {@link
 * #getOccurrences()}).
 */
public final class SyncRunResult {

  /** How a run ended. */
  public enum Status {
    /** All files were processed without errors. */
    COMPLETED,
    /** The run went through, but some files or folders failed. */
    COMPLETED_WITH_ERRORS,
    /** Stopped by the user or the system before it was done. */
    STOPPED,
    /** The run could not be done, see {@link #getFailureCategory()} and {@link #getReason()}. */
    FAILED,
    /** Aborted, or not started, because the device is low on space. */
    ABORTED_LOW_SPACE,
    /** Not started: the sync is restricted to WiFi and the device was not on WiFi. */
    SKIPPED_NO_WIFI;

    /** Returns whether a run with this status needs the user's attention. */
    public boolean isProblem() {
      return this == COMPLETED_WITH_ERRORS || this == FAILED || this == ABORTED_LOW_SPACE;
    }

    /** Returns whether the sync actually ran (as opposed to being skipped). */
    public boolean isRun() {
      return this != SKIPPED_NO_WIFI;
    }
  }

  /** One error of a run. */
  public static final class RunError {
    @NonNull private final String path;
    @NonNull private final String message;
    @NonNull private final SyncErrorCategory category;
    private int streak;

    public RunError(
        @NonNull String path,
        @NonNull String message,
        @NonNull SyncErrorCategory category,
        int streak) {
      this.path = path;
      this.message = message;
      this.category = category;
      this.streak = Math.max(1, streak);
    }

    /** The path relative to the sync root (a folder path ends with {@code /}). */
    @NonNull
    public String getPath() {
      return path;
    }

    @NonNull
    public String getMessage() {
      return message;
    }

    @NonNull
    public SyncErrorCategory getCategory() {
      return category;
    }

    /** In how many runs in a row this path failed, this one included. */
    public int getStreak() {
      return streak;
    }

    void setStreak(int streak) {
      this.streak = Math.max(1, streak);
    }

    JSONObject toJson() throws JSONException {
      JSONObject json = new JSONObject();
      json.put("path", path);
      json.put("message", message);
      json.put("category", category.name());
      json.put("streak", streak);
      return json;
    }

    static RunError fromJson(JSONObject json) {
      return new RunError(
          json.optString("path", ""),
          json.optString("message", ""),
          parseCategory(json.optString("category", null)),
          json.optInt("streak", 1));
    }
  }

  /** The number of errors whose details are kept per run. */
  public static final int MAX_STORED_ERRORS = 50;

  @NonNull private final String configId;
  private final long startedAt;
  private long endedAt;
  @NonNull private final Status status;
  @Nullable private final String reason;
  @Nullable private final SyncErrorCategory failureCategory;
  @NonNull private final EnumMap<SyncActionLog.Action, Integer> counts;
  private final int errorCount;
  @NonNull private final EnumMap<SyncErrorCategory, Integer> errorCategories;
  @NonNull private final List<RunError> errors;
  private int occurrences;

  SyncRunResult(
      @NonNull String configId,
      long startedAt,
      long endedAt,
      @NonNull Status status,
      @Nullable String reason,
      @Nullable SyncErrorCategory failureCategory,
      @NonNull Map<SyncActionLog.Action, Integer> counts,
      int errorCount,
      @NonNull Map<SyncErrorCategory, Integer> errorCategories,
      @NonNull List<RunError> errors,
      int occurrences) {
    this.configId = configId;
    this.startedAt = startedAt;
    this.endedAt = endedAt;
    this.status = status;
    this.reason = reason;
    this.failureCategory = failureCategory;
    this.counts = new EnumMap<>(SyncActionLog.Action.class);
    this.counts.putAll(counts);
    this.errorCount = errorCount;
    this.errorCategories = new EnumMap<>(SyncErrorCategory.class);
    this.errorCategories.putAll(errorCategories);
    this.errors = new ArrayList<>(errors);
    this.occurrences = Math.max(1, occurrences);
  }

  /** Returns a result for a run that did not start, such as a WiFi skip or a low-space abort. */
  @NonNull
  public static SyncRunResult notStarted(
      @NonNull String configId,
      @NonNull Status status,
      @Nullable String reason,
      @Nullable SyncErrorCategory failureCategory,
      long at) {
    return new SyncRunResult(
        configId,
        at,
        at,
        status,
        reason,
        failureCategory,
        Collections.emptyMap(),
        0,
        Collections.emptyMap(),
        Collections.emptyList(),
        1);
  }

  @NonNull
  public String getConfigId() {
    return configId;
  }

  public long getStartedAt() {
    return startedAt;
  }

  public long getEndedAt() {
    return endedAt;
  }

  @NonNull
  public Status getStatus() {
    return status;
  }

  /** A technical detail on why the run failed or was aborted; may be null. */
  @Nullable
  public String getReason() {
    return reason;
  }

  /** For {@link Status#FAILED}, the cause of the failure; otherwise null. */
  @Nullable
  public SyncErrorCategory getFailureCategory() {
    return failureCategory;
  }

  /** Returns how often {@code action} was logged in this run. */
  public int getCount(@NonNull SyncActionLog.Action action) {
    Integer value = counts.get(action);
    return value != null ? value : 0;
  }

  /** The number of errors of this run (files and skipped folders), stored or not. */
  public int getErrorCount() {
    return errorCount;
  }

  /** The number of errors per category, over all errors of the run. */
  @NonNull
  public Map<SyncErrorCategory, Integer> getErrorCategories() {
    return Collections.unmodifiableMap(errorCategories);
  }

  /** The first {@link #MAX_STORED_ERRORS} errors, in the order they happened. */
  @NonNull
  public List<RunError> getErrors() {
    return Collections.unmodifiableList(errors);
  }

  /** The number of files the run looked at: uploaded, downloaded, skipped or failed. */
  public int getProcessedFiles() {
    return getCount(SyncActionLog.Action.UPLOADED)
        + getCount(SyncActionLog.Action.DOWNLOADED)
        + getCount(SyncActionLog.Action.SKIPPED)
        + getCount(SyncActionLog.Action.ERROR);
  }

  /** For a merged WiFi skip, how many due runs were skipped in a row; otherwise 1. */
  public int getOccurrences() {
    return occurrences;
  }

  void mergeSkip(long at) {
    endedAt = Math.max(endedAt, at);
    occurrences++;
  }

  @NonNull
  JSONObject toJson() throws JSONException {
    JSONObject json = new JSONObject();
    json.put("configId", configId);
    json.put("startedAt", startedAt);
    json.put("endedAt", endedAt);
    json.put("status", status.name());
    if (reason != null) json.put("reason", reason);
    if (failureCategory != null) json.put("failureCategory", failureCategory.name());
    JSONObject countsJson = new JSONObject();
    for (Map.Entry<SyncActionLog.Action, Integer> e : counts.entrySet()) {
      if (e.getValue() != null && e.getValue() > 0) {
        countsJson.put(e.getKey().name(), e.getValue());
      }
    }
    json.put("counts", countsJson);
    json.put("errorCount", errorCount);
    JSONObject categoriesJson = new JSONObject();
    for (Map.Entry<SyncErrorCategory, Integer> e : errorCategories.entrySet()) {
      categoriesJson.put(e.getKey().name(), e.getValue());
    }
    json.put("errorCategories", categoriesJson);
    JSONArray errorsJson = new JSONArray();
    for (RunError error : errors) {
      errorsJson.put(error.toJson());
    }
    json.put("errors", errorsJson);
    if (occurrences > 1) json.put("occurrences", occurrences);
    return json;
  }

  /** Reads a result written by {@link #toJson()}; unknown enum names are skipped. */
  @NonNull
  static SyncRunResult fromJson(@NonNull JSONObject json) {
    Status status;
    try {
      status = Status.valueOf(json.optString("status", Status.FAILED.name()));
    } catch (IllegalArgumentException e) {
      status = Status.FAILED;
    }
    EnumMap<SyncActionLog.Action, Integer> counts = new EnumMap<>(SyncActionLog.Action.class);
    JSONObject countsJson = json.optJSONObject("counts");
    if (countsJson != null) {
      for (SyncActionLog.Action action : SyncActionLog.Action.values()) {
        int value = countsJson.optInt(action.name(), 0);
        if (value > 0) counts.put(action, value);
      }
    }
    EnumMap<SyncErrorCategory, Integer> categories = new EnumMap<>(SyncErrorCategory.class);
    JSONObject categoriesJson = json.optJSONObject("errorCategories");
    if (categoriesJson != null) {
      for (SyncErrorCategory category : SyncErrorCategory.values()) {
        int value = categoriesJson.optInt(category.name(), 0);
        if (value > 0) categories.put(category, value);
      }
    }
    List<RunError> errors = new ArrayList<>();
    JSONArray errorsJson = json.optJSONArray("errors");
    if (errorsJson != null) {
      for (int i = 0; i < errorsJson.length(); i++) {
        JSONObject e = errorsJson.optJSONObject(i);
        if (e != null) errors.add(RunError.fromJson(e));
      }
    }
    String failure = json.optString("failureCategory", null);
    return new SyncRunResult(
        json.optString("configId", ""),
        json.optLong("startedAt", 0),
        json.optLong("endedAt", 0),
        status,
        json.has("reason") ? json.optString("reason") : null,
        failure != null ? parseCategory(failure) : null,
        counts,
        json.optInt("errorCount", 0),
        categories,
        errors,
        json.optInt("occurrences", 1));
  }

  @NonNull
  private static SyncErrorCategory parseCategory(@Nullable String name) {
    if (name == null) return SyncErrorCategory.OTHER;
    try {
      return SyncErrorCategory.valueOf(name);
    } catch (IllegalArgumentException e) {
      return SyncErrorCategory.OTHER;
    }
  }
}
