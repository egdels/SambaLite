/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the static path-building helpers in {@link FileBrowserActivity}. These cover the
 * Share-handoff upload pipeline targeted by Issue #27 (Dateien landen in {@code AA/AA/}).
 */
public class FileBrowserActivityTest {

  // --- normalizeInternalPath ---

  @Test
  public void normalizeInternalPath_null_returnsEmpty() {
    assertEquals("", FileBrowserActivity.normalizeInternalPath(null));
  }

  @Test
  public void normalizeInternalPath_empty_returnsEmpty() {
    assertEquals("", FileBrowserActivity.normalizeInternalPath(""));
  }

  @Test
  public void normalizeInternalPath_singleSlash_returnsEmpty() {
    assertEquals("", FileBrowserActivity.normalizeInternalPath("/"));
  }

  @Test
  public void normalizeInternalPath_stripsLeadingSlash() {
    assertEquals("docs", FileBrowserActivity.normalizeInternalPath("/docs"));
    assertEquals("docs/sub", FileBrowserActivity.normalizeInternalPath("/docs/sub"));
  }

  @Test
  public void normalizeInternalPath_stripsTrailingSlash() {
    assertEquals("docs", FileBrowserActivity.normalizeInternalPath("docs/"));
    assertEquals("docs/sub", FileBrowserActivity.normalizeInternalPath("docs/sub/"));
  }

  @Test
  public void normalizeInternalPath_stripsBothSlashes() {
    assertEquals("docs/sub", FileBrowserActivity.normalizeInternalPath("/docs/sub/"));
  }

  @Test
  public void normalizeInternalPath_preservesInnerSlashes() {
    assertEquals("a/b/c", FileBrowserActivity.normalizeInternalPath("a/b/c"));
  }

  // --- buildShareUploadTargetPath ---
  // Issue #27 (revised): The downstream upload pipeline
  // (FileOperationsController#handleMultipleFileUploads + TransferWorker) treats the supplied
  // target as *share-relative* — the SMB share is already connected by the worker. Therefore
  // the helper must NOT prepend the share name; doing so produced "share/share/sub" duplication
  // on the server (e.g. AA/AA/DOC.pdf landing in AA/AA/AA/DOC.pdf).

  @Test
  public void buildShareUploadTargetPath_shareRoot_returnsEmptyOrNull() {
    // Share root selected with a known share: empty string signals "share root" to the caller
    // (which then falls back to the ViewModel's current path, also share-root after navigation).
    assertEquals("", FileBrowserActivity.buildShareUploadTargetPath("AA", ""));
    assertEquals("", FileBrowserActivity.buildShareUploadTargetPath("AA", null));
  }

  @Test
  public void buildShareUploadTargetPath_subFolder_returnsShareRelativePath() {
    assertEquals("sub", FileBrowserActivity.buildShareUploadTargetPath("AA", "sub"));
  }

  @Test
  public void buildShareUploadTargetPath_multiLevelSubFolder_isPreserved() {
    // Issue #27 explicitly mentions multi-level directories.
    assertEquals(
        "sub1/sub2", FileBrowserActivity.buildShareUploadTargetPath("AA", "sub1/sub2"));
  }

  @Test
  public void buildShareUploadTargetPath_sameNamedSubFolder_doesNotDuplicateShare() {
    // The exact reproducer from the bug report: share "AA" with sub-folder "AA". The result
    // must stay share-relative ("AA") and never include the share name twice.
    assertEquals("AA", FileBrowserActivity.buildShareUploadTargetPath("AA", "AA"));
  }

  @Test
  public void buildShareUploadTargetPath_noShare_returnsPathOrNull() {
    // Defensive fallback: without a share name we still return whatever path we have, or
    // null when there is none (so the caller can detect the absence).
    assertEquals("sub", FileBrowserActivity.buildShareUploadTargetPath(null, "sub"));
    assertEquals("sub", FileBrowserActivity.buildShareUploadTargetPath("", "sub"));
    assertNull(FileBrowserActivity.buildShareUploadTargetPath(null, ""));
    assertNull(FileBrowserActivity.buildShareUploadTargetPath("", null));
  }

  // --- Combined: normalize -> build (matches the production call sequence) ---

  @Test
  public void combined_shareRootFromVariousInputs_returnsEmpty() {
    String share = "AA";
    assertEquals(
        "",
        FileBrowserActivity.buildShareUploadTargetPath(
            share, FileBrowserActivity.normalizeInternalPath(null)));
    assertEquals(
        "",
        FileBrowserActivity.buildShareUploadTargetPath(
            share, FileBrowserActivity.normalizeInternalPath("")));
    assertEquals(
        "",
        FileBrowserActivity.buildShareUploadTargetPath(
            share, FileBrowserActivity.normalizeInternalPath("/")));
  }

  @Test
  public void combined_issue27Reproducer_doesNotDuplicateShare() {
    // Reproducer Variante A: share "AA", sub-folder "AA". The persisted internal path is
    // "AA" (share-relative); the upload target must stay "AA" — combined with the share
    // already connected by the TransferWorker the file lands in \\server\AA\AA\<file>.
    assertEquals(
        "AA",
        FileBrowserActivity.buildShareUploadTargetPath(
            "AA", FileBrowserActivity.normalizeInternalPath("AA")));
    // Even when the path arrives with surrounding slashes, the result stays stable.
    assertEquals(
        "AA",
        FileBrowserActivity.buildShareUploadTargetPath(
            "AA", FileBrowserActivity.normalizeInternalPath("/AA/")));
  }

  @Test
  public void combined_multiLevel_keepsShareRelativePath() {
    assertEquals(
        "sub1/sub2",
        FileBrowserActivity.buildShareUploadTargetPath(
            "myshare", FileBrowserActivity.normalizeInternalPath("/sub1/sub2/")));
  }

  // --- isUploadInDirectory ---
  // Decides whether a transfer-completed broadcast must trigger a refresh of the currently
  // displayed directory. Without this filter every upload (including background syncs to
  // unrelated folders) would invalidate the cache and reload the user's view.

  @Test
  public void isUploadInDirectory_uploadToSharedRoot_whileViewingRoot_matches() {
    // Browser at share-root ("") receives upload "share/file.txt" → parent "share" → ""
    assertTrue(FileBrowserActivity.isUploadInDirectory("share/file.txt", "share", ""));
  }

  @Test
  public void isUploadInDirectory_uploadToSubfolder_whileViewingRoot_doesNotMatch() {
    // Reproducer for the reported issue: viewing root, sync uploads into "share/TEST/scans/...".
    assertFalse(
        FileBrowserActivity.isUploadInDirectory(
            "share/TEST/scans/file.pdf", "share", ""));
  }

  @Test
  public void isUploadInDirectory_uploadAndViewMatchSubfolder_matches() {
    assertTrue(
        FileBrowserActivity.isUploadInDirectory(
            "share/TEST/scans/file.pdf", "share", "TEST/scans"));
  }

  @Test
  public void isUploadInDirectory_uploadInDifferentSubfolder_doesNotMatch() {
    assertFalse(
        FileBrowserActivity.isUploadInDirectory(
            "share/TEST/scans/file.pdf", "share", "TEST/other"));
  }

  @Test
  public void isUploadInDirectory_handlesBackslashesAndLeadingSlashes() {
    // TransferWorker logs "TEST\\TEST\\scans" (Windows-style); the helper must be tolerant.
    assertTrue(
        FileBrowserActivity.isUploadInDirectory(
            "share\\TEST\\scans\\file.pdf", "share", "TEST/scans"));
    assertTrue(
        FileBrowserActivity.isUploadInDirectory(
            "/share/TEST/scans/file.pdf", "share", "/TEST/scans/"));
  }

  @Test
  public void isUploadInDirectory_nullOrEmptyRemotePath_doesNotMatch() {
    assertFalse(FileBrowserActivity.isUploadInDirectory(null, "share", ""));
    assertFalse(FileBrowserActivity.isUploadInDirectory("", "share", ""));
  }

  @Test
  public void isUploadInDirectory_unknownShare_stillCanMatchByExactParent() {
    // Without a known share, the helper compares the raw parent. This is a defensive fallback
    // (e.g. when the connection has no share configured) and only matches when the caller's
    // currentInternal already includes the leading segment.
    assertTrue(FileBrowserActivity.isUploadInDirectory("a/b/file.txt", null, "a/b"));
    assertFalse(FileBrowserActivity.isUploadInDirectory("a/b/file.txt", null, "b"));
  }
}
