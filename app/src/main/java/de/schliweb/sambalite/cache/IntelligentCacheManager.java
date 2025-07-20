package de.schliweb.sambalite.cache;

import android.content.Context;
import de.schliweb.sambalite.cache.entry.CacheEntry;
import de.schliweb.sambalite.cache.exception.CacheExceptionHandler;
import de.schliweb.sambalite.cache.key.CacheKeyGenerator;
import de.schliweb.sambalite.cache.loader.CacheLoader;
import de.schliweb.sambalite.cache.maintenance.CacheMaintenanceManager;
import de.schliweb.sambalite.cache.operations.FileListCacheOperations;
import de.schliweb.sambalite.cache.operations.SearchCacheOperations;
import de.schliweb.sambalite.cache.serialization.SerializationValidator;
import de.schliweb.sambalite.cache.statistics.CacheStatistics;
import de.schliweb.sambalite.cache.strategy.CacheStrategy;
import de.schliweb.sambalite.cache.strategy.DiskCacheStrategy;
import de.schliweb.sambalite.cache.strategy.HybridCacheStrategy;
import de.schliweb.sambalite.cache.strategy.MemoryCacheStrategy;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.util.LogUtils;

import java.io.Serializable;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Refactored version of the IntelligentCacheManager.
 * This class serves as a facade for all the specialized components, providing a simplified API
 * for client code while delegating to the specialized components internally.
 */
public class IntelligentCacheManager {
    private static final String TAG = "IntelligentCacheManager";

    // Singleton instance
    private static IntelligentCacheManager instance;

    // Core components
    private final CacheStrategy<String, Serializable> cacheStrategy;
    private final CacheKeyGenerator keyGenerator;
    private final SerializationValidator serializationValidator;
    private final CacheExceptionHandler exceptionHandler;
    private final CacheStatistics statistics;
    private final CacheMaintenanceManager maintenanceManager;

    // Specialized operations
    private final FileListCacheOperations fileListOperations;
    private final SearchCacheOperations searchOperations;

    // Executor for background operations
    private final ScheduledExecutorService executor;

    /**
     * Private constructor to prevent direct instantiation.
     *
     * @param context The application context
     */
    private IntelligentCacheManager(Context context) {
        LogUtils.d(TAG, "Creating IntelligentCacheManager");

        // Create statistics
        this.statistics = new CacheStatistics();

        // Create exception handler
        this.exceptionHandler = new CacheExceptionHandler(statistics);

        // Create serialization validator
        this.serializationValidator = new SerializationValidator(exceptionHandler);

        // Create key generator
        this.keyGenerator = new CacheKeyGenerator(exceptionHandler);

        // Create cache strategies
        MemoryCacheStrategy<String, Serializable> memoryStrategy = new MemoryCacheStrategy<>(statistics);
        DiskCacheStrategy<String, Serializable> diskStrategy = new DiskCacheStrategy<>(context, statistics);
        this.cacheStrategy = new HybridCacheStrategy<>(memoryStrategy, diskStrategy, statistics);

        // Create specialized operations
        this.fileListOperations = new FileListCacheOperations(cacheStrategy, keyGenerator, serializationValidator, exceptionHandler, statistics);
        this.searchOperations = new SearchCacheOperations(cacheStrategy, keyGenerator, serializationValidator, exceptionHandler, statistics);

        // Create maintenance manager
        this.maintenanceManager = new CacheMaintenanceManager(context, cacheStrategy, statistics, exceptionHandler);

        // Create executor for background operations
        this.executor = Executors.newScheduledThreadPool(1);

        // Start scheduled maintenance
        maintenanceManager.startScheduledMaintenance();

        LogUtils.d(TAG, "IntelligentCacheManager created");
    }

    /**
     * Initializes the IntelligentCacheManager with the application context.
     * This must be called before getInstance() is called.
     *
     * @param context The application context
     */
    public static void initialize(Context context) {
        if (instance == null) {
            synchronized (IntelligentCacheManager.class) {
                if (instance == null) {
                    instance = new IntelligentCacheManager(context);
                }
            }
        }
    }

    /**
     * Gets the singleton instance of the IntelligentCacheManager.
     *
     * @return The singleton instance
     * @throws IllegalStateException if initialize() has not been called
     */
    public static IntelligentCacheManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("IntelligentCacheManager not initialized. Call initialize() first.");
        }
        return instance;
    }

    /**
     * Stores data in the cache with the default TTL.
     *
     * @param key  The cache key
     * @param data The data to cache
     * @param <T>  The type of data
     */
    public <T extends Serializable> void put(String key, T data) {
        LogUtils.d(TAG, "Putting data in cache with key: " + key);

        // Validate data
        if (!serializationValidator.validateSerializable(data, key)) {
            LogUtils.e(TAG, "Data is not serializable, not caching");
            return;
        }

        // Create cache entry
        long expirationTime = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(30);
        CacheEntry<Serializable> entry = new CacheEntry<>(data, expirationTime);

        // Store in cache
        cacheStrategy.put(key, entry);
    }

    /**
     * Stores data in the cache with a custom TTL.
     *
     * @param key   The cache key
     * @param data  The data to cache
     * @param ttlMs The time-to-live in milliseconds
     * @param <T>   The type of data
     */
    public <T extends Serializable> void put(String key, T data, long ttlMs) {
        LogUtils.d(TAG, "Putting data in cache with key: " + key + ", TTL: " + ttlMs + "ms");

        // Validate data
        if (!serializationValidator.validateSerializable(data, key)) {
            LogUtils.e(TAG, "Data is not serializable, not caching");
            return;
        }

        // Create cache entry
        long expirationTime = System.currentTimeMillis() + ttlMs;
        CacheEntry<Serializable> entry = new CacheEntry<>(data, expirationTime);

        // Store in cache
        cacheStrategy.put(key, entry);
    }

    /**
     * Retrieves data from the cache.
     *
     * @param key  The cache key
     * @param type The class of the data
     * @param <T>  The type of data
     * @return The cached data, or null if not found or expired
     */
    @SuppressWarnings("unchecked")
    public <T extends Serializable> T get(String key, Class<T> type) {
        LogUtils.d(TAG, "Getting data from cache with key: " + key);

        // Get from cache
        CacheEntry<Serializable> entry = cacheStrategy.get(key);

        if (entry != null) {
            // Validate type
            if (type.isInstance(entry.getData())) {
                LogUtils.d(TAG, "Cache hit for key: " + key);
                return (T) entry.getData();
            } else {
                LogUtils.e(TAG, "Cache entry type mismatch for key: " + key + ", expected: " + type.getSimpleName() + ", actual: " + entry.getData().getClass().getSimpleName());
            }
        }

        LogUtils.d(TAG, "Cache miss for key: " + key);
        return null;
    }

    /**
     * Removes a cache entry.
     *
     * @param key The cache key
     */
    public void remove(String key) {
        LogUtils.d(TAG, "Removing cache entry with key: " + key);
        cacheStrategy.remove(key);
    }

    /**
     * Removes all cache entries matching a pattern.
     *
     * @param keyPattern The pattern to match keys against
     * @return The number of entries removed
     */
    public int removePattern(String keyPattern) {
        LogUtils.d(TAG, "Removing cache entries matching pattern: " + keyPattern);
        return cacheStrategy.removePattern(keyPattern);
    }

    /**
     * Clears all cache entries.
     */
    public void clearAll() {
        LogUtils.d(TAG, "Clearing all cache entries");
        cacheStrategy.clear();
    }

    /**
     * Preloads data into the cache using a loader.
     *
     * @param key    The cache key
     * @param loader The loader to use
     * @param <T>    The type of data
     */
    public <T extends Serializable> void preload(String key, CacheLoader<T> loader) {
        LogUtils.d(TAG, "Preloading data for key: " + key);

        // Execute loader in background
        executor.submit(() -> {
            try {
                T data = loader.load();
                put(key, data);
                LogUtils.d(TAG, "Preloaded data for key: " + key);
            } catch (Exception e) {
                exceptionHandler.handleException(e, "Error preloading data for key: " + key);
            }
        });
    }

    /**
     * Gets the cache statistics.
     *
     * @return The cache statistics
     */
    public CacheStatistics.CacheStatisticsSnapshot getStatistics() {
        return statistics.createSnapshot();
    }

    /**
     * Performs maintenance on the cache.
     */
    public void performMaintenance() {
        LogUtils.d(TAG, "Performing maintenance");
        maintenanceManager.performMaintenance();
    }

    /**
     * Performs a deep cleanup of the cache.
     */
    public void performDeepCleanup() {
        LogUtils.d(TAG, "Performing deep cleanup");
        maintenanceManager.performDeepCleanup();
    }

    /**
     * Shuts down the cache manager.
     */
    public void shutdown() {
        LogUtils.d(TAG, "Shutting down");

        // Shutdown maintenance manager
        maintenanceManager.shutdown();

        // Shutdown executor
        executor.shutdown();

        // Shutdown cache strategy
        cacheStrategy.shutdown();
    }

    // File list operations

    /**
     * Caches a file list for a specific connection and path.
     *
     * @param connection The SMB connection
     * @param path       The path to the directory
     * @param files      The list of files to cache
     */
    public void cacheFileList(SmbConnection connection, String path, List<SmbFileItem> files) {
        fileListOperations.cacheFileList(connection, path, files);
    }

    /**
     * Retrieves a cached file list for a specific connection and path.
     *
     * @param connection The SMB connection
     * @param path       The path to the directory
     * @return The cached file list, or null if not found or expired
     */
    public List<SmbFileItem> getCachedFileList(SmbConnection connection, String path) {
        return fileListOperations.getCachedFileList(connection, path);
    }

    /**
     * Invalidates the file list cache for a specific connection and path.
     *
     * @param connection The SMB connection
     * @param path       The path to the directory
     */
    public void invalidateFileList(SmbConnection connection, String path) {
        fileListOperations.invalidateFileList(connection, path);
    }

    /**
     * Invalidates all file list caches for a specific connection.
     *
     * @param connection The SMB connection
     */
    public void invalidateConnection(SmbConnection connection) {
        fileListOperations.invalidateAllFileLists(connection);
        searchOperations.invalidateAllSearchCaches(connection);
    }

    /**
     * Preloads common file lists for a specific connection and path.
     *
     * @param connection The SMB connection
     * @param path       The path to the directory
     */
    public void preloadCommonFileLists(SmbConnection connection, String path) {
        fileListOperations.preloadCommonFileLists(connection, path);
    }

    // Search operations

    /**
     * Caches search results for a specific connection, path, and search parameters.
     *
     * @param connection        The SMB connection
     * @param path              The path to the directory
     * @param query             The search query
     * @param searchType        The type of search (0=all, 1=files only, 2=folders only)
     * @param includeSubfolders Whether to include subfolders in the search
     * @param results           The search results to cache
     */
    public void cacheSearchResults(SmbConnection connection, String path, String query, int searchType, boolean includeSubfolders, List<SmbFileItem> results) {
        searchOperations.cacheSearchResults(connection, path, query, searchType, includeSubfolders, results);
    }

    /**
     * Retrieves cached search results for a specific connection, path, and search parameters.
     *
     * @param connection        The SMB connection
     * @param path              The path to the directory
     * @param query             The search query
     * @param searchType        The type of search (0=all, 1=files only, 2=folders only)
     * @param includeSubfolders Whether to include subfolders in the search
     * @return The cached search results, or null if not found or expired
     */
    public List<SmbFileItem> getCachedSearchResults(SmbConnection connection, String path, String query, int searchType, boolean includeSubfolders) {
        return searchOperations.getCachedSearchResults(connection, path, query, searchType, includeSubfolders);
    }

    /**
     * Invalidates the search cache for a specific connection and path.
     *
     * @param connection The SMB connection
     * @param path       The path to the directory
     */
    public void invalidateSearchCache(SmbConnection connection, String path) {
        searchOperations.invalidateSearchCache(connection, path);
    }

    /**
     * Preloads common search queries for a specific connection and path.
     *
     * @param connection The SMB connection
     * @param path       The path to the directory
     */
    public void preloadCommonSearches(SmbConnection connection, String path) {
        searchOperations.preloadCommonSearches(connection, path);
    }

    /**
     * Generates a search cache key.
     *
     * @param connection        The SMB connection
     * @param path              The path to the directory
     * @param query             The search query
     * @param searchType        The type of search (0=all, 1=files only, 2=folders only)
     * @param includeSubfolders Whether to include subfolders in the search
     * @return The search cache key
     */
    public String generateSearchCacheKey(SmbConnection connection, String path, String query, int searchType, boolean includeSubfolders) {
        return keyGenerator.generateSearchKey(connection, path, query, searchType, includeSubfolders);
    }

    /**
     * Invalidates the cache synchronously.
     * This is useful for ensuring that cache entries are removed before continuing.
     *
     * @param keyPattern The pattern to match keys against
     */
    public void invalidateSync(String keyPattern) {
        LogUtils.d(TAG, "Synchronously invalidating cache entries matching pattern: " + keyPattern);
        cacheStrategy.removePattern(keyPattern);
    }

    /**
     * Runs a test to verify that the cache hit rate calculation is working correctly.
     * This is useful for debugging cache performance issues.
     */
    public void testCachePerformance() {
        LogUtils.d(TAG, "Running cache performance test");

        // Create a test key and value
        String testKey = "performance_test_" + System.currentTimeMillis();
        String testValue = "test_value";

        // Put the test entry in the cache
        put(testKey, testValue);

        // Get the test entry from the cache (should be a hit)
        get(testKey, String.class);

        // Get a non-existent entry (should be a miss)
        get("nonexistent_key_" + System.currentTimeMillis(), String.class);

        // Log the cache statistics
        CacheStatistics.CacheStatisticsSnapshot stats = getStatistics();
        LogUtils.d(TAG, "Cache Performance Test Results:");
        LogUtils.d(TAG, "- Total Requests: " + stats.getCacheRequests());
        LogUtils.d(TAG, "- Cache Hits: " + stats.getCacheHits());
        LogUtils.d(TAG, "- Cache Misses: " + stats.getCacheMisses());
        LogUtils.d(TAG, "- Hit Rate: " + String.format("%.2f%%", stats.getHitRate() * 100));

        // Clean up the test entry
        remove(testKey);
    }
}