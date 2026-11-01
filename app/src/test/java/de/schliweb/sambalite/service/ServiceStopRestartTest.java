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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * A stop request must not tear down a service start that is still pending, and a rotation must not
 * stop the service at all (the app crashed with ForegroundServiceDidNotStartInTimeException).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ServiceStopRestartTest {

  @Test
  public void actionStop_stopsOnlyUpToItsOwnStartId() {
    SmbBackgroundService service = spy(Robolectric.setupService(SmbBackgroundService.class));

    service.onStartCommand(new Intent(SmbBackgroundService.ACTION_STOP), 0, 7);

    verify(service).stopSelf(7);
    verify(service, never()).stopSelf();
  }

  private static BackgroundSmbManager connectedManager(Context ctx) {
    when(ctx.getApplicationContext()).thenReturn(ctx);
    ArgumentCaptor<ServiceConnection> conn = ArgumentCaptor.forClass(ServiceConnection.class);
    doReturn(true).when(ctx).bindService(any(Intent.class), conn.capture(), anyInt());
    BackgroundSmbManager mgr = new BackgroundSmbManager(ctx);
    SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);
    conn.getValue()
        .onServiceConnected(
            new ComponentName("de.schliweb.sambalite", "SmbBackgroundService"),
            new SmbBackgroundService.LocalBinder(service));
    return mgr;
  }

  private static int stopIntentsSent(Context ctx) {
    ArgumentCaptor<Intent> sent = ArgumentCaptor.forClass(Intent.class);
    verify(ctx, atLeast(0)).startForegroundService(sent.capture());
    int n = 0;
    for (Intent i : sent.getAllValues()) {
      if (SmbBackgroundService.ACTION_STOP.equals(i.getAction())) n++;
    }
    return n;
  }

  @Test
  public void activityStoppedForConfigurationChange_keepsServiceRunning() {
    Context ctx = mock(Context.class);
    BackgroundSmbManager mgr = connectedManager(ctx);
    mgr.onActivityStarted();

    mgr.onActivityStopped(true);

    org.junit.Assert.assertEquals(0, stopIntentsSent(ctx));
    org.junit.Assert.assertTrue(mgr.isServiceConnected());
  }

  @Test
  public void activityStoppedForGood_stopsService() {
    Context ctx = mock(Context.class);
    BackgroundSmbManager mgr = connectedManager(ctx);
    mgr.onActivityStarted();

    mgr.onActivityStopped(false);

    org.junit.Assert.assertEquals(1, stopIntentsSent(ctx));
    verify(ctx).unbindService(any(ServiceConnection.class));
  }
}
