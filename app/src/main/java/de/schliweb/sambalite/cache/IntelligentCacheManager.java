package de.schliweb.sambalite.cache;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.util.LogUtils;
import de.schliweb.sambalite.util.SimplePerformanceMonitor;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IntelligentCacheManager provides a hybrid in-memory and disk-based caching solution
 * with support for time-to-live (TTL), automatic cleanup, LRU eviction, and statistics.
 * <p>
 * Features:
 * <ul>
 *   <li>Singleton access pattern for global cache management.</li>
 *   <li>Configurable memory and disk cache size limits.</li>
 *   <li>Automatic expiration and cleanup of cache entries.</li>
 *   <li>Asynchronous disk operations for performance.</li>
 *   <li>Support for caching generic Serializable objects and specialized file lists.</li>
 *   <li>Smart merging and invalidation of cache entries by pattern.</li>
 *   <li>Preloading support for background cache population.</li>
 *   <li>Comprehensive cache statistics reporting.</li>
 * </ul>
 * <p>
 * Usage:
 * <pre>
 *   IntelligentCacheManager.initialize(context);
 *   IntelligentCacheManager cache = IntelligentCacheManager.getInstance();
 *   cache.put("key", data);
 *   MyDataType data = cache.get("key", MyDataType.class);
 *   cache.invalidate("pattern");
 *   cache.clearAll();
 *   cache.shutdown();
 * </pre>
 * <p>
 * Thread Safety: All public methods are thread-safe.
 * <p>
 * Limitations: Hit rate calculation is a placeholder and may require further implementation.
 */
public class IntelligentCacheManager {

    private static final String TAG = "IntelligentCacheManager";
    private static final String CACHE_DIR = "intelligent_cache";
    private static final long DEFAULT_TTL_MS = 5 * 60 * 1000; // 5 minutes
    private static final long CLEANUP_INTERVAL_MS = 60 * 1000; // 1 minute
    private static final int MAX_MEMORY_CACHE_SIZE = 100;
    private static final long MAX_DISK_CACHE_SIZE = 50 * 1024 * 1024; // 50MB

    private static IntelligentCacheManager instance;
    private final Context context;
    private final ConcurrentHashMap<String, CacheEntry> memoryCache;
    private final ConcurrentLinkedQueue<String> accessOrder;
    private final ScheduledExecutorService cleanupExecutor;
    private final AtomicLong diskCacheSize;
    private final Handler mainHandler;
    private final ConcurrentHashMap<String, Object> fileLocks; // Synchronization for disk operations
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheRequests = new AtomicLong(0);
    private File cacheDir;

    private IntelligentCacheManager(Context context) {
        this.context = context.getApplicationContext();
        this.memoryCache = new ConcurrentHashMap<>();
        this.accessOrder = new ConcurrentLinkedQueue<>();
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor();
        this.diskCacheSize = new AtomicLong(0);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.fileLocks = new ConcurrentHashMap<>();

        initializeCacheDirectory();
        scheduleCleanupTasks();
        loadDiskCacheSize();
    }

    /**
     * Initializes the singleton instance of the IntelligentCacheManager.
     *
     * <p>This method ensures that the cache manager is initialized only once
     * and provides a global access point to the instance. It uses the provided
     * application context to set up the cache directory and other resources.
     *
     * <p>Logging is performed to indicate successful initialization.
     *
     * @param context The application context used to initialize the cache manager.
     *                Must not be null.
     */
    public static void initialize(Context context) {
        if (instance == null) {
            instance = new IntelligentCacheManager(context);
            // Check and clear cache if format has changed
            instance.validateCacheVersion();
            // Proactively clean up any corrupted cache files
            instance.cleanupCorruptedCacheFiles();
            LogUtils.i(TAG, "Intelligent cache manager initialized");
        }
    }

    /**
     * Gets the singleton instance.
     *
     * @return The singleton instance of IntelligentCacheManager
     * @throws IllegalStateException if initialize() has not been called
     */
    public static IntelligentCacheManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("IntelligentCacheManager not initialized. Call initialize() first.");
        }
        return instance;
    }

    /**
     * Validates cache version and clears disk cache if format has changed.
     */
    private void validateCacheVersion() {
        cleanupExecutor.submit(() -> {
            try {
                File[] files = cacheDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.getName().endsWith(".cache")) {
                            // Try to load one cache entry to check version
                            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
                                CacheEntry entry = (CacheEntry) ois.readObject();
                                if (entry.cacheVersion != 2) {
                                    LogUtils.w(TAG, "Detected old cache format (version " + entry.cacheVersion + "), clearing cache");
                                    clearDiskCache();
                                    return;
                                }
                            } catch (Exception e) {
                                LogUtils.w(TAG, "Invalid cache entry detected, clearing cache: " + e.getMessage());
                                clearDiskCache();
                                return;
                            }
                            break; // Only need to check one file
                        }
                    }
                }
            } catch (Exception e) {
                LogUtils.e(TAG, "Error validating cache version: " + e.getMessage());
            }
        });
    }

    /**
     * Stores data in cache with default TTL.
     */
    public <T extends Serializable> void put(String key, T data) {
        put(key, data, DEFAULT_TTL_MS);
    }

    /**
     * Stores data in the cache with a custom time-to-live (TTL).
     *
     * <p>This method allows storing a serializable object in the cache with a specified TTL.
     * The data is stored in both the in-memory cache and asynchronously on disk.
     * If the memory cache exceeds its maximum size, the least recently used (LRU) entry is evicted.
     *
     * <p>Logging is performed to track the operation, and performance monitoring is used
     * to measure the time taken for the cache put operation.
     *
     * @param <T>   The type of the data to be cached. Must implement {@link Serializable}.
     * @param key   A unique key identifying the cache entry.
     * @param data  The serializable data to be cached.
     * @param ttlMs The time-to-live for the cache entry in milliseconds.
     */
    public <T extends Serializable> void put(String key, T data, long ttlMs) {
        LogUtils.d(TAG, "Storing data in cache with key: " + key + ", TTL: " + ttlMs + "ms, data type: " + (data != null ? data.getClass().getSimpleName() : "null"));

        SimplePerformanceMonitor.startOperation("cache_put");

        try {
            // Validate that the data is actually serializable
            if (data != null && !(data instanceof Serializable)) {
                LogUtils.e(TAG, "Data is not serializable for key: " + key + ", type: " + data.getClass().getSimpleName());
                return;
            }

            // Deep validation for List data with enhanced error detection
            if (data instanceof List<?> list) {
                LogUtils.d(TAG, "Validating List data with " + list.size() + " items for key: " + key);

                for (int i = 0; i < Math.min(list.size(), 5); i++) { // Check first 5 items
                    Object item = list.get(i);
                    if (item == null) {
                        LogUtils.w(TAG, "Found null item at index " + i + " in list for key: " + key);
                        continue;
                    }

                    LogUtils.d(TAG, "List item " + i + ": " + item.getClass().getSimpleName());

                    if (!(item instanceof Serializable)) {
                        LogUtils.e(TAG, "Non-serializable item found at index " + i + " for key: " + key + ", type: " + item.getClass().getSimpleName());
                        return;
                    }

                    // Special validation for SmbFileItem with detailed field checking
                    if (item instanceof SmbFileItem fileItem) {
                        LogUtils.d(TAG, "SmbFileItem validation - name: " + fileItem.getName() + ", path: " + fileItem.getPath() + ", isDirectory: " + fileItem.isDirectory());

                        // Check if all fields are properly serializable with type validation
                        try {
                            if (fileItem.getName() != null) {
                                String nameClass = fileItem.getName().getClass().getSimpleName();
                                if (!(fileItem.getName() instanceof String)) {
                                    LogUtils.e(TAG, "SmbFileItem name is not String: " + nameClass + " - value: " + fileItem.getName());
                                    throw new ClassCastException("SmbFileItem name field is not String: " + nameClass);
                                }
                                LogUtils.d(TAG, "Name field validated as String: " + fileItem.getName());
                            }

                            if (fileItem.getPath() != null) {
                                String pathClass = fileItem.getPath().getClass().getSimpleName();
                                if (!(fileItem.getPath() instanceof String)) {
                                    LogUtils.e(TAG, "SmbFileItem path is not String: " + pathClass + " - value: " + fileItem.getPath());
                                    throw new ClassCastException("SmbFileItem path field is not String: " + pathClass);
                                }
                                LogUtils.d(TAG, "Path field validated as String: " + fileItem.getPath());
                            }

                            // Test serialization of this specific item
                            try {
                                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                                java.io.ObjectOutputStream testOos = new java.io.ObjectOutputStream(baos);
                                testOos.writeObject(fileItem);
                                testOos.close();
                                LogUtils.d(TAG, "SmbFileItem test serialization successful for item " + i);
                            } catch (Exception serTest) {
                                LogUtils.e(TAG, "SmbFileItem test serialization failed for item " + i + ": " + serTest.getMessage());
                                throw new ClassCastException("SmbFileItem serialization test failed: " + serTest.getMessage());
                            }

                        } catch (Exception fieldCheck) {
                            LogUtils.e(TAG, "SmbFileItem field validation failed: " + fieldCheck.getMessage());
                            LogUtils.e(TAG, "Field validation stack trace: " + android.util.Log.getStackTraceString(fieldCheck));
                            return;
                        }
                    }
                }

                // Test serialization of the entire list before proceeding
                try {
                    LogUtils.d(TAG, "Testing serialization of entire list...");
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    java.io.ObjectOutputStream testOos = new java.io.ObjectOutputStream(baos);
                    testOos.writeObject(data);
                    testOos.close();
                    byte[] serializedData = baos.toByteArray();
                    LogUtils.d(TAG, "List serialization test successful, size: " + serializedData.length + " bytes");
                } catch (Exception serTest) {
                    LogUtils.e(TAG, "List serialization test failed: " + serTest.getMessage());
                    LogUtils.e(TAG, "Serialization test stack trace: " + android.util.Log.getStackTraceString(serTest));
                    throw new ClassCastException("List serialization test failed: " + serTest.getMessage());
                }
            }

            LogUtils.d(TAG, "Creating CacheEntry...");
            CacheEntry entry = new CacheEntry(data, System.currentTimeMillis() + ttlMs);

            LogUtils.d(TAG, "Created cache entry for key: " + key + ", data type in entry: " + (entry.data != null ? entry.data.getClass().getSimpleName() : "null"));

            // Store in memory cache
            memoryCache.put(key, entry);
            updateAccessOrder(key);

            LogUtils.d(TAG, "Stored in memory cache for key: " + key);

            // Asynchronously store to disk
            cleanupExecutor.submit(() -> saveToDisk(key, entry));

            // Check memory cache size
            if (memoryCache.size() > MAX_MEMORY_CACHE_SIZE) {
                evictLeastRecentlyUsed();
            }

        } catch (ClassCastException e) {
            LogUtils.e(TAG, "ClassCastException in put() for key: " + key + " - " + e.getMessage());
            LogUtils.e(TAG, "Exception stack trace: " + android.util.Log.getStackTraceString(e));
        } catch (Exception e) {
            LogUtils.e(TAG, "Error storing data in cache for key: " + key + ": " + e.getMessage());
            LogUtils.e(TAG, "Exception type: " + e.getClass().getSimpleName());
            LogUtils.e(TAG, "Exception stack trace: " + android.util.Log.getStackTraceString(e));
        } finally {
            SimplePerformanceMonitor.endOperation("cache_put");
        }
    }

    /**
     * Retrieves data from the cache.
     *
     * @param key  Unique key for the cache entry
     * @param type Class type of the data
     * @param <T>  Type of the data
     * @return Cached data or null if not found or expired
     */
    @SuppressWarnings("unchecked")
    public <T extends Serializable> T get(String key, Class<T> type) {
        LogUtils.d(TAG, "Retrieving data from cache with key: " + key);

        SimplePerformanceMonitor.startOperation("cache_get");
        cacheRequests.incrementAndGet();

        try {
            // Check memory cache first
            CacheEntry entry = memoryCache.get(key);

            if (entry != null) {
                if (entry.isValid()) {
                    updateAccessOrder(key);
                    cacheHits.incrementAndGet();
                    LogUtils.d(TAG, "Cache hit in memory for key: " + key);
                    return (T) entry.data;
                } else {
                    // Remove expired entry
                    memoryCache.remove(key);
                    cleanupExecutor.submit(() -> removeFromDisk(key));
                    LogUtils.d(TAG, "Expired cache entry removed for key: " + key);
                }
            }

            // Check disk cache
            entry = loadFromDisk(key);
            if (entry != null && entry.isValid()) {
                // Restore to memory cache
                memoryCache.put(key, entry);
                updateAccessOrder(key);
                cacheHits.incrementAndGet();
                LogUtils.d(TAG, "Cache hit on disk for key: " + key);
                return (T) entry.data;
            } else if (entry != null) {
                // Remove expired disk entry
                cleanupExecutor.submit(() -> removeFromDisk(key));
            }

            LogUtils.d(TAG, "Cache miss for key: " + key);
            return null;

        } finally {
            SimplePerformanceMonitor.endOperation("cache_get");
        }
    }

    /**
     * Stores file list cache with smart merging.
     */
    public void putFileList(String cacheKey, List<SmbFileItem> files) {
        LogUtils.d(TAG, "Caching file list for key: " + cacheKey + " (" + files.size() + " files)");

        // Use the standard put method which handles serialization properly
        put(cacheKey, (Serializable) new ArrayList<>(files), DEFAULT_TTL_MS);
    }

    /**
     * Retrieves cached file list.
     */
    @SuppressWarnings("unchecked")
    public List<SmbFileItem> getFileList(String cacheKey) {
        cacheRequests.incrementAndGet();
        CacheEntry entry = memoryCache.get(cacheKey);

        if (entry != null && entry.isValid()) {
            updateAccessOrder(cacheKey);
            if (entry.data instanceof List) {
                List<SmbFileItem> files = (List<SmbFileItem>) entry.data;
                cacheHits.incrementAndGet();
                LogUtils.d(TAG, "Retrieved cached file list for key: " + cacheKey + " (" + files.size() + " files)");
                return new ArrayList<>(files);
            }
        }

        // Check disk cache if not in memory
        entry = loadFromDisk(cacheKey);
        if (entry != null && entry.isValid()) {
            memoryCache.put(cacheKey, entry);
            updateAccessOrder(cacheKey);
            if (entry.data instanceof List) {
                List<SmbFileItem> files = (List<SmbFileItem>) entry.data;
                cacheHits.incrementAndGet();
                LogUtils.d(TAG, "Retrieved cached file list from disk for key: " + cacheKey + " (" + files.size() + " files)");
                return new ArrayList<>(files);
            }
        }

        LogUtils.d(TAG, "No cached file list found for key: " + cacheKey);
        return null;
    }

    /**
     * Stores file list cache with connection-specific key.
     */
    public void cacheFileList(SmbConnection connection, String path, List<SmbFileItem> files) {
        String cacheKey = generateCacheKey(connection, path);
        LogUtils.d(TAG, "Caching file list for connection: " + connection.getName() + ", path: " + path + " (" + files.size() + " files)");
        putFileList(cacheKey, files);
    }

    /**
     * Retrieves cached file list for a specific connection and path.
     */
    public List<SmbFileItem> getCachedFileList(SmbConnection connection, String path) {
        String cacheKey = generateCacheKey(connection, path);
        List<SmbFileItem> cached = getFileList(cacheKey);

        if (cached != null) {
            LogUtils.d(TAG, "Retrieved cached file list for connection: " + connection.getName() + ", path: " + path + " (" + cached.size() + " files)");
        } else {
            LogUtils.d(TAG, "No cached file list found for connection: " + connection.getName() + ", path: " + path);
        }

        return cached;
    }

    /**
     * Invalidates cache entries matching a pattern.
     */
    public void invalidate(String keyPattern) {
        LogUtils.d(TAG, "Invalidating cache entries matching pattern: " + keyPattern);

        List<String> keysToRemove = new ArrayList<>();

        for (String key : memoryCache.keySet()) {
            if (key.contains(keyPattern)) {
                keysToRemove.add(key);
            }
        }

        for (String key : keysToRemove) {
            memoryCache.remove(key);
            accessOrder.remove(key);
            cleanupExecutor.submit(() -> removeFromDisk(key));
        }

        LogUtils.i(TAG, "Invalidated " + keysToRemove.size() + " cache entries");
    }

    /**
     * Invalidates cache entries matching a pattern synchronously.
     * This is used for critical operations where we need to ensure
     * cache is completely cleared before proceeding.
     */
    public void invalidateSync(String keyPattern) {
        LogUtils.d(TAG, "Synchronously invalidating cache entries matching pattern: " + keyPattern);

        List<String> keysToRemove = new ArrayList<>();

        for (String key : memoryCache.keySet()) {
            if (key.contains(keyPattern)) {
                keysToRemove.add(key);
            }
        }

        for (String key : keysToRemove) {
            memoryCache.remove(key);
            accessOrder.remove(key);
            // Remove from disk synchronously to prevent race conditions
            removeFromDisk(key);
        }

        LogUtils.i(TAG, "Synchronously invalidated " + keysToRemove.size() + " cache entries");
    }

    /**
     * Invalidates cache for a specific connection.
     */
    public void invalidateConnection(SmbConnection connection) {
        try {
            String connectionId = String.valueOf(connection.getId());
            String connectionPattern = "conn_" + connectionId;
            LogUtils.i(TAG, "Invalidating cache for connection: " + connection.getName());
            invalidate(connectionPattern);
        } catch (Exception e) {
            LogUtils.e(TAG, "Error invalidating connection cache: " + e.getMessage());
        }
    }

    /**
     * Performs aggressive cache cleanup after cancelled operations to prevent cache bloat.
     * This method removes not only the specific cache entries but also any temporary or
     * incomplete entries that might have been created during the operation.
     */
    public void cleanupAfterCancelledOperation(SmbConnection connection, String path) {
        try {
            // Log cache sizes before cleanup
            int memoryCacheCount = memoryCache.size();
            long diskCacheSizeBytes = diskCacheSize.get();
            long diskCacheSizeMB = diskCacheSizeBytes / (1024 * 1024);

            LogUtils.i(TAG, "Performing aggressive cache cleanup after cancelled operation for connection: " + (connection != null ? connection.getName() : "null") + ", path: " + path);
            LogUtils.i(TAG, "Cache state before cleanup - Memory entries: " + memoryCacheCount + ", Disk cache: " + diskCacheSizeMB + " MB (" + diskCacheSizeBytes + " bytes)");

            if (connection != null) {
                String connectionId = String.valueOf(connection.getId());

                // Invalidate all cache entries for this connection and path
                String pathPattern = "conn_" + connectionId + "_path_" + (path != null ? path.hashCode() : "");
                invalidateSync(pathPattern);

                // Also invalidate search cache for this path
                invalidateSearchCache(connection, path != null ? path : "");

                // Force immediate cleanup of expired entries in memory
                cleanupExecutor.submit(() -> {
                    LogUtils.d(TAG, "Running emergency cache cleanup");
                    performCleanup();

                    // Also trigger garbage collection hint for memory cache
                    int removedCount = 0;
                    java.util.Iterator<java.util.Map.Entry<String, CacheEntry>> iterator = memoryCache.entrySet().iterator();
                    while (iterator.hasNext()) {
                        java.util.Map.Entry<String, CacheEntry> entry = iterator.next();
                        String key = entry.getKey();

                        // Remove any entries related to this connection that might be stale
                        if (key.contains("conn_" + connectionId)) {
                            iterator.remove();
                            accessOrder.remove(key);
                            removeFromDisk(key);
                            removedCount++;
                        }
                    }

                    // Log cache sizes after cleanup
                    int newMemoryCacheCount = memoryCache.size();
                    long newDiskCacheSizeBytes = diskCacheSize.get();
                    long newDiskCacheSizeMB = newDiskCacheSizeBytes / (1024 * 1024);

                    LogUtils.i(TAG, "Cache state after cleanup - Memory entries: " + newMemoryCacheCount + " (removed " + (memoryCacheCount - newMemoryCacheCount) + "), " + "Disk cache: " + newDiskCacheSizeMB + " MB (freed " + ((diskCacheSizeBytes - newDiskCacheSizeBytes) / (1024 * 1024)) + " MB)");

                    if (removedCount > 0) {
                        LogUtils.i(TAG, "Emergency cleanup removed " + removedCount + " potentially stale cache entries");
                    }

                    // Suggest garbage collection to free up memory
                    System.gc();
                    LogUtils.d(TAG, "Suggested garbage collection after cache cleanup");
                });
            }
        } catch (Exception e) {
            LogUtils.e(TAG, "Error during aggressive cache cleanup: " + e.getMessage());
        }
    }

    /**
     * Clears all cache data.
     */
    public void clearAll() {
        LogUtils.i(TAG, "Clearing all cache data");

        memoryCache.clear();
        accessOrder.clear();

        cleanupExecutor.submit(() -> {
            try {
                if (cacheDir.exists()) {
                    File[] files = cacheDir.listFiles();
                    if (files != null) {
                        for (File file : files) {
                            file.delete();
                        }
                    }
                }
                diskCacheSize.set(0);
                LogUtils.i(TAG, "Disk cache cleared");
            } catch (Exception e) {
                LogUtils.e(TAG, "Error clearing disk cache: " + e.getMessage());
            }
        });
    }

    /**
     * Proactively detects and removes corrupted cache files to prevent runtime errors.
     * This method should be called during initialization or when cache corruption is suspected.
     */
    public void cleanupCorruptedCacheFiles() {
        LogUtils.d(TAG, "Starting proactive cleanup of corrupted cache files");

        cleanupExecutor.submit(() -> {
            try {
                File[] files = cacheDir.listFiles();
                if (files == null) {
                    LogUtils.d(TAG, "Cache directory is empty or inaccessible");
                    return;
                }

                int checkedCount = 0;
                int removedCount = 0;
                long freedSpace = 0;

                for (File file : files) {
                    if (!file.getName().endsWith(".cache")) {
                        continue;
                    }

                    checkedCount++;
                    boolean isCorrupted = false;

                    try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
                        Object obj = ois.readObject();

                        // Check if object is valid CacheEntry
                        if (!(obj instanceof CacheEntry entry)) {
                            LogUtils.w(TAG, "Invalid cache entry type in file: " + file.getName());
                            isCorrupted = true;
                        } else {

                            // Check cache version
                            if (entry.cacheVersion != 2) {
                                LogUtils.w(TAG, "Outdated cache version in file: " + file.getName());
                                isCorrupted = true;
                            }

                            // Validate data field
                            if (entry.data == null) {
                                LogUtils.w(TAG, "Null data in cache entry: " + file.getName());
                                isCorrupted = true;
                            }
                        }

                    } catch (Exception e) {
                        LogUtils.w(TAG, "Cache file validation failed for " + file.getName() + ": " + e.getMessage());
                        isCorrupted = true;
                    }

                    // Remove corrupted files
                    if (isCorrupted) {
                        long fileSize = file.length();
                        if (file.delete()) {
                            removedCount++;
                            freedSpace += fileSize;
                            LogUtils.d(TAG, "Removed corrupted cache file: " + file.getName());
                        } else {
                            LogUtils.w(TAG, "Failed to delete corrupted cache file: " + file.getName());
                        }
                    }
                }

                // Update disk cache size
                if (removedCount > 0) {
                    diskCacheSize.addAndGet(-freedSpace);
                }

                LogUtils.i(TAG, "Cache cleanup completed: checked " + checkedCount + " files, removed " + removedCount + " corrupted files, freed " + (freedSpace / 1024) + "KB");

            } catch (Exception e) {
                LogUtils.e(TAG, "Error during cache cleanup: " + e.getMessage());
            }
        });
    }

    /**
     * Preloads data into cache for better performance.
     */
    public <T extends Serializable> void preload(String key, CacheLoader<T> loader) {
        LogUtils.d(TAG, "Preloading data for key: " + key);

        cleanupExecutor.submit(() -> {
            try {
                T data = loader.load();
                if (data != null) {
                    mainHandler.post(() -> put(key, data));
                    LogUtils.d(TAG, "Preloaded data for key: " + key);
                }
            } catch (Exception e) {
                LogUtils.e(TAG, "Error preloading data for key " + key + ": " + e.getMessage());
            }
        });
    }

    /**
     * Gets comprehensive cache statistics.
     */
    public CacheStatistics getStatistics() {
        CacheStatistics stats = new CacheStatistics();
        stats.memoryEntries = memoryCache.size();
        stats.diskSizeBytes = diskCacheSize.get();
        stats.hitRate = calculateHitRate();

        // Count valid vs expired entries
        for (CacheEntry entry : memoryCache.values()) {
            if (entry.isValid()) {
                stats.validEntries++;
            } else {
                stats.expiredEntries++;
            }
        }

        LogUtils.d(TAG, "Cache statistics: " + stats.memoryEntries + " memory entries, " + stats.diskSizeBytes + " bytes on disk, " + String.format(Locale.ROOT, "%.2f", stats.hitRate * 100) + "% hit rate");

        return stats;
    }

    // Private helper methods

    private void initializeCacheDirectory() {
        cacheDir = new File(context.getCacheDir(), CACHE_DIR);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        LogUtils.d(TAG, "Cache directory: " + cacheDir.getAbsolutePath());
    }

    private void scheduleCleanupTasks() {
        // Schedule regular cleanup
        cleanupExecutor.scheduleWithFixedDelay(this::performCleanup, CLEANUP_INTERVAL_MS, CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void performCleanup() {
        LogUtils.d(TAG, "Performing cache cleanup");

        // Remove expired entries from memory
        List<String> expiredKeys = new ArrayList<>();
        for (Map.Entry<String, CacheEntry> entry : memoryCache.entrySet()) {
            if (!entry.getValue().isValid()) {
                expiredKeys.add(entry.getKey());
            }
        }

        for (String key : expiredKeys) {
            memoryCache.remove(key);
            accessOrder.remove(key);
            removeFromDisk(key);
        }

        // Clean up disk cache if size exceeded
        if (diskCacheSize.get() > MAX_DISK_CACHE_SIZE) {
            cleanupDiskCache();
        }

        if (!expiredKeys.isEmpty()) {
            LogUtils.d(TAG, "Cleaned up " + expiredKeys.size() + " expired cache entries");
        }
    }

    private void updateAccessOrder(String key) {
        accessOrder.remove(key); // Remove if exists
        accessOrder.offer(key);  // Add to end
    }

    private void evictLeastRecentlyUsed() {
        String lruKey = accessOrder.poll();
        if (lruKey != null) {
            memoryCache.remove(lruKey);
            LogUtils.d(TAG, "Evicted LRU cache entry: " + lruKey);
        }
    }

    private void saveToDisk(String key, CacheEntry entry) {
        // Get or create a lock object for this specific key to prevent race conditions
        Object fileLock = fileLocks.computeIfAbsent(key, k -> new Object());

        synchronized (fileLock) {
            try {
                File file = new File(cacheDir, key.hashCode() + ".cache");

                LogUtils.d(TAG, "Saving cache entry to disk: " + key + ", file: " + file.getName() + ", data type: " + (entry.data != null ? entry.data.getClass().getSimpleName() : "null"));

                // Deep validation of cache entry data before serialization
                if (entry.data instanceof List<?> list) {
                    LogUtils.d(TAG, "Pre-serialization validation: List with " + list.size() + " items");

                    for (int i = 0; i < Math.min(list.size(), 3); i++) { // Check first 3 items
                        Object item = list.get(i);
                        LogUtils.d(TAG, "List item " + i + " type: " + (item != null ? item.getClass().getSimpleName() : "null"));

                        if (item instanceof SmbFileItem smbItem) {
                            LogUtils.d(TAG, "SmbFileItem fields - name: " + (smbItem.getName() != null ? smbItem.getName().getClass().getSimpleName() : "null") + ", path: " + (smbItem.getPath() != null ? smbItem.getPath().getClass().getSimpleName() : "null"));
                        }
                    }
                }

                try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file))) {
                    oos.writeObject(entry);
                    oos.flush();

                    long fileSize = file.length();
                    diskCacheSize.addAndGet(fileSize);

                    LogUtils.d(TAG, "Saved cache entry to disk: " + key + " (" + fileSize + " bytes)");
                }

            } catch (ClassCastException e) {
                LogUtils.e(TAG, "ClassCastException during serialization for key: " + key + " - " + e.getMessage());
                LogUtils.e(TAG, "Exception stack trace: " + android.util.Log.getStackTraceString(e));
                // Clean up any partially written file
                File file = new File(cacheDir, key.hashCode() + ".cache");
                if (file.exists()) {
                    file.delete();
                    LogUtils.d(TAG, "Cleaned up partially written cache file for key: " + key);
                }
            } catch (Exception e) {
                LogUtils.e(TAG, "Error saving cache entry to disk for key: " + key + ": " + e.getMessage());
                LogUtils.e(TAG, "Exception type: " + e.getClass().getSimpleName());
                // Try to clean up the partially written file
                File file = new File(cacheDir, key.hashCode() + ".cache");
                if (file.exists()) {
                    file.delete();
                    LogUtils.d(TAG, "Cleaned up partially written cache file for key: " + key);
                }
            } finally {
                // Clean up the file lock if this was the last thread using it
                fileLocks.remove(key, fileLock);
            }
        }
    }

    private CacheEntry loadFromDisk(String key) {
        // Get or create a lock object for this specific key to prevent race conditions
        Object fileLock = fileLocks.computeIfAbsent(key, k -> new Object());

        synchronized (fileLock) {
            try {
                File file = new File(cacheDir, key.hashCode() + ".cache");
                if (!file.exists()) {
                    return null;
                }

                LogUtils.d(TAG, "Attempting to load cache file: " + file.getName() + " (size: " + file.length() + " bytes)");

                try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
                    LogUtils.d(TAG, "ObjectInputStream created successfully, reading object...");
                    Object obj = ois.readObject();
                    LogUtils.d(TAG, "Object read successfully, type: " + (obj != null ? obj.getClass().getSimpleName() : "null"));

                    // Explicit type checking to prevent ClassCastException
                    if (!(obj instanceof CacheEntry entry)) {
                        LogUtils.w(TAG, "Invalid cache entry type in file: " + file.getName() + " (expected CacheEntry, got " + obj.getClass().getSimpleName() + ")");
                        // Delete corrupted file
                        file.delete();
                        return null;
                    }

                    LogUtils.d(TAG, "Object is valid CacheEntry, casting...");
                    LogUtils.d(TAG, "CacheEntry cast successful");

                    // Validate cache entry version
                    if (entry.cacheVersion != 2) {
                        LogUtils.w(TAG, "Outdated cache entry version " + entry.cacheVersion + " in file: " + file.getName());
                        // Delete outdated file
                        file.delete();
                        return null;
                    }

                    LogUtils.d(TAG, "Cache version valid (2), checking data field...");
                    LogUtils.d(TAG, "Entry.data type: " + (entry.data != null ? entry.data.getClass().getSimpleName() : "null"));

                    // Deep validation of the loaded data with detailed logging
                    if (entry.data instanceof List<?> list) {
                        LogUtils.d(TAG, "Entry.data is List, casting to List...");
                        try {
                            LogUtils.d(TAG, "List cast successful, size: " + list.size());

                            if (!list.isEmpty()) {
                                Object firstItem = list.get(0);
                                LogUtils.d(TAG, "First item type: " + (firstItem != null ? firstItem.getClass().getSimpleName() : "null"));

                                // Try to cast first item to SmbFileItem to detect issues early
                                if (firstItem instanceof SmbFileItem smbItem) {
                                    LogUtils.d(TAG, "First item is valid SmbFileItem - name: " + smbItem.getName() + ", path: " + smbItem.getPath());

                                    // Validate critical fields
                                    if (smbItem.getName() == null) {
                                        LogUtils.w(TAG, "SmbFileItem has null name - potential data corruption");
                                    } else if (!(smbItem.getName() instanceof String)) {
                                        LogUtils.e(TAG, "SmbFileItem name is not String: " + smbItem.getName().getClass().getSimpleName());
                                        throw new ClassCastException("SmbFileItem name is not String: " + smbItem.getName().getClass().getSimpleName());
                                    }

                                    if (smbItem.getPath() != null && !(smbItem.getPath() instanceof String)) {
                                        LogUtils.e(TAG, "SmbFileItem path is not String: " + smbItem.getPath().getClass().getSimpleName());
                                        throw new ClassCastException("SmbFileItem path is not String: " + smbItem.getPath().getClass().getSimpleName());
                                    }

                                } else {
                                    LogUtils.e(TAG, "First item is not SmbFileItem: " + (firstItem != null ? firstItem.getClass().getSimpleName() : "null"));
                                    throw new ClassCastException("List contains non-SmbFileItem: " + (firstItem != null ? firstItem.getClass().getSimpleName() : "null"));
                                }
                            }
                        } catch (ClassCastException e) {
                            LogUtils.e(TAG, "ClassCastException during List processing: " + e.getMessage());
                            LogUtils.e(TAG, "Exception stack trace: " + android.util.Log.getStackTraceString(e));
                            throw e; // Re-throw to be caught by outer handler
                        }
                    } else {
                        LogUtils.w(TAG, "Warning: Loaded cache data is not a List: " + entry.data.getClass().getSimpleName());
                    }

                    LogUtils.d(TAG, "Cache entry validation completed successfully for key: " + key);
                    return entry;
                }

            } catch (ClassCastException e) {
                LogUtils.e(TAG, "ClassCastException loading cache entry '" + key + "': " + e.getMessage());
                LogUtils.e(TAG, "Full exception stack trace: " + android.util.Log.getStackTraceString(e));
                // Remove corrupted file
                File file = new File(cacheDir, key.hashCode() + ".cache");
                if (file.exists()) {
                    long fileSize = file.length();
                    if (file.delete()) {
                        LogUtils.d(TAG, "Removed corrupted cache file: " + file.getName() + " (" + fileSize + " bytes)");
                    } else {
                        LogUtils.w(TAG, "Failed to delete corrupted cache file: " + file.getName());
                    }
                }
                return null;
            } catch (Exception e) {
                LogUtils.e(TAG, "Error loading cache entry from disk for key '" + key + "': " + e.getMessage());
                LogUtils.e(TAG, "Exception type: " + e.getClass().getSimpleName());
                LogUtils.e(TAG, "Full exception stack trace: " + android.util.Log.getStackTraceString(e));

                // If we have serialization issues, remove this specific file
                File file = new File(cacheDir, key.hashCode() + ".cache");
                if (file.exists() && (e.getMessage() != null && (e.getMessage().contains("NotSerializableException") || e.getMessage().contains("InvalidClassException") || e.getMessage().contains("ClassCastException")))) {
                    LogUtils.w(TAG, "Detected serialization issue for key '" + key + "', removing file");
                    if (file.delete()) {
                        LogUtils.d(TAG, "Successfully removed problematic cache file: " + file.getName());
                    } else {
                        LogUtils.w(TAG, "Failed to remove problematic cache file: " + file.getName());
                    }
                }

                return null;
            } finally {
                // Clean up the file lock if this was the last thread using it
                fileLocks.remove(key, fileLock);
            }
        }
    }

    private void removeFromDisk(String key) {
        // Get or create a lock object for this specific key to prevent race conditions
        Object fileLock = fileLocks.computeIfAbsent(key, k -> new Object());

        synchronized (fileLock) {
            try {
                File file = new File(cacheDir, key.hashCode() + ".cache");
                if (file.exists()) {
                    long fileSize = file.length();
                    if (file.delete()) {
                        diskCacheSize.addAndGet(-fileSize);
                        LogUtils.d(TAG, "Removed cache entry from disk: " + key);
                    }
                }
            } catch (Exception e) {
                LogUtils.e(TAG, "Error removing cache entry from disk: " + e.getMessage());
            } finally {
                // Clean up the file lock if this was the last thread using it
                fileLocks.remove(key, fileLock);
            }
        }
    }

    private void loadDiskCacheSize() {
        cleanupExecutor.submit(() -> {
            try {
                long totalSize = 0;
                File[] files = cacheDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        totalSize += file.length();
                    }
                }
                diskCacheSize.set(totalSize);
                LogUtils.d(TAG, "Loaded disk cache size: " + totalSize + " bytes");
            } catch (Exception e) {
                LogUtils.e(TAG, "Error loading disk cache size: " + e.getMessage());
            }
        });
    }

    private void cleanupDiskCache() {
        // Implementation for cleaning up old disk cache files when size limit is exceeded
        LogUtils.w(TAG, "Disk cache size limit exceeded, performing cleanup");

        try {
            File[] files = cacheDir.listFiles();
            if (files != null) {
                // Sort by last modified time
                java.util.Arrays.sort(files, (f1, f2) -> Long.compare(f1.lastModified(), f2.lastModified()));

                // Remove oldest files until under limit
                long currentSize = diskCacheSize.get();
                for (File file : files) {
                    if (currentSize <= MAX_DISK_CACHE_SIZE * 0.8) { // 80% of limit
                        break;
                    }

                    long fileSize = file.length();
                    if (file.delete()) {
                        currentSize -= fileSize;
                        diskCacheSize.addAndGet(-fileSize);
                    }
                }
            }
        } catch (Exception e) {
            LogUtils.e(TAG, "Error during disk cache cleanup: " + e.getMessage());
        }
    }

    private double calculateHitRate() {
        long totalRequests = cacheRequests.get();
        if (totalRequests == 0) {
            return 0.0;
        }
        return (double) cacheHits.get() / totalRequests;
    }

    /**
     * Shuts down the cache manager.
     */
    public void shutdown() {
        LogUtils.i(TAG, "Shutting down cache manager");
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Generates a cache key for connection and path combination.
     */
    private String generateCacheKey(SmbConnection connection, String path) {
        try {
            String connectionId = String.valueOf(connection.getId());
            return "conn_" + connectionId + "_path_" + path.hashCode();
        } catch (Exception e) {
            LogUtils.e(TAG, "Error generating cache key: " + e.getMessage());
            return "cache_fallback_" + System.currentTimeMillis();
        }
    }

    /**
     * Stores search results cache with search parameters.
     */
    public void cacheSearchResults(SmbConnection connection, String path, String query, int searchType, boolean includeSubfolders, List<SmbFileItem> results) {
        String searchKey = generateSearchCacheKey(connection, path, query, searchType, includeSubfolders);
        LogUtils.d(TAG, "Caching search results for key: " + searchKey + " (" + results.size() + " results)");

        try {
            // Create a defensive copy to avoid issues with the original list
            ArrayList<SmbFileItem> resultsCopy = new ArrayList<>(results);

            // Validate that the copy was created correctly
            LogUtils.d(TAG, "Created results copy of type: " + resultsCopy.getClass().getSimpleName() + " with " + resultsCopy.size() + " items");

            // Store with explicit serializable casting
            put(searchKey, resultsCopy, DEFAULT_TTL_MS);

            LogUtils.d(TAG, "Successfully cached search results for key: " + searchKey);
        } catch (Exception e) {
            LogUtils.e(TAG, "Error caching search results for key: " + searchKey + ": " + e.getMessage());
        }
    }

    /**
     * Retrieves cached search results for specific search parameters.
     */
    @SuppressWarnings("unchecked")
    public List<SmbFileItem> getCachedSearchResults(SmbConnection connection, String path, String query, int searchType, boolean includeSubfolders) {
        String searchKey = generateSearchCacheKey(connection, path, query, searchType, includeSubfolders);

        LogUtils.d(TAG, "Looking for cached search results with key: " + searchKey);
        cacheRequests.incrementAndGet();

        try {
            // Check memory cache first
            CacheEntry entry = memoryCache.get(searchKey);

            if (entry != null && entry.isValid()) {
                updateAccessOrder(searchKey);
                LogUtils.d(TAG, "Found entry in memory cache, data type: " + (entry.data != null ? entry.data.getClass().getSimpleName() : "null"));

                if (entry.data instanceof List) {
                    try {
                        @SuppressWarnings("unchecked") List<SmbFileItem> files = (List<SmbFileItem>) entry.data;
                        cacheHits.incrementAndGet();
                        LogUtils.d(TAG, "Retrieved cached search results from memory for key: " + searchKey + " (" + files.size() + " results)");
                        return new ArrayList<>(files);
                    } catch (ClassCastException e) {
                        LogUtils.w(TAG, "Memory cache type mismatch for search key: " + searchKey + ", data type: " + entry.data.getClass().getSimpleName() + ", removing entry");
                        memoryCache.remove(searchKey);
                        accessOrder.remove(searchKey);
                    }
                } else {
                    LogUtils.w(TAG, "Memory cache entry is not a List for key: " + searchKey + ", type: " + (entry.data != null ? entry.data.getClass().getSimpleName() : "null"));
                    memoryCache.remove(searchKey);
                    accessOrder.remove(searchKey);
                }
            }

            // Check disk cache
            LogUtils.d(TAG, "Checking disk cache for key: " + searchKey);
            entry = loadFromDisk(searchKey);
            if (entry != null && entry.isValid()) {
                LogUtils.d(TAG, "Found entry on disk, data type: " + (entry.data != null ? entry.data.getClass().getSimpleName() : "null"));

                if (entry.data instanceof List) {
                    try {
                        @SuppressWarnings("unchecked") List<SmbFileItem> files = (List<SmbFileItem>) entry.data;
                        cacheHits.incrementAndGet();
                        LogUtils.d(TAG, "Retrieved cached search results from disk for key: " + searchKey + " (" + files.size() + " results)");
                        // Restore to memory cache
                        memoryCache.put(searchKey, entry);
                        updateAccessOrder(searchKey);
                        return new ArrayList<>(files);
                    } catch (ClassCastException e) {
                        LogUtils.w(TAG, "Disk cache type mismatch for search key: " + searchKey + ", data type: " + entry.data.getClass().getSimpleName() + ", removing entry");
                        cleanupExecutor.submit(() -> removeFromDisk(searchKey));
                    }
                } else {
                    LogUtils.w(TAG, "Disk cache entry is not a List for key: " + searchKey + ", type: " + (entry.data != null ? entry.data.getClass().getSimpleName() : "null"));
                    cleanupExecutor.submit(() -> removeFromDisk(searchKey));
                }
            }

            LogUtils.d(TAG, "No cached search results found for key: " + searchKey);
            return null;

        } catch (Exception e) {
            LogUtils.e(TAG, "Error retrieving cached search results for key: " + searchKey + ": " + e.getMessage());
            // Clean up potentially corrupted cache entry
            try {
                removeEntry(searchKey);
            } catch (Exception cleanupError) {
                LogUtils.w(TAG, "Error during cache cleanup: " + cleanupError.getMessage());
            }
            return null;
        }
    }

    /**
     * Invalidates search cache entries for a specific connection and path.
     * This should be called when files are added, deleted, or modified.
     */
    public void invalidateSearchCache(SmbConnection connection, String path) {
        try {
            String connectionId = String.valueOf(connection.getId());
            String searchPattern = "search_conn_" + connectionId + "_path_" + sanitizePath(path);
            LogUtils.i(TAG, "Invalidating search cache for connection: " + connection.getName() + ", path: " + path);
            invalidate(searchPattern);
        } catch (Exception e) {
            LogUtils.e(TAG, "Error invalidating search cache: " + e.getMessage());
        }
    }

    /**
     * Generates a cache key for search results based on search parameters.
     * Made public for external access while maintaining consistency.
     */
    public String generateSearchCacheKey(SmbConnection connection, String path, String query, int searchType, boolean includeSubfolders) {
        try {
            String sanitizedPath = sanitizePath(path);
            String sanitizedQuery = sanitizeSearchQuery(query);

            // Ensure connection ID is converted to string properly
            String connectionId = String.valueOf(connection.getId());

            LogUtils.d(TAG, "Generating search cache key - connectionId: " + connectionId + ", path: " + sanitizedPath + ", query: " + sanitizedQuery);

            return String.format(Locale.US, "search_conn_%s_path_%s_query_%s_type_%d_sub_%s", connectionId, sanitizedPath, sanitizedQuery, searchType, includeSubfolders ? "true" : "false");
        } catch (Exception e) {
            LogUtils.e(TAG, "Error generating search cache key: " + e.getMessage());
            LogUtils.e(TAG, "Connection ID type: " + (connection.getId() != null ? connection.getId().getClass().getSimpleName() : "null"));
            LogUtils.e(TAG, "Exception stack trace: " + android.util.Log.getStackTraceString(e));

            // Fallback to simple key generation
            return "search_fallback_" + System.currentTimeMillis();
        }
    }

    /**
     * Sanitizes search query for use in cache key.
     */
    private String sanitizeSearchQuery(String query) {
        if (query == null || query.isEmpty()) {
            return "empty";
        }

        // Replace characters that might cause issues in file names
        return query.toLowerCase(Locale.ROOT).replaceAll("[^a-zA-Z0-9]", "_").replaceAll("_{2,}", "_").replaceAll("^_|_$", "");
    }

    /**
     * Sanitizes path for use in cache key by converting to a safe string representation.
     */
    private String sanitizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "root";
        }

        // Use the same pattern as generateCacheKey - just use hashCode for simplicity
        return String.valueOf(path.hashCode());
    }

    /**
     * Pre-loads search results for common queries to improve performance.
     */
    public void preloadCommonSearches(SmbConnection connection, String path) {
        LogUtils.d(TAG, "Preloading common searches for connection: " + connection.getName() + ", path: " + path);

        // Common search patterns that users might search for
        String[] commonQueries = {"*.txt", "*.pdf", "*.doc", "*.jpg", "*.png", "*.mp4", "*.mp3"};

        cleanupExecutor.submit(() -> {
            for (String query : commonQueries) {
                try {
                    // Generate search key with complete error isolation
                    String searchKey;
                    try {
                        searchKey = generateSearchCacheKey(connection, path, query, 0, true);
                    } catch (Exception keyError) {
                        LogUtils.w(TAG, "Error generating search key for query " + query + ": " + keyError.getMessage());
                        continue;
                    }

                    // Check memory cache first (safest)
                    CacheEntry entry = null;
                    try {
                        entry = memoryCache.get(searchKey);
                    } catch (Exception memError) {
                        LogUtils.w(TAG, "Error accessing memory cache for query " + query + ": " + memError.getMessage());
                        // Continue to disk check
                    }

                    // Check disk cache only if memory cache is empty and safe to do so
                    if (entry == null) {
                        try {
                            // Double-check that the key is valid before attempting disk access
                            if (searchKey != null && !searchKey.isEmpty()) {
                                entry = loadFromDisk(searchKey);
                            }
                        } catch (Exception diskError) {
                            LogUtils.w(TAG, "Error loading cache for query " + query + ", cleaning up: " + diskError.getMessage());
                            // Remove any problematic cache files for this key
                            try {
                                removeFromDisk(searchKey);
                            } catch (Exception cleanupError) {
                                LogUtils.w(TAG, "Error during cache cleanup: " + cleanupError.getMessage());
                            }
                            continue;
                        }
                    }

                    // Log the result
                    if (entry == null || !entry.isValid()) {
                        LogUtils.d(TAG, "No valid cache found for query: " + query + " (would preload if implemented)");
                    } else {
                        LogUtils.d(TAG, "Search query already cached: " + query);
                    }

                } catch (Exception e) {
                    LogUtils.e(TAG, "Error preloading search for query " + query + ": " + e.getMessage());
                    // Continue with next query instead of breaking the entire loop
                }
            }
        });
    }

    // Inner classes and interfaces

    /**
     * Clears all disk cache files. Useful for handling serialization format changes.
     */
    private void clearDiskCache() {
        cleanupExecutor.submit(() -> {
            try {
                File[] files = cacheDir.listFiles();
                if (files != null) {
                    int deletedCount = 0;
                    long freedSpace = 0;

                    for (File file : files) {
                        if (file.getName().endsWith(".cache")) {
                            long fileSize = file.length();
                            if (file.delete()) {
                                deletedCount++;
                                freedSpace += fileSize;
                            }
                        }
                    }

                    diskCacheSize.set(0);
                    LogUtils.i(TAG, "Cleared disk cache: deleted " + deletedCount + " files, freed " + freedSpace + " bytes");
                }
            } catch (Exception e) {
                LogUtils.e(TAG, "Error clearing disk cache: " + e.getMessage());
            }
        });
    }

    /**
     * Removes a specific cache entry by exact key match.
     * Useful for cleaning up corrupted entries.
     */
    public void removeEntry(String key) {
        try {
            // Remove from memory cache
            CacheEntry removed = memoryCache.remove(key);
            if (removed != null) {
                LogUtils.d(TAG, "Removed cache entry from memory: " + key);
            }

            // Remove access order tracking
            accessOrder.remove(key);

            // Remove from disk cache
            cleanupExecutor.submit(() -> removeFromDisk(key));

        } catch (Exception e) {
            LogUtils.e(TAG, "Error removing cache entry '" + key + "': " + e.getMessage());
        }
    }

    public interface CacheLoader<T> {
        T load() throws Exception;
    }

    private static class CacheEntry implements Serializable {
        private static final long serialVersionUID = 2L; // Incremented due to model changes

        final Serializable data;
        final long expirationTime;
        final int cacheVersion; // Track format version

        CacheEntry(Serializable data, long expirationTime) {
            this.data = data;
            this.expirationTime = expirationTime;
            this.cacheVersion = 2; // Current cache format version
        }

        boolean isValid() {
            return System.currentTimeMillis() < expirationTime && cacheVersion == 2;
        }
    }

    public static class CacheStatistics {
        public int memoryEntries;
        public long diskSizeBytes;
        public double hitRate;
        public int validEntries;
        public int expiredEntries;
    }
}
