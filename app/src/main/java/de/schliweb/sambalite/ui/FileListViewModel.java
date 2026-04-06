/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;
import de.schliweb.sambalite.cache.IntelligentCacheManager;
import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.util.LogUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;

/**
 * ViewModel for handling file browsing, navigation, sorting, and filtering. This is part of the
 * refactored FileBrowserViewModel, focusing only on file list management.
 */
@SuppressWarnings("KotlinPropertyAccess") // LiveData getters intentionally return wrapped types
public class FileListViewModel extends ViewModel {

  @NonNull private final SmbRepository smbRepository;
  @NonNull private final ExecutorService executor;
  @NonNull private final FileBrowserState state;
  @NonNull private final BackgroundSmbManager backgroundSmbManager;

  @NonNull
  private final Set<String> pendingValidations =
      Collections.newSetFromMap(new ConcurrentHashMap<>());

  @NonNull
  private final Set<String> pendingPrefetches =
      Collections.newSetFromMap(new ConcurrentHashMap<>());

  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private ScheduledFuture<?> pendingLoadTask;
  private static final long LOAD_DEBOUNCE_MS = 300;

  @Inject
  public FileListViewModel(
      @NonNull SmbRepository smbRepository,
      @NonNull FileBrowserState state,
      @NonNull BackgroundSmbManager backgroundSmbManager) {
    this.smbRepository = smbRepository;
    this.state = state;
    this.backgroundSmbManager = backgroundSmbManager;
    this.executor = Executors.newFixedThreadPool(4);
    LogUtils.d("FileListViewModel", "FileListViewModel initialized");
  }

  /**
   * Sets the connection to use for browsing files.
   *
   * @param connection The connection to use
   */
  public void setConnection(@NonNull SmbConnection connection) {
    LogUtils.d("FileListViewModel", "Setting connection: " + connection.getName());
    state.setConnection(connection);
    loadFiles();
  }

  /** Resets navigation state to the share root. */
  public void resetNavigation() {
    state.resetNavigation();
  }

  @Override
  protected void onCleared() {
    super.onCleared();
    scheduler.shutdownNow();
    executor.shutdownNow();
    LogUtils.d("FileListViewModel", "Executors shutdown");
  }

  public @Nullable SmbConnection getConnection() {
    return state.getConnection();
  }

  /** Gets the list of files as LiveData. */
  public @NonNull LiveData<List<SmbFileItem>> getFiles() {
    return state.getFiles();
  }

  /** Gets the loading state as LiveData. */
  public @NonNull LiveData<Boolean> isLoading() {
    return state.isLoading();
  }

  /** Gets the error message as LiveData. */
  public @NonNull LiveData<String> getErrorMessage() {
    return state.getErrorMessage();
  }

  /** Gets the current path as LiveData (display path including share name). */
  public @NonNull LiveData<String> getCurrentPath() {
    return state.getCurrentPath();
  }

  /**
   * Gets the current internal path (relative to the share root, without share name).
   *
   * @return the current path relative to the share root
   */
  public @NonNull String getCurrentPathInternal() {
    return state.getCurrentPathString();
  }

  /** Gets the current sort option as LiveData. */
  public @NonNull LiveData<FileSortOption> getSortOption() {
    return state.getSortOption();
  }

  /**
   * Sets the sort option.
   *
   * @param option The sort option to set
   */
  public void setSortOption(@NonNull FileSortOption option) {
    state.setSortOption(option);

    // Reload files with new sorting
    if (state.getSearchMode()) {
      // Re-sort search results
      List<SmbFileItem> results = state.getSearchResults().getValue();
      if (results != null) {
        List<SmbFileItem> sorted = new ArrayList<>(results);
        sortFiles(sorted);
        state.setSearchResults(sorted);
      }
    } else {
      // Reload from current state (efficiently uses currently loaded files if possible)
      refreshUIFromCurrentState();
    }
  }

  /** Gets the current "directories first" flag as LiveData. */
  public @NonNull LiveData<Boolean> getDirectoriesFirst() {
    return state.getDirectoriesFirst();
  }

  /**
   * Sets the "directories first" flag.
   *
   * @param directoriesFirst Whether to show directories first
   */
  public void setDirectoriesFirst(boolean directoriesFirst) {
    state.setDirectoriesFirst(directoriesFirst);

    // Reload files with new sorting
    if (state.getSearchMode()) {
      // Re-sort search results
      List<SmbFileItem> results = state.getSearchResults().getValue();
      if (results != null) {
        List<SmbFileItem> sorted = new ArrayList<>(results);
        sortFiles(sorted);
        state.setSearchResults(sorted);
      }
    } else {
      // Reload from current state
      refreshUIFromCurrentState();
    }
  }

  /** Gets the current "show hidden files" flag as LiveData. */
  public @NonNull LiveData<Boolean> getShowHiddenFiles() {
    return state.getShowHiddenFiles();
  }

  /**
   * Sets the "show hidden files" flag.
   *
   * @param showHiddenFiles Whether to show hidden files
   */
  public void setShowHiddenFiles(boolean showHiddenFiles) {
    state.setShowHiddenFiles(showHiddenFiles);

    // Reload files with new setting
    if (state.getSearchMode()) {
      List<SmbFileItem> results = state.getSearchResults().getValue();
      if (results != null) {
        List<SmbFileItem> filtered = filterHiddenFiles(results);
        sortFiles(filtered);
        state.setSearchResults(filtered);
      }
    } else {
      // Reload from current state
      refreshUIFromCurrentState();
    }
  }

  /** Gets the current "show thumbnails" flag as LiveData. */
  public @NonNull LiveData<Boolean> getShowThumbnails() {
    return state.getShowThumbnails();
  }

  /**
   * Sets the "show thumbnails" flag.
   *
   * @param showThumbnails Whether to show file thumbnails
   */
  public void setShowThumbnails(boolean showThumbnails) {
    state.setShowThumbnails(showThumbnails);
  }

  /**
   * Filters hidden files (files starting with a dot) from the list if the setting is enabled.
   *
   * @param fileList The list of files to filter
   * @return The filtered list
   */
  private List<SmbFileItem> filterHiddenFiles(List<SmbFileItem> fileList) {
    if (state.isShowHiddenFiles()) {
      return fileList;
    }
    List<SmbFileItem> filtered = new ArrayList<>();
    for (SmbFileItem file : fileList) {
      if (!file.getName().startsWith(".")) {
        filtered.add(file);
      }
    }
    return filtered;
  }

  /**
   * Sorts a list of files according to the current sorting options.
   *
   * @param fileList The list of files to sort
   */
  public void sortFiles(@NonNull List<SmbFileItem> fileList) {
    LogUtils.d(
        "FileListViewModel",
        "Sorting files with option: "
            + state.getCurrentSortOption()
            + ", directoriesFirst: "
            + state.isDirectoriesFirst());

    Collections.sort(
        fileList,
        (file1, file2) -> {
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

  /** Loads the list of files from the repository. */
  public void loadFiles() {
    loadFiles(true);
  }

  /**
   * Refreshes the current directory by forcing a reload from the server. This method should be
   * called when the user performs a "pull to refresh" gesture or when there's a need to ensure the
   * displayed files match what's on the server. This is particularly useful when files might have
   * been deleted directly on the server.
   */
  public void refreshCurrentDirectory() {
    LogUtils.d(
        "FileListViewModel",
        "Manual refresh requested for current directory: " + state.getCurrentPathString());

    // Invalidate the cache for this path to ensure fresh data is loaded
    if (state.getConnection() != null) {
      IntelligentCacheManager.getInstance()
          .invalidateFileList(state.getConnection(), state.getCurrentPathString());
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
    LogUtils.d(
        "FileListViewModel",
        "Force refreshing files from: " + path + ", showLoadingIndicator: " + showLoadingIndicator);

    // Only set loading state to true if showLoadingIndicator is true
    if (showLoadingIndicator) {
      state.setLoading(true);
    }

    executor.execute(
        () -> {
          try {
            // Skip cache and load directly from server
            List<SmbFileItem> fileList =
                smbRepository.listFiles(state.getConnection(), state.getCurrentPathString());
            LogUtils.d(
                "FileListViewModel", "Loaded " + fileList.size() + " files from server: " + path);

            // Sort and filter files according to the current options
            sortFiles(fileList);
            fileList = filterHiddenFiles(fileList);

            // Update the cache with the fresh data
            IntelligentCacheManager.getInstance()
                .cacheFileList(state.getConnection(), state.getCurrentPathString(), fileList);

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

    // Cancel any pending load task
    if (pendingLoadTask != null && !pendingLoadTask.isDone()) {
      pendingLoadTask.cancel(false);
    }

    // Schedule new load task with debounce
    pendingLoadTask =
        scheduler.schedule(
            () -> performLoadFiles(showLoadingIndicator), LOAD_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
  }

  /**
   * Performs the actual file loading logic.
   *
   * @param showLoadingIndicator Whether to show the loading indicator
   */
  @SuppressWarnings("ThreadPriorityCheck")
  private void performLoadFiles(boolean showLoadingIndicator) {
    backgroundSmbManager.ensureServiceRunning();
    String path = state.getCurrentPathString().isEmpty() ? "root" : state.getCurrentPathString();
    LogUtils.d(
        "FileListViewModel",
        "Loading files from: " + path + ", showLoadingIndicator: " + showLoadingIndicator);

    // Only set loading state to true if showLoadingIndicator is true
    if (showLoadingIndicator) {
      state.setLoading(true);
    }

    executor.execute(
        () -> {
          // Set thread priority to background to avoid blocking the UI
          Thread.currentThread().setPriority(Thread.NORM_PRIORITY - 1);
          try {
            // Check if we can use cache for loading
            List<SmbFileItem> cachedFiles =
                IntelligentCacheManager.getInstance()
                    .getCachedFileList(state.getConnection(), state.getCurrentPathString());
            if (cachedFiles != null) {
              LogUtils.d(
                  "FileListViewModel",
                  "Loaded "
                      + cachedFiles.size()
                      + " files from cache: "
                      + state.getCurrentPathString());
              // Sort and filter cached files according to current options
              sortFiles(cachedFiles);
              List<SmbFileItem> filteredCachedFiles = filterHiddenFiles(cachedFiles);
              state.setFiles(filteredCachedFiles);
              if (showLoadingIndicator) {
                state.setLoading(false);
              }

              // Asynchronously validate the cache
              validateCacheAsync(state.getConnection(), state.getCurrentPathString());
              return;
            }

            List<SmbFileItem> fileList =
                smbRepository.listFiles(state.getConnection(), state.getCurrentPathString());
            LogUtils.d("FileListViewModel", "Loaded " + fileList.size() + " files from: " + path);

            // Sort and filter files according to the current options
            sortFiles(fileList);
            List<SmbFileItem> filteredFileList = filterHiddenFiles(fileList);

            // Cache the loaded file list (the full list, not filtered)
            IntelligentCacheManager.getInstance()
                .cacheFileList(state.getConnection(), state.getCurrentPathString(), fileList);

            // Prefetch subdirectories for better performance
            prefetchSubdirectories(state.getConnection(), fileList);

            // Preload common search patterns in the background for better search performance
            IntelligentCacheManager.getInstance()
                .preloadCommonSearches(state.getConnection(), state.getCurrentPathString());

            state.setFiles(filteredFileList);
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
   * Prefetches the content of subdirectories to improve navigation performance.
   *
   * @param connection The SMB connection
   * @param fileList The list of files containing potential subdirectories
   */
  @SuppressWarnings("ThreadPriorityCheck")
  private void prefetchSubdirectories(
      @NonNull SmbConnection connection, @NonNull List<SmbFileItem> fileList) {
    List<SmbFileItem> directories = new ArrayList<>();
    for (SmbFileItem item : fileList) {
      if (item.isDirectory()) {
        directories.add(item);
      }
    }

    // Limit prefetching to avoid overloading the network
    final int MAX_PREFETCH = 3;
    for (int i = 0; i < Math.min(directories.size(), MAX_PREFETCH); i++) {
      SmbFileItem dir = directories.get(i);
      String cacheKey = connection.getId() + ":" + dir.getPath();

      // Skip if already in cache or validation/prefetch is pending
      if (pendingPrefetches.contains(cacheKey) || pendingValidations.contains(cacheKey)) {
        continue;
      }

      pendingPrefetches.add(cacheKey);
      executor.execute(
          () -> {
            // Set thread priority to background
            Thread.currentThread().setPriority(Thread.NORM_PRIORITY - 1);
            try {
              // Check if already in cache
              if (IntelligentCacheManager.getInstance().getCachedFileList(connection, dir.getPath())
                  != null) {
                return;
              }

              LogUtils.d("FileListViewModel", "Prefetching directory: " + dir.getName());
              List<SmbFileItem> subFiles = smbRepository.listFiles(connection, dir.getPath());
              IntelligentCacheManager.getInstance()
                  .cacheFileList(connection, dir.getPath(), subFiles);
              LogUtils.d(
                  "FileListViewModel",
                  "Prefetched " + subFiles.size() + " files for: " + dir.getName());
            } catch (Exception e) {
              LogUtils.w(
                  "FileListViewModel",
                  "Prefetch failed for " + dir.getName() + ": " + e.getMessage());
            } finally {
              pendingPrefetches.remove(cacheKey);
            }
          });
    }
  }

  /**
   * Asynchronously validates the cache for a specific path. If the directory has changed on the
   * server, it triggers a background refresh.
   *
   * @param connection The SMB connection
   * @param path The path to validate
   */
  @SuppressWarnings("ThreadPriorityCheck")
  private void validateCacheAsync(@NonNull SmbConnection connection, @NonNull String path) {
    String cacheKey = connection.getId() + ":" + path;
    if (pendingValidations.contains(cacheKey)) {
      return;
    }

    pendingValidations.add(cacheKey);
    executor.execute(
        () -> {
          try {
            // Set thread priority to background to avoid blocking the UI
            Thread.currentThread().setPriority(Thread.NORM_PRIORITY - 1);
            LogUtils.d("FileListViewModel", "Validating cache for: " + path);
            SmbFileItem remoteDir = smbRepository.getFileItem(connection, path);
            if (remoteDir == null) return;

            // Retrieve currently cached data to compare with remote
            List<SmbFileItem> cachedFiles =
                IntelligentCacheManager.getInstance().getCachedFileList(connection, path);

            // Heuristic: If we have cached files and the remote directory's last modified time
            // is not newer than our latest cached file's timestamp, we can assume the cache is
            // still valid.
            if (cachedFiles != null && !cachedFiles.isEmpty()) {
              long newestCachedTimestamp = 0;
              for (SmbFileItem item : cachedFiles) {
                if (item.getLastModified() != null) {
                  newestCachedTimestamp =
                      Math.max(newestCachedTimestamp, item.getLastModified().getTime());
                }
              }

              if (remoteDir.getLastModified() != null
                  && remoteDir.getLastModified().getTime() <= newestCachedTimestamp) {
                LogUtils.d("FileListViewModel", "Cache is still up to date for: " + path);
                return;
              }
            }

            // Re-list and re-cache if needed (background refresh)
            List<SmbFileItem> fileList = smbRepository.listFiles(connection, path);

            // Only proceed if the list has actually changed
            if (cachedFiles != null && areFileListEqual(cachedFiles, fileList)) {
              LogUtils.d("FileListViewModel", "Cache content unchanged for: " + path);
              return;
            }

            IntelligentCacheManager.getInstance().cacheFileList(connection, path, fileList);
            LogUtils.d("FileListViewModel", "Cache validated/refreshed for: " + path);

            // If the path is still the current one, update the UI
            if (path.equals(state.getCurrentPathString())) {
              List<SmbFileItem> listToProcess = new ArrayList<>(fileList);
              sortFiles(listToProcess);
              List<SmbFileItem> finalFileList = filterHiddenFiles(listToProcess);
              state.setFiles(finalFileList);
            }
          } catch (Exception e) {
            LogUtils.w("FileListViewModel", "Cache validation failed: " + e.getMessage());
          } finally {
            pendingValidations.remove(cacheKey);
          }
        });
  }

  /**
   * Refreshes the UI using the currently loaded file list from the cache if available. This is much
   * faster than loadFiles() as it avoids potential network calls.
   */
  @SuppressWarnings("ThreadPriorityCheck")
  private void refreshUIFromCurrentState() {
    executor.execute(
        () -> {
          try {
            Thread.currentThread().setPriority(Thread.NORM_PRIORITY - 1);
            List<SmbFileItem> currentFiles =
                IntelligentCacheManager.getInstance()
                    .getCachedFileList(state.getConnection(), state.getCurrentPathString());
            if (currentFiles != null) {
              List<SmbFileItem> listToProcess = new ArrayList<>(currentFiles);
              sortFiles(listToProcess);
              listToProcess = filterHiddenFiles(listToProcess);
              state.setFiles(listToProcess);
            } else {
              // If not in cache for some reason, fall back to normal loading
              loadFiles(false);
            }
          } catch (Exception e) {
            LogUtils.e("FileListViewModel", "Failed to refresh UI from state: " + e.getMessage());
          }
        });
  }

  /** Helper method to compare two file lists for equality. */
  private boolean areFileListEqual(List<SmbFileItem> list1, List<SmbFileItem> list2) {
    if (list1.size() != list2.size()) return false;
    for (int i = 0; i < list1.size(); i++) {
      SmbFileItem item1 = list1.get(i);
      SmbFileItem item2 = list2.get(i);
      if (!item1.getName().equals(item2.getName())
          || item1.isDirectory() != item2.isDirectory()
          || item1.getSize() != item2.getSize()) {
        return false;
      }
      if (item1.getLastModified() != null && item2.getLastModified() != null) {
        if (item1.getLastModified().getTime() != item2.getLastModified().getTime()) return false;
      } else if (item1.getLastModified() == null && item2.getLastModified() == null) {
        // both are null, so they are equal in this regard
      } else {
        // one is null and the other is not
        return false;
      }
    }
    return true;
  }

  /**
   * Navigates to a directory.
   *
   * @param directory The directory to navigate to
   */
  public void navigateToDirectory(@NonNull SmbFileItem directory) {
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
  public void navigateToPath(@NonNull String path) {
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
   * Navigates to a path and builds the correct navigation hierarchy. This method ensures that the
   * navigation stack is properly built so that the user can navigate back up the directory tree.
   *
   * @param path The path to navigate to
   */
  public void navigateToPathWithHierarchy(@NonNull String path) {
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
    if (path.equals("/")) {
      state.setCurrentPath("");
      loadFiles();
      return;
    }

    // Remove leading slash if present
    String cleanPath = path.startsWith("/") ? path.substring(1) : path;

    // Split the path into parts
    String[] pathParts = cleanPath.split("/", -1);

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
