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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
 * Tests which transfers count as open for duplicate detection: pending, running, and failed with
 * retries left, because the worker picks those up again by itself.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class OpenTransferQueriesTest {

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

  private void insert(String type, String path, String status, int retryCount, String connection) {
    PendingTransfer t = new PendingTransfer();
    t.transferType = type;
    t.remotePath = path;
    t.status = status;
    t.retryCount = retryCount;
    t.maxRetries = 3;
    t.connectionId = connection;
    t.displayName = path;
    t.createdAt = System.currentTimeMillis();
    t.updatedAt = t.createdAt;
    dao.insert(t);
  }

  @Test
  public void failedWithRetriesLeft_isOpen() {
    insert("UPLOAD", "Uploads/a.jpg", "FAILED", 1, "c1");

    assertEquals(1, dao.countActiveForPath("Uploads/a.jpg"));
    assertTrue(dao.getActiveRemotePaths().contains("Uploads/a.jpg"));
    assertTrue(dao.getOpenUploadRemotePaths("c1").contains("Uploads/a.jpg"));
  }

  @Test
  public void failedWithoutRetriesLeft_completedAndCancelled_areNotOpen() {
    insert("UPLOAD", "Uploads/a.jpg", "FAILED", 3, "c1");
    insert("UPLOAD", "Uploads/b.jpg", "COMPLETED", 0, "c1");
    insert("UPLOAD", "Uploads/c.jpg", "CANCELLED", 0, "c1");

    assertEquals(0, dao.countActiveForPath("Uploads/a.jpg"));
    assertTrue(dao.getActiveRemotePaths().isEmpty());
    assertTrue(dao.getOpenUploadRemotePaths("c1").isEmpty());
  }

  @Test
  public void pendingAndActive_areOpen() {
    insert("UPLOAD", "Uploads/a.jpg", "PENDING", 0, "c1");
    insert("UPLOAD", "Uploads/b.jpg", "ACTIVE", 0, "c1");

    List<String> open = dao.getOpenUploadRemotePaths("c1");
    assertEquals(2, open.size());
    assertTrue(open.contains("Uploads/a.jpg"));
    assertTrue(open.contains("Uploads/b.jpg"));
  }

  @Test
  public void openUploadPaths_onlyUploadsOfThatConnection() {
    insert("UPLOAD", "Uploads/a.jpg", "PENDING", 0, "c1");
    insert("UPLOAD", "Uploads/b.jpg", "PENDING", 0, "c2");
    insert("DOWNLOAD", "Uploads/c.jpg", "PENDING", 0, "c1");

    List<String> open = dao.getOpenUploadRemotePaths("c1");
    assertEquals(List.of("Uploads/a.jpg"), open);
    assertFalse(open.contains("Uploads/c.jpg"));
  }
}
