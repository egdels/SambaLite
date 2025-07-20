package de.schliweb.sambalite.cache.entry;

import lombok.Getter;

import java.io.Serializable;

/**
 * Represents a cache entry with data and metadata.
 * This class encapsulates the cached data along with information about its expiration and version.
 *
 * @param <T> The type of data stored in the cache entry
 */
public class CacheEntry<T> implements Serializable {
    private static final long serialVersionUID = 3L; // Incremented from original version

    /**
     * The cached data.
     */
    private final T data;


    @Getter
    private final long expirationTime;


    @Getter
    private final int cacheVersion;


    @Getter
    private final long creationTime;

    @Getter
    private long lastAccessTime;

    /**
     * Creates a new cache entry.
     *
     * @param data           The data to cache
     * @param expirationTime The time when this entry expires (in milliseconds since epoch)
     */
    public CacheEntry(T data, long expirationTime) {
        this.data = data;
        this.expirationTime = expirationTime;
        this.cacheVersion = 3; // Current cache format version
        this.creationTime = System.currentTimeMillis();
        this.lastAccessTime = this.creationTime;
    }

    /**
     * Gets the cached data.
     *
     * @return The cached data
     */
    public T getData() {
        this.lastAccessTime = System.currentTimeMillis();
        return data;
    }

    /**
     * Updates the last access time to the current time.
     */
    public void updateLastAccessTime() {
        this.lastAccessTime = System.currentTimeMillis();
    }

    /**
     * Checks if this entry is valid (not expired and has the correct version).
     *
     * @return true if the entry is valid, false otherwise
     */
    public boolean isValid() {
        return System.currentTimeMillis() < expirationTime && cacheVersion == 3;
    }

    /**
     * Gets the time-to-live (TTL) in milliseconds.
     *
     * @return The TTL (in milliseconds)
     */
    public long getTtl() {
        return Math.max(0, expirationTime - System.currentTimeMillis());
    }

    /**
     * Gets the age of this entry in milliseconds.
     *
     * @return The age (in milliseconds)
     */
    public long getAge() {
        return System.currentTimeMillis() - creationTime;
    }

    /**
     * Gets the time since last access in milliseconds.
     *
     * @return The time since last access (in milliseconds)
     */
    public long getTimeSinceLastAccess() {
        return System.currentTimeMillis() - lastAccessTime;
    }
}