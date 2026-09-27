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

import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps a large multi-file selection onto a single folder grant.
 *
 * <p>Android keeps at most 512 persisted URI grants per app, so per-file grants cannot make a
 * selection of thousands of files survive a reboot. A grant on the common parent folder can: the
 * files are then addressed as children of that tree, exactly like a folder upload.
 *
 * <p>Only documents of the platform's external storage provider are handled. Its document IDs are
 * paths of the form {@code primary:Download/scans/a.pdf}, so the parent folder can be derived from
 * the ID without querying the provider. Other providers use opaque IDs and are left alone.
 */
public final class SelectionFolderGrant {

  /** Authority of the platform's external storage documents provider. */
  static final String EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents";

  private SelectionFolderGrant() {}

  /**
   * Returns the document URI of the deepest folder that contains all picked files, or {@code null}
   * when the selection cannot be covered by one folder grant: mixed authorities, a provider without
   * path-shaped document IDs, tree-based URIs, or files from different storage volumes.
   *
   * @param uris Document URIs returned by {@code ACTION_OPEN_DOCUMENT}
   */
  @Nullable
  public static Uri commonParentDocumentUri(@NonNull List<Uri> uris) {
    if (uris.isEmpty()) {
      return null;
    }
    String common = null;
    for (Uri uri : uris) {
      String id = plainExternalStorageDocumentId(uri);
      if (id == null) {
        return null;
      }
      String parent = parentOf(id);
      if (parent == null) {
        return null;
      }
      common = common == null ? parent : commonAncestor(common, parent);
      if (common == null) {
        return null;
      }
    }
    return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, common);
  }

  /**
   * Whether every picked file lies below the given tree, so that its grant covers all of them.
   *
   * @param treeUri The tree URI returned by {@code ACTION_OPEN_DOCUMENT_TREE}
   * @param uris The picked document URIs
   */
  public static boolean treeCoversAll(@NonNull Uri treeUri, @NonNull List<Uri> uris) {
    if (!EXTERNAL_STORAGE_AUTHORITY.equals(treeUri.getAuthority())
        || !DocumentsContract.isTreeUri(treeUri)) {
      return false;
    }
    String treeId = DocumentsContract.getTreeDocumentId(treeUri);
    for (Uri uri : uris) {
      String id = plainExternalStorageDocumentId(uri);
      if (id == null || !isBelow(treeId, id)) {
        return false;
      }
    }
    return !uris.isEmpty();
  }

  /**
   * Re-addresses the requests as children of the tree, in the form {@code
   * DocumentsContract#buildDocumentUriUsingTree} produces, so that the tree grant applies when the
   * worker opens them.
   *
   * @return The rebased requests, or {@code null} when the tree does not cover every file
   */
  @Nullable
  public static List<UploadRequest> rebaseOnTree(
      @NonNull List<UploadRequest> requests, @NonNull Uri treeUri) {
    List<Uri> uris = new ArrayList<>(requests.size());
    for (UploadRequest r : requests) {
      uris.add(r.uri);
    }
    if (!treeCoversAll(treeUri, uris)) {
      return null;
    }
    List<UploadRequest> rebased = new ArrayList<>(requests.size());
    for (UploadRequest r : requests) {
      Uri child =
          DocumentsContract.buildDocumentUriUsingTree(
              treeUri, DocumentsContract.getDocumentId(r.uri));
      rebased.add(new UploadRequest(child, r.remotePath, r.displayName, r.fileSize));
    }
    return rebased;
  }

  /** The folder name to show for a parent document URI, e.g. {@code scans} or {@code primary}. */
  @NonNull
  public static String folderDisplayName(@NonNull Uri parentDocumentUri) {
    String id = DocumentsContract.getDocumentId(parentDocumentUri);
    int slash = id.lastIndexOf('/');
    if (slash >= 0) {
      return id.substring(slash + 1);
    }
    int colon = id.indexOf(':');
    return colon > 0 ? id.substring(0, colon) : id;
  }

  /** The document ID of a plain (not tree-based) external storage document URI, or {@code null}. */
  @Nullable
  static String plainExternalStorageDocumentId(@NonNull Uri uri) {
    if (!EXTERNAL_STORAGE_AUTHORITY.equals(uri.getAuthority())) {
      return null;
    }
    List<String> segments = uri.getPathSegments();
    if (segments.size() != 2 || !"document".equals(segments.get(0))) {
      return null;
    }
    return segments.get(1);
  }

  /**
   * The parent folder ID of a document ID: {@code primary:a/b/c.pdf} gives {@code primary:a/b},
   * {@code primary:c.pdf} gives the volume root {@code primary:}. IDs without a volume prefix give
   * {@code null}.
   */
  @Nullable
  static String parentOf(@NonNull String documentId) {
    int colon = documentId.indexOf(':');
    if (colon <= 0) {
      return null;
    }
    int slash = documentId.lastIndexOf('/');
    return slash > colon ? documentId.substring(0, slash) : documentId.substring(0, colon + 1);
  }

  /**
   * The deepest folder ID that contains both folders, or {@code null} when they lie on different
   * volumes.
   */
  @Nullable
  static String commonAncestor(@NonNull String a, @NonNull String b) {
    if (a.equals(b)) {
      return a;
    }
    int colonA = a.indexOf(':');
    int colonB = b.indexOf(':');
    if (colonA <= 0 || colonB <= 0 || !a.regionMatches(0, b, 0, colonA + 1) || colonA != colonB) {
      return null;
    }
    String volume = a.substring(0, colonA + 1);
    String[] partsA = split(a.substring(colonA + 1));
    String[] partsB = split(b.substring(colonB + 1));
    StringBuilder common = new StringBuilder(volume);
    for (int i = 0; i < Math.min(partsA.length, partsB.length); i++) {
      if (!partsA[i].equals(partsB[i])) {
        break;
      }
      if (i > 0) {
        common.append('/');
      }
      common.append(partsA[i]);
    }
    return common.toString();
  }

  /** Whether the document lies below the folder, or is the folder itself. */
  static boolean isBelow(@NonNull String folderId, @NonNull String documentId) {
    if (documentId.equals(folderId)) {
      return true;
    }
    String prefix = folderId.endsWith(":") ? folderId : folderId + "/";
    return documentId.startsWith(prefix);
  }

  private static String[] split(String relativePath) {
    return relativePath.isEmpty() ? new String[0] : relativePath.split("/");
  }
}
