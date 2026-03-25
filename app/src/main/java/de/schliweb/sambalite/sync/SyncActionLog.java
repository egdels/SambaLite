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

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Persistent action log for folder sync operations. Stores the most recent sync actions in
 * SharedPreferences (FIFO, max 100 entries).
 */
public class SyncActionLog {

  private static final String PREFS_NAME = "sync_action_log";
  private static final String KEY_ENTRIES = "log_entries";
  private static final String KEY_ENTRY_COUNT = "entry_count";
  private static final int MAX_ENTRIES = 100;
  private static final String ENTRY_SEPARATOR = "\n";

  public enum Action {
    UPLOADED("↑ Uploaded"),
    DOWNLOADED("↓ Downloaded"),
    SKIPPED("⊘ Skipped"),
    CREATED_DIR("📁 Created dir"),
    ERROR("✗ Error");

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

  public SyncActionLog(@NonNull Context context) {
    this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    this.dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
  }

  /** Logs a sync action. */
  public void log(@NonNull Action action, @NonNull String fileName) {
    log(action, fileName, null);
  }

  /** Logs a sync action with an optional detail message (e.g. error reason). */
  public void log(@NonNull Action action, @NonNull String fileName, @NonNull String detail) {
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

  /** Returns a formatted summary string for display in the System Monitor. */
  public @NonNull String getFormattedLog(int maxEntries) {
    List<String> recent = getRecentEntries(maxEntries);
    int total = getEntries().size();

    if (recent.isEmpty()) {
      return "=== Sync Activity Log ===\nNo sync actions recorded yet.\n";
    }

    StringBuilder sb = new StringBuilder();
    sb.append("=== Sync Activity Log ===\n");
    sb.append("Showing ")
        .append(recent.size())
        .append(" of ")
        .append(total)
        .append(" entries (newest first)\n\n");

    for (String entry : recent) {
      sb.append(entry).append("\n");
    }

    // Summary counts
    int uploaded = 0, downloaded = 0, skipped = 0, errors = 0, dirs = 0;
    for (String entry : getEntries()) {
      if (entry.contains(Action.UPLOADED.getSymbol())) uploaded++;
      else if (entry.contains(Action.DOWNLOADED.getSymbol())) downloaded++;
      else if (entry.contains(Action.SKIPPED.getSymbol())) skipped++;
      else if (entry.contains(Action.ERROR.getSymbol())) errors++;
      else if (entry.contains(Action.CREATED_DIR.getSymbol())) dirs++;
    }

    sb.append("\nTotal: ")
        .append(uploaded)
        .append(" uploaded, ")
        .append(downloaded)
        .append(" downloaded, ")
        .append(skipped)
        .append(" skipped, ")
        .append(dirs)
        .append(" dirs created, ")
        .append(errors)
        .append(" errors\n");

    return sb.toString();
  }

  /** Clears all log entries. */
  public void clear() {
    prefs.edit().clear().apply();
  }
}
