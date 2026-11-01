/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.service;

import static org.junit.Assert.*;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import de.schliweb.sambalite.ui.utils.ProgressFormat;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/** The service notification and its channel follow the app language (here German). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "de")
public class NotificationLocaleTest {

  private static Notification last(SmbBackgroundService service) {
    NotificationManager nm =
        (NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE);
    List<Notification> all = Shadows.shadowOf(nm).getAllNotifications();
    assertFalse(all.isEmpty());
    return all.get(all.size() - 1);
  }

  @Test
  public void idleNotificationAndChannel_areGerman() {
    SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);
    service.onStartCommand(new Intent(), 0, 1);

    Notification n = last(service);
    assertEquals("SMB-Dienst bereit", n.extras.getString(Notification.EXTRA_TITLE));
    assertEquals("Bereit für Hintergrundvorgänge", n.extras.getString(Notification.EXTRA_TEXT));
    NotificationManager nm =
        (NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE);
    assertEquals(
        "SMB-Hintergrundvorgänge",
        nm.getNotificationChannel("SMB_BACKGROUND_OPERATIONS").getName().toString());
  }

  @Test
  public void runningOperation_showsGermanTitle() {
    SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);
    service.onStartCommand(new Intent(), 0, 1);

    service.startOperation("Deleting: a.txt");

    assertEquals("Löschen…", last(service).extras.getString(Notification.EXTRA_TITLE));
  }

  @Test
  public void progressHeadline_usesTheGivenLabel() {
    ProgressFormat.Result r = ProgressFormat.parse("Downloading: 2/5 - a.txt", 40, "a.txt");

    assertEquals("Herunterladen: 2/5", ProgressFormat.toHeadlineNoPercent(r, "Herunterladen"));
  }
}
