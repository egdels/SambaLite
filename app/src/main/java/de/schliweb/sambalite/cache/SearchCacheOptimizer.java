package de.schliweb.sambalite.cache;

import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.util.LogUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SearchCacheOptimizer provides intelligent search caching strategies
 * to improve search performance by learning from user search patterns.
 * <p>
 * Features:
 * - Tracks frequently used search queries
 * - Provides intelligent cache warming based on search patterns
 * - Optimizes cache invalidation strategies
 * - Suggests related searches based on query history
 */
public class SearchCacheOptimizer {

    private static final String TAG = "SearchCacheOptimizer";
    private static SearchCacheOptimizer instance;
    // Track search query frequency per connection
    private final Map<String, ConcurrentHashMap<String, AtomicInteger>> searchFrequency;
    // Track search result sizes for cache optimization
    private final Map<String, Integer> queryResultSizes;
    // Recently used searches for quick access
    private final Map<String, List<String>> recentSearches;

    private SearchCacheOptimizer() {
        this.searchFrequency = new ConcurrentHashMap<>();
        this.queryResultSizes = new ConcurrentHashMap<>();
        this.recentSearches = new ConcurrentHashMap<>();
    }

    public static SearchCacheOptimizer getInstance() {
        if (instance == null) {
            instance = new SearchCacheOptimizer();
        }
        return instance;
    }

    /**
     * Records a search query for learning purposes.
     */
    public void recordSearch(SmbConnection connection, String query, int resultCount) {
        String connectionKey = getConnectionKey(connection);

        // Track frequency
        searchFrequency.computeIfAbsent(connectionKey, k -> new ConcurrentHashMap<>()).computeIfAbsent(query, k -> new AtomicInteger(0)).incrementAndGet();

        // Track result size
        queryResultSizes.put(generateQueryKey(connectionKey, query), resultCount);

        // Add to recent searches
        List<String> recent = recentSearches.computeIfAbsent(connectionKey, k -> new ArrayList<>());
        synchronized (recent) {
            recent.remove(query); // Remove if already exists
            recent.add(0, query); // Add to front

            // Keep only last 20 searches
            if (recent.size() > 20) {
                recent.remove(recent.size() - 1);
            }
        }

        LogUtils.d(TAG, "Recorded search: " + query + " for connection: " + connection.getName() + " (result count: " + resultCount + ")");
    }

    /**
     * Determines if a query should be cached based on its historical performance.
     */
    public boolean shouldCacheQuery(SmbConnection connection, String query) {
        String connectionKey = getConnectionKey(connection);
        String queryKey = generateQueryKey(connectionKey, query);

        // Always cache if it's been searched before
        ConcurrentHashMap<String, AtomicInteger> frequencies = searchFrequency.get(connectionKey);
        if (frequencies != null && frequencies.containsKey(query)) {
            return true;
        }

        // Cache based on result size (avoid caching very large result sets)
        Integer resultSize = queryResultSizes.get(queryKey);
        if (resultSize != null && resultSize > 1000) {
            LogUtils.d(TAG, "Not caching large result set (" + resultSize + " items) for query: " + query);
            return false;
        }

        return true;
    }

    /**
     * Gets the optimal cache TTL for a query based on its usage pattern.
     */
    public long getOptimalCacheTTL(SmbConnection connection, String query) {
        String connectionKey = getConnectionKey(connection);
        ConcurrentHashMap<String, AtomicInteger> frequencies = searchFrequency.get(connectionKey);

        if (frequencies == null) {
            return 5 * 60 * 1000; // Default 5 minutes
        }

        AtomicInteger frequency = frequencies.get(query);
        if (frequency == null) {
            return 5 * 60 * 1000; // Default 5 minutes
        }

        int count = frequency.get();

        // More frequently used queries get longer cache time
        if (count >= 10) {
            return 30 * 60 * 1000; // 30 minutes for very frequent queries
        } else if (count >= 5) {
            return 15 * 60 * 1000; // 15 minutes for frequent queries
        } else {
            return 5 * 60 * 1000;  // 5 minutes for occasional queries
        }
    }

    /**
     * Gets cache statistics for monitoring purposes.
     */
    public SearchCacheStatistics getStatistics() {
        SearchCacheStatistics stats = new SearchCacheStatistics();

        stats.totalConnections = searchFrequency.size();
        stats.totalQueries = searchFrequency.values().stream().mapToInt(Map::size).sum();
        stats.totalSearches = searchFrequency.values().stream().flatMap(map -> map.values().stream()).mapToInt(AtomicInteger::get).sum();

        return stats;
    }

    private String getConnectionKey(SmbConnection connection) {
        return "conn_" + connection.getId();
    }

    private String generateQueryKey(String connectionKey, String query) {
        return connectionKey + "_query_" + query.hashCode();
    }

    /**
     * Statistics for search cache optimization.
     */
    public static class SearchCacheStatistics {
        public int totalConnections;
        public int totalQueries;
        public int totalSearches;

        @Override
        public String toString() {
            return "SearchCacheStatistics{" + "totalConnections=" + totalConnections + ", totalQueries=" + totalQueries + ", totalSearches=" + totalSearches + '}';
        }
    }
}
