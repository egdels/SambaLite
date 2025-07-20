package de.schliweb.sambalite.cache.strategy;

import android.content.Context;
import de.schliweb.sambalite.cache.entry.CacheEntry;
import de.schliweb.sambalite.cache.statistics.CacheStatistics;
import de.schliweb.sambalite.util.LogUtils;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A disk-based cache strategy implementation.
 * This strategy stores cache entries on disk and provides methods for saving, loading, and managing cache files.
 *
 * @param <K> The type of keys used for cache entries
 * @param <V> The type of values stored in the cache
 */
public class DiskCacheStrategy<K, V> implements CacheStrategy<K, V> {
    private static final String TAG = "DiskCacheStrategy";

    // Default maximum size of the disk cache in bytes (50MB)
    private static final long DEFAULT_MAX_SIZE_BYTES = 50 * 1024 * 1024;

    // File extension for cache files
    private static final String CACHE_FILE_EXTENSION = ".cache";

    // The directory where cache files are stored
    private final File cacheDir;

    // Maximum size of the disk cache in bytes
    private final long maxSizeBytes;

    // Current size of the disk cache in bytes
    private final AtomicLong currentSizeBytes = new AtomicLong(0);

    // Executor for background operations
    private final ExecutorService executor;

    // Statistics for monitoring cache performance
    private final CacheStatistics statistics;

    /**
     * Creates a new DiskCacheStrategy with default settings.
     *
     * @param context    The application context
     * @param statistics The statistics object for tracking cache performance
     */
    public DiskCacheStrategy(Context context, CacheStatistics statistics) {
        this(context, DEFAULT_MAX_SIZE_BYTES, statistics);
    }

    /**
     * Creates a new DiskCacheStrategy with the specified maximum size.
     *
     * @param context      The application context
     * @param maxSizeBytes The maximum size of the disk cache in bytes
     * @param statistics   The statistics object for tracking cache performance
     */
    public DiskCacheStrategy(Context context, long maxSizeBytes, CacheStatistics statistics) {
        this.cacheDir = new File(context.getCacheDir(), "intelligent_cache");
        this.maxSizeBytes = maxSizeBytes;
        this.statistics = statistics;
        this.executor = Executors.newSingleThreadExecutor();

        // Create cache directory if it doesn't exist
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            LogUtils.e(TAG, "Failed to create cache directory: " + cacheDir.getAbsolutePath());
        }

        // Calculate current cache size
        calculateCacheSize();

        LogUtils.d(TAG, "Created disk cache strategy with max size: " + maxSizeBytes + " bytes, current size: " + currentSizeBytes.get() + " bytes");
    }

    @Override
    public void put(K key, CacheEntry<V> entry) {
        LogUtils.d(TAG, "Putting entry in disk cache with key: " + key);
        statistics.incrementPutOperations();

        // Save to disk asynchronously
        executor.submit(() -> saveToDisk(key, entry));
    }

    @Override
    public CacheEntry<V> get(K key) {
        LogUtils.d(TAG, "Getting entry from disk cache with key: " + key);
        statistics.incrementGetOperations();
        statistics.incrementCacheRequests();

        CacheEntry<V> entry = loadFromDisk(key);

        if (entry != null) {
            if (entry.isValid()) {
                // Update statistics
                statistics.incrementCacheHits();
                entry.updateLastAccessTime();

                LogUtils.d(TAG, "Cache hit on disk for key: " + key);
                return entry;
            } else {
                // Remove expired entry
                remove(key);
                LogUtils.d(TAG, "Expired cache entry removed for key: " + key);
            }
        }

        statistics.incrementCacheMisses();
        LogUtils.d(TAG, "Cache miss on disk for key: " + key);
        return null;
    }

    @Override
    public CacheEntry<V> remove(K key) {
        LogUtils.d(TAG, "Removing entry from disk cache with key: " + key);
        statistics.incrementRemoveOperations();

        // Load the entry before removing it
        CacheEntry<V> entry = loadFromDisk(key);

        // Remove from disk
        File cacheFile = getCacheFile(key);
        if (cacheFile.exists()) {
            long fileSize = cacheFile.length();
            if (cacheFile.delete()) {
                // Update cache size
                currentSizeBytes.addAndGet(-fileSize);
                statistics.setDiskSizeBytes(currentSizeBytes.get());

                LogUtils.d(TAG, "Removed cache file: " + cacheFile.getName() + ", size: " + fileSize + " bytes");
            } else {
                LogUtils.e(TAG, "Failed to delete cache file: " + cacheFile.getAbsolutePath());
            }
        }

        return entry;
    }

    @Override
    public int removePattern(String keyPattern) {
        LogUtils.d(TAG, "Removing entries matching pattern: " + keyPattern);

        File[] files = cacheDir.listFiles((dir, name) -> name.endsWith(CACHE_FILE_EXTENSION) && name.contains(keyPattern));

        if (files == null) {
            LogUtils.e(TAG, "Failed to list cache files");
            return 0;
        }

        int count = 0;
        for (File file : files) {
            long fileSize = file.length();
            if (file.delete()) {
                // Update cache size
                currentSizeBytes.addAndGet(-fileSize);
                count++;

                LogUtils.d(TAG, "Removed cache file: " + file.getName() + ", size: " + fileSize + " bytes");
            } else {
                LogUtils.e(TAG, "Failed to delete cache file: " + file.getAbsolutePath());
            }
        }

        // Update statistics
        statistics.setDiskSizeBytes(currentSizeBytes.get());

        LogUtils.d(TAG, "Removed " + count + " entries matching pattern: " + keyPattern);
        return count;
    }

    @Override
    public void clear() {
        LogUtils.d(TAG, "Clearing disk cache");

        File[] files = cacheDir.listFiles((dir, name) -> name.endsWith(CACHE_FILE_EXTENSION));

        if (files == null) {
            LogUtils.e(TAG, "Failed to list cache files");
            return;
        }

        int count = 0;
        for (File file : files) {
            if (file.delete()) {
                count++;
            } else {
                LogUtils.e(TAG, "Failed to delete cache file: " + file.getAbsolutePath());
            }
        }

        // Reset cache size
        currentSizeBytes.set(0);

        // Update statistics
        statistics.setDiskSizeBytes(0);

        LogUtils.d(TAG, "Cleared disk cache: deleted " + count + " files");
    }

    @Override
    public int size() {
        File[] files = cacheDir.listFiles((dir, name) -> name.endsWith(CACHE_FILE_EXTENSION));
        return files != null ? files.length : 0;
    }

    @Override
    public void performMaintenance() {
        LogUtils.d(TAG, "Performing maintenance on disk cache");

        // Check if we need to clean up disk cache
        if (currentSizeBytes.get() > maxSizeBytes) {
            cleanupDiskCache();
        }

        // Remove expired entries
        removeExpiredEntries();
    }

    @Override
    public void shutdown() {
        LogUtils.d(TAG, "Shutting down disk cache strategy");
        executor.shutdown();
    }

    /**
     * Saves a cache entry to disk.
     *
     * @param key   The key for the cache entry
     * @param entry The cache entry to save
     */
    private void saveToDisk(K key, CacheEntry<V> entry) {
        // Ensure cache directory exists
        if (!cacheDir.exists()) {
            LogUtils.d(TAG, "Cache directory does not exist, creating it: " + cacheDir.getAbsolutePath());
            if (!cacheDir.mkdirs()) {
                LogUtils.e(TAG, "Failed to create cache directory: " + cacheDir.getAbsolutePath());
                statistics.incrementDiskWriteErrors();
                return; // Cannot proceed without a valid directory
            }
        }

        File cacheFile = getCacheFile(key);

        try (FileOutputStream fos = new FileOutputStream(cacheFile); BufferedOutputStream bos = new BufferedOutputStream(fos); ObjectOutputStream oos = new ObjectOutputStream(bos)) {

            // Write the entry to disk
            oos.writeObject(entry);
            oos.flush();

            // Update cache size
            long fileSize = cacheFile.length();
            currentSizeBytes.addAndGet(fileSize);

            // Update statistics
            statistics.setDiskSizeBytes(currentSizeBytes.get());

            LogUtils.d(TAG, "Saved cache entry to disk: " + cacheFile.getName() + ", size: " + fileSize + " bytes");

            // Check if we need to clean up disk cache
            if (currentSizeBytes.get() > maxSizeBytes) {
                cleanupDiskCache();
            }

        } catch (IOException e) {
            LogUtils.e(TAG, "Error saving cache entry to disk: " + cacheFile.getAbsolutePath() + " - " + e.getMessage());
            statistics.incrementDiskWriteErrors();

            // Delete the file if it exists (it might be corrupted)
            if (cacheFile.exists() && !cacheFile.delete()) {
                LogUtils.e(TAG, "Failed to delete corrupted cache file: " + cacheFile.getAbsolutePath());
            }
        }
    }

    /**
     * Loads a cache entry from disk.
     *
     * @param key The key for the cache entry
     * @return The loaded cache entry, or null if not found or an error occurred
     */
    @SuppressWarnings("unchecked")
    private CacheEntry<V> loadFromDisk(K key) {
        File cacheFile = getCacheFile(key);

        if (!cacheFile.exists()) {
            return null;
        }

        try (FileInputStream fis = new FileInputStream(cacheFile); BufferedInputStream bis = new BufferedInputStream(fis); ObjectInputStream ois = new ObjectInputStream(bis)) {

            // Read the entry from disk
            Object obj = ois.readObject();

            if (obj instanceof CacheEntry) {
                LogUtils.d(TAG, "Loaded cache entry from disk: " + cacheFile.getName());
                return (CacheEntry<V>) obj;
            } else {
                LogUtils.e(TAG, "Invalid cache entry type: " + obj.getClass().getName());
                statistics.incrementDeserializationErrors();

                // Delete the file (it's corrupted)
                if (!cacheFile.delete()) {
                    LogUtils.e(TAG, "Failed to delete corrupted cache file: " + cacheFile.getAbsolutePath());
                }
            }

        } catch (IOException | ClassNotFoundException | ClassCastException e) {
            LogUtils.e(TAG, "Error loading cache entry from disk: " + e.getMessage());
            statistics.incrementDiskReadErrors();

            // Delete the file (it's corrupted)
            if (!cacheFile.delete()) {
                LogUtils.e(TAG, "Failed to delete corrupted cache file: " + cacheFile.getAbsolutePath());
            }
        }

        return null;
    }

    /**
     * Gets the cache file for a key.
     *
     * @param key The key for the cache entry
     * @return The cache file
     */
    private File getCacheFile(K key) {
        // Create a safe filename from the key
        String filename = key.toString().replaceAll("[^a-zA-Z0-9_-]", "_") + CACHE_FILE_EXTENSION;
        return new File(cacheDir, filename);
    }

    /**
     * Calculates the current size of the disk cache.
     */
    private void calculateCacheSize() {
        File[] files = cacheDir.listFiles((dir, name) -> name.endsWith(CACHE_FILE_EXTENSION));

        if (files == null) {
            LogUtils.e(TAG, "Failed to list cache files");
            return;
        }

        long totalSize = 0;
        for (File file : files) {
            totalSize += file.length();
        }

        currentSizeBytes.set(totalSize);
        statistics.setDiskSizeBytes(totalSize);

        LogUtils.d(TAG, "Calculated disk cache size: " + totalSize + " bytes (" + files.length + " files)");
    }

    /**
     * Cleans up the disk cache by removing the oldest files until the cache size is below the maximum.
     */
    private void cleanupDiskCache() {
        LogUtils.d(TAG, "Cleaning up disk cache (current size: " + currentSizeBytes.get() + " bytes, max size: " + maxSizeBytes + " bytes)");

        File[] files = cacheDir.listFiles((dir, name) -> name.endsWith(CACHE_FILE_EXTENSION));

        if (files == null) {
            LogUtils.e(TAG, "Failed to list cache files");
            return;
        }

        // Sort files by last modified time (oldest first)
        List<File> sortedFiles = new ArrayList<>(files.length);
        Collections.addAll(sortedFiles, files);
        sortedFiles.sort((f1, f2) -> Long.compare(f1.lastModified(), f2.lastModified()));

        // Remove oldest files until we're under the limit
        int removedCount = 0;
        long removedSize = 0;

        for (File file : sortedFiles) {
            if (currentSizeBytes.get() <= maxSizeBytes * 0.8) { // Leave some buffer
                break;
            }

            long fileSize = file.length();
            if (file.delete()) {
                currentSizeBytes.addAndGet(-fileSize);
                removedCount++;
                removedSize += fileSize;

                LogUtils.d(TAG, "Removed old cache file: " + file.getName() + ", size: " + fileSize + " bytes");
            } else {
                LogUtils.e(TAG, "Failed to delete old cache file: " + file.getAbsolutePath());
            }
        }

        // Update statistics
        statistics.setDiskSizeBytes(currentSizeBytes.get());

        LogUtils.d(TAG, "Disk cache cleanup complete: removed " + removedCount + " files, freed " + removedSize + " bytes");
    }

    /**
     * Removes expired cache entries.
     */
    private void removeExpiredEntries() {
        LogUtils.d(TAG, "Removing expired cache entries");

        File[] files = cacheDir.listFiles((dir, name) -> name.endsWith(CACHE_FILE_EXTENSION));

        if (files == null) {
            LogUtils.e(TAG, "Failed to list cache files");
            return;
        }

        int validCount = 0;
        int expiredCount = 0;

        for (File file : files) {
            // Load the entry to check if it's expired
            try (FileInputStream fis = new FileInputStream(file); BufferedInputStream bis = new BufferedInputStream(fis); ObjectInputStream ois = new ObjectInputStream(bis)) {

                Object obj = ois.readObject();

                if (obj instanceof CacheEntry<?> entry) {

                    if (entry.isValid()) {
                        validCount++;
                    } else {
                        // Remove expired entry
                        long fileSize = file.length();
                        if (file.delete()) {
                            currentSizeBytes.addAndGet(-fileSize);
                            expiredCount++;

                            LogUtils.d(TAG, "Removed expired cache file: " + file.getName() + ", size: " + fileSize + " bytes");
                        } else {
                            LogUtils.e(TAG, "Failed to delete expired cache file: " + file.getAbsolutePath());
                        }
                    }
                } else {
                    // Remove invalid entry
                    if (file.delete()) {
                        LogUtils.d(TAG, "Removed invalid cache file: " + file.getName());
                    } else {
                        LogUtils.e(TAG, "Failed to delete invalid cache file: " + file.getAbsolutePath());
                    }
                }

            } catch (IOException | ClassNotFoundException | ClassCastException e) {
                LogUtils.e(TAG, "Error checking cache entry: " + e.getMessage());

                // Remove corrupted entry
                if (file.delete()) {
                    LogUtils.d(TAG, "Removed corrupted cache file: " + file.getName());
                } else {
                    LogUtils.e(TAG, "Failed to delete corrupted cache file: " + file.getAbsolutePath());
                }
            }
        }

        // Update statistics
        statistics.setValidEntries(validCount);
        statistics.setExpiredEntries(expiredCount);
        statistics.setDiskSizeBytes(currentSizeBytes.get());

        LogUtils.d(TAG, "Expired entries removal complete: " + expiredCount + " expired, " + validCount + " valid");
    }
}