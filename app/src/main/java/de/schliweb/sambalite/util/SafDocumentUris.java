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

import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Helpers for Storage Access Framework document URIs. */
public final class SafDocumentUris {

  private SafDocumentUris() {}

  /**
   * Derives the document URI of the parent folder of a document that was created inside a granted
   * tree, for example a download target created with {@code DocumentFile.createFile} below a folder
   * picked by the user.
   *
   * <p>The Storage Access Framework has no "parent" query, but providers that build document ids
   * from paths (the external storage provider: {@code <volume>:<path>}, the downloads provider:
   * {@code raw:<path>}) let the parent be read off the id. The result stays within the same tree,
   * so the tree grant covers it.
   *
   * @param documentUri a document URI built from a tree URI via {@link
   *     DocumentsContract#buildDocumentUriUsingTree}
   * @return the parent folder's document URI within the same tree, or {@code null} if the URI is no
   *     tree document, is the tree root itself, or its id is not path-shaped
   */
  @Nullable
  public static Uri parentDocumentUri(@NonNull Uri documentUri) {
    try {
      if (!DocumentsContract.isTreeUri(documentUri)) return null;
      String treeId = DocumentsContract.getTreeDocumentId(documentUri);
      String docId = DocumentsContract.getDocumentId(documentUri);
      String parentId = parentDocumentId(docId);
      if (parentId == null || docId.equals(treeId)) return null;
      return DocumentsContract.buildDocumentUriUsingTree(documentUri, parentId);
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Returns the parent of a path-shaped document id, or {@code null} if it has none.
   *
   * <ul>
   *   <li>{@code 427D-0786:Music/a/b.mp3} → {@code 427D-0786:Music/a}
   *   <li>{@code primary:b.mp3} → {@code primary:} (the volume root)
   *   <li>{@code primary:} or {@code primary} → {@code null}
   * </ul>
   */
  @Nullable
  static String parentDocumentId(@Nullable String docId) {
    if (docId == null || docId.isEmpty()) return null;
    int colon = docId.indexOf(':');
    String path = colon < 0 ? docId : docId.substring(colon + 1);
    if (path.isEmpty()) return null;
    int slash = path.lastIndexOf('/');
    if (slash < 0) {
      return colon < 0 ? null : docId.substring(0, colon + 1);
    }
    return (colon < 0 ? "" : docId.substring(0, colon + 1)) + path.substring(0, slash);
  }
}
