package de.schliweb.sambalite.ui;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.util.LogUtils;
import de.schliweb.sambalite.util.PreferencesManager;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

/**
 * Shared state object for the file browser ViewModels.
 * This class holds the state that needs to be shared between the specialized ViewModels:
 * - FileListViewModel
 * - FileOperationsViewModel
 * - SearchViewModel
 */
public class FileBrowserState {
    // Preferences manager for persisting UI preferences
    private final PreferencesManager preferencesManager;
    // Navigation state
    private final Stack<String> pathStack = new Stack<>();
    private final MutableLiveData<String> currentPathLiveData = new MutableLiveData<>("");
    // File list state
    private final MutableLiveData<List<SmbFileItem>> files = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(false);
    private final MutableLiveData<String> errorMessage = new MutableLiveData<>();
    // Sorting state
    private final MutableLiveData<FileSortOption> sortOption;
    private final MutableLiveData<Boolean> directoriesFirstLiveData;
    // Search state
    private final MutableLiveData<List<SmbFileItem>> searchResults = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Boolean> isSearching = new MutableLiveData<>(false);
    // Connection state
    @Getter
    private SmbConnection connection;
    private String currentPath = "";
    private FileSortOption currentSortOption;
    private boolean directoriesFirst;
    private boolean isSearchMode = false;
    private String currentSearchQuery = "";
    // Operation state
    private volatile boolean uploadCancelled = false;
    private volatile boolean downloadCancelled = false;
    /**
     * Creates a new FileBrowserState with the given PreferencesManager.
     *
     * @param preferencesManager The PreferencesManager to use for storing preferences
     */
    public FileBrowserState(PreferencesManager preferencesManager) {
        this.preferencesManager = preferencesManager;

        // Load sorting preferences from PreferencesManager
        this.currentSortOption = preferencesManager.getSortOption();
        this.directoriesFirst = preferencesManager.getDirectoriesFirst();

        // Initialize LiveData with loaded preferences
        this.sortOption = new MutableLiveData<>(currentSortOption);
        this.directoriesFirstLiveData = new MutableLiveData<>(directoriesFirst);

        LogUtils.d("FileBrowserState", "Initialized with sort option: " + currentSortOption + ", directoriesFirst: " + directoriesFirst);
    }

    /**
     * Sets the connection to use for browsing files.
     *
     * @param connection The connection to use
     */
    public void setConnection(SmbConnection connection) {
        this.connection = connection;
        pathStack.clear();
        currentPath = "";
        currentPathLiveData.setValue(connection.getShare());
    }

    /**
     * Gets the current path.
     */
    public String getCurrentPathString() {
        return currentPath;
    }

    /**
     * Updates the current path display.
     */
    private void updateCurrentPathDisplay() {
        if (connection == null) return;

        String displayPath = connection.getShare();
        if (currentPath != null && !currentPath.isEmpty()) {
            displayPath += "/" + currentPath;
        }
        currentPathLiveData.postValue(displayPath);
    }

    /**
     * Pushes the current path onto the path stack.
     */
    public void pushPath(String path) {
        pathStack.push(path);
    }

    /**
     * Pops the path stack and returns the previous path.
     */
    public String popPath() {
        if (pathStack.isEmpty()) {
            return "";
        }
        return pathStack.pop();
    }

    /**
     * Checks if there is a parent directory to navigate to.
     */
    public boolean hasParentDirectory() {
        return !pathStack.isEmpty();
    }

    /**
     * Gets the list of files as LiveData.
     */
    public LiveData<List<SmbFileItem>> getFiles() {
        return files;
    }

    /**
     * Sets the list of files.
     */
    public void setFiles(List<SmbFileItem> fileList) {
        files.postValue(fileList);
    }

    /**
     * Gets the loading state as LiveData.
     */
    public LiveData<Boolean> isLoading() {
        return isLoading;
    }

    /**
     * Sets the loading state.
     */
    public void setLoading(boolean loading) {
        isLoading.postValue(loading);
    }

    /**
     * Gets the error message as LiveData.
     */
    public LiveData<String> getErrorMessage() {
        return errorMessage;
    }

    /**
     * Sets the error message.
     */
    public void setErrorMessage(String message) {
        errorMessage.postValue(message);
    }

    /**
     * Gets the current path as LiveData.
     */
    public LiveData<String> getCurrentPath() {
        return currentPathLiveData;
    }

    /**
     * Sets the current path.
     */
    public void setCurrentPath(String path) {
        this.currentPath = path;
        updateCurrentPathDisplay();
    }

    /**
     * Gets the current sort option as LiveData.
     */
    public LiveData<FileSortOption> getSortOption() {
        return sortOption;
    }

    /**
     * Sets the sort option and persists it to preferences.
     */
    public void setSortOption(FileSortOption option) {
        this.currentSortOption = option;
        this.sortOption.setValue(option);

        // Save to preferences
        preferencesManager.saveSortOption(option);
        LogUtils.d("FileBrowserState", "Sort option saved to preferences: " + option);
    }

    /**
     * Gets the current sort option.
     */
    public FileSortOption getCurrentSortOption() {
        return currentSortOption;
    }

    /**
     * Gets the current "directories first" flag as LiveData.
     */
    public LiveData<Boolean> getDirectoriesFirst() {
        return directoriesFirstLiveData;
    }

    /**
     * Gets the current "directories first" flag.
     */
    public boolean isDirectoriesFirst() {
        return directoriesFirst;
    }

    /**
     * Sets the "directories first" flag and persists it to preferences.
     */
    public void setDirectoriesFirst(boolean directoriesFirst) {
        this.directoriesFirst = directoriesFirst;
        this.directoriesFirstLiveData.setValue(directoriesFirst);

        // Save to preferences
        preferencesManager.saveDirectoriesFirst(directoriesFirst);
        LogUtils.d("FileBrowserState", "Directories first saved to preferences: " + directoriesFirst);
    }

    /**
     * Gets the search results as LiveData.
     */
    public LiveData<List<SmbFileItem>> getSearchResults() {
        return searchResults;
    }

    /**
     * Sets the search results.
     */
    public void setSearchResults(List<SmbFileItem> results) {
        searchResults.postValue(results);
    }

    /**
     * Gets the searching state as LiveData.
     */
    public LiveData<Boolean> isSearching() {
        return isSearching;
    }

    /**
     * Sets the searching state.
     */
    public void setSearching(boolean searching) {
        isSearching.postValue(searching);
    }

    /**
     * Checks if the view model is in search mode.
     */
    public boolean isInSearchMode() {
        return isSearchMode;
    }

    /**
     * Sets the search mode.
     */
    public void setSearchMode(boolean searchMode) {
        this.isSearchMode = searchMode;
    }

    /**
     * Gets the current search query.
     */
    public String getCurrentSearchQuery() {
        return currentSearchQuery;
    }

    /**
     * Sets the current search query.
     */
    public void setCurrentSearchQuery(String query) {
        this.currentSearchQuery = query;
    }

    /**
     * Checks if upload is cancelled.
     */
    public boolean isUploadCancelled() {
        return uploadCancelled;
    }

    /**
     * Sets the upload cancelled flag.
     */
    public void setUploadCancelled(boolean cancelled) {
        this.uploadCancelled = cancelled;
    }

    /**
     * Checks if download is cancelled.
     */
    public boolean isDownloadCancelled() {
        return downloadCancelled;
    }

    /**
     * Sets the download cancelled flag.
     */
    public void setDownloadCancelled(boolean cancelled) {
        this.downloadCancelled = cancelled;
    }
}