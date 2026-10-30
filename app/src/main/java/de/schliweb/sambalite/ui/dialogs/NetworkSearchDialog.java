/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui.dialogs;

import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.ui.adapters.DiscoveredServerAdapter;
import de.schliweb.sambalite.util.DiscoveredServers;
import de.schliweb.sambalite.util.EnhancedUIUtils;
import de.schliweb.sambalite.util.LogUtils;
import de.schliweb.sambalite.util.NetworkScanner;
import de.schliweb.sambalite.util.SmbServiceDiscovery;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The dialog that searches the local network for SMB servers. It runs two searches side by side and
 * lists their results together: the port scan of the local subnet ({@link NetworkScanner}, ports
 * 445 and 139) and the mDNS/DNS-SD search for announced SMB services ({@link SmbServiceDiscovery},
 * {@code _smb._tcp}, e.g. via Avahi). The latter also finds servers on ports other than 445. Both
 * stop when the dialog closes.
 */
public class NetworkSearchDialog {

  private static final String TAG = "NetworkSearchDialog";

  /** How long the check whether this device resolves an mDNS host name may take. */
  private static final long HOST_NAME_CHECK_TIMEOUT_MS = 2_000;

  /** Receives the chosen server. */
  public interface OnServerChosenListener {
    /**
     * Called with the server the user picked.
     *
     * @param server host name or IP address to connect to
     * @param port the port to fill in, or 0 to leave the port field unchanged
     * @param suggestedName a name for the connection
     */
    void onServerChosen(@NonNull String server, int port, @NonNull String suggestedName);
  }

  private final AppCompatActivity activity;
  private final NetworkScanner networkScanner;
  private final SmbServiceDiscovery serviceDiscovery;
  private final OnServerChosenListener onServerChosen;

  private final DiscoveredServers results = new DiscoveredServers();
  private final DiscoveredServerAdapter adapter = new DiscoveredServerAdapter();
  private final ExecutorService hostNameCheck = Executors.newSingleThreadExecutor();

  private boolean scanSupported;
  private boolean mdnsSupported;
  private boolean scanRunning;
  private boolean mdnsRunning;
  private boolean closed;

  private AlertDialog dialog;
  private View progressSection;
  private View serverListSection;
  private ProgressBar progressIndicator;
  private TextView statusText;
  private TextView progressText;
  private TextView serversFoundLabel;
  private TextView noServersText;
  private Button searchButton;
  private Button useServerButton;

  public NetworkSearchDialog(
      @NonNull AppCompatActivity activity,
      @NonNull NetworkScanner networkScanner,
      @NonNull SmbServiceDiscovery serviceDiscovery,
      @NonNull OnServerChosenListener onServerChosen) {
    this.activity = activity;
    this.networkScanner = networkScanner;
    this.serviceDiscovery = serviceDiscovery;
    this.onServerChosen = onServerChosen;
  }

  /** Shows the dialog and starts both searches. */
  public void show() {
    scanSupported = networkScanner.isScanningSupported();
    mdnsSupported = serviceDiscovery.isAvailable();
    if (!scanSupported && !mdnsSupported) {
      EnhancedUIUtils.showError(
          activity, "Network scanning is not available. Please check your network connection.");
      return;
    }

    View view = activity.getLayoutInflater().inflate(R.layout.dialog_network_scan, null);
    progressSection = view.findViewById(R.id.scan_progress_section);
    serverListSection = view.findViewById(R.id.server_list_section);
    progressIndicator = view.findViewById(R.id.scan_progress_indicator);
    statusText = view.findViewById(R.id.scan_status_text);
    progressText = view.findViewById(R.id.scan_progress_text);
    serversFoundLabel = view.findViewById(R.id.servers_found_label);
    noServersText = view.findViewById(R.id.no_servers_text);
    searchButton = view.findViewById(R.id.btn_scan);
    useServerButton = view.findViewById(R.id.btn_use_server);
    Button cancelButton = view.findViewById(R.id.btn_cancel);
    view.findViewById(R.id.custom_button_bar).setVisibility(View.VISIBLE);

    RecyclerView recyclerView = view.findViewById(R.id.servers_recycler_view);
    recyclerView.setLayoutManager(new LinearLayoutManager(activity));
    recyclerView.setAdapter(adapter);
    adapter.setOnServerSelectedListener(server -> useServerButton.setVisibility(View.VISIBLE));

    dialog =
        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.scan_network)
            .setView(view)
            .create();
    dialog.setCanceledOnTouchOutside(false);
    dialog.setCancelable(false);
    // Neither search may outlive the dialog, however it is closed
    dialog.setOnDismissListener(d -> close());

    cancelButton.setOnClickListener(
        v -> {
          LogUtils.d(TAG, "Network search cancelled by user");
          dialog.dismiss();
        });
    searchButton.setOnClickListener(v -> startSearch());
    useServerButton.setOnClickListener(v -> useSelectedServer());

    dialog.show();
    startSearch();
  }

  private void close() {
    closed = true;
    networkScanner.cancelScan();
    serviceDiscovery.stop();
    hostNameCheck.shutdownNow();
  }

  private void startSearch() {
    LogUtils.d(
        TAG, "Starting network search (scan=" + scanSupported + ", mDNS=" + mdnsSupported + ")");
    results.clear();
    adapter.setServers(results.getEntries());
    searchButton.setVisibility(View.GONE);
    useServerButton.setVisibility(View.GONE);
    noServersText.setVisibility(View.GONE);
    progressSection.setVisibility(View.VISIBLE);
    statusText.setText(scanSupported ? R.string.scanning_network : R.string.discovering_services);
    progressIndicator.setProgress(0);
    progressIndicator.setVisibility(scanSupported ? View.VISIBLE : View.GONE);
    progressText.setVisibility(scanSupported ? View.VISIBLE : View.GONE);
    progressText.setText(R.string.hosts_scan_progress);
    updateList();

    if (scanSupported) {
      scanRunning = true;
      networkScanner.scanLocalNetwork(new ScanListener());
    }
    if (mdnsSupported) {
      mdnsRunning = true;
      serviceDiscovery.start(new MdnsListener());
    }
  }

  private void updateList() {
    adapter.setServers(results.getEntries());
    int count = results.size();
    serverListSection.setVisibility(count > 0 || !isSearching() ? View.VISIBLE : View.GONE);
    serversFoundLabel.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
    if (count > 0) {
      serversFoundLabel.setText(
          activity.getResources().getQuantityString(R.plurals.servers_found_count, count, count));
    }
    useServerButton.setVisibility(adapter.getSelectedServer() != null ? View.VISIBLE : View.GONE);
  }

  private boolean isSearching() {
    return scanRunning || mdnsRunning;
  }

  /** Called when one of the searches ends; finishes the dialog state once both have. */
  private void onSearchEnded() {
    if (closed || isSearching()) {
      return;
    }
    progressSection.setVisibility(View.GONE);
    searchButton.setVisibility(View.VISIBLE);
    noServersText.setVisibility(results.size() == 0 ? View.VISIBLE : View.GONE);
    updateList();
    EnhancedUIUtils.showInfo(activity, activity.getString(R.string.scan_complete));
  }

  private void useSelectedServer() {
    DiscoveredServers.Entry selected = adapter.getSelectedServer();
    if (selected == null) {
      return;
    }
    LogUtils.d(TAG, "Using selected server: " + selected);
    useServerButton.setEnabled(false);
    String hostName = selected.getMdnsHostName();
    if (hostName == null) {
      apply(selected, false);
      return;
    }
    // Resolving a .local name may block, so it is checked off the main thread
    hostNameCheck.execute(
        () -> {
          boolean resolves = resolves(hostName);
          activity.runOnUiThread(
              () -> {
                if (!closed) apply(selected, resolves);
              });
        });
  }

  /** Whether this device resolves the given host name within a short time. */
  private boolean resolves(String hostName) {
    ExecutorService lookup = Executors.newSingleThreadExecutor();
    try {
      Future<InetAddress> address = lookup.submit(() -> InetAddress.getByName(hostName));
      address.get(HOST_NAME_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      LogUtils.d(TAG, "mDNS host name resolves on this device: " + hostName);
      return true;
    } catch (Exception e) {
      LogUtils.i(TAG, "mDNS host name does not resolve, using the address: " + hostName);
      return false;
    } finally {
      lookup.shutdownNow();
    }
  }

  private void apply(DiscoveredServers.Entry selected, boolean hostNameResolves) {
    String server = selected.getServerToUse(hostNameResolves);
    onServerChosen.onServerChosen(server, selected.getPortToUse(), suggestedName(selected));
    EnhancedUIUtils.showInfo(
        activity, activity.getString(R.string.server_info_applied, selected.getDisplayName()));
    dialog.dismiss();
  }

  private static String suggestedName(DiscoveredServers.Entry entry) {
    if (entry.getServiceName() != null) {
      return entry.getServiceName();
    }
    if (entry.getScanHostName() != null) {
      return entry.getScanHostName();
    }
    return "Server " + entry.getAddress();
  }

  /** Port scan events arrive on worker threads. */
  private class ScanListener implements NetworkScanner.ScanProgressListener {
    @Override
    public void onProgressUpdate(int scannedHosts, int totalHosts, String currentHost) {
      activity.runOnUiThread(
          () -> {
            if (closed) return;
            progressIndicator.setProgress((int) ((scannedHosts / (float) totalHosts) * 100));
            progressText.setText(
                activity
                    .getResources()
                    .getQuantityString(
                        R.plurals.hosts_scanned, scannedHosts, scannedHosts, totalHosts));
            statusText.setText(activity.getString(R.string.currently_scanning, currentHost));
          });
    }

    @Override
    public void onServerFound(NetworkScanner.DiscoveredServer server) {
      activity.runOnUiThread(
          () -> {
            if (closed) return;
            LogUtils.d(TAG, "Port scan found: " + server.getDisplayName());
            results.addScanned(
                server.getIpAddress(),
                server.getHostname(),
                server.isSmbPortOpen(),
                server.isNetbiosPortOpen(),
                server.getResponseTime());
            updateList();
          });
    }

    @Override
    public void onScanComplete(List<NetworkScanner.DiscoveredServer> servers) {
      activity.runOnUiThread(
          () -> {
            LogUtils.i(TAG, "Port scan completed with " + servers.size() + " servers");
            scanRunning = false;
            onSearchEnded();
          });
    }

    @Override
    public void onScanError(String error) {
      activity.runOnUiThread(
          () -> {
            LogUtils.e(TAG, "Port scan error: " + error);
            scanRunning = false;
            if (closed) return;
            EnhancedUIUtils.showError(activity, activity.getString(R.string.scan_error, error));
            onSearchEnded();
          });
    }
  }

  /** mDNS events arrive on the main thread and stop after {@link SmbServiceDiscovery#stop()}. */
  private class MdnsListener implements SmbServiceDiscovery.Listener {
    @Override
    public void onDiscoveryStarted() {
      LogUtils.d(TAG, "mDNS discovery started");
    }

    @Override
    public void onServiceFound(@NonNull String serviceName, String serviceType) {
      if (results.addMdnsFound(serviceName)) {
        updateList();
      }
    }

    @Override
    public void onServiceResolved(
        @NonNull String serviceName, String hostName, String address, int port) {
      if (results.markMdnsResolved(serviceName, hostName, address, port)) {
        updateList();
      }
    }

    @Override
    public void onServiceLost(@NonNull String serviceName) {
      if (results.removeMdns(serviceName)) {
        updateList();
      }
    }

    @Override
    public void onDiscoveryFinished() {
      LogUtils.d(TAG, "mDNS discovery finished");
      mdnsRunning = false;
      onSearchEnded();
    }

    @Override
    public void onDiscoveryFailed() {
      // The port scan may still find servers, so this is only logged
      LogUtils.w(TAG, "mDNS discovery failed");
      mdnsRunning = false;
      onSearchEnded();
    }
  }
}
