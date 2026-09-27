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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.work.Configuration;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.testing.SynchronousExecutor;
import androidx.work.testing.WorkManagerTestInitHelper;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests the threading contract of {@link TransferWorker#enqueueQueueProcessing(Context)}. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TransferWorkerEnqueueTest {

  private Context context;
  private ExecutorService background;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    Configuration config =
        new Configuration.Builder().setExecutor(new SynchronousExecutor()).build();
    WorkManagerTestInitHelper.initializeTestWorkManager(context, config);
    background = Executors.newSingleThreadExecutor();
  }

  @After
  public void tearDown() {
    background.shutdownNow();
    // Close the test WorkManager's in-memory database, otherwise CloseGuard reports a leak
    WorkManagerTestInitHelper.closeWorkDatabase();
  }

  /** The method blocks on the WorkManager state query, so it must refuse the main thread. */
  @Test
  public void enqueueQueueProcessing_rejectsMainThread() {
    // Robolectric runs the test body on the main looper thread
    assertThrows(
        IllegalStateException.class, () -> TransferWorker.enqueueQueueProcessing(context));
  }

  /** From a background thread the call succeeds and enqueues the unique work. */
  @Test
  public void enqueueQueueProcessing_enqueuesFromBackgroundThread() throws Exception {
    background.submit(() -> TransferWorker.enqueueQueueProcessing(context)).get(10, TimeUnit.SECONDS);

    List<WorkInfo> infos =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(TransferWorker.WORK_NAME)
            .get(10, TimeUnit.SECONDS);
    assertNotNull(infos);
    assertEquals(1, infos.size());
  }
}
