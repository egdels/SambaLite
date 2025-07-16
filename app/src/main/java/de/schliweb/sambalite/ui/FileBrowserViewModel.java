package de.schliweb.sambalite.ui;

import android.os.Handler;
import android.os.Looper;
import androidx.documentfile.provider.DocumentFile;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import de.schliweb.sambalite.cache.IntelligentCacheManager;
import de.schliweb.sambalite.cache.SearchCacheOptimizer;
import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.util.EnhancedFileUtils;
import de.schliweb.sambalite.util.LogUtils;
import lombok.Getter;

import javax.inject.Inject;
import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * ViewModel for the FileBrowserActivity.
 * Handles the list of files and operations on them.
 */
public class FileBrowserViewModel extends ViewModel {

    private final SmbRepository smbRepository;
    private final Executor executor;
    private final android.content.Context context;
    private final Stack<String> pathStack = new Stack<>();
    private final MutableLiveData<List<SmbFileItem>> files = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(false);
    private final MutableLiveData<String> errorMessage = new MutableLiveData<>();
    private final MutableLiveData<String> currentPathLiveData = new MutableLiveData<>("");
    private final MutableLiveData<SortOption> sortOption = new MutableLiveData<>(SortOption.NAME);
    private final MutableLiveData<Boolean> directoriesFirstLiveData = new MutableLiveData<>(true);
    private final MutableLiveData<List<SmbFileItem>> searchResults = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isSearching = new MutableLiveData<>(false);
    @Getter
    private SmbConnection connection;
    private String currentPath = "";
    // Sorting-related fields
    private SortOption currentSortOption = SortOption.NAME;
    private boolean directoriesFirst = true;
    // Search-related fields
    private boolean isSearchMode = false;
    private String currentSearchQuery = "";
    // Upload cancellation field
    private volatile boolean uploadCancelled = false;
    // Download cancellation field
    private volatile boolean downloadCancelled = false;

    @Inject
    public FileBrowserViewModel(SmbRepository smbRepository, android.content.Context context) {
        this.smbRepository = smbRepository;
        this.context = context;
        this.executor = Executors.newSingleThreadExecutor();
        LogUtils.d("FileBrowserViewModel", "FileBrowserViewModel initialized");
    }

    /**
     * Sets the connection to use for browsing files.
     *
     * @param connection The connection to use
     */
    public void setConnection(SmbConnection connection) {
        LogUtils.d("FileBrowserViewModel", "Setting connection: " + connection.getName());
        this.connection = connection;
        pathStack.clear();
        currentPath = "";
        currentPathLiveData.setValue(connection.getShare());
        loadFiles();
    }

    /**
     * Gets the list of files as LiveData.
     */
    public LiveData<List<SmbFileItem>> getFiles() {
        return files;
    }

    /**
     * Gets the loading state as LiveData.
     */
    public LiveData<Boolean> isLoading() {
        return isLoading;
    }

    /**
     * Gets the error message as LiveData.
     */
    public LiveData<String> getErrorMessage() {
        return errorMessage;
    }

    /**
     * Gets the current path as LiveData.
     */
    public LiveData<String> getCurrentPath() {
        return currentPathLiveData;
    }

    /**
     * Cancels any ongoing download operation.
     */
    public void cancelDownload() {
        LogUtils.d("FileBrowserViewModel", "Download cancellation requested from UI");
        downloadCancelled = true;
        // Also cancel at the repository level to stop network transfer
        smbRepository.cancelDownload();
    }

    /**
     * Cancels any ongoing upload operation.
     */
    public void cancelUpload() {
        LogUtils.d("FileBrowserViewModel", "Upload cancellation requested from UI");
        uploadCancelled = true;
        // Also cancel at the repository level to stop network transfer
        smbRepository.cancelUpload();
    }

    /**
     * Gets the search results as LiveData.
     */
    public LiveData<List<SmbFileItem>> getSearchResults() {
        return searchResults;
    }

    /**
     * Gets the searching state as LiveData.
     */
    public LiveData<Boolean> isSearching() {
        return isSearching;
    }

    /**
     * Checks if the view model is in search mode.
     */
    public boolean isInSearchMode() {
        return isSearchMode;
    }

    /**
     * Gets the current sort option as LiveData.
     */
    public LiveData<SortOption> getSortOption() {
        return sortOption;
    }

    /**
     * Sets the sort option.
     *
     * @param option The sort option to set
     */
    public void setSortOption(SortOption option) {
        this.currentSortOption = option;
        this.sortOption.setValue(option);

        // Invalidate cache when sorting changes to ensure fresh loading with new sort order
        if (connection != null) {
            IntelligentCacheManager.getInstance().invalidateConnection(connection);
        }

        // Reload files with new sorting
        if (isInSearchMode()) {
            // Re-sort search results
            List<SmbFileItem> results = searchResults.getValue();
            if (results != null) {
                sortFiles(results);
                searchResults.setValue(results);
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
        return directoriesFirstLiveData;
    }

    /**
     * Sets the "directories first" flag.
     *
     * @param directoriesFirst Whether to show directories first
     */
    public void setDirectoriesFirst(boolean directoriesFirst) {
        this.directoriesFirst = directoriesFirst;
        this.directoriesFirstLiveData.setValue(directoriesFirst);

        // Invalidate cache when sorting changes to ensure fresh loading with new sort order
        if (connection != null) {
            IntelligentCacheManager.getInstance().invalidateConnection(connection);
        }

        // Reload files with new sorting
        if (isInSearchMode()) {
            // Re-sort search results
            List<SmbFileItem> results = searchResults.getValue();
            if (results != null) {
                sortFiles(results);
                searchResults.setValue(results);
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
    private void sortFiles(List<SmbFileItem> fileList) {
        LogUtils.d("FileBrowserViewModel", "Sorting files with option: " + currentSortOption + ", directoriesFirst: " + directoriesFirst);

        Collections.sort(fileList, (file1, file2) -> {
            // If directoriesFirst is true, directories come before files
            if (directoriesFirst) {
                if (file1.isDirectory() && !file2.isDirectory()) {
                    return -1;
                }
                if (!file1.isDirectory() && file2.isDirectory()) {
                    return 1;
                }
            }

            // Both are directories or both are files, sort according to the current sort option
            if (currentSortOption == SortOption.NAME) {
                return file1.getName().compareToIgnoreCase(file2.getName());
            } else if (currentSortOption == SortOption.DATE) {
                // If lastModified is null, treat it as oldest (comes last)
                if (file1.getLastModified() == null) {
                    return file2.getLastModified() == null ? 0 : 1;
                }
                if (file2.getLastModified() == null) {
                    return -1;
                }
                // Sort by date, newest first
                return file2.getLastModified().compareTo(file1.getLastModified());
            } else if (currentSortOption == SortOption.SIZE) {
                // Directories have size 0, so if directoriesFirst is false, we need to handle this case
                if (!directoriesFirst) {
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
        if (connection == null) {
            LogUtils.w("FileBrowserViewModel", "Cannot load files: connection is null");
            return;
        }

        String path = currentPath.isEmpty() ? "root" : currentPath;
        LogUtils.d("FileBrowserViewModel", "Loading files from: " + path);
        isLoading.postValue(true);
        executor.execute(() -> {
            try {
                // Check if we can use cache for loading
                List<SmbFileItem> cachedFiles = IntelligentCacheManager.getInstance().getCachedFileList(connection, currentPath);
                if (cachedFiles != null) {
                    LogUtils.d("FileBrowserViewModel", "Loaded " + cachedFiles.size() + " files from cache: " + currentPath);
                    // Sort cached files according to current sorting options
                    sortFiles(cachedFiles);
                    files.postValue(cachedFiles);
                    isLoading.postValue(false);
                    currentPathLiveData.postValue(path);
                    return;
                }

                List<SmbFileItem> fileList = smbRepository.listFiles(connection, currentPath);
                LogUtils.d("FileBrowserViewModel", "Loaded " + fileList.size() + " files from: " + path);

                // Sort files according to the current sorting options
                sortFiles(fileList);

                // Cache the loaded file list
                IntelligentCacheManager.getInstance().cacheFileList(connection, currentPath, fileList);

                // Preload common search patterns in the background for better search performance
                IntelligentCacheManager.getInstance().preloadCommonSearches(connection, currentPath);

                files.postValue(fileList);
                isLoading.postValue(false);
                currentPathLiveData.postValue(path);
            } catch (Exception e) {
                LogUtils.e("FileBrowserViewModel", "Failed to load files: " + e.getMessage());
                files.postValue(new ArrayList<>());
                isLoading.postValue(false);
                errorMessage.postValue("Failed to load files: " + e.getMessage());
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
            LogUtils.w("FileBrowserViewModel", "Cannot navigate to non-directory: " + directory.getName());
            return;
        }

        LogUtils.d("FileBrowserViewModel", "Navigating to directory: " + directory.getName());
        pathStack.push(currentPath);
        currentPath = directory.getPath();
        updateCurrentPathDisplay();
        loadFiles();
    }

    /**
     * Navigates to the parent directory.
     *
     * @return true if navigation was successful, false if already at the root
     */
    public boolean navigateUp() {
        if (pathStack.isEmpty()) {
            LogUtils.w("FileBrowserViewModel", "Cannot navigate up: already at root");
            return false;
        }

        LogUtils.d("FileBrowserViewModel", "Navigating up from: " + currentPath);
        currentPath = pathStack.pop();
        updateCurrentPathDisplay();
        loadFiles();
        return true;
    }

    /**
     * Updates the current path display.
     */
    private void updateCurrentPathDisplay() {
        String displayPath = connection.getShare();
        if (currentPath != null && !currentPath.isEmpty()) {
            displayPath += "/" + currentPath;
        }
        currentPathLiveData.postValue(displayPath);
    }

    /**
     * Checks if there is a parent directory to navigate to.
     *
     * @return true if there is a parent directory, false otherwise
     */
    public boolean hasParentDirectory() {
        return !pathStack.isEmpty();
    }

    /**
     * Downloads a file from the SMB server.
     *
     * @param file      The file to download
     * @param localFile The local file to save the downloaded file to
     * @param callback  The callback to be called when the download is complete
     */
    public void downloadFile(SmbFileItem file, java.io.File localFile, DownloadCallback callback) {
        downloadFile(file, localFile, callback, null);
    }

    /**
     * Downloads a file from the SMB server with progress tracking.
     *
     * @param file             The file to download
     * @param localFile        The local file to save the downloaded file to
     * @param callback         The callback to be called when the download is complete
     * @param progressCallback The callback for progress updates during download
     */
    public void downloadFile(SmbFileItem file, java.io.File localFile, DownloadCallback callback, ProgressCallback progressCallback) {
        if (connection == null || file == null || !file.isFile()) {
            LogUtils.w("FileBrowserViewModel", "Cannot download: invalid file or connection");
            if (callback != null) {
                callback.onResult(false, "Invalid file or connection");
            }
            return;
        }

        LogUtils.d("FileBrowserViewModel", "Downloading file: " + file.getName() + " to " + localFile.getAbsolutePath());
        isLoading.setValue(true);
        executor.execute(() -> {
            try {
                // Reset download cancellation flag at the start of download
                downloadCancelled = false;

                // Check for cancellation before starting
                if (downloadCancelled) {
                    LogUtils.d("FileBrowserViewModel", "Download cancelled before starting");
                    handleDownloadCancellation(localFile, "file download pre-start cancellation", callback, "Download cancelled by user");
                    return;
                }

                // Use progress-aware download if callback is provided
                if (progressCallback != null) {
                    LogUtils.d("FileBrowserViewModel", "Using progress-aware file download");
                    smbRepository.downloadFileWithProgress(connection, file.getPath(), localFile, new BackgroundSmbManager.ProgressCallback() {
                        @Override
                        public void updateProgress(String progressInfo) {
                            progressCallback.updateProgress(progressInfo);

                            // Also send progress to DownloadCallback if available
                            if (callback != null) {
                                // Parse progress percentage from string format like "Download: 45% (...)"
                                int percentage = parseProgressPercentage(progressInfo);
                                callback.onProgress(progressInfo, percentage);
                            }
                        }

                        @Override
                        public void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
                            progressCallback.updateBytesProgress(currentBytes, totalBytes, fileName);

                            // Also send progress to DownloadCallback if available
                            if (callback != null) {
                                int percentage = totalBytes > 0 ? (int) ((currentBytes * 100) / totalBytes) : 0;
                                String status = "Download: " + percentage + "% (" + EnhancedFileUtils.formatFileSize(currentBytes) + " / " + EnhancedFileUtils.formatFileSize(totalBytes) + ")";
                                callback.onProgress(status, percentage);
                            }
                        }
                    });
                } else {
                    LogUtils.d("FileBrowserViewModel", "Using standard file download (no progress)");
                    smbRepository.downloadFile(connection, file.getPath(), localFile);
                }

                LogUtils.i("FileBrowserViewModel", "File downloaded successfully: " + file.getName());
                isLoading.postValue(false);
                if (callback != null) {
                    callback.onResult(true, "File downloaded successfully");
                }
            } catch (Exception e) {
                LogUtils.e("FileBrowserViewModel", "Download failed: " + e.getMessage());

                isLoading.postValue(false);

                // Check if this was a user cancellation
                if (downloadCancelled || (e.getMessage() != null && e.getMessage().contains("cancelled by user"))) {
                    LogUtils.i("FileBrowserViewModel", "Download was cancelled by user");
                    handleDownloadCancellation(localFile, "file download user cancellation", callback, "Download cancelled by user");
                } else {
                    // Regular download failure - cleanup and report error
                    cleanupDownloadFiles(localFile, "file download failure");

                    if (callback != null) {
                        callback.onResult(false, "Download failed: " + e.getMessage());
                    }
                    errorMessage.postValue("Failed to download file: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Downloads a folder from the SMB server with progress tracking.
     *
     * @param folder           The folder to download
     * @param localFolder      The local folder to save the downloaded folder to
     * @param callback         The callback to be called when the download is complete
     * @param progressCallback The callback for progress updates during download
     */
    public void downloadFolder(SmbFileItem folder, java.io.File localFolder, DownloadCallback callback, ProgressCallback progressCallback) {
        if (connection == null || folder == null || !folder.isDirectory()) {
            LogUtils.w("FileBrowserViewModel", "Cannot download: invalid folder or connection");
            if (callback != null) {
                callback.onResult(false, "Invalid folder or connection");
            }
            return;
        }

        LogUtils.d("FileBrowserViewModel", "Downloading folder: " + folder.getName() + " to " + localFolder.getAbsolutePath());
        isLoading.setValue(true);
        executor.execute(() -> {
            try {
                // Reset download cancellation flag at the start of download
                downloadCancelled = false;

                // Check for cancellation before starting
                if (downloadCancelled) {
                    LogUtils.d("FileBrowserViewModel", "Folder download cancelled before starting");
                    handleDownloadCancellation(localFolder, "folder download pre-start cancellation", callback, "Download cancelled by user");
                    return;
                }

                // Use Background Manager for Multi-File Progress if available
                if (progressCallback != null) {
                    LogUtils.d("FileBrowserViewModel", "Using progress-aware folder download");
                    smbRepository.downloadFolderWithProgress(connection, folder.getPath(), localFolder, new BackgroundSmbManager.MultiFileProgressCallback() {
                        @Override
                        public void updateFileProgress(int currentFile, String currentFileName) {
                            // currentFile is the current index, but we don't have totalFiles here
                            // Also nehmen wir an dass -1 "unbekannt" bedeutet
                            progressCallback.updateFileProgress(currentFile, -1, currentFileName);

                            // Also send progress to DownloadCallback if available
                            if (callback != null) {
                                String status = "Downloading: " + currentFileName + " (" + currentFile + " files processed)";
                                int percentage = 0; // For multi-file operations without total count, we can't calculate accurate percentage
                                callback.onProgress(status, percentage);
                            }
                        }

                        @Override
                        public void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
                            progressCallback.updateBytesProgress(currentBytes, totalBytes, fileName);

                            // Also send progress to DownloadCallback if available
                            if (callback != null) {
                                int percentage = totalBytes > 0 ? (int) ((currentBytes * 100) / totalBytes) : 0;
                                String status = "Download: " + percentage + "% (" + EnhancedFileUtils.formatFileSize(currentBytes) + " / " + EnhancedFileUtils.formatFileSize(totalBytes) + ")";
                                callback.onProgress(status, percentage);
                            }
                        }

                        @Override
                        public void updateProgress(String progressInfo) {
                            progressCallback.updateProgress(progressInfo);

                            // Also send progress to DownloadCallback if available
                            if (callback != null) {
                                int percentage = parseProgressPercentage(progressInfo);
                                callback.onProgress(progressInfo, percentage);
                            }
                        }
                    });
                } else {
                    LogUtils.d("FileBrowserViewModel", "Using standard folder download (no progress)");
                    smbRepository.downloadFolder(connection, folder.getPath(), localFolder);
                }

                LogUtils.i("FileBrowserViewModel", "Folder downloaded successfully: " + folder.getName());
                isLoading.postValue(false);
                if (callback != null) {
                    callback.onResult(true, "Folder downloaded successfully");
                }
            } catch (Exception e) {
                LogUtils.e("FileBrowserViewModel", "Folder download failed: " + e.getMessage());

                isLoading.postValue(false);

                // Check if this was a user cancellation
                if (downloadCancelled || (e.getMessage() != null && e.getMessage().contains("cancelled by user"))) {
                    LogUtils.i("FileBrowserViewModel", "Folder download was cancelled by user");
                    handleDownloadCancellation(localFolder, "folder download user cancellation", callback, "Download cancelled by user");
                } else {
                    // Regular download failure - cleanup and report error
                    cleanupDownloadFiles(localFolder, "folder download failure");

                    if (callback != null) {
                        callback.onResult(false, "Download failed: " + e.getMessage());
                    }
                    errorMessage.postValue("Failed to download folder: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Uploads a file to the SMB server.
     *
     * @param localFile          The local file to upload
     * @param remotePath         The path on the SMB server to upload the file to
     * @param callback           The callback to be called when the upload is complete
     * @param fileExistsCallback The callback to be called if the file already exists
     * @param displayFileName    The actual filename to display in the confirmation dialog (if null, localFile.getName() will be used)
     */
    public void uploadFile(java.io.File localFile, String remotePath, UploadCallback callback, FileExistsCallback fileExistsCallback, String displayFileName) {
        if (connection == null || localFile == null || !localFile.exists()) {
            LogUtils.w("FileBrowserViewModel", "Cannot upload: invalid file or connection");
            if (callback != null) {
                callback.onResult(false, "Invalid file or connection");
            }
            return;
        }

        LogUtils.d("FileBrowserViewModel", "Checking if file exists before uploading: " + remotePath);
        isLoading.setValue(true);
        executor.execute(() -> {
            try {
                // Check if the file already exists
                boolean fileExists = smbRepository.fileExists(connection, remotePath);

                if (fileExists && fileExistsCallback != null) {
                    LogUtils.d("FileBrowserViewModel", "File already exists, asking for confirmation: " + remotePath);
                    isLoading.postValue(false);

                    // Create a runnable that will be executed if the user confirms the overwrite
                    Runnable confirmAction = () -> {
                        LogUtils.d("FileBrowserViewModel", "User confirmed overwrite, uploading file: " + localFile.getName());
                        performUpload(localFile, remotePath, callback);
                    };

                    // Create a runnable that will be executed if the user cancels
                    Runnable cancelAction = () -> {
                        LogUtils.d("FileBrowserViewModel", "User cancelled file upload for: " + localFile.getName());
                        // For regular file uploads, we notify the callback about cancellation
                        new Handler(Looper.getMainLooper()).post(() -> callback.onResult(false, "Upload cancelled by user"));
                    };

                    // Call the callback to show the confirmation dialog
                    new Handler(Looper.getMainLooper()).post(() -> {
                        // Use the display filename if provided, otherwise use the local file name
                        String nameToDisplay = displayFileName != null ? displayFileName : localFile.getName();
                        fileExistsCallback.onFileExists(nameToDisplay, confirmAction, cancelAction);
                    });
                } else {
                    // File doesn't exist or no callback provided, proceed with upload
                    LogUtils.d("FileBrowserViewModel", "File doesn't exist or no callback provided, proceeding with upload");
                    performUpload(localFile, remotePath, callback);
                }
            } catch (Exception e) {
                LogUtils.e("FileBrowserViewModel", "Error checking if file exists: " + e.getMessage());
                isLoading.postValue(false);
                if (callback != null) {
                    callback.onResult(false, "Error checking if file exists: " + e.getMessage());
                }
                errorMessage.postValue("Failed to check if file exists: " + e.getMessage());
            }
        });
    }

    /**
     * Searches for files matching the query with specified options.
     *
     * @param query             The search query
     * @param searchType        The type of items to search for (0=all, 1=files only, 2=folders only)
     * @param includeSubfolders Whether to include subfolders in the search
     */
    public void searchFiles(String query, int searchType, boolean includeSubfolders) {
        if (connection == null) {
            LogUtils.w("FileBrowserViewModel", "Cannot search: connection is null");
            return;
        }

        if (query == null || query.trim().isEmpty()) {
            clearSearch();
            return;
        }

        LogUtils.d("FileBrowserViewModel", "Searching for files matching query: '" + query + "', searchType: " + searchType + ", includeSubfolders: " + includeSubfolders);

        currentSearchQuery = query.trim();
        isSearchMode = true;
        isSearching.postValue(true);

        executor.execute(() -> {
            try {
                // Check if we have cached search results first with comprehensive error handling
                List<SmbFileItem> cachedResults = null;
                try {
                    cachedResults = IntelligentCacheManager.getInstance().getCachedSearchResults(connection, currentPath, currentSearchQuery, searchType, includeSubfolders);
                } catch (ClassCastException e) {
                    LogUtils.w("FileBrowserViewModel", "Cache corruption detected for search query '" + currentSearchQuery + "': " + e.getMessage());
                    // Clear corrupted cache for this specific search
                    try {
                        String searchKey = IntelligentCacheManager.getInstance().generateSearchCacheKey(connection, currentPath, currentSearchQuery, searchType, includeSubfolders);
                        IntelligentCacheManager.getInstance().removeEntry(searchKey);
                        LogUtils.d("FileBrowserViewModel", "Cleared corrupted search cache for key: " + searchKey);
                    } catch (Exception cleanupError) {
                        LogUtils.w("FileBrowserViewModel", "Error during cache cleanup: " + cleanupError.getMessage());
                    }
                    cachedResults = null; // Ensure we proceed with fresh search
                } catch (Exception e) {
                    LogUtils.w("FileBrowserViewModel", "Error accessing search cache: " + e.getMessage());
                    cachedResults = null; // Ensure we proceed with fresh search
                }

                if (cachedResults != null) {
                    LogUtils.d("FileBrowserViewModel", "Using cached search results: " + cachedResults.size() + " items");

                    // Record cache hit for optimization
                    try {
                        SearchCacheOptimizer.getInstance().recordSearch(connection, currentSearchQuery, cachedResults.size());
                    } catch (Exception optimizerError) {
                        LogUtils.w("FileBrowserViewModel", "Error recording search in optimizer: " + optimizerError.getMessage());
                    }

                    // Sort cached results according to current sorting options
                    sortFiles(cachedResults);

                    searchResults.postValue(cachedResults);
                    isSearching.postValue(false);
                    return;
                }

                // Perform actual search if no cache found
                LogUtils.d("FileBrowserViewModel", "No cached search results found, performing new search");
                List<SmbFileItem> results = smbRepository.searchFiles(connection, currentPath, currentSearchQuery, searchType, includeSubfolders);

                LogUtils.d("FileBrowserViewModel", "Search completed. Found " + results.size() + " matching items");

                // Record search for optimization learning
                try {
                    SearchCacheOptimizer.getInstance().recordSearch(connection, currentSearchQuery, results.size());
                } catch (Exception optimizerError) {
                    LogUtils.w("FileBrowserViewModel", "Error recording search in optimizer: " + optimizerError.getMessage());
                }

                // Cache the search results for future use (if optimization recommends it)
                try {
                    if (SearchCacheOptimizer.getInstance().shouldCacheQuery(connection, currentSearchQuery)) {
                        long optimalTTL = SearchCacheOptimizer.getInstance().getOptimalCacheTTL(connection, currentSearchQuery);

                        // Use the IntelligentCacheManager with custom TTL and error protection
                        String cacheKey = IntelligentCacheManager.getInstance().generateSearchCacheKey(connection, currentPath, currentSearchQuery, searchType, includeSubfolders);

                        IntelligentCacheManager.getInstance().put(cacheKey, (Serializable) new ArrayList<>(results), optimalTTL);

                        LogUtils.d("FileBrowserViewModel", "Cached search results with TTL: " + optimalTTL + "ms");
                    } else {
                        LogUtils.d("FileBrowserViewModel", "Search results not cached based on optimization strategy");
                    }
                } catch (Exception cacheError) {
                    LogUtils.w("FileBrowserViewModel", "Error caching search results: " + cacheError.getMessage());
                    // Continue without caching - not critical for search functionality
                }

                // Sort results according to the current sorting options
                sortFiles(results);

                searchResults.postValue(results);
                isSearching.postValue(false);
            } catch (Exception e) {
                LogUtils.e("FileBrowserViewModel", "Search failed: " + e.getMessage());
                searchResults.postValue(new ArrayList<>());
                isSearching.postValue(false);
                errorMessage.postValue("Failed to search files: " + e.getMessage());
            }
        });
    }

    /**
     * Cancels any ongoing search operation.
     * This should be called when the user wants to stop a search in progress.
     */
    public void cancelSearch() {
        LogUtils.d("FileBrowserViewModel", "Cancelling search");
        if (isSearching.getValue() != null && isSearching.getValue()) {
            smbRepository.cancelSearch();
            // Force stop the search state and clear search mode
            isSearching.postValue(false);
            clearSearch();
        }
    }

    /**
     * Clears the search results and returns to normal browsing.
     */
    public void clearSearch() {
        LogUtils.d("FileBrowserViewModel", "Clearing search results");
        isSearchMode = false;
        currentSearchQuery = "";
        searchResults.postValue(new ArrayList<>());
        // Reload the current directory files
        loadFiles();
    }

    /**
     * Creates a new folder in the current directory.
     *
     * @param folderName The name of the folder to create
     * @param callback   The callback to be called when the folder creation is complete
     */
    public void createFolder(String folderName, CreateFolderCallback callback) {
        if (connection == null || folderName == null || folderName.isEmpty()) {
            LogUtils.w("FileBrowserViewModel", "Cannot create folder: invalid folder name or connection");
            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    callback.onResult(false, "Invalid folder name or connection");
                });
            }
            return;
        }

        LogUtils.d("FileBrowserViewModel", "Creating folder: " + folderName + " in path: " + currentPath);
        isLoading.postValue(true);
        executor.execute(() -> {
            try {
                smbRepository.createDirectory(connection, currentPath, folderName);
                LogUtils.i("FileBrowserViewModel", "Folder created successfully: " + folderName);
                isLoading.postValue(false);
                if (callback != null) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        callback.onResult(true, "Folder created successfully");
                    });
                }
                // Invalidate caches since directory structure changed
                IntelligentCacheManager.getInstance().invalidateSearchCache(connection, currentPath);
                // CRITICAL: Also invalidate the file list cache for current path so loadFiles() 
                // will fetch fresh data from server instead of showing stale cached data
                // Use synchronous invalidation to prevent race conditions
                String cachePattern = "conn_" + connection.getId() + "_path_" + currentPath.hashCode();
                IntelligentCacheManager.getInstance().invalidateSync(cachePattern);
                // Update file list after folder creation
                loadFiles();
            } catch (Exception e) {
                LogUtils.e("FileBrowserViewModel", "Folder creation failed: " + e.getMessage());
                isLoading.postValue(false);
                if (callback != null) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        callback.onResult(false, "Folder creation failed: " + e.getMessage());
                    });
                }
                errorMessage.postValue("Failed to create folder: " + e.getMessage());
            }
        });
    }

    /**
     * Deletes a file or directory.
     *
     * @param file     The file or directory to delete
     * @param callback The callback to be called when the deletion is complete
     */
    public void deleteFile(SmbFileItem file, DeleteFileCallback callback) {
        if (connection == null || file == null) {
            LogUtils.w("FileBrowserViewModel", "Cannot delete: invalid file or connection");
            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    callback.onResult(false, "Invalid file or connection");
                });
            }
            return;
        }

        LogUtils.d("FileBrowserViewModel", "Deleting file: " + file.getName() + " at path: " + file.getPath());
        isLoading.postValue(true);
        executor.execute(() -> {
            try {
                smbRepository.deleteFile(connection, file.getPath());
                LogUtils.i("FileBrowserViewModel", "File deleted successfully: " + file.getName());
                isLoading.postValue(false);
                if (callback != null) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        callback.onResult(true, "File deleted successfully");
                    });
                }
                // Cache invalidation for file operations that change directory contents
                // Use synchronous invalidation to prevent race conditions with loadFiles()
                String cachePattern = "conn_" + connection.getId() + "_path_" + currentPath.hashCode();
                IntelligentCacheManager.getInstance().invalidateSync(cachePattern);

                // Also invalidate search cache for this path since file structure changed
                IntelligentCacheManager.getInstance().invalidateSearchCache(connection, currentPath);

                // Refresh the file list after deletion
                loadFiles();
            } catch (Exception e) {
                LogUtils.e("FileBrowserViewModel", "File deletion failed: " + e.getMessage());
                isLoading.postValue(false);
                if (callback != null) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        callback.onResult(false, "File deletion failed: " + e.getMessage());
                    });
                }
                errorMessage.postValue("Failed to delete file: " + e.getMessage());
            }
        });
    }

    /**
     * Renames a file or directory.
     *
     * @param file     The file or directory to rename
     * @param newName  The new name for the file or directory
     * @param callback The callback to be called when the rename is complete
     */
    public void renameFile(SmbFileItem file, String newName, RenameFileCallback callback) {
        if (connection == null || file == null || newName == null || newName.isEmpty()) {
            LogUtils.w("FileBrowserViewModel", "Cannot rename: invalid file, name, or connection");
            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    callback.onResult(false, "Invalid file, name, or connection");
                });
            }
            return;
        }

        LogUtils.d("FileBrowserViewModel", "Renaming file: " + file.getName() + " to " + newName);
        isLoading.postValue(true);
        executor.execute(() -> {
            try {
                smbRepository.renameFile(connection, file.getPath(), newName);
                LogUtils.i("FileBrowserViewModel", "File renamed successfully: " + file.getName() + " to " + newName);
                isLoading.postValue(false);
                if (callback != null) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        callback.onResult(true, "File renamed successfully");
                    });
                }
                // Invalidate caches since file names changed
                IntelligentCacheManager.getInstance().invalidateSearchCache(connection, currentPath);
                // CRITICAL: Also invalidate the file list cache for current path so loadFiles() 
                // will fetch fresh data from server instead of showing stale cached data
                // Use synchronous invalidation to prevent race conditions
                String cachePattern = "conn_" + connection.getId() + "_path_" + currentPath.hashCode();
                IntelligentCacheManager.getInstance().invalidateSync(cachePattern);

                // ADDITIONAL: If renaming a directory, also invalidate cache for the directory itself
                // since its path has changed and any cached content inside it is now invalid
                if (file.isDirectory()) {
                    LogUtils.d("FileBrowserViewModel", "Renaming directory, invalidating directory cache: " + file.getPath());
                    String dirCachePattern = "conn_" + connection.getId() + "_path_" + file.getPath().hashCode();
                    IntelligentCacheManager.getInstance().invalidateSync(dirCachePattern);
                    // Also invalidate search cache for the directory itself
                    IntelligentCacheManager.getInstance().invalidateSearchCache(connection, file.getPath());
                }

                // Refresh the file list after renaming
                loadFiles();
            } catch (Exception e) {
                LogUtils.e("FileBrowserViewModel", "File rename failed: " + e.getMessage());
                isLoading.postValue(false);
                if (callback != null) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        callback.onResult(false, "File rename failed: " + e.getMessage());
                    });
                }
                errorMessage.postValue("Failed to rename file: " + e.getMessage());
            }
        });
    }

    void performUpload(java.io.File localFile, String remotePath, UploadCallback callback) {
        // Set loading indicator to true before starting the upload
        isLoading.postValue(true);
        executor.execute(() -> {
            try {
                // Create progress callback to forward to upload callback
                BackgroundSmbManager.ProgressCallback progressCallback = new BackgroundSmbManager.ProgressCallback() {
                    @Override
                    public void updateProgress(String progressInfo) {
                        // Parse progress info if it contains percentage
                        int percentage = 0;
                        if (progressInfo.contains("%")) {
                            try {
                                String percentStr = progressInfo.substring(progressInfo.indexOf("Upload: ") + 8);
                                percentStr = percentStr.substring(0, percentStr.indexOf("%"));
                                percentage = Integer.parseInt(percentStr);
                            } catch (Exception e) {
                                // Fallback to 0 if parsing fails
                                percentage = 0;
                            }
                        }

                        final int finalPercentage = percentage;
                        new Handler(Looper.getMainLooper()).post(() -> {
                            if (callback != null) {
                                callback.onProgress(progressInfo, finalPercentage);
                            }
                        });
                    }
                };

                // Use progress-enabled upload method
                smbRepository.uploadFileWithProgress(connection, localFile, remotePath, progressCallback);
                LogUtils.i("FileBrowserViewModel", "File uploaded successfully: " + localFile.getName());
                isLoading.postValue(false);
                if (callback != null) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        callback.onResult(true, "File uploaded successfully");
                    });
                }
                // Invalidate search cache since directory contents changed
                IntelligentCacheManager.getInstance().invalidateSearchCache(connection, currentPath);
                // CRITICAL: Also invalidate the file list cache for current path so loadFiles() 
                // will fetch fresh data from server instead of showing stale cached data
                // Use synchronous invalidation to prevent race conditions
                String cachePattern = "conn_" + connection.getId() + "_path_" + currentPath.hashCode();
                IntelligentCacheManager.getInstance().invalidateSync(cachePattern);
                loadFiles();
            } catch (Exception e) {
                LogUtils.e("FileBrowserViewModel", "Upload failed: " + e.getMessage());

                // Cache cleanup for failed upload operations to prevent cache bloat
                try {
                    LogUtils.d("FileBrowserViewModel", "Performing cache cleanup after upload failure");
                    IntelligentCacheManager.getInstance().cleanupAfterCancelledOperation(connection, currentPath);
                } catch (Exception cacheError) {
                    LogUtils.w("FileBrowserViewModel", "Could not perform cache cleanup after upload failure: " + cacheError.getMessage());
                }

                isLoading.postValue(false);
                if (callback != null) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        callback.onResult(false, "Upload failed: " + e.getMessage());
                    });
                }
                errorMessage.postValue("Failed to upload file: " + e.getMessage());
            }
        });
    }

    /**
     * Centralized cleanup for local temp files with detailed logging.
     * This handles cleanup of any local temporary files created during operations.
     */
    private void cleanupLocalTempFile(java.io.File tempFile, String operationContext) {
        if (tempFile != null && tempFile.exists()) {
            long tempFileSize = tempFile.length();
            boolean deleted = tempFile.delete();
            LogUtils.d("FileBrowserViewModel", "Cleaned up local temp file after " + operationContext + ": " + tempFile.getAbsolutePath() + " (deleted: " + deleted + ", size was: " + tempFileSize + " bytes)");

            if (!deleted) {
                LogUtils.w("FileBrowserViewModel", "Warning: Could not delete local temp file: " + tempFile.getAbsolutePath());
                // Try to mark for deletion on exit as fallback
                try {
                    tempFile.deleteOnExit();
                    LogUtils.d("FileBrowserViewModel", "Marked temp file for deletion on exit: " + tempFile.getAbsolutePath());
                } catch (Exception e) {
                    LogUtils.w("FileBrowserViewModel", "Could not mark temp file for deletion on exit: " + e.getMessage());
                }
            }
        } else if (tempFile != null) {
            LogUtils.d("FileBrowserViewModel", "Local temp file already cleaned up or doesn't exist: " + tempFile.getAbsolutePath());
        }
    }

    /**
     * Centralized cache invalidation and UI refresh for upload operations.
     * This ensures the UI reflects the current state after any upload-related operation.
     */
    private void invalidateCacheAndRefreshUI() {
        // CRITICAL: Invalidate cache and refresh file list to update display
        String cachePattern = "conn_" + connection.getId() + "_path_" + currentPath.hashCode();
        IntelligentCacheManager.getInstance().invalidateSync(cachePattern);
        IntelligentCacheManager.getInstance().invalidateSearchCache(connection, currentPath);

        // Refresh the file list on main thread
        new Handler(Looper.getMainLooper()).post(() -> loadFiles());
    }

    /**
     * Centralized method for handling upload cancellation with cleanup and UI refresh.
     * This method combines cleanup, cache invalidation, and user notification.
     */
    private void handleUploadCancellation(java.io.File localTempFile, String remoteZipName, String operationContext, UploadCallback callback, String userMessage) {
        // Use centralized cleanup
        cleanupUploadFiles(localTempFile, remoteZipName, operationContext);

        // Invalidate cache and refresh UI
        invalidateCacheAndRefreshUI();

        // Notify callback about cancellation on main thread
        new Handler(Looper.getMainLooper()).post(() -> callback.onResult(false, userMessage));
    }

    /**
     * Centralized cleanup for .uploading files on server and local temp files.
     * This should be called whenever an upload is cancelled or fails.
     */
    private void cleanupUploadFiles(java.io.File localTempFile, String remoteZipName, String operationContext) {
        // Clean up local temp file using centralized method
        cleanupLocalTempFile(localTempFile, operationContext);

        // Clean up .uploading file on server
        if (remoteZipName != null && connection != null) {
            try {
                String tempRemoteName = remoteZipName + ".uploading";
                String tempRemotePath = currentPath.isEmpty() ? tempRemoteName : currentPath + "/" + tempRemoteName;
                smbRepository.deleteFile(connection, tempRemotePath);
                LogUtils.d("FileBrowserViewModel", "Cleaned up .uploading file on server after " + operationContext + ": " + tempRemotePath);
            } catch (Exception deleteError) {
                LogUtils.w("FileBrowserViewModel", "Could not clean up .uploading file on server after " + operationContext + ": " + deleteError.getMessage());
            }
        }

        // Perform aggressive cache cleanup
        try {
            LogUtils.d("FileBrowserViewModel", "Performing aggressive cache cleanup after " + operationContext);
            IntelligentCacheManager.getInstance().cleanupAfterCancelledOperation(connection, currentPath);
        } catch (Exception cacheError) {
            LogUtils.w("FileBrowserViewModel", "Could not perform cache cleanup after " + operationContext + ": " + cacheError.getMessage());
        }
    }

    /**
     * Centralized cleanup for partial/incomplete download files and local temp files.
     * This should be called whenever a download is cancelled or fails.
     */
    private void cleanupDownloadFiles(java.io.File localFile, String operationContext) {
        // Clean up local download file using centralized method
        cleanupLocalTempFile(localFile, operationContext);

        // Perform aggressive cache cleanup for download cancellation
        try {
            LogUtils.d("FileBrowserViewModel", "Performing aggressive cache cleanup after " + operationContext);
            IntelligentCacheManager.getInstance().cleanupAfterCancelledOperation(connection, currentPath);
        } catch (Exception cacheError) {
            LogUtils.w("FileBrowserViewModel", "Could not perform cache cleanup after " + operationContext + ": " + cacheError.getMessage());
        }
    }

    /**
     * Centralized method for handling download cancellation with cleanup and UI notification.
     * This method combines cleanup, cache invalidation, and user notification.
     */
    private void handleDownloadCancellation(java.io.File localFile, String operationContext, DownloadCallback callback, String userMessage) {
        // Use centralized cleanup
        cleanupDownloadFiles(localFile, operationContext);

        // Notify callback about cancellation on main thread
        new Handler(Looper.getMainLooper()).post(() -> callback.onResult(false, userMessage));
    }


    /**
     * Uploads a folder as ZIP file using DocumentFile URI (for Android SAF).
     *
     * @param localFolderUri     The URI of the local folder to upload (from DocumentFile)
     * @param remoteZipName      The name of the ZIP file on the server
     * @param callback           Callback for result notification
     * @param fileExistsCallback Callback to handle when ZIP file already exists (optional)
     */
    public void uploadFolderAsZipFromUri(android.net.Uri localFolderUri, String remoteZipName, UploadCallback callback, FileExistsCallback fileExistsCallback) {
        LogUtils.d("FileBrowserViewModel", "Starting ZIP upload of folder URI: " + localFolderUri + " as " + remoteZipName);

        executor.execute(() -> {
            java.io.File tempZip = null;
            try {
                // Reset upload cancellation flag at the start of upload
                uploadCancelled = false;

                // Check for cancellation before starting
                if (uploadCancelled) {
                    LogUtils.d("FileBrowserViewModel", "Upload cancelled before ZIP creation");
                    handleUploadCancellation(null, remoteZipName, "early cancellation", callback, "Upload cancelled");
                    return;
                }

                // Report start of ZIP creation
                new Handler(Looper.getMainLooper()).post(() -> callback.onProgress("Creating ZIP archive...", 0));

                // Create temporary ZIP in cache directory
                java.io.File cacheDir = context.getCacheDir();
                tempZip = new java.io.File(cacheDir, "temp_" + System.currentTimeMillis() + ".zip");

                LogUtils.d("FileBrowserViewModel", "Creating ZIP archive: " + tempZip.getAbsolutePath());

                // Create progress callback for ZIP creation (0-100%)
                UploadCallback zipProgressCallback = new UploadCallback() {
                    @Override
                    public void onProgress(String message, int percentage) {
                        // ZIP creation phase: 0-100%
                        new Handler(Looper.getMainLooper()).post(() -> callback.onProgress("Creating ZIP: " + percentage + "%", percentage));
                    }

                    @Override
                    public void onResult(boolean success, String message) {
                        // Not used for progress updates
                    }
                };

                // Create ZIP archive from DocumentFile URI with progress
                createZipFromDocumentFolder(localFolderUri, tempZip, zipProgressCallback);

                // Check for cancellation after ZIP creation
                if (uploadCancelled) {
                    LogUtils.d("FileBrowserViewModel", "Upload cancelled after ZIP creation");
                    handleUploadCancellation(tempZip, remoteZipName, "ZIP creation cancellation", callback, "Upload cancelled");
                    return;
                }

                // Check if ZIP file already exists before uploading
                String finalRemotePath = currentPath.isEmpty() ? remoteZipName : currentPath + "/" + remoteZipName;
                boolean zipExists = smbRepository.fileExists(connection, finalRemotePath);

                if (zipExists) {
                    LogUtils.d("FileBrowserViewModel", "ZIP file already exists: " + finalRemotePath);

                    if (fileExistsCallback != null) {
                        // Clean up temp file temporarily
                        if (tempZip.exists()) {
                            long tempFileSize = tempZip.length();
                            LogUtils.d("FileBrowserViewModel", "Keeping temporary ZIP file for potential overwrite: " + tempZip.getAbsolutePath() + " (size: " + tempFileSize + " bytes)");
                        }

                        // Create a runnable that will be executed if the user confirms the overwrite
                        final java.io.File finalTempZip = tempZip;
                        Runnable confirmAction = () -> {
                            LogUtils.d("FileBrowserViewModel", "User confirmed overwrite for ZIP file: " + remoteZipName);
                            // Delete existing file first, then proceed with normal atomic upload
                            // This must be done in a background thread to avoid NetworkOnMainThreadException
                            executor.execute(() -> {
                                try {
                                    LogUtils.d("FileBrowserViewModel", "Deleting existing ZIP file before upload: " + finalRemotePath);
                                    smbRepository.deleteFile(connection, finalRemotePath);
                                    LogUtils.d("FileBrowserViewModel", "Existing ZIP file deleted successfully");

                                    // Now proceed with normal atomic upload (no special overwrite logic needed)
                                    performZipUpload(finalTempZip, remoteZipName, callback);
                                } catch (Exception deleteError) {
                                    LogUtils.e("FileBrowserViewModel", "Failed to delete existing ZIP file: " + deleteError.getMessage());
                                    // Use centralized cleanup
                                    cleanupUploadFiles(finalTempZip, remoteZipName, "existing file deletion error");
                                    new Handler(Looper.getMainLooper()).post(() -> callback.onResult(false, "Failed to delete existing file: " + deleteError.getMessage()));
                                }
                            });
                        };

                        // Create a runnable that will be executed if the user cancels
                        Runnable cancelAction = () -> {
                            LogUtils.d("FileBrowserViewModel", "User cancelled ZIP upload, cleaning up temporary file");
                            handleUploadCancellation(finalTempZip, remoteZipName, "user cancellation", callback, "Upload cancelled by user");
                        };

                        // Call the callback to show the confirmation dialog
                        new Handler(Looper.getMainLooper()).post(() -> {
                            fileExistsCallback.onFileExists(remoteZipName, confirmAction, cancelAction);
                        });
                        return;
                    } else {
                        // No callback provided, just fail
                        // Use centralized cleanup
                        cleanupUploadFiles(tempZip, remoteZipName, "file exists but no callback provided");

                        // Notify user about existing file
                        new Handler(Looper.getMainLooper()).post(() -> callback.onResult(false, "ZIP file '" + remoteZipName + "' already exists on server"));
                        return;
                    }
                }

                // File doesn't exist, proceed with upload
                performZipUpload(tempZip, remoteZipName, callback);

            } catch (Exception e) {
                LogUtils.e("FileBrowserViewModel", "Error uploading ZIP: " + e.getMessage());

                // Use centralized cleanup
                cleanupUploadFiles(tempZip, remoteZipName, "upload exception");

                // Invalidate cache and refresh UI
                invalidateCacheAndRefreshUI();

                // Check if it's a cancellation and handle accordingly
                String errorMessage = (e.getMessage() != null && e.getMessage().contains("cancelled")) ? "Upload cancelled" : e.getMessage();

                LogUtils.d("FileBrowserViewModel", "Upload operation result: " + errorMessage);
                new Handler(Looper.getMainLooper()).post(() -> callback.onResult(false, errorMessage));
            }
        });
    }

    /**
     * Performs the actual ZIP upload to the SMB server.
     * This method handles the upload process including progress reporting and cleanup.
     */
    private void performZipUpload(java.io.File tempZip, String remoteZipName, UploadCallback callback) {
        try {
            // Report start of upload phase
            new Handler(Looper.getMainLooper()).post(() -> callback.onProgress("Starting upload...", 0));

            LogUtils.d("FileBrowserViewModel", "Starting atomic upload using .uploading extension");

            // Upload ZIP with .uploading extension for atomic operation
            String tempRemoteName = remoteZipName + ".uploading";
            String tempRemotePath = currentPath.isEmpty() ? tempRemoteName : currentPath + "/" + tempRemoteName;

            // Create progress callback for upload tracking (0-100%)
            BackgroundSmbManager.ProgressCallback uploadProgressCallback = new BackgroundSmbManager.ProgressCallback() {
                @Override
                public void updateProgress(String progressInfo) {
                    int percentage = 0;
                    String displayText = "Uploading ZIP file...";

                    if (progressInfo.startsWith("Upload: ") && progressInfo.contains("%")) {
                        try {
                            String percentStr = progressInfo.substring(8);
                            percentStr = percentStr.substring(0, percentStr.indexOf("%"));
                            int uploadPercent = Integer.parseInt(percentStr);
                            percentage = Math.min(uploadPercent, 100);

                            if (progressInfo.contains("(") && progressInfo.contains(")")) {
                                String sizeInfo = progressInfo.substring(progressInfo.indexOf("(") + 1, progressInfo.lastIndexOf(")"));
                                displayText = "Uploading: " + percentage + "% (" + sizeInfo + ")";
                            } else {
                                displayText = "Uploading: " + percentage + "%";
                            }
                        } catch (Exception e) {
                            LogUtils.w("FileBrowserViewModel", "Error parsing upload progress: " + e.getMessage());
                        }
                    }

                    final int finalPercentage = percentage;
                    final String finalDisplayText = displayText;

                    new Handler(Looper.getMainLooper()).post(() -> callback.onProgress(finalDisplayText, finalPercentage));
                }
            };

            smbRepository.uploadFileWithProgress(connection, tempZip, tempRemotePath, uploadProgressCallback);

            // Check for cancellation after upload
            if (uploadCancelled) {
                LogUtils.d("FileBrowserViewModel", "Upload cancelled after file upload");
                // Use centralized cleanup
                cleanupUploadFiles(tempZip, remoteZipName, "upload cancellation");

                throw new Exception("Upload cancelled");
            }

            // Report finalization
            new Handler(Looper.getMainLooper()).post(() -> callback.onProgress("Finalizing upload...", 95));

            // Rename to final name (atomic operation)
            String parentDir = currentPath;
            String tempName = remoteZipName + ".uploading";
            smbRepository.renameFile(connection, parentDir.isEmpty() ? tempName : parentDir + "/" + tempName, remoteZipName);

            // Check for cancellation after completion
            if (uploadCancelled) {
                LogUtils.d("FileBrowserViewModel", "Upload cancelled after completion");

                // Clean up temp file using centralized method
                cleanupLocalTempFile(tempZip, "upload cancellation after completion");

                // Aggressive cache cleanup for cancelled operation
                try {
                    LogUtils.d("FileBrowserViewModel", "Performing aggressive cache cleanup after cancelled upload");
                    IntelligentCacheManager.getInstance().cleanupAfterCancelledOperation(connection, currentPath);
                } catch (Exception cacheError) {
                    LogUtils.w("FileBrowserViewModel", "Could not perform aggressive cache cleanup after cancelled upload: " + cacheError.getMessage());
                }

                throw new Exception("Upload cancelled");
            }

            // Report completion (100%)
            new Handler(Looper.getMainLooper()).post(() -> callback.onProgress("Upload completed!", 100));

            // Cleanup temp file only (upload was successful) using centralized method
            cleanupLocalTempFile(tempZip, "successful upload completion");

            LogUtils.i("FileBrowserViewModel", "ZIP upload completed successfully: " + remoteZipName);

            // Invalidate cache and refresh UI using centralized method
            invalidateCacheAndRefreshUI();

            new Handler(Looper.getMainLooper()).post(() -> callback.onResult(true, "Upload completed"));

        } catch (Exception e) {
            LogUtils.e("FileBrowserViewModel", "Error in performZipUpload: " + e.getMessage());
            throw new RuntimeException(e); // Re-throw to be handled by calling method
        }
    }

    /**
     * Downloads a ZIP file from SMB server and unpacks it to local folder.
     *
     * @param remoteZipName Name of the ZIP file on the server
     * @param targetFolder  Local folder where to unpack the ZIP
     * @param callback      Callback for result notification
     */
    public void downloadZipAndUnpack(String remoteZipName, java.io.File targetFolder, DownloadCallback callback) {
        LogUtils.d("FileBrowserViewModel", "Starting ZIP download and unpack: " + remoteZipName + " to " + targetFolder.getAbsolutePath());

        executor.execute(() -> {
            java.io.File tempZip = null;
            try {
                // Create temporary ZIP in cache directory
                java.io.File cacheDir = context.getCacheDir();
                tempZip = new java.io.File(cacheDir, "download_" + System.currentTimeMillis() + ".zip");

                // Download ZIP file
                String remotePath = currentPath.isEmpty() ? remoteZipName : currentPath + "/" + remoteZipName;
                LogUtils.d("FileBrowserViewModel", "Downloading ZIP from: " + remotePath);

                smbRepository.downloadFile(connection, remotePath, tempZip.getAbsolutePath());

                // Unpack ZIP to target folder
                LogUtils.d("FileBrowserViewModel", "Unpacking ZIP to: " + targetFolder.getAbsolutePath());
                unpackZipToFolder(tempZip, targetFolder);

                LogUtils.i("FileBrowserViewModel", "ZIP download and unpack completed successfully");
                new Handler(Looper.getMainLooper()).post(() -> callback.onResult(true, "Download and unpack completed"));

            } catch (Exception e) {
                LogUtils.e("FileBrowserViewModel", "Error downloading/unpacking ZIP: " + e.getMessage());
                new Handler(Looper.getMainLooper()).post(() -> callback.onResult(false, e.getMessage()));
            } finally {
                // Cleanup temp ZIP file using centralized method
                cleanupLocalTempFile(tempZip, "download ZIP operation completion");
            }
        });
    }

    /**
     * Creates a ZIP archive from a DocumentFile folder (Android SAF) with progress callback.
     */
    private void createZipFromDocumentFolder(android.net.Uri folderUri, java.io.File zipFile, UploadCallback progressCallback) throws IOException {
        LogUtils.d("FileBrowserViewModel", "Creating ZIP from DocumentFile URI: " + folderUri);

        androidx.documentfile.provider.DocumentFile docFolder = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, folderUri);
        if (docFolder == null || !docFolder.exists()) {
            throw new IOException("Source folder does not exist: " + folderUri);
        }

        if (!docFolder.isDirectory()) {
            throw new IOException("Source is not a directory: " + folderUri);
        }

        // Count total files for progress tracking
        int totalFiles = countFilesRecursively(docFolder);
        LogUtils.d("FileBrowserViewModel", "Total files to compress: " + totalFiles);

        // Ensure parent directory for ZIP file exists
        java.io.File parentDir = zipFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(zipFile))) {
            LogUtils.d("FileBrowserViewModel", "ZipOutputStream created successfully");
            String folderName = docFolder.getName() != null ? docFolder.getName() : "folder";

            // Use an AtomicInteger to track processed files across recursive calls
            java.util.concurrent.atomic.AtomicInteger processedFiles = new java.util.concurrent.atomic.AtomicInteger(0);

            addDocumentFolderToZip(docFolder, folderName, zos, totalFiles, processedFiles, progressCallback);
            zos.flush();
        }

        LogUtils.d("FileBrowserViewModel", "ZIP creation completed: " + zipFile.getAbsolutePath() + " (size: " + zipFile.length() + " bytes)");

        // Verify ZIP file was created successfully
        if (!zipFile.exists()) {
            throw new IOException("ZIP file was not created: " + zipFile.getAbsolutePath());
        }

        if (zipFile.length() == 0) {
            LogUtils.w("FileBrowserViewModel", "Warning: ZIP file is empty!");
        }
    }

    /**
     * Counts total files recursively for progress tracking.
     */
    private int countFilesRecursively(androidx.documentfile.provider.DocumentFile folder) {
        int count = 0;

        androidx.documentfile.provider.DocumentFile[] files = folder.listFiles();
        if (files == null || files.length == 0) {
            return 0;
        }

        for (androidx.documentfile.provider.DocumentFile file : files) {
            if (file.isDirectory()) {
                count += countFilesRecursively(file);
            } else {
                count++;
            }
        }

        return count;
    }

    /**
     * Recursively adds DocumentFile folder contents to ZIP (for Android SAF) with progress tracking.
     */
    private void addDocumentFolderToZip(androidx.documentfile.provider.DocumentFile folder, String baseName, java.util.zip.ZipOutputStream zos, int totalFiles, java.util.concurrent.atomic.AtomicInteger processedFiles, UploadCallback progressCallback) throws IOException {
        LogUtils.d("FileBrowserViewModel", "Adding DocumentFile folder to ZIP: " + folder.getUri() + " -> " + baseName);

        // Check for upload cancellation
        if (uploadCancelled) {
            LogUtils.d("FileBrowserViewModel", "Upload cancelled during ZIP creation");
            throw new IOException("Upload cancelled by user");
        }

        androidx.documentfile.provider.DocumentFile[] files = folder.listFiles();
        if (files == null || files.length == 0) {
            LogUtils.w("FileBrowserViewModel", "No files found in DocumentFile folder: " + folder.getUri());
            return;
        }

        LogUtils.d("FileBrowserViewModel", "Found " + files.length + " files/folders in: " + folder.getUri());

        for (androidx.documentfile.provider.DocumentFile file : files) {
            // Check for cancellation before processing each file
            if (uploadCancelled) {
                LogUtils.d("FileBrowserViewModel", "Upload cancelled during file processing");
                throw new IOException("Upload cancelled by user");
            }

            String fileName = file.getName() != null ? file.getName() : "unnamed";
            String entryName = baseName + "/" + fileName;
            LogUtils.d("FileBrowserViewModel", "Processing DocumentFile: " + fileName + " -> " + entryName);

            if (file.isDirectory()) {
                LogUtils.d("FileBrowserViewModel", "Adding directory entry: " + entryName);
                // Add directory entry
                java.util.zip.ZipEntry dirEntry = new java.util.zip.ZipEntry(entryName + "/");
                zos.putNextEntry(dirEntry);
                zos.closeEntry();

                // Recursively add directory contents
                addDocumentFolderToZip(file, entryName, zos, totalFiles, processedFiles, progressCallback);
            } else {
                LogUtils.d("FileBrowserViewModel", "Adding file entry: " + entryName + " (size: " + file.length() + " bytes)");

                // Add file entry
                java.util.zip.ZipEntry fileEntry = new java.util.zip.ZipEntry(entryName);
                fileEntry.setSize(file.length());
                fileEntry.setTime(file.lastModified());
                zos.putNextEntry(fileEntry);

                // Copy file content using ContentResolver
                try (java.io.InputStream inputStream = context.getContentResolver().openInputStream(file.getUri())) {
                    if (inputStream != null) {
                        byte[] buffer = new byte[8192];
                        int length;
                        long totalBytes = 0;
                        while ((length = inputStream.read(buffer)) > 0) {
                            // Check for cancellation during file copying
                            if (uploadCancelled) {
                                LogUtils.d("FileBrowserViewModel", "Upload cancelled during file copying");
                                throw new IOException("Upload cancelled by user");
                            }

                            zos.write(buffer, 0, length);
                            totalBytes += length;
                        }
                        LogUtils.d("FileBrowserViewModel", "Written " + totalBytes + " bytes for file: " + fileName);
                    } else {
                        LogUtils.w("FileBrowserViewModel", "Could not open input stream for file: " + fileName);
                    }
                } catch (Exception e) {
                    LogUtils.e("FileBrowserViewModel", "Error reading file " + fileName + ": " + e.getMessage());
                    // Re-throw IOException to stop the process if it's a cancellation
                    if (e instanceof IOException && e.getMessage() != null && e.getMessage().contains("cancelled")) {
                        throw (IOException) e;
                    }
                    // Continue with other files instead of failing completely for other errors
                }

                zos.closeEntry();

                // Update progress if callback is provided
                if (progressCallback != null && totalFiles > 0) {
                    int currentProcessed = processedFiles.incrementAndGet();
                    int percentage = (int) ((currentProcessed * 100.0) / totalFiles); // 0-100% for ZIP creation
                    final String progressMsg = "Compressing: " + fileName + " (" + currentProcessed + "/" + totalFiles + ")";

                    new Handler(Looper.getMainLooper()).post(() -> progressCallback.onProgress(progressMsg, percentage));
                }
            }
        }

        LogUtils.d("FileBrowserViewModel", "Completed adding DocumentFile folder: " + folder.getUri());
    }

    /**
     * Unpacks a ZIP file to a target folder.
     */
    private void unpackZipToFolder(java.io.File zipFile, java.io.File targetFolder) throws IOException {
        LogUtils.d("FileBrowserViewModel", "Unpacking ZIP to folder: " + targetFolder.getAbsolutePath());

        if (!targetFolder.exists()) {
            targetFolder.mkdirs();
        }

        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.FileInputStream(zipFile))) {
            java.util.zip.ZipEntry entry;

            while ((entry = zis.getNextEntry()) != null) {
                java.io.File entryFile = new java.io.File(targetFolder, entry.getName());

                // Security check: prevent path traversal attacks
                if (!entryFile.getCanonicalPath().startsWith(targetFolder.getCanonicalPath())) {
                    LogUtils.w("FileBrowserViewModel", "Skipping potentially malicious ZIP entry: " + entry.getName());
                    continue;
                }

                if (entry.isDirectory()) {
                    entryFile.mkdirs();
                } else {
                    // Create parent directories if needed
                    entryFile.getParentFile().mkdirs();

                    // Extract file
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(entryFile)) {
                        byte[] buffer = new byte[8192];
                        int length;
                        while ((length = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, length);
                        }
                    }
                }

                zis.closeEntry();
            }
        }

        LogUtils.d("FileBrowserViewModel", "ZIP unpacking completed");
    }

    /**
     * Uploads a folder's contents as individual files with robust error handling and integrity verification.
     *
     * @param localFolderUri     The URI of the local folder to upload
     * @param callback           Callback for result notification with detailed progress
     * @param fileExistsCallback Callback to handle when individual files already exist (optional)
     */
    public void uploadFolderContentsFromUri(android.net.Uri localFolderUri, UploadCallback callback, FileExistsCallback fileExistsCallback) {
        LogUtils.d("FileBrowserViewModel", "Starting folder contents upload from URI: " + localFolderUri);

        executor.execute(() -> {
            List<FileUploadTask> uploadTasks = new ArrayList<>();
            List<String> createdFolders = new ArrayList<>();
            int totalFiles = 0;
            int successfulUploads = 0;
            int skippedFiles = 0;
            int finalTotalFiles = 0; // Declare here so it's accessible in catch block

            try {
                // Reset upload cancellation flag
                uploadCancelled = false;

                // Phase 1: Analyze folder structure and create upload plan
                new Handler(Looper.getMainLooper()).post(() -> callback.onProgress("Analyzing folder structure...", 0));

                DocumentFile sourceFolder = DocumentFile.fromTreeUri(context, localFolderUri);
                if (sourceFolder == null || !sourceFolder.isDirectory()) {
                    throw new Exception("Invalid folder selected");
                }

                // Recursively scan all files and create upload tasks
                uploadTasks = scanFolderForUpload(sourceFolder, "", uploadTasks);
                totalFiles = uploadTasks.size();
                finalTotalFiles = totalFiles; // Assign value to already declared variable
                final int finalTotalFilesForLambda = finalTotalFiles; // Create truly final copy for lambda expressions

                if (totalFiles == 0) {
                    new Handler(Looper.getMainLooper()).post(() -> callback.onResult(true, "Folder is empty - nothing to upload"));
                    return;
                }

                LogUtils.d("FileBrowserViewModel", "Found " + totalFiles + " files to upload");

                // Phase 2: Create folder structure on server
                new Handler(Looper.getMainLooper()).post(() -> callback.onProgress("Creating folder structure...", 5));

                createFolderStructure(uploadTasks, createdFolders);

                // Phase 3: Upload files with progress tracking
                for (int i = 0; i < uploadTasks.size(); i++) {
                    if (uploadCancelled) {
                        LogUtils.d("FileBrowserViewModel", "Upload cancelled by user at file " + (i + 1) + " of " + finalTotalFilesForLambda);
                        throw new Exception("Upload cancelled by user");
                    }

                    FileUploadTask task = uploadTasks.get(i);
                    final int currentFileIndex = i + 1;
                    final int progressPercentage = 10 + (int) ((currentFileIndex * 80.0) / finalTotalFilesForLambda);

                    new Handler(Looper.getMainLooper()).post(() -> callback.onProgress("Uploading " + currentFileIndex + " of " + finalTotalFilesForLambda + " files", progressPercentage));

                    try {
                        uploadSingleFileFromTask(task, fileExistsCallback);
                        successfulUploads++;
                        LogUtils.d("FileBrowserViewModel", "Successfully uploaded file " + currentFileIndex + ": " + task.fileName);
                    } catch (FileSkippedException e) {
                        LogUtils.d("FileBrowserViewModel", "File skipped by user: " + task.fileName);
                        skippedFiles++;
                        // Continue with next file - skipped files are not counted as successful or failed
                    } catch (Exception e) {
                        LogUtils.e("FileBrowserViewModel", "Failed to upload file " + task.fileName + ": " + e.getMessage());
                        // Continue with next file instead of stopping completely
                    }
                }

                // Phase 4: Verify upload integrity
                new Handler(Looper.getMainLooper()).post(() -> callback.onProgress("Verifying upload integrity...", 95));

                int failedFiles = totalFiles - successfulUploads - skippedFiles;

                if (successfulUploads < totalFiles) {
                    StringBuilder message = new StringBuilder();
                    message.append("Upload incomplete: ").append(successfulUploads).append(" of ").append(finalTotalFiles).append(" files uploaded successfully");

                    if (skippedFiles > 0) {
                        message.append(", ").append(skippedFiles).append(" file(s) skipped");
                    }
                    if (failedFiles > 0) {
                        message.append(", ").append(failedFiles).append(" file(s) failed");
                    }

                    LogUtils.w("FileBrowserViewModel", message.toString());

                    // Show detailed warning to user
                    new Handler(Looper.getMainLooper()).post(() -> callback.onResult(false, message + ". Check the server and retry if needed."));
                } else {
                    LogUtils.i("FileBrowserViewModel", "All " + finalTotalFiles + " files uploaded successfully");

                    // Invalidate caches since directory contents changed
                    IntelligentCacheManager.getInstance().invalidateSearchCache(connection, currentPath);
                    String cachePattern = "conn_" + connection.getId() + "_path_" + currentPath.hashCode();
                    IntelligentCacheManager.getInstance().invalidateSync(cachePattern);

                    String successMessage = "All " + finalTotalFilesForLambda + " files uploaded successfully";
                    if (skippedFiles > 0) {
                        successMessage = successfulUploads + " files uploaded successfully, " + skippedFiles + " files skipped";
                    }

                    final String finalSuccessMessage = successMessage;
                    new Handler(Looper.getMainLooper()).post(() -> {
                        callback.onProgress("Upload completed successfully!", 100);
                        callback.onResult(true, finalSuccessMessage);
                        // Refresh the file list to show uploaded content
                        loadFiles();
                    });
                }

            } catch (Exception e) {
                LogUtils.e("FileBrowserViewModel", "Error uploading folder contents: " + e.getMessage());

                // Robust cleanup on failure
                new Handler(Looper.getMainLooper()).post(() -> callback.onProgress("Cleaning up incomplete upload...", 95));

                cleanupIncompleteUpload(createdFolders, uploadTasks, successfulUploads);

                // Cache cleanup for failed operations
                try {
                    LogUtils.d("FileBrowserViewModel", "Performing cache cleanup after folder upload failure");
                    IntelligentCacheManager.getInstance().cleanupAfterCancelledOperation(connection, currentPath);
                } catch (Exception cacheError) {
                    LogUtils.w("FileBrowserViewModel", "Could not perform cache cleanup: " + cacheError.getMessage());
                }

                String errorMessage = "Folder upload failed: " + e.getMessage();
                if (successfulUploads > 0) {
                    errorMessage += " (" + successfulUploads + " of " + finalTotalFiles + " files were uploaded)";
                }
                final String finalErrorMessage = errorMessage; // Create final copy for lambda

                new Handler(Looper.getMainLooper()).post(() -> callback.onResult(false, finalErrorMessage));
            }
        });
    }

    private List<FileUploadTask> scanFolderForUpload(DocumentFile folder, String relativePath, List<FileUploadTask> tasks) {
        DocumentFile[] files = folder.listFiles();
        if (files == null) return tasks;

        for (DocumentFile file : files) {
            if (uploadCancelled) break;

            String fileName = file.getName();
            if (fileName == null) continue;

            String currentRelativePath = relativePath.isEmpty() ? fileName : relativePath + "/" + fileName;
            String serverPath = currentPath.isEmpty() ? currentRelativePath : currentPath + "/" + currentRelativePath;

            if (file.isDirectory()) {
                // Recursively scan subdirectories
                scanFolderForUpload(file, currentRelativePath, tasks);
            } else if (file.isFile()) {
                // Add file to upload tasks
                tasks.add(new FileUploadTask(file, relativePath, fileName, serverPath));
            }
        }

        return tasks;
    }

    private void createFolderStructure(List<FileUploadTask> tasks, List<String> createdFolders) throws Exception {
        Set<String> foldersToCreate = new HashSet<>();

        // Collect all unique folder paths from serverPath (excluding the filename)
        for (FileUploadTask task : tasks) {
            String folderPath = task.serverPath;

            // Remove the filename to get the directory path
            int lastSlashIndex = folderPath.lastIndexOf("/");
            if (lastSlashIndex > 0) {
                String directoryPath = folderPath.substring(0, lastSlashIndex);

                // Add all parent directories that need to be created
                String[] pathParts = directoryPath.split("/");
                String currentPath = "";

                for (String part : pathParts) {
                    currentPath = currentPath.isEmpty() ? part : currentPath + "/" + part;
                    foldersToCreate.add(currentPath);
                }
            }
        }

        // Create folders in correct order (parent directories first)
        List<String> sortedFolders = new ArrayList<>(foldersToCreate);
        sortedFolders.sort((a, b) -> Integer.compare(a.split("/").length, b.split("/").length));

        for (String serverFolderPath : sortedFolders) {
            if (uploadCancelled) break;

            // Extract the folder name and parent path
            String[] pathParts = serverFolderPath.split("/");
            String folderName = pathParts[pathParts.length - 1];
            String parentPath = pathParts.length > 1 ? serverFolderPath.substring(0, serverFolderPath.lastIndexOf("/")) : "";

            try {
                smbRepository.createDirectory(connection, parentPath, folderName);
                createdFolders.add(serverFolderPath);
                LogUtils.d("FileBrowserViewModel", "Created folder: " + serverFolderPath);
            } catch (Exception e) {
                // Folder might already exist, which is fine
                LogUtils.d("FileBrowserViewModel", "Folder creation skipped (may already exist): " + serverFolderPath + " - " + e.getMessage());
            }
        }
    }

    private void uploadSingleFileFromTask(FileUploadTask task, FileExistsCallback fileExistsCallback) throws Exception {
        // Check if file already exists on server
        boolean fileExists = smbRepository.fileExists(connection, task.serverPath);

        if (fileExists && fileExistsCallback != null) {
            // File exists and we have a callback to handle this situation
            LogUtils.d("FileBrowserViewModel", "File already exists on server: " + task.serverPath);

            // Use a CountDownLatch to wait for user decision in synchronous context
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicBoolean shouldOverwrite = new java.util.concurrent.atomic.AtomicBoolean(false);
            final java.util.concurrent.atomic.AtomicBoolean userCancelled = new java.util.concurrent.atomic.AtomicBoolean(false);

            // Create confirm and cancel actions
            Runnable confirmAction = () -> {
                LogUtils.d("FileBrowserViewModel", "User confirmed overwrite for file: " + task.fileName);
                shouldOverwrite.set(true);
                latch.countDown();
            };

            Runnable cancelAction = () -> {
                LogUtils.d("FileBrowserViewModel", "User cancelled upload for file: " + task.fileName);
                userCancelled.set(true);
                latch.countDown();
            };

            // Show confirmation dialog on main thread
            new Handler(Looper.getMainLooper()).post(() -> {
                fileExistsCallback.onFileExists(task.fileName, confirmAction, cancelAction);
            });

            // Wait for user decision (with timeout)
            boolean decisionMade = latch.await(60, java.util.concurrent.TimeUnit.SECONDS);

            if (!decisionMade) {
                throw new Exception("User decision timeout for file: " + task.fileName);
            }

            if (userCancelled.get()) {
                LogUtils.d("FileBrowserViewModel", "User chose to skip file: " + task.fileName);
                throw new FileSkippedException("User chose to skip file: " + task.fileName);
            }

            if (!shouldOverwrite.get()) {
                throw new Exception("Unexpected decision state for file: " + task.fileName);
            }

            // User confirmed overwrite, delete existing file first
            LogUtils.d("FileBrowserViewModel", "Deleting existing file before upload: " + task.serverPath);
            smbRepository.deleteFile(connection, task.serverPath);
        }

        // Proceed with upload (either file didn't exist or user confirmed overwrite)
        // Create temporary file from DocumentFile
        java.io.File tempFile = java.io.File.createTempFile("upload", ".tmp", context.getCacheDir());

        try {
            // Copy DocumentFile content to temporary file
            try (java.io.InputStream input = context.getContentResolver().openInputStream(task.file.getUri()); java.io.FileOutputStream output = new java.io.FileOutputStream(tempFile)) {

                if (input == null) throw new Exception("Cannot read file: " + task.fileName);

                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    if (uploadCancelled) throw new Exception("Upload cancelled");
                    output.write(buffer, 0, bytesRead);
                }
            }

            // Upload the file to server
            smbRepository.uploadFile(connection, tempFile, task.serverPath);

        } finally {
            // Always cleanup temp file using centralized method
            cleanupLocalTempFile(tempFile, "individual file upload completion");
        }
    }

    private void cleanupIncompleteUpload(List<String> createdFolders, List<FileUploadTask> tasks, int successfulUploads) {
        try {
            LogUtils.d("FileBrowserViewModel", "Starting cleanup of incomplete upload");

            // Delete partially uploaded files (best effort)
            for (int i = 0; i < successfulUploads && i < tasks.size(); i++) {
                try {
                    smbRepository.deleteFile(connection, tasks.get(i).serverPath);
                    LogUtils.d("FileBrowserViewModel", "Cleaned up uploaded file: " + tasks.get(i).fileName);
                } catch (Exception e) {
                    LogUtils.w("FileBrowserViewModel", "Could not clean up file " + tasks.get(i).fileName + ": " + e.getMessage());
                }
            }

            // Delete created folders (in reverse order)
            Collections.reverse(createdFolders);
            for (String folderPath : createdFolders) {
                try {
                    smbRepository.deleteFile(connection, folderPath);
                    LogUtils.d("FileBrowserViewModel", "Cleaned up created folder: " + folderPath);
                } catch (Exception e) {
                    LogUtils.w("FileBrowserViewModel", "Could not clean up folder " + folderPath + ": " + e.getMessage());
                }
            }

        } catch (Exception e) {
            LogUtils.e("FileBrowserViewModel", "Error during cleanup: " + e.getMessage());
        }
    }

    /**
     * Parses the progress percentage from progress info strings like "Upload: 45% (...)" or "Download: 67% (...)".
     *
     * @param progressInfo The progress information string
     * @return The parsed percentage, or 0 if parsing fails
     */
    private int parseProgressPercentage(String progressInfo) {
        if (progressInfo == null) return 0;

        try {
            // Look for percentage pattern like "45%" in the string
            int percentIndex = progressInfo.indexOf('%');
            if (percentIndex > 0) {
                // Find the number before the % sign
                int start = percentIndex - 1;
                while (start >= 0 && Character.isDigit(progressInfo.charAt(start))) {
                    start--;
                }
                start++; // Move to the first digit

                if (start < percentIndex) {
                    String percentageStr = progressInfo.substring(start, percentIndex);
                    return Integer.parseInt(percentageStr);
                }
            }
        } catch (Exception e) {
            LogUtils.w("FileBrowserViewModel", "Failed to parse progress percentage from: " + progressInfo);
        }

        return 0;
    }

    /**
     * Enum for sorting options.
     */
    public enum SortOption {
        NAME, DATE, SIZE
    }

    /**
     * Callback interface for download operations.
     */
    public interface DownloadCallback {
        void onResult(boolean success, String message);

        default void onProgress(String status, int percentage) {
            // Default empty implementation for backward compatibility
        }
    }

    /**
     * Callback interface for file existence confirmation.
     */
    public interface FileExistsCallback {
        void onFileExists(String fileName, Runnable confirmAction, Runnable cancelAction);
    }

    /**
     * Callback interface for folder creation operations.
     */
    public interface CreateFolderCallback {
        void onResult(boolean success, String message);
    }

    /**
     * Callback interface for file deletion operations.
     */
    public interface DeleteFileCallback {
        void onResult(boolean success, String message);
    }

    /**
     * Callback interface for file rename operations.
     */
    public interface RenameFileCallback {
        void onResult(boolean success, String message);
    }

    // Callback interfaces for ZIP transfer
    public interface UploadCallback {
        void onResult(boolean success, String message);

        default void onProgress(String status, int percentage) {
            // Default empty implementation for backward compatibility
        }
    }

    /**
     * Callback interface for progress tracking during multi-file operations.
     */
    public interface ProgressCallback {
        void updateFileProgress(int currentFile, int totalFiles, String currentFileName);

        void updateBytesProgress(long currentBytes, long totalBytes, String fileName);

        void updateProgress(String progressInfo);
    }

    private record FileUploadTask(DocumentFile file, String relativePath, String fileName, String serverPath) {
    }

    /**
     * Exception thrown when a user chooses to skip a file during upload.
     */
    private static class FileSkippedException extends Exception {
        public FileSkippedException(String message) {
            super(message);
        }
    }
}
