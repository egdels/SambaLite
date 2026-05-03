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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the {@link FolderSyncWorker#isTrashAtRoot(String, String)} filter, which is the
 * single point of truth that prevents the mirror-mode trash folder ({@code .sambalite-trash}) from
 * being traversed during sync or mirror sweep.
 *
 * <p>The filter is invoked at four call sites in {@link FolderSyncWorker}:
 *
 * <ul>
 *   <li>{@code syncLocalToRemote} – avoids uploading a local trash folder created by a previous
 *       Remote→Local mirror run to the SMB target.
 *   <li>{@code syncRemoteToLocal} – avoids downloading a remote trash folder created by a previous
 *       Local→Remote mirror run to the local target.
 *   <li>{@code collectLocalPaths} – the trash folder is not part of the local source listing for
 *       the mirror sweep, so its tracked children are not reported as "missing on source".
 *   <li>{@code collectRemotePaths} – same as above for the remote source listing.
 * </ul>
 *
 * <p>Together these four call sites guarantee that a trash folder created by a prior mirror run is
 * never re-mirrored, re-uploaded, re-downloaded, or itself deleted by a subsequent sweep.
 */
public class TrashExclusionTest {

  /** The trash folder must be excluded when it appears at the sync root. */
  @Test
  public void trashAtRoot_isExcluded_withEmptyRelPath() {
    assertTrue(FolderSyncWorker.isTrashAtRoot(FolderSyncWorker.TRASH_DIR_NAME, ""));
  }

  /** {@code null} {@code relPath} is treated like the root. */
  @Test
  public void trashAtRoot_isExcluded_withNullRelPath() {
    assertTrue(FolderSyncWorker.isTrashAtRoot(FolderSyncWorker.TRASH_DIR_NAME, null));
  }

  /**
   * A user-created folder that happens to be named {@code .sambalite-trash} but lives in a
   * subdirectory must NOT be filtered. The filter only triggers at the sync root, so deliberate
   * usage of the name elsewhere is preserved.
   */
  @Test
  public void trashInSubdirectory_isNotExcluded() {
    assertFalse(FolderSyncWorker.isTrashAtRoot(FolderSyncWorker.TRASH_DIR_NAME, "music"));
    assertFalse(
        FolderSyncWorker.isTrashAtRoot(FolderSyncWorker.TRASH_DIR_NAME, "music/U2 (1991)"));
  }

  /** Other folder names at the root must not be filtered. */
  @Test
  public void otherNamesAtRoot_areNotExcluded() {
    assertFalse(FolderSyncWorker.isTrashAtRoot("music", ""));
    assertFalse(FolderSyncWorker.isTrashAtRoot("photos", null));
    // Similar but distinct names must not match.
    assertFalse(FolderSyncWorker.isTrashAtRoot(".sambalite", ""));
    assertFalse(FolderSyncWorker.isTrashAtRoot("sambalite-trash", ""));
    assertFalse(FolderSyncWorker.isTrashAtRoot(".sambalite-trash-old", ""));
  }

  /** Documents the canonical trash folder name so a rename is caught by the test suite. */
  @Test
  public void trashDirName_isStable() {
    assertEquals(".sambalite-trash", FolderSyncWorker.TRASH_DIR_NAME);
  }
}
