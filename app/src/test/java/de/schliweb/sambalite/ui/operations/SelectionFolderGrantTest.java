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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.net.Uri;
import android.provider.DocumentsContract;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Tests the mapping of a large multi-file selection onto one folder grant: finding the common
 * parent folder, checking that a picked tree covers the files, and re-addressing the files as
 * children of the tree.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SelectionFolderGrantTest {

  private static final String AUTH = SelectionFolderGrant.EXTERNAL_STORAGE_AUTHORITY;

  private static Uri doc(String id) {
    return DocumentsContract.buildDocumentUri(AUTH, id);
  }

  private static Uri tree(String id) {
    return DocumentsContract.buildTreeDocumentUri(AUTH, id);
  }

  // ── commonParentDocumentUri ────────────────────────────────────────────────

  @Test
  public void commonParent_filesInOneFolder_isThatFolder() {
    Uri parent =
        SelectionFolderGrant.commonParentDocumentUri(
            Arrays.asList(
                doc("primary:Download/test/scans/a.pdf"),
                doc("primary:Download/test/scans/b (1).pdf")));

    assertNotNull(parent);
    assertEquals("primary:Download/test/scans", DocumentsContract.getDocumentId(parent));
    assertEquals("scans", SelectionFolderGrant.folderDisplayName(parent));
  }

  @Test
  public void commonParent_filesInSubfoldersAndParent_isDeepestCommonFolder() {
    Uri parent =
        SelectionFolderGrant.commonParentDocumentUri(
            Arrays.asList(
                doc("primary:Download/test/scans/2024/a.pdf"),
                doc("primary:Download/test/b.pdf"),
                doc("primary:Download/test/scans/c.pdf")));

    assertNotNull(parent);
    assertEquals("primary:Download/test", DocumentsContract.getDocumentId(parent));
  }

  @Test
  public void commonParent_filesInVolumeRoot_isVolumeRoot() {
    Uri parent =
        SelectionFolderGrant.commonParentDocumentUri(
            Arrays.asList(doc("primary:a.pdf"), doc("primary:Download/b.pdf")));

    assertNotNull(parent);
    assertEquals("primary:", DocumentsContract.getDocumentId(parent));
    assertEquals("primary", SelectionFolderGrant.folderDisplayName(parent));
  }

  @Test
  public void commonParent_differentVolumes_isNull() {
    assertNull(
        SelectionFolderGrant.commonParentDocumentUri(
            Arrays.asList(doc("primary:Download/a.pdf"), doc("1234-5678:Download/b.pdf"))));
  }

  @Test
  public void commonParent_otherProviderOrTreeUri_isNull() {
    Uri media = Uri.parse("content://com.android.providers.media.documents/document/image%3A1");
    assertNull(
        SelectionFolderGrant.commonParentDocumentUri(
            Arrays.asList(doc("primary:Download/a.pdf"), media)));

    Uri treeChild =
        DocumentsContract.buildDocumentUriUsingTree(
            tree("primary:Download"), "primary:Download/a.pdf");
    assertNull(
        SelectionFolderGrant.commonParentDocumentUri(Collections.singletonList(treeChild)));

    assertNull(SelectionFolderGrant.commonParentDocumentUri(Collections.emptyList()));
  }

  // ── treeCoversAll ──────────────────────────────────────────────────────────

  @Test
  public void treeCoversAll_parentAndAncestorCover_siblingDoesNot() {
    List<Uri> files =
        Arrays.asList(
            doc("primary:Download/test/scans/a.pdf"), doc("primary:Download/test/scans/b.pdf"));

    assertTrue(SelectionFolderGrant.treeCoversAll(tree("primary:Download/test/scans"), files));
    assertTrue(SelectionFolderGrant.treeCoversAll(tree("primary:Download"), files));
    assertTrue(SelectionFolderGrant.treeCoversAll(tree("primary:"), files));
    assertFalse(SelectionFolderGrant.treeCoversAll(tree("primary:Download/test/scans2"), files));
    assertFalse(SelectionFolderGrant.treeCoversAll(tree("primary:Download/other"), files));
    assertFalse(SelectionFolderGrant.treeCoversAll(doc("primary:Download"), files));
    assertFalse(SelectionFolderGrant.treeCoversAll(tree("primary:Download"), Collections.emptyList()));
  }

  // ── rebaseOnTree ───────────────────────────────────────────────────────────

  @Test
  public void rebaseOnTree_addressesFilesAsTreeChildren() {
    Uri treeUri = tree("primary:Download/test/scans");
    List<UploadRequest> requests =
        Arrays.asList(
            new UploadRequest(doc("primary:Download/test/scans/a.pdf"), "lore/a.pdf", "a.pdf", 10),
            new UploadRequest(doc("primary:Download/test/scans/b.pdf"), "lore/b.pdf", "b.pdf", 20));

    List<UploadRequest> rebased = SelectionFolderGrant.rebaseOnTree(requests, treeUri);

    assertNotNull(rebased);
    assertEquals(2, rebased.size());
    assertEquals(
        DocumentsContract.buildDocumentUriUsingTree(treeUri, "primary:Download/test/scans/a.pdf"),
        rebased.get(0).uri);
    assertTrue(rebased.get(0).uri.toString().startsWith(treeUri.toString() + "/document/"));
    assertEquals("lore/a.pdf", rebased.get(0).remotePath);
    assertEquals("a.pdf", rebased.get(0).displayName);
    assertEquals(10, rebased.get(0).fileSize);
    assertEquals("lore/b.pdf", rebased.get(1).remotePath);
  }

  @Test
  public void rebaseOnTree_treeNotCoveringAllFiles_isNull() {
    List<UploadRequest> requests =
        Arrays.asList(
            new UploadRequest(doc("primary:Download/test/scans/a.pdf"), "lore/a.pdf", "a.pdf", 10),
            new UploadRequest(doc("primary:Download/other/b.pdf"), "lore/b.pdf", "b.pdf", 20));

    assertNull(SelectionFolderGrant.rebaseOnTree(requests, tree("primary:Download/test/scans")));
  }

  // ── ID helpers ─────────────────────────────────────────────────────────────

  @Test
  public void parentOf_and_commonAncestor() {
    assertEquals("primary:a/b", SelectionFolderGrant.parentOf("primary:a/b/c.pdf"));
    assertEquals("primary:", SelectionFolderGrant.parentOf("primary:c.pdf"));
    assertNull(SelectionFolderGrant.parentOf("no-volume-prefix"));

    assertEquals("primary:a", SelectionFolderGrant.commonAncestor("primary:a/b", "primary:a/c"));
    assertEquals("primary:a/b", SelectionFolderGrant.commonAncestor("primary:a/b", "primary:a/b"));
    assertEquals("primary:", SelectionFolderGrant.commonAncestor("primary:a", "primary:b"));
    assertEquals("primary:", SelectionFolderGrant.commonAncestor("primary:", "primary:a/b"));
    assertNull(SelectionFolderGrant.commonAncestor("primary:a", "other:a"));
  }
}
