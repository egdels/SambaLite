package de.schliweb.sambalite.cache.test;

import android.content.Context;
import de.schliweb.sambalite.cache.entry.CacheEntry;
import de.schliweb.sambalite.cache.statistics.CacheStatistics;
import de.schliweb.sambalite.cache.strategy.DiskCacheStrategy;
import de.schliweb.sambalite.cache.strategy.HybridCacheStrategy;
import de.schliweb.sambalite.cache.strategy.MemoryCacheStrategy;
import de.schliweb.sambalite.util.LogUtils;

import java.io.Serializable;

/**
 * A simple test class to verify that the cache performance metrics are working correctly.
 * This class can be used to test the cache hit rate calculation and ensure that cache hits
 * are being properly counted.
 */
public class CachePerformanceTest {
    private static final String TAG = "CachePerformanceTest";

    /**
     * Runs a simple test to verify that cache hits are being properly counted.
     *
     * @param context The application context
     */
    public static void testCacheHitRate(Context context) {
        LogUtils.d(TAG, "Starting cache hit rate test");

        // Create statistics object
        CacheStatistics statistics = new CacheStatistics();

        // Create cache strategies
        MemoryCacheStrategy<String, Serializable> memoryStrategy = new MemoryCacheStrategy<>(statistics);
        DiskCacheStrategy<String, Serializable> diskStrategy = new DiskCacheStrategy<>(context, statistics);
        HybridCacheStrategy<String, Serializable> hybridStrategy = new HybridCacheStrategy<>(memoryStrategy, diskStrategy, statistics);

        // Create test data
        String testKey = "test_key";
        String testValue = "test_value";
        long expirationTime = System.currentTimeMillis() + 60000; // 1 minute from now
        CacheEntry<Serializable> testEntry = new CacheEntry<>(testValue, expirationTime);

        // Put the test entry in the cache
        hybridStrategy.put(testKey, testEntry);

        // Get the test entry from the cache (should be a hit)
        CacheEntry<Serializable> retrievedEntry = hybridStrategy.get(testKey);

        // Verify that the entry was retrieved successfully
        if (retrievedEntry != null) {
            LogUtils.d(TAG, "Test entry retrieved successfully");

            // Get another entry that doesn't exist (should be a miss)
            hybridStrategy.get("nonexistent_key");

            // Log the cache statistics
            LogUtils.d(TAG, "Cache requests: " + statistics.getCacheRequests());
            LogUtils.d(TAG, "Cache hits: " + statistics.getCacheHits());
            LogUtils.d(TAG, "Cache misses: " + statistics.getCacheMisses());
            LogUtils.d(TAG, "Hit rate: " + String.format("%.2f%%", statistics.getHitRate() * 100));

            // Verify that the hit rate is correct
            double expectedHitRate = 0.5; // 1 hit out of 2 requests = 50%
            double actualHitRate = statistics.getHitRate();

            if (Math.abs(actualHitRate - expectedHitRate) < 0.01) {
                LogUtils.d(TAG, "Hit rate is correct: " + String.format("%.2f%%", actualHitRate * 100));
            } else {
                LogUtils.e(TAG, "Hit rate is incorrect: expected " + String.format("%.2f%%", expectedHitRate * 100) + ", actual " + String.format("%.2f%%", actualHitRate * 100));
            }
        } else {
            LogUtils.e(TAG, "Test entry not retrieved");
        }

        // Clean up
        hybridStrategy.clear();
        LogUtils.d(TAG, "Cache hit rate test completed");
    }
}