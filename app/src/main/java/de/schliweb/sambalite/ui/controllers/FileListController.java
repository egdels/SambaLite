/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui.controllers;

import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.sync.SyncDirection;
import de.schliweb.sambalite.transfer.db.PendingTransfer;
import de.schliweb.sambalite.transfer.db.TransferDatabase;
import de.schliweb.sambalite.ui.FileAdapter;
import de.schliweb.sambalite.ui.FileListViewModel;
import de.schliweb.sambalite.ui.FileSortOption;
import de.schliweb.sambalite.ui.ThumbnailManager;
import de.schliweb.sambalite.util.LogUtils;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

/**
 * Controller for managing the file list display in the FileBrowserActivity. Handles the
 * RecyclerView, adapter, and file list interactions.
 */
public class FileListController
    implements FileAdapter.OnFileClickListener,
        FileAdapter.OnFileOptionsClickListener,
        FileAdapter.OnFileLongClickListener {

  private final RecyclerView recyclerView;
  private final SwipeRefreshLayout swipeRefreshLayout;
  private final View emptyView;
  private final TextView currentPathView;
  private final FileAdapter adapter;
  private final FileListViewModel viewModel;
  private final FileBrowserUIState uiState;

  // Search mode flag – when true, the getFiles() observer will not overwrite search results
  @Getter @Setter private boolean searchMode = false;

  // Selection state
  @Getter private boolean selectionMode = false;
  private final java.util.LinkedHashSet<String> selectedPaths = new java.util.LinkedHashSet<>();

  @Setter private SelectionChangedCallback selectionChangedCallback;

  // Callback interfaces
  @Setter private FileOptionsCallback fileOptionsCallback;

  @Setter private FileStatisticsCallback fileStatisticsCallback;
  @Setter private FolderChangeCallback folderChangeCallback;

  /**
   * Creates a new FileListController.
   *
   * @param recyclerView The RecyclerView for displaying files
   * @param swipeRefreshLayout The SwipeRefreshLayout for pull-to-refresh
   * @param emptyView The view to show when the file list is empty
   * @param currentPathView The TextView for displaying the current path
   * @param viewModel The FileListViewModel for business logic
   * @param uiState The shared UI state
   */
  public FileListController(
      @NonNull RecyclerView recyclerView,
      @NonNull SwipeRefreshLayout swipeRefreshLayout,
      @NonNull View emptyView,
      @NonNull TextView currentPathView,
      @NonNull FileListViewModel viewModel,
      @NonNull FileBrowserUIState uiState) {
    this.recyclerView = recyclerView;
    this.swipeRefreshLayout = swipeRefreshLayout;
    this.emptyView = emptyView;
    this.currentPathView = currentPathView;
    this.viewModel = viewModel;
    this.uiState = uiState;

    // Initialize the adapter
    this.adapter = new FileAdapter();
    this.adapter.setOnFileClickListener(this);
    this.adapter.setOnFileOptionsClickListener(this);
    this.adapter.setOnFileLongClickListener(this);

    // Set up the RecyclerView
    setupRecyclerView();

    // Set up the SwipeRefreshLayout
    setupSwipeRefreshLayout();

    // Observe the ViewModel
    observeViewModel();
  }

  /** Sets up the RecyclerView. */
  private void setupRecyclerView() {
    recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
    recyclerView.setAdapter(adapter);
  }

  /** Sets up the SwipeRefreshLayout. */
  private void setupSwipeRefreshLayout() {
    swipeRefreshLayout.setOnRefreshListener(
        () -> {
          LogUtils.d("FileListController", "Refreshing file list");
          viewModel.refreshCurrentDirectory();
        });
  }

  /** Observes the ViewModel for changes. */
  private void observeViewModel() {
    // Observe file list changes
    viewModel
        .getFiles()
        .observe(
            getLifecycleOwner(),
            files -> {
              if (searchMode) {
                LogUtils.d("FileListController", "Ignoring file list update while in search mode");
                swipeRefreshLayout.setRefreshing(false);
                return;
              }
              LogUtils.d("FileListController", "File list updated: " + files.size() + " files");
              adapter.setFiles(files);
              // Propagate current selection state to adapter
              adapter.setSelectionMode(selectionMode);
              adapter.setSelectedPaths(selectedPaths);
              updateEmptyView(files);
              swipeRefreshLayout.setRefreshing(false);
              // Update file statistics if callback is set
              if (fileStatisticsCallback != null) {
                fileStatisticsCallback.onFileStatisticsUpdated(files);
              }
              // Refresh active upload indicators from DB
              refreshActiveUploadPaths();
            });

    // Observe current path changes
    viewModel
        .getCurrentPath()
        .observe(
            getLifecycleOwner(),
            path -> {
              LogUtils.d("FileListController", "Current path updated: " + path);
              currentPathView.setText(path);

              // If user navigated to a different folder while in multi-select, clear and exit
              // selection mode
              if (selectionMode) {
                clearSelection();
                enableSelectionMode(false);
              }

              if (folderChangeCallback != null) {
                folderChangeCallback.onFolderChanged(path);
              }
            });

    // Observe loading state
    viewModel
        .isLoading()
        .observe(
            getLifecycleOwner(),
            isLoading -> {
              swipeRefreshLayout.setRefreshing(isLoading);
            });
  }

  /**
   * Updates the empty view based on the file list.
   *
   * @param files The list of files
   */
  private void updateEmptyView(List<SmbFileItem> files) {
    if (files.isEmpty()) {
      emptyView.setVisibility(View.VISIBLE);
      recyclerView.setVisibility(View.GONE);
    } else {
      emptyView.setVisibility(View.GONE);
      recyclerView.setVisibility(View.VISIBLE);
    }
  }

  /** Gets the lifecycle owner for observing LiveData. */
  private androidx.lifecycle.LifecycleOwner getLifecycleOwner() {
    return (androidx.lifecycle.LifecycleOwner) recyclerView.getContext();
  }

  /**
   * Called when a file is clicked.
   *
   * @param file The file that was clicked
   */
  @Override
  public void onFileClick(@NonNull SmbFileItem file) {
    LogUtils.d("FileListController", "File clicked: " + file.getName());

    // Selection mode: toggle selection for files and directories.
    if (selectionMode) {
      if (file.isFile() || file.isDirectory()) {
        toggleSelection(file);
      }
      return;
    }

    // Store the selected file in the UI state
    uiState.setSelectedFile(file);

    // If it's a directory, navigate to it
    if (file.isDirectory()) {
      viewModel.navigateToDirectory(file);
    } else {
      // For files, show the file options dialog
      onFileOptionsClick(file);
    }
  }

  /**
   * Called when the file options button is clicked.
   *
   * @param file The file for which options were requested
   */
  @Override
  public void onFileOptionsClick(@NonNull SmbFileItem file) {
    LogUtils.d("FileListController", "File options clicked: " + file.getName());

    // Store the selected file in the UI state
    uiState.setSelectedFile(file);

    // Notify the callback
    if (fileOptionsCallback != null) {
      fileOptionsCallback.onFileOptionsClick(file);
    }
  }

  /** Called when the parent directory item is clicked. */
  @Override
  public void onParentDirectoryClick() {
    LogUtils.d("FileListController", "Parent directory clicked");
    navigateUp();
  }

  /**
   * Navigates to the parent directory.
   *
   * @return true if navigation was successful, false if already at the root
   */
  public boolean navigateUp() {
    LogUtils.d("FileListController", "Navigating up");
    return viewModel.navigateUp();
  }

  /**
   * Sets the sort option.
   *
   * @param option The sort option to set
   */
  public void setSortOption(@NonNull FileSortOption option) {
    LogUtils.d("FileListController", "Setting sort option: " + option);
    viewModel.setSortOption(option);
  }

  /**
   * Gets the current sort option.
   *
   * @return The current sort option
   */
  public @NonNull FileSortOption getSortOption() {
    return viewModel.getSortOption().getValue();
  }

  /**
   * Gets whether directories are shown first.
   *
   * @return Whether directories are shown first
   */
  public boolean isDirectoriesFirst() {
    return viewModel.getDirectoriesFirst().getValue();
  }

  /**
   * Sets whether directories should be shown first.
   *
   * @param directoriesFirst Whether directories should be shown first
   */
  public void setDirectoriesFirst(boolean directoriesFirst) {
    LogUtils.d("FileListController", "Setting directories first: " + directoriesFirst);
    viewModel.setDirectoriesFirst(directoriesFirst);
  }

  /**
   * Sets the sync direction markers for folders displayed in the file list.
   *
   * @param syncDirections Map of folder paths to their SyncDirection
   */
  public void setSyncDirections(@NonNull Map<String, SyncDirection> syncDirections) {
    LogUtils.d(
        "FileListController",
        "Setting sync directions: "
            + (syncDirections != null ? syncDirections.size() : 0)
            + " entries");
    adapter.setSyncDirections(syncDirections);
  }

  /**
   * Sets the active upload paths for transfer indicators in the file list.
   *
   * @param paths Set of remote paths currently being uploaded
   */
  public void setActiveUploadPaths(@NonNull Set<String> paths) {
    adapter.setActiveUploadPaths(paths);
  }

  /**
   * Sets the thumbnail manager for loading image previews in the file list.
   *
   * @param thumbnailManager The thumbnail manager instance
   */
  public void setThumbnailManager(ThumbnailManager thumbnailManager) {
    adapter.setThumbnailManager(thumbnailManager);
  }

  /**
   * Sets whether thumbnails should be shown in the file list.
   *
   * @param showThumbnails Whether to show thumbnails
   */
  public void setShowThumbnails(boolean showThumbnails) {
    adapter.setShowThumbnails(showThumbnails);
  }

  public void shutdown() {
    adapter.shutdown();
  }

  /**
   * Sets the active download paths for transfer indicators in the file list.
   *
   * @param paths Set of remote paths currently being downloaded
   */
  public void setActiveDownloadPaths(@NonNull Set<String> paths) {
    adapter.setActiveDownloadPaths(paths);
  }

  /**
   * Refreshes a single file item in the list.
   *
   * @param remotePath The remote path of the file to refresh
   */
  public void refreshFileItem(@NonNull String remotePath) {
    adapter.notifyFileItemChanged(remotePath);
  }

  /**
   * Refreshes active transfer indicators (uploads and downloads) by querying the DB on a background
   * thread. Called once after each file list update and on manual refresh.
   */
  private void refreshActiveUploadPaths() {
    SmbConnection conn = viewModel.getConnection();
    if (conn == null) {
      adapter.setActiveUploadPaths(new HashSet<>());
      adapter.setActiveDownloadPaths(new HashSet<>());
      return;
    }
    String connectionId = conn.getId();
    new Thread(
            () -> {
              var dao =
                  TransferDatabase.getInstance(recyclerView.getContext()).pendingTransferDao();
              List<PendingTransfer> uploads = dao.getActiveUploadsForConnection(connectionId);
              Set<String> uploadPaths = new HashSet<>();
              for (PendingTransfer t : uploads) {
                if (t.remotePath != null && !t.remotePath.isEmpty()) {
                  uploadPaths.add(t.remotePath);
                }
              }
              List<PendingTransfer> downloads = dao.getActiveDownloadsForConnection(connectionId);
              Set<String> downloadPaths = new HashSet<>();
              for (PendingTransfer t : downloads) {
                if (t.remotePath != null && !t.remotePath.isEmpty()) {
                  downloadPaths.add(t.remotePath);
                }
              }
              recyclerView.post(
                  () -> {
                    adapter.setActiveUploadPaths(uploadPaths);
                    adapter.setActiveDownloadPaths(downloadPaths);
                  });
            })
        .start();
  }

  /**
   * Updates the adapter with the given list of files. This method can be used to display search
   * results or other file lists that are not directly observed from the ViewModel.
   *
   * @param files The list of files to display
   */
  public void updateAdapter(@NonNull List<SmbFileItem> files) {
    LogUtils.d("FileListController", "Updating adapter with " + files.size() + " files");
    adapter.setFiles(files);
    updateEmptyView(files);
  }

  // --- Selection mode APIs ---
  public void enableSelectionMode(boolean enabled) {
    if (this.selectionMode == enabled) return;
    this.selectionMode = enabled;
    if (!enabled) {
      selectedPaths.clear();
      adapter.setSelectedPaths(selectedPaths);
    }
    adapter.setSelectionMode(enabled);
    notifySelectionChanged();
  }

  public void toggleSelection(@NonNull SmbFileItem file) {
    if (file == null || (!file.isFile() && !file.isDirectory())) return;
    String path = file.getPath();
    if (path == null) return;
    if (selectedPaths.contains(path)) {
      selectedPaths.remove(path);
    } else {
      selectedPaths.add(path);
    }
    adapter.setSelectedPaths(selectedPaths);
    // If no items remain selected, exit selection mode to restore normal navigation behavior
    if (selectionMode && selectedPaths.isEmpty()) {
      enableSelectionMode(false);
    } else {
      notifySelectionChanged();
    }
  }

  public void clearSelection() {
    selectedPaths.clear();
    adapter.setSelectedPaths(selectedPaths);
    // Exit selection mode when selection is cleared to allow normal navigation
    if (selectionMode) {
      enableSelectionMode(false);
    } else {
      notifySelectionChanged();
    }
  }

  public void selectAllVisible() {
    List<SmbFileItem> files = adapter.getFiles();
    for (SmbFileItem f : files) {
      if (f != null && (f.isFile() || f.isDirectory()) && f.getPath() != null) {
        selectedPaths.add(f.getPath());
      }
    }
    adapter.setSelectedPaths(selectedPaths);
    notifySelectionChanged();
  }

  public @NonNull java.util.Set<String> getSelectedPaths() {
    return new java.util.LinkedHashSet<>(selectedPaths);
  }

  public @NonNull java.util.List<SmbFileItem> getSelectedItems() {
    java.util.ArrayList<SmbFileItem> items = new java.util.ArrayList<>();
    List<SmbFileItem> files = adapter.getFiles();
    java.util.HashSet<String> lookup = new java.util.HashSet<>(selectedPaths);
    for (SmbFileItem f : files) {
      if (f != null && lookup.contains(f.getPath())) {
        items.add(f);
      }
    }
    return items;
  }

  private void notifySelectionChanged() {
    if (selectionChangedCallback != null) {
      selectionChangedCallback.onSelectionChanged(selectedPaths.size(), getSelectedItems());
    }
  }

  @Override
  public void onFileLongClick(@NonNull SmbFileItem file) {
    LogUtils.d(
        "FileListController", "File long-clicked: " + (file != null ? file.getName() : "null"));
    if (file != null && (file.isFile() || file.isDirectory())) {
      if (!selectionMode) {
        enableSelectionMode(true);
      }
      toggleSelection(file);
    }
  }

  /** Callback for file options clicks. */
  public interface FileOptionsCallback {
    /**
     * Called when the file options button is clicked.
     *
     * @param file The file for which options were requested
     */
    void onFileOptionsClick(@NonNull SmbFileItem file);

    /**
     * Called when multi-select options are requested.
     *
     * @param selectedItems The list of selected items
     */
    default void onMultiSelectOptionsClick(@NonNull List<SmbFileItem> selectedItems) {}
  }

  /** Callback for file statistics updates. */
  public interface FileStatisticsCallback {
    /**
     * Called when the file statistics are updated.
     *
     * @param files The list of files
     */
    void onFileStatisticsUpdated(@NonNull List<SmbFileItem> files);
  }

  /**
   * Callback for folder changes. This can be used to notify when the current folder changes, for
   * example, to update the UI or perform actions based on the new path.
   */
  public interface FolderChangeCallback {
    void onFolderChanged(@NonNull String newRemotePath);
  }

  /** Callback when the multi-selection changes. */
  public interface SelectionChangedCallback {
    void onSelectionChanged(int count, @NonNull java.util.List<SmbFileItem> selectedItems);
  }
}
