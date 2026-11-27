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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import androidx.documentfile.provider.DocumentFile;
import androidx.test.core.app.ApplicationProvider;
import java.io.IOException;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowContentResolver;

/** Tests that a folder listing takes the metadata of its children from one query (#40). */
@RunWith(RobolectricTestRunner.class)
public class LocalFolderListingTest {

  private static final String AUTHORITY = "de.schliweb.sambalite.test.documents";
  private static final Uri TREE = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root");
  private static final Uri FOLDER = DocumentsContract.buildDocumentUriUsingTree(TREE, "root/dir");

  private Context context;
  private FakeProvider provider;

  /** Answers the children query with the rows set by the test. */
  public static class FakeProvider extends ContentProvider {
    MatrixCursor rows;
    boolean fail;
    boolean noResult;
    int queries;

    @Override
    public boolean onCreate() {
      return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] args, String sort) {
      queries++;
      if (fail) throw new SecurityException("no access");
      if (noResult) return null;
      return rows;
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
  }

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    provider = new FakeProvider();
    provider.rows =
        new MatrixCursor(
            new String[] {
              Document.COLUMN_DOCUMENT_ID,
              Document.COLUMN_DISPLAY_NAME,
              Document.COLUMN_MIME_TYPE,
              Document.COLUMN_SIZE,
              Document.COLUMN_LAST_MODIFIED
            });
    ShadowContentResolver.registerProviderInternal(AUTHORITY, provider);
  }

  private static DocumentFile child(String documentId) {
    DocumentFile file = mock(DocumentFile.class);
    when(file.getUri()).thenReturn(DocumentsContract.buildDocumentUriUsingTree(TREE, documentId));
    return file;
  }

  private static DocumentFile folder(Uri uri, DocumentFile... children) {
    DocumentFile folder = mock(DocumentFile.class);
    when(folder.getUri()).thenReturn(uri);
    when(folder.listFiles()).thenReturn(children);
    return folder;
  }

  @Test
  public void metadataComesFromOneQueryWithoutAskingTheChildren() throws IOException {
    DocumentFile file = child("root/dir/a.txt");
    DocumentFile sub = child("root/dir/sub");
    provider.rows.addRow(new Object[] {"root/dir/a.txt", "a.txt", "text/plain", 42L, 1000L});
    provider.rows.addRow(new Object[] {"root/dir/sub", "sub", Document.MIME_TYPE_DIR, null, 2000L});

    List<LocalFolderListing.Entry> entries = LocalFolderListing.list(context, folder(FOLDER, file, sub));

    assertEquals(2, entries.size());
    assertSame(file, entries.get(0).file);
    assertEquals("a.txt", entries.get(0).getName());
    assertFalse(entries.get(0).isDirectory());
    assertEquals(42L, entries.get(0).length());
    assertEquals(1000L, entries.get(0).lastModified());
    assertEquals("sub", entries.get(1).getName());
    assertTrue(entries.get(1).isDirectory());
    assertEquals(0L, entries.get(1).length());
    assertEquals(1, provider.queries);
    verify(file, never()).getName();
    verify(file, never()).isDirectory();
    verify(file, never()).length();
    verify(file, never()).lastModified();
  }

  @Test
  public void childMissingInTheQueryAsksTheProviderItself() throws IOException {
    DocumentFile known = child("root/dir/a.txt");
    DocumentFile late = child("root/dir/late.txt");
    when(late.getName()).thenReturn("late.txt");
    when(late.length()).thenReturn(7L);
    when(late.lastModified()).thenReturn(3000L);
    provider.rows.addRow(new Object[] {"root/dir/a.txt", "a.txt", "text/plain", 42L, 1000L});

    List<LocalFolderListing.Entry> entries =
        LocalFolderListing.list(context, folder(FOLDER, known, late));

    assertEquals("late.txt", entries.get(1).getName());
    assertFalse(entries.get(1).isDirectory());
    assertEquals(7L, entries.get(1).length());
    assertEquals(3000L, entries.get(1).lastModified());
  }

  @Test
  public void failedQueryIsReportedInsteadOfAnEmptyFolder() {
    provider.fail = true;

    assertThrows(
        IOException.class,
        () -> LocalFolderListing.list(context, folder(FOLDER, child("root/dir/a.txt"))));
  }

  @Test
  public void queryWithoutResultIsReported() {
    provider.noResult = true;

    assertThrows(IOException.class, () -> LocalFolderListing.list(context, folder(FOLDER)));
  }

  /**
   * DocumentFile.listFiles() returns an empty array when its own query fails. Taken as an empty
   * folder, the sync downloaded every server file again as "name (1)".
   */
  @Test
  public void silentlyFailedChildListingIsReported() {
    provider.rows.addRow(new Object[] {"root/dir/a.txt", "a.txt", "text/plain", 42L, 1000L});
    provider.rows.addRow(new Object[] {"root/dir/b.txt", "b.txt", "text/plain", 7L, 1000L});

    IOException e =
        assertThrows(IOException.class, () -> LocalFolderListing.list(context, folder(FOLDER)));
    assertTrue(e.getMessage(), e.getMessage().contains("0 of 2"));
  }

  @Test
  public void childAddedBetweenTheQueriesIsListed() throws IOException {
    DocumentFile file = child("root/dir/a.txt");
    DocumentFile added = child("root/dir/new.txt");
    when(added.getName()).thenReturn("new.txt");
    provider.rows.addRow(new Object[] {"root/dir/a.txt", "a.txt", "text/plain", 42L, 1000L});

    List<LocalFolderListing.Entry> entries =
        LocalFolderListing.list(context, folder(FOLDER, file, added));

    assertEquals(2, entries.size());
    assertEquals("new.txt", entries.get(1).getName());
  }

  @Test
  public void folderOutsideADocumentTreeIsNotQueried() throws IOException {
    DocumentFile file = mock(DocumentFile.class);
    when(file.getUri()).thenReturn(Uri.parse("file:///tmp/dir/a.txt"));
    when(file.getName()).thenReturn("a.txt");
    when(file.isDirectory()).thenReturn(true);

    List<LocalFolderListing.Entry> entries =
        LocalFolderListing.list(context, folder(Uri.parse("file:///tmp/dir"), file));

    assertEquals("a.txt", entries.get(0).getName());
    assertTrue(entries.get(0).isDirectory());
    assertEquals(0, provider.queries);
  }

  @Test
  public void emptyFolderIsListedEmpty() throws IOException {
    assertTrue(LocalFolderListing.list(context, folder(FOLDER)).isEmpty());
    assertEquals(1, provider.queries);
  }
}
