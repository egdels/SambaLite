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

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.schliweb.sambalite.ui.operations.UploadRequest;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects all files below a SAF folder tree as {@link UploadRequest}s.
 *
 * <p>Uses one {@code queryChildDocuments} call per directory with a fixed projection. {@code
 * DocumentFile} would instead issue a separate content provider query per attribute and per child
 * (name, MIME type, size, directory flag), which makes a folder with tens of thousands of files
 * take minutes to scan.
 *
 * <p>Child URIs are built with {@link DocumentsContract#buildDocumentUriUsingTree}, the same form
 * {@code DocumentFile} produces, so they stay covered by the persisted grant of the tree URI.
 */
public final class SafFolderScanner {

  private static final String TAG = "SafFolderScanner";

  /** One entry of a directory listing. */
  public static final class Entry {
    @NonNull public final String documentId;
    @NonNull public final String name;
    public final boolean isDirectory;
    public final long size;

    public Entry(@NonNull String documentId, @NonNull String name, boolean isDirectory, long size) {
      this.documentId = documentId;
      this.name = name;
      this.isDirectory = isDirectory;
      this.size = size;
    }
  }

  /** Lists the direct children of a document within a tree. */
  public interface ChildLister {
    /**
     * Returns the direct children of a directory.
     *
     * @param treeUri The picked tree URI that carries the permission
     * @param parentDocumentId Document ID of the directory to list
     * @return The direct children, or an empty list if the directory cannot be read
     */
    @NonNull
    List<Entry> list(@NonNull Uri treeUri, @NonNull String parentDocumentId);
  }

  /** Reports scan progress. */
  public interface ProgressListener {
    /** Called after each directory, with the number of files collected so far. */
    void onProgress(int filesSoFar);
  }

  private final ChildLister lister;

  public SafFolderScanner(@NonNull ChildLister lister) {
    this.lister = lister;
  }

  /** Creates a scanner that reads through the given content resolver. */
  @NonNull
  public static SafFolderScanner forResolver(@NonNull ContentResolver resolver) {
    return new SafFolderScanner(new ResolverLister(resolver));
  }

  /**
   * Scans the tree recursively.
   *
   * @param treeUri The picked folder tree URI
   * @param remoteBasePath Share-relative remote directory the folder contents go to, may be empty
   * @param progress Optional progress listener
   * @return All files below the tree, in listing order, directories first-depth
   */
  @NonNull
  public List<UploadRequest> scan(
      @NonNull Uri treeUri, @NonNull String remoteBasePath, @Nullable ProgressListener progress) {
    List<UploadRequest> result = new ArrayList<>();
    String rootId = DocumentsContract.getTreeDocumentId(treeUri);
    scanDirectory(treeUri, rootId, remoteBasePath, result, progress);
    return result;
  }

  private void scanDirectory(
      Uri treeUri,
      String documentId,
      String remoteDir,
      List<UploadRequest> result,
      @Nullable ProgressListener progress) {
    for (Entry entry : lister.list(treeUri, documentId)) {
      String remotePath = remoteDir.isEmpty() ? entry.name : remoteDir + "/" + entry.name;
      if (entry.isDirectory) {
        scanDirectory(treeUri, entry.documentId, remotePath, result, progress);
      } else {
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.documentId);
        result.add(new UploadRequest(uri, remotePath, entry.name, entry.size));
      }
    }
    if (progress != null) {
      progress.onProgress(result.size());
    }
  }

  /** Lists children with a single projected query per directory. */
  static final class ResolverLister implements ChildLister {

    private static final String[] PROJECTION = {
      Document.COLUMN_DOCUMENT_ID,
      Document.COLUMN_DISPLAY_NAME,
      Document.COLUMN_MIME_TYPE,
      Document.COLUMN_SIZE
    };

    private final ContentResolver resolver;

    ResolverLister(ContentResolver resolver) {
      this.resolver = resolver;
    }

    @NonNull
    @Override
    public List<Entry> list(@NonNull Uri treeUri, @NonNull String parentDocumentId) {
      Uri childrenUri =
          DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId);
      List<Entry> entries = new ArrayList<>();
      try (Cursor cursor = resolver.query(childrenUri, PROJECTION, null, null, null)) {
        if (cursor == null) {
          LogUtils.w(TAG, "Provider returned no cursor for " + childrenUri);
          return entries;
        }
        int idCol = cursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID);
        int nameCol = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME);
        int mimeCol = cursor.getColumnIndex(Document.COLUMN_MIME_TYPE);
        int sizeCol = cursor.getColumnIndex(Document.COLUMN_SIZE);
        while (cursor.moveToNext()) {
          String id = idCol >= 0 ? cursor.getString(idCol) : null;
          String name = nameCol >= 0 ? cursor.getString(nameCol) : null;
          if (id == null || name == null) {
            continue;
          }
          String mime = mimeCol >= 0 ? cursor.getString(mimeCol) : null;
          boolean isDir = Document.MIME_TYPE_DIR.equals(mime);
          long size = sizeCol >= 0 && !cursor.isNull(sizeCol) ? cursor.getLong(sizeCol) : 0L;
          entries.add(new Entry(id, name, isDir, size));
        }
      } catch (Exception e) {
        LogUtils.w(TAG, "Could not list " + childrenUri + ": " + e.getMessage());
      }
      return entries;
    }
  }
}
