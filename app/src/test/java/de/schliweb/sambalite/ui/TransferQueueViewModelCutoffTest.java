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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.Observer;
import androidx.test.core.app.ApplicationProvider;
import de.schliweb.sambalite.transfer.db.PendingTransfer;
import de.schliweb.sambalite.transfer.db.PendingTransferDao;
import de.schliweb.sambalite.transfer.db.TransferDatabase;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Completed transfers age out of the queue list while the screen stays open. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TransferQueueViewModelCutoffTest {

  @Rule public InstantTaskExecutorRule instantTasks = new InstantTaskExecutorRule();

  private static boolean containsId(List<PendingTransfer> list, long id) {
    if (list == null) return false;
    for (PendingTransfer t : list) {
      if (t.id == id) return true;
    }
    return false;
  }

  @Test
  public void refreshCompletedCutoff_dropsTransfersCompletedMoreThanAnHourAgo() throws Exception {
    Application app = ApplicationProvider.getApplicationContext();
    PendingTransferDao dao = TransferDatabase.getInstance(app).pendingTransferDao();
    PendingTransfer t = new PendingTransfer();
    t.transferType = "UPLOAD";
    t.remotePath = "Uploads/aging.jpg";
    t.displayName = "aging.jpg";
    t.connectionId = "c1";
    t.status = "COMPLETED";
    t.maxRetries = 3;
    t.createdAt = System.currentTimeMillis();
    // Completed just under an hour ago: visible now, gone in two seconds
    t.updatedAt =
        System.currentTimeMillis() - TransferQueueViewModel.COMPLETED_VISIBLE_MILLIS + 1500;
    ExecutorService io = Executors.newSingleThreadExecutor();
    long id;
    try {
      id = io.submit(() -> dao.insert(t)).get(10, TimeUnit.SECONDS);
    } finally {
      io.shutdown();
    }

    TransferQueueViewModel vm = new TransferQueueViewModel(app);
    AtomicReference<List<PendingTransfer>> latest = new AtomicReference<>();
    Observer<List<PendingTransfer>> observer = latest::set;
    vm.getActiveTransfers().observeForever(observer);
    try {
      assertNotNull(latest.get());
      assertTrue(containsId(latest.get(), id));

      Thread.sleep(2000);
      vm.refreshCompletedCutoff();

      assertFalse(containsId(latest.get(), id));
    } finally {
      vm.getActiveTransfers().removeObserver(observer);
      // The app database is a process-wide singleton: leave nothing behind for other tests
      ExecutorService cleanup = Executors.newSingleThreadExecutor();
      try {
        cleanup
            .submit(() -> dao.deleteByIds(java.util.Collections.singletonList(id)))
            .get(10, TimeUnit.SECONDS);
      } finally {
        cleanup.shutdown();
      }
    }
  }
}
