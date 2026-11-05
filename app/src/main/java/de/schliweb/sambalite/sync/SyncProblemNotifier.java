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

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.TaskStackBuilder;
import androidx.core.content.ContextCompat;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.ui.monitor.SyncStatusDetailActivity;
import de.schliweb.sambalite.ui.monitor.SyncStatusTexts;
import de.schliweb.sambalite.util.LogUtils;

/**
 * Tells the user when the state of a sync changes (#50): when it starts to fail or to have errors,
 * when it works again, and once when it becomes overdue while waiting for WiFi. A sync that fails
 * the same way run after run does not notify again, so the notification means something.
 */
public final class SyncProblemNotifier {

  private static final String TAG = "SyncProblemNotifier";
  static final String CHANNEL_ID = "FOLDER_SYNC_PROBLEMS";
  private static final int NOTIFICATION_ID_BASE = 3000;

  /** What to tell the user about a new result. */
  @VisibleForTesting
  enum Decision {
    NONE,
    PROBLEM,
    RECOVERED,
    OVERDUE
  }

  private SyncProblemNotifier() {}

  /** Called by the worker after storing a result. */
  public static void onResult(
      @NonNull Context context,
      @NonNull SyncRunHistoryStore store,
      @NonNull SyncConfig config,
      @NonNull SyncRunResult result,
      @Nullable SyncRunResult previousRun) {
    if (store.isMuted(config.getId())) return;
    Decision decision =
        decide(
            result,
            previousRun,
            SyncHealth.isOverdue(config, System.currentTimeMillis()),
            store.isOverdueNotified(config.getId()));
    if (decision == Decision.NONE) return;
    if (decision == Decision.OVERDUE) {
      store.setOverdueNotified(config.getId(), true);
    }
    try {
      show(context, config, result, decision);
    } catch (RuntimeException e) {
      LogUtils.w(TAG, "Could not show the sync notification: " + e.getMessage());
    }
  }

  @VisibleForTesting
  @NonNull
  static Decision decide(
      @NonNull SyncRunResult result,
      @Nullable SyncRunResult previousRun,
      boolean overdue,
      boolean overdueNotified) {
    SyncRunResult.Status status = result.getStatus();
    if (status == SyncRunResult.Status.SKIPPED_NO_WIFI) {
      return overdue && !overdueNotified ? Decision.OVERDUE : Decision.NONE;
    }
    if (status.isProblem()) {
      if (previousRun == null || !previousRun.getStatus().isProblem()) {
        return Decision.PROBLEM;
      }
      boolean sameKind =
          previousRun.getStatus() == status
              && previousRun.getFailureCategory() == result.getFailureCategory();
      return sameKind ? Decision.NONE : Decision.PROBLEM;
    }
    if (status == SyncRunResult.Status.COMPLETED
        && previousRun != null
        && previousRun.getStatus().isProblem()) {
      return Decision.RECOVERED;
    }
    return Decision.NONE;
  }

  /** Removes the notification of a config, e.g. after the user opened its details. */
  public static void cancel(@NonNull Context context, @NonNull String configId) {
    NotificationManagerCompat.from(context).cancel(notificationId(configId));
  }

  static int notificationId(@NonNull String configId) {
    return NOTIFICATION_ID_BASE + (configId.hashCode() & 0x0FFF);
  }

  private static void show(
      @NonNull Context context,
      @NonNull SyncConfig config,
      @NonNull SyncRunResult result,
      @NonNull Decision decision) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
      return;
    }
    NotificationManagerCompat manager = NotificationManagerCompat.from(context);
    if (!manager.areNotificationsEnabled()) return;
    ensureChannel(context);

    String name = SyncStatusTexts.configName(config);
    String title;
    String text;
    switch (decision) {
      case RECOVERED:
        title = context.getString(R.string.sync_notify_recovered_title, name);
        text = SyncStatusTexts.resultSummary(context, result);
        break;
      case OVERDUE:
        title = context.getString(R.string.sync_notify_overdue_title, name);
        text = context.getString(R.string.sync_notify_overdue_text);
        break;
      default:
        title = SyncStatusTexts.problemTitle(context, name, result);
        text = SyncStatusTexts.problemText(context, result);
        break;
    }

    Intent detail = SyncStatusDetailActivity.createIntent(context, config.getId());
    PendingIntent contentIntent =
        TaskStackBuilder.create(context)
            .addNextIntentWithParentStack(detail)
            .getPendingIntent(
                notificationId(config.getId()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

    Intent mute = new Intent(context, SyncNotificationActionReceiver.class);
    mute.setAction(SyncNotificationActionReceiver.ACTION_MUTE);
    mute.putExtra(SyncNotificationActionReceiver.EXTRA_CONFIG_ID, config.getId());
    PendingIntent muteIntent =
        PendingIntent.getBroadcast(
            context,
            notificationId(config.getId()),
            mute,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

    NotificationCompat.Builder builder =
        new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS);
    if (decision != Decision.RECOVERED) {
      builder.addAction(0, context.getString(R.string.sync_notify_mute), muteIntent);
    }
    manager.notify(notificationId(config.getId()), builder.build());
  }

  private static void ensureChannel(@NonNull Context context) {
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) return;
    NotificationChannel channel =
        new NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.sync_notify_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT);
    channel.setDescription(context.getString(R.string.sync_notify_channel_description));
    manager.createNotificationChannel(channel);
  }
}
