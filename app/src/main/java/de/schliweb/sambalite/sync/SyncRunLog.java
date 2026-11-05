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
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.schliweb.sambalite.util.LogUtils;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The detailed log of the last run of one sync config (#50): one line per file with the action, the
 * path relative to the sync root and the detail (such as the reason a file was skipped).
 *
 * <p>Each config has its own append-only file under {@code filesDir/sync_logs}, which is emptied
 * when a run starts. Writing goes through a buffer, so a run over thousands of files does not
 * rewrite a whole preferences string for every file as the shared {@link SyncActionLog} does. After
 * {@link #MAX_LINES} lines only errors are written, so a huge run cannot fill the disk.
 */
public final class SyncRunLog {

  private static final String TAG = "SyncRunLog";
  private static final String DIR_NAME = "sync_logs";

  /** Lines after which skipped and other routine entries are no longer written. */
  static final int MAX_LINES = 50_000;

  /** One line of the log. */
  public static final class Entry {
    private final long time;
    @NonNull private final SyncActionLog.Action action;
    @NonNull private final String path;
    @NonNull private final String detail;

    Entry(
        long time,
        @NonNull SyncActionLog.Action action,
        @NonNull String path,
        @NonNull String detail) {
      this.time = time;
      this.action = action;
      this.path = path;
      this.detail = detail;
    }

    public long getTime() {
      return time;
    }

    @NonNull
    public SyncActionLog.Action getAction() {
      return action;
    }

    /** The path relative to the sync root. */
    @NonNull
    public String getPath() {
      return path;
    }

    @NonNull
    public String getDetail() {
      return detail;
    }
  }

  private final File file;
  @Nullable private BufferedWriter writer;
  private int lines;
  private boolean truncated;
  private boolean pending;

  public SyncRunLog(@NonNull Context context, @NonNull String configId) {
    this.file = fileFor(context, configId);
  }

  /**
   * Starts a new run. The log of the previous run is replaced when the first file is logged, so a
   * run that fails before it reaches any file (server down, folder missing) keeps the previous log
   * for the "why was file X not synced?" search.
   */
  public synchronized void begin() {
    close();
    pending = true;
    lines = 0;
    truncated = false;
  }

  private void open() {
    pending = false;
    try {
      File dir = file.getParentFile();
      if (dir != null && !dir.exists() && !dir.mkdirs()) {
        LogUtils.w(TAG, "Could not create " + dir);
      }
      writer =
          new BufferedWriter(
              new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8));
    } catch (IOException e) {
      LogUtils.w(TAG, "Could not open the run log: " + e.getMessage());
      writer = null;
    }
  }

  /**
   * Appends one entry; does nothing if no run was begun. Entries without a path (the run as a
   * whole) do not start a new log on their own.
   */
  public synchronized void append(
      long time,
      @NonNull SyncActionLog.Action action,
      @NonNull String path,
      @Nullable String detail) {
    if (pending && !path.isEmpty()) open();
    if (writer == null) return;
    if (lines >= MAX_LINES && action != SyncActionLog.Action.ERROR) {
      truncated = true;
      return;
    }
    try {
      writer.write(Long.toString(time));
      writer.write('\t');
      writer.write(action.name());
      writer.write('\t');
      writer.write(clean(path));
      writer.write('\t');
      writer.write(detail != null ? clean(detail) : "");
      writer.write('\n');
      lines++;
    } catch (IOException e) {
      LogUtils.w(TAG, "Could not write to the run log: " + e.getMessage());
      close();
    }
  }

  /** Returns whether entries were left out because the run had more than {@link #MAX_LINES}. */
  public synchronized boolean isTruncated() {
    return truncated;
  }

  /** Flushes and closes the log. */
  public synchronized void close() {
    pending = false;
    if (writer == null) return;
    try {
      writer.close();
    } catch (IOException e) {
      LogUtils.w(TAG, "Could not close the run log: " + e.getMessage());
    }
    writer = null;
  }

  /** Reads the log of the last run of a config, in the order it was written. */
  @NonNull
  public static List<Entry> read(@NonNull Context context, @NonNull String configId) {
    List<Entry> entries = new ArrayList<>();
    File f = fileFor(context, configId);
    if (!f.isFile()) return entries;
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        Entry entry = parse(line);
        if (entry != null) entries.add(entry);
      }
    } catch (IOException e) {
      LogUtils.w(TAG, "Could not read the run log: " + e.getMessage());
    }
    return entries;
  }

  /** Deletes the log of a config, e.g. when the config is deleted. */
  public static void delete(@NonNull Context context, @NonNull String configId) {
    File f = fileFor(context, configId);
    if (f.exists() && !f.delete()) {
      LogUtils.w(TAG, "Could not delete " + f);
    }
  }

  /** Parses one line of a run log; returns null for a broken line. */
  @Nullable
  public static Entry parse(@NonNull String line) {
    String[] parts = line.split("\t", 4);
    if (parts.length < 3) return null;
    try {
      long time = Long.parseLong(parts[0]);
      SyncActionLog.Action action = SyncActionLog.Action.valueOf(parts[1]);
      return new Entry(time, action, parts[2], parts.length > 3 ? parts[3] : "");
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  @NonNull
  static File fileFor(@NonNull Context context, @NonNull String configId) {
    String safe = configId.replaceAll("[^A-Za-z0-9_-]", "_");
    return new File(new File(context.getFilesDir(), DIR_NAME), safe + ".log");
  }

  @NonNull
  private static String clean(@NonNull String value) {
    return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
  }
}
