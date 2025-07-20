package de.schliweb.sambalite.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import de.schliweb.sambalite.util.LogUtils;
import de.schliweb.sambalite.util.SimplePerformanceMonitor;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Advanced network optimization manager for SambaLite.
 * Provides intelligent connection management, adaptive retry strategies,
 * bandwidth monitoring, and network quality assessment.
 */
public class AdvancedNetworkOptimizer {

    private static final String TAG = "AdvancedNetworkOptimizer";

    // Network quality thresholds
    private static final long EXCELLENT_BANDWIDTH_THRESHOLD = 10 * 1024 * 1024; // 10 Mbps
    private static final long GOOD_BANDWIDTH_THRESHOLD = 2 * 1024 * 1024; // 2 Mbps
    private static final long POOR_BANDWIDTH_THRESHOLD = 512 * 1024; // 512 Kbps

    // Retry strategy parameters
    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final long INITIAL_RETRY_DELAY_MS = 1000;
    private static final double RETRY_BACKOFF_MULTIPLIER = 2.0;
    private static final long MAX_RETRY_DELAY_MS = 30000;

    // Connection pooling
    private static final int MAX_CONCURRENT_CONNECTIONS = 5;
    private static final long CONNECTION_TIMEOUT_MS = 30000;
    private static final long IDLE_CONNECTION_TIMEOUT_MS = 300000; // 5 minutes

    @Getter
    private static AdvancedNetworkOptimizer instance;
    private final ConnectivityManager connectivityManager;
    private final ScheduledExecutorService networkExecutor;
    private final ConcurrentHashMap<String, ConnectionPool> connectionPools;
    private final AtomicBoolean isNetworkAvailable;
    private final AtomicLong currentBandwidth;
    private final BandwidthMonitor bandwidthMonitor;
    private NetworkCapabilities currentNetworkCapabilities;
    private NetworkQuality currentNetworkQuality;

    private AdvancedNetworkOptimizer(Context context) {
        this.connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.networkExecutor = Executors.newScheduledThreadPool(3);
        this.connectionPools = new ConcurrentHashMap<>();
        this.isNetworkAvailable = new AtomicBoolean(false);
        this.currentBandwidth = new AtomicLong(0);
        this.bandwidthMonitor = new BandwidthMonitor();
        this.currentNetworkQuality = NetworkQuality.UNKNOWN;

        initializeNetworkMonitoring();
        startBandwidthMonitoring();
        scheduleConnectionPoolCleanup();
    }

    /**
     * Initializes the network optimizer.
     */
    public static void initialize(Context context) {
        if (instance == null) {
            instance = new AdvancedNetworkOptimizer(context);
            LogUtils.i(TAG, "Advanced network optimizer initialized");
        }
    }

    /**
     * Gets optimal connection settings based on current network conditions.
     */
    public OptimalSettings getOptimalSettings() {
        OptimalSettings settings = new OptimalSettings();

        // Adjust timeouts based on network quality
        switch (currentNetworkQuality) {
            case EXCELLENT:
                settings.connectionTimeoutMs = 10000;
                settings.readTimeoutMs = 15000;
                settings.maxConcurrentConnections = MAX_CONCURRENT_CONNECTIONS;
                settings.chunkSize = 1024 * 1024; // 1MB chunks
                break;
            case GOOD:
                settings.connectionTimeoutMs = 20000;
                settings.readTimeoutMs = 30000;
                settings.maxConcurrentConnections = 3;
                settings.chunkSize = 512 * 1024; // 512KB chunks
                break;
            case POOR:
                settings.connectionTimeoutMs = 45000;
                settings.readTimeoutMs = 60000;
                settings.maxConcurrentConnections = 1;
                settings.chunkSize = 128 * 1024; // 128KB chunks
                break;
            default:
                settings.connectionTimeoutMs = CONNECTION_TIMEOUT_MS;
                settings.readTimeoutMs = 45000;
                settings.maxConcurrentConnections = 2;
                settings.chunkSize = 256 * 1024; // 256KB chunks
        }

        // Enable compression for slow networks
        settings.useCompression = currentNetworkQuality == NetworkQuality.POOR;

        // Aggressive caching for slow networks
        settings.aggressiveCaching = currentNetworkQuality != NetworkQuality.EXCELLENT;

        LogUtils.d(TAG, "Generated optimal settings for " + currentNetworkQuality + " network: timeout=" + settings.connectionTimeoutMs + "ms, " + "chunks=" + settings.chunkSize + " bytes");

        return settings;
    }

    /**
     * Monitors and reports current network status.
     */
    public NetworkStatus getCurrentNetworkStatus() {
        NetworkStatus status = new NetworkStatus();
        status.isConnected = isNetworkAvailable.get();
        status.networkQuality = currentNetworkQuality;
        status.estimatedBandwidth = currentBandwidth.get();
        status.connectionType = getConnectionType();
        status.isMetered = isCurrentNetworkMetered();

        LogUtils.d(TAG, "Current network status: " + status.isConnected + ", quality=" + status.networkQuality + ", bandwidth=" + status.estimatedBandwidth + " bps");

        return status;
    }

    // Private helper methods

    private void initializeNetworkMonitoring() {
        // Since minSdkVersion is 28, we're always on Android N (API 24) or higher
        NetworkRequest.Builder builder = new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);

        connectivityManager.registerNetworkCallback(builder.build(), new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                LogUtils.i(TAG, "Network became available");
                isNetworkAvailable.set(true);
                updateNetworkCapabilities(network);
                assessNetworkQuality();
            }

            @Override
            public void onLost(Network network) {
                LogUtils.w(TAG, "Network lost");
                isNetworkAvailable.set(false);
                currentNetworkQuality = NetworkQuality.UNKNOWN;
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                LogUtils.d(TAG, "Network capabilities changed");
                currentNetworkCapabilities = capabilities;
                assessNetworkQuality();
            }
        });

        // Initial network state check
        updateCurrentNetworkState();
    }

    private void updateCurrentNetworkState() {
        // Since minSdkVersion is 28, we're always on Android M (API 23) or higher
        Network activeNetwork = connectivityManager.getActiveNetwork();
        if (activeNetwork != null) {
            isNetworkAvailable.set(true);
            updateNetworkCapabilities(activeNetwork);
            assessNetworkQuality();
        }
    }

    private void updateNetworkCapabilities(Network network) {
        // Since minSdkVersion is 28, we're always on Android M (API 23) or higher
        currentNetworkCapabilities = connectivityManager.getNetworkCapabilities(network);
    }

    private void assessNetworkQuality() {
        if (currentNetworkCapabilities == null) {
            currentNetworkQuality = NetworkQuality.UNKNOWN;
            return;
        }

        long bandwidth = currentBandwidth.get();

        if (bandwidth >= EXCELLENT_BANDWIDTH_THRESHOLD) {
            currentNetworkQuality = NetworkQuality.EXCELLENT;
        } else if (bandwidth >= GOOD_BANDWIDTH_THRESHOLD) {
            currentNetworkQuality = NetworkQuality.GOOD;
        } else if (bandwidth >= POOR_BANDWIDTH_THRESHOLD) {
            currentNetworkQuality = NetworkQuality.POOR;
        } else {
            currentNetworkQuality = NetworkQuality.UNKNOWN;
        }

        LogUtils.i(TAG, "Network quality assessed as: " + currentNetworkQuality + " (bandwidth: " + bandwidth + " bps)");
    }

    private void startBandwidthMonitoring() {
        networkExecutor.scheduleWithFixedDelay(() -> {
            long estimatedBandwidth = bandwidthMonitor.measureBandwidth();
            currentBandwidth.set(estimatedBandwidth);
            assessNetworkQuality();
        }, 5, 30, TimeUnit.SECONDS);
    }

    private void scheduleConnectionPoolCleanup() {
        networkExecutor.scheduleWithFixedDelay(() -> {
            LogUtils.d(TAG, "Performing connection pool cleanup");

            for (ConnectionPool pool : connectionPools.values()) {
                pool.cleanup();
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    private <T> void executeWithRetry(NetworkOperation<T> operation, NetworkCallback<T> callback, RetryContext retryContext) {
        try {
            // Check network availability
            if (!isNetworkAvailable.get()) {
                callback.onFailure(new NetworkException("Network not available"));
                return;
            }

            // Execute the operation
            T result = operation.execute();
            callback.onSuccess(result);

            // Reset retry context on success
            retryContext.reset();

        } catch (Exception e) {
            LogUtils.w(TAG, "Network operation failed (attempt " + (retryContext.attemptCount + 1) + "): " + e.getMessage());

            if (shouldRetry(e, retryContext)) {
                long delay = calculateRetryDelay(retryContext);
                retryContext.attemptCount++;

                LogUtils.d(TAG, "Scheduling retry in " + delay + "ms");
                networkExecutor.schedule(() -> executeWithRetry(operation, callback, retryContext), delay, TimeUnit.MILLISECONDS);
            } else {
                LogUtils.e(TAG, "Max retry attempts reached, operation failed");
                callback.onFailure(e);
            }
        } finally {
            SimplePerformanceMonitor.endOperation("network_operation");
        }
    }

    private boolean shouldRetry(Exception e, RetryContext retryContext) {
        if (retryContext.attemptCount >= MAX_RETRY_ATTEMPTS) {
            return false;
        }

        // Don't retry on certain types of errors
        if (e instanceof SecurityException || e instanceof IllegalArgumentException) {
            return false;
        }

        // Retry on network-related errors
        return e instanceof java.net.SocketTimeoutException || e instanceof java.net.ConnectException || e instanceof java.io.IOException;
    }

    private long calculateRetryDelay(RetryContext retryContext) {
        long delay = (long) (INITIAL_RETRY_DELAY_MS * Math.pow(RETRY_BACKOFF_MULTIPLIER, retryContext.attemptCount));

        // Add jitter to avoid thundering herd
        delay += (long) (Math.random() * 1000);

        return Math.min(delay, MAX_RETRY_DELAY_MS);
    }

    private String getConnectionType() {
        if (currentNetworkCapabilities == null) {
            return "Unknown";
        }

        // Since minSdkVersion is 28, we're always on Android M (API 23) or higher
        if (currentNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "WiFi";
        } else if (currentNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return "Mobile";
        } else if (currentNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return "Ethernet";
        }

        return "Unknown";
    }

    private boolean isCurrentNetworkMetered() {
        // Since minSdkVersion is 28, we're always on Android M (API 23) or higher
        if (currentNetworkCapabilities != null) {
            return !currentNetworkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        }
        return false;
    }

    /**
     * Shuts down the network optimizer.
     */
    public void shutdown() {
        LogUtils.i(TAG, "Shutting down network optimizer");
        networkExecutor.shutdown();
        try {
            if (!networkExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                networkExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            networkExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // Inner classes and interfaces

    public enum NetworkQuality {
        EXCELLENT, GOOD, POOR, UNKNOWN
    }

    public interface NetworkOperation<T> {
        T execute() throws Exception;
    }

    public interface NetworkCallback<T> {
        void onSuccess(T result);

        void onFailure(Exception error);
    }

    public static class OptimalSettings {
        public long connectionTimeoutMs;
        public long readTimeoutMs;
        public int maxConcurrentConnections;
        public int chunkSize;
        public boolean useCompression;
        public boolean aggressiveCaching;
    }

    public static class NetworkStatus {
        public boolean isConnected;
        public NetworkQuality networkQuality;
        public long estimatedBandwidth;
        public String connectionType;
        public boolean isMetered;
    }

    public static class NetworkException extends Exception {
        public NetworkException(String message) {
            super(message);
        }

        public NetworkException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static class RetryContext {
        int attemptCount = 0;

        void reset() {
            attemptCount = 0;
        }
    }

    private static class BandwidthMonitor {
        private final long lastByteCount = 0;
        private long lastMeasureTime = 0;

        long measureBandwidth() {
            // Simplified bandwidth measurement
            // In a real implementation, this would measure actual network throughput
            long currentTime = System.currentTimeMillis();

            if (lastMeasureTime == 0) {
                lastMeasureTime = currentTime;
                return GOOD_BANDWIDTH_THRESHOLD; // Default assumption
            }

            // Placeholder calculation - would be replaced with actual measurement
            return GOOD_BANDWIDTH_THRESHOLD;
        }
    }

    public static class ConnectionPool {
        private final String serverKey;
        private final int maxConnections;
        private final List<PooledConnection> availableConnections;
        private final List<PooledConnection> busyConnections;

        ConnectionPool(String serverKey, int maxConnections) {
            this.serverKey = serverKey;
            this.maxConnections = maxConnections;
            this.availableConnections = Collections.synchronizedList(new ArrayList<>());
            this.busyConnections = Collections.synchronizedList(new ArrayList<>());
        }

        public PooledConnection getConnection() {
            synchronized (this) {
                if (!availableConnections.isEmpty()) {
                    PooledConnection connection = availableConnections.remove(0);
                    busyConnections.add(connection);
                    return connection;
                }

                if (getTotalConnections() < maxConnections) {
                    PooledConnection connection = new PooledConnection(serverKey);
                    busyConnections.add(connection);
                    return connection;
                }

                return null; // Pool exhausted
            }
        }

        public void returnConnection(PooledConnection connection) {
            synchronized (this) {
                busyConnections.remove(connection);
                if (connection.isValid()) {
                    availableConnections.add(connection);
                }
            }
        }

        void cleanup() {
            synchronized (this) {
                availableConnections.removeIf(conn -> !conn.isValid());
                LogUtils.d(TAG, "Connection pool cleanup for " + serverKey + ": " + availableConnections.size() + " available, " + busyConnections.size() + " busy");
            }
        }

        private int getTotalConnections() {
            return availableConnections.size() + busyConnections.size();
        }
    }

    public static class PooledConnection {
        private final String serverKey;
        private final long creationTime;
        private long lastUsedTime;

        PooledConnection(String serverKey) {
            this.serverKey = serverKey;
            this.creationTime = System.currentTimeMillis();
            this.lastUsedTime = creationTime;
        }

        public boolean isValid() {
            long age = System.currentTimeMillis() - lastUsedTime;
            return age < IDLE_CONNECTION_TIMEOUT_MS;
        }

        public void updateLastUsed() {
            lastUsedTime = System.currentTimeMillis();
        }

        public String getServerKey() {
            return serverKey;
        }
    }
}
