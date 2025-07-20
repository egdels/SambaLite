package de.schliweb.sambalite.cache.strategy;

import de.schliweb.sambalite.cache.entry.CacheEntry;
import de.schliweb.sambalite.cache.statistics.CacheStatistics;
import de.schliweb.sambalite.util.LogUtils;
import lombok.Getter;

/**
 * A hybrid cache strategy that combines memory and disk caching.
 * This strategy first checks the memory cache, then falls back to the disk cache if not found.
 * It provides the best balance between performance and persistence.
 *
 * @param <K> The type of keys used for cache entries
 * @param <V> The type of values stored in the cache
 */
public class HybridCacheStrategy<K, V> implements CacheStrategy<K, V> {
    private static final String TAG = "HybridCacheStrategy";

    // The memory cache strategy
    @Getter
    private final MemoryCacheStrategy<K, V> memoryStrategy;

    // The disk cache strategy
    @Getter
    private final DiskCacheStrategy<K, V> diskStrategy;

    // Statistics for monitoring cache performance
    private final CacheStatistics statistics;

    /**
     * Creates a new HybridCacheStrategy with the specified memory and disk strategies.
     *
     * @param memoryStrategy The memory cache strategy
     * @param diskStrategy   The disk cache strategy
     * @param statistics     The statistics object for tracking cache performance
     */
    public HybridCacheStrategy(MemoryCacheStrategy<K, V> memoryStrategy, DiskCacheStrategy<K, V> diskStrategy, CacheStatistics statistics) {
        this.memoryStrategy = memoryStrategy;
        this.diskStrategy = diskStrategy;
        this.statistics = statistics;

        LogUtils.d(TAG, "Created hybrid cache strategy");
    }

    @Override
    public void put(K key, CacheEntry<V> entry) {
        LogUtils.d(TAG, "Putting entry in hybrid cache with key: " + key);

        // Store in both memory and disk
        memoryStrategy.put(key, entry);
        diskStrategy.put(key, entry);
    }

    @Override
    public CacheEntry<V> get(K key) {
        LogUtils.d(TAG, "Getting entry from hybrid cache with key: " + key);
        statistics.incrementCacheRequests();

        // First try memory cache
        CacheEntry<V> entry = memoryStrategy.get(key);

        if (entry != null) {
            // Increment cache hits counter
            statistics.incrementCacheHits();
            LogUtils.d(TAG, "Cache hit in memory for key: " + key + ", total hits: " + statistics.getCacheHits());
            return entry;
        }

        // If not in memory, try disk cache
        entry = diskStrategy.get(key);

        if (entry != null) {
            // Increment cache hits counter
            statistics.incrementCacheHits();
            // Store in memory cache for faster access next time
            memoryStrategy.put(key, entry);
            LogUtils.d(TAG, "Cache hit on disk for key: " + key + ", stored in memory cache, total hits: " + statistics.getCacheHits());
            return entry;
        }

        statistics.incrementCacheMisses();
        LogUtils.d(TAG, "Cache miss in hybrid cache for key: " + key + ", total misses: " + statistics.getCacheMisses());
        return null;
    }

    @Override
    public CacheEntry<V> remove(K key) {
        LogUtils.d(TAG, "Removing entry from hybrid cache with key: " + key);

        // Remove from both memory and disk
        CacheEntry<V> memoryEntry = memoryStrategy.remove(key);
        CacheEntry<V> diskEntry = diskStrategy.remove(key);

        // Return the entry that was found (prefer memory entry if both exist)
        return memoryEntry != null ? memoryEntry : diskEntry;
    }

    @Override
    public int removePattern(String keyPattern) {
        LogUtils.d(TAG, "Removing entries matching pattern: " + keyPattern);

        // Remove from both memory and disk
        int memoryCount = memoryStrategy.removePattern(keyPattern);
        int diskCount = diskStrategy.removePattern(keyPattern);

        LogUtils.d(TAG, "Removed " + memoryCount + " entries from memory and " + diskCount + " entries from disk");

        // Return the total number of entries removed
        return memoryCount + diskCount;
    }

    @Override
    public void clear() {
        LogUtils.d(TAG, "Clearing hybrid cache");

        // Clear both memory and disk
        memoryStrategy.clear();
        diskStrategy.clear();
    }

    @Override
    public int size() {
        // Return the total number of entries in both caches
        // Note: This might count some entries twice if they exist in both caches
        return memoryStrategy.size() + diskStrategy.size();
    }

    @Override
    public void performMaintenance() {
        LogUtils.d(TAG, "Performing maintenance on hybrid cache");

        // Perform maintenance on both memory and disk
        memoryStrategy.performMaintenance();
        diskStrategy.performMaintenance();

        // Aggregate valid entries from both caches
        int memoryValidEntries = memoryStrategy.size();
        int diskValidEntries = diskStrategy.size();
        int totalValidEntries = memoryValidEntries + diskValidEntries;

        // Update statistics with the total valid entries
        statistics.setValidEntries(totalValidEntries);

        LogUtils.d(TAG, "Maintenance complete: " + memoryValidEntries + " valid entries in memory, " + diskValidEntries + " valid entries on disk, " + totalValidEntries + " total valid entries");

        // Log detailed cache statistics for debugging
        logCacheStatistics();
    }

    /**
     * Logs detailed cache statistics for debugging purposes.
     * This provides a comprehensive view of the cache state.
     */
    private void logCacheStatistics() {
        StringBuilder stats = new StringBuilder();
        stats.append("[DEBUG_LOG] CACHE STATISTICS:\n");

        // Cache size metrics
        stats.append("Memory Usage:\n");
        stats.append("- Memory Entries: ").append(statistics.getMemoryEntries()).append("\n");
        stats.append("- Disk Size: ").append(statistics.getDiskSizeBytes()).append(" bytes\n");
        stats.append("- Valid Entries: ").append(statistics.getValidEntries()).append("\n");
        stats.append("- Expired Entries: ").append(statistics.getExpiredEntries()).append("\n");

        // Cache performance metrics
        long requests = statistics.getCacheRequests();
        long hits = statistics.getCacheHits();
        long misses = statistics.getCacheMisses();
        double hitRate = statistics.getHitRate();

        stats.append("Cache Performance:\n");
        stats.append("- Total Requests: ").append(requests).append("\n");
        stats.append("- Cache Hits: ").append(hits).append("\n");
        stats.append("- Cache Misses: ").append(misses).append("\n");
        stats.append("- Hit Rate: ").append(String.format("%.2f%%", hitRate * 100)).append("\n");
        stats.append("- Hit Rate Calculation: ").append(hits).append("/").append(requests).append(" = ").append(String.format("%.2f", hitRate)).append("\n");

        // Operation statistics
        stats.append("Operations:\n");
        stats.append("- Put Operations: ").append(statistics.getPutOperations()).append("\n");
        stats.append("- Get Operations: ").append(statistics.getGetOperations()).append("\n");
        stats.append("- Remove Operations: ").append(statistics.getRemoveOperations()).append("\n");

        // Error statistics
        int totalErrors = statistics.getSerializationErrors() + statistics.getDeserializationErrors() + statistics.getDiskWriteErrors() + statistics.getDiskReadErrors();

        stats.append("Errors:\n");
        if (totalErrors > 0) {
            stats.append("- Total Errors: ").append(totalErrors).append("\n");
            stats.append("- Serialization Errors: ").append(statistics.getSerializationErrors()).append("\n");
            stats.append("- Deserialization Errors: ").append(statistics.getDeserializationErrors()).append("\n");
            stats.append("- Disk Write Errors: ").append(statistics.getDiskWriteErrors()).append("\n");
            stats.append("- Disk Read Errors: ").append(statistics.getDiskReadErrors()).append("\n");
        } else {
            stats.append("- No errors detected\n");
        }

        LogUtils.d(TAG, stats.toString());
    }

    @Override
    public void shutdown() {
        LogUtils.d(TAG, "Shutting down hybrid cache strategy");

        // Shutdown both memory and disk
        memoryStrategy.shutdown();
        diskStrategy.shutdown();
    }

}