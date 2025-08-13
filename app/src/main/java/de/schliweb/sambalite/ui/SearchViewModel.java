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
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * ViewModel for handling search functionality and caching of search results.
 * This is part of the refactored FileBrowserViewModel, focusing only on search functionality.
 */
public class SearchViewModel extends ViewModel {

    private final SmbRepository smbRepository;
    private final Executor executor;
    private final FileBrowserState state;
    private final FileListViewModel fileListViewModel;

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

        // TODO: FIX cached SearchResults
        IntelligentCacheManager.getInstance().remove(IntelligentCacheManager.getInstance().generateSearchCacheKey(state.getConnection(), state.getCurrentPathString(), state.getCurrentSearchQuery(), searchType, includeSubfolders));

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

                    state.setSearchResults(cachedResults);
                    state.setSearching(false);
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
                        String cacheKey = IntelligentCacheManager.getInstance().generateSearchCacheKey(state.getConnection(), state.getCurrentPathString(), state.getCurrentSearchQuery(), searchType, includeSubfolders);

                        IntelligentCacheManager.getInstance().put(cacheKey, (Serializable) new ArrayList<>(results), optimalTTL);

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

                state.setSearchResults(results);
                state.setSearching(false);
            } catch (Exception e) {
                LogUtils.e("SearchViewModel", "Search failed: " + e.getMessage());
                state.setSearchResults(new ArrayList<>());
                state.setSearching(false);
                state.setErrorMessage("Failed to search files: " + e.getMessage());
            }
        });
    }

    /**
     * Cancels any ongoing search operation.
     * This should be called when the user wants to stop a search in progress.
     */
    public void cancelSearch() {
        LogUtils.d("SearchViewModel", "Cancelling search");
        if (state.isSearching().getValue() != null && state.isSearching().getValue()) {
            smbRepository.cancelSearch();
            // Force stop the search state and clear search mode
            state.setSearching(false);
            clearSearch();
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
        // Reload the current directory files
        fileListViewModel.loadFiles();
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
}