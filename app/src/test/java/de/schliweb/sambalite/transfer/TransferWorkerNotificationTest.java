/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.robolectric.Shadows.shadowOf;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import androidx.test.core.app.ApplicationProvider;
import androidx.work.testing.TestWorkerBuilder;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.ui.TransferQueueActivity;
import java.util.concurrent.Executors;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The transfer notification follows the app language and opens the transfer queue. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TransferWorkerNotificationTest {

  @Test
  @Config(qualifiers = "de")
  public void foregroundNotification_isLocalizedAndOpensTheQueue() {
    Context context = ApplicationProvider.getApplicationContext();
    TransferWorker worker =
        TestWorkerBuilder.from(context, TransferWorker.class, Executors.newSingleThreadExecutor())
            .build();

    Notification n = worker.getForegroundInfo().getNotification();

    assertEquals("Übertragung läuft …", n.extras.getString(Notification.EXTRA_TITLE));
    assertEquals(
        context.getString(R.string.transfer_queue_title),
        context
            .getSystemService(NotificationManager.class)
            .getNotificationChannel("TRANSFER_QUEUE")
            .getName()
            .toString());
    PendingIntent tap = n.contentIntent;
    assertNotNull(tap);
    Intent opened = shadowOf(tap).getSavedIntent();
    assertEquals(TransferQueueActivity.class.getName(), opened.getComponent().getClassName());
  }
}
