package de.schliweb.sambalite.cache.strategy;

import de.schliweb.sambalite.cache.entry.CacheEntry;
import de.schliweb.sambalite.cache.statistics.CacheStatistics;
import de.schliweb.sambalite.util.LogUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A memory-based cache strategy implementation using an LRU (Least Recently Used) approach.
 * This strategy stores cache entries in memory and evicts the least recently used entries when the cache size limit is reached.
 *
 * @param <K> The type of keys used for cache entries
 * @param <V> The type of values stored in the cache
 */
public class MemoryCacheStrategy<K, V> implements CacheStrategy<K, V> {
    private static final String TAG = "MemoryCacheStrategy";

    // Default maximum number of entries in the memory cache
    private static final int DEFAULT_MAX_ENTRIES = 100;

    // The memory cache, using a thread-safe map
    private final Map<K, CacheEntry<V>> cache;

    // Access order tracking for LRU eviction
    private final LinkedHashMap<K, Long> accessOrder;

    // Lock for synchronizing access to the accessOrder map
    private final ReadWriteLock accessOrderLock;

    // Maximum number of entries in the memory cache
    private final int maxEntries;

    // Statistics for monitoring cache performance
    private final CacheStatistics statistics;

    /**
     * Creates a new MemoryCacheStrategy with default settings.
     *
     * @param statistics The statistics object for tracking cache performance
     */
    public MemoryCacheStrategy(CacheStatistics statistics) {
        this(DEFAULT_MAX_ENTRIES, statistics);
    }

    /**
     * Creates a new MemoryCacheStrategy with the specified maximum number of entries.
     *
     * @param maxEntries The maximum number of entries in the memory cache
     * @param statistics The statistics object for tracking cache performance
     */
    public MemoryCacheStrategy(int maxEntries, CacheStatistics statistics) {
        this.cache = new ConcurrentHashMap<>();
        this.accessOrder = new LinkedHashMap<>();
        this.accessOrderLock = new ReentrantReadWriteLock();
        this.maxEntries = maxEntries;
        this.statistics = statistics;

        LogUtils.d(TAG, "Created memory cache strategy with max entries: " + maxEntries);
    }

    @Override
    public void put(K key, CacheEntry<V> entry) {
        LogUtils.d(TAG, "Putting entry in memory cache with key: " + key);
        statistics.incrementPutOperations();

        // Add to cache
        cache.put(key, entry);

        // Update access order
        updateAccessOrder(key);

        // Check if we need to evict entries
        if (cache.size() > maxEntries) {
            evictLeastRecentlyUsed();
        }

        // Update statistics
        statistics.setMemoryEntries(cache.size());
    }

    @Override
    public CacheEntry<V> get(K key) {
        LogUtils.d(TAG, "Getting entry from memory cache with key: " + key);
        statistics.incrementGetOperations();
        statistics.incrementCacheRequests();

        CacheEntry<V> entry = cache.get(key);

        if (entry != null) {
            if (entry.isValid()) {
                // Update access order for LRU
                updateAccessOrder(key);

                // Update statistics
                statistics.incrementCacheHits();
                entry.updateLastAccessTime();

                LogUtils.d(TAG, "Cache hit in memory for key: " + key);
                return entry;
            } else {
                // Remove expired entry
                remove(key);
                LogUtils.d(TAG, "Expired cache entry removed for key: " + key);
            }
        }

        statistics.incrementCacheMisses();
        LogUtils.d(TAG, "Cache miss in memory for key: " + key);
        return null;
    }

    @Override
    public CacheEntry<V> remove(K key) {
        LogUtils.d(TAG, "Removing entry from memory cache with key: " + key);
        statistics.incrementRemoveOperations();

        // Remove from cache
        CacheEntry<V> removed = cache.remove(key);

        // Remove from access order
        accessOrderLock.writeLock().lock();
        try {
            accessOrder.remove(key);
        } finally {
            accessOrderLock.writeLock().unlock();
        }

        // Update statistics
        statistics.setMemoryEntries(cache.size());

        return removed;
    }

    @Override
    public int removePattern(String keyPattern) {
        LogUtils.d(TAG, "Removing entries matching pattern: " + keyPattern);

        List<K> keysToRemove = new ArrayList<>();

        // Find keys matching the pattern
        for (K key : cache.keySet()) {
            if (key.toString().contains(keyPattern)) {
                keysToRemove.add(key);
            }
        }

        // Remove matching keys
        int count = 0;
        for (K key : keysToRemove) {
            if (remove(key) != null) {
                count++;
            }
        }

        LogUtils.d(TAG, "Removed " + count + " entries matching pattern: " + keyPattern);
        return count;
    }

    @Override
    public void clear() {
        LogUtils.d(TAG, "Clearing memory cache");

        // Clear cache
        cache.clear();

        // Clear access order
        accessOrderLock.writeLock().lock();
        try {
            accessOrder.clear();
        } finally {
            accessOrderLock.writeLock().unlock();
        }

        // Update statistics
        statistics.setMemoryEntries(0);
    }

    @Override
    public int size() {
        return cache.size();
    }

    @Override
    public void performMaintenance() {
        LogUtils.d(TAG, "Performing maintenance on memory cache");

        List<K> keysToRemove = new ArrayList<>();

        // Find expired entries
        for (Map.Entry<K, CacheEntry<V>> entry : cache.entrySet()) {
            if (!entry.getValue().isValid()) {
                keysToRemove.add(entry.getKey());
            }
        }

        // Remove expired entries
        int count = 0;
        for (K key : keysToRemove) {
            if (remove(key) != null) {
                count++;
            }
        }

        // Update statistics
        int validCount = cache.size();
        statistics.setValidEntries(validCount);
        statistics.setExpiredEntries(count);

        LogUtils.d(TAG, "Maintenance complete: removed " + count + " expired entries, " + validCount + " valid entries remain");
    }

    @Override
    public void shutdown() {
        LogUtils.d(TAG, "Shutting down memory cache strategy");
        clear();
    }

    /**
     * Updates the access order for a key.
     *
     * @param key The key to update
     */
    private void updateAccessOrder(K key) {
        accessOrderLock.writeLock().lock();
        try {
            // Remove existing entry if present
            accessOrder.remove(key);
            // Add to end (most recently used)
            accessOrder.put(key, System.currentTimeMillis());
        } finally {
            accessOrderLock.writeLock().unlock();
        }
    }

    /**
     * Evicts the least recently used entry from the cache.
     */
    private void evictLeastRecentlyUsed() {
        accessOrderLock.writeLock().lock();
        try {
            if (!accessOrder.isEmpty()) {
                // Get the first entry (least recently used)
                K oldestKey = accessOrder.keySet().iterator().next();

                // Remove from cache and access order
                cache.remove(oldestKey);
                accessOrder.remove(oldestKey);

                LogUtils.d(TAG, "Evicted least recently used entry with key: " + oldestKey);
            }
        } finally {
            accessOrderLock.writeLock().unlock();
        }
    }
}