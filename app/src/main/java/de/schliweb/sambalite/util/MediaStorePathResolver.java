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
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.File;

/**
 * Resolves {@code content://} URIs to real filesystem paths ({@code java.io.File}) where possible.
 *
 * <p>On Android 10+ files owned by the app (e.g. created via MediaStore or SAF in standard media
 * directories) are accessible through the FUSE-backed path ({@code /storage/emulated/0/...}). On
 * such paths {@code File.setLastModified()} works reliably, unlike SAF document URIs which offer no
 * API for setting timestamps. This resolver is the foundation for the MediaStore-based timestamp
 * preservation on downloads.
 *
 * <p>Supported URI types:
 *
 * <ul>
 *   <li>{@code file://} URIs → direct path
 *   <li>MediaStore URIs ({@code content://media/...}) → resolved via the {@code DATA} column
 *   <li>External storage documents ({@code content://com.android.externalstorage.documents/...}) →
 *       resolved by parsing the document ID (e.g. {@code primary:Download/file.txt})
 * </ul>
 */
public class MediaStorePathResolver {

  private static final String TAG = "MediaStorePathResolver";

  /** Authority of the external storage documents provider used by the SAF picker. */
  static final String EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents";

  /** Private constructor to prevent instantiation. */
  private MediaStorePathResolver() {
    // Utility class
  }

  /**
   * Attempts to resolve the given URI to a real filesystem path.
   *
   * @param context the application context for content resolver access
   * @param uri the URI to resolve
   * @return the resolved {@link File}, or {@code null} if the URI cannot be resolved
   */
  @Nullable
  public static File resolveToFile(@NonNull Context context, @NonNull Uri uri) {
    String scheme = uri.getScheme();

    if ("file".equals(scheme)) {
      String path = uri.getPath();
      return (path != null) ? new File(path) : null;
    }

    if (!"content".equals(scheme)) {
      return null;
    }

    String authority = uri.getAuthority();

    if (MediaStore.AUTHORITY.equals(authority)) {
      return queryDataColumn(context, uri);
    }

    if (EXTERNAL_STORAGE_AUTHORITY.equals(authority)) {
      try {
        String docId = DocumentsContract.getDocumentId(uri);
        return resolveExternalStorageDocId(docId, Environment.getExternalStorageDirectory());
      } catch (Exception e) {
        LogUtils.d(
            TAG,
            "[TIMESTAMP] Cannot extract document id from URI: "
                + uri
                + " - "
                + e.getClass().getSimpleName());
        return null;
      }
    }

    return null;
  }

  /**
   * Resolves an external storage document ID (e.g. {@code primary:Download/file.txt}) to a real
   * filesystem path. Package-visible and free of Android framework calls for testability.
   *
   * @param docId the document ID from {@code DocumentsContract.getDocumentId()}
   * @param primaryRoot the root of the primary external storage volume
   * @return the resolved {@link File}, or {@code null} if the document ID is invalid
   */
  @Nullable
  public static File resolveExternalStorageDocId(@Nullable String docId, @NonNull File primaryRoot) {
    if (docId == null) {
      return null;
    }
    int colon = docId.indexOf(':');
    if (colon < 0) {
      return null;
    }
    String volume = docId.substring(0, colon);
    String relativePath = docId.substring(colon + 1);
    if (volume.isEmpty() || relativePath.isEmpty() || relativePath.contains("..")) {
      return null;
    }
    if ("primary".equalsIgnoreCase(volume)) {
      return new File(primaryRoot, relativePath);
    }
    // Secondary volumes (e.g. SD cards) are mounted under /storage/<volume-id>/
    return new File("/storage/" + volume, relativePath);
  }

  /**
   * Queries the MediaStore {@code DATA} column to obtain the real filesystem path of a MediaStore
   * URI.
   *
   * @param context the application context for content resolver access
   * @param uri the MediaStore URI
   * @return the resolved {@link File}, or {@code null} if the query fails or yields no path
   */
  @Nullable
  private static File queryDataColumn(@NonNull Context context, @NonNull Uri uri) {
    String[] projection = {MediaStore.MediaColumns.DATA};
    try (Cursor cursor =
        context.getContentResolver().query(uri, projection, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        int columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
        if (columnIndex >= 0) {
          String path = cursor.getString(columnIndex);
          if (path != null && !path.isEmpty()) {
            return new File(path);
          }
        }
      }
    } catch (Exception e) {
      LogUtils.d(
          TAG,
          "[TIMESTAMP] MediaStore DATA query failed for "
              + uri
              + " - "
              + e.getClass().getSimpleName()
              + ": "
              + e.getMessage());
    }
    return null;
  }
}
