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
import static org.junit.Assert.assertNotEquals;

import android.app.Application;
import androidx.test.core.app.ApplicationProvider;
import androidx.work.Configuration;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.testing.SynchronousExecutor;
import androidx.work.testing.WorkManagerTestInitHelper;
import de.schliweb.sambalite.transfer.TransferWorker;
import de.schliweb.sambalite.transfer.db.PendingTransfer;
import de.schliweb.sambalite.transfer.db.PendingTransferDao;
import de.schliweb.sambalite.transfer.db.TransferDatabase;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Cancelling selected transfers must not stop the worker that runs the rest of the queue. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TransferQueueViewModelCancelTest {

  private Application app;
  private PendingTransferDao dao;

  @Before
  public void setUp() {
    app = ApplicationProvider.getApplicationContext();
    Configuration config =
        new Configuration.Builder().setExecutor(new SynchronousExecutor()).build();
    WorkManagerTestInitHelper.initializeTestWorkManager(app, config);
    dao = TransferDatabase.getInstance(app).pendingTransferDao();
  }

  private final java.util.List<Long> inserted = new java.util.ArrayList<>();

  @After
  public void tearDown() throws Exception {
    // The app database is a process-wide singleton: leave nothing behind for other test classes
    bg(
        () -> {
          dao.deleteByIds(inserted);
          return null;
        });
    WorkManagerTestInitHelper.closeWorkDatabase();
  }

  /** Room refuses the main thread, on which Robolectric runs the test body. */
  private static <T> T bg(Callable<T> call) throws Exception {
    ExecutorService ex = Executors.newSingleThreadExecutor();
    try {
      return ex.submit(call).get(10, TimeUnit.SECONDS);
    } finally {
      ex.shutdown();
    }
  }

  private long insertPending(String name) throws Exception {
    PendingTransfer t = new PendingTransfer();
    t.transferType = "UPLOAD";
    t.remotePath = "Uploads/" + name;
    t.localUri = "content://test/" + name;
    t.displayName = name;
    t.status = "PENDING";
    t.maxRetries = 3;
    t.connectionId = "c1";
    t.createdAt = System.currentTimeMillis();
    t.updatedAt = t.createdAt;
    long id = bg(() -> dao.insert(t));
    inserted.add(id);
    return id;
  }

  private static void drain(TransferQueueViewModel vm) throws Exception {
    Field f = TransferQueueViewModel.class.getDeclaredField("executor");
    f.setAccessible(true);
    ((ExecutorService) f.get(vm)).submit(() -> {}).get(10, TimeUnit.SECONDS);
  }

  @Test
  public void cancelTransfers_cancelsSelectionButKeepsTheWorker() throws Exception {
    long selected = insertPending("a.jpg");
    long other = insertPending("b.jpg");
    WorkManager wm = WorkManager.getInstance(app);
    wm.enqueueUniqueWork(
            TransferWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            new OneTimeWorkRequest.Builder(TransferWorker.class).build())
        .getResult()
        .get(10, TimeUnit.SECONDS);

    TransferQueueViewModel vm = new TransferQueueViewModel(app);
    vm.cancelTransfers(Collections.singleton(selected));
    drain(vm);

    assertEquals("CANCELLED", bg(() -> dao.getStatus(selected)));
    assertEquals("PENDING", bg(() -> dao.getStatus(other)));
    List<WorkInfo> infos =
        wm.getWorkInfosForUniqueWork(TransferWorker.WORK_NAME).get(10, TimeUnit.SECONDS);
    assertEquals(1, infos.size());
    assertNotEquals(WorkInfo.State.CANCELLED, infos.get(0).getState());
  }
}
