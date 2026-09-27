/*
 * SambaLite - A lightweight Android SMB client
 * Copyright (C) 2025 Christian Kierdorf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package de.schliweb.sambalite.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import de.schliweb.sambalite.transfer.db.PendingTransfer;
import de.schliweb.sambalite.transfer.db.PendingTransferDao;
import de.schliweb.sambalite.transfer.db.TransferDatabase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Tests the release of persisted upload source grants: after completion, on cancel and on removal
 * from the queue, and the rules that keep grants which other uploads or downloads still need.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class UploadSourceGrantsTest {

  private static final String FILE_A = "content://com.android.providers.media.documents/document/image%3A1";
  private static final String FILE_B = "content://com.android.providers.media.documents/document/image%3A2";
  private static final String TREE = "content://com.android.externalstorage.documents/tree/primary%3ADCIM";
  private static final String TREE_CHILD_1 = TREE + "/document/primary%3ADCIM%2Fa.jpg";
  private static final String TREE_CHILD_2 = TREE + "/document/primary%3ADCIM%2Fb.jpg";
  /** A sibling tree whose name starts with the other tree's name. */
  private static final String TREE_SIBLING = TREE + "2";
  private static final String TREE_SIBLING_CHILD = TREE_SIBLING + "/document/primary%3ADCIM2%2Fc.jpg";
  private static final String DOWNLOAD_TARGET = "content://com.android.externalstorage.documents/tree/primary%3ADownload";

  private Context context;
  private ContentResolver resolver;
  private TransferDatabase db;
  private PendingTransferDao dao;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    resolver = context.getContentResolver();
    db =
        Room.inMemoryDatabaseBuilder(context, TransferDatabase.class)
            .allowMainThreadQueries()
            .build();
    dao = db.pendingTransferDao();
    releaseAllGrants();
  }

  @After
  public void tearDown() {
    db.close();
    releaseAllGrants();
  }

  /** The shadow resolver keeps grants in a static list that outlives a single test. */
  private void releaseAllGrants() {
    for (UriPermission p : new ArrayList<>(resolver.getPersistedUriPermissions())) {
      resolver.releasePersistableUriPermission(
          p.getUri(),
          Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }
  }

  // ── DAO queries ────────────────────────────────────────────────────────────

  @Test
  public void getUnfinishedUploadLocalUris_returnsPendingActiveAndFailedUploadsOnly() {
    insertUpload(FILE_A);
    long active = insertUpload(FILE_B);
    dao.updateStatus(active, "ACTIVE", now());
    long failed = insertUpload(TREE_CHILD_1);
    dao.markFailed(failed, "err", now());
    long completed = insertUpload(TREE_CHILD_2);
    dao.updateStatus(completed, "COMPLETED", now());
    long cancelled = insertUpload(TREE_SIBLING_CHILD);
    dao.cancelByIds(Collections.singletonList(cancelled), now());
    insertDownload(DOWNLOAD_TARGET);

    List<String> unfinished = dao.getUnfinishedUploadLocalUris();

    assertEquals(3, unfinished.size());
    assertTrue(unfinished.containsAll(Arrays.asList(FILE_A, FILE_B, TREE_CHILD_1)));
  }

  @Test
  public void getUploadLocalUrisByIds_returnsUploadsOnly() {
    long upload = insertUpload(FILE_A);
    long download = insertDownload(DOWNLOAD_TARGET);

    List<String> uris = dao.getUploadLocalUrisByIds(Arrays.asList(upload, download));

    assertEquals(Collections.singletonList(FILE_A), uris);
  }

  // ── coversAny ──────────────────────────────────────────────────────────────

  @Test
  public void coversAny_matchesFileItselfAndTreeChildrenButNotSiblingTrees() {
    assertTrue(UploadSourceGrants.coversAny(FILE_A, Collections.singletonList(FILE_A)));
    assertTrue(UploadSourceGrants.coversAny(TREE, Collections.singletonList(TREE_CHILD_1)));
    assertFalse(UploadSourceGrants.coversAny(TREE, Collections.singletonList(TREE_SIBLING_CHILD)));
    assertFalse(UploadSourceGrants.coversAny(FILE_A, Collections.singletonList(FILE_B)));
  }

  // ── release after completion ───────────────────────────────────────────────

  @Test
  public void releaseIfUnused_releasesFileGrantWhenNoUploadNeedsIt() {
    takeRead(FILE_A);
    long id = insertUpload(FILE_A);
    dao.updateStatus(id, "COMPLETED", now());

    UploadSourceGrants.releaseIfUnused(context, dao, FILE_A);

    assertFalse(hasGrant(FILE_A));
  }

  @Test
  public void releaseIfUnused_keepsFileGrantWhileSameFileIsQueuedAgain() {
    takeRead(FILE_A);
    long done = insertUpload(FILE_A);
    dao.updateStatus(done, "COMPLETED", now());
    insertUpload(FILE_A); // queued a second time, still PENDING

    UploadSourceGrants.releaseIfUnused(context, dao, FILE_A);

    assertTrue(hasGrant(FILE_A));
  }

  @Test
  public void releaseIfUnused_keepsTreeGrantUntilLastChildIsDone() {
    takeRead(TREE);
    long first = insertUpload(TREE_CHILD_1);
    long second = insertUpload(TREE_CHILD_2);

    dao.updateStatus(first, "COMPLETED", now());
    UploadSourceGrants.releaseIfUnused(context, dao, TREE_CHILD_1);
    assertTrue("tree grant must survive while a child is pending", hasGrant(TREE));

    dao.updateStatus(second, "COMPLETED", now());
    UploadSourceGrants.releaseIfUnused(context, dao, TREE_CHILD_2);
    assertFalse("tree grant must be released after the last child", hasGrant(TREE));
  }

  @Test
  public void releaseIfUnused_keepsTreeGrantWhileFailedChildCanBeRetried() {
    takeRead(TREE);
    long first = insertUpload(TREE_CHILD_1);
    long second = insertUpload(TREE_CHILD_2);
    dao.updateStatus(first, "COMPLETED", now());
    dao.markFailed(second, "err", now());

    UploadSourceGrants.releaseIfUnused(context, dao, TREE_CHILD_1);

    assertTrue(hasGrant(TREE));
  }

  @Test
  public void releaseIfUnused_doesNotTouchSiblingTreeGrant() {
    takeRead(TREE);
    takeRead(TREE_SIBLING);
    long id = insertUpload(TREE_CHILD_1);
    dao.updateStatus(id, "COMPLETED", now());

    UploadSourceGrants.releaseIfUnused(context, dao, TREE_CHILD_1);

    assertFalse(hasGrant(TREE));
    assertTrue("unrelated grant must stay", hasGrant(TREE_SIBLING));
  }

  @Test
  public void releaseIfUnused_neverReleasesReadWriteGrants() {
    resolver.takePersistableUriPermission(
        Uri.parse(TREE),
        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    long id = insertUpload(TREE_CHILD_1);
    dao.updateStatus(id, "COMPLETED", now());

    UploadSourceGrants.releaseIfUnused(context, dao, TREE_CHILD_1);

    assertTrue("read/write grant (download target or sync folder) must stay", hasGrant(TREE));
  }

  @Test
  public void releaseIfUnused_ignoresUploadsWithoutGrant() {
    long id = insertUpload(FILE_A); // e.g. a share-intent URI that was never persistable
    dao.updateStatus(id, "COMPLETED", now());

    UploadSourceGrants.releaseIfUnused(context, dao, FILE_A); // must not throw

    assertEquals(0, resolver.getPersistedUriPermissions().size());
  }

  // ── cancel ─────────────────────────────────────────────────────────────────

  @Test
  public void cancelAndRelease_cancelsAndReleasesGrantOfCancelledUpload() {
    takeRead(FILE_A);
    long id = insertUpload(FILE_A);

    UploadSourceGrants.cancelAndRelease(context, dao, Collections.singletonList(id), now());

    assertEquals("CANCELLED", dao.getStatus(id));
    assertFalse(hasGrant(FILE_A));
  }

  @Test
  public void cancelAndRelease_keepsTreeGrantWhileOtherChildStillQueued() {
    takeRead(TREE);
    long first = insertUpload(TREE_CHILD_1);
    insertUpload(TREE_CHILD_2);

    UploadSourceGrants.cancelAndRelease(context, dao, Collections.singletonList(first), now());

    assertTrue(hasGrant(TREE));
  }

  @Test
  public void cancelAllAndRelease_releasesGrantsOfAllCancelledUploads() {
    takeRead(FILE_A);
    takeRead(TREE);
    takeRead(DOWNLOAD_TARGET); // read-only grant that no upload uses
    long a = insertUpload(FILE_A);
    long child = insertUpload(TREE_CHILD_1);
    dao.updateStatus(child, "ACTIVE", now());

    UploadSourceGrants.cancelAllAndRelease(context, dao, now());

    assertEquals("CANCELLED", dao.getStatus(a));
    assertEquals("CANCELLED", dao.getStatus(child));
    assertFalse(hasGrant(FILE_A));
    assertFalse(hasGrant(TREE));
    assertTrue("grants not tied to a cancelled upload must stay", hasGrant(DOWNLOAD_TARGET));
  }

  // ── remove from queue ──────────────────────────────────────────────────────

  @Test
  public void deleteAndRelease_removesRowsAndReleasesGrant() {
    takeRead(FILE_A);
    long id = insertUpload(FILE_A);
    dao.markFailed(id, "err", now());

    UploadSourceGrants.deleteAndRelease(context, dao, Collections.singletonList(id));

    assertEquals(0, dao.countAll());
    assertFalse(hasGrant(FILE_A));
  }

  @Test
  public void deleteAndRelease_keepsGrantWhileAnotherUploadOfSameSourceRemains() {
    takeRead(FILE_A);
    long removed = insertUpload(FILE_A);
    insertUpload(FILE_A);

    UploadSourceGrants.deleteAndRelease(context, dao, Collections.singletonList(removed));

    assertEquals(1, dao.countAll());
    assertTrue(hasGrant(FILE_A));
  }

  // ── enqueue race ───────────────────────────────────────────────────────────

  /**
   * Reproduces the race between a completing upload and a new upload from the same tree: the grant
   * was released after the picker result but before the new rows were inserted. Re-taking the
   * grant after the insert, as the enqueue path does under the lock, restores it.
   */
  @Test
  public void retainAfterInsert_restoresGrantReleasedByCompletingUpload() {
    takeRead(TREE);
    long old = insertUpload(TREE_CHILD_1);
    dao.updateStatus(old, "COMPLETED", now());

    // Picker result for the same tree: grant taken again (no-op, already persisted).
    takeRead(TREE);
    // The completing upload releases the grant before the new rows exist.
    UploadSourceGrants.releaseIfUnused(context, dao, TREE_CHILD_1);
    assertFalse(hasGrant(TREE));

    // Enqueue path: insert rows and re-take the grant under the lock.
    synchronized (UploadSourceGrants.lock()) {
      insertUpload(TREE_CHILD_2);
      UploadSourceGrants.retain(context, Uri.parse(TREE));
    }

    assertTrue(hasGrant(TREE));
    // A later completion of an unrelated child must still keep it while CHILD_2 is pending.
    UploadSourceGrants.releaseIfUnused(context, dao, TREE_CHILD_1);
    assertTrue(hasGrant(TREE));
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  // ── per-file grants and the grant budget ───────────────────────────────────

  @Test
  public void retainAll_takesGrantsWhileTheyFitTheBudget() {
    List<Uri> uris = Arrays.asList(Uri.parse(FILE_A), Uri.parse(FILE_B));

    assertTrue(UploadSourceGrants.retainAll(context, uris));

    assertTrue(hasGrant(FILE_A));
    assertTrue(hasGrant(FILE_B));
  }

  @Test
  public void retainAll_skipsSelectionExceedingTheBudget() {
    int tooMany = PersistedGrantBudget.remaining(context) + 1;
    List<Uri> uris = new ArrayList<>(tooMany);
    for (int i = 0; i < tooMany; i++) {
      uris.add(Uri.parse(FILE_A + i));
    }

    assertFalse(UploadSourceGrants.retainAll(context, uris));

    assertTrue(resolver.getPersistedUriPermissions().isEmpty());
  }

  @Test
  public void releaseUnreferenced_dropsReadGrantsWithoutUnfinishedUpload_keepsOthers() {
    takeRead(FILE_A); // leftover, no queue row
    takeRead(FILE_B); // completed upload
    long done = insertUpload(FILE_B);
    dao.updateStatus(done, "COMPLETED", now());
    takeRead(TREE); // folder grant with a pending child upload
    insertUpload(TREE_CHILD_1);
    takeRead(TREE_SIBLING); // folder grant whose only upload failed and may be retried
    long failed = insertUpload(TREE_SIBLING_CHILD);
    dao.markFailed(failed, "err", now());
    takeReadWrite(DOWNLOAD_TARGET); // read/write grants are never touched

    int released = UploadSourceGrants.releaseUnreferenced(context, dao);

    assertEquals(2, released);
    assertFalse(hasGrant(FILE_A));
    assertFalse(hasGrant(FILE_B));
    assertTrue(hasGrant(TREE));
    assertTrue(hasGrant(TREE_SIBLING));
    assertTrue(hasGrant(DOWNLOAD_TARGET));
  }

  private long insertUpload(String localUri) {
    return dao.insert(transfer("UPLOAD", localUri));
  }

  private long insertDownload(String localUri) {
    return dao.insert(transfer("DOWNLOAD", localUri));
  }

  private static PendingTransfer transfer(String type, String localUri) {
    PendingTransfer t = new PendingTransfer();
    t.transferType = type;
    t.localUri = localUri;
    t.remotePath = "smb://server/share/" + Uri.parse(localUri).getLastPathSegment();
    t.connectionId = "conn";
    t.displayName = "file";
    t.fileSize = 1;
    t.status = "PENDING";
    t.maxRetries = 3;
    t.createdAt = now();
    t.updatedAt = now();
    t.batchId = "batch";
    return t;
  }

  private void takeRead(String uri) {
    resolver.takePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
  }

  private void takeReadWrite(String uri) {
    resolver.takePersistableUriPermission(
        Uri.parse(uri),
        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
  }

  private boolean hasGrant(String uri) {
    for (UriPermission p : resolver.getPersistedUriPermissions()) {
      if (p.getUri().toString().equals(uri)) {
        return true;
      }
    }
    return false;
  }

  private static long now() {
    return System.currentTimeMillis();
  }
}
