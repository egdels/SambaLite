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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Handles the "Mute" action of a sync problem notification: no more notifications for it. */
public class SyncNotificationActionReceiver extends BroadcastReceiver {

  static final String ACTION_MUTE = "de.schliweb.sambalite.action.MUTE_SYNC_NOTIFICATIONS";
  static final String EXTRA_CONFIG_ID = "config_id";

  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null || !ACTION_MUTE.equals(intent.getAction())) return;
    String configId = intent.getStringExtra(EXTRA_CONFIG_ID);
    if (configId == null || configId.isEmpty()) return;
    new SyncRunHistoryStore(context).setMuted(configId, true);
    SyncProblemNotifier.cancel(context, configId);
  }
}
