package de.schliweb.sambalite.util;

import android.os.Build;
import android.os.SystemClock;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unified performance monitor for SambaLite.
 * Tracks basic and advanced performance metrics depending on the operation type.
 * Consolidates functionality from both SimplePerformanceMonitor and AdvancedPerformanceMonitor.
 */
public class SimplePerformanceMonitor {

    private static final String TAG = "PerformanceMonitor";

    // Simple monitoring (for UI operations)
    private static final AtomicLong operationCount = new AtomicLong(0);
    private static final AtomicLong totalTime = new AtomicLong(0);
    // Advanced monitoring (for cache/network operations)
    private static final Map<String, Long> operationStartTimes = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> operationCounts = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> totalExecutionTimes = new ConcurrentHashMap<>();
    private static final Map<String, Long> memorySnapshots = new ConcurrentHashMap<>();
    private static final long GC_THRESHOLD_MS = 5000; // 5 seconds
    private static final boolean isEnabled = true;
    private static long lastOperationStart = 0;
    private static long lastGcTime = 0;

    /**
     * Starts monitoring an operation (simple mode for UI operations).
     */
    public static void startOperation(String operationName) {
        if (!isEnabled) return;

        // Simple monitoring for UI operations  
        if (isUIOperation(operationName)) {
            lastOperationStart = System.currentTimeMillis();
            LogUtils.d(TAG, "Started operation: " + operationName);
        } else {
            // Advanced monitoring for cache/network operations
            startAdvancedOperation(operationName);
        }
    }

    /**
     * Ends monitoring an operation (simple mode for UI operations).
     */
    public static void endOperation(String operationName) {
        if (!isEnabled) return;

        // Simple monitoring for UI operations
        if (isUIOperation(operationName)) {
            if (lastOperationStart == 0) return;

            long duration = System.currentTimeMillis() - lastOperationStart;
            operationCount.incrementAndGet();
            totalTime.addAndGet(duration);

            String performance = getPerformanceLevel(duration);
            LogUtils.i(TAG, "Completed operation: " + operationName + " in " + duration + "ms (" + performance + ")");

            lastOperationStart = 0;
        } else {
            // Advanced monitoring for cache/network operations
            endAdvancedOperation(operationName);
        }
    }

    /**
     * Starts advanced monitoring with detailed metrics collection.
     */
    private static void startAdvancedOperation(String operationName) {
        if (!isEnabled) return;

        long startTime = SystemClock.elapsedRealtime();
        operationStartTimes.put(operationName, startTime);

        // Capture memory snapshot
        long memoryUsage = getCurrentMemoryUsage();
        memorySnapshots.put(operationName + "_start", memoryUsage);

        LogUtils.d(TAG, "Starting advanced operation: " + operationName + " | Memory: " + EnhancedFileUtils.formatFileSize(memoryUsage) + " | Available Processors: " + Runtime.getRuntime().availableProcessors());
    }

    /**
     * Ends advanced monitoring and calculates comprehensive metrics.
     */
    private static void endAdvancedOperation(String operationName) {
        if (!isEnabled) return;

        Long startTime = operationStartTimes.remove(operationName);
        if (startTime == null) {
            LogUtils.w(TAG, "No start time found for operation: " + operationName);
            return;
        }

        long endTime = SystemClock.elapsedRealtime();
        long duration = endTime - startTime;

        // Update operation statistics
        operationCounts.computeIfAbsent(operationName, k -> new AtomicLong(0)).incrementAndGet();
        totalExecutionTimes.computeIfAbsent(operationName, k -> new AtomicLong(0)).addAndGet(duration);

        // Memory usage analysis
        long startMemory = memorySnapshots.getOrDefault(operationName + "_start", 0L);
        long endMemory = getCurrentMemoryUsage();
        long memoryDelta = endMemory - startMemory;

        // Performance analysis
        String performanceLevel = getPerformanceLevel(duration);

        LogUtils.i(TAG, "Advanced operation completed: " + operationName + " | Duration: " + duration + "ms" + " | Performance: " + performanceLevel + " | Memory Delta: " + EnhancedFileUtils.formatFileSize(memoryDelta) + " | Final Memory: " + EnhancedFileUtils.formatFileSize(endMemory));

        // Check if GC is recommended
        if (shouldTriggerGC(memoryDelta)) {
            suggestGarbageCollection();
        }

        memorySnapshots.remove(operationName + "_start");
    }

    /**
     * Gets current memory usage information.
     */
    public static String getMemoryInfo() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();

        return String.format(Locale.US, "Memory: %s used / %s max (%.1f%% usage)", EnhancedFileUtils.formatFileSize(usedMemory), EnhancedFileUtils.formatFileSize(maxMemory), (usedMemory * 100.0 / maxMemory));
    }

    /**
     * Gets device information.
     */
    public static String getDeviceInfo() {
        return String.format(Locale.US, "Device: %s %s (Android %s, API %d)", Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT);
    }

    /**
     * Gets performance statistics.
     */
    public static String getPerformanceStats() {
        long count = operationCount.get();
        long total = totalTime.get();
        double average = count > 0 ? (double) total / count : 0;

        return String.format(Locale.US, "Performance: %d operations, avg %.1fms, total %.1fs", count, average, total / 1000.0);
    }

    // Helper methods

    /**
     * Determines if this is a UI operation (simple monitoring) or cache/network operation (advanced monitoring).
     */
    private static boolean isUIOperation(String operationName) {
        return operationName.contains("Activity") || operationName.contains("onCreate") || operationName.contains("fileClick") || operationName.contains("connectionClick");
    }

    private static long getCurrentMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static boolean shouldTriggerGC(long memoryDelta) {
        return memoryDelta > 50 * 1024 * 1024; // 50MB threshold
    }

    private static void suggestGarbageCollection() {
        long currentTime = SystemClock.elapsedRealtime();
        if (currentTime - lastGcTime > GC_THRESHOLD_MS) {
            LogUtils.w(TAG, "High memory usage detected, suggesting garbage collection");
            System.gc();
            lastGcTime = currentTime;
        }
    }

    private static String getPerformanceLevel(long durationMs) {
        if (durationMs < 100) return "EXCELLENT";
        if (durationMs < 500) return "GOOD";
        if (durationMs < 1000) return "FAIR";
        if (durationMs < 3000) return "POOR";
        return "CRITICAL";
    }
}
