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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds uploads that collide with a file already on the server or with a transfer already in the
 * queue.
 *
 * <p>The server is asked once per remote directory for its file names instead of once per file. For
 * a folder upload with tens of thousands of files that is the difference between a handful of SMB
 * round trips and tens of thousands.
 */
public final class UploadConflictFinder {

  /** Lists the file names of a remote directory. */
  public interface DirectoryLister {
    /**
     * Returns the entry names of a remote directory.
     *
     * @param remoteDirectory Share-relative directory, empty for the share root
     * @return The names of the entries in that directory, or {@code null} if the directory does not
     *     exist or cannot be listed. Both cases mean no upload can collide there.
     */
    @Nullable
    Set<String> listNames(@NonNull String remoteDirectory);
  }

  /** The result of a conflict search. */
  public static final class Result {
    /** Uploads whose remote path is already taken by a pending or active transfer. */
    @NonNull public final List<UploadRequest> queued;

    /** Uploads whose remote path already exists on the server. */
    @NonNull public final List<UploadRequest> existing;

    Result(@NonNull List<UploadRequest> queued, @NonNull List<UploadRequest> existing) {
      this.queued = queued;
      this.existing = existing;
    }
  }

  /** Reports progress in directories. */
  public interface ProgressListener {
    void onProgress(int directoriesDone, int directoriesTotal);
  }

  private UploadConflictFinder() {}

  /**
   * Finds queued and existing conflicts for the given uploads.
   *
   * @param requests The uploads to check, in order
   * @param activeRemotePaths Remote paths of all pending or active transfers
   * @param lister Lists a remote directory, called once per distinct directory
   * @param progress Optional progress listener
   */
  @NonNull
  public static Result find(
      @NonNull List<UploadRequest> requests,
      @NonNull Collection<String> activeRemotePaths,
      @NonNull DirectoryLister lister,
      @Nullable ProgressListener progress) {
    Set<String> active = new HashSet<>(activeRemotePaths);

    // Group by remote directory, keeping the first-seen order of directories
    Map<String, List<UploadRequest>> byDirectory = new LinkedHashMap<>();
    for (UploadRequest r : requests) {
      List<UploadRequest> list = byDirectory.get(r.remoteDirectory());
      if (list == null) {
        list = new ArrayList<>();
        byDirectory.put(r.remoteDirectory(), list);
      }
      list.add(r);
    }

    Map<UploadRequest, Boolean> existsByRequest = new HashMap<>();
    int done = 0;
    for (Map.Entry<String, List<UploadRequest>> e : byDirectory.entrySet()) {
      Set<String> names = lister.listNames(e.getKey());
      for (UploadRequest r : e.getValue()) {
        existsByRequest.put(r, names != null && names.contains(r.remoteFileName()));
      }
      done++;
      if (progress != null) {
        progress.onProgress(done, byDirectory.size());
      }
    }

    List<UploadRequest> queued = new ArrayList<>();
    List<UploadRequest> existing = new ArrayList<>();
    for (UploadRequest r : requests) {
      if (active.contains(r.remotePath)) {
        queued.add(r);
      }
      if (Boolean.TRUE.equals(existsByRequest.get(r))) {
        existing.add(r);
      }
    }
    return new Result(queued, existing);
  }
}
