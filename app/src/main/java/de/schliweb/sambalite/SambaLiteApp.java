package de.schliweb.sambalite;

import android.app.Application;
import de.schliweb.sambalite.cache.IntelligentCacheManager;
import de.schliweb.sambalite.di.AppComponent;
import de.schliweb.sambalite.di.DaggerAppComponent;
import de.schliweb.sambalite.network.AdvancedNetworkOptimizer;
import de.schliweb.sambalite.util.LogUtils;
import de.schliweb.sambalite.util.SambaLiteLifecycleTracker;
import de.schliweb.sambalite.util.SimplePerformanceMonitor;
import de.schliweb.sambalite.util.SmartErrorHandler;
import lombok.Getter;

/**
 * Enhanced main Application class for SambaLite.
 * Initializes Dagger component and other app-wide configurations.
 */
public class SambaLiteApp extends Application {

    /**
     * -- GETTER --
     * Gets the Dagger application component.
     */
    @Getter
    private AppComponent appComponent;
    /**
     * -- GETTER --
     * Gets the cache manager instance.
     */
    @Getter
    private IntelligentCacheManager cacheManager;
    /**
     * -- GETTER --
     * Gets the network optimizer instance.
     */
    @Getter
    private AdvancedNetworkOptimizer networkOptimizer;
    /**
     * -- GETTER --
     * Gets the error handler instance.
     */
    @Getter
    private SmartErrorHandler errorHandler;
    private SambaLiteLifecycleTracker lifecycleTracker;

    @Override
    public void onCreate() {
        super.onCreate();

        LogUtils.i("SambaLiteApp", "Starting SambaLite application initialization");

        try {
            // Initialize Timber logging first
            LogUtils.init(BuildConfig.DEBUG);
            LogUtils.i("SambaLiteApp", "Logging system initialized");

            // Initialize performance monitoring (static methods)
            LogUtils.i("SambaLiteApp", "Memory: " + SimplePerformanceMonitor.getMemoryInfo());
            LogUtils.i("SambaLiteApp", "Device: " + SimplePerformanceMonitor.getDeviceInfo());
            LogUtils.i("SambaLiteApp", "Performance monitoring initialized");

            // Initialize advanced systems
            IntelligentCacheManager.initialize(this);
            cacheManager = IntelligentCacheManager.getInstance();
            AdvancedNetworkOptimizer.initialize(this);
            networkOptimizer = AdvancedNetworkOptimizer.getInstance();
            errorHandler = SmartErrorHandler.getInstance();

            // Setup global error handling
            errorHandler.setupGlobalErrorHandler();

            LogUtils.i("SambaLiteApp", "Advanced systems initialized");

            // Initialize Dagger dependency injection
            appComponent = DaggerAppComponent.builder().application(this).build();
            appComponent.inject(this);
            LogUtils.i("SambaLiteApp", "Dependency injection initialized");

            // Initialize background-aware connection management
            lifecycleTracker = SambaLiteLifecycleTracker.getInstance();
            registerActivityLifecycleCallbacks(lifecycleTracker);

            lifecycleTracker.setLifecycleListener(new SambaLiteLifecycleTracker.LifecycleListener() {
                @Override
                public void onAppMovedToBackground() {
                    LogUtils.i("SambaLiteApp", "Eva's app moved to background - SMB connections may be affected");
                    // SMB connections will be automatically recreated on next use
                }

                @Override
                public void onAppMovedToForeground() {
                    LogUtils.i("SambaLiteApp", "Eva's app moved to foreground - ready for SMB operations");
                    // Connections will be automatically restored when needed
                }
            });
            LogUtils.i("SambaLiteApp", "Background-aware connection management initialized");

            LogUtils.i("SambaLiteApp", "SambaLite application fully initialized");

        } catch (Exception e) {
            LogUtils.e("SambaLiteApp", "Critical error during app initialization: " + e.getMessage());

            // Record critical error
            if (errorHandler != null) {
                errorHandler.recordError(e, "SambaLiteApp.onCreate", SmartErrorHandler.ErrorSeverity.CRITICAL);
            }

            throw e; // Re-throw to ensure app doesn't start in broken state
        }
    }

    @Override
    public void onTerminate() {
        LogUtils.i("SambaLiteApp", "Application terminating");

        // Save statistics before termination
        try {
            if (cacheManager != null) {
                cacheManager.shutdown();
            }
            if (networkOptimizer != null) {
                networkOptimizer.shutdown();
            }
            if (errorHandler != null) {
                errorHandler.saveErrorStats(this);
            }

            LogUtils.i("SambaLiteApp", "App statistics saved successfully");
        } catch (Exception e) {
            LogUtils.e("SambaLiteApp", "Error saving app statistics: " + e.getMessage());
        }

        super.onTerminate();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        LogUtils.w("SambaLiteApp", "Low memory condition detected");

        // Clear cache to free memory
        if (cacheManager != null) {
            cacheManager.clearAll();
            LogUtils.i("SambaLiteApp", "Cache cleared due to low memory");
        }

        // Force garbage collection
        System.gc();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        LogUtils.d("SambaLiteApp", "Memory trim requested with level: " + level);

        switch (level) {
            case TRIM_MEMORY_RUNNING_CRITICAL:
            case TRIM_MEMORY_UI_HIDDEN:
            case TRIM_MEMORY_BACKGROUND:
            case TRIM_MEMORY_MODERATE:
            case TRIM_MEMORY_COMPLETE:
                // Perform garbage collection for critical memory situations
                System.gc();
                LogUtils.w("SambaLiteApp", "Performed memory cleanup");
                break;
        }
    }

    /**
     * Gets application health status for monitoring.
     */
    public ApplicationHealthStatus getHealthStatus() {
        ApplicationHealthStatus status = new ApplicationHealthStatus();

        // Check if all systems are initialized
        status.dependencyInjectionReady = (appComponent != null);
        status.performanceMonitoringReady = true; // Performance monitoring is now static
        status.cacheSystemReady = (cacheManager != null);
        status.networkOptimizerReady = (networkOptimizer != null);
        status.errorHandlerReady = (errorHandler != null);
        status.overallHealthy = status.dependencyInjectionReady && status.performanceMonitoringReady && status.cacheSystemReady && status.networkOptimizerReady && status.errorHandlerReady;

        return status;
    }

    /**
     * Data class for application health monitoring.
     */
    public static class ApplicationHealthStatus {
        public boolean overallHealthy;
        public boolean dependencyInjectionReady;
        public boolean performanceMonitoringReady;
        public boolean cacheSystemReady;
        public boolean networkOptimizerReady;
        public boolean errorHandlerReady;

        @Override
        public String toString() {
            return "ApplicationHealthStatus{" + "dependencyInjection=" + dependencyInjectionReady + ", performanceMonitoring=" + performanceMonitoringReady + ", cacheSystem=" + cacheSystemReady + ", networkOptimizer=" + networkOptimizerReady + ", errorHandler=" + errorHandlerReady + ", overall=" + overallHealthy + '}';
        }
    }
}
