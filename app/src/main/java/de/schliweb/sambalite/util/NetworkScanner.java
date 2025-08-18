package de.schliweb.sambalite.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import android.net.LinkAddress;
import lombok.Getter;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Network scanner utility for discovering SMB servers in the local network.
 * Provides efficient network scanning with configurable timeouts and thread management.
 */
public class NetworkScanner {

    private static final String TAG = "NetworkScanner";
    private static final int DEFAULT_TIMEOUT_MS = 1000;
    private static final int SMB_PORT = 445;
    private static final int NETBIOS_PORT = 139;
    private final Context context;
    private volatile boolean isScanning = false;
    private ExecutorService currentExecutorService;

    public NetworkScanner(Context context) {
        this.context = context;
    }

    /**
     * Scans the local network for SMB servers.
     */
    public void scanLocalNetwork(ScanProgressListener listener) {
        scanLocalNetwork(listener, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Scans the local network for SMB servers with custom timeout.
     */
    public void scanLocalNetwork(ScanProgressListener listener, int timeoutMs) {
        if (isScanning) {
            LogUtils.w(TAG, "Scan already in progress, ignoring new scan request");
            return;
        }

        LogUtils.i(TAG, "Starting local network scan");
        isScanning = true;

        // Create a new executor service for each scan
        currentExecutorService = Executors.newFixedThreadPool(20);

        CompletableFuture.runAsync(() -> {
            try {
                List<String> networkRanges = getLocalNetworkRanges();
                if (networkRanges.isEmpty()) {
                    listener.onScanError("Network scanning not available - no network interfaces found");
                    return;
                }

                List<DiscoveredServer> allServers = new ArrayList<>();

                for (String networkRange : networkRanges) {
                    LogUtils.d(TAG, "Scanning network range: " + networkRange);
                    List<DiscoveredServer> servers = scanNetworkRange(networkRange, listener, timeoutMs);
                    allServers.addAll(servers);
                }

                LogUtils.i(TAG, "Network scan completed. Found " + allServers.size() + " potential SMB servers");
                listener.onScanComplete(allServers);

            } catch (Exception e) {
                LogUtils.e(TAG, "Error during network scan: " + e.getMessage());
                // Provide a more user-friendly error message
                String userMessage = "Network scan failed";
                if (e.getMessage().contains("rejected") || e.getMessage().contains("ThreadPoolExecutor")) {
                    userMessage = "Network scan encountered an internal error. Please try again.";
                } else if (e.getMessage().contains("timeout")) {
                    userMessage = "Network scan timed out. Please check your network connection.";
                } else if (e.getMessage().contains("permission")) {
                    userMessage = "Network scan requires additional permissions.";
                }
                listener.onScanError(userMessage);
            } finally {
                isScanning = false;
                // Clean up the executor service
                if (currentExecutorService != null) {
                    currentExecutorService.shutdown();
                    currentExecutorService = null;
                }
            }
        });
    }

    /**
     * Cancels the current scan operation.
     */
    public void cancelScan() {
        if (isScanning) {
            LogUtils.d(TAG, "Cancelling network scan");
            isScanning = false;
            if (currentExecutorService != null) {
                currentExecutorService.shutdownNow();
                currentExecutorService = null;
            }
        }
    }

    /**
     * Gets the local network ranges to scan.
     */
    private List<String> getLocalNetworkRanges() {
        List<String> ranges = new ArrayList<>();

        try {
            // Prefer modern ConnectivityManager APIs to derive active IPv4 subnet
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                try {
                    Network active = cm.getActiveNetwork();
                    if (active != null) {
                        LinkProperties lp = cm.getLinkProperties(active);
                        if (lp != null) {
                            for (LinkAddress la : lp.getLinkAddresses()) {
                                InetAddress addr = la.getAddress();
                                if (addr != null && addr.isSiteLocalAddress() && addr.getHostAddress() != null && addr.getHostAddress().contains(".")) {
                                    String subnet = getSubnetFromAddress(addr);
                                    if (subnet != null && !ranges.contains(subnet)) {
                                        ranges.add(subnet);
                                        LogUtils.d(TAG, "Added active network subnet: " + subnet);
                                    }
                                }
                            }
                        }
                    }
                } catch (SecurityException e) {
                    LogUtils.w(TAG, "Connectivity permissions not available, using fallback network ranges");
                }
            }

            // Get all network interfaces as fallback/additional
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface networkInterface : interfaces) {
                if (networkInterface.isUp() && !networkInterface.isLoopback()) {
                    List<InetAddress> addresses = Collections.list(networkInterface.getInetAddresses());
                    for (InetAddress address : addresses) {
                        if (!address.isLoopbackAddress() && address.isSiteLocalAddress()) {
                            String subnet = getSubnetFromAddress(address);
                            if (subnet != null && !ranges.contains(subnet)) {
                                ranges.add(subnet);
                                LogUtils.d(TAG, "Added network subnet: " + subnet);
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            LogUtils.e(TAG, "Error getting network ranges: " + e.getMessage());
        }

        // If no ranges found, add common default ranges
        if (ranges.isEmpty()) {
            LogUtils.i(TAG, "No network ranges detected, using common default ranges");
            ranges.add("192.168.1");     // Most common home router range
            ranges.add("192.168.0");     // Second most common
            ranges.add("10.0.0");        // Corporate networks
            ranges.add("172.16.0");      // Private networks
            ranges.add("192.168.2");     // Alternative home range
        }

        return ranges;
    }

    /**
     * Converts an IP address integer to a subnet string.
     */
    private String getSubnetFromIp(int ip) {
        try {
            String ipStr = String.format(Locale.ROOT, "%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));

            // Assume /24 subnet
            String[] parts = ipStr.split("\\.");
            return parts[0] + "." + parts[1] + "." + parts[2] + ".";

        } catch (Exception e) {
            LogUtils.e(TAG, "Error converting IP to subnet: " + e.getMessage());
            return null;
        }
    }

    /**
     * Gets subnet from InetAddress.
     */
    private String getSubnetFromAddress(InetAddress address) {
        try {
            String ipStr = address.getHostAddress();
            if (ipStr != null && ipStr.contains(".")) {
                String[] parts = ipStr.split("\\.");
                if (parts.length == 4) {
                    return parts[0] + "." + parts[1] + "." + parts[2] + ".";
                }
            }
        } catch (Exception e) {
            LogUtils.e(TAG, "Error getting subnet from address: " + e.getMessage());
        }
        return null;
    }

    /**
     * Scans a specific network range.
     */
    private List<DiscoveredServer> scanNetworkRange(String subnet, ScanProgressListener listener, int timeoutMs) {
        List<DiscoveredServer> servers = new ArrayList<>();
        List<CompletableFuture<DiscoveredServer>> futures = new ArrayList<>();

        // Scan hosts 1-254 in the subnet
        for (int i = 1; i <= 254; i++) {
            if (!isScanning) break; // Check if scan was cancelled

            final String host = subnet + i;
            final int hostNumber = i;

            CompletableFuture<DiscoveredServer> future = CompletableFuture.supplyAsync(() -> {
                if (!isScanning) return null;

                listener.onProgressUpdate(hostNumber, 254, host);
                return scanHost(host, timeoutMs);
            }, currentExecutorService);

            futures.add(future);
        }

        // Collect results
        for (CompletableFuture<DiscoveredServer> discoveredServerCompletableFuture : futures) {
            if (!isScanning) break;

            try {
                DiscoveredServer server = discoveredServerCompletableFuture.get(timeoutMs + (long) 500, TimeUnit.MILLISECONDS);

                if (server != null && server.isLikelySmbServer()) {
                    servers.add(server);
                    listener.onServerFound(server);
                    LogUtils.d(TAG, "Found SMB server: " + server);
                }

            } catch (Exception e) {
                // Timeout or other error for this host - continue scanning
                LogUtils.v(TAG, "Host scan failed: " + e.getMessage());
            }
        }

        return servers;
    }

    /**
     * Scans a specific host for SMB services.
     */
    private DiscoveredServer scanHost(String host, int timeoutMs) {
        long startTime = System.currentTimeMillis();

        try {
            InetAddress address = InetAddress.getByName(host);

            // Check if host is reachable
            if (!address.isReachable(timeoutMs)) {
                return null;
            }

            long responseTime = System.currentTimeMillis() - startTime;

            // Get hostname
            String hostname = null;
            try {
                hostname = address.getCanonicalHostName();
                if (hostname.equals(host)) {
                    hostname = null; // No reverse DNS available
                }
            } catch (Exception e) {
                // Hostname resolution failed
            }

            // Check SMB ports
            boolean smbPortOpen = isPortOpen(host, SMB_PORT, timeoutMs / 2);
            boolean netbiosPortOpen = isPortOpen(host, NETBIOS_PORT, timeoutMs / 2);

            // Only return if at least one SMB-related port is open or if it's a named host
            if (smbPortOpen || netbiosPortOpen || hostname != null) {
                return new DiscoveredServer(host, hostname, smbPortOpen, netbiosPortOpen, responseTime);
            }

        } catch (Exception e) {
            LogUtils.v(TAG, "Error scanning host " + host + ": " + e.getMessage());
        }

        return null;
    }

    /**
     * Checks if a specific port is open on a host.
     */
    private boolean isPortOpen(String host, int port, int timeoutMs) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Checks if network scanning is supported and permitted.
     */
    public boolean isScanningSupported() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network active = cm.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
        return caps != null && (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
    }

    /**
     * Cleanup resources.
     */
    public void shutdown() {
        LogUtils.d(TAG, "Shutting down NetworkScanner");
        isScanning = false;
        if (currentExecutorService != null && !currentExecutorService.isShutdown()) {
            currentExecutorService.shutdown();
            try {
                if (!currentExecutorService.awaitTermination(2, TimeUnit.SECONDS)) {
                    currentExecutorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                currentExecutorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
            currentExecutorService = null;
        }
    }

    /**
     * Interface for receiving scan progress updates.
     */
    public interface ScanProgressListener {
        void onProgressUpdate(int scannedHosts, int totalHosts, String currentHost);

        void onServerFound(DiscoveredServer server);

        void onScanComplete(List<DiscoveredServer> servers);

        void onScanError(String error);
    }

    /**
     * Represents a discovered server in the network.
     */
    @Getter
    public static class DiscoveredServer {
        private final String ipAddress;
        private final String hostname;
        private final boolean smbPortOpen;
        private final boolean netbiosPortOpen;
        private final long responseTime;

        public DiscoveredServer(String ipAddress, String hostname, boolean smbPortOpen, boolean netbiosPortOpen, long responseTime) {
            this.ipAddress = ipAddress;
            this.hostname = hostname;
            this.smbPortOpen = smbPortOpen;
            this.netbiosPortOpen = netbiosPortOpen;
            this.responseTime = responseTime;
        }

        public boolean isLikelySmbServer() {
            return smbPortOpen || netbiosPortOpen;
        }

        public String getDisplayName() {
            if (hostname != null && !hostname.equals(ipAddress)) {
                return hostname + " (" + ipAddress + ")";
            }
            return ipAddress;
        }

        @Override
        public String toString() {
            return "DiscoveredServer{" + "ip='" + ipAddress + '\'' + ", hostname='" + hostname + '\'' + ", smb=" + smbPortOpen + ", netbios=" + netbiosPortOpen + ", responseTime=" + responseTime + "ms" + '}';
        }
    }
}
