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

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;

/**
 * Thin wrapper around Android's {@link NsdManager} that discovers SMB services announced via DNS-SD
 * ({@code _smb._tcp}). Discovery only runs after an explicit call to {@link #start} and is stopped
 * automatically after a timeout. Found services are resolved sequentially through a small queue
 * because parallel resolves are not reliable on all API levels.
 *
 * <p>All listener callbacks are delivered on the main thread. Callbacks that arrive after {@link
 * #stop()} are ignored, so late NSD events can neither touch a destroyed UI nor crash the app.
 */
public class SmbServiceDiscovery {

  /** The only DNS-SD service type this component searches for. */
  public static final String SERVICE_TYPE = "_smb._tcp";

  private static final String TAG = "SmbServiceDiscovery";
  private static final long DEFAULT_TIMEOUT_MS = 15_000;

  private final Context appContext;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();

  private NsdManager.DiscoveryListener discoveryListener;
  private Listener listener;
  private int generation = 0;
  private boolean discovering = false;
  private boolean resolveInProgress = false;
  private final Runnable timeoutRunnable = this::onTimeout;

  public SmbServiceDiscovery(@NonNull Context context) {
    this.appContext = context.getApplicationContext();
  }

  /** Returns true if NSD and an active local network (Wi-Fi/Ethernet) are available. */
  public boolean isAvailable() {
    if (appContext.getSystemService(Context.NSD_SERVICE) == null) {
      return false;
    }
    ConnectivityManager cm =
        (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
    if (cm == null) {
      return false;
    }
    try {
      Network active = cm.getActiveNetwork();
      if (active == null) {
        return false;
      }
      NetworkCapabilities caps = cm.getNetworkCapabilities(active);
      return caps != null
          && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
              || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    } catch (Exception e) {
      LogUtils.w(TAG, "Failed to check network availability: " + e.getMessage());
      return false;
    }
  }

  /**
   * Starts a discovery for {@code _smb._tcp} services. Any running discovery is stopped first. The
   * discovery stops automatically after a fixed timeout.
   */
  public void start(@NonNull Listener listener) {
    stop();

    NsdManager nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
    if (nsdManager == null) {
      LogUtils.e(TAG, "NSD system service not available");
      listener.onDiscoveryFailed();
      return;
    }

    this.listener = listener;
    final int gen = ++generation;
    discoveryListener = createDiscoveryListener(gen, nsdManager);

    try {
      LogUtils.i(TAG, "Starting SMB service discovery (" + SERVICE_TYPE + ")");
      nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
      discovering = true;
      mainHandler.postDelayed(timeoutRunnable, DEFAULT_TIMEOUT_MS);
    } catch (Exception e) {
      LogUtils.e(TAG, "Failed to start discovery: " + e.getMessage());
      discoveryListener = null;
      this.listener = null;
      listener.onDiscoveryFailed();
    }
  }

  /** Stops a running discovery. Safe to call multiple times and from any lifecycle state. */
  public void stop() {
    generation++;
    mainHandler.removeCallbacks(timeoutRunnable);
    resolveQueue.clear();
    resolveInProgress = false;

    if (discoveryListener != null && discovering) {
      NsdManager nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
      if (nsdManager != null) {
        try {
          LogUtils.i(TAG, "Stopping SMB service discovery");
          nsdManager.stopServiceDiscovery(discoveryListener);
        } catch (Exception e) {
          // NsdManager throws IllegalArgumentException if the listener is not registered.
          LogUtils.w(TAG, "stopServiceDiscovery failed: " + e.getMessage());
        }
      }
    }
    discovering = false;
    discoveryListener = null;
    listener = null;
  }

  private void onTimeout() {
    LogUtils.i(TAG, "Discovery timeout reached, stopping");
    Listener current = listener;
    stop();
    if (current != null) {
      current.onDiscoveryFinished();
    }
  }

  /** Runs the given action on the main thread if the discovery session is still active. */
  private void postIfCurrent(int gen, @NonNull Runnable action) {
    mainHandler.post(
        () -> {
          if (gen != generation || listener == null) {
            return; // Late callback after stop() - ignore.
          }
          action.run();
        });
  }

  private NsdManager.DiscoveryListener createDiscoveryListener(
      final int gen, final NsdManager nsdManager) {
    return new NsdManager.DiscoveryListener() {
      @Override
      public void onStartDiscoveryFailed(String serviceType, int errorCode) {
        LogUtils.e(TAG, "onStartDiscoveryFailed: errorCode=" + errorCode);
        postIfCurrent(
            gen,
            () -> {
              Listener current = listener;
              discovering = false;
              stop();
              current.onDiscoveryFailed();
            });
      }

      @Override
      public void onStopDiscoveryFailed(String serviceType, int errorCode) {
        LogUtils.w(TAG, "onStopDiscoveryFailed: errorCode=" + errorCode);
      }

      @Override
      public void onDiscoveryStarted(String serviceType) {
        LogUtils.d(TAG, "Discovery started for " + serviceType);
        postIfCurrent(gen, () -> listener.onDiscoveryStarted());
      }

      @Override
      public void onDiscoveryStopped(String serviceType) {
        LogUtils.d(TAG, "Discovery stopped for " + serviceType);
      }

      @Override
      public void onServiceFound(NsdServiceInfo serviceInfo) {
        if (serviceInfo == null || serviceInfo.getServiceName() == null) {
          LogUtils.w(TAG, "Ignoring found service without name");
          return;
        }
        LogUtils.d(TAG, "Service found: " + serviceInfo.getServiceName());
        postIfCurrent(
            gen,
            () -> {
              listener.onServiceFound(serviceInfo.getServiceName(), serviceInfo.getServiceType());
              resolveQueue.add(serviceInfo);
              resolveNext(gen, nsdManager);
            });
      }

      @Override
      public void onServiceLost(NsdServiceInfo serviceInfo) {
        if (serviceInfo == null || serviceInfo.getServiceName() == null) {
          return;
        }
        LogUtils.d(TAG, "Service lost: " + serviceInfo.getServiceName());
        postIfCurrent(gen, () -> listener.onServiceLost(serviceInfo.getServiceName()));
      }
    };
  }

  /** Resolves queued services one at a time; parallel resolves are unreliable on older APIs. */
  @SuppressWarnings("deprecation") // resolveService is the only API available below level 34.
  private void resolveNext(final int gen, final NsdManager nsdManager) {
    if (resolveInProgress || gen != generation) {
      return;
    }
    final NsdServiceInfo next = resolveQueue.poll();
    if (next == null) {
      return;
    }
    resolveInProgress = true;
    LogUtils.d(TAG, "Resolving service: " + next.getServiceName());

    NsdManager.ResolveListener resolveListener =
        new NsdManager.ResolveListener() {
          @Override
          public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
            LogUtils.w(
                TAG,
                "Resolve failed for "
                    + (serviceInfo != null ? serviceInfo.getServiceName() : "?")
                    + ": errorCode="
                    + errorCode);
            postIfCurrent(
                gen,
                () -> {
                  resolveInProgress = false;
                  resolveNext(gen, nsdManager);
                });
          }

          @Override
          public void onServiceResolved(NsdServiceInfo serviceInfo) {
            postIfCurrent(
                gen,
                () -> {
                  resolveInProgress = false;
                  handleResolved(serviceInfo);
                  resolveNext(gen, nsdManager);
                });
          }
        };

    try {
      nsdManager.resolveService(next, resolveListener);
    } catch (Exception e) {
      LogUtils.w(TAG, "resolveService failed: " + e.getMessage());
      resolveInProgress = false;
      resolveNext(gen, nsdManager);
    }
  }

  private void handleResolved(NsdServiceInfo serviceInfo) {
    if (serviceInfo == null || serviceInfo.getServiceName() == null) {
      LogUtils.w(TAG, "Ignoring resolved service without name");
      return;
    }
    String hostName;
    List<InetAddress> addresses;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      hostName =
          android.os.ext.SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 17
              ? serviceInfo.getHostname()
              : null;
      addresses = serviceInfo.getHostAddresses();
      if (hostName == null && !addresses.isEmpty()) {
        hostName = DiscoveredServers.nonResolvingHostName(addresses.get(0));
      }
    } else {
      @SuppressWarnings("deprecation") // getHost is the only accessor below API level 34.
      InetAddress host = serviceInfo.getHost();
      hostName = DiscoveredServers.nonResolvingHostName(host);
      addresses = host != null ? Collections.singletonList(host) : Collections.emptyList();
    }
    String address = DiscoveredServers.preferredAddress(addresses);
    if (hostName == null && address == null) {
      LogUtils.w(TAG, "Resolved service has no usable host: " + serviceInfo.getServiceName());
      return;
    }
    int port = serviceInfo.getPort();
    LogUtils.i(
        TAG,
        "Resolved service: "
            + serviceInfo.getServiceName()
            + " -> "
            + hostName
            + " / "
            + address
            + ":"
            + port);
    listener.onServiceResolved(serviceInfo.getServiceName(), hostName, address, port);
  }

  /** Callback interface for discovery events; all methods are invoked on the main thread. */
  public interface Listener {
    /** Discovery started successfully. */
    void onDiscoveryStarted();

    /** A service was found (not yet resolved). */
    void onServiceFound(@NonNull String serviceName, String serviceType);

    /**
     * A previously found service was resolved. At least one of host name and address is set; the
     * host name is the raw mDNS name, the address an IP literal (IPv4 preferred).
     */
    void onServiceResolved(
        @NonNull String serviceName, @Nullable String hostName, @Nullable String address, int port);

    /** A previously found service disappeared. */
    void onServiceLost(@NonNull String serviceName);

    /** Discovery finished after its timeout. */
    void onDiscoveryFinished();

    /** Discovery could not be started or failed fatally. */
    void onDiscoveryFailed();
  }
}
