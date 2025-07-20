package de.schliweb.sambalite.cache.operations;

import de.schliweb.sambalite.cache.entry.CacheEntry;
import de.schliweb.sambalite.cache.exception.CacheExceptionHandler;
import de.schliweb.sambalite.cache.key.CacheKeyGenerator;
import de.schliweb.sambalite.cache.serialization.SerializationValidator;
import de.schliweb.sambalite.cache.statistics.CacheStatistics;
import de.schliweb.sambalite.cache.strategy.CacheStrategy;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.util.LogUtils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Handles search result specific caching operations.
 * This class centralizes the search result caching logic that was previously scattered
 * throughout the original IntelligentCacheManager.
 */
public class SearchCacheOperations {
    private static final String TAG = "SearchCacheOperations";

    // Default TTL for search result cache entries (10 minutes)
    private static final long DEFAULT_TTL_MS = TimeUnit.MINUTES.toMillis(10);

    // Optimal TTL for search result cache entries (30 minutes)
    private static final long OPTIMAL_TTL_MS = TimeUnit.MINUTES.toMillis(30);

    // The cache strategy to use
    private final CacheStrategy<String, Serializable> cacheStrategy;

    // The key generator for creating cache keys
    private final CacheKeyGenerator keyGenerator;

    // The serialization validator for validating serializable objects
    private final SerializationValidator serializationValidator;

    // The exception handler for reporting errors
    private final CacheExceptionHandler exceptionHandler;

    // Statistics for monitoring cache performance
    private final CacheStatistics statistics;

    /**
     * Creates a new SearchCacheOperations.
     *
     * @param cacheStrategy          The cache strategy to use
     * @param keyGenerator           The key generator for creating cache keys
     * @param serializationValidator The serialization validator for validating serializable objects
     * @param exceptionHandler       The exception handler for reporting errors
     * @param statistics             The statistics object for tracking cache performance
     */
    public SearchCacheOperations(CacheStrategy<String, Serializable> cacheStrategy, CacheKeyGenerator keyGenerator, SerializationValidator serializationValidator, CacheExceptionHandler exceptionHandler, CacheStatistics statistics) {
        this.cacheStrategy = cacheStrategy;
        this.keyGenerator = keyGenerator;
        this.serializationValidator = serializationValidator;
        this.exceptionHandler = exceptionHandler;
        this.statistics = statistics;

        LogUtils.d(TAG, "Created search cache operations");
    }

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
        LogUtils.d(TAG, "Caching search results for connection: " + connection.getName() + ", path: " + path + ", query: " + query + ", type: " + searchType + ", includeSubfolders: " + includeSubfolders + " (" + results.size() + " results)");

        // Generate cache key
        String cacheKey = keyGenerator.generateSearchKey(connection, path, query, searchType, includeSubfolders);

        // Create a defensive copy to avoid issues with the original list
        ArrayList<SmbFileItem> resultsCopy = new ArrayList<>(results);

        // Validate that the copy was created correctly
        LogUtils.d(TAG, "Created results copy of type: " + resultsCopy.getClass().getSimpleName() + " with " + resultsCopy.size() + " items");

        // Validate that the list is serializable
        if (!serializationValidator.validateSerializable(resultsCopy, cacheKey)) {
            LogUtils.e(TAG, "Search results are not serializable, not caching");
            return;
        }

        // Determine TTL based on result size and query complexity
        long ttl = determineOptimalTtl(query, results.size());

        // Create cache entry
        long expirationTime = System.currentTimeMillis() + ttl;
        CacheEntry<Serializable> entry = new CacheEntry<>(resultsCopy, expirationTime);

        // Store in cache
        cacheStrategy.put(cacheKey, entry);

        LogUtils.d(TAG, "Successfully cached search results for key: " + cacheKey + " with TTL: " + ttl + "ms");
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
    @SuppressWarnings("unchecked")
    public List<SmbFileItem> getCachedSearchResults(SmbConnection connection, String path, String query, int searchType, boolean includeSubfolders) {
        LogUtils.d(TAG, "Getting cached search results for connection: " + connection.getName() + ", path: " + path + ", query: " + query + ", type: " + searchType + ", includeSubfolders: " + includeSubfolders);

        // Generate cache key
        String cacheKey = keyGenerator.generateSearchKey(connection, path, query, searchType, includeSubfolders);

        // Get from cache
        CacheEntry<Serializable> entry = cacheStrategy.get(cacheKey);

        if (entry != null) {
            // Validate that the entry contains a list of SmbFileItem
            if (entry.getData() instanceof List) {
                try {
                    List<SmbFileItem> results = (List<SmbFileItem>) entry.getData();
                    LogUtils.d(TAG, "Retrieved cached search results for key: " + cacheKey + " (" + results.size() + " results)");

                    // Create a defensive copy to avoid issues with the cached list
                    return new ArrayList<>(results);
                } catch (ClassCastException e) {
                    exceptionHandler.handleException(e, "Invalid cache entry type for search results");
                }
            } else {
                LogUtils.e(TAG, "Cache entry is not a List for key: " + cacheKey + ", type: " + (entry.getData() != null ? entry.getData().getClass().getSimpleName() : "null"));
            }
        }

        LogUtils.d(TAG, "No cached search results found for key: " + cacheKey);
        return null;
    }

    /**
     * Invalidates the search cache for a specific connection and path.
     * This should be called when files are added, deleted, or modified in the directory.
     *
     * @param connection The SMB connection
     * @param path       The path to the directory
     */
    public void invalidateSearchCache(SmbConnection connection, String path) {
        LogUtils.d(TAG, "Invalidating search cache for connection: " + connection.getName() + ", path: " + path);

        // Generate invalidation pattern
        String pattern = "search_conn_" + connection.getId() + "_path_" + keyGenerator.sanitizePath(path);

        // Remove matching entries
        int count = cacheStrategy.removePattern(pattern);

        LogUtils.d(TAG, "Invalidated " + count + " search cache entries for connection: " + connection.getName() + ", path: " + path);
    }

    /**
     * Invalidates all search caches for a specific connection.
     *
     * @param connection The SMB connection
     */
    public void invalidateAllSearchCaches(SmbConnection connection) {
        LogUtils.d(TAG, "Invalidating all search caches for connection: " + connection.getName());

        // Generate invalidation pattern
        String pattern = "search_conn_" + connection.getId();

        // Remove matching entries
        int count = cacheStrategy.removePattern(pattern);

        LogUtils.d(TAG, "Invalidated " + count + " search cache entries for connection: " + connection.getName());
    }

    /**
     * Preloads common search queries for a specific connection and path.
     * This can be used to improve performance by caching search results that are likely to be accessed soon.
     *
     * @param connection The SMB connection
     * @param path       The path to the directory
     */
    public void preloadCommonSearches(SmbConnection connection, String path) {
        // This is a placeholder for future implementation
        // In a real implementation, this would preload search results for common queries
        LogUtils.d(TAG, "Preloading common searches for connection: " + connection.getName() + ", path: " + path);
    }

    /**
     * Determines the optimal TTL for search results based on query complexity and result size.
     *
     * @param query      The search query
     * @param resultSize The size of the search results
     * @return The optimal TTL in milliseconds
     */
    private long determineOptimalTtl(String query, int resultSize) {
        // Simple heuristic: longer TTL for simpler queries and smaller result sets
        if (query.length() < 5 && resultSize < 100) {
            return OPTIMAL_TTL_MS;
        } else {
            return DEFAULT_TTL_MS;
        }
    }
}