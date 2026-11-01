/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui.controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;
import de.schliweb.sambalite.data.model.SmbFileItem;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * Listeners update views, so they must be called on the main thread even when an operation (the
 * batch delete) reports its end from a worker thread.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = de.schliweb.sambalite.SambaLiteApp.class)
public class FileOperationListenerThreadTest {

  @Test
  public void completionFromWorkerThread_isDeliveredOnMainThread() throws Exception {
    FileOperationsController controller =
        new FileOperationsController(
            ApplicationProvider.getApplicationContext(), null, null, null, null);
    AtomicReference<Thread> calledOn = new AtomicReference<>();
    controller.addListener(
        new FileOperationsController.FileOperationListener() {
          @Override
          public void onFileOperationStarted(@NonNull String type, @NonNull SmbFileItem file) {}

          @Override
          public void onFileOperationCompleted(
              @NonNull String type,
              @NonNull SmbFileItem file,
              boolean success,
              @NonNull String message) {
            calledOn.set(Thread.currentThread());
          }

          @Override
          public void onFileOperationProgress(
              @NonNull String type,
              @NonNull SmbFileItem file,
              int progress,
              @NonNull String message) {}
        });

    Thread worker =
        new Thread(() -> controller.notifyOperationCompleted("delete", null, true, "done"));
    worker.start();
    worker.join();
    assertNull("Not called on the worker thread", calledOn.get());

    ShadowLooper.idleMainLooper();
    assertEquals(Looper.getMainLooper().getThread(), calledOn.get());
  }
}
