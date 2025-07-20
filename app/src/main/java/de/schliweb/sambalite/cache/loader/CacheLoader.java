package de.schliweb.sambalite.cache.loader;

/**
 * Interface for loading data into the cache.
 * This is used for preloading operations and lazy loading of cache entries.
 *
 * @param <T> The type of data to be loaded
 */
public interface CacheLoader<T> {

    /**
     * Loads data for caching.
     * This method is called when data needs to be loaded into the cache.
     *
     * @return The loaded data
     * @throws Exception If an error occurs during loading
     */
    T load() throws Exception;
}