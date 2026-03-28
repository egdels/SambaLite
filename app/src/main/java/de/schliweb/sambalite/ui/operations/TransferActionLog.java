/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui.operations;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Persistent action log for file transfer operations (upload/download). Stores the most recent
 * transfer actions in SharedPreferences (FIFO, max 100 entries).
 */
public class TransferActionLog {

  private static final String PREFS_NAME = "transfer_action_log";
  private static final String KEY_ENTRIES = "log_entries";
  private static final String KEY_ENTRY_COUNT = "entry_count";
  private static final int MAX_ENTRIES = 100;
  private static final String ENTRY_SEPARATOR = "\n";

  public enum Action {
    DOWNLOAD_STARTED("↓ Download started"),
    DOWNLOAD_COMPLETED("✓ Downloaded"),
    DOWNLOAD_FAILED("✗ Download failed"),
    UPLOAD_STARTED("↑ Upload started"),
    UPLOAD_COMPLETED("✓ Uploaded"),
    UPLOAD_FAILED("✗ Upload failed"),
    CACHE_HIT("⊙ Cache hit"),
    CACHE_MISS("⊘ Cache miss"),
    RETRY("↻ Retry"),
    TIMESTAMP_SET("🕐 Timestamp set"),
    TIMESTAMP_FAILED("⚠ Timestamp failed");

    private final String symbol;

    Action(String symbol) {
      this.symbol = symbol;
    }

    public @NonNull String getSymbol() {
      return symbol;
    }
  }

  private final SharedPreferences prefs;
  private final SimpleDateFormat dateFormat;

  public TransferActionLog(@NonNull Context context) {
    this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    this.dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
  }

  /** Logs a transfer action. */
  public void log(@NonNull Action action, @NonNull String fileName) {
    log(action, fileName, null);
  }

  /** Logs a transfer action with an optional detail message (e.g. error reason). */
  public void log(@NonNull Action action, @NonNull String fileName, @Nullable String detail) {
    String timestamp = dateFormat.format(new Date());
    StringBuilder entry = new StringBuilder();
    entry.append("[").append(timestamp).append("] ");
    entry.append(action.getSymbol()).append(": ").append(fileName);
    if (detail != null && !detail.isEmpty()) {
      entry.append(" - ").append(detail);
    }

    addEntry(entry.toString());
  }

  private synchronized void addEntry(String entry) {
    List<String> entries = getEntries();
    entries.add(entry);

    // Trim to max size (FIFO)
    while (entries.size() > MAX_ENTRIES) {
      entries.remove(0);
    }

    String joined = String.join(ENTRY_SEPARATOR, entries);
    prefs.edit().putString(KEY_ENTRIES, joined).putInt(KEY_ENTRY_COUNT, entries.size()).apply();
  }

  /** Returns all log entries, oldest first. */
  public @NonNull List<String> getEntries() {
    String joined = prefs.getString(KEY_ENTRIES, "");
    List<String> entries = new ArrayList<>();
    if (!joined.isEmpty()) {
      String[] parts = joined.split(ENTRY_SEPARATOR);
      for (String part : parts) {
        if (!part.isEmpty()) {
          entries.add(part);
        }
      }
    }
    return entries;
  }

  /** Returns the most recent N log entries, newest first. */
  public @NonNull List<String> getRecentEntries(int count) {
    List<String> all = getEntries();
    List<String> recent = new ArrayList<>();
    int start = Math.max(0, all.size() - count);
    for (int i = all.size() - 1; i >= start; i--) {
      recent.add(all.get(i));
    }
    return recent;
  }

  /** Returns a formatted summary string for display. */
  public @NonNull String getFormattedLog(int maxEntries) {
    List<String> recent = getRecentEntries(maxEntries);
    int total = getEntries().size();

    if (recent.isEmpty()) {
      return "=== Transfer Activity Log ===\nNo transfer actions recorded yet.\n";
    }

    StringBuilder sb = new StringBuilder();
    sb.append("=== Transfer Activity Log ===\n");
    sb.append("Showing ")
        .append(recent.size())
        .append(" of ")
        .append(total)
        .append(" entries (newest first)\n\n");

    for (String entry : recent) {
      sb.append(entry).append("\n");
    }

    // Summary counts
    int uploaded = 0, downloaded = 0, uploadFailed = 0, downloadFailed = 0, cacheHits = 0;
    int timestampsSet = 0, timestampsFailed = 0;
    for (String entry : getEntries()) {
      if (entry.contains(Action.UPLOAD_COMPLETED.getSymbol())) uploaded++;
      else if (entry.contains(Action.DOWNLOAD_COMPLETED.getSymbol())) downloaded++;
      else if (entry.contains(Action.UPLOAD_FAILED.getSymbol())) uploadFailed++;
      else if (entry.contains(Action.DOWNLOAD_FAILED.getSymbol())) downloadFailed++;
      else if (entry.contains(Action.CACHE_HIT.getSymbol())) cacheHits++;
      else if (entry.contains(Action.TIMESTAMP_SET.getSymbol())) timestampsSet++;
      else if (entry.contains(Action.TIMESTAMP_FAILED.getSymbol())) timestampsFailed++;
    }

    sb.append("\nTotal: ")
        .append(uploaded)
        .append(" uploaded, ")
        .append(downloaded)
        .append(" downloaded, ")
        .append(cacheHits)
        .append(" cache hits, ")
        .append(uploadFailed + downloadFailed)
        .append(" errors, ")
        .append(timestampsSet)
        .append(" timestamps set, ")
        .append(timestampsFailed)
        .append(" timestamps failed\n");

    return sb.toString();
  }

  /** Clears all log entries. */
  public void clear() {
    prefs.edit().clear().apply();
  }
}
