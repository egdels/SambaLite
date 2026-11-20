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

  /** A config and its result, as one line of a summary notification. */
  @VisibleForTesting
  static final class Item {
    @NonNull final SyncConfig config;
    @NonNull final SyncRunResult result;

    Item(@NonNull SyncConfig config, @NonNull SyncRunResult result) {
      this.config = config;
      this.result = result;
    }
  }

  /**
   * The notifications of one run of the worker. The first sync with a problem notifies on its own
   * as before. From the second one of the same kind (problem, works again, overdue) the single
   * notifications are replaced by one that sums them up, so an unreachable server does not notify
   * once per sync: with up to 25 syncs that were 25 notifications within seconds (#40).
   */
  public static final class Batch {
    private final java.util.Map<Decision, java.util.List<Item>> groups =
        new java.util.EnumMap<>(Decision.class);

    /** The kinds whose summary does not show all of its syncs yet. */
    private final java.util.Set<Decision> pending = java.util.EnumSet.noneOf(Decision.class);

    private final java.util.function.LongSupplier clock;
    private long lastSummaryAt;

    public Batch() {
      this(android.os.SystemClock::elapsedRealtime);
    }

    @VisibleForTesting
    Batch(@NonNull java.util.function.LongSupplier clock) {
      this.clock = clock;
    }

    /** Called by the worker after storing a result. */
    public void onResult(
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
      java.util.List<Item> group =
          groups.computeIfAbsent(decision, d -> new java.util.ArrayList<>());
      group.add(new Item(config, result));
      try {
        if (group.size() == 1) {
          show(context, config, result, decision);
          return;
        }
        if (group.size() == 2) {
          cancel(context, group.get(0).config.getId());
        }
        // Android drops updates of an app that come faster than about five per second, and syncs
        // that cannot reach the server fail faster than that. So the summary is updated at most
        // once per interval; finish() shows what is left.
        long now = clock.getAsLong();
        if (group.size() == 2 || now - lastSummaryAt >= SUMMARY_UPDATE_INTERVAL_MS) {
          showSummary(context, decision, group);
          lastSummaryAt = now;
          pending.remove(decision);
        } else {
          pending.add(decision);
        }
      } catch (RuntimeException e) {
        LogUtils.w(TAG, "Could not show the sync notification: " + e.getMessage());
      }
    }

    /**
     * Called by the worker at the end of the run: brings the summaries that were not updated for
     * every sync to their final state.
     */
    public void finish(@NonNull Context context) {
      try {
        // "25 folder syncs have problems" of an earlier run must not stay next to "work again"
        if (groups.containsKey(Decision.RECOVERED)
            && !groups.containsKey(Decision.PROBLEM)
            && !anyProblemLeft(context)) {
          NotificationManagerCompat.from(context).cancel(summaryNotificationId(Decision.PROBLEM));
        }
      } catch (RuntimeException e) {
        LogUtils.w(TAG, "Could not remove the outdated sync notification: " + e.getMessage());
      }
      if (pending.isEmpty()) return;
      long wait = SUMMARY_UPDATE_INTERVAL_MS - (clock.getAsLong() - lastSummaryAt);
      if (wait > 0) {
        try {
          Thread.sleep(Math.min(wait, SUMMARY_UPDATE_INTERVAL_MS));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      try {
        for (Decision decision : pending) {
          showSummary(context, decision, groups.get(decision));
        }
      } catch (RuntimeException e) {
        LogUtils.w(TAG, "Could not show the sync notification: " + e.getMessage());
      }
      pending.clear();
    }
  }

  /** Returns whether the last result of any sync that is not paused is a problem. */
  private static boolean anyProblemLeft(@NonNull Context context) {
    SyncRunHistoryStore store = new SyncRunHistoryStore(context);
    for (SyncConfig config : new SyncRepository(context).getAllEnabledConfigs()) {
      java.util.List<SyncRunResult> history = store.getHistory(config.getId());
      for (SyncRunResult run : history) {
        if (!run.getStatus().isRun()) continue;
        if (run.getStatus().isProblem()) return true;
        break;
      }
    }
    return false;
  }

  /** The least time between two updates of a summary notification. */
  @VisibleForTesting static final long SUMMARY_UPDATE_INTERVAL_MS = 1000;

  /** Notifies about a single result that is not part of a run over several syncs. */
  public static void onResult(
      @NonNull Context context,
      @NonNull SyncRunHistoryStore store,
      @NonNull SyncConfig config,
      @NonNull SyncRunResult result,
      @Nullable SyncRunResult previousRun) {
    new Batch().onResult(context, store, config, result, previousRun);
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

  /** The ID of the notification that sums up several syncs of one kind. */
  @VisibleForTesting
  static int summaryNotificationId(@NonNull Decision decision) {
    // Below the range of the single notifications (base + up to 0x0FFF)
    return NOTIFICATION_ID_BASE - 1 - decision.ordinal();
  }

  /** "25 folder syncs have problems". */
  @VisibleForTesting
  @NonNull
  static String summaryTitle(@NonNull Context context, @NonNull Decision decision, int count) {
    int res;
    switch (decision) {
      case RECOVERED:
        res = R.plurals.sync_notify_many_recovered_title;
        break;
      case OVERDUE:
        res = R.plurals.sync_notify_many_overdue_title;
        break;
      default:
        res = R.plurals.sync_notify_many_problems_title;
        break;
    }
    return context.getResources().getQuantityString(res, count, count);
  }

  /**
   * The names of the syncs, at most {@link #SUMMARY_NAMES} of them and the number of the rest. If
   * all of them failed for the same reason, the reason comes first: "Server not reachable: Photos,
   * Documents, Music, 22 more".
   */
  @VisibleForTesting
  @NonNull
  static String summaryText(
      @NonNull Context context, @NonNull Decision decision, @NonNull java.util.List<Item> items) {
    java.util.List<String> parts = new java.util.ArrayList<>();
    int named = Math.min(items.size(), SUMMARY_NAMES);
    for (int i = 0; i < named; i++) {
      parts.add(SyncStatusTexts.configName(items.get(i).config));
    }
    int more = items.size() - named;
    if (more > 0) {
      parts.add(
          context.getResources().getQuantityString(R.plurals.monitor_banner_more, more, more));
    }
    String names = String.join(", ", parts);
    if (decision != Decision.PROBLEM) return names;

    SyncErrorCategory common = null;
    for (Item item : items) {
      if (item.result.getStatus() != SyncRunResult.Status.FAILED) return names;
      SyncErrorCategory category = item.result.getFailureCategory();
      if (category == null || (common != null && common != category)) return names;
      common = category;
    }
    if (common == null) return names;
    return context.getString(
        R.string.sync_notify_many_cause, SyncStatusTexts.failureLabel(context, common), names);
  }

  /** How many syncs a summary notification names. */
  @VisibleForTesting static final int SUMMARY_NAMES = 3;

  /** Shows or updates the notification that sums up the syncs of one kind of this run. */
  private static void showSummary(
      @NonNull Context context, @NonNull Decision decision, @NonNull java.util.List<Item> items) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
      return;
    }
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return;
    ensureChannel(context);
    String text = summaryText(context, decision, items);
    int id = summaryNotificationId(decision);
    PendingIntent contentIntent =
        TaskStackBuilder.create(context)
            .addNextIntentWithParentStack(
                de.schliweb.sambalite.ui.SystemMonitorActivity.createIntent(context))
            .getPendingIntent(id, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    NotificationCompat.Builder builder =
        new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(summaryTitle(context, decision, items.size()))
            .setContentText(text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            // The first sync of this kind has already notified with sound; this replaces it
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS);
    NotificationManagerCompat.from(context).notify(id, builder.build());
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
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return;
    NotificationManagerCompat manager = NotificationManagerCompat.from(context);
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
