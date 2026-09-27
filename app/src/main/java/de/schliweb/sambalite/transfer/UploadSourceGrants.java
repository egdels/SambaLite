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
import androidx.annotation.NonNull;
import de.schliweb.sambalite.transfer.db.PendingTransferDao;
import de.schliweb.sambalite.util.LogUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Manages the persisted SAF read grants of upload sources.
 *
 * <p>When the user picks a file or folder for upload, a persistable read grant is taken so that
 * queued uploads survive an app restart. Android limits the number of persisted grants per app, so
 * a grant is released again once no unfinished upload depends on it any more. Grants are released
 * when an upload completes, when uploads are cancelled and when they are removed from the queue.
 *
 * <p>Only read-only grants are ever released. Read/write grants belong to download targets and sync
 * folders and are never touched. For folder uploads the grant lives on the picked tree URI, which
 * covers all child documents, so it is released only after the last unfinished child upload from
 * that tree is done.
 *
 * <p>All operations run under a process-wide lock, together with the enqueue path in {@code
 * FileOperationsViewModel} which inserts the queue rows and re-takes the grant under the same lock.
 * This closes the window in which a completing upload could otherwise release a grant that was just
 * taken for uploads whose rows are not in the database yet.
 */
public final class UploadSourceGrants {

  private static final String TAG = "UploadSourceGrants";

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
      context
          .getApplicationContext()
          .getContentResolver()
          .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    } catch (Exception e) {
      LogUtils.d(TAG, "No persistable read grant for " + uri + ": " + e.getMessage());
    }
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
          try {
            resolver.releasePersistableUriPermission(
                perm.getUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            LogUtils.i(TAG, "Released persisted upload source grant: " + grant);
          } catch (Exception e) {
            LogUtils.w(TAG, "Could not release grant " + grant + ": " + e.getMessage());
          }
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
   * Cancels the given transfers and releases the grants of the cancelled uploads that no other
   * unfinished upload still needs.
   */
  public static void cancelAndRelease(
      @NonNull Context context,
      @NonNull PendingTransferDao dao,
      @NonNull List<Long> ids,
      long now) {
    List<String> uploadUris = dao.getUploadLocalUrisByIds(ids);
    dao.cancelByIds(ids, now);
    releaseIfUnused(context, dao, uploadUris);
  }

  /** Cancels all pending and active transfers and releases the grants of the cancelled uploads. */
  public static void cancelAllAndRelease(
      @NonNull Context context, @NonNull PendingTransferDao dao, long now) {
    List<String> uploadUris = dao.getUnfinishedUploadLocalUris();
    dao.cancelAll(now);
    releaseIfUnused(context, dao, uploadUris);
  }

  /**
   * Deletes the given transfers from the queue and releases the grants of the removed uploads that
   * no other unfinished upload still needs.
   */
  public static void deleteAndRelease(
      @NonNull Context context, @NonNull PendingTransferDao dao, @NonNull List<Long> ids) {
    List<String> uploadUris = dao.getUploadLocalUrisByIds(ids);
    dao.deleteByIds(ids);
    releaseIfUnused(context, dao, uploadUris);
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
