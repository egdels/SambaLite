/*
 * SambaLite - A lightweight Android SMB client
 * Copyright (C) 2025 Christian Kierdorf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package de.schliweb.sambalite.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import androidx.test.core.app.ApplicationProvider;
import de.schliweb.sambalite.ui.operations.UploadRequest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests the recursive SAF folder scan and its single-query-per-directory lister. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SafFolderScannerTest {

  private static final String AUTHORITY = "de.schliweb.sambalite.test.docs";
  private static final Uri TREE = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root");

  /** In-memory directory tree: parent document ID to children. */
  private static final Map<String, List<SafFolderScanner.Entry>> TREE_CONTENT = new HashMap<>();

  static {
    TREE_CONTENT.put(
        "root",
        Arrays.asList(
            new SafFolderScanner.Entry("root/a.jpg", "a.jpg", false, 10),
            new SafFolderScanner.Entry("root/sub", "sub", true, 0),
            new SafFolderScanner.Entry("root/b.jpg", "b.jpg", false, 20)));
    TREE_CONTENT.put(
        "root/sub",
        Arrays.asList(
            new SafFolderScanner.Entry("root/sub/c.txt", "c.txt", false, 30),
            new SafFolderScanner.Entry("root/sub/empty", "empty", true, 0)));
    TREE_CONTENT.put("root/sub/empty", Collections.emptyList());
  }

  private static final SafFolderScanner.ChildLister MEMORY_LISTER =
      (treeUri, parentId) -> {
        List<SafFolderScanner.Entry> children = TREE_CONTENT.get(parentId);
        return children != null ? children : Collections.emptyList();
      };

  @Test
  public void scan_collectsFilesRecursivelyWithRemotePathsAndTreeUris() {
    List<UploadRequest> files = new SafFolderScanner(MEMORY_LISTER).scan(TREE, "Photos", null);

    assertEquals(3, files.size());
    assertEquals("Photos/a.jpg", files.get(0).remotePath);
    assertEquals("Photos/sub/c.txt", files.get(1).remotePath);
    assertEquals("Photos/b.jpg", files.get(2).remotePath);
    assertEquals(30, files.get(1).fileSize);
    assertEquals("c.txt", files.get(1).displayName);
    // Child URIs must be tree-based so the persisted tree grant covers them
    assertEquals(
        DocumentsContract.buildDocumentUriUsingTree(TREE, "root/sub/c.txt"), files.get(1).uri);
    assertTrue(files.get(1).uri.toString().startsWith(TREE + "/"));
  }

  @Test
  public void scan_withEmptyBasePathUsesRelativePathsOnly() {
    List<UploadRequest> files = new SafFolderScanner(MEMORY_LISTER).scan(TREE, "", null);

    assertEquals("a.jpg", files.get(0).remotePath);
    assertEquals("sub/c.txt", files.get(1).remotePath);
  }

  @Test
  public void scan_reportsProgressPerDirectory() {
    AtomicInteger calls = new AtomicInteger();
    new SafFolderScanner(MEMORY_LISTER).scan(TREE, "", filesSoFar -> calls.incrementAndGet());

    assertEquals("root, sub and empty", 3, calls.get());
  }

  @Test
  public void scan_returnsEmptyForUnreadableRoot() {
    Uri unknownTree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "missing");

    assertTrue(new SafFolderScanner(MEMORY_LISTER).scan(unknownTree, "", null).isEmpty());
  }

  /** The resolver-backed lister issues one children query per directory against the provider. */
  @Test
  public void resolverLister_listsChildrenWithOneQueryPerDirectory() {
    Robolectric.buildContentProvider(FakeDocumentsProvider.class).create(AUTHORITY);
    FakeDocumentsProvider.queries.set(0);

    List<UploadRequest> files =
        SafFolderScanner.forResolver(
                ApplicationProvider.getApplicationContext().getContentResolver())
            .scan(TREE, "Photos", null);

    assertEquals(3, files.size());
    assertEquals("Photos/sub/c.txt", files.get(1).remotePath);
    assertEquals(30, files.get(1).fileSize);
    assertEquals("one query for each of root, sub and empty", 3, FakeDocumentsProvider.queries.get());
  }

  /**
   * Serves {@link #TREE_CONTENT} for child-document queries. A plain {@link ContentProvider} rather
   * than a {@code DocumentsProvider}: Robolectric routes queries through the legacy five-argument
   * overload, which {@code DocumentsProvider} declares final and rejects.
   */
  public static class FakeDocumentsProvider extends ContentProvider {
    static final AtomicInteger queries = new AtomicInteger();

    @Override
    public boolean onCreate() {
      return true;
    }

    @Override
    public Cursor query(
        Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
      queries.incrementAndGet();
      MatrixCursor cursor = new MatrixCursor(columns(projection));
      if (!uri.getPath().endsWith("/children")) {
        return cursor;
      }
      String parentDocumentId = DocumentsContract.getDocumentId(uri);
      List<SafFolderScanner.Entry> children = TREE_CONTENT.get(parentDocumentId);
      if (children == null) {
        return cursor;
      }
      for (SafFolderScanner.Entry e : children) {
        cursor
            .newRow()
            .add(Document.COLUMN_DOCUMENT_ID, e.documentId)
            .add(Document.COLUMN_DISPLAY_NAME, e.name)
            .add(Document.COLUMN_MIME_TYPE, e.isDirectory ? Document.MIME_TYPE_DIR : "image/jpeg")
            .add(Document.COLUMN_SIZE, e.size);
      }
      return cursor;
    }

    @Override
    public String getType(Uri uri) {
      return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
      return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
      return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
      return 0;
    }

    private static String[] columns(String[] projection) {
      return projection != null
          ? projection
          : new String[] {
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE
          };
    }
  }
}
