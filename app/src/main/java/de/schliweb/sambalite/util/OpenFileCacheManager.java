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
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Manages the open-file cache directory ({@code getCacheDir()/open_files/}).
 *
 * <p>Each remote file gets its own subfolder named after a hash of its connection and path, with
 * the file inside under its own name ({@code open_files/<hash>/report.pdf}): viewers show the real
 * name, and two files with the same name in different folders or on different connections never
 * share a cache entry.
 *
 * <p>Strategy: Lazy cleanup on app start (files older than 1 hour) combined with a size limit (100
 * MB, evicts oldest first down to 50 MB).
 */
public final class OpenFileCacheManager {

  private static final String TAG = "OpenFileCacheManager";
  private static final String CACHE_DIR_NAME = "open_files";
  private static final long MAX_AGE_MS = 60 * 60 * 1000; // 1 hour
  private static final long MAX_CACHE_SIZE_BYTES = 100 * 1024 * 1024; // 100 MB
  private static final long TARGET_CACHE_SIZE_BYTES = 50 * 1024 * 1024; // 50 MB

  private OpenFileCacheManager() {}

  /**
   * Deletes all cache files older than 1 hour. Should be called from {@code
   * SambaLiteApp.onCreate()}.
   */
  public static void cleanupOnAppStart(@NonNull Context context) {
    File cacheDir = new File(context.getCacheDir(), CACHE_DIR_NAME);
    if (!cacheDir.exists()) return;

    long now = System.currentTimeMillis();
    int deleted = 0;
    for (File file : listCacheFiles(cacheDir)) {
      if (now - file.lastModified() > MAX_AGE_MS) {
        if (file.delete()) {
          deleted++;
        }
      }
    }
    deleteEmptySubfolders(cacheDir);
    if (deleted > 0) {
      LogUtils.d(TAG, "Cleaned up " + deleted + " expired cache file(s)");
    }
  }

  /**
   * Ensures the cache does not exceed 100 MB. Deletes oldest files first until under 50 MB. Should
   * be called before each new download into the cache.
   */
  public static void enforceMaxSize(@NonNull Context context) {
    enforceMaxSize(context, null);
  }

  /**
   * Ensures the cache does not exceed 100 MB. Deletes oldest files first until under 50 MB, but
   * never deletes the specified {@code excludeFile} (e.g. a file that is about to be opened).
   */
  public static void enforceMaxSize(@NonNull Context context, @Nullable File excludeFile) {
    File cacheDir = new File(context.getCacheDir(), CACHE_DIR_NAME);
    if (!cacheDir.exists()) return;

    List<File> files = listCacheFiles(cacheDir);
    long totalSize = 0;
    for (File f : files) totalSize += f.length();

    if (totalSize <= MAX_CACHE_SIZE_BYTES) return;

    // Sort by last modified, oldest first
    files.sort(Comparator.comparingLong(File::lastModified));
    int deleted = 0;
    for (File f : files) {
      if (totalSize <= TARGET_CACHE_SIZE_BYTES) break;
      if (excludeFile != null && f.getAbsolutePath().equals(excludeFile.getAbsolutePath())) {
        continue;
      }
      totalSize -= f.length();
      if (f.delete()) {
        deleted++;
      }
    }
    deleteEmptySubfolders(cacheDir);
    if (deleted > 0) {
      LogUtils.d(TAG, "Evicted " + deleted + " cache file(s) to enforce size limit");
    }
  }

  /** Returns the cache directory for open files, creating it if necessary. */
  @NonNull
  public static File getCacheDir(@NonNull Context context) {
    File cacheDir = new File(context.getCacheDir(), CACHE_DIR_NAME);
    if (!cacheDir.exists()) {
      cacheDir.mkdirs();
    }
    return cacheDir;
  }

  /**
   * Returns the cache file for a remote file, in a subfolder that is unique for the connection and
   * the remote path. The subfolder is created if necessary.
   *
   * @param context the context
   * @param connectionKey identifies the connection, e.g. its id
   * @param remotePath the path of the file on the share
   * @param fileName the name of the file, used as the name of the cache file
   * @return the cache file, which may not exist yet
   */
  @NonNull
  public static File getCacheFile(
      @NonNull Context context,
      @Nullable String connectionKey,
      @NonNull String remotePath,
      @NonNull String fileName) {
    File folder = new File(getCacheDir(context), entryFolderName(connectionKey, remotePath));
    if (!folder.exists()) {
      folder.mkdirs();
    }
    return new File(folder, fileName);
  }

  /**
   * Name of the subfolder for one remote file: the first 16 hex digits of the SHA-256 of connection
   * and path.
   */
  @NonNull
  static String entryFolderName(@Nullable String connectionKey, @NonNull String remotePath) {
    String key = connectionKey + "\n" + remotePath;
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (int i = 0; i < 8; i++) {
        hex.append(String.format(java.util.Locale.ROOT, "%02x", hash[i]));
      }
      return hex.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      return Integer.toHexString(key.hashCode());
    }
  }

  /**
   * Lists all cached files: those in the per-file subfolders and, from older versions, those
   * directly in the cache folder.
   *
   * @param context the context
   * @return the cached files, empty if there are none
   */
  @NonNull
  public static List<File> listCacheFiles(@NonNull Context context) {
    return listCacheFiles(new File(context.getCacheDir(), CACHE_DIR_NAME));
  }

  @NonNull
  private static List<File> listCacheFiles(@NonNull File cacheDir) {
    List<File> result = new ArrayList<>();
    File[] entries = cacheDir.listFiles();
    if (entries == null) return result;
    for (File entry : entries) {
      if (entry.isDirectory()) {
        File[] inner = entry.listFiles();
        if (inner != null) {
          for (File f : inner) {
            if (f.isFile()) result.add(f);
          }
        }
      } else {
        result.add(entry);
      }
    }
    return result;
  }

  /** Removes per-file subfolders whose file has been deleted. */
  private static void deleteEmptySubfolders(@NonNull File cacheDir) {
    File[] entries = cacheDir.listFiles();
    if (entries == null) return;
    for (File entry : entries) {
      String[] inner = entry.isDirectory() ? entry.list() : null;
      if (inner != null && inner.length == 0) {
        entry.delete();
      }
    }
  }
}
