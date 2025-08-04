package de.schliweb.sambalite.ui;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;
import de.schliweb.sambalite.cache.IntelligentCacheManager;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.util.LogUtils;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * ViewModel for handling file browsing, navigation, sorting, and filtering.
 * This is part of the refactored FileBrowserViewModel, focusing only on file list management.
 */
public class FileListViewModel extends ViewModel {

    private final SmbRepository smbRepository;
    private final Executor executor;
    private final android.content.Context context;
    private final FileBrowserState state;

    @Inject
    public FileListViewModel(SmbRepository smbRepository, android.content.Context context, FileBrowserState state) {
        this.smbRepository = smbRepository;
        this.context = context;
        this.state = state;
        this.executor = Executors.newSingleThreadExecutor();
        LogUtils.d("FileListViewModel", "FileListViewModel initialized");
    }

    /**
     * Sets the connection to use for browsing files.
     *
     * @param connection The connection to use
     */
    public void setConnection(SmbConnection connection) {
        LogUtils.d("FileListViewModel", "Setting connection: " + connection.getName());
        state.setConnection(connection);
        loadFiles();
    }

    /**
     * Gets the list of files as LiveData.
     */
    public LiveData<List<SmbFileItem>> getFiles() {
        return state.getFiles();
    }

    /**
     * Gets the loading state as LiveData.
     */
    public LiveData<Boolean> isLoading() {
        return state.isLoading();
    }

    /**
     * Gets the error message as LiveData.
     */
    public LiveData<String> getErrorMessage() {
        return state.getErrorMessage();
    }

    /**
     * Gets the current path as LiveData.
     */
    public LiveData<String> getCurrentPath() {
        return state.getCurrentPath();
    }

    /**
     * Gets the current sort option as LiveData.
     */
    public LiveData<FileSortOption> getSortOption() {
        return state.getSortOption();
    }

    /**
     * Sets the sort option.
     *
     * @param option The sort option to set
     */
    public void setSortOption(FileSortOption option) {
        state.setSortOption(option);

        // Invalidate cache when sorting changes to ensure fresh loading with new sort order
        if (state.getConnection() != null) {
            IntelligentCacheManager.getInstance().invalidateConnection(state.getConnection());
        }

        // Reload files with new sorting
        if (state.isInSearchMode()) {
            // Re-sort search results
            List<SmbFileItem> results = state.getSearchResults().getValue();
            if (results != null) {
                sortFiles(results);
                state.setSearchResults(results);
            }
        } else {
            // Reload files with new sorting
            loadFiles();
        }
    }

    /**
     * Gets the current "directories first" flag as LiveData.
     */
    public LiveData<Boolean> getDirectoriesFirst() {
        return state.getDirectoriesFirst();
    }

    /**
     * Sets the "directories first" flag.
     *
     * @param directoriesFirst Whether to show directories first
     */
    public void setDirectoriesFirst(boolean directoriesFirst) {
        state.setDirectoriesFirst(directoriesFirst);

        // Invalidate cache when sorting changes to ensure fresh loading with new sort order
        if (state.getConnection() != null) {
            IntelligentCacheManager.getInstance().invalidateConnection(state.getConnection());
        }

        // Reload files with new sorting
        if (state.isInSearchMode()) {
            // Re-sort search results
            List<SmbFileItem> results = state.getSearchResults().getValue();
            if (results != null) {
                sortFiles(results);
                state.setSearchResults(results);
            }
        } else {
            // Reload files with new sorting
            loadFiles();
        }
    }

    /**
     * Sorts a list of files according to the current sorting options.
     *
     * @param fileList The list of files to sort
     */
    public void sortFiles(List<SmbFileItem> fileList) {
        LogUtils.d("FileListViewModel", "Sorting files with option: " + state.getCurrentSortOption() + 
                ", directoriesFirst: " + state.isDirectoriesFirst());

        Collections.sort(fileList, (file1, file2) -> {
            // If directoriesFirst is true, directories come before files
            if (state.isDirectoriesFirst()) {
                if (file1.isDirectory() && !file2.isDirectory()) {
                    return -1;
                }
                if (!file1.isDirectory() && file2.isDirectory()) {
                    return 1;
                }
            }

            // Both are directories or both are files, sort according to the current sort option
            if (state.getCurrentSortOption() == FileSortOption.NAME) {
                return file1.getName().compareToIgnoreCase(file2.getName());
            } else if (state.getCurrentSortOption() == FileSortOption.DATE) {
                // If lastModified is null, treat it as oldest (comes last)
                if (file1.getLastModified() == null) {
                    return file2.getLastModified() == null ? 0 : 1;
                }
                if (file2.getLastModified() == null) {
                    return -1;
                }
                // Sort by date, newest first
                return file2.getLastModified().compareTo(file1.getLastModified());
            } else if (state.getCurrentSortOption() == FileSortOption.SIZE) {
                // Directories have size 0, so if directoriesFirst is false, we need to handle this case
                if (!state.isDirectoriesFirst()) {
                    if (file1.isDirectory() && file2.isDirectory()) {
                        // Both are directories, sort by name
                        return file1.getName().compareToIgnoreCase(file2.getName());
                    }
                    if (file1.isDirectory()) {
                        return -1; // Directories come before files when sorting by size
                    }
                    if (file2.isDirectory()) {
                        return 1; // Files come after directories when sorting by size
                    }
                }
                // Sort by size, largest first
                return Long.compare(file2.getSize(), file1.getSize());
            } else {
                return file1.getName().compareToIgnoreCase(file2.getName());
            }
        });
    }

    /**
     * Loads the list of files from the repository.
     */
    public void loadFiles() {
        loadFiles(true);
    }
    
    /**
     * Refreshes the current directory by forcing a reload from the server.
     * This method should be called when the user performs a "pull to refresh" gesture
     * or when there's a need to ensure the displayed files match what's on the server.
     * This is particularly useful when files might have been deleted directly on the server.
     */
    public void refreshCurrentDirectory() {
        LogUtils.d("FileListViewModel", "Manual refresh requested for current directory: " + state.getCurrentPathString());
        
        // Invalidate the cache for this path to ensure fresh data is loaded
        if (state.getConnection() != null) {
            IntelligentCacheManager.getInstance().invalidateFileList(state.getConnection(), state.getCurrentPathString());
        }
        
        // Load files with loading indicator
        loadFilesForceRefresh(true);
    }
    
    /**
     * Loads the list of files from the repository, bypassing the cache.
     * 
     * @param showLoadingIndicator Whether to show the loading indicator
     */
    private void loadFilesForceRefresh(boolean showLoadingIndicator) {
        if (state.getConnection() == null) {
            LogUtils.w("FileListViewModel", "Cannot load files: connection is null");
            return;
        }

        String path = state.getCurrentPathString().isEmpty() ? "root" : state.getCurrentPathString();
        LogUtils.d("FileListViewModel", "Force refreshing files from: " + path + ", showLoadingIndicator: " + showLoadingIndicator);
        
        // Only set loading state to true if showLoadingIndicator is true
        if (showLoadingIndicator) {
            state.setLoading(true);
        }
        
        executor.execute(() -> {
            try {
                // Skip cache and load directly from server
                List<SmbFileItem> fileList = smbRepository.listFiles(
                        state.getConnection(), state.getCurrentPathString());
                LogUtils.d("FileListViewModel", "Loaded " + fileList.size() + " files from server: " + path);

                // Sort files according to the current sorting options
                sortFiles(fileList);

                // Update the cache with the fresh data
                IntelligentCacheManager.getInstance().cacheFileList(
                        state.getConnection(), state.getCurrentPathString(), fileList);

                state.setFiles(fileList);
                if (showLoadingIndicator) {
                    state.setLoading(false);
                }
            } catch (Exception e) {
                LogUtils.e("FileListViewModel", "Failed to load files: " + e.getMessage());
                state.setFiles(new ArrayList<>());
                if (showLoadingIndicator) {
                    state.setLoading(false);
                }
                state.setErrorMessage("Failed to load files: " + e.getMessage());
            }
        });
    }
    
    /**
     * Loads the list of files from the repository.
     * 
     * @param showLoadingIndicator Whether to show the loading indicator
     */
    public void loadFiles(boolean showLoadingIndicator) {
        if (state.getConnection() == null) {
            LogUtils.w("FileListViewModel", "Cannot load files: connection is null");
            return;
        }

        String path = state.getCurrentPathString().isEmpty() ? "root" : state.getCurrentPathString();
        LogUtils.d("FileListViewModel", "Loading files from: " + path + ", showLoadingIndicator: " + showLoadingIndicator);
        
        // Only set loading state to true if showLoadingIndicator is true
        if (showLoadingIndicator) {
            state.setLoading(true);
        }
        
        executor.execute(() -> {
            try {
                // Check if we can use cache for loading
                List<SmbFileItem> cachedFiles = IntelligentCacheManager.getInstance().getCachedFileList(
                        state.getConnection(), state.getCurrentPathString());
                if (cachedFiles != null) {
                    LogUtils.d("FileListViewModel", "Loaded " + cachedFiles.size() + 
                            " files from cache: " + state.getCurrentPathString());
                    // Sort cached files according to current sorting options
                    sortFiles(cachedFiles);
                    state.setFiles(cachedFiles);
                    if (showLoadingIndicator) {
                        state.setLoading(false);
                    }
                    return;
                }

                List<SmbFileItem> fileList = smbRepository.listFiles(
                        state.getConnection(), state.getCurrentPathString());
                LogUtils.d("FileListViewModel", "Loaded " + fileList.size() + " files from: " + path);

                // Sort files according to the current sorting options
                sortFiles(fileList);

                // Cache the loaded file list
                IntelligentCacheManager.getInstance().cacheFileList(
                        state.getConnection(), state.getCurrentPathString(), fileList);

                // Preload common search patterns in the background for better search performance
                IntelligentCacheManager.getInstance().preloadCommonSearches(
                        state.getConnection(), state.getCurrentPathString());

                state.setFiles(fileList);
                if (showLoadingIndicator) {
                    state.setLoading(false);
                }
            } catch (Exception e) {
                LogUtils.e("FileListViewModel", "Failed to load files: " + e.getMessage());
                state.setFiles(new ArrayList<>());
                if (showLoadingIndicator) {
                    state.setLoading(false);
                }
                state.setErrorMessage("Failed to load files: " + e.getMessage());
            }
        });
    }

    /**
     * Navigates to a directory.
     *
     * @param directory The directory to navigate to
     */
    public void navigateToDirectory(SmbFileItem directory) {
        if (!directory.isDirectory()) {
            LogUtils.w("FileListViewModel", "Cannot navigate to non-directory: " + directory.getName());
            return;
        }

        LogUtils.d("FileListViewModel", "Navigating to directory: " + directory.getName());
        state.pushPath(state.getCurrentPathString());
        state.setCurrentPath(directory.getPath());
        loadFiles();
    }

    /**
     * Navigates to the parent directory.
     *
     * @return true if navigation was successful, false if already at the root
     */
    public boolean navigateUp() {
        if (!state.hasParentDirectory()) {
            LogUtils.w("FileListViewModel", "Cannot navigate up: already at root");
            return false;
        }

        LogUtils.d("FileListViewModel", "Navigating up from: " + state.getCurrentPathString());
        state.setCurrentPath(state.popPath());
        loadFiles();
        return true;
    }

    /**
     * Navigates to a specific path.
     *
     * @param path The path to navigate to
     */
    public void navigateToPath(String path) {
        if (path == null || path.isEmpty()) {
            LogUtils.w("FileListViewModel", "Cannot navigate to empty path");
            return;
        }

        LogUtils.d("FileListViewModel", "Navigating to path: " + path);
        // Clear the navigation stack and set the current path
        while (state.hasParentDirectory()) {
            state.popPath();
        }
        state.setCurrentPath(path);
        loadFiles();
    }

    /**
     * Navigates to a path and builds the correct navigation hierarchy.
     * This method ensures that the navigation stack is properly built so that
     * the user can navigate back up the directory tree.
     *
     * @param path The path to navigate to
     */
    public void navigateToPathWithHierarchy(String path) {
        if (path == null || path.isEmpty()) {
            LogUtils.w("FileListViewModel", "Cannot navigate to empty path");
            return;
        }

        LogUtils.d("FileListViewModel", "Navigating to path with hierarchy: " + path);

        // Clear the navigation stack first
        while (state.hasParentDirectory()) {
            state.popPath();
        }

        // Handle root path
        if (path.equals("/") || path.isEmpty()) {
            state.setCurrentPath("");
            loadFiles();
            return;
        }

        // Remove leading slash if present
        String cleanPath = path.startsWith("/") ? path.substring(1) : path;

        // Split the path into parts
        String[] pathParts = cleanPath.split("/");

        // Start from root (empty path) and build navigation stack step by step
        state.setCurrentPath("");

        // For each path segment, push the previous path and navigate to the next
        String buildPath = "";
        for (String part : pathParts) {
            if (part != null && !part.isEmpty()) {
                // Push the current path before moving to next level
                state.pushPath(buildPath);

                // Build the next path
                buildPath = buildPath.isEmpty() ? part : buildPath + "/" + part;

                LogUtils.d("FileListViewModel", "Building navigation hierarchy step: " + buildPath);
            }
        }

        // Set the final destination path
        state.setCurrentPath(buildPath);

        // Load files for the final destination
        loadFiles();
    }
}
