package de.schliweb.sambalite.cache.maintenance;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import de.schliweb.sambalite.cache.exception.CacheExceptionHandler;
import de.schliweb.sambalite.cache.statistics.CacheStatistics;
import de.schliweb.sambalite.cache.strategy.CacheStrategy;
import de.schliweb.sambalite.util.LogUtils;
import lombok.Setter;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages maintenance operations for the cache.
 * This class centralizes the maintenance logic that was previously scattered
 * throughout the original IntelligentCacheManager.
 */
public class CacheMaintenanceManager {
    private static final String TAG = "CacheMaintenanceManager";

    // Default maintenance interval in milliseconds (30 minutes)
    private static final long DEFAULT_MAINTENANCE_INTERVAL_MS = 30 * 60 * 1000;

    // The application context
    private final Context context;

    // The cache strategy to maintain
    private final CacheStrategy<?, ?> cacheStrategy;

    // Statistics for monitoring cache performance
    private final CacheStatistics statistics;

    // Exception handler for reporting maintenance errors
    private final CacheExceptionHandler exceptionHandler;

    // Executor for scheduled maintenance tasks
    private final ScheduledExecutorService scheduledExecutor;

    // Handler for posting maintenance results to the main thread
    private final Handler mainHandler;

    // Flag to track whether maintenance is currently running
    private final AtomicBoolean maintenanceRunning = new AtomicBoolean(false);

    // Maintenance interval in milliseconds
    private final long maintenanceIntervalMs;

    // Listener for maintenance events
    @Setter
    private MaintenanceListener maintenanceListener;

    /**
     * Creates a new CacheMaintenanceManager with the default maintenance interval.
     *
     * @param context          The application context
     * @param cacheStrategy    The cache strategy to maintain
     * @param statistics       The statistics object for tracking cache performance
     * @param exceptionHandler The exception handler for reporting maintenance errors
     */
    public CacheMaintenanceManager(Context context, CacheStrategy<?, ?> cacheStrategy, CacheStatistics statistics, CacheExceptionHandler exceptionHandler) {
        this(context, cacheStrategy, statistics, exceptionHandler, DEFAULT_MAINTENANCE_INTERVAL_MS);
    }

    /**
     * Creates a new CacheMaintenanceManager with the specified maintenance interval.
     *
     * @param context               The application context
     * @param cacheStrategy         The cache strategy to maintain
     * @param statistics            The statistics object for tracking cache performance
     * @param exceptionHandler      The exception handler for reporting maintenance errors
     * @param maintenanceIntervalMs The maintenance interval in milliseconds
     */
    public CacheMaintenanceManager(Context context, CacheStrategy<?, ?> cacheStrategy, CacheStatistics statistics, CacheExceptionHandler exceptionHandler, long maintenanceIntervalMs) {
        this.context = context;
        this.cacheStrategy = cacheStrategy;
        this.statistics = statistics;
        this.exceptionHandler = exceptionHandler;
        this.maintenanceIntervalMs = maintenanceIntervalMs;
        this.scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());

        LogUtils.d(TAG, "Created cache maintenance manager with interval: " + maintenanceIntervalMs + "ms");
    }

    /**
     * Starts scheduled maintenance.
     */
    public void startScheduledMaintenance() {
        LogUtils.d(TAG, "Starting scheduled maintenance");

        // Schedule periodic maintenance
        scheduledExecutor.scheduleAtFixedRate(this::performMaintenance, maintenanceIntervalMs, maintenanceIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Performs maintenance on the cache.
     * This includes removing expired entries, cleaning up corrupted files, and optimizing storage.
     */
    public void performMaintenance() {
        // Check if maintenance is already running
        if (maintenanceRunning.getAndSet(true)) {
            LogUtils.d(TAG, "Maintenance already running, skipping");
            return;
        }

        LogUtils.d(TAG, "Performing cache maintenance");

        try {
            // Notify listener that maintenance is starting
            if (maintenanceListener != null) {
                mainHandler.post(() -> maintenanceListener.onMaintenanceStarted());
            }

            // Get initial statistics
            int initialSize = cacheStrategy.size();

            // Perform maintenance on the cache strategy
            cacheStrategy.performMaintenance();

            // Get final statistics
            int finalSize = cacheStrategy.size();
            int entriesRemoved = initialSize - finalSize;

            // Log maintenance results
            LogUtils.d(TAG, "Maintenance complete: removed " + entriesRemoved + " entries, " + finalSize + " entries remain");

            // Notify listener that maintenance is complete
            if (maintenanceListener != null) {
                mainHandler.post(() -> maintenanceListener.onMaintenanceCompleted(entriesRemoved));
            }

        } catch (Exception e) {
            exceptionHandler.handleException(e, "Error performing cache maintenance");

            // Notify listener that maintenance failed
            if (maintenanceListener != null) {
                mainHandler.post(() -> maintenanceListener.onMaintenanceFailed(e));
            }
        } finally {
            // Reset maintenance running flag
            maintenanceRunning.set(false);
        }
    }

    /**
     * Performs a deep cleanup of the cache.
     * This includes removing all entries and cleaning up storage.
     */
    public void performDeepCleanup() {
        // Check if maintenance is already running
        if (maintenanceRunning.getAndSet(true)) {
            LogUtils.d(TAG, "Maintenance already running, skipping deep cleanup");
            return;
        }

        LogUtils.d(TAG, "Performing deep cache cleanup");

        try {
            // Notify listener that deep cleanup is starting
            if (maintenanceListener != null) {
                mainHandler.post(() -> maintenanceListener.onDeepCleanupStarted());
            }

            // Get initial statistics
            int initialSize = cacheStrategy.size();

            // Clear the cache
            cacheStrategy.clear();

            // Get final statistics
            int finalSize = cacheStrategy.size();
            int entriesRemoved = initialSize - finalSize;

            // Log deep cleanup results
            LogUtils.d(TAG, "Deep cleanup complete: removed " + entriesRemoved + " entries, " + finalSize + " entries remain");

            // Notify listener that deep cleanup is complete
            if (maintenanceListener != null) {
                mainHandler.post(() -> maintenanceListener.onDeepCleanupCompleted(entriesRemoved));
            }

        } catch (Exception e) {
            exceptionHandler.handleException(e, "Error performing deep cache cleanup");

            // Notify listener that deep cleanup failed
            if (maintenanceListener != null) {
                mainHandler.post(() -> maintenanceListener.onDeepCleanupFailed(e));
            }
        } finally {
            // Reset maintenance running flag
            maintenanceRunning.set(false);
        }
    }

    /**
     * Stops scheduled maintenance.
     */
    public void stopScheduledMaintenance() {
        LogUtils.d(TAG, "Stopping scheduled maintenance");

        // Shutdown the executor
        scheduledExecutor.shutdown();
    }

    /**
     * Shuts down the maintenance manager.
     */
    public void shutdown() {
        LogUtils.d(TAG, "Shutting down cache maintenance manager");

        // Stop scheduled maintenance
        stopScheduledMaintenance();
    }

    /**
     * Listener for maintenance events.
     */
    public interface MaintenanceListener {
        /**
         * Called when maintenance starts.
         */
        void onMaintenanceStarted();

        /**
         * Called when maintenance completes successfully.
         *
         * @param entriesRemoved The number of entries removed during maintenance
         */
        void onMaintenanceCompleted(int entriesRemoved);

        /**
         * Called when maintenance fails.
         *
         * @param error The error that occurred
         */
        void onMaintenanceFailed(Exception error);

        /**
         * Called when deep cleanup starts.
         */
        void onDeepCleanupStarted();

        /**
         * Called when deep cleanup completes successfully.
         *
         * @param entriesRemoved The number of entries removed during deep cleanup
         */
        void onDeepCleanupCompleted(int entriesRemoved);

        /**
         * Called when deep cleanup fails.
         *
         * @param error The error that occurred
         */
        void onDeepCleanupFailed(Exception error);
    }

    /**
     * Adapter class for MaintenanceListener that provides empty implementations of all methods.
     * This allows clients to override only the methods they are interested in.
     */
    public static class MaintenanceListenerAdapter implements MaintenanceListener {
        @Override
        public void onMaintenanceStarted() {
        }

        @Override
        public void onMaintenanceCompleted(int entriesRemoved) {
        }

        @Override
        public void onMaintenanceFailed(Exception error) {
        }

        @Override
        public void onDeepCleanupStarted() {
        }

        @Override
        public void onDeepCleanupCompleted(int entriesRemoved) {
        }

        @Override
        public void onDeepCleanupFailed(Exception error) {
        }
    }
}