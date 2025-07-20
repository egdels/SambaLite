package de.schliweb.sambalite.cache.strategy;

import de.schliweb.sambalite.cache.entry.CacheEntry;

/**
 * Interface defining a caching strategy.
 * Different implementations can provide different caching behaviors (memory-only, disk-only, hybrid).
 *
 * @param <K> The type of keys used for cache entries
 * @param <V> The type of values stored in the cache
 */
public interface CacheStrategy<K, V> {

    /**
     * Puts a value in the cache.
     *
     * @param key   The key for the cache entry
     * @param entry The cache entry to store
     */
    void put(K key, CacheEntry<V> entry);

    /**
     * Gets a value from the cache.
     *
     * @param key The key for the cache entry
     * @return The cache entry, or null if not found
     */
    CacheEntry<V> get(K key);

    /**
     * Removes a value from the cache.
     *
     * @param key The key for the cache entry to remove
     * @return The removed entry, or null if not found
     */
    CacheEntry<V> remove(K key);

    /**
     * Removes all entries matching a pattern.
     *
     * @param keyPattern The pattern to match keys against
     * @return The number of entries removed
     */
    int removePattern(String keyPattern);

    /**
     * Clears all entries from the cache.
     */
    void clear();

    /**
     * Gets the number of entries in the cache.
     *
     * @return The number of entries
     */
    int size();

    /**
     * Performs maintenance operations on the cache.
     * This might include removing expired entries, optimizing storage, etc.
     */
    void performMaintenance();

    /**
     * Shuts down the cache strategy, releasing any resources.
     */
    void shutdown();
}