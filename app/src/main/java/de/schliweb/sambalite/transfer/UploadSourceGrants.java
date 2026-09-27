/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.transfer;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.annotation.NonNull;
import de.schliweb.sambalite.transfer.db.PendingTransferDao;
import de.schliweb.sambalite.util.LogUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Manages the persisted SAF grants of transfer endpoints: the read grants of upload sources and the
 * read/write grants of single-file download targets.
 *
 * <p>When the user picks a file or folder for upload, a persistable read grant is taken so that
 * queued uploads survive an app restart. Android limits the number of persisted grants per app, so
 * a grant is released again once no unfinished upload depends on it any more. Grants are released
 * when an upload completes, when uploads are cancelled and when they are removed from the queue.
 *
 * <p>Read/write grants on tree URIs belong to download folders and sync folders and are never
 * touched. The read/write grant on a single-file download target (a document created with {@code
 * ACTION_CREATE_DOCUMENT}) is released once the download is finished, cancelled or removed, so that
 * downloads do not eat into the grant budget of {@link PersistedGrantBudget}. For folder uploads
 * the grant lives on the picked tree URI, which covers all child documents, so it is released only
 * after the last unfinished child upload from that tree is done.
 *
 * <p>All operations run under a process-wide lock, together with the enqueue path in {@code
 * FileOperationsViewModel} which inserts the queue rows and re-takes the grant under the same lock.
 * This closes the window in which a completing upload could otherwise release a grant that was just
 * taken for uploads whose rows are not in the database yet.
 */
public final class UploadSourceGrants {

  private static final String TAG = "UploadSourceGrants";

  private static final int READ = Intent.FLAG_GRANT_READ_URI_PERMISSION;
  private static final int READ_WRITE =
      Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

  private static final Object LOCK = new Object();

  private UploadSourceGrants() {}

  /**
   * The lock that serializes grant release against enqueueing. The enqueue path holds it while it
   * inserts the queue rows and re-takes the grant with {@link #retain(Context, Uri)}.
   */
  @NonNull
  public static Object lock() {
    return LOCK;
  }

  /**
   * Takes a persistable read grant for an upload source, best effort. Providers that do not offer
   * persistable grants make this a no-op; the upload then works as before while the process lives.
   *
   * @param context Any context; the application context is used
   * @param uri The picked file URI or folder tree URI
   */
  public static void retain(@NonNull Context context, @NonNull Uri uri) {
    try {
      context.getApplicationContext().getContentResolver().takePersistableUriPermission(uri, READ);
    } catch (Exception e) {
      LogUtils.d(TAG, "No persistable read grant for " + uri + ": " + e.getMessage());
    }
  }

  /**
   * Takes persistable read grants for several picked files, but only while they fit into the
   * remaining grant budget. Taking more than Android keeps would silently evict older grants,
   * including those of sync folders, and most of the new grants would be lost anyway.
   *
   * @return {@code true} when the grants were taken, {@code false} when the selection exceeded the
   *     budget and the files stay on their temporary grants
   */
  public static boolean retainAll(@NonNull Context context, @NonNull Collection<Uri> uris) {
    // Grants already persisted (by the picker result handler) do not need budget again
    java.util.Set<String> held = new java.util.HashSet<>();
    try {
      for (UriPermission p :
          context.getApplicationContext().getContentResolver().getPersistedUriPermissions()) {
        held.add(p.getUri().toString());
      }
    } catch (Exception ignored) {
      // treat all as new
    }
    int newGrants = 0;
    for (Uri uri : uris) {
      if (!held.contains(uri.toString())) {
        newGrants++;
      }
    }
    if (!PersistedGrantBudget.fits(context, newGrants)) {
      LogUtils.w(
          TAG,
          "Not persisting "
              + newGrants
              + " new upload source grants: only "
              + PersistedGrantBudget.remaining(context)
              + " of Android's "
              + PersistedGrantBudget.ANDROID_MAX_PERSISTED_GRANTS
              + " persisted grants are still free");
      return false;
    }
    for (Uri uri : uris) {
      retain(context, uri);
    }
    return true;
  }

  /**
   * Releases the read-only grants covering the given upload sources, unless another unfinished
   * upload (PENDING, ACTIVE, or FAILED with manual retry possible) still depends on them.
   *
   * @param context Any context; the application context is used
   * @param dao The transfer DAO used to look up unfinished uploads
   * @param uploadUris Local URIs of uploads that no longer need their grant
   */
  public static void releaseIfUnused(
      @NonNull Context context,
      @NonNull PendingTransferDao dao,
      @NonNull Collection<String> uploadUris) {
    if (uploadUris.isEmpty()) {
      return;
    }
    synchronized (LOCK) {
      try {
        ContentResolver resolver = context.getApplicationContext().getContentResolver();
        List<String> unfinished = dao.getUnfinishedUploadLocalUris();

        // Iterate over a snapshot: releasing modifies the underlying grant table
        for (UriPermission perm : new ArrayList<>(resolver.getPersistedUriPermissions())) {
          if (!perm.isReadPermission() || perm.isWritePermission()) {
            continue; // read/write grants are download targets or sync folders
          }
          String grant = perm.getUri().toString();
          if (!coversAny(grant, uploadUris) || coversAny(grant, unfinished)) {
            continue;
          }
          release(resolver, perm.getUri(), READ, "upload source");
        }
      } catch (Exception e) {
        LogUtils.w(TAG, "Could not release upload URI permissions: " + e.getMessage());
      }
    }
  }

  /** Releases the grant of a single completed upload, see {@link #releaseIfUnused}. */
  public static void releaseIfUnused(
      @NonNull Context context, @NonNull PendingTransferDao dao, @NonNull String uploadUri) {
    releaseIfUnused(context, dao, Collections.singletonList(uploadUri));
  }

  /**
   * Releases every persisted read-only grant that no unfinished upload refers to any more.
   * Read-only grants are only ever taken for upload sources, so a grant without an unfinished
   * upload is a leftover: from app versions that never released grants, or from files whose uploads
   * were re-addressed under a folder grant. Called when the worker has drained the queue.
   *
   * @return The number of grants released
   */
  public static int releaseUnreferenced(@NonNull Context context, @NonNull PendingTransferDao dao) {
    int released = 0;
    synchronized (LOCK) {
      try {
        ContentResolver resolver = context.getApplicationContext().getContentResolver();
        List<String> unfinished = dao.getUnfinishedUploadLocalUris();
        for (UriPermission perm : new ArrayList<>(resolver.getPersistedUriPermissions())) {
          if (!perm.isReadPermission() || perm.isWritePermission()) {
            continue;
          }
          if (coversAny(perm.getUri().toString(), unfinished)) {
            continue;
          }
          release(resolver, perm.getUri(), READ, "unreferenced upload source");
          released++;
        }
      } catch (Exception e) {
        LogUtils.w(TAG, "Could not release unreferenced grants: " + e.getMessage());
      }
    }
    return released;
  }

  /**
   * Releases the read/write grants of single-file download targets, unless another unfinished
   * download still writes to the same target. Tree URIs (download folders, sync folders) are never
   * released.
   *
   * @param context Any context; the application context is used
   * @param dao The transfer DAO used to look up unfinished downloads
   * @param targetUris Local target URIs of downloads that no longer need their grant
   */
  public static void releaseDownloadTargetsIfUnused(
      @NonNull Context context,
      @NonNull PendingTransferDao dao,
      @NonNull Collection<String> targetUris) {
    if (targetUris.isEmpty()) {
      return;
    }
    synchronized (LOCK) {
      try {
        ContentResolver resolver = context.getApplicationContext().getContentResolver();
        List<String> unfinished = dao.getUnfinishedDownloadLocalUris();

        for (UriPermission perm : new ArrayList<>(resolver.getPersistedUriPermissions())) {
          if (!perm.isWritePermission() || DocumentsContract.isTreeUri(perm.getUri())) {
            continue;
          }
          String grant = perm.getUri().toString();
          if (!targetUris.contains(grant) || unfinished.contains(grant)) {
            continue;
          }
          release(resolver, perm.getUri(), READ_WRITE, "download target");
        }
      } catch (Exception e) {
        LogUtils.w(TAG, "Could not release download target permissions: " + e.getMessage());
      }
    }
  }

  /**
   * Releases the grant of a single completed download, see {@link #releaseDownloadTargetsIfUnused}.
   */
  public static void releaseDownloadTargetIfUnused(
      @NonNull Context context, @NonNull PendingTransferDao dao, @NonNull String targetUri) {
    releaseDownloadTargetsIfUnused(context, dao, Collections.singletonList(targetUri));
  }

  /**
   * Cancels the given transfers and releases the grants of the cancelled uploads and downloads that
   * no other unfinished transfer still needs.
   */
  public static void cancelAndRelease(
      @NonNull Context context,
      @NonNull PendingTransferDao dao,
      @NonNull List<Long> ids,
      long now) {
    List<String> uploadUris = dao.getUploadLocalUrisByIds(ids);
    List<String> downloadUris = dao.getDownloadLocalUrisByIds(ids);
    dao.cancelByIds(ids, now);
    releaseIfUnused(context, dao, uploadUris);
    releaseDownloadTargetsIfUnused(context, dao, downloadUris);
  }

  /** Cancels all pending and active transfers and releases the grants of the cancelled ones. */
  public static void cancelAllAndRelease(
      @NonNull Context context, @NonNull PendingTransferDao dao, long now) {
    List<String> uploadUris = dao.getUnfinishedUploadLocalUris();
    List<String> downloadUris = dao.getUnfinishedDownloadLocalUris();
    dao.cancelAll(now);
    releaseIfUnused(context, dao, uploadUris);
    releaseDownloadTargetsIfUnused(context, dao, downloadUris);
  }

  /**
   * Deletes the given transfers from the queue and releases the grants of the removed uploads and
   * downloads that no other unfinished transfer still needs.
   */
  public static void deleteAndRelease(
      @NonNull Context context, @NonNull PendingTransferDao dao, @NonNull List<Long> ids) {
    List<String> uploadUris = dao.getUploadLocalUrisByIds(ids);
    List<String> downloadUris = dao.getDownloadLocalUrisByIds(ids);
    dao.deleteByIds(ids);
    releaseIfUnused(context, dao, uploadUris);
    releaseDownloadTargetsIfUnused(context, dao, downloadUris);
  }

  private static void release(ContentResolver resolver, Uri uri, int modeFlags, String what) {
    try {
      resolver.releasePersistableUriPermission(uri, modeFlags);
      LogUtils.i(TAG, "Released persisted " + what + " grant: " + uri);
    } catch (Exception e) {
      LogUtils.w(TAG, "Could not release grant " + uri + ": " + e.getMessage());
    }
  }

  /**
   * Whether the grant covers any of the URIs: either the URI itself (file picker) or a child
   * document of the tree (folder picker, whose children have the form {@code <tree>/document/...}).
   */
  static boolean coversAny(@NonNull String grant, @NonNull Collection<String> uris) {
    String prefix = grant + "/";
    for (String uri : uris) {
      if (uri.equals(grant) || uri.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }
}
