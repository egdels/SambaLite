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
import de.schliweb.sambalite.sync.db.FileSyncState;
import de.schliweb.sambalite.sync.db.SyncStateStore;
import de.schliweb.sambalite.util.LogUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Performs the mirror "sweep" phase of a one-way folder sync.
 *
 * <p>After a successful one-way sync (e.g. local→remote or remote→local), this class compares the
 * set of relative paths previously tracked in {@link SyncStateStore} with the set of paths
 * currently visible on the <i>source</i> side. Any tracked path that is no longer present on the
 * source is considered to have been deleted (or renamed) on the source side and is removed on the
 * <i>target</i> side via the supplied {@link TargetDeleter}.
 *
 * <p>The sweep deliberately only deletes entries that the SambaLite sync had previously created
 * itself (i.e. entries with a corresponding row in {@link SyncStateStore}). Files that exist on the
 * target but were never tracked by SambaLite are never touched. This protects manually created
 * files on the target side.
 *
 * <h2>Safeguards</h2>
 *
 * <ul>
 *   <li>If the source listing was incomplete (e.g. due to a network/IO error before the listing
 *       finished), the caller must pass {@code sourceListingComplete = false}; in that case the
 *       sweep is skipped entirely.
 *   <li>If the source listing is empty <em>and</em> the DB previously tracked at least one entry,
 *       the sweep is also skipped: an empty listing is most likely caused by a temporarily
 *       inaccessible share or a misconfigured root, not by the user actually deleting everything.
 *   <li>If the planned number of deletions exceeds a sanity threshold (strictly more than 50% of
 *       the previously tracked entries <em>and</em> strictly more than 100 entries), the sweep is
 *       aborted with no deletions performed and the caller is informed via {@link Result#aborted}.
 * </ul>
 *
 * <p>This class is intentionally free of Android dependencies (no {@code DocumentFile}, no {@code
 * DiskShare}) so that it can be unit-tested directly against a real Samba container.
 */
public class MirrorSweeper {

  private static final String TAG = "MirrorSweeper";

  /** Hard threshold: more than this many planned deletes triggers the percentage check. */
  static final int ABS_DELETE_THRESHOLD = 100;

  /** Hard threshold: more than this fraction of tracked entries to delete also triggers abort. */
  static final double REL_DELETE_THRESHOLD = 0.5d;

  private final SyncStateStore stateStore;

  public MirrorSweeper(@NonNull SyncStateStore stateStore) {
    this.stateStore = stateStore;
  }

  /**
   * Deletes entries on the target side that are tracked in the DB but no longer present on the
   * source.
   *
   * @param rootUri the local sync root URI (key into {@link SyncStateStore})
   * @param sourcePaths set of relative paths (forward-slash separated) currently visible on the
   *     source side
   * @param sourceListingComplete {@code true} iff the caller is sure that {@code sourcePaths} is a
   *     complete enumeration of the source side
   * @param deleter strategy that performs the actual delete on the target side and returns whether
   *     the deletion succeeded
   * @return a result object describing what happened
   */
  @NonNull
  public Result sweep(
      @NonNull String rootUri,
      @NonNull Set<String> sourcePaths,
      boolean sourceListingComplete,
      @NonNull TargetDeleter deleter) {

    List<FileSyncState> tracked = stateStore.getAllForRoot(rootUri);
    if (tracked == null) tracked = Collections.emptyList();

    if (!sourceListingComplete) {
      LogUtils.w(TAG, "[MIRROR] Source listing incomplete – skipping mirror sweep");
      return Result.skipped(tracked.size(), "source listing incomplete");
    }

    if (sourcePaths.isEmpty() && !tracked.isEmpty()) {
      LogUtils.w(
          TAG,
          "[MIRROR] Source listing is empty but DB has "
              + tracked.size()
              + " tracked entries – refusing to wipe target");
      return Result.skipped(tracked.size(), "empty source listing protection");
    }

    // Determine candidates: tracked entries whose relativePath is not in the source listing.
    List<FileSyncState> candidates = new ArrayList<>();
    for (FileSyncState st : tracked) {
      String rel = normalize(st.relativePath);
      if (!sourcePaths.contains(rel)) {
        candidates.add(st);
      }
    }

    if (candidates.isEmpty()) {
      LogUtils.d(TAG, "[MIRROR] Nothing to delete on target");
      return Result.empty(tracked.size());
    }

    // Sanity threshold: abort if too many candidates relative to tracked size.
    if (candidates.size() > ABS_DELETE_THRESHOLD
        && candidates.size() > REL_DELETE_THRESHOLD * tracked.size()) {
      LogUtils.e(
          TAG,
          "[MIRROR] Aborting mirror sweep: "
              + candidates.size()
              + " of "
              + tracked.size()
              + " tracked entries would be deleted (threshold exceeded)");
      return Result.aborted(tracked.size(), candidates.size());
    }

    // Sort by descending path depth so children are deleted before their parents.
    candidates.sort(
        (a, b) -> {
          int da = depth(a.relativePath);
          int db = depth(b.relativePath);
          return Integer.compare(db, da);
        });

    int deleted = 0;
    int failed = 0;
    for (FileSyncState st : candidates) {
      String rel = normalize(st.relativePath);
      try {
        boolean ok = deleter.deleteOnTarget(rel);
        if (ok) {
          stateStore.deleteState(rootUri, st.relativePath);
          deleted++;
          LogUtils.d(TAG, "[MIRROR] Deleted on target: " + rel);
        } else {
          failed++;
          LogUtils.w(TAG, "[MIRROR] Target delete reported failure: " + rel);
        }
      } catch (Exception e) {
        failed++;
        LogUtils.e(TAG, "[MIRROR] Error deleting on target: " + rel + ": " + e.getMessage());
      }
    }

    return Result.completed(tracked.size(), candidates.size(), deleted, failed);
  }

  private static String normalize(@Nullable String relPath) {
    if (relPath == null) return "";
    return relPath.replace('\\', '/');
  }

  private static int depth(@NonNull String relPath) {
    int d = 0;
    for (int i = 0; i < relPath.length(); i++) {
      char c = relPath.charAt(i);
      if (c == '/' || c == '\\') d++;
    }
    return d;
  }

  /**
   * Strategy for deleting an entry on the target side. The implementation is responsible for
   * mapping the relative path to the actual storage layer (SAF {@code DocumentFile}, SMB {@code
   * DiskShare}, plain filesystem, …) and for handling whether the entry is a file or a directory.
   *
   * <p>If the entry no longer exists on the target, implementations should return {@code true}: the
   * desired post-condition (entry gone on target) is already satisfied.
   */
  public interface TargetDeleter {
    /**
     * Deletes the given entry on the target side.
     *
     * @param relativePath relative path with forward-slash separators
     * @return {@code true} if the entry is gone on the target after this call, {@code false}
     *     otherwise
     */
    boolean deleteOnTarget(@NonNull String relativePath) throws Exception;
  }

  /** Result of a single sweep run. */
  public static final class Result {
    public final int tracked;
    public final int candidates;
    public final int deleted;
    public final int failed;
    public final boolean aborted;
    public final boolean skipped;
    @Nullable public final String reason;

    private Result(
        int tracked,
        int candidates,
        int deleted,
        int failed,
        boolean aborted,
        boolean skipped,
        @Nullable String reason) {
      this.tracked = tracked;
      this.candidates = candidates;
      this.deleted = deleted;
      this.failed = failed;
      this.aborted = aborted;
      this.skipped = skipped;
      this.reason = reason;
    }

    static Result skipped(int tracked, @NonNull String reason) {
      return new Result(tracked, 0, 0, 0, false, true, reason);
    }

    static Result empty(int tracked) {
      return new Result(tracked, 0, 0, 0, false, false, null);
    }

    static Result aborted(int tracked, int candidates) {
      return new Result(tracked, candidates, 0, 0, true, false, "delete threshold exceeded");
    }

    static Result completed(int tracked, int candidates, int deleted, int failed) {
      return new Result(tracked, candidates, deleted, failed, false, false, null);
    }
  }
}
