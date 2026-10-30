/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Runs {@link SmbServiceDiscovery} against the device's real NSD stack. The test announces an
 * {@code _smb._tcp} service on port 4455 from the device itself, as Avahi would on a Linux server,
 * and expects the discovery to find and resolve it with the announced port. Needs a device or
 * emulator with an active network.
 */
@RunWith(AndroidJUnit4.class)
public class SmbServiceDiscoveryInstrumentedTest {

  private static final int ANNOUNCED_PORT = 4455;
  private static final long TIMEOUT_S = 20;

  private Context context;
  private NsdManager nsdManager;
  private NsdManager.RegistrationListener registration;
  private SmbServiceDiscovery discovery;

  @Before
  public void setUp() {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
    discovery = new SmbServiceDiscovery(context);
  }

  @After
  public void tearDown() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> discovery.stop());
    if (registration != null) {
      try {
        nsdManager.unregisterService(registration);
      } catch (IllegalArgumentException ignored) {
        // not registered
      }
    }
  }

  @Test
  public void findsAndResolvesAnAnnouncedSmbServiceWithItsPort() throws Exception {
    String registeredName = announce("SambaLite Test " + UUID.randomUUID().toString().substring(0, 8));

    CountDownLatch resolved = new CountDownLatch(1);
    AtomicReference<String> hostName = new AtomicReference<>();
    AtomicReference<String> address = new AtomicReference<>();
    AtomicReference<Integer> port = new AtomicReference<>();
    AtomicReference<String> failure = new AtomicReference<>();

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () ->
                discovery.start(
                    new SmbServiceDiscovery.Listener() {
                      @Override
                      public void onDiscoveryStarted() {}

                      @Override
                      public void onServiceFound(
                          @NonNull String serviceName, String serviceType) {}

                      @Override
                      public void onServiceResolved(
                          @NonNull String serviceName, String host, String addr, int p) {
                        if (serviceName.equals(registeredName)) {
                          hostName.set(host);
                          address.set(addr);
                          port.set(p);
                          resolved.countDown();
                        }
                      }

                      @Override
                      public void onServiceLost(@NonNull String serviceName) {}

                      @Override
                      public void onDiscoveryFinished() {}

                      @Override
                      public void onDiscoveryFailed() {
                        failure.set("discovery failed");
                        resolved.countDown();
                      }
                    }));

    assertTrue(
        "service " + registeredName + " was not resolved",
        resolved.await(TIMEOUT_S, TimeUnit.SECONDS));
    assertEquals(null, failure.get());
    assertEquals(Integer.valueOf(ANNOUNCED_PORT), port.get());
    assertTrue(
        "neither host name nor address: " + hostName.get() + " / " + address.get(),
        hostName.get() != null || address.get() != null);
  }

  /** Announces an SMB service and returns the name NSD registered it under. */
  private String announce(String name) throws InterruptedException {
    NsdServiceInfo info = new NsdServiceInfo();
    info.setServiceName(name);
    info.setServiceType(SmbServiceDiscovery.SERVICE_TYPE);
    info.setPort(ANNOUNCED_PORT);

    CountDownLatch registered = new CountDownLatch(1);
    AtomicReference<String> registeredName = new AtomicReference<>();
    registration =
        new NsdManager.RegistrationListener() {
          @Override
          public void onServiceRegistered(NsdServiceInfo serviceInfo) {
            registeredName.set(serviceInfo.getServiceName());
            registered.countDown();
          }

          @Override
          public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            registered.countDown();
          }

          @Override
          public void onServiceUnregistered(NsdServiceInfo serviceInfo) {}

          @Override
          public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}
        };
    nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
    assertTrue("registration timed out", registered.await(TIMEOUT_S, TimeUnit.SECONDS));
    assertNotNull("registration failed", registeredName.get());
    return registeredName.get();
  }
}
