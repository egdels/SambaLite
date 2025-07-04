package de.schliweb.sambalite.ui;

import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.util.LogUtils;
import lombok.Getter;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * ViewModel for the FileBrowserActivity.
 * Handles the list of files and operations on them.
 */
public class FileBrowserViewModel extends ViewModel {

    private final SmbRepository smbRepository;
    private final Executor executor;
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

    @Inject
    public FileBrowserViewModel(SmbRepository smbRepository) {
        this.smbRepository = smbRepository;
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
                List<SmbFileItem> fileList = smbRepository.listFiles(connection, currentPath);
                LogUtils.d("FileBrowserViewModel", "Loaded " + fileList.size() + " files from: " + path);

                // Sort files according to the current sorting options
                sortFiles(fileList);

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
                smbRepository.downloadFile(connection, file.getPath(), localFile);
                LogUtils.i("FileBrowserViewModel", "File downloaded successfully: " + file.getName());
                isLoading.postValue(false);
                if (callback != null) {
                    callback.onResult(true, "File downloaded successfully");
                }
            } catch (Exception e) {
                LogUtils.e("FileBrowserViewModel", "Download failed: " + e.getMessage());
                isLoading.postValue(false);
                if (callback != null) {
                    callback.onResult(false, "Download failed: " + e.getMessage());
                }
                errorMessage.postValue("Failed to download file: " + e.getMessage());
            }
        });
    }

    /**
     * Downloads a folder from the SMB server.
     *
     * @param folder      The folder to download
     * @param localFolder The local folder to save the downloaded folder to
     * @param callback    The callback to be called when the download is complete
     */
    public void downloadFolder(SmbFileItem folder, java.io.File localFolder, DownloadCallback callback) {
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
                smbRepository.downloadFolder(connection, folder.getPath(), localFolder);
                LogUtils.i("FileBrowserViewModel", "Folder downloaded successfully: " + folder.getName());
                isLoading.postValue(false);
                if (callback != null) {
                    callback.onResult(true, "Folder downloaded successfully");
                }
            } catch (Exception e) {
                LogUtils.e("FileBrowserViewModel", "Download failed: " + e.getMessage());
                isLoading.postValue(false);
                if (callback != null) {
                    callback.onResult(false, "Download failed: " + e.getMessage());
                }
                errorMessage.postValue("Failed to download folder: " + e.getMessage());
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

                    // Call the callback to show the confirmation dialog
                    new Handler(Looper.getMainLooper()).post(() -> {
                        // Use the display filename if provided, otherwise use the local file name
                        String nameToDisplay = displayFileName != null ? displayFileName : localFile.getName();
                        fileExistsCallback.onFileExists(nameToDisplay, confirmAction);
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
                List<SmbFileItem> results = smbRepository.searchFiles(connection, currentPath, currentSearchQuery, searchType, includeSubfolders);

                LogUtils.d("FileBrowserViewModel", "Search completed. Found " + results.size() + " matching items");

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
            // We don't set isSearching to false here because we'll wait for the search operation
            // to complete or return early due to cancellation
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
                // Aktualisiere die Dateiliste nach der Ordnererstellung
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
                smbRepository.uploadFile(connection, localFile, remotePath);
                LogUtils.i("FileBrowserViewModel", "File uploaded successfully: " + localFile.getName());
                isLoading.postValue(false);
                if (callback != null) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        callback.onResult(true, "File uploaded successfully");
                    });
                }
                loadFiles();
            } catch (Exception e) {
                LogUtils.e("FileBrowserViewModel", "Upload failed: " + e.getMessage());
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
    }

    /**
     * Callback interface for file existence confirmation.
     */
    public interface FileExistsCallback {
        void onFileExists(String fileName, Runnable confirmAction);
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

    public interface UploadCallback {
        void onResult(boolean success, String message);
    }
}
