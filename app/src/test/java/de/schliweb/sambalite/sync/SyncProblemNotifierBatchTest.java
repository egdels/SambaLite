/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import androidx.core.app.NotificationCompat;
import androidx.test.core.app.ApplicationProvider;
import de.schliweb.sambalite.ui.monitor.SyncStatusTexts;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowNotificationManager;

/** Tests that several syncs with the same problem in one run share one notification (#40). */
@RunWith(RobolectricTestRunner.class)
public class SyncProblemNotifierBatchTest {

  private Context context;
  private SyncRunHistoryStore store;
  private ShadowNotificationManager notifications;

  @Before
  public void setUp() {
    Application app = ApplicationProvider.getApplicationContext();
    shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
    context = app;
    store = new SyncRunHistoryStore(context);
    notifications = shadowOf((NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE));
  }

  private static SyncConfig config(String name) {
    SyncConfig config = new SyncConfig();
    config.setId(name);
    config.setLocalFolderDisplayName(name);
    config.setRemotePath("remote/" + name);
    config.setEnabled(true);
    config.setIntervalMinutes(0);
    return config;
  }

  private static SyncRunResult failed(String id, SyncErrorCategory category) {
    return SyncRunResult.notStarted(id, SyncRunResult.Status.FAILED, "x", category, 1);
  }

  private static SyncRunResult completed(String id) {
    return SyncRunResult.notStarted(id, SyncRunResult.Status.COMPLETED, null, null, 2);
  }

  private void fail(SyncProblemNotifier.Batch batch, String name, SyncErrorCategory category) {
    batch.onResult(context, store, config(name), failed(name, category), null);
  }

  private static String title(Notification n) {
    return String.valueOf(n.extras.getCharSequence(NotificationCompat.EXTRA_TITLE));
  }

  private static String text(Notification n) {
    return String.valueOf(n.extras.getCharSequence(NotificationCompat.EXTRA_TEXT));
  }

  private Notification summary(SyncProblemNotifier.Decision decision) {
    return notifications.getNotification(SyncProblemNotifier.summaryNotificationId(decision));
  }

  @Test
  public void singleProblemNotifiesOnItsOwn() {
    SyncProblemNotifier.Batch batch = new SyncProblemNotifier.Batch();

    fail(batch, "Photos", SyncErrorCategory.NETWORK);

    assertEquals(1, notifications.size());
    assertNotNull(notifications.getNotification(SyncProblemNotifier.notificationId("Photos")));
    assertNull(summary(SyncProblemNotifier.Decision.PROBLEM));
  }

  @Test
  public void fastUpdatesAreHeldBackUntilTheRunEnds() {
    long[] now = {10_000};
    SyncProblemNotifier.Batch batch = new SyncProblemNotifier.Batch(() -> now[0]);
    for (int i = 0; i < 25; i++) {
      fail(batch, "S" + i, SyncErrorCategory.NETWORK);
    }

    // All 25 failed within the same instant: only the first update of the summary was shown
    assertEquals(
        "2 folder syncs have problems", title(summary(SyncProblemNotifier.Decision.PROBLEM)));

    now[0] += SyncProblemNotifier.SUMMARY_UPDATE_INTERVAL_MS;
    batch.finish(context);

    assertEquals(1, notifications.size());
    assertEquals(
        "25 folder syncs have problems", title(summary(SyncProblemNotifier.Decision.PROBLEM)));
  }

  @Test
  public void severalProblemsOfOneRunShareOneNotification() {
    long[] now = {10_000};
    SyncProblemNotifier.Batch batch = new SyncProblemNotifier.Batch(() -> now[0]);
    for (int i = 0; i < 25; i++) {
      now[0] += SyncProblemNotifier.SUMMARY_UPDATE_INTERVAL_MS;
      fail(batch, "S" + i, SyncErrorCategory.NETWORK);
    }

    assertEquals(1, notifications.size());
    Notification n = summary(SyncProblemNotifier.Decision.PROBLEM);
    assertNotNull(n);
    assertEquals("25 folder syncs have problems", title(n));
    assertEquals(
        SyncStatusTexts.failureLabel(context, SyncErrorCategory.NETWORK) + ": S0, S1, S2, 22 more",
        text(n));
  }

  @Test
  public void differentCausesAreNotNamedAsOne() {
    SyncProblemNotifier.Batch batch = new SyncProblemNotifier.Batch();
    fail(batch, "A", SyncErrorCategory.NETWORK);
    fail(batch, "B", SyncErrorCategory.AUTH);

    assertEquals("2 folder syncs have problems", title(summary(SyncProblemNotifier.Decision.PROBLEM)));
    assertEquals("A, B", text(summary(SyncProblemNotifier.Decision.PROBLEM)));
  }

  @Test
  public void recoveredSyncsAreSummedUpSeparatelyFromProblems() {
    SyncProblemNotifier.Batch batch = new SyncProblemNotifier.Batch();
    fail(batch, "Broken", SyncErrorCategory.NETWORK);
    for (String name : new String[] {"A", "B", "C"}) {
      batch.onResult(
          context, store, config(name), completed(name), failed(name, SyncErrorCategory.NETWORK));
    }
    batch.finish(context);

    assertEquals(2, notifications.size());
    assertNotNull(notifications.getNotification(SyncProblemNotifier.notificationId("Broken")));
    Notification n = summary(SyncProblemNotifier.Decision.RECOVERED);
    assertEquals("3 folder syncs work again", title(n));
    assertEquals("A, B, C", text(n));
  }

  @Test
  public void outdatedProblemSummaryGoesWhenAllSyncsWorkAgain() {
    SyncRepository repository = new SyncRepository(context);
    String[] names = {"A", "B", "C"};
    SyncProblemNotifier.Batch failing = new SyncProblemNotifier.Batch();
    for (String name : names) {
      SyncConfig config = config(name);
      repository.saveSyncConfig(config);
      store.record(failed(name, SyncErrorCategory.NETWORK));
      failing.onResult(context, store, config, failed(name, SyncErrorCategory.NETWORK), null);
    }
    failing.finish(context);
    assertNotNull(summary(SyncProblemNotifier.Decision.PROBLEM));

    // Only A works again: the other two still have the problem
    SyncProblemNotifier.Batch first = new SyncProblemNotifier.Batch();
    store.record(completed("A"));
    first.onResult(
        context, store, config("A"), completed("A"), failed("A", SyncErrorCategory.NETWORK));
    first.finish(context);
    assertNotNull(summary(SyncProblemNotifier.Decision.PROBLEM));

    SyncProblemNotifier.Batch rest = new SyncProblemNotifier.Batch();
    for (String name : new String[] {"B", "C"}) {
      store.record(completed(name));
      rest.onResult(
          context, store, config(name), completed(name), failed(name, SyncErrorCategory.NETWORK));
    }
    rest.finish(context);

    assertNull(summary(SyncProblemNotifier.Decision.PROBLEM));
    assertNotNull(summary(SyncProblemNotifier.Decision.RECOVERED));
  }

  @Test
  public void mutedSyncIsLeftOut() {
    store.setMuted("B", true);
    SyncProblemNotifier.Batch batch = new SyncProblemNotifier.Batch();
    fail(batch, "A", SyncErrorCategory.NETWORK);
    fail(batch, "B", SyncErrorCategory.NETWORK);

    assertEquals(1, notifications.size());
    assertNotNull(notifications.getNotification(SyncProblemNotifier.notificationId("A")));
  }

  @Test
  public void aNewRunStartsWithASingleNotificationAgain() {
    fail(new SyncProblemNotifier.Batch(), "A", SyncErrorCategory.NETWORK);
    fail(new SyncProblemNotifier.Batch(), "B", SyncErrorCategory.NETWORK);

    assertEquals(2, notifications.size());
    assertNull(summary(SyncProblemNotifier.Decision.PROBLEM));
  }
}
