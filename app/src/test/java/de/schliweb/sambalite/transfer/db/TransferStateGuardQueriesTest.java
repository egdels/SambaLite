/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.transfer.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Tests the guarded state changes the worker uses: a transfer read into the worker's work list is
 * only started while it is still queued, and only marked completed while it is still running.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TransferStateGuardQueriesTest {

  private TransferDatabase db;
  private PendingTransferDao dao;

  @Before
  public void setUp() {
    Context context = ApplicationProvider.getApplicationContext();
    db =
        Room.inMemoryDatabaseBuilder(context, TransferDatabase.class)
            .allowMainThreadQueries()
            .build();
    dao = db.pendingTransferDao();
  }

  @After
  public void tearDown() {
    db.close();
  }

  private long insert(String status, int retryCount) {
    PendingTransfer t = new PendingTransfer();
    t.transferType = "UPLOAD";
    t.remotePath = "Uploads/a.jpg";
    t.status = status;
    t.retryCount = retryCount;
    t.maxRetries = 3;
    t.connectionId = "c1";
    t.displayName = "a.jpg";
    t.createdAt = System.currentTimeMillis();
    t.updatedAt = t.createdAt;
    return dao.insert(t);
  }

  @Test
  public void markActiveIfQueued_claimsPendingAndRetryableFailed() {
    long pending = insert("PENDING", 0);
    long failed = insert("FAILED", 1);

    assertEquals(1, dao.markActiveIfQueued(pending, 1L));
    assertEquals(1, dao.markActiveIfQueued(failed, 1L));
    assertEquals("ACTIVE", dao.getStatus(pending));
    assertEquals("ACTIVE", dao.getStatus(failed));
  }

  @Test
  public void markActiveIfQueued_doesNotReviveCancelledOrRemovedTransfers() {
    long pending = insert("PENDING", 0);
    long removed = insert("PENDING", 0);
    // The user cancels one and removes the other while the worker holds both in its work list
    dao.cancelByIds(Collections.singletonList(pending), 1L);
    dao.deleteByIds(Collections.singletonList(removed));

    assertEquals(0, dao.markActiveIfQueued(pending, 2L));
    assertEquals(0, dao.markActiveIfQueued(removed, 2L));
    assertEquals("CANCELLED", dao.getStatus(pending));
    assertNull(dao.getStatus(removed));
  }

  @Test
  public void markActiveIfQueued_skipsCompletedAndExhaustedFailed() {
    long completed = insert("COMPLETED", 0);
    long exhausted = insert("FAILED", 3);

    assertEquals(0, dao.markActiveIfQueued(completed, 1L));
    assertEquals(0, dao.markActiveIfQueued(exhausted, 1L));
  }

  @Test
  public void markCompletedIfActive_completesOnlyRunningTransfers() {
    long running = insert("ACTIVE", 0);
    long paused = insert("ACTIVE", 0);
    long cancelled = insert("ACTIVE", 0);
    // The worker was stopped mid-transfer: the transfer goes back to PENDING
    dao.updateStatusIfActive(paused, "PENDING", 1L);
    dao.cancelByIds(Collections.singletonList(cancelled), 1L);

    assertEquals(1, dao.markCompletedIfActive(running, 2L));
    assertEquals(0, dao.markCompletedIfActive(paused, 2L));
    assertEquals(0, dao.markCompletedIfActive(cancelled, 2L));
    assertEquals("COMPLETED", dao.getStatus(running));
    assertEquals("PENDING", dao.getStatus(paused));
    assertEquals("CANCELLED", dao.getStatus(cancelled));
  }

  @Test
  public void resetToPendingByIds_retriesOnlyFailedTransfers() {
    long failed = insert("FAILED", 3);
    long active = insert("ACTIVE", 0);
    long completed = insert("COMPLETED", 0);
    long pending = insert("PENDING", 1);

    dao.resetToPendingByIds(java.util.Arrays.asList(failed, active, completed, pending), 2L);

    assertEquals("PENDING", dao.getStatus(failed));
    // A running transfer is not started a second time, a completed one not transferred again
    assertEquals("ACTIVE", dao.getStatus(active));
    assertEquals("COMPLETED", dao.getStatus(completed));
    assertEquals("PENDING", dao.getStatus(pending));
  }

  @Test
  public void cancelByIds_leavesCompletedTransfersAlone() {
    long completed = insert("COMPLETED", 0);
    long pending = insert("PENDING", 0);
    long active = insert("ACTIVE", 0);
    long failed = insert("FAILED", 1);

    dao.cancelByIds(java.util.Arrays.asList(completed, pending, active, failed), 2L);

    assertEquals("COMPLETED", dao.getStatus(completed));
    assertEquals("CANCELLED", dao.getStatus(pending));
    assertEquals("CANCELLED", dao.getStatus(active));
    assertEquals("CANCELLED", dao.getStatus(failed));
  }
}
