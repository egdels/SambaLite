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
 * Handles file list specific caching operations.
 * This class centralizes the file list caching logic that was previously scattered
 * throughout the original IntelligentCacheManager.
 */
public class FileListCacheOperations {
    private static final String TAG = "FileListCacheOperations";

    // Default TTL for file list cache entries (5 minutes)
    private static final long DEFAULT_TTL_MS = TimeUnit.MINUTES.toMillis(5);

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
     * Creates a new FileListCacheOperations.
     *
     * @param cacheStrategy          The cache strategy to use
     * @param keyGenerator           The key generator for creating cache keys
     * @param serializationValidator The serialization validator for validating serializable objects
     * @param exceptionHandler       The exception handler for reporting errors
     * @param statistics             The statistics object for tracking cache performance
     */
    public FileListCacheOperations(CacheStrategy<String, Serializable> cacheStrategy, CacheKeyGenerator keyGenerator, SerializationValidator serializationValidator, CacheExceptionHandler exceptionHandler, CacheStatistics statistics) {
        this.cacheStrategy = cacheStrategy;
        this.keyGenerator = keyGenerator;
        this.serializationValidator = serializationValidator;
        this.exceptionHandler = exceptionHandler;
        this.statistics = statistics;

        LogUtils.d(TAG, "Created file list cache operations");
    }

    /**
     * Caches a file list for a specific connection and path.
     *
     * @param connection The SMB connection
     * @param path       The path to the directory
     * @param files      The list of files to cache
     */
    public void cacheFileList(SmbConnection connection, String path, List<SmbFileItem> files) {
        LogUtils.d(TAG, "Caching file list for connection: " + connection.getName() + ", path: " + path + " (" + files.size() + " files)");

        // Generate cache key
        String cacheKey = keyGenerator.generateFileListKey(connection, path);

        // Create a defensive copy to avoid issues with the original list
        ArrayList<SmbFileItem> filesCopy = new ArrayList<>(files);

        // Validate that the copy was created correctly
        LogUtils.d(TAG, "Created files copy of type: " + filesCopy.getClass().getSimpleName() + " with " + filesCopy.size() + " items");

        // Validate that the list is serializable
        if (!serializationValidator.validateSerializable(filesCopy, cacheKey)) {
            LogUtils.e(TAG, "File list is not serializable, not caching");
            return;
        }

        // Create cache entry
        long expirationTime = System.currentTimeMillis() + DEFAULT_TTL_MS;
        CacheEntry<Serializable> entry = new CacheEntry<>(filesCopy, expirationTime);

        // Store in cache
        cacheStrategy.put(cacheKey, entry);

        LogUtils.d(TAG, "Successfully cached file list for key: " + cacheKey);
    }

    /**
     * Retrieves a cached file list for a specific connection and path.
     *
     * @param connection The SMB connection
     * @param path       The path to the directory
     * @return The cached file list, or null if not found or expired
     */
    @SuppressWarnings("unchecked")
    public List<SmbFileItem> getCachedFileList(SmbConnection connection, String path) {
        LogUtils.d(TAG, "Getting cached file list for connection: " + connection.getName() + ", path: " + path);

        // Generate cache key
        String cacheKey = keyGenerator.generateFileListKey(connection, path);

        // Get from cache
        CacheEntry<Serializable> entry = cacheStrategy.get(cacheKey);

        if (entry != null) {
            // Validate that the entry contains a list of SmbFileItem
            if (entry.getData() instanceof List) {
                try {
                    List<SmbFileItem> files = (List<SmbFileItem>) entry.getData();
                    LogUtils.d(TAG, "Retrieved cached file list for key: " + cacheKey + " (" + files.size() + " files)");

                    // Create a defensive copy to avoid issues with the cached list
                    return new ArrayList<>(files);
                } catch (ClassCastException e) {
                    exceptionHandler.handleException(e, "Invalid cache entry type for file list");
                }
            } else {
                LogUtils.e(TAG, "Cache entry is not a List for key: " + cacheKey + ", type: " + (entry.getData() != null ? entry.getData().getClass().getSimpleName() : "null"));
            }
        }

        LogUtils.d(TAG, "No cached file list found for key: " + cacheKey);
        return null;
    }

    /**
     * Invalidates the file list cache for a specific connection and path.
     *
     * @param connection The SMB connection
     * @param path       The path to the directory
     */
    public void invalidateFileList(SmbConnection connection, String path) {
        LogUtils.d(TAG, "Invalidating file list cache for connection: " + connection.getName() + ", path: " + path);

        // Generate cache key
        String cacheKey = keyGenerator.generateFileListKey(connection, path);

        // Remove from cache
        cacheStrategy.remove(cacheKey);

        LogUtils.d(TAG, "Invalidated file list cache for key: " + cacheKey);
    }

    /**
     * Invalidates all file list caches for a specific connection.
     *
     * @param connection The SMB connection
     */
    public void invalidateAllFileLists(SmbConnection connection) {
        LogUtils.d(TAG, "Invalidating all file list caches for connection: " + connection.getName());

        // Generate invalidation pattern
        String pattern = keyGenerator.generateInvalidationPattern(connection, "");

        // Remove matching entries
        int count = cacheStrategy.removePattern(pattern);

        LogUtils.d(TAG, "Invalidated " + count + " file list caches for connection: " + connection.getName());
    }

    /**
     * Preloads common file lists for a specific connection and path.
     * This can be used to improve performance by caching file lists that are likely to be accessed soon.
     *
     * @param connection The SMB connection
     * @param path       The path to the directory
     */
    public void preloadCommonFileLists(SmbConnection connection, String path) {
        // This is a placeholder for future implementation
        // In a real implementation, this would preload file lists for common subdirectories
        LogUtils.d(TAG, "Preloading common file lists for connection: " + connection.getName() + ", path: " + path);
    }
}