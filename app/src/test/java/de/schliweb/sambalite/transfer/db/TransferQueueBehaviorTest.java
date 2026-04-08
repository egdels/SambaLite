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

import static org.junit.Assert.*;

import android.content.Context;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Tests for the Transfer Queue behavior according to the User Guide.
 * Verifies status transitions, retry logic, crash recovery, and cleanup.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TransferQueueBehaviorTest {

  private TransferDatabase db;
  private PendingTransferDao dao;
  private Context context;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    db = Room.inMemoryDatabaseBuilder(context, TransferDatabase.class)
            .allowMainThreadQueries()
            .build();
    dao = db.pendingTransferDao();
  }

  @After
  public void tearDown() {
    if (db != null) {
      db.close();
    }
  }

  /**
   * Verifies the status lifecycle: PENDING -> ACTIVE -> COMPLETED.
   * According to Guide: "Each transfer goes through the following states: PENDING -> ACTIVE -> COMPLETED"
   */
  @Test
  public void testStatusLifecycle_Success() {
    PendingTransfer transfer = createTestTransfer("file1.txt");
    long id = dao.insert(transfer);

    // Initial state: PENDING
    PendingTransfer fetched = dao.getNextPending();
    assertNotNull(fetched);
    assertEquals("PENDING", fetched.status);

    // Transition to ACTIVE
    dao.updateStatus(id, "ACTIVE", System.currentTimeMillis());
    // Note: getNextPending only returns PENDING or FAILED, so we check status directly
    assertEquals("ACTIVE", dao.getStatus(id));

    // Transition to COMPLETED
    dao.updateStatus(id, "COMPLETED", System.currentTimeMillis());
    assertEquals("COMPLETED", dao.getStatus(id));
  }

  /**
   * Verifies the retry logic: FAILED transfers are retried up to 3 times.
   * According to Guide: "Will be retried automatically up to 3 times."
   */
  @Test
  public void testRetryLogic() {
    PendingTransfer transfer = createTestTransfer("retry_file.txt");
    transfer.maxRetries = 3;
    long id = dao.insert(transfer);

    // Attempt 1 fails
    dao.markFailed(id, "Error 1", System.currentTimeMillis());
    PendingTransfer fetched = dao.getNextPending();
    assertNotNull("Should be retryable after 1st failure", fetched);
    assertEquals(1, fetched.retryCount);
    assertEquals("FAILED", fetched.status);

    // Attempt 2 fails
    dao.markFailed(id, "Error 2", System.currentTimeMillis());
    fetched = dao.getNextPending();
    assertNotNull("Should be retryable after 2nd failure", fetched);
    assertEquals(2, fetched.retryCount);

    // Attempt 3 fails
    dao.markFailed(id, "Error 3", System.currentTimeMillis());
    fetched = dao.getNextPending();
    assertNull("Should NOT be retryable after 3rd failure (maxRetries reached)", fetched);
    assertEquals("FAILED", dao.getStatus(id));
  }

  /**
   * Verifies crash recovery: ACTIVE transfers are reset to PENDING on app start.
   * According to Guide: "All ACTIVE transfers are automatically reset to PENDING after an unclean app exit."
   */
  @Test
  public void testCrashRecovery() {
    PendingTransfer transfer = createTestTransfer("crash_file.txt");
    transfer.status = "ACTIVE";
    long id = dao.insert(transfer);

    assertEquals("ACTIVE", dao.getStatus(id));

    // Simulate crash recovery
    int resetCount = dao.resetActiveToRetry(System.currentTimeMillis());
    assertEquals(1, resetCount);

    PendingTransfer recovered = dao.getNextPending();
    assertNotNull(recovered);
    assertEquals("PENDING", recovered.status);
    assertEquals(0, recovered.bytesTransferred);
  }

  @Test
  public void testDuplicateDetection() {
    String remotePath = "/share/duplicate.txt";
    PendingTransfer transfer = createTestTransfer("duplicate.txt");
    transfer.remotePath = remotePath;
    transfer.status = "PENDING";
    long id = dao.insert(transfer);

    int count = dao.countActiveForPath(remotePath);
    assertEquals("Should detect 1 active/pending transfer", 1, count);

    // Test with COMPLETED - should NOT count as active duplicate
    dao.updateStatus(id, "COMPLETED", System.currentTimeMillis());
    count = dao.countActiveForPath(remotePath);
    assertEquals("Should NOT detect completed transfer as active duplicate", 0, count);
  }

  /**
   * Verifies automatic cleanup: transfers older than 7 days are removed.
   * According to Guide: "Completed and cancelled transfers are automatically cleaned up after 7 days."
   */
  @Test
  public void testCleanup() {
    long sevenDaysMillis = 7L * 24 * 60 * 60 * 1000;
    long now = System.currentTimeMillis();
    long eightDaysAgo = now - sevenDaysMillis - 1000;

    // Old completed transfer
    PendingTransfer oldCompleted = createTestTransfer("old_comp.txt");
    oldCompleted.status = "COMPLETED";
    oldCompleted.updatedAt = eightDaysAgo;
    dao.insert(oldCompleted);

    // Old cancelled transfer
    PendingTransfer oldCancelled = createTestTransfer("old_canc.txt");
    oldCancelled.status = "CANCELLED";
    oldCancelled.updatedAt = eightDaysAgo;
    dao.insert(oldCancelled);

    // Recent completed transfer
    PendingTransfer recentCompleted = createTestTransfer("recent.txt");
    recentCompleted.status = "COMPLETED";
    recentCompleted.updatedAt = now;
    dao.insert(recentCompleted);

    int deletedCount = dao.cleanupOld(now - sevenDaysMillis);
    assertEquals(2, deletedCount);
    assertEquals(1, dao.countAll());
  }

  private PendingTransfer createTestTransfer(String name) {
    PendingTransfer t = new PendingTransfer();
    t.transferType = "UPLOAD";
    t.localUri = "content://test/" + name;
    t.remotePath = "/share/" + name;
    t.connectionId = "conn1";
    t.displayName = name;
    t.fileSize = 1000;
    t.status = "PENDING";
    t.createdAt = System.currentTimeMillis();
    t.updatedAt = System.currentTimeMillis();
    t.batchId = "batch1";
    return t;
  }
}
