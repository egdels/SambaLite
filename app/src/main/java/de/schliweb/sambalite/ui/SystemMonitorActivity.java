package de.schliweb.sambalite.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.SambaLiteApp;
import de.schliweb.sambalite.cache.IntelligentCacheManager;
import de.schliweb.sambalite.cache.statistics.CacheStatistics;
import de.schliweb.sambalite.network.AdvancedNetworkOptimizer;
import de.schliweb.sambalite.ui.utils.LoadingIndicator;
import de.schliweb.sambalite.util.EnhancedFileUtils;
import de.schliweb.sambalite.util.LogUtils;
import de.schliweb.sambalite.util.SimplePerformanceMonitor;
import de.schliweb.sambalite.util.SmartErrorHandler;

import java.util.Locale;

/**
 * Advanced system monitoring and administration activity for SambaLite.
 * Provides comprehensive insights into app performance, cache, network, and errors.
 */
public class SystemMonitorActivity extends AppCompatActivity {

    private static final String TAG = "SystemMonitorActivity";

    private TextView statusOverview;
    private LoadingIndicator loadingIndicator;
    private SmartErrorHandler errorHandler;

    public static Intent createIntent(Context context) {
        return new Intent(context, SystemMonitorActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize loading indicator
        loadingIndicator = new LoadingIndicator(this);
        LogUtils.d(TAG, "Loading indicator initialized");

        // Configure edge-to-edge display using backward-compatible helper (Android 15+ safe)
        EdgeToEdge.enable(this);

        LogUtils.d(TAG, "SystemMonitorActivity created");

        // Initialize SmartErrorHandler
        errorHandler = ((SambaLiteApp) getApplication()).getErrorHandler();

        // Use XML layout instead of programmatic creation
        setContentView(R.layout.activity_system_monitor);

        // Set up the toolbar
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
                getSupportActionBar().setTitle(getString(R.string.system_monitor_title));
            }
        }

        // Get the TextView from layout
        statusOverview = findViewById(R.id.status_overview);

        if (statusOverview == null) {
            LogUtils.e(TAG, "status_overview TextView not found in layout, using fallback");

            // Record UI error
            if (errorHandler != null) {
                errorHandler.recordError(new RuntimeException("status_overview TextView not found"), "SystemMonitorActivity.onCreate", SmartErrorHandler.ErrorSeverity.HIGH);
            }
            createSystemMonitorLayoutFallback();
        } else {
            LogUtils.d(TAG, "status_overview TextView found successfully");
        }

        refreshSystemStatus();
    }

    private void createSystemMonitorLayoutFallback() {
        // Fallback method for programmatic layout creation if XML layout fails
        LogUtils.w(TAG, "Using fallback programmatic layout creation");

        androidx.core.widget.NestedScrollView scrollView = new androidx.core.widget.NestedScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setPadding(16, 16, 16, 16);
        scrollView.setBackgroundColor(getResources().getColor(android.R.color.background_light, null));

        statusOverview = new TextView(this);
        statusOverview.setPadding(20, 20, 20, 20);
        statusOverview.setTextSize(14);
        statusOverview.setTypeface(android.graphics.Typeface.MONOSPACE);
        // Use theme-aware colors instead of hardcoded ones
        int textColor = android.graphics.Color.BLACK;
        int backgroundColor = android.graphics.Color.WHITE;

        try {
            // Try to get theme-aware colors
            android.util.TypedValue typedValue = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.textColorPrimary, typedValue, true);
            if (typedValue.data != 0) {
                textColor = typedValue.data;
            }

            getTheme().resolveAttribute(android.R.attr.colorBackground, typedValue, true);
            if (typedValue.data != 0) {
                backgroundColor = typedValue.data;
            }
        } catch (Exception e) {
            LogUtils.w(TAG, "Could not resolve theme colors, using defaults: " + e.getMessage());

            // Record theme error (low severity)
            if (errorHandler != null) {
                errorHandler.recordError(e, "SystemMonitorActivity.setupFallbackTextView", SmartErrorHandler.ErrorSeverity.LOW);
            }
        }

        statusOverview.setTextColor(textColor);
        statusOverview.setBackgroundColor(backgroundColor);

        scrollView.addView(statusOverview);
        setContentView(scrollView);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("System Monitor");
        }

        LogUtils.d(TAG, "Fallback layout created");
    }

    private void refreshSystemStatus() {
        LogUtils.d(TAG, "Refreshing system status");

        if (statusOverview == null) {
            LogUtils.e(TAG, "statusOverview TextView is null, cannot refresh status");

            // Record critical UI error
            if (errorHandler != null) {
                errorHandler.recordError(new RuntimeException("statusOverview TextView is null"), "SystemMonitorActivity.refreshSystemStatus", SmartErrorHandler.ErrorSeverity.CRITICAL);
            }
            return;
        }

        // Show loading indicator while gathering system information
        loadingIndicator.show("Gathering system information...");

        try {
            SambaLiteApp app = (SambaLiteApp) getApplication();

            String fullStatus = "=== SambaLite System Monitor ===\n" + "Generated: " + getCurrentTimeString() + "\n\n" +

                    // Update system overview
                    getSystemOverview(app) + "\n\n" +

                    // Update performance metrics
                    getPerformanceMetrics() + "\n\n" +

                    // Update cache statistics
                    getCacheStatistics(app) + "\n\n" +

                    // Update network status
                    getNetworkStatus(app) + "\n\n" +

                    // Update error summary
                    getErrorSummary(app);

            statusOverview.setText(fullStatus);

            // Hide loading indicator after successful update
            loadingIndicator.hide();

            LogUtils.i(TAG, "System status updated successfully");

        } catch (Exception e) {
            LogUtils.e(TAG, "Failed to refresh system status: " + e.getMessage());

            // Record system monitoring error
            if (errorHandler != null) {
                errorHandler.recordError(e, "SystemMonitorActivity.refreshSystemStatus", SmartErrorHandler.ErrorSeverity.HIGH);
            }

            String errorStatus = "=== System Monitor Error ===\n\n" + "Failed to load system information:\n" + e.getMessage() + "\n\n" + "Time: " + getCurrentTimeString() + "\n\n" + "Please try refreshing the page or restart the app.";
            statusOverview.setText(errorStatus);

            // Hide loading indicator after error
            loadingIndicator.hide();
        }
    }

    private String getSystemOverview(SambaLiteApp app) {
        StringBuilder overview = new StringBuilder();

        overview.append("=== SambaLite System Status ===\n");
        overview.append("Last Updated: ").append(getCurrentTimeString()).append("\n\n");

        SambaLiteApp.ApplicationHealthStatus health = app.getHealthStatus();
        overview.append("System Health: ").append(health.overallHealthy ? "✓ HEALTHY" : "⚠ ISSUES").append("\n");
        overview.append("- Dependency Injection: ").append(health.dependencyInjectionReady ? "✓" : "✗").append("\n");
        overview.append("- Performance Monitoring: ").append(health.performanceMonitoringReady ? "✓" : "✗").append("\n");
        overview.append("- Cache System: ").append(health.cacheSystemReady ? "✓" : "✗").append("\n");
        overview.append("- Network Optimizer: ").append(health.networkOptimizerReady ? "✓" : "✗").append("\n");
        overview.append("- Error Handler: ").append(health.errorHandlerReady ? "✓" : "✗").append("\n");

        return overview.toString();
    }

    private String getPerformanceMetrics() {

        String metrics = "=== Performance Metrics ===\n" + "Memory: " + SimplePerformanceMonitor.getMemoryInfo() + "\n" + "Device: " + SimplePerformanceMonitor.getDeviceInfo() + "\n" + "Performance: " + SimplePerformanceMonitor.getPerformanceStats() + "\n";

        return metrics;
    }

    private String getCacheStatistics(SambaLiteApp app) {
        StringBuilder stats = new StringBuilder();

        stats.append("=== Cache Statistics ===\n");

        IntelligentCacheManager cacheManager = app.getCacheManager();
        if (cacheManager != null) {
            // Run a cache performance test to verify hit rate calculation
            cacheManager.testCachePerformance();

            // Perform maintenance to trigger debug logging
            cacheManager.performMaintenance();

            CacheStatistics.CacheStatisticsSnapshot cacheStats = cacheManager.getStatistics();

            // Memory usage statistics
            stats.append("\nMemory Usage:\n");
            stats.append("- Memory Entries: ").append(cacheStats.getMemoryEntries()).append("\n");
            stats.append("- Disk Size: ").append(EnhancedFileUtils.formatFileSize(cacheStats.getDiskSizeBytes())).append("\n");
            stats.append("- Valid Entries: ").append(cacheStats.getValidEntries()).append("\n");
            stats.append("- Expired Entries: ").append(cacheStats.getExpiredEntries()).append("\n");

            // Cache performance statistics
            stats.append("\nCache Performance:\n");
            stats.append("- Total Requests: ").append(cacheStats.getCacheRequests()).append("\n");
            stats.append("- Cache Hits: ").append(cacheStats.getCacheHits()).append("\n");
            stats.append("- Cache Misses: ").append(cacheStats.getCacheMisses()).append("\n");
            stats.append("- Hit Rate: ").append(String.format(Locale.US, "%.2f%%", cacheStats.getHitRate() * 100)).append("\n");

            // Operation statistics
            stats.append("\nOperations:\n");
            stats.append("- Put Operations: ").append(cacheStats.getPutOperations()).append("\n");
            stats.append("- Get Operations: ").append(cacheStats.getGetOperations()).append("\n");
            stats.append("- Remove Operations: ").append(cacheStats.getRemoveOperations()).append("\n");

            // Error statistics
            int totalErrors = cacheStats.getTotalErrors();
            stats.append("\nErrors:\n");
            if (totalErrors > 0) {
                stats.append("- Total Errors: ").append(totalErrors).append("\n");
                stats.append("- Serialization Errors: ").append(cacheStats.getSerializationErrors()).append("\n");
                stats.append("- Deserialization Errors: ").append(cacheStats.getDeserializationErrors()).append("\n");
                stats.append("- Disk Write Errors: ").append(cacheStats.getDiskWriteErrors()).append("\n");
                stats.append("- Disk Read Errors: ").append(cacheStats.getDiskReadErrors()).append("\n");
            } else {
                stats.append("✓ No errors detected\n");
            }
        } else {
            stats.append("Cache system not available\n");
        }

        return stats.toString();
    }

    private String getNetworkStatus(SambaLiteApp app) {
        StringBuilder status = new StringBuilder();

        status.append("=== Network Status ===\n");

        AdvancedNetworkOptimizer networkOptimizer = app.getNetworkOptimizer();
        if (networkOptimizer != null) {
            AdvancedNetworkOptimizer.NetworkStatus netStatus = networkOptimizer.getCurrentNetworkStatus();
            status.append("Network Available: ").append(netStatus.isConnected ? "✓ Yes" : "✗ No").append("\n");
            status.append("Network Type: ").append(netStatus.connectionType).append("\n");
            status.append("Quality: ").append(netStatus.networkQuality).append("\n");
            status.append("Bandwidth: ").append(EnhancedFileUtils.formatFileSize(netStatus.estimatedBandwidth)).append("/s\n");
            status.append("Metered Network: ").append(netStatus.isMetered ? "Yes" : "No").append("\n");

            AdvancedNetworkOptimizer.OptimalSettings settings = networkOptimizer.getOptimalSettings();
            status.append("\nOptimal Settings:\n");
            status.append("- Connection Timeout: ").append(settings.connectionTimeoutMs).append("ms\n");
            status.append("- Read Timeout: ").append(settings.readTimeoutMs).append("ms\n");
            status.append("- Max Connections: ").append(settings.maxConcurrentConnections).append("\n");
            status.append("- Chunk Size: ").append(EnhancedFileUtils.formatFileSize(settings.chunkSize)).append("\n");
            status.append("- Use Compression: ").append(settings.useCompression ? "Yes" : "No").append("\n");
            status.append("- Aggressive Caching: ").append(settings.aggressiveCaching ? "Yes" : "No").append("\n");
        } else {
            status.append("Network optimizer not available\n");
        }

        return status.toString();
    }

    private String getErrorSummary(SambaLiteApp app) {
        StringBuilder summary = new StringBuilder();

        summary.append("=== Error Summary ===\n");

        // Use SmartErrorHandler for comprehensive error reporting
        SmartErrorHandler errorHandler = app.getErrorHandler();

        if (errorHandler != null) {
            SmartErrorHandler.ErrorStats stats = errorHandler.getErrorStats();

            // Get recent errors (if any)
            if (stats.totalErrors > 0) {
                summary.append("Error Details:\n");
                summary.append("- Total Errors: ").append(stats.totalErrors).append("\n");
                summary.append("- Critical Errors: ").append(stats.criticalErrors).append("\n");
                summary.append("- Network Errors: ").append(stats.networkErrors).append("\n");
                summary.append("- SMB Errors: ").append(stats.smbErrors).append("\n");
                summary.append("- Recent Entries: ").append(stats.recentErrors).append("\n");
                summary.append("\nError tracking is active and automatically collecting data.\n");
                summary.append("Errors are categorized by type and severity for better debugging.\n");
            } else {
                summary.append("✓ No errors recorded since app start - System running smoothly!\n");
            }
        } else {
            summary.append("SmartErrorHandler not available\n");
        }

        return summary.toString();
    }

    private String getCurrentTimeString() {
        return android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", System.currentTimeMillis()).toString();
    }

    @Override
    public boolean onSupportNavigateUp() {
        getOnBackPressedDispatcher().onBackPressed();
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSystemStatus();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_system_monitor, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here.
        int id = item.getItemId();

        if (id == R.id.action_refresh) {
            // Refresh action
            LogUtils.d(TAG, "Refresh menu item clicked");
            refreshSystemStatus();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
