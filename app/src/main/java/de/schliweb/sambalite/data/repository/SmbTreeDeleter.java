/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.data.repository;

import static com.hierynomus.mserref.NtStatus.STATUS_DELETE_PENDING;
import static com.hierynomus.mserref.NtStatus.STATUS_DIRECTORY_NOT_EMPTY;
import static com.hierynomus.mserref.NtStatus.STATUS_FILE_IS_A_DIRECTORY;
import static com.hierynomus.mserref.NtStatus.STATUS_OBJECT_NAME_NOT_FOUND;
import static com.hierynomus.mserref.NtStatus.STATUS_OBJECT_PATH_NOT_FOUND;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.smbj.share.DiskEntry;
import com.hierynomus.smbj.share.DiskShare;
import de.schliweb.sambalite.util.LogUtils;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deletes files and folder trees on an SMB share with few, overlapping network round trips.
 *
 * <p>SMBJ's {@code DiskShare.rmdir(path, true)} walks a tree strictly sequentially and spends three
 * round trips per file (CREATE, SET_INFO "delete on close", CLOSE). Over Wi-Fi with a few
 * milliseconds per round trip, a folder of a few hundred audiobook chapters therefore took many
 * seconds. This class
 *
 * <ul>
 *   <li>opens each entry with {@code FILE_DELETE_ON_CLOSE}, so a delete costs CREATE + CLOSE only,
 *   <li>lists the whole tree first and then deletes all files concurrently over the same session
 *       (SMB2 multiplexes requests on one connection), and
 *   <li>removes the folders afterwards from the deepest level upwards, again concurrently per
 *       level.
 * </ul>
 *
 * <p>Unexpected server answers on the fast path fall back to SMBJ's own {@code rm}/{@code rmdir},
 * so error behaviour (read-only files, access denied) stays the same as before.
 */
final class SmbTreeDeleter implements AutoCloseable {

  private static final String TAG = "SmbTreeDeleter";

  /** Number of concurrently outstanding delete/list requests. */
  static final int DEFAULT_PARALLELISM = 8;

  private static final EnumSet<SMB2ShareAccess> SHARE_ALL = EnumSet.allOf(SMB2ShareAccess.class);

  private final DiskShare share;
  private final ExecutorService pool;
  private final AtomicInteger deletedEntries = new AtomicInteger();

  SmbTreeDeleter(@NonNull DiskShare share) {
    this(share, DEFAULT_PARALLELISM);
  }

  SmbTreeDeleter(@NonNull DiskShare share, int parallelism) {
    this.share = share;
    AtomicInteger n = new AtomicInteger();
    this.pool =
        Executors.newFixedThreadPool(
            Math.max(1, parallelism),
            r -> {
              Thread t = new Thread(r, "smb-delete-" + n.incrementAndGet());
              t.setDaemon(true);
              return t;
            });
  }

  /** Number of files and folders removed so far by this instance. */
  int getDeletedEntries() {
    return deletedEntries.get();
  }

  @Override
  public void close() {
    pool.shutdownNow();
  }

  /**
   * Deletes each path (file or folder, recursively) and reports the outcome per path.
   *
   * <p>Top-level entries are first tried as files, all at once. Whatever turns out to be a folder
   * is then deleted as a tree, one after another, each with full internal concurrency.
   *
   * @param paths share-relative paths without leading separator
   * @return one entry per path in input order: {@code null} if deleted, otherwise the error; a
   *     missing path yields a {@link FileNotFoundException}
   */
  @NonNull
  List<Exception> deleteAll(@NonNull List<String> paths) throws InterruptedException {
    List<Future<TopLevel>> probes = new ArrayList<>(paths.size());
    for (String path : paths) {
      probes.add(pool.submit(() -> deleteTopLevelFile(path)));
    }
    List<Exception> results = new ArrayList<>(Collections.nCopies(paths.size(), null));
    for (int i = 0; i < paths.size(); i++) {
      TopLevel outcome;
      try {
        outcome = probes.get(i).get();
      } catch (ExecutionException e) {
        results.set(i, asException(e.getCause()));
        continue;
      }
      if (outcome == TopLevel.DIRECTORY) {
        try {
          deleteTree(paths.get(i));
        } catch (IOException | RuntimeException e) {
          results.set(i, e);
        }
      } else if (outcome == TopLevel.NOT_FOUND) {
        results.set(i, new FileNotFoundException("File or directory not found: " + paths.get(i)));
      }
    }
    return results;
  }

  private enum TopLevel {
    DELETED,
    DIRECTORY,
    NOT_FOUND
  }

  /**
   * Deletes a top-level path if it is a file and verifies that it is gone. SMB servers may keep a
   * file visible for a moment when another client still holds it open (oplocks), so a still
   * existing file gets one more attempt after a short pause, as before.
   */
  private TopLevel deleteTopLevelFile(String path) throws IOException, InterruptedException {
    Entry result = tryDelete(path, false);
    if (result == Entry.IS_DIRECTORY) return TopLevel.DIRECTORY;
    if (result == Entry.NOT_FOUND) {
      // FILE_NON_DIRECTORY_FILE on a missing path; a folder would have answered IS_DIRECTORY
      return TopLevel.NOT_FOUND;
    }
    if (share.fileExists(path)) {
      LogUtils.w(TAG, "File still exists after delete, retrying: " + path);
      Thread.sleep(200);
      if (share.fileExists(path)) {
        share.rm(path);
        if (share.fileExists(path)) {
          throw new IOException("File still exists after delete retry: " + path);
        }
      }
    }
    return TopLevel.DELETED;
  }

  /**
   * Deletes a folder and everything below it.
   *
   * @param root share-relative folder path
   */
  void deleteTree(@NonNull String root) throws IOException, InterruptedException {
    // 1. Walk the tree level by level, listing all folders of a level concurrently
    List<List<String>> levels = new ArrayList<>();
    List<String> files = new ArrayList<>();
    List<String> current = Collections.singletonList(root);
    while (!current.isEmpty()) {
      levels.add(current);
      List<Future<Listing>> listings = new ArrayList<>(current.size());
      for (String dir : current) {
        listings.add(pool.submit(() -> list(dir)));
      }
      List<String> next = new ArrayList<>();
      for (Future<Listing> f : listings) {
        Listing l = await(f);
        files.addAll(l.files);
        next.addAll(l.dirs);
      }
      current = next;
    }

    // 2. Delete all files concurrently; keep going on errors so as much as possible is removed
    IOException firstError = runAll(files, false);
    if (firstError != null) {
      throw firstError;
    }

    // 3. Delete the folders, deepest level first
    for (int i = levels.size() - 1; i >= 0; i--) {
      IOException error = runAll(levels.get(i), true);
      if (error != null) {
        throw error;
      }
    }
  }

  private IOException runAll(List<String> paths, boolean directories) throws InterruptedException {
    List<Future<?>> futures = new ArrayList<>(paths.size());
    for (String p : paths) {
      futures.add(pool.submit(() -> deleteEntry(p, directories)));
    }
    IOException first = null;
    for (Future<?> f : futures) {
      try {
        await(f);
      } catch (IOException e) {
        if (first == null) first = e;
      }
    }
    return first;
  }

  /** Deletes one entry inside a tree that is known to be a file or a folder. */
  private Void deleteEntry(String path, boolean directory) throws IOException {
    try {
      return deleteEntryOrThrow(path, directory);
    } catch (SMBApiException e) {
      throw new IOException("Failed to delete " + path + ": " + e.getMessage(), e);
    }
  }

  private Void deleteEntryOrThrow(String path, boolean directory) throws IOException {
    Entry result = tryDelete(path, directory);
    if (result == Entry.NOT_EMPTY) {
      // Something appeared in the folder meanwhile (another client); fall back to SMBJ's walk
      LogUtils.w(TAG, "Folder not empty after deleting its content, walking again: " + path);
      share.rmdir(path, true);
      deletedEntries.incrementAndGet();
    } else if (result == Entry.IS_DIRECTORY) {
      // Listed as a file but is a folder (changed meanwhile)
      share.rmdir(path, true);
      deletedEntries.incrementAndGet();
    }
    return null;
  }

  private enum Entry {
    DELETED,
    NOT_FOUND,
    IS_DIRECTORY,
    NOT_EMPTY
  }

  /**
   * Opens the entry with {@code FILE_DELETE_ON_CLOSE} and closes it again, which removes it in two
   * round trips. Answers the fast path cannot handle are retried with SMBJ's {@code rm}/{@code
   * rmdir}, which also produce the familiar exceptions.
   */
  private Entry tryDelete(String path, boolean directory) throws IOException {
    EnumSet<SMB2CreateOptions> options =
        EnumSet.of(
            directory
                ? SMB2CreateOptions.FILE_DIRECTORY_FILE
                : SMB2CreateOptions.FILE_NON_DIRECTORY_FILE,
            SMB2CreateOptions.FILE_DELETE_ON_CLOSE);
    EnumSet<FileAttributes> attributes =
        EnumSet.of(
            directory
                ? FileAttributes.FILE_ATTRIBUTE_DIRECTORY
                : FileAttributes.FILE_ATTRIBUTE_NORMAL);
    try (DiskEntry ignored =
        share.open(
            path,
            EnumSet.of(AccessMask.DELETE),
            attributes,
            SHARE_ALL,
            SMB2CreateDisposition.FILE_OPEN,
            options)) {
      deletedEntries.incrementAndGet();
      return Entry.DELETED;
    } catch (SMBApiException e) {
      long status = e.getStatusCode();
      if (status == STATUS_DELETE_PENDING.getValue()) {
        return Entry.DELETED;
      }
      if (status == STATUS_OBJECT_NAME_NOT_FOUND.getValue()
          || status == STATUS_OBJECT_PATH_NOT_FOUND.getValue()) {
        return Entry.NOT_FOUND;
      }
      if (!directory && status == STATUS_FILE_IS_A_DIRECTORY.getValue()) {
        return Entry.IS_DIRECTORY;
      }
      if (directory && status == STATUS_DIRECTORY_NOT_EMPTY.getValue()) {
        return Entry.NOT_EMPTY;
      }
      LogUtils.d(TAG, "Fast delete refused (" + e.getStatus() + "), falling back: " + path);
      try {
        if (directory) {
          share.rmdir(path, false);
        } else {
          share.rm(path);
        }
      } catch (SMBApiException fallback) {
        throw new IOException("Failed to delete " + path + ": " + fallback.getMessage(), fallback);
      }
      deletedEntries.incrementAndGet();
      return Entry.DELETED;
    }
  }

  private static final class Listing {
    final List<String> files = new ArrayList<>();
    final List<String> dirs = new ArrayList<>();
  }

  private Listing list(String dir) throws IOException {
    Listing listing = new Listing();
    List<FileIdBothDirectoryInformation> entries;
    try {
      entries = share.list(dir);
    } catch (SMBApiException e) {
      throw new IOException("Failed to list " + dir + ": " + e.getMessage(), e);
    }
    for (FileIdBothDirectoryInformation info : entries) {
      String name = info.getFileName();
      if (".".equals(name) || "..".equals(name)) continue;
      String child = dir + "\\" + name;
      boolean isDir =
          (info.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
      (isDir ? listing.dirs : listing.files).add(child);
    }
    return listing;
  }

  private static <T> T await(Future<T> future) throws IOException, InterruptedException {
    try {
      return future.get();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException io) throw io;
      if (cause instanceof RuntimeException re) throw re;
      throw new IOException(cause);
    }
  }

  @Nullable
  private static Exception asException(Throwable t) {
    return t instanceof Exception e ? e : new IOException(t);
  }
}
