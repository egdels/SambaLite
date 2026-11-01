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

import android.content.Context;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The queue list shows open transfers in run order and completed ones newest first, so that the
 * 500-row limit drops the oldest completed transfers rather than arbitrary ones.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class QueueListOrderTest {

  @Rule public InstantTaskExecutorRule instantTasks = new InstantTaskExecutorRule();

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

  private void insert(String name, String status, int sortOrder, long updatedAt) {
    PendingTransfer t = new PendingTransfer();
    t.transferType = "UPLOAD";
    t.remotePath = "Q/" + name;
    t.displayName = name;
    t.connectionId = "c1";
    t.status = status;
    t.maxRetries = 3;
    t.sortOrder = sortOrder;
    t.createdAt = 1;
    t.updatedAt = updatedAt;
    dao.insert(t);
  }

  @Test
  public void openInRunOrder_completedNewestFirst() {
    insert("done-old-first-of-batch", "COMPLETED", 0, 1_000);
    insert("done-new-last-of-batch", "COMPLETED", 9, 3_000);
    insert("done-middle", "COMPLETED", 3, 2_000);
    insert("pending-2", "PENDING", 2, 10);
    insert("pending-1", "PENDING", 1, 20);
    insert("running", "ACTIVE", 5, 30);

    AtomicReference<List<PendingTransfer>> rows = new AtomicReference<>();
    dao.observeActiveTransfers(0).observeForever(rows::set);

    List<String> names = new ArrayList<>();
    for (PendingTransfer t : rows.get()) names.add(t.displayName);
    assertEquals(
        Arrays.asList(
            "running",
            "pending-1",
            "pending-2",
            "done-new-last-of-batch",
            "done-middle",
            "done-old-first-of-batch"),
        names);
  }
}
