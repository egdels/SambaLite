package de.schliweb.sambalite.cache.statistics;

import lombok.Getter;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Provides statistics about cache performance and usage.
 * This class tracks various metrics such as hit rate, memory usage, and disk usage.
 */
public class CacheStatistics {

    // Cache size metrics
    private final AtomicInteger memoryEntries = new AtomicInteger(0);
    private final AtomicLong diskSizeBytes = new AtomicLong(0);

    // Cache entry metrics
    private final AtomicInteger validEntries = new AtomicInteger(0);
    private final AtomicInteger expiredEntries = new AtomicInteger(0);

    // Cache performance metrics
    private final AtomicLong cacheRequests = new AtomicLong(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);

    // Cache operation metrics
    private final AtomicLong putOperations = new AtomicLong(0);
    private final AtomicLong getOperations = new AtomicLong(0);
    private final AtomicLong removeOperations = new AtomicLong(0);

    // Cache error metrics
    private final AtomicInteger serializationErrors = new AtomicInteger(0);
    private final AtomicInteger deserializationErrors = new AtomicInteger(0);
    private final AtomicInteger diskWriteErrors = new AtomicInteger(0);
    private final AtomicInteger diskReadErrors = new AtomicInteger(0);

    /**
     * Gets the number of entries in memory cache.
     *
     * @return The number of memory entries
     */
    public int getMemoryEntries() {
        return memoryEntries.get();
    }

    /**
     * Sets the number of entries in memory cache.
     *
     * @param count The number of memory entries
     */
    public void setMemoryEntries(int count) {
        memoryEntries.set(count);
    }

    /**
     * Gets the size of disk cache in bytes.
     *
     * @return The disk cache size in bytes
     */
    public long getDiskSizeBytes() {
        return diskSizeBytes.get();
    }

    /**
     * Sets the size of disk cache in bytes.
     *
     * @param bytes The disk cache size in bytes
     */
    public void setDiskSizeBytes(long bytes) {
        diskSizeBytes.set(bytes);
    }

    /**
     * Gets the number of valid entries in cache.
     *
     * @return The number of valid entries
     */
    public int getValidEntries() {
        return validEntries.get();
    }

    /**
     * Sets the number of valid entries in cache.
     *
     * @param count The number of valid entries
     */
    public void setValidEntries(int count) {
        validEntries.set(count);
    }

    /**
     * Gets the number of expired entries in cache.
     *
     * @return The number of expired entries
     */
    public int getExpiredEntries() {
        return expiredEntries.get();
    }

    /**
     * Sets the number of expired entries in cache.
     *
     * @param count The number of expired entries
     */
    public void setExpiredEntries(int count) {
        expiredEntries.set(count);
    }

    /**
     * Increments the count of cache requests.
     */
    public void incrementCacheRequests() {
        cacheRequests.incrementAndGet();
    }

    /**
     * Gets the number of cache requests.
     *
     * @return The number of cache requests
     */
    public long getCacheRequests() {
        return cacheRequests.get();
    }

    /**
     * Increments the count of cache hits.
     */
    public void incrementCacheHits() {
        cacheHits.incrementAndGet();
    }

    /**
     * Gets the number of cache hits.
     *
     * @return The number of cache hits
     */
    public long getCacheHits() {
        return cacheHits.get();
    }

    /**
     * Increments the count of cache misses.
     */
    public void incrementCacheMisses() {
        cacheMisses.incrementAndGet();
    }

    /**
     * Gets the number of cache misses.
     *
     * @return The number of cache misses
     */
    public long getCacheMisses() {
        return cacheMisses.get();
    }

    /**
     * Increments the count of put operations.
     */
    public void incrementPutOperations() {
        putOperations.incrementAndGet();
    }

    /**
     * Gets the number of put operations.
     *
     * @return The number of put operations
     */
    public long getPutOperations() {
        return putOperations.get();
    }

    /**
     * Increments the count of get operations.
     */
    public void incrementGetOperations() {
        getOperations.incrementAndGet();
    }

    /**
     * Gets the number of get operations.
     *
     * @return The number of get operations
     */
    public long getGetOperations() {
        return getOperations.get();
    }

    /**
     * Increments the count of remove operations.
     */
    public void incrementRemoveOperations() {
        removeOperations.incrementAndGet();
    }

    /**
     * Gets the number of remove operations.
     *
     * @return The number of remove operations
     */
    public long getRemoveOperations() {
        return removeOperations.get();
    }

    /**
     * Increments the count of serialization errors.
     */
    public void incrementSerializationErrors() {
        serializationErrors.incrementAndGet();
    }

    /**
     * Gets the number of serialization errors.
     *
     * @return The number of serialization errors
     */
    public int getSerializationErrors() {
        return serializationErrors.get();
    }

    /**
     * Increments the count of deserialization errors.
     */
    public void incrementDeserializationErrors() {
        deserializationErrors.incrementAndGet();
    }

    /**
     * Gets the number of deserialization errors.
     *
     * @return The number of deserialization errors
     */
    public int getDeserializationErrors() {
        return deserializationErrors.get();
    }

    /**
     * Increments the count of disk write errors.
     */
    public void incrementDiskWriteErrors() {
        diskWriteErrors.incrementAndGet();
    }

    /**
     * Gets the number of disk write errors.
     *
     * @return The number of disk write errors
     */
    public int getDiskWriteErrors() {
        return diskWriteErrors.get();
    }

    /**
     * Increments the count of disk read errors.
     */
    public void incrementDiskReadErrors() {
        diskReadErrors.incrementAndGet();
    }

    /**
     * Gets the number of disk read errors.
     *
     * @return The number of disk read errors
     */
    public int getDiskReadErrors() {
        return diskReadErrors.get();
    }

    /**
     * Calculates the cache hit rate.
     *
     * @return The cache hit rate (0.0 to 1.0)
     */
    public double getHitRate() {
        long requests = cacheRequests.get();
        if (requests == 0) {
            return 0.0;
        }
        return (double) cacheHits.get() / requests;
    }

    /**
     * Resets all statistics to zero.
     */
    public void reset() {
        memoryEntries.set(0);
        diskSizeBytes.set(0);
        validEntries.set(0);
        expiredEntries.set(0);
        cacheRequests.set(0);
        cacheHits.set(0);
        cacheMisses.set(0);
        putOperations.set(0);
        getOperations.set(0);
        removeOperations.set(0);
        serializationErrors.set(0);
        deserializationErrors.set(0);
        diskWriteErrors.set(0);
        diskReadErrors.set(0);
    }

    /**
     * Creates a snapshot of the current statistics.
     *
     * @return A new CacheStatistics object with the current values
     */
    public CacheStatisticsSnapshot createSnapshot() {
        return new CacheStatisticsSnapshot(memoryEntries.get(), diskSizeBytes.get(), validEntries.get(), expiredEntries.get(), cacheRequests.get(), cacheHits.get(), cacheMisses.get(), putOperations.get(), getOperations.get(), removeOperations.get(), serializationErrors.get(), deserializationErrors.get(), diskWriteErrors.get(), diskReadErrors.get());
    }

    /**
     * An immutable snapshot of cache statistics at a point in time.
     */
    @Getter
    public static class CacheStatisticsSnapshot {
        private final int memoryEntries;
        private final long diskSizeBytes;
        private final int validEntries;
        private final int expiredEntries;
        private final long cacheRequests;
        private final long cacheHits;
        private final long cacheMisses;
        private final long putOperations;
        private final long getOperations;
        private final long removeOperations;
        private final int serializationErrors;
        private final int deserializationErrors;
        private final int diskWriteErrors;
        private final int diskReadErrors;

        private CacheStatisticsSnapshot(int memoryEntries, long diskSizeBytes, int validEntries, int expiredEntries, long cacheRequests, long cacheHits, long cacheMisses, long putOperations, long getOperations, long removeOperations, int serializationErrors, int deserializationErrors, int diskWriteErrors, int diskReadErrors) {
            this.memoryEntries = memoryEntries;
            this.diskSizeBytes = diskSizeBytes;
            this.validEntries = validEntries;
            this.expiredEntries = expiredEntries;
            this.cacheRequests = cacheRequests;
            this.cacheHits = cacheHits;
            this.cacheMisses = cacheMisses;
            this.putOperations = putOperations;
            this.getOperations = getOperations;
            this.removeOperations = removeOperations;
            this.serializationErrors = serializationErrors;
            this.deserializationErrors = deserializationErrors;
            this.diskWriteErrors = diskWriteErrors;
            this.diskReadErrors = diskReadErrors;
        }

        public double getHitRate() {
            if (cacheRequests == 0) {
                return 0.0;
            }
            return (double) cacheHits / cacheRequests;
        }

        public int getTotalErrors() {
            return serializationErrors + deserializationErrors + diskWriteErrors + diskReadErrors;
        }
    }
}