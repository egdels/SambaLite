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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.TextView;
import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.IntentCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.SambaLiteApp;
import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.di.AppComponent;
import de.schliweb.sambalite.security.BiometricAuthHelper;
import de.schliweb.sambalite.sync.SyncConfig;
import de.schliweb.sambalite.sync.SyncDirection;
import de.schliweb.sambalite.sync.SyncManager;
import de.schliweb.sambalite.transfer.TransferWorker;
import de.schliweb.sambalite.ui.controllers.*;
import de.schliweb.sambalite.ui.operations.FileOperationsViewModel;
import de.schliweb.sambalite.ui.utils.PreferenceUtils;
import de.schliweb.sambalite.util.KeyboardUtils;
import de.schliweb.sambalite.util.LogUtils;
import de.schliweb.sambalite.util.PreferencesManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;

/**
 * Refactored Activity for browsing files on an SMB server. This version uses specialized
 * controllers to handle different responsibilities.
 */
public class FileBrowserActivity extends AppCompatActivity
    implements FileListController.FileOptionsCallback,
        FileListController.FileStatisticsCallback,
        FileOperationsController.FileOperationListener,
        DialogController.FileOpenCallback,
        DialogController.MultiSelectCallback {

  private static final String EXTRA_CONNECTION_ID = "extra_connection_id";
  private static final String EXTRA_SEARCH_QUERY = "extra_search_query";
  private static final String EXTRA_SEARCH_TYPE = "extra_search_type";
  private static final String EXTRA_SEARCH_INCLUDE_SUBFOLDERS = "extra_search_include_subfolders";
  private static final String EXTRA_FROM_SEARCH_NOTIFICATION = "extra_from_search_notification";
  private static final String EXTRA_DIRECTORY_PATH = "extra_directory_path";
  private static final String EXTRA_FROM_UPLOAD_NOTIFICATION = "extra_from_upload_notification";
  private static final String EXTRA_FROM_DOWNLOAD_NOTIFICATION = "extra_from_download_notification";
  private static final String EXTRA_FROM_SHARE_UPLOAD = "extra_from_share_upload";
  private static final String EXTRA_SHARE_URIS = "extra_share_uris";
  private static final String EXTRA_FOLDER_PICKER_MODE = "extra_folder_picker_mode";

  @Inject ViewModelProvider.Factory viewModelFactory;

  @Inject FileBrowserUIState uiState;

  @Inject BackgroundSmbManager backgroundSmbManager;

  @Inject SyncManager syncManager;

  @Inject PreferencesManager preferencesManager;

  // ViewModels
  FileListViewModel fileListViewModel;
  private FileOperationsViewModel fileOperationsViewModel;
  SearchViewModel searchViewModel;

  // Controllers
  FileListController fileListController;
  private DialogController dialogController;
  FileOperationsController fileOperationsController;

  @SuppressWarnings("WeakerAccess") /* accessed from anonymous inner class */
  ProgressController progressController;

  ActivityResultController activityResultController;
  // ServiceController removed; using BackgroundSmbManager directly
  private InputController inputController;

  // Selection state for toolbar actions
  int selectionCount = 0;
  private List<SmbFileItem> selectedItems = new ArrayList<>();

  // UI Components
  private RecyclerView recyclerView;
  private SwipeRefreshLayout swipeRefreshLayout;
  private View emptyView;
  private TextView currentPathView;
  FloatingActionButton fab;
  FloatingActionButton fabCreateFolder;
  private FloatingActionButton fabMultiOptions;
  private FloatingActionButton fabSelectAll;
  private FloatingActionButton fabClearSelection;
  private com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
      fabSelectFolder;
  private boolean folderPickerMode = false;

  private final BroadcastReceiver transferCompletedReceiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          String displayName = intent.getStringExtra(TransferWorker.EXTRA_DISPLAY_NAME);
          String transferType = intent.getStringExtra(TransferWorker.EXTRA_TRANSFER_TYPE);
          String label =
              "UPLOAD".equals(transferType)
                  ? getString(R.string.transfer_upload_success, displayName)
                  : getString(R.string.transfer_download_success, displayName);
          LogUtils.i("FileBrowserActivity", "Transfer completed broadcast: " + displayName);
          if (progressController != null) {
            progressController.showSuccess(label);
          }
          if (fileListViewModel != null) {
            fileListViewModel.refreshCurrentDirectory();
          }
        }
      };

  /**
   * Creates an intent to start this activity.
   *
   * @param context The context to use
   * @param connectionId The ID of the connection to browse
   * @return The intent to start this activity
   */
  public static @Nullable Intent createIntent(
      @NonNull Context context, @NonNull String connectionId) {
    Intent intent = new Intent(context, FileBrowserActivity.class);
    intent.putExtra(EXTRA_CONNECTION_ID, connectionId);
    return intent;
  }

  /**
   * Creates an intent to start this activity in folder picker mode. In this mode, a "Select this
   * folder" button is shown and regular FABs are hidden.
   *
   * @param context The context to use
   * @param connectionId The ID of the connection to browse
   * @return The intent to start this activity in folder picker mode
   */
  public static @Nullable Intent createFolderPickerIntent(
      @NonNull Context context, @NonNull String connectionId) {
    Intent intent = new Intent(context, FileBrowserActivity.class);
    intent.putExtra(EXTRA_CONNECTION_ID, connectionId);
    intent.putExtra(EXTRA_FOLDER_PICKER_MODE, true);
    return intent;
  }

  public static @Nullable Intent createIntentFromUploadNotification(
      @NonNull Context context, @NonNull String connectionId, @NonNull String directoryPath) {
    Intent intent = new Intent(context, FileBrowserActivity.class);
    intent.putExtra(EXTRA_CONNECTION_ID, connectionId);
    intent.putExtra(EXTRA_DIRECTORY_PATH, directoryPath);
    intent.putExtra(EXTRA_FROM_UPLOAD_NOTIFICATION, true);
    return intent;
  }

  /**
   * Creates an intent to start this activity with search parameters.
   *
   * @param context The context to use
   * @param connectionId The ID of the connection to browse
   * @param searchQuery The search query
   * @param searchType The type of search (0=all, 1=files only, 2=folders only)
   * @param includeSubfolders Whether to include subfolders in the search
   * @return The intent to start this activity
   */
  public static @Nullable Intent createSearchIntent(
      @NonNull Context context,
      @NonNull String connectionId,
      @NonNull String searchQuery,
      int searchType,
      boolean includeSubfolders) {
    Intent intent = new Intent(context, FileBrowserActivity.class);
    intent.putExtra(EXTRA_CONNECTION_ID, connectionId);
    intent.putExtra(EXTRA_SEARCH_QUERY, searchQuery);
    intent.putExtra(EXTRA_SEARCH_TYPE, searchType);
    intent.putExtra(EXTRA_SEARCH_INCLUDE_SUBFOLDERS, includeSubfolders);
    intent.putExtra(EXTRA_FROM_SEARCH_NOTIFICATION, true);
    return intent;
  }

  /**
   * Creates an intent to start this activity for a Share handoff that will upload URIs to a target
   * path.
   */
  @SuppressWarnings("NonApiType")
  public static @Nullable Intent createIntentForShareUpload(
      @NonNull Context context,
      @NonNull String connectionId,
      @NonNull String directoryPath,
      @NonNull java.util.ArrayList<android.net.Uri> shareUris) {
    Intent intent = new Intent(context, FileBrowserActivity.class);
    intent.putExtra(EXTRA_CONNECTION_ID, connectionId);
    intent.putExtra(EXTRA_DIRECTORY_PATH, directoryPath);
    intent.putExtra(EXTRA_FROM_SHARE_UPLOAD, true);
    intent.putParcelableArrayListExtra(EXTRA_SHARE_URIS, shareUris);
    // Reorder to front if an instance is running
    intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    return intent;
  }

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    LogUtils.d("FileBrowserActivity", "onCreate called");
    LogUtils.d("FileBrowserActivity", "Error handler initialized");

    // Get the Dagger component and inject dependencies
    AppComponent appComponent = ((SambaLiteApp) getApplication()).getAppComponent();
    appComponent.inject(this);
    LogUtils.d("FileBrowserActivity", "Dependencies injected");

    super.onCreate(savedInstanceState);

    // Configure edge-to-edge display for better landscape experience
    configureEdgeToEdgeDisplay();

    setContentView(R.layout.activity_file_browser);
    LogUtils.d("FileBrowserActivity", "Content view set");

    // Set up Toolbar
    setupToolbar();

    // Initialize UI components
    initializeUIComponents();

    // Initialize ViewModels
    initializeViewModels();

    // Initialize shared state
    initializeSharedState();

    // Initialize controllers
    initializeControllers();

    // Set up ViewModel observers
    setupViewModelObservers();

    restoreProgressDialogsIfNeeded();

    // Set up controller callbacks
    setupControllerCallbacks();

    // Set up UI event listeners
    setupUIEventListeners();

    // Reset navigation to root on fresh start to avoid stale paths surviving a swipe-kill
    // while a foreground service keeps the process alive (singleton FileBrowserState persists).
    // Skip reset on configuration changes (e.g. rotation) where savedInstanceState is non-null.
    if (savedInstanceState == null) {
      fileListViewModel.resetNavigation();
    }

    // Load connection from intent
    loadConnectionFromIntent();

    // Check for folder picker mode
    folderPickerMode = getIntent().getBooleanExtra(EXTRA_FOLDER_PICKER_MODE, false);
    if (folderPickerMode) {
      setupFolderPickerMode();
    }

    // Handle system back via OnBackPressedDispatcher (replaces deprecated onBackPressed())
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new androidx.activity.OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                LogUtils.d("FileBrowserActivity", "System back pressed (dispatcher)");
                if (searchViewModel != null && searchViewModel.isInSearchMode()) {
                  searchViewModel.cancelSearch();
                  fileListController.setSearchMode(false);
                  fileListViewModel.refreshCurrentDirectory();
                  resetToolbarAfterSearch();
                  return;
                }
                if (fileListController != null && fileListController.navigateUp()) {
                  return;
                }
                // Already at top-level -> finish activity
                confirmFinishIfBusy();
              }
            });
  }

  /** Configures edge-to-edge display for better landscape experience without deprecated flags. */
  private void configureEdgeToEdgeDisplay() {
    // Use Activity EdgeToEdge helper for backward-compatible, borderless display
    EdgeToEdge.enable(this);
  }

  /** Resets the toolbar title and subtitle after leaving search mode. */
  void resetToolbarAfterSearch() {
    if (getSupportActionBar() != null) {
      getSupportActionBar().setTitle(getString(R.string.files_tab));
      getSupportActionBar().setSubtitle(null);
    }
  }

  /** Sets up the toolbar. */
  private void setupToolbar() {
    androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    if (getSupportActionBar() != null) {
      getSupportActionBar().setDisplayHomeAsUpEnabled(true);
      getSupportActionBar().setTitle(getString(R.string.files_tab));
    }
    LogUtils.d("FileBrowserActivity", "Toolbar set up");
  }

  /** Initializes UI components. */
  private void initializeUIComponents() {
    recyclerView = findViewById(R.id.files_recycler_view);
    swipeRefreshLayout = findViewById(R.id.swipe_refresh);
    emptyView = findViewById(R.id.empty_state);
    currentPathView = findViewById(R.id.current_path);
    fab = findViewById(R.id.fab);
    fabCreateFolder = findViewById(R.id.fab_create_folder);
    fabMultiOptions = findViewById(R.id.fab_multi_options);
    fabSelectAll = findViewById(R.id.fab_select_all);
    fabClearSelection = findViewById(R.id.fab_clear_selection);
    fabSelectFolder = findViewById(R.id.fab_select_folder);
    LogUtils.d("FileBrowserActivity", "UI components initialized");
  }

  /** Initializes ViewModels. */
  private void initializeViewModels() {
    fileListViewModel = new ViewModelProvider(this, viewModelFactory).get(FileListViewModel.class);
    fileOperationsViewModel =
        new ViewModelProvider(this, viewModelFactory).get(FileOperationsViewModel.class);
    searchViewModel = new ViewModelProvider(this, viewModelFactory).get(SearchViewModel.class);
    // FileBrowserViewModel initialization removed as it's no longer needed
    LogUtils.d("FileBrowserActivity", "ViewModels initialized");
  }

  /** Initializes shared state. */
  private void initializeSharedState() {
    // FileBrowserUIState is now injected by Dagger
    LogUtils.d("FileBrowserActivity", "Shared state initialized");
  }

  /** Sets up observers for ViewModels. */
  private void setupViewModelObservers() {
    searchViewModel
        .isSearching()
        .observe(
            this,
            isSearching -> {
              if (isSearching) {
                // Prevent the getFiles() observer from overwriting search results
                fileListController.setSearchMode(true);

                // Update toolbar to show search is in progress
                if (getSupportActionBar() != null) {
                  getSupportActionBar()
                      .setTitle("\uD83D\uDD0D " + searchViewModel.getCurrentSearchQuery());
                  getSupportActionBar().setSubtitle(getString(R.string.searching_files));
                }
              } else {
                // Search is complete — update toolbar to show final result count
                LogUtils.d(
                    "FileBrowserActivity",
                    "isSearching=false, isInSearchMode=" + searchViewModel.isInSearchMode());
                if (searchViewModel.isInSearchMode() && getSupportActionBar() != null) {
                  java.util.List<?> results = searchViewModel.getSearchResults().getValue();
                  int count = results != null ? results.size() : 0;
                  String msg = "✓ " + count + " " + getString(R.string.search_results_found);
                  getSupportActionBar().setSubtitle(msg);
                  de.schliweb.sambalite.ui.utils.UIHelper.showInfo(FileBrowserActivity.this, msg);
                  LogUtils.i("FileBrowserActivity", "Search complete: " + count + " results found");
                }
                // Keep searchMode true so results stay visible until user navigates away
              }
            });

    searchViewModel
        .getSearchResults()
        .observe(
            this,
            searchResults -> {
              if (searchViewModel.isInSearchMode() && searchResults != null) {
                fileListController.updateAdapter(searchResults);
                onFileStatisticsUpdated(searchResults);
                // Update toolbar to indicate search mode
                if (getSupportActionBar() != null) {
                  getSupportActionBar()
                      .setTitle("\uD83D\uDD0D " + searchViewModel.getCurrentSearchQuery());
                  boolean stillSearching =
                      Boolean.TRUE.equals(searchViewModel.isSearching().getValue());
                  if (stillSearching) {
                    getSupportActionBar()
                        .setSubtitle(
                            searchResults.size()
                                + " "
                                + getString(R.string.search_results_found)
                                + "…");
                  } else {
                    getSupportActionBar()
                        .setSubtitle(
                            "✓ "
                                + searchResults.size()
                                + " "
                                + getString(R.string.search_results_found));
                  }
                }
                LogUtils.d(
                    "FileBrowserActivity",
                    "Search results updated: " + searchResults.size() + " items");
                // No direct Service call needed here – the executeOperation loop handles it.
              }
            });

    // Ensure FABs visibility reflects current list state, especially for empty folders
    fileListViewModel
        .getFiles()
        .observe(
            this,
            files -> {
              // Respect multi-select: do not show regular FABs while selection is active
              boolean selectionActive =
                  fileListController != null
                      && fileListController.isSelectionMode()
                      && selectionCount > 0;
              if (folderPickerMode) {
                // In folder picker mode, keep regular FABs hidden
                fab.setVisibility(View.GONE);
                fabCreateFolder.setVisibility(View.GONE);
              } else if (selectionActive) {
                // Keep regular FABs fully gone so they don't take space
                fab.setVisibility(View.GONE);
                fabCreateFolder.setVisibility(View.GONE);
              } else {
                // Ensure upload and create-folder buttons are visible
                if (!fab.isShown()) fab.show();
                if (!fabCreateFolder.isShown()) fabCreateFolder.show();
              }
            });

    fileOperationsViewModel
        .isAnyOperationActive()
        .observe(
            this,
            active -> {
              if (Boolean.TRUE.equals(active)) {
                if (!progressController.isTransferDialogShowing()) {
                  String title =
                      Boolean.TRUE.equals(fileOperationsViewModel.isFinalizing().getValue())
                          ? getString(R.string.finalizing)
                          : Boolean.TRUE.equals(fileOperationsViewModel.isUploading().getValue())
                              ? getString(R.string.uploading)
                              : getString(R.string.downloading);
                  progressController.showTransferProgressDialog(title);
                  progressController.setDetailedProgressDialogCancelAction(
                      () -> {
                        if (Boolean.TRUE.equals(fileOperationsViewModel.isUploading().getValue())) {
                          fileOperationsViewModel.cancelUpload();
                        } else if (Boolean.TRUE.equals(
                            fileOperationsViewModel.isDownloading().getValue())) {
                          fileOperationsViewModel.cancelDownload();
                        }
                      });
                }
              } else {
                progressController.hideTransferProgressDialog();
              }
            });

    fileOperationsViewModel
        .getTransferProgress()
        .observe(
            this,
            tp -> {
              if (tp == null) return;

              if (!progressController.isTransferDialogShowing()
                  && Boolean.TRUE.equals(
                      fileOperationsViewModel.isAnyOperationActive().getValue())) {
                String title =
                    Boolean.TRUE.equals(fileOperationsViewModel.isFinalizing().getValue())
                        ? getString(R.string.finalizing)
                        : Boolean.TRUE.equals(fileOperationsViewModel.isUploading().getValue())
                            ? getString(R.string.uploading)
                            : getString(R.string.downloading);
                progressController.showTransferProgressDialog(title);
                progressController.setDetailedProgressDialogCancelAction(
                    () -> {
                      if (Boolean.TRUE.equals(fileOperationsViewModel.isUploading().getValue())) {
                        fileOperationsViewModel.cancelUpload();
                      } else if (Boolean.TRUE.equals(
                          fileOperationsViewModel.isDownloading().getValue())) {
                        fileOperationsViewModel.cancelDownload();
                      }
                    });
              }

              progressController.updateDetailedProgress(
                  tp.percentage(), tp.statusText(), tp.fileName());
            });

    LogUtils.d("FileBrowserActivity", "ViewModel observers set up");
  }

  /**
   * Restores progress dialogs for ongoing operations if needed.
   *
   * <p>This method checks the current state of ongoing operations such as searching, uploading, and
   * downloading using the respective ViewModel instances. If an operation is active, the
   * corresponding progress dialog is displayed.
   *
   * <p>The following scenarios are handled: 1. Shows a search progress dialog if a search operation
   * is active. 2. Shows a transfer progress dialog in the case of an ongoing file upload or
   * download, with appropriate titles indicating "Uploading" or "Downloading". 3. Allows setting a
   * cancel action for the transfer progress dialog to support interruption of ongoing file
   * operations based on user input.
   */
  private void restoreProgressDialogsIfNeeded() {
    // Search progress is now shown inline via toolbar — no dialog needed

    boolean uploading = Boolean.TRUE.equals(fileOperationsViewModel.isUploading().getValue());
    boolean downloading = Boolean.TRUE.equals(fileOperationsViewModel.isDownloading().getValue());
    boolean any =
        uploading
            || downloading
            || Boolean.TRUE.equals(fileOperationsViewModel.isAnyOperationActive().getValue());

    if (any) {
      boolean finalizingActive =
          Boolean.TRUE.equals(fileOperationsViewModel.isFinalizing().getValue());
      if (finalizingActive) {
        progressController.showTransferProgressDialog(getString(R.string.finalizing));
      } else if (uploading) {
        progressController.showTransferProgressDialog(getString(R.string.uploading));
      } else if (downloading) {
        progressController.showTransferProgressDialog(getString(R.string.downloading));
      } else {
        progressController.showTransferProgressDialog();
      }

      progressController.setDetailedProgressDialogCancelAction(
          () -> {
            if (Boolean.TRUE.equals(fileOperationsViewModel.isUploading().getValue())) {
              fileOperationsViewModel.cancelUpload();
            } else if (Boolean.TRUE.equals(fileOperationsViewModel.isDownloading().getValue())) {
              fileOperationsViewModel.cancelDownload();
            }
          });
    }
  }

  /** Initializes controllers. */
  private void initializeControllers() {
    // Create controllers
    inputController = new InputController(this);
    progressController = new ProgressController(this);
    fileListController =
        new FileListController(
            recyclerView,
            swipeRefreshLayout,
            emptyView,
            currentPathView,
            fileListViewModel,
            uiState);

    dialogController =
        new DialogController(
            this, fileListViewModel, fileOperationsViewModel, searchViewModel, uiState);

    fileOperationsController =
        new FileOperationsController(
            this, fileOperationsViewModel, fileListViewModel, uiState, backgroundSmbManager);

    activityResultController = new ActivityResultController(this, uiState, inputController);

    // ServiceController removed; BackgroundSmbManager handles service binding internally.

    LogUtils.d("FileBrowserActivity", "Controllers initialized");
  }

  /** Sets up controller callbacks. */
  private void setupControllerCallbacks() {
    // Set up FileListController callbacks
    fileListController.setFileOptionsCallback(this);
    fileListController.setFileStatisticsCallback(this);
    fileListController.setFolderChangeCallback(this::onRemoteFolderChanged);

    // Set up DialogController callbacks
    dialogController.setFileOperationCallback(
        new DialogController.FileOperationCallback() {
          @Override
          public void onDownloadRequested(SmbFileItem file) {
            fileOperationsController.getFileOperationRequester().requestFileOrFolderDownload(file);
          }
        });
    dialogController.setSearchCallback(
        (query, searchType, includeSubfolders) -> {
          KeyboardUtils.hideKeyboard(this);
          searchViewModel.searchFiles(query, searchType, includeSubfolders);
        });
    dialogController.setFileOpenCallback(this);
    dialogController.setMultiSelectCallback(this);
    dialogController.setUploadCallback(
        new DialogController.UploadCallback() {
          @Override
          public void onFileUploadRequested() {
            activityResultController.selectFileToUpload();
          }

          @Override
          public void onFolderContentsUploadRequested() {
            activityResultController.selectFolderToUpload();
          }
        });

    // Set up FileOperationsController callbacks
    fileOperationsController.setProgressCallback(progressController);
    fileOperationsController.setActivityResultController(activityResultController);
    fileOperationsController.setDialogController(
        dialogController); // Set DialogController for confirmation dialogs

    // Set the FileOperationRequester on the DialogController
    dialogController.setFileOperationRequester(
        fileOperationsController.getFileOperationRequester());

    fileOperationsController.addListener(this);

    // Set up ActivityResultController callbacks
    activityResultController.setFileOperationCallback(
        new ActivityResultController.FileOperationCallback() {
          @Override
          public void onFileUploadResult(Uri uri) {
            fileOperationsController.handleFileUpload(uri);
          }

          @Override
          public void onMultipleFileUploadResult(java.util.List<Uri> uris) {
            fileOperationsController.handleMultipleFileUploads(uris);
          }

          @Override
          public void onFileDownloadResult(Uri uri) {
            fileOperationsController.handleFileDownload(uri);
          }

          @Override
          public void onFolderDownloadResult(Uri uri) {
            LogUtils.d(
                "FileBrowserActivity",
                "onFolderDownloadResult: uri="
                    + uri
                    + ", isMultiDownloadPending="
                    + uiState.isMultiDownloadPending()
                    + ", pendingItems="
                    + (uiState.getPendingMultiDownloadItems() != null
                        ? uiState.getPendingMultiDownloadItems().size()
                        : "null")
                    + ", selectedFile="
                    + (uiState.getSelectedFile() != null
                        ? uiState.getSelectedFile().getName()
                        : "null"));
            if (uiState.isMultiDownloadPending()) {
              fileOperationsController.handleMultipleFileDownloadsWithTargetUri(uri);
            } else {
              fileOperationsController.handleFolderDownload(uri);
            }
          }

          @Override
          public void onFolderUploadResult(Uri uri) {
            fileOperationsController.handleFolderContentsUpload(uri);
          }

          @Override
          public void onSyncFolderSelected(Uri uri) {
            handleSyncFolderSelected(uri);
          }
        });

    // Listen for selection changes to update toolbar actions
    fileListController.setSelectionChangedCallback(
        (count, items) -> {
          selectionCount = count;
          selectedItems = new java.util.ArrayList<>(items);
          // Update toolbar subtitle with selection count
          if (getSupportActionBar() != null) {
            if (fileListController.isSelectionMode() && selectionCount > 0) {
              getSupportActionBar()
                  .setSubtitle(selectionCount + " " + getString(R.string.selected));
            } else {
              getSupportActionBar().setSubtitle(null);
            }
          }
          // Toggle FAB visibility for multi-select actions
          boolean showMultiFabs = fileListController.isSelectionMode() && selectionCount > 0;
          if (showMultiFabs) {
            // Ensure regular FABs do not take space
            fab.setVisibility(View.GONE);
            fabCreateFolder.setVisibility(View.GONE);
            // Improve placement of multi-select FABs when regular ones are hidden
            adjustMultiSelectFabPlacement();
            if (fabMultiOptions.getVisibility() != View.VISIBLE) fabMultiOptions.show();
            if (fabSelectAll.getVisibility() != View.VISIBLE) fabSelectAll.show();
            if (fabClearSelection.getVisibility() != View.VISIBLE) fabClearSelection.show();
          } else {
            // Restore default visibility of regular FABs
            fab.setVisibility(View.VISIBLE);
            fabCreateFolder.setVisibility(View.VISIBLE);
            if (fabMultiOptions.isShown()) fabMultiOptions.hide();
            if (fabSelectAll.isShown()) fabSelectAll.hide();
            if (fabClearSelection.isShown()) fabClearSelection.hide();
          }
          // Update menu item visibility/enabled state
          invalidateOptionsMenu();
        });

    // Set up sync callbacks
    dialogController.setSyncSetupCallback(
        new DialogController.SyncSetupCallback() {
          @Override
          public void onSyncSetupRequested(
              SyncDirection direction, int intervalMinutes, String remotePath) {
            handleSyncSetupConfirmed(direction, intervalMinutes, remotePath);
          }

          @Override
          public void onSyncFolderPickRequested() {
            activityResultController.selectFolderForSync();
          }
        });

    // Set up folder sync callback for context menu
    dialogController.setFolderSyncCallback(
        new DialogController.FolderSyncCallback() {
          @Override
          public void onSetupSyncRequested(SmbFileItem folder) {
            handleFolderSyncSetup(folder);
          }

          @Override
          public void onSyncNowRequested(SmbFileItem folder) {
            handleSyncNow(folder);
          }

          @Override
          public void onEditSyncRequested(SmbFileItem folder) {
            handleFolderSyncEdit(folder);
          }

          @Override
          public void onRemoveSyncRequested(SmbFileItem folder) {
            handleFolderSyncRemove(folder);
          }

          @Override
          public boolean hasSyncConfig(SmbFileItem folder) {
            return findSyncConfigForFolder(folder) != null;
          }
        });

    LogUtils.d("FileBrowserActivity", "Controller callbacks set up");
  }

  /** Sets up UI event listeners. */
  private void setupUIEventListeners() {
    // Set up search button
    findViewById(R.id.search_button)
        .setOnClickListener(
            v -> {
              LogUtils.d("FileBrowserActivity", "Search button clicked");
              dialogController.showSearchDialog();
            });

    // Set up sort button
    findViewById(R.id.sort_button)
        .setOnClickListener(
            v -> {
              LogUtils.d("FileBrowserActivity", "Sort button clicked");
              dialogController.showSortDialog();
            });

    // Set up upload button (FAB)
    fab.setOnClickListener(
        v -> {
          LogUtils.d("FileBrowserActivity", "Main FAB clicked");
          dialogController.showUploadOptionsDialog();
        });

    // Set up create folder button
    fabCreateFolder.setOnClickListener(
        v -> {
          LogUtils.d("FileBrowserActivity", "Create folder button clicked");
          dialogController.showCreateFolderDialog();
        });

    // Set up parent directory button
    findViewById(R.id.parent_directory_button)
        .setOnClickListener(
            v -> {
              LogUtils.d("FileBrowserActivity", "Parent directory button clicked");
              fileListController.navigateUp();
            });

    // Set up refresh button
    findViewById(R.id.refresh_button)
        .setOnClickListener(
            v -> {
              LogUtils.d("FileBrowserActivity", "Refresh button clicked");
              fileListViewModel.refreshCurrentDirectory();
            });

    // Auto-hide/show FABs on scroll
    recyclerView.addOnScrollListener(
        new RecyclerView.OnScrollListener() {
          @Override
          public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
            super.onScrolled(recyclerView, dx, dy);
            boolean selectionActive =
                fileListController != null
                    && fileListController.isSelectionMode()
                    && selectionCount > 0;
            if (selectionActive) {
              // Do not manipulate regular FABs during selection
              return;
            }
            if (dy > 0) {
              // Scrolling down -> hide
              if (fab.isShown()) fab.hide();
              if (fabCreateFolder.isShown()) fabCreateFolder.hide();
            } else if (dy < 0) {
              // Scrolling up -> show
              if (!fab.isShown()) fab.show();
              if (!fabCreateFolder.isShown()) fabCreateFolder.show();
            }
          }

          @Override
          public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
            super.onScrollStateChanged(recyclerView, newState);
            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
              boolean selectionActive =
                  fileListController != null
                      && fileListController.isSelectionMode()
                      && selectionCount > 0;
              if (selectionActive) {
                // Keep regular FABs gone during selection
                fab.setVisibility(View.GONE);
                fabCreateFolder.setVisibility(View.GONE);
                return;
              }
              // If at top or idle, ensure FABs are visible
              boolean canScrollUp = recyclerView.canScrollVertically(-1);
              if (!canScrollUp) {
                if (!fab.isShown()) fab.show();
                if (!fabCreateFolder.isShown()) fabCreateFolder.show();
              }
            }
          }
        });

    // Multi-select FABs
    if (fabSelectAll != null) {
      fabSelectAll.setOnClickListener(
          v -> {
            LogUtils.d("FileBrowserActivity", "FAB select all clicked");
            if (fileListController != null) {
              fileListController.selectAllVisible();
            }
          });
    }
    if (fabMultiOptions != null) {
      fabMultiOptions.setOnClickListener(
          v -> {
            LogUtils.d("FileBrowserActivity", "FAB multi options clicked (" + selectionCount + ")");
            if (selectionCount > 0) {
              onMultiSelectOptionsClick(selectedItems);
            }
          });
    }
    if (fabClearSelection != null) {
      fabClearSelection.setOnClickListener(
          v -> {
            LogUtils.d("FileBrowserActivity", "FAB clear selection clicked");
            fileListController.clearSelection();
          });
    }

    LogUtils.d("FileBrowserActivity", "UI event listeners set up");
  }

  /** Loads the connection from the intent. */
  private void loadConnectionFromIntent() {
    String connectionId = getIntent().getStringExtra(EXTRA_CONNECTION_ID);
    if (connectionId == null) {
      LogUtils.e("FileBrowserActivity", "No connection ID specified in intent");
      progressController.showError("Error", "No connection specified");
      finish();
      return;
    }
    LogUtils.d("FileBrowserActivity", "Connection ID from intent: " + connectionId);

    // Load connection from MainViewModel
    MainViewModel mainViewModel =
        new ViewModelProvider(this, viewModelFactory).get(MainViewModel.class);
    LogUtils.d("FileBrowserActivity", "MainViewModel initialized to load connection");

    mainViewModel
        .getConnections()
        .observe(
            this,
            connections -> {
              LogUtils.d(
                  "FileBrowserActivity",
                  "Searching for connection with ID: "
                      + connectionId
                      + " among "
                      + connections.size()
                      + " connections");
              for (SmbConnection connection : connections) {
                if (connection.getId().equals(connectionId)) {
                  LogUtils.i("FileBrowserActivity", "Connection found: " + connection.getName());
                  fileListViewModel.setConnection(connection);

                  // Check if we were opened from a notification or share handoff and handle it
                  checkAndHandleSearchNotification();
                  checkAndHandleUploadNotification();
                  checkAndHandleDownloadNotification();
                  checkAndHandleShareUpload();
                  return;
                }
              }

              // Connection not found
              LogUtils.e("FileBrowserActivity", "Connection not found with ID: " + connectionId);
              progressController.showError("Error", "Connection not found");
              finish();
            });
  }

  /**
   * Callback for when the remote folder changes. Updates the current SMB folder in preferences.
   *
   * @param newRemotePath The new remote path
   */
  /**
   * Configures the activity for folder picker mode. Hides regular FABs and shows the "Select this
   * folder" button.
   */
  private void setupFolderPickerMode() {
    LogUtils.d("FileBrowserActivity", "Setting up folder picker mode");
    fab.setVisibility(View.GONE);
    fabCreateFolder.setVisibility(View.GONE);
    fabSelectFolder.setVisibility(View.VISIBLE);
    fabSelectFolder.setOnClickListener(
        v -> {
          // The current folder is already saved in preferences by onRemoteFolderChanged
          LogUtils.d("FileBrowserActivity", "Folder selected in picker mode, finishing activity");
          finish();
        });
  }

  private void onRemoteFolderChanged(String newRemotePath) {
    LogUtils.d("FileBrowserActivity", "Remote folder changed to: " + newRemotePath);
    // Persist both the connection ID and the path to make uploads robust across renames
    SmbConnection conn = fileListViewModel.getConnection();
    if (conn != null) {
      PreferenceUtils.setCurrentSmbContext(this, conn.getId(), newRemotePath);
    } else {
      // Fallback for safety
      PreferenceUtils.setCurrentSmbFolder(this, newRemotePath);
    }
    // Update sync direction markers for the current folder (use internal path without share name)
    updateSyncDirections(fileListViewModel.getCurrentPathInternal());
  }

  /**
   * Updates the sync direction markers for folders in the current directory. Checks all SyncConfigs
   * for the current connection and marks folders whose remotePath starts with or equals the current
   * path.
   *
   * @param currentPath The current remote path being browsed
   */
  private void updateSyncDirections(String currentPath) {
    SmbConnection conn = fileListViewModel.getConnection();
    if (conn == null || fileListController == null) {
      LogUtils.d(
          "FileBrowserActivity",
          "updateSyncDirections: conn="
              + conn
              + ", fileListController="
              + fileListController
              + " -> ABORT");
      return;
    }

    LogUtils.d(
        "FileBrowserActivity",
        "updateSyncDirections: currentPath='"
            + currentPath
            + "', connectionId='"
            + conn.getId()
            + "'");

    java.util.Map<String, SyncDirection> syncDirections = new java.util.HashMap<>();
    java.util.List<SyncConfig> allConfigs = syncManager.getAllSyncConfigs();

    LogUtils.d(
        "FileBrowserActivity", "updateSyncDirections: total SyncConfigs=" + allConfigs.size());

    for (SyncConfig config : allConfigs) {
      LogUtils.d(
          "FileBrowserActivity",
          "updateSyncDirections: checking config: connectionId='"
              + config.getConnectionId()
              + "', remotePath='"
              + config.getRemotePath()
              + "', enabled="
              + config.isEnabled()
              + ", direction="
              + config.getDirection());
      if (!conn.getId().equals(config.getConnectionId()) || !config.isEnabled()) {
        LogUtils.d(
            "FileBrowserActivity", "updateSyncDirections: SKIPPED (wrong connection or disabled)");
        continue;
      }
      String remotePath = config.getRemotePath();
      if (remotePath == null) {
        continue;
      }
      // Normalize remotePath: replace backslashes, strip share name prefix (legacy configs)
      String normalizedRemotePath = remotePath.replace('\\', '/').trim();
      if (normalizedRemotePath.endsWith("/"))
        normalizedRemotePath = normalizedRemotePath.substring(0, normalizedRemotePath.length() - 1);
      String shareName = conn.getShare();
      if (shareName != null
          && !shareName.isEmpty()
          && normalizedRemotePath.startsWith(shareName + "/")) {
        normalizedRemotePath = normalizedRemotePath.substring(shareName.length() + 1);
      }
      // Normalize: for root path (empty), use empty string so startsWith works correctly
      String normalizedCurrent =
          currentPath.isEmpty()
              ? ""
              : (currentPath.endsWith("/") ? currentPath : currentPath + "/");
      LogUtils.d(
          "FileBrowserActivity",
          "updateSyncDirections: normalizedCurrent='"
              + normalizedCurrent
              + "', remotePath='"
              + normalizedRemotePath
              + "', startsWith="
              + normalizedRemotePath.startsWith(normalizedCurrent));
      // Check if the sync config's remotePath is a direct child of the current path
      if (normalizedRemotePath.startsWith(normalizedCurrent)) {
        // The sync folder is at or below the current directory
        // Extract the immediate child folder name
        String remainder = normalizedRemotePath.substring(normalizedCurrent.length());
        if (remainder.endsWith("/")) {
          remainder = remainder.substring(0, remainder.length() - 1);
        }
        if (!remainder.isEmpty() && !remainder.contains("/")) {
          // Only mark direct children that ARE the sync folder (not intermediate parents)
          String fullChildPath = normalizedCurrent + remainder;
          // Only add if not already present (first config wins)
          if (!syncDirections.containsKey(fullChildPath)) {
            syncDirections.put(fullChildPath, config.getDirection());
          }
        }
        // If remainder is empty, the sync folder IS the current directory (don't mark it)
      }
      // Also check exact match (the current folder itself is a sync target)
      if (normalizedRemotePath.equals(currentPath)
          || normalizedRemotePath.equals(normalizedCurrent)) {
        // Mark all items? No — the folder itself is synced, not individual children
        // We could show a general indicator, but for now we skip this case
      }
    }

    LogUtils.d(
        "FileBrowserActivity",
        "Sync directions updated: " + syncDirections.size() + " marked folders");
    fileListController.setSyncDirections(syncDirections);
  }

  /** Checks if the activity was opened from a search notification and handles it. */
  private void checkAndHandleSearchNotification() {
    Intent intent = getIntent();
    if (intent.getBooleanExtra(EXTRA_FROM_SEARCH_NOTIFICATION, false)) {
      LogUtils.d("FileBrowserActivity", "Activity opened from search notification");

      // Extract search parameters from intent
      String searchQuery = intent.getStringExtra(EXTRA_SEARCH_QUERY);
      int searchType = intent.getIntExtra(EXTRA_SEARCH_TYPE, 0);
      boolean includeSubfolders = intent.getBooleanExtra(EXTRA_SEARCH_INCLUDE_SUBFOLDERS, true);

      if (searchQuery != null && !searchQuery.isEmpty()) {
        boolean alreadySearching = Boolean.TRUE.equals(searchViewModel.isSearching().getValue());
        if (alreadySearching) {
          LogUtils.i(
              "FileBrowserActivity", "Search already in progress – results are updating live");
        } else {
          LogUtils.i("FileBrowserActivity", "Starting search from notification: " + searchQuery);
          // Start the search
          searchViewModel.searchFiles(searchQuery, searchType, includeSubfolders);
        }
      }
    }
  }

  /** Checks if the activity was opened from an upload notification and handles it. */
  private void checkAndHandleUploadNotification() {
    Intent intent = getIntent();
    if (intent.getBooleanExtra(EXTRA_FROM_UPLOAD_NOTIFICATION, false)) {
      LogUtils.d("FileBrowserActivity", "Activity opened from upload notification");

      // Extract directory path from intent
      String directoryPath = intent.getStringExtra(EXTRA_DIRECTORY_PATH);

      if (directoryPath != null && !directoryPath.isEmpty()) {
        LogUtils.i("FileBrowserActivity", "Navigating to upload directory: " + directoryPath);
        // Navigate to the directory with proper hierarchy to enable up navigation
        fileListViewModel.navigateToPathWithHierarchy(directoryPath);
        fileListViewModel.refreshCurrentDirectory();
      }

      // Ensure the regular transfer progress dialog is visible, even if this Activity
      // did not initiate the upload (e.g., upload started from ShareReceiverActivity).
      // This mirrors the regular in-app upload UX when opening from notification.
      String title = getString(R.string.uploading);
      progressController.showTransferProgressDialog(title);
      progressController.setDetailedProgressDialogCancelAction(
          () -> fileOperationsViewModel.cancelUpload());
    }
  }

  /** Checks if the activity was opened from a download notification and handles it. */
  private void checkAndHandleDownloadNotification() {
    Intent intent = getIntent();
    if (intent.getBooleanExtra(EXTRA_FROM_DOWNLOAD_NOTIFICATION, false)) {
      LogUtils.d("FileBrowserActivity", "Activity opened from download notification");

      // Extract directory path from intent
      String directoryPath = intent.getStringExtra(EXTRA_DIRECTORY_PATH);

      if (directoryPath != null && !directoryPath.isEmpty()) {
        LogUtils.i("FileBrowserActivity", "Navigating to download directory: " + directoryPath);

        // Navigate to the directory
        fileListViewModel.navigateToPath(directoryPath);
      }
    }
  }

  /** Handles a Share handoff intent to start uploads in this activity (foreground UI). */
  private void checkAndHandleShareUpload() {
    Intent intent = getIntent();
    if (intent.getBooleanExtra(EXTRA_FROM_SHARE_UPLOAD, false)) {
      LogUtils.d("FileBrowserActivity", "Activity opened from Share handoff for upload");

      String directoryPath = intent.getStringExtra(EXTRA_DIRECTORY_PATH);
      java.util.ArrayList<android.net.Uri> uris =
          IntentCompat.getParcelableArrayListExtra(intent, EXTRA_SHARE_URIS, android.net.Uri.class);

      if (directoryPath != null && !directoryPath.isEmpty()) {
        fileListViewModel.navigateToPathWithHierarchy(directoryPath);
      }

      if (uris != null && !uris.isEmpty()) {
        // Best-effort persist or self-grant read permissions for each URI
        final int modeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        for (android.net.Uri u : uris) {
          try {
            String auth = u.getAuthority();
            // Google Photos provides only temporary grants; do NOT attempt to persist
            if (auth == null || !auth.startsWith("com.google.android.apps.photos")) {
              getContentResolver().takePersistableUriPermission(u, modeFlags);
            } else {
              LogUtils.d(
                  "FileBrowserActivity",
                  "Skipping persistable permission for Google Photos URI: " + u);
            }
          } catch (Exception e) {
            LogUtils.w(
                "FileBrowserActivity",
                "takePersistableUriPermission skipped/failed: " + e.getMessage());
          }
          try {
            grantUriPermission(getPackageName(), u, modeFlags);
          } catch (Exception e) {
            LogUtils.w("FileBrowserActivity", "grantUriPermission failed: " + e.getMessage());
          }
        }

        // Build the full remote path (share/subdir) for the upload target
        String uploadTargetPath = null;
        if (directoryPath != null && !directoryPath.isEmpty() && !"/".equals(directoryPath)) {
          SmbConnection conn = fileListViewModel.getConnection();
          if (conn != null && conn.getShare() != null && !conn.getShare().isEmpty()) {
            uploadTargetPath = conn.getShare() + "/" + directoryPath;
          } else {
            uploadTargetPath = directoryPath;
          }
        }
        LogUtils.i(
            "FileBrowserActivity",
            "Starting Share uploads for " + uris.size() + " items to " + uploadTargetPath);
        // Batch uploads via controller to ensure sequential processing and consistent progress
        fileOperationsController.handleMultipleFileUploads(uris, uploadTargetPath);
        // Clear the flag to avoid duplicate starts if onNewIntent/setIntent happens again
        intent.removeExtra(EXTRA_FROM_SHARE_UPLOAD);
        setIntent(intent);
      }
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    LogUtils.d("FileBrowserActivity", "onResume called");

    // Register for transfer completion broadcasts
    IntentFilter filter = new IntentFilter(TransferWorker.ACTION_TRANSFER_COMPLETED);
    //noinspection InlinedApi – RECEIVER_NOT_EXPORTED is inlined; safe on API 28+ (no-op pre-33)
    registerReceiver(transferCompletedReceiver, filter, Context.RECEIVER_NOT_EXPORTED);

    // Refresh the current directory when resuming
    if (PreferenceUtils.getNeedsRefresh(this)) {
      LogUtils.i("FileBrowserActivity", "Update needed, refreshing current directory");
      PreferenceUtils.setNeedsRefresh(this, false);
      fileListViewModel.refreshCurrentDirectory();
    }
  }

  @Override
  protected void onPause() {
    super.onPause();
    try {
      unregisterReceiver(transferCompletedReceiver);
    } catch (IllegalArgumentException ignored) {
      // Receiver was not registered
    }
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    LogUtils.d(
        "FileBrowserActivity",
        "onNewIntent received: " + (intent != null ? intent.getAction() : "null"));
    if (intent == null) return;
    // Update the stored intent so getIntent() returns the latest one
    setIntent(intent);

    // If a different connection is requested, reload; otherwise just handle the notification extras
    String newConnId = intent.getStringExtra(EXTRA_CONNECTION_ID);
    String currentConnId = null;
    SmbConnection currentConn = null;
    try {
      currentConn = fileListViewModel != null ? fileListViewModel.getConnection() : null;
      currentConnId = currentConn != null ? currentConn.getId() : null;
    } catch (Throwable ignored) {
    }

    if (newConnId != null && !newConnId.equals(currentConnId)) {
      LogUtils.i(
          "FileBrowserActivity",
          "New intent targets different connection. Reloading connection context.");
      loadConnectionFromIntent();
    } else {
      // Same connection – handle the specific notification scenarios directly
      checkAndHandleSearchNotification();
      checkAndHandleUploadNotification();
      checkAndHandleDownloadNotification();
      checkAndHandleShareUpload();
    }
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    MenuInflater inflater = getMenuInflater();
    inflater.inflate(R.menu.menu_main, menu);
    return true;
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    /*MenuItem multiDownload = menu.findItem(R.id.action_multi_download);
    MenuItem multiDelete = menu.findItem(R.id.action_multi_delete);
    MenuItem selectAll = menu.findItem(R.id.action_select_all);
    MenuItem clearSel = menu.findItem(R.id.action_clear_selection);
    boolean hasSelection = selectionCount > 0;
    boolean opActive = Boolean.TRUE.equals(fileOperationsViewModel.isAnyOperationActive().getValue());
    boolean enableActions = hasSelection && !opActive;
    if (multiDownload != null) {
        multiDownload.setVisible(hasSelection);
        multiDownload.setEnabled(enableActions);
    }
    if (multiDelete != null) {
        multiDelete.setVisible(hasSelection);
        multiDelete.setEnabled(enableActions);
    }
    // Selection mode helpers
    boolean inSelectionMode = fileListController != null && fileListController.isSelectionMode();
    if (selectAll != null) {
        selectAll.setVisible(inSelectionMode);
        selectAll.setEnabled(!opActive);
    }
    if (clearSel != null) {
        clearSel.setVisible(inSelectionMode);
        clearSel.setEnabled(hasSelection && !opActive);
    }*/
    return super.onPrepareOptionsMenu(menu);
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    // Multi-select actions
    /*int id = item.getItemId();

    if (id == R.id.action_multi_download) {
        if (selectionCount > 0) {
            // Delegate to controller
            fileOperationsController.handleMultipleFileDownloads(fileListController.getSelectedItems());
            // Minimal UX: exit selection mode immediately after starting batch action
            fileListController.enableSelectionMode(false);
            if (getSupportActionBar() != null) getSupportActionBar().setSubtitle(null);
            invalidateOptionsMenu();
        }
        return true;
    } else if (id == R.id.action_multi_delete) {
        if (selectionCount > 0) {
            fileOperationsController.handleMultipleFileDelete(fileListController.getSelectedItems());
            // Minimal UX: exit selection mode immediately after starting batch action
            fileListController.enableSelectionMode(false);
            if (getSupportActionBar() != null) getSupportActionBar().setSubtitle(null);
            invalidateOptionsMenu();
        }
        return true;
    } else if (id == R.id.action_select_all) {
        if (fileListController != null && fileListController.isSelectionMode()) {
            fileListController.selectAllVisible();
            invalidateOptionsMenu();
        }
        return true;
    } else if (id == R.id.action_clear_selection) {
        if (fileListController != null && fileListController.isSelectionMode()) {
            fileListController.clearSelection();
            if (getSupportActionBar() != null) getSupportActionBar().setSubtitle(null);
            invalidateOptionsMenu();
        }
        return true;
    }*/
    // Handle toolbar navigation
    if (item.getItemId() == android.R.id.home) {
      LogUtils.d("FileBrowserActivity", "Toolbar back button clicked");
      // If we are in search mode, exit search and return to the search start folder
      if (searchViewModel != null && searchViewModel.isInSearchMode()) {
        // Cancel any in-flight search and return to the starting folder
        searchViewModel.cancelSearch();
        fileListController.setSearchMode(false);
        fileListViewModel.refreshCurrentDirectory();
        resetToolbarAfterSearch();
        return true;
      }
      // Try to navigate up within the folder hierarchy first
      if (fileListController != null && fileListController.navigateUp()) {
        return true; // consumed by navigating up
      }
      // Already at top-level -> finish to return to connections
      confirmFinishIfBusy();
      return true;
    } else if (item.getItemId() == R.id.action_transfer_queue) {
      startActivity(TransferQueueActivity.createIntent(this));
      return true;
    } else if (item.getItemId() == R.id.action_security_settings) {
      LogUtils.d("FileBrowserActivity", "Security settings menu item selected");
      authenticateBeforeSecuritySettings();
      return true;
    } else if (item.getItemId() == R.id.action_system_monitor) {
      LogUtils.d("FileBrowserActivity", "System Monitor menu item selected");
      Intent intent = SystemMonitorActivity.createIntent(this);
      startActivity(intent);
      return true;
    } else if (item.getItemId() == R.id.action_quit) {
      handleQuit();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  /**
   * Requires authentication before opening the security settings dialog if any auth setting is
   * currently enabled. This prevents unauthorized users from disabling security protections.
   */
  private void authenticateBeforeSecuritySettings() {
    boolean anyAuthEnabled =
        preferencesManager.isAuthRequiredForAccess()
            || preferencesManager.isAuthRequiredForPasswordReveal();

    if (anyAuthEnabled && BiometricAuthHelper.isDeviceAuthAvailable(this)) {
      BiometricAuthHelper.authenticate(
          this,
          getString(R.string.auth_title_access),
          getString(R.string.auth_subtitle_security_settings),
          new BiometricAuthHelper.AuthCallback() {
            @Override
            public void onAuthSuccess() {
              showSecuritySettingsDialog();
            }

            @Override
            public void onAuthFailure(String errorMessage) {
              progressController.showError(getString(R.string.auth_failed, errorMessage), null);
            }

            @Override
            public void onAuthCancelled() {
              LogUtils.d(
                  "FileBrowserActivity", "Security settings authentication cancelled by user");
            }
          });
    } else {
      showSecuritySettingsDialog();
    }
  }

  /** Shows a dialog for configuring security settings. */
  private void showSecuritySettingsDialog() {
    LogUtils.d("FileBrowserActivity", "Showing security settings dialog");

    boolean deviceAuthAvailable = BiometricAuthHelper.isDeviceAuthAvailable(this);

    View dialogView = getLayoutInflater().inflate(R.layout.dialog_security_settings, null);
    com.google.android.material.materialswitch.MaterialSwitch authAccessSwitch =
        dialogView.findViewById(R.id.auth_access_switch);
    com.google.android.material.materialswitch.MaterialSwitch authPasswordSwitch =
        dialogView.findViewById(R.id.auth_password_reveal_switch);
    TextView authNotAvailableText = dialogView.findViewById(R.id.auth_not_available_text);

    authAccessSwitch.setChecked(preferencesManager.isAuthRequiredForAccess());
    authPasswordSwitch.setChecked(preferencesManager.isAuthRequiredForPasswordReveal());

    if (!deviceAuthAvailable) {
      authAccessSwitch.setEnabled(false);
      authPasswordSwitch.setEnabled(false);
      authNotAvailableText.setVisibility(View.VISIBLE);
    } else {
      authNotAvailableText.setVisibility(View.GONE);
    }

    new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
        .setTitle(R.string.security_settings_title)
        .setView(dialogView)
        .setPositiveButton(
            R.string.save,
            (dialog, which) -> {
              preferencesManager.saveAuthRequiredForAccess(authAccessSwitch.isChecked());
              preferencesManager.saveAuthRequiredForPasswordReveal(authPasswordSwitch.isChecked());
              LogUtils.i(
                  "FileBrowserActivity",
                  "Security settings saved: access="
                      + authAccessSwitch.isChecked()
                      + ", passwordReveal="
                      + authPasswordSwitch.isChecked());
            })
        .setNegativeButton(R.string.cancel, null)
        .show();
  }

  /** Builds the full remote path for a folder item in the current directory. */
  private String buildFullRemotePath(SmbFileItem folder) {
    String currentPath = fileListViewModel.getCurrentPathInternal();
    if (currentPath == null || currentPath.isEmpty()) {
      return folder.getName();
    }
    String normalized = currentPath.endsWith("/") ? currentPath : currentPath + "/";
    return normalized + folder.getName();
  }

  /** Finds the SyncConfig that exactly matches the given folder, or null. */
  SyncConfig findSyncConfigForFolder(SmbFileItem folder) {
    SmbConnection conn = fileListViewModel.getConnection();
    if (conn == null) return null;
    String fullPath = buildFullRemotePath(folder);
    String shareName = conn.getShare();
    for (SyncConfig config : syncManager.getAllSyncConfigs()) {
      if (!conn.getId().equals(config.getConnectionId())) continue;
      String remotePath = config.getRemotePath();
      if (remotePath == null) continue;
      // Normalize: remove trailing slash and replace backslashes
      String normalizedRemote = remotePath.replace('\\', '/').trim();
      if (normalizedRemote.endsWith("/"))
        normalizedRemote = normalizedRemote.substring(0, normalizedRemote.length() - 1);
      // Strip share name prefix if present (legacy configs may include it)
      if (shareName != null
          && !shareName.isEmpty()
          && normalizedRemote.startsWith(shareName + "/")) {
        normalizedRemote = normalizedRemote.substring(shareName.length() + 1);
      }
      if (normalizedRemote.equals(fullPath)) {
        return config;
      }
    }
    return null;
  }

  /**
   * Handles setting up sync for a folder from the context menu. Checks for collisions (parent or
   * child already synced) before showing the setup dialog.
   */
  void handleFolderSyncSetup(SmbFileItem folder) {
    SmbConnection conn = fileListViewModel.getConnection();
    if (conn == null) return;

    String fullPath = buildFullRemotePath(folder);
    String fullPathWithSlash = fullPath + "/";

    String shareName = conn.getShare();
    for (SyncConfig config : syncManager.getAllSyncConfigs()) {
      if (!conn.getId().equals(config.getConnectionId()) || !config.isEnabled()) continue;
      String remotePath = config.getRemotePath();
      if (remotePath == null) continue;
      // Normalize: replace backslashes, strip share name prefix (legacy configs)
      String normalizedRemote = remotePath.replace('\\', '/').trim();
      if (normalizedRemote.endsWith("/"))
        normalizedRemote = normalizedRemote.substring(0, normalizedRemote.length() - 1);
      if (shareName != null
          && !shareName.isEmpty()
          && normalizedRemote.startsWith(shareName + "/")) {
        normalizedRemote = normalizedRemote.substring(shareName.length() + 1);
      }
      normalizedRemote = normalizedRemote + "/";
      String normalizedFull = fullPathWithSlash;

      // Check exact match
      if (normalizedRemote.equals(normalizedFull)) {
        progressController.showError(getString(R.string.sync_folder_already_synced), null);
        return;
      }
      // Check if a parent folder is already synced (remotePath is prefix of fullPath)
      if (normalizedFull.startsWith(normalizedRemote)) {
        progressController.showError(getString(R.string.sync_parent_folder_synced), null);
        return;
      }
      // Check if a child folder is already synced (fullPath is prefix of remotePath)
      if (normalizedRemote.startsWith(normalizedFull)) {
        progressController.showError(getString(R.string.sync_child_folder_synced), null);
        return;
      }
    }

    // No collision — show the sync setup dialog with the folder's full path
    dialogController.showSyncSetupDialog(fullPath);
  }

  /**
   * Handles editing sync for a folder via the context menu. Finds the existing config, removes it,
   * and opens the setup dialog pre-filled with old values.
   */
  void handleFolderSyncEdit(SmbFileItem folder) {
    SyncConfig config = findSyncConfigForFolder(folder);
    if (config == null) return;

    // Store the config ID so we can remove it only when the user confirms
    uiState.setEditingSyncConfigId(config.getId());

    // Show edit dialog with pre-filled values
    dialogController.showSyncEditDialog(config);
  }

  /** Handles triggering an immediate sync for a folder via the context menu. */
  void handleSyncNow(SmbFileItem folder) {
    LogUtils.d("FileBrowserActivity", "Sync now requested for folder: " + folder.getName());
    SyncConfig config = findSyncConfigForFolder(folder);
    if (config != null) {
      syncManager.triggerImmediateSync(config.getId());
      progressController.showSuccess(getString(R.string.sync_now));
    } else {
      LogUtils.w("FileBrowserActivity", "No sync config found for folder: " + folder.getName());
      progressController.showError(getString(R.string.sync_config_removed), null);
    }
  }

  /** Handles removing sync from a folder via the context menu. */
  void handleFolderSyncRemove(SmbFileItem folder) {
    SyncConfig config = findSyncConfigForFolder(folder);
    if (config != null) {
      syncManager.removeSyncConfig(config.getId());
      progressController.showSuccess(getString(R.string.sync_config_removed));
      String currentPath = fileListViewModel.getCurrentPathInternal();
      if (currentPath != null) {
        updateSyncDirections(currentPath);
      }
    }
  }

  /** Types of local folder collisions that can occur when setting up sync. */
  enum LocalFolderCollisionType {
    EXACT_MATCH,
    PARENT_SYNCED,
    CHILD_SYNCED
  }

  /**
   * Checks whether the given local folder URI collides with any existing sync config.
   *
   * @param folderUriStr the URI string of the folder to check
   * @param existingConfigs the list of existing sync configurations
   * @param editingConfigId the ID of the config being edited (to exclude from check), or null
   * @return the type of collision found, or null if no collision
   */
  static LocalFolderCollisionType checkLocalFolderCollision(
      String folderUriStr, java.util.List<SyncConfig> existingConfigs, String editingConfigId) {
    for (SyncConfig config : existingConfigs) {
      if (!config.isEnabled()) continue;
      if (editingConfigId != null && editingConfigId.equals(config.getId())) continue;
      String existingUri = config.getLocalFolderUri();
      if (existingUri == null) continue;

      if (folderUriStr.equals(existingUri)) {
        return LocalFolderCollisionType.EXACT_MATCH;
      }
      if (isPathPrefixOf(existingUri, folderUriStr)) {
        return LocalFolderCollisionType.PARENT_SYNCED;
      }
      if (isPathPrefixOf(folderUriStr, existingUri)) {
        return LocalFolderCollisionType.CHILD_SYNCED;
      }
    }
    return null;
  }

  /**
   * Checks if candidateUri starts with prefixUri as a proper path prefix, handling both "/" and
   * "%2F" (encoded) path separators in Android content URIs.
   */
  static boolean isPathPrefixOf(String prefixUri, String candidateUri) {
    // Direct check with "/"
    String prefixWithSlash = prefixUri.endsWith("/") ? prefixUri : prefixUri + "/";
    if (candidateUri.startsWith(prefixWithSlash)) {
      return true;
    }
    // Check with encoded separator "%2F"
    String prefixWithEncoded =
        prefixUri.endsWith("%2F") || prefixUri.endsWith("%2f") ? prefixUri : prefixUri + "%2F";
    return candidateUri.startsWith(prefixWithEncoded)
        || candidateUri
            .toLowerCase(Locale.ROOT)
            .startsWith(prefixWithEncoded.toLowerCase(Locale.ROOT));
  }

  /** Handles the sync setup confirmation from the dialog. */
  private static final int MAX_SYNC_CONFIGS = 5;

  void handleSyncSetupConfirmed(SyncDirection direction, int intervalMinutes, String remotePath) {
    Uri folderUri = uiState.getSyncFolderUri();
    if (folderUri == null) {
      progressController.showError(getString(R.string.sync_select_folder_first), null);
      return;
    }

    // Check max sync configs limit (only for new configs, not edits)
    if (uiState.getEditingSyncConfigId() == null
        && syncManager.getAllSyncConfigs().size() >= MAX_SYNC_CONFIGS) {
      progressController.showError(
          getString(R.string.sync_max_configs_reached, MAX_SYNC_CONFIGS), null);
      return;
    }

    SmbConnection connection = fileListViewModel.getConnection();
    if (connection == null) {
      LogUtils.e("FileBrowserActivity", "No connection available for sync setup");
      return;
    }

    // Check for local folder collisions (parent/child already synced)
    String editingConfigId = uiState.getEditingSyncConfigId();
    LocalFolderCollisionType collision =
        checkLocalFolderCollision(
            folderUri.toString(), syncManager.getAllSyncConfigs(), editingConfigId);
    if (collision != null) {
      switch (collision) {
        case EXACT_MATCH:
          progressController.showError(getString(R.string.sync_local_folder_already_synced), null);
          return;
        case PARENT_SYNCED:
          progressController.showError(getString(R.string.sync_local_parent_folder_synced), null);
          return;
        case CHILD_SYNCED:
          progressController.showError(getString(R.string.sync_local_child_folder_synced), null);
          return;
      }
    }

    String displayName =
        uiState.getSyncFolderDisplayName() != null
            ? uiState.getSyncFolderDisplayName()
            : folderUri.getLastPathSegment();

    // If editing an existing config, remove the old one before saving the new one
    if (editingConfigId != null) {
      syncManager.removeSyncConfig(editingConfigId);
      uiState.setEditingSyncConfigId(null);
    }

    syncManager.addSyncConfig(
        connection.getId(), folderUri, remotePath, displayName, direction, intervalMinutes);

    progressController.showSuccess(getString(R.string.sync_config_saved));

    // Clear sync state
    uiState.setSyncFolderUri(null);
    uiState.setSyncFolderDisplayName(null);

    // Refresh sync direction markers
    String currentPath = fileListViewModel.getCurrentPathInternal();
    if (currentPath != null) {
      updateSyncDirections(currentPath);
    }
  }

  /** Handles the folder selection result for sync. */
  void handleSyncFolderSelected(Uri uri) {
    LogUtils.d("FileBrowserActivity", "Sync folder selected: " + uri);
    uiState.setSyncFolderUri(uri);

    // Get display name from DocumentFile
    androidx.documentfile.provider.DocumentFile docFile =
        androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri);
    String displayName = docFile != null ? docFile.getName() : uri.getLastPathSegment();
    uiState.setSyncFolderDisplayName(displayName);

    // Update the folder display in the dialog if it's still showing
    android.widget.TextView folderDisplay = uiState.getSyncFolderDisplay();
    if (folderDisplay != null) {
      folderDisplay.setText(displayName);
    }
  }

  /**
   * Checks whether a file transfer (upload/download) is active and, if so, shows a confirmation
   * dialog before finishing the activity. If no transfer is active, finishes immediately.
   */
  void confirmFinishIfBusy() {
    Boolean active = fileOperationsViewModel.isAnyOperationActive().getValue();
    if (Boolean.TRUE.equals(active)) {
      new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
          .setTitle(R.string.leave_confirm_title)
          .setMessage(R.string.leave_confirm_message)
          .setPositiveButton(R.string.leave_confirm_positive, (dialog, which) -> finish())
          .setNegativeButton(R.string.cancel, null)
          .show();
    } else {
      finish();
    }
  }

  private void handleQuit() {
    if (backgroundSmbManager != null && backgroundSmbManager.hasActiveOperations()) {
      int count = backgroundSmbManager.getActiveOperationCount();
      new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
          .setTitle(R.string.quit_app_confirm_title)
          .setMessage(
              getResources().getQuantityString(R.plurals.quit_app_confirm_message, count, count))
          .setPositiveButton(R.string.quit_app_confirm_positive, (dialog, which) -> performQuit())
          .setNegativeButton(R.string.cancel, null)
          .show();
    } else {
      performQuit();
    }
  }

  private void performQuit() {
    if (backgroundSmbManager != null) {
      backgroundSmbManager.requestStopService();
    }
    finishAffinity();
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent event) {
    // Delegate to InputController
    inputController.handleTouchEvent(event);
    return super.dispatchTouchEvent(event);
  }

  @Override
  protected void onStart() {
    super.onStart();
    if (backgroundSmbManager != null) {
      backgroundSmbManager.onActivityStarted();
    }
  }

  @Override
  protected void onStop() {
    super.onStop();
    if (backgroundSmbManager != null) {
      backgroundSmbManager.onActivityStopped();
    }
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    LogUtils.d("FileBrowserActivity", "onDestroy called");

    fileOperationsController.removeListener(this);

    // Clear View references in singleton UIState to prevent memory leaks
    uiState.setSyncFolderDisplay(null);

    // Immer schließen, sonst WindowLeaked bei Rotation
    progressController.closeAllDialogs();

    boolean shouldCancelOps = isFinishing() && !isChangingConfigurations();
    if (shouldCancelOps) {
      LogUtils.d("FileBrowserActivity", "Activity finishing – requesting operation cancellations");
      fileOperationsViewModel.cancelUpload();
      fileOperationsViewModel.cancelDownload();
      searchViewModel.cancelSearch();
    } else {
      LogUtils.d("FileBrowserActivity", "Activity not finishing – keeping operations running");
    }
  }

  // DialogController.FileOpenCallback implementation
  @Override
  public void onOpenRequested(@NonNull SmbFileItem file) {
    LogUtils.d("FileBrowserActivity", "Open requested for: " + file.getName());
    openFileFromServer(file);
  }

  /** Downloads a file to the local cache and opens it with an appropriate external app. */
  private void openFileFromServer(@NonNull SmbFileItem file) {
    progressController.showDetailedProgressDialog(
        String.format(getString(R.string.opening_file), file.getName()),
        getString(R.string.preparing_ellipsis));
    progressController.setDetailedProgressDialogCancelAction(
        () -> fileOperationsViewModel.cancelDownload());

    fileOperationsViewModel.downloadToCache(
        file,
        localFile -> {
          progressController.hideDetailedProgressDialog();
          if (!de.schliweb.sambalite.util.FileOpener.openFile(this, localFile)) {
            progressController.showError(file.getName(), getString(R.string.no_app_to_open_file));
          }
        },
        error -> {
          progressController.hideDetailedProgressDialog();
          progressController.showError(file.getName(), error);
        });
  }

  // FileListController.FileOptionsCallback implementation
  @Override
  public void onFileOptionsClick(@NonNull SmbFileItem file) {
    LogUtils.d("FileBrowserActivity", "File options clicked: " + file.getName());
    dialogController.showFileOptionsDialog(file);
  }

  @Override
  public void onMultiSelectOptionsClick(@NonNull List<SmbFileItem> selectedItems) {
    LogUtils.d(
        "FileBrowserActivity", "Multi-select options clicked: " + selectedItems.size() + " items");
    dialogController.showMultiSelectOptionsDialog(selectedItems);
  }

  // DialogController.MultiSelectCallback implementation
  @Override
  public void onMultiDownloadRequested(@NonNull List<SmbFileItem> files) {
    LogUtils.d("FileBrowserActivity", "Multi-download requested for " + files.size() + " items");
    fileOperationsController.handleMultipleFileDownloads(files);
    fileListController.clearSelection();
  }

  @Override
  public void onMultiDeleteRequested(@NonNull List<SmbFileItem> files) {
    LogUtils.d("FileBrowserActivity", "Multi-delete requested for " + files.size() + " items");
    fileOperationsController.handleMultipleFileDelete(files);
    fileListController.clearSelection();
  }

  // FileListController.FileStatisticsCallback implementation
  @Override
  public void onFileStatisticsUpdated(@NonNull java.util.List<SmbFileItem> files) {
    int fileCount = 0;
    int folderCount = 0;

    for (SmbFileItem file : files) {
      if (file.isDirectory()) {
        folderCount++;
      } else {
        fileCount++;
      }
    }

    TextView filesCountView = findViewById(R.id.files_count);
    TextView foldersCountView = findViewById(R.id.folders_count);

    if (filesCountView != null) {
      filesCountView.setText(String.valueOf(fileCount));
    }
    if (foldersCountView != null) {
      foldersCountView.setText(String.valueOf(folderCount));
    }

    LogUtils.d(
        "FileBrowserActivity",
        "Statistics updated: " + fileCount + " files, " + folderCount + " folders");
  }

  // FileOperationsController.FileOperationListener implementation
  @Override
  public void onFileOperationStarted(@NonNull String operationType, @NonNull SmbFileItem file) {
    LogUtils.d(
        "FileBrowserActivity",
        "File operation started: "
            + operationType
            + " on "
            + (file != null ? file.getName() : "null"));
    // If a multi-select operation is starting, immediately reset selection UI for clearer UX
    try {
      if (fileListController != null && fileListController.isSelectionMode()) {
        fileListController.clearSelection();
        fileListController.enableSelectionMode(false);
      }
      if (getSupportActionBar() != null) {
        getSupportActionBar().setSubtitle(null);
      }
      invalidateOptionsMenu();
    } catch (Throwable t) {
      LogUtils.w(
          "FileBrowserActivity",
          "Failed to reset selection UI on operation start: " + t.getMessage());
    }
  }

  @Override
  public void onFileOperationCompleted(
      @NonNull String operationType,
      @NonNull SmbFileItem file,
      boolean success,
      @NonNull String message) {
    LogUtils.d(
        "FileBrowserActivity",
        "File operation completed: "
            + operationType
            + " on "
            + (file != null ? file.getName() : "null")
            + ", success: "
            + success
            + ", message: "
            + message);
    // UX polish: always reset selection UI and subtitle after any operation completes
    try {
      if (fileListController != null && fileListController.isSelectionMode()) {
        fileListController.clearSelection();
        fileListController.enableSelectionMode(false);
      }
      if (getSupportActionBar() != null) {
        getSupportActionBar().setSubtitle(null);
      }
      invalidateOptionsMenu();
    } catch (Throwable t) {
      LogUtils.w(
          "FileBrowserActivity", "Failed to reset selection UI after operation: " + t.getMessage());
    }
  }

  @Override
  public void onFileOperationProgress(
      @NonNull String operationType,
      @NonNull SmbFileItem file,
      int progress,
      @NonNull String message) {
    LogUtils.d(
        "FileBrowserActivity",
        "File operation progress: "
            + operationType
            + " on "
            + (file != null ? file.getName() : "null")
            + ", progress: "
            + progress
            + "%, message: "
            + message);
  }

  // Adjust placement of multi-select FABs when regular FABs are hidden so they sit closer to the
  // bottom
  private void adjustMultiSelectFabPlacement() {
    try {
      if (fabMultiOptions == null || fabSelectAll == null || fabClearSelection == null) return;
      // Compact layout: stack with 64dp gaps starting at 32dp from bottom
      int base = dpToPx(32);
      int gap = dpToPx(64);
      setFabBottomMargin(fabClearSelection, base);
      setFabBottomMargin(fabSelectAll, base + gap);
      setFabBottomMargin(fabMultiOptions, base + gap + gap);
    } catch (Throwable ignored) {
      // best-effort adjustment only
    }
  }

  private void setFabBottomMargin(View v, int bottomMarginPx) {
    if (v == null) return;
    android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
    if (lp instanceof android.view.ViewGroup.MarginLayoutParams mlp) {
      if (mlp.bottomMargin != bottomMarginPx) {
        mlp.bottomMargin = bottomMarginPx;
        v.setLayoutParams(mlp);
      }
    }
  }

  private int dpToPx(int dp) {
    float density = getResources().getDisplayMetrics().density;
    return Math.round(dp * density);
  }
}
