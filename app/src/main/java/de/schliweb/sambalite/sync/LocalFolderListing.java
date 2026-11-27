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
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import de.schliweb.sambalite.util.LogUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists a local folder with the name, type, size and modification time of every child from one
 * query.
 *
 * <p>{@link DocumentFile#getName()}, {@link DocumentFile#isDirectory()}, {@link
 * DocumentFile#length()} and {@link DocumentFile#lastModified()} each ask the storage provider with
 * a query of their own. For a sync that only compares files this was four queries per file and by
 * far most of the time of a run in which nothing changed (#40). The provider returns the same
 * values for all children of a folder at once.
 *
 * <p>The values are those of the moment the folder was listed. They are meant for deciding what to
 * do with an entry; after a file was written, ask its {@link Entry#file} again.
 */
final class LocalFolderListing {

  private static final String TAG = "LocalFolderListing";

  private static final String[] PROJECTION = {
    Document.COLUMN_DOCUMENT_ID,
    Document.COLUMN_DISPLAY_NAME,
    Document.COLUMN_MIME_TYPE,
    Document.COLUMN_SIZE,
    Document.COLUMN_LAST_MODIFIED
  };

  private LocalFolderListing() {}

  /** A child of a listed folder. */
  static final class Entry {
    /** The child itself, for reading, writing and listing it. */
    @NonNull final DocumentFile file;

    @Nullable private final Metadata metadata;

    Entry(@NonNull DocumentFile file, @Nullable Metadata metadata) {
      this.file = file;
      this.metadata = metadata;
    }

    /** Returns the display name, or {@code null} if the provider gives none. */
    @Nullable
    String getName() {
      return metadata != null ? metadata.name : file.getName();
    }

    boolean isDirectory() {
      return metadata != null ? metadata.directory : file.isDirectory();
    }

    /** Returns the size in bytes when the folder was listed; 0 if unknown. */
    long length() {
      return metadata != null ? metadata.size : file.length();
    }

    /** Returns the modification time when the folder was listed; 0 if unknown. */
    long lastModified() {
      return metadata != null ? metadata.lastModified : file.lastModified();
    }
  }

  /** The values of one row of the children query. */
  static final class Metadata {
    @Nullable final String name;
    final boolean directory;
    final long size;
    final long lastModified;

    Metadata(@Nullable String name, boolean directory, long size, long lastModified) {
      this.name = name;
      this.directory = directory;
      this.size = size;
      this.lastModified = lastModified;
    }
  }

  /**
   * Lists the children of {@code folder}. A child the metadata query does not return asks the
   * provider itself as before; a folder that is not a document tree is listed with {@link
   * DocumentFile#listFiles()} only.
   *
   * <p>{@link DocumentFile#listFiles()} returns an empty array when the provider query fails, which
   * looks exactly like an empty folder. A sync that took that at face value downloaded every server
   * file again, and the provider stored the copies as "name (1)" next to the existing files. For a
   * document tree the children are therefore counted by a query of their own, and a listing that
   * cannot be read, or that has fewer children than that query, is reported as an error instead.
   *
   * @throws IOException if the children of a document tree folder could not be listed completely
   */
  @NonNull
  static List<Entry> list(@NonNull Context context, @NonNull DocumentFile folder)
      throws IOException {
    Uri uri = folder.getUri();
    Map<String, Metadata> metadata =
        uri != null && DocumentsContract.isTreeUri(uri) ? queryMetadata(context, uri) : null;

    DocumentFile[] children = folder.listFiles();
    if (children == null) children = new DocumentFile[0];
    if (metadata != null && children.length < metadata.size()) {
      throw new IOException(
          "local folder listing incomplete: "
              + children.length
              + " of "
              + metadata.size()
              + " entries");
    }

    List<Entry> entries = new ArrayList<>(children.length);
    for (DocumentFile child : children) {
      Metadata m = null;
      if (metadata != null) {
        try {
          m = metadata.get(DocumentsContract.getDocumentId(child.getUri()));
        } catch (RuntimeException e) {
          m = null;
        }
      }
      entries.add(new Entry(child, m));
    }
    return entries;
  }

  /**
   * Returns the metadata of the children of a document tree folder by document id.
   *
   * @throws IOException if the provider does not answer the query
   */
  @NonNull
  private static Map<String, Metadata> queryMetadata(@NonNull Context context, @NonNull Uri uri)
      throws IOException {
    try {
      Uri childrenUri =
          DocumentsContract.buildChildDocumentsUriUsingTree(
              uri, DocumentsContract.getDocumentId(uri));
      Map<String, Metadata> result = new HashMap<>();
      try (Cursor c =
          context.getContentResolver().query(childrenUri, PROJECTION, null, null, null)) {
        if (c == null) throw new IOException("local folder could not be listed: no result");
        int idCol = c.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID);
        int nameCol = c.getColumnIndex(Document.COLUMN_DISPLAY_NAME);
        int mimeCol = c.getColumnIndex(Document.COLUMN_MIME_TYPE);
        int sizeCol = c.getColumnIndex(Document.COLUMN_SIZE);
        int modCol = c.getColumnIndex(Document.COLUMN_LAST_MODIFIED);
        while (c.moveToNext()) {
          String id = c.isNull(idCol) ? null : c.getString(idCol);
          if (id == null) continue;
          String name = nameCol < 0 || c.isNull(nameCol) ? null : c.getString(nameCol);
          String mime = mimeCol < 0 || c.isNull(mimeCol) ? null : c.getString(mimeCol);
          long size = sizeCol < 0 || c.isNull(sizeCol) ? 0 : c.getLong(sizeCol);
          long lastModified = modCol < 0 || c.isNull(modCol) ? 0 : c.getLong(modCol);
          result.put(
              id, new Metadata(name, Document.MIME_TYPE_DIR.equals(mime), size, lastModified));
        }
      }
      return result;
    } catch (RuntimeException e) {
      LogUtils.w(TAG, "Could not list local folder " + uri + ": " + e.getMessage());
      throw new IOException("local folder could not be listed: " + e.getMessage(), e);
    }
  }
}
