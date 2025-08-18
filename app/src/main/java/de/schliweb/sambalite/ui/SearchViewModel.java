package de.schliweb.sambalite.ui;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;
import de.schliweb.sambalite.cache.IntelligentCacheManager;
import de.schliweb.sambalite.cache.SearchCacheOptimizer;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.util.LogUtils;

import javax.inject.Inject;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ViewModel for handling search functionality and caching of search results.
 * This is part of the refactored FileBrowserViewModel, focusing only on search functionality.
 */
public class SearchViewModel extends ViewModel {

    private final SmbRepository smbRepository;
    private final Executor executor;
    private final FileBrowserState state;
    private final FileListViewModel fileListViewModel;

    // Generation token to prevent stale updates after cancellation or context switch
    private final AtomicInteger searchGeneration = new AtomicInteger(0);

    // In-memory throttle for background revalidation per cache key
    private final Map<String, Long> lastRevalidateAt = new HashMap<>();

    // Minimum interval between revalidation attempts per key (ms)
    private static final long REVALIDATE_MIN_INTERVAL_MS = 2 * 60 * 1000;

    // Track last connection used for search to handle cross-connection transitions
    private String lastSearchConnectionId = "";

    @Inject
    public SearchViewModel(SmbRepository smbRepository, FileBrowserState state, FileListViewModel fileListViewModel) {
        this.smbRepository = smbRepository;
        this.state = state;
        this.fileListViewModel = fileListViewModel;
        this.executor = Executors.newSingleThreadExecutor();
        LogUtils.d("SearchViewModel", "SearchViewModel initialized");
    }

    /**
     * Gets the search results as LiveData.
     */
    public LiveData<List<SmbFileItem>> getSearchResults() {
        return state.getSearchResults();
    }

    /**
     * Gets the searching state as LiveData.
     */
    public LiveData<Boolean> isSearching() {
        return state.isSearching();
    }

    /**
     * Checks if the view model is in search mode.
     */
    public boolean isInSearchMode() {
        return state.isInSearchMode();
    }

    /**
     * Searches for files matching the query with specified options.
     *
     * @param query             The search query
     * @param searchType        The type of items to search for (0=all, 1=files only, 2=folders only)
     * @param includeSubfolders Whether to include subfolders in the search
     */
    public void searchFiles(String query, int searchType, boolean includeSubfolders) {
        if (state.getConnection() == null) {
            LogUtils.w("SearchViewModel", "Cannot search: connection is null");
            return;
        }

        if (query == null || query.trim().isEmpty()) {
            clearSearch();
            return;
        }

        LogUtils.d("SearchViewModel", "Searching for files matching query: '" + query + "', searchType: " + searchType + ", includeSubfolders: " + includeSubfolders);

        state.setCurrentSearchQuery(query.trim());
        state.setSearchMode(true);
        state.setSearching(true);
        // Remember the folder where the search started so BACK can return there
        state.setSearchStartPath(state.getCurrentPathString());

        // If connection changed since last search, clear stale results and invalidate generation
        String currentConnId = state.getConnection() != null ? state.getConnection().getId() : "";
        if (!currentConnId.equals(lastSearchConnectionId)) {
            LogUtils.d("SearchViewModel", "Connection changed from '" + lastSearchConnectionId + "' to '" + currentConnId + "'. Resetting search state.");
            lastSearchConnectionId = currentConnId;
            searchGeneration.incrementAndGet();
            state.setSearchResults(new ArrayList<>());
        }

        // Increment generation for this search
        final int myGen = searchGeneration.incrementAndGet();
        String cacheKey;
        try {
            cacheKey = IntelligentCacheManager.getInstance().generateSearchCacheKey(state.getConnection(), state.getCurrentPathString(), state.getCurrentSearchQuery(), searchType, includeSubfolders);
        } catch (Exception e) {
            LogUtils.w("SearchViewModel", "Failed to generate cache key, proceeding without revalidation throttle: " + e.getMessage());
            // Fallback cache key to avoid NPE in maps
            // Note: actual cache access below also computes its own key internally
            String tmpPath = state.getCurrentPathString() == null ? "" : state.getCurrentPathString();
            cacheKey = (state.getConnection() != null ? state.getConnection().getId() : "no_conn") + "|" + tmpPath + "|" + state.getCurrentSearchQuery() + "|" + searchType + "|" + includeSubfolders;
        }

        // Ensure effectively-final copies for background tasks
        final int stCopy = searchType;
        final boolean incSubCopy = includeSubfolders;

        executor.execute(() -> {
            try {
                // Check if we have cached search results first with comprehensive error handling
                List<SmbFileItem> cachedResults = null;
                try {
                    cachedResults = IntelligentCacheManager.getInstance().getCachedSearchResults(state.getConnection(), state.getCurrentPathString(), state.getCurrentSearchQuery(), searchType, includeSubfolders);
                } catch (ClassCastException e) {
                    LogUtils.w("SearchViewModel", "Cache corruption detected for search query '" + state.getCurrentSearchQuery() + "': " + e.getMessage());
                    // Clear corrupted cache for this specific search
                    try {
                        String searchKey = IntelligentCacheManager.getInstance().generateSearchCacheKey(state.getConnection(), state.getCurrentPathString(), state.getCurrentSearchQuery(), searchType, includeSubfolders);
                        IntelligentCacheManager.getInstance().remove(searchKey);
                        LogUtils.d("SearchViewModel", "Cleared corrupted search cache for key: " + searchKey);
                    } catch (Exception cleanupError) {
                        LogUtils.w("SearchViewModel", "Error during cache cleanup: " + cleanupError.getMessage());
                    }
                    cachedResults = null; // Ensure we proceed with fresh search
                } catch (Exception e) {
                    LogUtils.w("SearchViewModel", "Error accessing search cache: " + e.getMessage());
                    cachedResults = null; // Ensure we proceed with fresh search
                }

                if (cachedResults != null) {
                    LogUtils.d("SearchViewModel", "Using cached search results: " + cachedResults.size() + " items");

                    // Record cache hit for optimization
                    try {
                        SearchCacheOptimizer.getInstance().recordSearch(state.getConnection(), state.getCurrentSearchQuery(), cachedResults.size());
                    } catch (Exception optimizerError) {
                        LogUtils.w("SearchViewModel", "Error recording search in optimizer: " + optimizerError.getMessage());
                    }

                    // Sort cached results according to current sorting options
                    fileListViewModel.sortFiles(cachedResults);

                    // Apply only if still current
                    if (myGen == searchGeneration.get()) {
                        state.setSearchResults(cachedResults);
                        state.setSearching(false);
                    } else {
                        LogUtils.d("SearchViewModel", "Skipping applying cached results due to generation mismatch");
                    }

                    // Stale-while-revalidate: optionally trigger background revalidation
                    maybeRevalidateInBackground(myGen, stCopy, incSubCopy);
                    return;
                }

                // Perform actual search if no cache found
                LogUtils.d("SearchViewModel", "No cached search results found, performing new search");
                List<SmbFileItem> results = smbRepository.searchFiles(state.getConnection(), state.getCurrentPathString(), state.getCurrentSearchQuery(), searchType, includeSubfolders);

                LogUtils.d("SearchViewModel", "Search completed. Found " + results.size() + " matching items");

                // Record search for optimization learning
                try {
                    SearchCacheOptimizer.getInstance().recordSearch(state.getConnection(), state.getCurrentSearchQuery(), results.size());
                } catch (Exception optimizerError) {
                    LogUtils.w("SearchViewModel", "Error recording search in optimizer: " + optimizerError.getMessage());
                }

                // Cache the search results for future use (if optimization recommends it)
                try {
                    if (SearchCacheOptimizer.getInstance().shouldCacheQuery(state.getConnection(), state.getCurrentSearchQuery())) {
                        long optimalTTL = SearchCacheOptimizer.getInstance().getOptimalCacheTTL(state.getConnection(), state.getCurrentSearchQuery());

                        // Use the IntelligentCacheManager with custom TTL and error protection
                        String cacheKeyFresh = IntelligentCacheManager.getInstance().generateSearchCacheKey(state.getConnection(), state.getCurrentPathString(), state.getCurrentSearchQuery(), searchType, includeSubfolders);

                        IntelligentCacheManager.getInstance().put(cacheKeyFresh, (Serializable) new ArrayList<>(results), optimalTTL);

                        LogUtils.d("SearchViewModel", "Cached search results with TTL: " + optimalTTL + "ms");
                    } else {
                        LogUtils.d("SearchViewModel", "Search results not cached based on optimization strategy");
                    }
                } catch (Exception cacheError) {
                    LogUtils.w("SearchViewModel", "Error caching search results: " + cacheError.getMessage());
                    // Continue without caching - not critical for search functionality
                }

                // Sort results according to the current sorting options
                fileListViewModel.sortFiles(results);

                if (myGen == searchGeneration.get()) {
                    state.setSearchResults(results);
                    state.setSearching(false);
                } else {
                    LogUtils.d("SearchViewModel", "Skipping applying fresh results due to generation mismatch");
                }
            } catch (Exception e) {
                String msg = e != null ? e.getMessage() : "";
                LogUtils.e("SearchViewModel", "Search failed: " + msg);

                // Graceful handling: if the failure is due to an operation lock/timeout
                // (e.g., a download/upload is running), keep the current results and just
                // stop the searching indicator without clearing the list.
                boolean isLockTimeout = msg != null && msg.toLowerCase().contains("search operation timeout");
                if (!isLockTimeout) {
                    state.setSearchResults(new ArrayList<>());
                    state.setErrorMessage("Failed to search files: " + msg);
                } else {
                    LogUtils.w("SearchViewModel", "Search timed out due to another operation. Preserving existing results.");
                }
                state.setSearching(false);
            }
        });
    }

    /**
     * Cancels any ongoing search operation.
     * This should be called when the user wants to stop a search in progress.
     */
    // Debounce rapid consecutive cancellations to avoid log spam and redundant work
    private final java.util.concurrent.atomic.AtomicLong lastCancelAt = new java.util.concurrent.atomic.AtomicLong(0);

    public void cancelSearch() {
        boolean currentlySearching = Boolean.TRUE.equals(state.isSearching().getValue());
        boolean inSearchMode = state.isInSearchMode();

        // If there's nothing to cancel/clear, return silently
        if (!currentlySearching && !inSearchMode) {
            return;
        }

        long now = System.currentTimeMillis();
        long prev = lastCancelAt.get();
        if (now - prev < 750) {
            LogUtils.d("SearchViewModel", "Cancel search ignored (debounced)");
            return;
        }
        lastCancelAt.set(now);

        LogUtils.d("SearchViewModel", "Cancelling search");

        if (currentlySearching) {
            // Invalidate any in-flight search updates
            searchGeneration.incrementAndGet();
            smbRepository.cancelSearch();
            // Force stop the search state and clear search mode
            state.setSearching(false);
            clearSearch();
        } else {
            // Even if not actively searching, invalidate to be safe
            searchGeneration.incrementAndGet();
            // If we're in search mode (e.g., showing cached results), still exit search gracefully
            if (inSearchMode) {
                clearSearch();
            }
        }
    }

    /**
     * Clears the search results and returns to normal browsing.
     */
    public void clearSearch() {
        LogUtils.d("SearchViewModel", "Clearing search results");
        state.setSearchMode(false);
        state.setCurrentSearchQuery("");
        state.setSearchResults(new ArrayList<>());
        // Navigate back to the folder where the search started, if known
        String startPath = state.getSearchStartPath();
        if (startPath != null && !startPath.isEmpty()) {
            LogUtils.d("SearchViewModel", "Returning to search start folder: " + startPath);
            fileListViewModel.navigateToPathWithHierarchy(startPath);
            state.setSearchStartPath("");
        } else {
            // Reload the current directory files as a fallback
            fileListViewModel.loadFiles();
        }
    }

    /**
     * Gets the current search query.
     *
     * @return The current search query
     */
    public String getCurrentSearchQuery() {
        return state.getCurrentSearchQuery();
    }

    /**
     * Gets the connection ID.
     *
     * @return The connection ID, or empty string if no connection is set
     */
    public String getConnectionId() {
        if (state.getConnection() != null) {
            return state.getConnection().getId();
        }
        return "";
    }

    /**
     * Gets the current search type.
     *
     * @return The current search type (0=all, 1=files only, 2=folders only)
     */
    public int getCurrentSearchType() {
        // This is a placeholder. In a real implementation, this would be stored in the state.
        return 0;
    }

    /**
     * Gets whether subfolders are included in the search.
     *
     * @return Whether subfolders are included in the search
     */
    public boolean isIncludeSubfolders() {
        // This is a placeholder. In a real implementation, this would be stored in the state.
        return true;
    }
    private void maybeRevalidateInBackground(final int gen, final int searchType, final boolean includeSubfolders) {
        // Build a cache key for throttle tracking
        String keyForThrottle;
        try {
            keyForThrottle = IntelligentCacheManager.getInstance().generateSearchCacheKey(state.getConnection(), state.getCurrentPathString(), state.getCurrentSearchQuery(), searchType, includeSubfolders);
        } catch (Exception e) {
            String tmpPath = state.getCurrentPathString() == null ? "" : state.getCurrentPathString();
            keyForThrottle = (state.getConnection() != null ? state.getConnection().getId() : "no_conn") + "|" + tmpPath + "|" + state.getCurrentSearchQuery() + "|" + searchType + "|" + includeSubfolders;
        }

        try {
            long now = System.currentTimeMillis();
            Long last = lastRevalidateAt.get(keyForThrottle);
            if (last != null && (now - last) < REVALIDATE_MIN_INTERVAL_MS) {
                LogUtils.d("SearchViewModel", "Revalidation skipped (throttled) for key: " + keyForThrottle);
                return;
            }
            lastRevalidateAt.put(keyForThrottle, now);
        } catch (Exception e) {
            LogUtils.w("SearchViewModel", "Revalidation throttle error: " + e.getMessage());
        }

        // Run revalidation in background
        final String keyForThrottleFinal = keyForThrottle;
        executor.execute(() -> {
            try {
                // Ensure context hasn't changed
                if (gen != searchGeneration.get()) {
                    LogUtils.d("SearchViewModel", "Abort revalidation due to generation change");
                    return;
                }

                // Perform a fresh search to validate
                List<SmbFileItem> fresh = smbRepository.searchFiles(state.getConnection(), state.getCurrentPathString(), state.getCurrentSearchQuery(), searchType, includeSubfolders);

                // Compare with currently displayed results
                List<SmbFileItem> current = state.getSearchResults().getValue();
                if (current == null) current = new ArrayList<>();
                String sigFresh = computeSignature(fresh);
                String sigCurrent = computeSignature(current);

                if (!sigFresh.equals(sigCurrent)) {
                    LogUtils.i("SearchViewModel", "Revalidation found updated results. Updating cache and UI.");
                    // Update cache with optimizer TTL
                    try {
                        long ttl = SearchCacheOptimizer.getInstance().getOptimalCacheTTL(state.getConnection(), state.getCurrentSearchQuery());
                        IntelligentCacheManager.getInstance().put(keyForThrottleFinal, (Serializable) new ArrayList<>(fresh), ttl);
                    } catch (Exception cacheErr) {
                        LogUtils.w("SearchViewModel", "Error updating cache after revalidation: " + cacheErr.getMessage());
                    }

                    // Sort and update UI if still current
                    fileListViewModel.sortFiles(fresh);
                    if (gen == searchGeneration.get() && state.isInSearchMode()) {
                        state.setSearchResults(fresh);
                        state.setSearching(false);
                    }
                } else {
                    LogUtils.d("SearchViewModel", "Revalidation shows no changes.");
                }
            } catch (Exception e) {
                LogUtils.w("SearchViewModel", "Revalidation error: " + e.getMessage());
            }
        });
    }

    private String computeSignature(List<SmbFileItem> items) {
        if (items == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append(items.size()).append('|');
        for (SmbFileItem it : items) {
            if (it == null) continue;
            sb.append(it.getPath()).append('#')
              .append(it.getName()).append('#')
              .append(it.getType()).append('#')
              .append(it.getSize()).append('#')
              .append(it.getLastModified() != null ? it.getLastModified().getTime() : 0L)
              .append(';');
        }
        return Integer.toHexString(sb.toString().hashCode());
    }
}