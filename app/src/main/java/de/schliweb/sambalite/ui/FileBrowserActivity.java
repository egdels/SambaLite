package de.schliweb.sambalite.ui;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.SambaLiteApp;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.di.AppComponent;
import de.schliweb.sambalite.service.SmbBackgroundService;
import de.schliweb.sambalite.ui.animations.AnimationHelper;
import de.schliweb.sambalite.ui.dialogs.DialogHelper;
import de.schliweb.sambalite.ui.operations.FileOperations;
import de.schliweb.sambalite.ui.utils.LoadingIndicator;
import de.schliweb.sambalite.ui.utils.UIHelper;
import de.schliweb.sambalite.util.*;

import javax.inject.Inject;
import java.io.File;

/**
 * Activity for browsing files on an SMB server.
 */
public class FileBrowserActivity extends AppCompatActivity implements FileAdapter.OnFileClickListener, FileAdapter.OnFileOptionsClickListener {

    private static final String EXTRA_CONNECTION_ID = "extra_connection_id";
    @Inject
    ViewModelProvider.Factory viewModelFactory;
    // Modern Activity Result Launchers
    private ActivityResultLauncher<Intent> createFileLauncher;
    private ActivityResultLauncher<Intent> pickFileLauncher;
    private ActivityResultLauncher<Intent> createFolderLauncher;
    private ActivityResultLauncher<Intent> zipUploadLauncher;
    private ActivityResultLauncher<Intent> zipDownloadLauncher;
    private FileBrowserViewModel viewModel;
    private FileAdapter adapter;
    private SwipeRefreshLayout swipeRefreshLayout;
    private View emptyView;  // Changed from TextView to View (LinearLayout)
    private View progressBar;  // Already View, now LinearLayout
    private TextView currentPathView;

    private SmbFileItem fileToDownload;
    private LoadingIndicator loadingIndicator;
    private SmartErrorHandler errorHandler;

    // Background Service Integration for Multi-File Progress
    private SmbBackgroundService backgroundService;
    private ServiceConnection serviceConnection;
    private boolean isServiceBound = false;
    // Progress dialog for detailed download progress
    private AlertDialog progressDialog;
    private ProgressBar downloadProgressBar;
    private TextView progressMessage;
    private TextView progressPercentage;
    private TextView progressDetails;
    // Search progress dialog for search operations
    private AlertDialog searchProgressDialog;

    /**
     * Creates an intent to start this activity.
     *
     * @param context      The context to use
     * @param connectionId The ID of the connection to browse
     * @return The intent to start this activity
     */
    public static Intent createIntent(Context context, String connectionId) {
        Intent intent = new Intent(context, FileBrowserActivity.class);
        intent.putExtra(EXTRA_CONNECTION_ID, connectionId);
        return intent;
    }

    @Override
    protected void onResume() {
        super.onResume();
        LogUtils.d("FileBrowserActivity", "visibilityChanged oldVisibility=false newVisibility=true");

        // Bind to Background Service for Multi-File Progress
        bindToBackgroundService();
    }

    @Override
    protected void onPause() {
        super.onPause();
        LogUtils.d("FileBrowserActivity", "visibilityChanged oldVisibility=true newVisibility=false");

        // Unbind from Background Service
        unbindFromBackgroundService();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LogUtils.d("FileBrowserActivity", "onDestroy called - cleaning up dialogs");

        // Close all dialogs to prevent window leaks
        closeAllDialogs();

        // Cancel any ongoing operations to prevent callbacks after destruction
        if (viewModel != null) {
            viewModel.cancelUpload();
            viewModel.cancelDownload();
        }
    }

    /**
     * Closes all active dialogs to prevent window leaks.
     */
    private void closeAllDialogs() {
        try {
            if (progressDialog != null && progressDialog.isShowing()) {
                LogUtils.d("FileBrowserActivity", "Closing progress dialog in onDestroy");
                progressDialog.dismiss();
                progressDialog = null;
            }

            if (searchProgressDialog != null && searchProgressDialog.isShowing()) {
                LogUtils.d("FileBrowserActivity", "Closing search progress dialog in onDestroy");
                searchProgressDialog.dismiss();
                searchProgressDialog = null;
            }

            // Also hide loading indicator
            if (loadingIndicator != null) {
                loadingIndicator.hide();
            }
        } catch (Exception e) {
            LogUtils.w("FileBrowserActivity", "Error closing dialogs in onDestroy: " + e.getMessage());
        }
    }

    /**
     * Checks if the activity is safe for UI operations (not finishing or destroyed).
     *
     * @return true if safe for UI operations, false otherwise
     */
    private boolean isActivitySafe() {
        return !isFinishing() && !isDestroyed();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        LogUtils.d("FileBrowserActivity", "onCreate called");

        // Start performance tracking
        long startTime = System.currentTimeMillis();

        // Initialize loading indicator
        loadingIndicator = new LoadingIndicator(this);
        LogUtils.d("FileBrowserActivity", "Loading indicator initialized");

        // Initialize error handler
        errorHandler = ((SambaLiteApp) getApplication()).getErrorHandler();
        LogUtils.d("FileBrowserActivity", "Error handler initialized");

        // Initialize Activity Result Launchers
        initializeActivityResultLaunchers();

        // Get the Dagger component and inject dependencies
        AppComponent appComponent = ((SambaLiteApp) getApplication()).getAppComponent();
        appComponent.inject(this);
        LogUtils.d("FileBrowserActivity", "Dependencies injected");

        super.onCreate(savedInstanceState);

        // Configure edge-to-edge display for better landscape experience
        Window window = getWindow();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Modern API (Android 11+)
            WindowCompat.setDecorFitsSystemWindows(window, false);
            window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        } else {
            // Legacy API (Android 10 and below)
            window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            window.getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }

        setContentView(R.layout.activity_file_browser);
        LogUtils.d("FileBrowserActivity", "Content view set");

        // Set up Toolbar for great Material Design
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("File Browser");
        }
        LogUtils.d("FileBrowserActivity", "Toolbar set up");

        // Navigation is now handled by the Navigation-Bar
        LogUtils.d("FileBrowserActivity", "Using Navigation-Bar for navigation");

        // Initialize views
        RecyclerView recyclerView = findViewById(R.id.files_recycler_view);
        swipeRefreshLayout = findViewById(R.id.swipe_refresh);
        emptyView = findViewById(R.id.empty_state);  // Now LinearLayout instead of TextView
        progressBar = findViewById(R.id.loading_state);  // Now LinearLayout instead of View
        currentPathView = findViewById(R.id.current_path);
        LogUtils.d("FileBrowserActivity", "Views initialized");

        // Set up search button (moved to TopBar)
        findViewById(R.id.search_button).setOnClickListener(v -> {
            LogUtils.d("FileBrowserActivity", "Search button clicked");
            showSearchDialog();
        });
        LogUtils.d("FileBrowserActivity", "Search button set up");

        // Set up sort button (moved to TopBar)
        findViewById(R.id.sort_button).setOnClickListener(v -> {
            LogUtils.d("FileBrowserActivity", "Sort button clicked");
            showSortDialog();
        });
        LogUtils.d("FileBrowserActivity", "Sort button set up");

        // Set up upload button with extended options (Upload + ZIP Transfer)
        findViewById(R.id.fab).setOnClickListener(v -> {
            LogUtils.d("FileBrowserActivity", "Main FAB clicked - showing upload options");
            showUploadOptionsDialog();
        });
        LogUtils.d("FileBrowserActivity", "Main FAB set up");

        // Set up create folder button
        findViewById(R.id.fab_create_folder).setOnClickListener(v -> {
            LogUtils.d("FileBrowserActivity", "Create folder button clicked");
            showCreateFolderDialog();
        });
        LogUtils.d("FileBrowserActivity", "Create folder button set up");

        // Set up parent directory button for great navigation
        findViewById(R.id.parent_directory_button).setOnClickListener(v -> {
            LogUtils.d("FileBrowserActivity", "Parent directory button clicked");
            viewModel.navigateUp();
        });
        LogUtils.d("FileBrowserActivity", "Parent directory button set up");

        // Set up refresh button
        findViewById(R.id.refresh_button).setOnClickListener(v -> {
            LogUtils.d("FileBrowserActivity", "Refresh button clicked");
            viewModel.loadFiles();
        });
        LogUtils.d("FileBrowserActivity", "Refresh button set up");

        // Set up RecyclerView
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FileAdapter();
        adapter.setOnFileClickListener(this);
        adapter.setOnFileOptionsClickListener(this);
        recyclerView.setAdapter(adapter);

        // Long click functionality is now used instead of swipe gestures
        LogUtils.d("FileBrowserActivity", "Long click functionality set up");

        LogUtils.d("FileBrowserActivity", "RecyclerView and adapter set up");

        // Set up SwipeRefreshLayout
        swipeRefreshLayout.setOnRefreshListener(() -> {
            LogUtils.d("FileBrowserActivity", "Swipe refresh triggered");
            if (viewModel.isInSearchMode()) {
                // If in search mode, clear the search instead of refreshing
                LogUtils.d("FileBrowserActivity", "In search mode, clearing search");
                viewModel.clearSearch();
            } else {
                // Normal refresh behavior
                viewModel.loadFiles();
            }
        });
        LogUtils.d("FileBrowserActivity", "SwipeRefreshLayout set up");

        // Initialize ViewModel
        viewModel = new ViewModelProvider(this, viewModelFactory).get(FileBrowserViewModel.class);
        LogUtils.d("FileBrowserActivity", "ViewModel initialized");

        // Observe files
        viewModel.getFiles().observe(this, files -> {
            LogUtils.d("FileBrowserActivity", "Files updated: " + files.size() + " files");
            adapter.setFiles(files);
            adapter.setShowParentDirectory(viewModel.hasParentDirectory());

            // Update statistics card
            updateFileStatistics(files);

            // Show empty view if there are no files and no parent directory
            boolean isEmpty = files.isEmpty() && !viewModel.hasParentDirectory();
            LogUtils.d("FileBrowserActivity", "Directory is empty: " + isEmpty);
            emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
            recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        });
        LogUtils.d("FileBrowserActivity", "Files observer set up");

        // Observe loading state
        viewModel.isLoading().observe(this, isLoading -> {
            LogUtils.d("FileBrowserActivity", "Loading state changed: " + isLoading);
            swipeRefreshLayout.setRefreshing(isLoading);
            progressBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        });
        LogUtils.d("FileBrowserActivity", "Loading state observer set up");

        // Observe error messages with enhanced UI feedback
        viewModel.getErrorMessage().observe(this, errorMessage -> {
            if (errorMessage != null && !errorMessage.isEmpty()) {
                LogUtils.w("FileBrowserActivity", "Error message received: " + errorMessage);
                EnhancedUIUtils.showError(this, errorMessage);
            }
        });
        LogUtils.d("FileBrowserActivity", "Error message observer set up");

        // Observe current path
        viewModel.getCurrentPath().observe(this, path -> {
            LogUtils.d("FileBrowserActivity", "Current path updated: " + path);
            currentPathView.setText(path);

            // Update parent directory button state - great UX!
            View parentButton = findViewById(R.id.parent_directory_button);
            boolean hasParent = viewModel.hasParentDirectory();
            parentButton.setEnabled(hasParent);
            parentButton.setAlpha(hasParent ? 1.0f : 0.5f);
            LogUtils.d("FileBrowserActivity", "Parent directory button enabled: " + hasParent);
        });
        LogUtils.d("FileBrowserActivity", "Current path observer set up");

        // Observe search results
        viewModel.getSearchResults().observe(this, searchResults -> {
            LogUtils.d("FileBrowserActivity", "Search results updated: " + searchResults.size() + " results");
            if (viewModel.isInSearchMode()) {
                adapter.setFiles(searchResults);
                adapter.setShowParentDirectory(false); // Don't show parent directory in search results

                // Show empty view if there are no search results
                boolean isEmpty = searchResults.isEmpty();
                LogUtils.d("FileBrowserActivity", "Search results empty: " + isEmpty);
                // emptyView.setText(R.string.no_search_results); // Removed - static text in layout
                emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
                recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);

                // We're in search mode
                LogUtils.d("FileBrowserActivity", "In search mode");
            } else {
                // Not in search mode
                LogUtils.d("FileBrowserActivity", "Not in search mode");
                // emptyView.setText(R.string.empty_directory); // Removed - static text in layout
            }
        });
        LogUtils.d("FileBrowserActivity", "Search results observer set up");

        // Observe searching state with progress dialog
        viewModel.isSearching().observe(this, isSearching -> {
            LogUtils.d("FileBrowserActivity", "Searching state changed: " + isSearching);

            if (isSearching) {
                // Show search progress dialog instead of FAB
                showSearchProgressDialog();
            } else {
                // Hide search progress dialog
                hideSearchProgressDialog();
            }
        });
        LogUtils.d("FileBrowserActivity", "Searching state observer set up");

        // Get connection ID from intent
        String connectionId = getIntent().getStringExtra(EXTRA_CONNECTION_ID);
        if (connectionId == null) {
            LogUtils.e("FileBrowserActivity", "No connection ID specified in intent");
            EnhancedUIUtils.showError(this, "No connection specified");
            finish();
            return;
        }
        LogUtils.d("FileBrowserActivity", "Connection ID from intent: " + connectionId);

        // Load connection from MainViewModel
        MainViewModel mainViewModel = new ViewModelProvider(this, viewModelFactory).get(MainViewModel.class);
        LogUtils.d("FileBrowserActivity", "MainViewModel initialized to load connection");

        mainViewModel.getConnections().observe(this, connections -> {
            LogUtils.d("FileBrowserActivity", "Searching for connection with ID: " + connectionId + " among " + connections.size() + " connections");
            for (SmbConnection connection : connections) {
                if (connection.getId().equals(connectionId)) {
                    LogUtils.i("FileBrowserActivity", "Connection found: " + connection.getName());
                    viewModel.setConnection(connection);
                    return;
                }
            }

            // Connection not found
            LogUtils.e("FileBrowserActivity", "Connection not found with ID: " + connectionId);
            EnhancedUIUtils.showError(this, "Connection not found");
            finish();
        });
        LogUtils.d("FileBrowserActivity", "Connection observer set up");

        // Log performance metrics
        long endTime = System.currentTimeMillis();
        SimplePerformanceMonitor.startOperation("FileBrowserActivity.onCreate");
        SimplePerformanceMonitor.endOperation("FileBrowserActivity.onCreate");
        LogUtils.i("FileBrowserActivity", "Memory: " + SimplePerformanceMonitor.getMemoryInfo());

        LogUtils.i("FileBrowserActivity", "FileBrowserActivity initialized in " + (endTime - startTime) + "ms");
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        // Handle toolbar navigation
        if (item.getItemId() == android.R.id.home) {
            LogUtils.d("FileBrowserActivity", "Toolbar back button clicked");
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Shows the search dialog.
     */
    private void showSearchDialog() {
        DialogHelper.showSearchDialog(this, viewModel);
    }

    /**
     * Updates the file statistics card with current file/folder counts
     */
    private void updateFileStatistics(java.util.List<SmbFileItem> files) {
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

        LogUtils.d("FileBrowserActivity", "Statistics updated: " + fileCount + " files, " + folderCount + " folders");
    }

    @Override
    public void onFileClick(SmbFileItem file) {
        LogUtils.d("FileBrowserActivity", "File clicked: " + file.getName() + ", isDirectory: " + file.isDirectory());
        // Record timing
        SimplePerformanceMonitor.startOperation("FileBrowserActivity.fileClick");
        if (file.isDirectory()) {
            LogUtils.d("FileBrowserActivity", "Navigating to directory: " + file.getName());
            viewModel.navigateToDirectory(file);
            EnhancedUIUtils.showInfo(this, "Opening " + file.getName());
        } else {
            LogUtils.d("FileBrowserActivity", "Showing options for file: " + file.getName());
            String fileInfo = EnhancedFileUtils.getFileType(file.getName()).name() + " • " + EnhancedFileUtils.formatFileSize(file.getSize());
            EnhancedUIUtils.showInfo(this, fileInfo);
            showFileOptionsDialog(file); // Show options dialog
        }


        SimplePerformanceMonitor.endOperation("FileBrowserActivity.fileClick");
    }

    /**
     * Shows a dialog with options for a file or folder.
     * For files, only the download option is shown when clicked.
     * For files that are long-clicked, all options (download, rename, delete) are shown.
     * For all files and folders, all options (download, rename, delete) are shown.
     *
     * @param file The file or folder to show options for
     */
    private void showFileOptionsDialog(SmbFileItem file) {
        LogUtils.d("FileBrowserActivity", "Showing options dialog for: " + file.getName() + ", isDirectory: " + file.isDirectory());

        // Show all options for all files and folders
        String[] options = new String[]{getString(R.string.download), getString(R.string.rename), getString(R.string.delete_file)};

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(file.getName()).setItems(options, (dialog, which) -> {
            // Handle all options for all files and folders
            switch (which) {
                case 0: // Download
                    LogUtils.d("FileBrowserActivity", "Download option selected for: " + file.getName());
                    downloadFile(file);
                    break;
                case 1: // Rename
                    LogUtils.d("FileBrowserActivity", "Rename option selected for: " + file.getName());
                    showRenameFileDialog(file);
                    break;
                case 2: // Delete
                    LogUtils.d("FileBrowserActivity", "Delete option selected for: " + file.getName());
                    showDeleteFileConfirmationDialog(file);
                    break;
            }
        }).setNegativeButton(R.string.cancel, (dialog, which) -> {
            LogUtils.d("FileBrowserActivity", "File options dialog cancelled");
            dialog.dismiss();
        }).show();
    }

    /**
     * Shows a dialog to rename a file.
     */
    private void showRenameFileDialog(SmbFileItem file) {
        DialogHelper.showRenameDialog(this, file, createRenameCallback(file), viewModel);
    }

    /**
     * Shows a beautiful confirmation dialog to delete a file.
     */
    private void showDeleteFileConfirmationDialog(SmbFileItem file) {
        LogUtils.d("FileBrowserActivity", "Showing delete confirmation dialog for file: " + file.getName());

        UIHelper.showConfirmation(this, R.string.delete_file_dialog_title, getString(R.string.confirm_delete_file, file.getName()), () -> deleteFileWithFeedback(file));
    }

    private void deleteFileWithFeedback(SmbFileItem file) {
        LogUtils.d("FileBrowserActivity", "Delete confirmed for file: " + file.getName());

        if (!isActivitySafe()) return;

        loadingIndicator.show(getString(R.string.delete_file), true, null);

        viewModel.deleteFile(file, (success, message) -> runOnUiThread(() -> {
            if (!isActivitySafe()) return;

            loadingIndicator.hide();
            if (success) {
                UIHelper.showSuccess(this, R.string.delete_success);
            } else {
                UIHelper.showError(this, R.string.delete_error, message);
            }
        }));
    }

    /**
     * Initiates the download of a file or folder.
     */
    private void downloadFile(SmbFileItem file) {
        LogUtils.d("FileBrowserActivity", "Initiating download for: " + file.getName() + ", type: " + (file.isDirectory() ? "directory" : "file") + (file.isFile() ? ", size: " + file.getSize() + " bytes" : ""));

        if (file.isDirectory()) {
            // For directories, we need to create a folder picker
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            LogUtils.d("FileBrowserActivity", "Created folder picker intent for folder: " + file.getName());

            // Store the file to download in a field for use in onActivityResult
            fileToDownload = file;

            // Start the folder picker activity
            LogUtils.d("FileBrowserActivity", "Starting folder picker activity for download");
            createFolderLauncher.launch(intent);
        } else {
            // For files, create a file picker
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*"); // Allow any file type
            intent.putExtra(Intent.EXTRA_TITLE, file.getName()); // Suggest the file name
            LogUtils.d("FileBrowserActivity", "Created file picker intent with suggested name: " + file.getName());

            // Store the file to download in a field for use in onActivityResult
            fileToDownload = file;

            // Start the file picker activity
            LogUtils.d("FileBrowserActivity", "Starting file picker activity for download");
            createFileLauncher.launch(intent);
        }
    }

    private void hideKeyboardAndClearFocus() {
        View currentFocus = getCurrentFocus();
        if (currentFocus != null) {
            currentFocus.clearFocus();
        }
        KeyboardUtils.hideKeyboard(this);
    }

    /**
     * Beautiful file operation handlers with elegant pattern.
     */
    private void handleFileDownload(Uri uri) {
        if (fileToDownload == null) return;

        executeFileOperation(() -> {
            File tempFile = File.createTempFile("download", ".tmp", getCacheDir());

            // Use Progress-Callback for File-Tracking
            FileBrowserViewModel.ProgressCallback progressCallback = createUIProgressCallback();
            viewModel.downloadFile(fileToDownload, tempFile, createDownloadCallback(tempFile, uri), progressCallback);
        }, R.string.downloading);
    }

    private void handleFolderDownload(Uri uri) {
        if (fileToDownload == null || !fileToDownload.isDirectory()) return;

        executeFileOperation(() -> {
            DocumentFile destFolder = createDestinationFolder(uri);
            File tempFolder = createTempFolder();

            // Use Progress-Callback for Multi-File-Tracking
            FileBrowserViewModel.ProgressCallback progressCallback = createUIProgressCallback();
            viewModel.downloadFolder(fileToDownload, tempFolder, createFolderDownloadCallback(tempFolder, destFolder), progressCallback);
        }, R.string.downloading);
    }

    private void handleFileUpload(Uri uri) {
        executeFileOperation(() -> {
            String fileName = getFileNameFromUri(uri);
            if (fileName == null) fileName = "uploaded_file_" + System.currentTimeMillis();

            File tempFile = File.createTempFile("upload", ".tmp", getCacheDir());
            FileOperations.copyUriToFile(uri, tempFile, this);
            String remotePath = buildRemotePath(fileName);

            viewModel.uploadFile(tempFile, remotePath, createUploadCallback(), (existingFileName, confirmAction, cancelAction) -> runOnUiThread(() -> {
                if (!isActivitySafe()) {
                    cancelAction.run();
                    return;
                }

                UIHelper.showConfirmation(this, getString(R.string.file_exists), getString(R.string.file_exists_message, existingFileName), getString(R.string.overwrite), getString(R.string.cancel), confirmAction, cancelAction);
            }), fileName);
        }, R.string.uploading, "upload");
    }

    private void executeFileOperation(FileOperation operation, int progressMessageRes) {
        executeFileOperation(operation, progressMessageRes, "download");
    }

    private void executeFileOperation(FileOperation operation, int progressMessageRes, String operationType) {
        // Always show LoadingIndicator for immediate user feedback
        LogUtils.d("FileBrowserActivity", "Starting file operation with loading indicator");

        if (!isActivitySafe()) return;

        loadingIndicator.show(getString(progressMessageRes), true, () -> {
            LogUtils.d("FileBrowserActivity", "User requested " + operationType + " cancellation");
            // Update the UI to show that cancellation is running
            if (isActivitySafe()) {
                loadingIndicator.updateMessage("Abbruch läuft...");
                loadingIndicator.setCancelButtonEnabled(false); // Disable Cancel-Button during cancellation
            }

            // Call appropriate cancellation method based on operation type
            if (operationType.equals("upload")) {
                viewModel.cancelUpload();
            } else {
                viewModel.cancelDownload();
            }
        });

        // Additionally use Background Service for notification support
        if (isServiceBound && backgroundService != null) {
            LogUtils.d("FileBrowserActivity", "Also starting background service for notifications");
            backgroundService.startOperation(getString(progressMessageRes));
        }

        try {
            operation.execute();

            // Erfolgreicher Abschluss - Both UI dialog and background service
            if (isActivitySafe()) {
                loadingIndicator.hide();
            }
            hideDetailedProgressDialog();
            if (isServiceBound && backgroundService != null) {
                backgroundService.finishOperation(getString(progressMessageRes), true);
            }

        } catch (Exception e) {
            // Record error first
            errorHandler.recordError(e, "FileBrowserActivity.executeOperationWithProgress", SmartErrorHandler.ErrorSeverity.HIGH);

            // Cleanup on error or cancellation - Both UI dialog and background service
            if (isActivitySafe()) {
                loadingIndicator.hide();
            }
            hideDetailedProgressDialog();
            if (isServiceBound && backgroundService != null) {
                backgroundService.finishOperation(getString(progressMessageRes), false);
            }

            // Special handling for user cancellation
            if (e.getMessage() != null && e.getMessage().contains("cancelled by user")) {
                LogUtils.i("FileBrowserActivity", "Operation was cancelled by user");
                if (isActivitySafe()) {
                    String operationName = operationType.equals("upload") ? "Upload" : "Download";
                    UIHelper.showInfo(this, operationName + " wurde abgebrochen");
                }
            } else {
                if (isActivitySafe()) {
                    int errorResource = operationType.equals("upload") ? R.string.upload_error : R.string.download_error;
                    UIHelper.showError(this, errorResource, e.getMessage());
                }
            }
        }
    }

    private DocumentFile createDestinationFolder(Uri uri) throws Exception {
        DocumentFile documentFile = DocumentFile.fromTreeUri(this, uri);
        if (documentFile == null || !documentFile.exists() || !documentFile.isDirectory()) {
            throw new Exception("Invalid destination folder");
        }

        DocumentFile subFolder = documentFile.createDirectory(fileToDownload.getName());
        if (subFolder == null) {
            throw new Exception("Failed to create folder");
        }
        return subFolder;
    }

    private File createTempFolder() throws Exception {
        File tempFolder = new File(getCacheDir(), "download_" + System.currentTimeMillis());
        if (!tempFolder.mkdirs()) {
            throw new Exception("Failed to create temporary folder");
        }
        return tempFolder;
    }

    /**
     * Beautiful ZIP operation handlers with elegant builder pattern.
     */
    private void handleZipUpload(Uri folderUri) {
        DocumentFile docFolder = DocumentFile.fromTreeUri(this, folderUri);
        String folderName = getDocumentFileName(docFolder, "folder");

        // For uploads, we use the detailed progress dialog instead of the loading indicator
        setZipButtonsEnabled(false);

        // Create file exists callback for handling existing ZIP files
        FileBrowserViewModel.FileExistsCallback fileExistsCallback = new FileBrowserViewModel.FileExistsCallback() {
            @Override
            public void onFileExists(String fileName, Runnable confirmAction, Runnable cancelAction) {
                new MaterialAlertDialogBuilder(FileBrowserActivity.this).setTitle(R.string.file_exists_title).setMessage(getString(R.string.file_exists_message, fileName)).setPositiveButton(R.string.overwrite, (dialog, which) -> {
                    // User confirmed overwrite
                    confirmAction.run();
                }).setNegativeButton(R.string.cancel, (dialog, which) -> {
                    // User cancelled - execute cancel action (includes cleanup)
                    cancelAction.run();
                    // Reset UI state
                    setZipButtonsEnabled(true);
                    hideDetailedProgressDialog();
                }).setCancelable(false).show();
            }
        };

        viewModel.uploadFolderAsZipFromUri(folderUri, folderName + ".zip", createZipUploadCallback(), fileExistsCallback);
    }

    /**
     * Handles uploading folder contents as individual files.
     */
    private void handleFolderContentsUpload(Uri folderUri) {
        DocumentFile docFolder = DocumentFile.fromTreeUri(this, folderUri);
        String folderName = getDocumentFileName(docFolder, "folder");

        LogUtils.d("FileBrowserActivity", "Starting folder contents upload: " + folderName);

        // Use detailed progress dialog for folder contents upload
        setZipButtonsEnabled(false);
        viewModel.uploadFolderContentsFromUri(folderUri, createFolderContentsUploadCallback(), createFileExistsCallback());
    }

    private String getDocumentFileName(DocumentFile docFile, String fallback) {
        return docFile != null ? docFile.getName() : fallback;
    }

    private FileBrowserViewModel.UploadCallback createZipUploadCallback() {
        return new FileBrowserViewModel.UploadCallback() {
            @Override
            public void onProgress(String status, int percentage) {
                runOnUiThread(() -> {
                    if (!isActivitySafe()) return;

                    if (progressDialog == null || !progressDialog.isShowing()) {
                        showDetailedUploadProgressDialog("ZIP Upload", status);
                    }
                    updateDetailedProgress(percentage, status, "");
                });
            }

            @Override
            public void onResult(boolean success, String message) {
                runOnUiThread(() -> {
                    if (!isActivitySafe()) return;

                    hideDetailedProgressDialog();
                    setZipButtonsEnabled(true);

                    if (success) {
                        UIHelper.showSuccess(FileBrowserActivity.this, R.string.zip_upload_success);
                        viewModel.loadFiles();
                    } else {
                        UIHelper.showError(FileBrowserActivity.this, R.string.zip_upload_error, message);
                    }
                });
            }
        };
    }

    /**
     * Creates callback for robust folder contents upload with detailed progress and error handling.
     */
    private FileBrowserViewModel.UploadCallback createFolderContentsUploadCallback() {
        return new FileBrowserViewModel.UploadCallback() {
            @Override
            public void onProgress(String status, int percentage) {
                runOnUiThread(() -> {
                    if (!isActivitySafe()) return;

                    if (progressDialog == null || !progressDialog.isShowing()) {
                        showDetailedUploadProgressDialog("Folder Contents Upload", status);
                    }
                    updateDetailedProgress(percentage, status, "");
                });
            }

            @Override
            public void onResult(boolean success, String message) {
                runOnUiThread(() -> {
                    if (!isActivitySafe()) return;

                    hideDetailedProgressDialog();
                    setZipButtonsEnabled(true);

                    if (success) {
                        // Show success with file count information
                        UIHelper.showSuccess(FileBrowserActivity.this, R.string.folder_contents_upload_success);
                        viewModel.loadFiles(); // Refresh to show uploaded files
                    } else {
                        // Show detailed error information
                        LogUtils.e("FileBrowserActivity", "Folder contents upload failed: " + message);

                        // Determine if it was a partial failure or complete failure
                        if (message.contains("incomplete") || message.contains("of")) {
                            // Partial failure - show detailed error with warning tone
                            UIHelper.showError(FileBrowserActivity.this, R.string.upload_incomplete_warning, message + "\n\nSome files were uploaded successfully. Check the server and retry if needed.");
                        } else {
                            // Complete failure
                            UIHelper.showError(FileBrowserActivity.this, R.string.folder_contents_upload_error, message);
                        }

                        // Still refresh the file list to show any files that were uploaded
                        viewModel.loadFiles();
                    }
                });
            }
        };
    }

    /**
     * Creates a FileExistsCallback for handling individual file conflicts during folder contents upload.
     */
    private FileBrowserViewModel.FileExistsCallback createFileExistsCallback() {
        return new FileBrowserViewModel.FileExistsCallback() {
            @Override
            public void onFileExists(String fileName, Runnable confirmAction, Runnable cancelAction) {
                runOnUiThread(() -> {
                    // Check if activity is still active to prevent window leaks
                    if (!isActivitySafe()) {
                        LogUtils.w("FileBrowserActivity", "Activity is finishing/destroyed, cancelling file exists dialog for: " + fileName);
                        cancelAction.run();
                        return;
                    }

                    try {
                        new MaterialAlertDialogBuilder(FileBrowserActivity.this).setTitle(R.string.file_exists_title).setMessage(getString(R.string.file_exists_individual_message, fileName)).setPositiveButton(R.string.overwrite, (dialog, which) -> {
                            // User confirmed overwrite
                            LogUtils.d("FileBrowserActivity", "User confirmed overwrite for file: " + fileName);
                            confirmAction.run();
                        }).setNegativeButton(R.string.skip_file, (dialog, which) -> {
                            // User chose to skip this file (execute cancel action for this specific file)
                            LogUtils.d("FileBrowserActivity", "User chose to skip file: " + fileName);
                            cancelAction.run();
                        }).setCancelable(false).show();
                    } catch (Exception e) {
                        LogUtils.e("FileBrowserActivity", "Error showing file exists dialog: " + e.getMessage());
                        cancelAction.run();
                    }
                });
            }
        };
    }

    /**
     * Beautiful callback creators for elegant async operations.
     */
    private FileBrowserViewModel.DownloadCallback createDownloadCallback(File tempFile, Uri uri) {
        return new FileBrowserViewModel.DownloadCallback() {
            @Override
            public void onProgress(String status, int percentage) {
                runOnUiThread(() -> {
                    if (!isActivitySafe()) return;

                    if (progressDialog == null || !progressDialog.isShowing()) {
                        showDetailedDownloadProgressDialog("File Download", status);
                    }
                    updateDetailedProgress(percentage, status, fileToDownload != null ? fileToDownload.getName() : "");
                });
            }

            @Override
            public void onResult(boolean success, String message) {
                runOnUiThread(() -> {
                    if (!isActivitySafe()) return;

                    // End Background Service Operation
                    if (isServiceBound && backgroundService != null) {
                        backgroundService.finishOperation("Download", success);
                    } else {
                        loadingIndicator.hide();
                    }

                    // Always hide detailed progress dialog
                    hideDetailedProgressDialog();

                    if (success) {
                        try {
                            FileOperations.copyFileToUri(tempFile, uri, FileBrowserActivity.this);
                            UIHelper.showSuccess(FileBrowserActivity.this, R.string.download_success);
                        } catch (Exception e) {
                            errorHandler.recordError(e, "FileBrowserActivity.downloadFile.copyToUri", SmartErrorHandler.ErrorSeverity.HIGH);
                            UIHelper.showError(FileBrowserActivity.this, R.string.download_error, e.getMessage());
                        } finally {
                            tempFile.delete();
                        }
                    } else {
                        UIHelper.showError(FileBrowserActivity.this, R.string.download_error, message);
                    }
                });
            }
        };
    }

    private FileBrowserViewModel.DownloadCallback createFolderDownloadCallback(File tempFolder, DocumentFile destFolder) {
        return new FileBrowserViewModel.DownloadCallback() {
            @Override
            public void onProgress(String status, int percentage) {
                runOnUiThread(() -> {
                    if (!isActivitySafe()) return;

                    if (progressDialog == null || !progressDialog.isShowing()) {
                        showDetailedDownloadProgressDialog("Folder Download", status);
                    }
                    updateDetailedProgress(percentage, status, fileToDownload != null ? fileToDownload.getName() : "");
                });
            }

            @Override
            public void onResult(boolean success, String message) {
                runOnUiThread(() -> {
                    if (!isActivitySafe()) return;

                    // End Background Service Operation
                    if (isServiceBound && backgroundService != null) {
                        backgroundService.finishOperation("Folder Download", success);
                    } else {
                        loadingIndicator.hide();
                    }

                    // Always hide detailed progress dialog
                    hideDetailedProgressDialog();

                    if (success) {
                        try {
                            FileOperations.copyFolderToDocumentFile(tempFolder, destFolder, FileBrowserActivity.this);
                            UIHelper.showSuccess(FileBrowserActivity.this, R.string.download_success);
                        } catch (Exception e) {
                            UIHelper.showError(FileBrowserActivity.this, R.string.download_error, e.getMessage());
                        } finally {
                            FileOperations.deleteRecursive(tempFolder);
                        }
                    } else {
                        UIHelper.showError(FileBrowserActivity.this, R.string.download_error, message);
                    }
                });
            }
        };
    }

    private FileBrowserViewModel.UploadCallback createUploadCallback() {
        return new FileBrowserViewModel.UploadCallback() {
            @Override
            public void onProgress(String status, int percentage) {
                runOnUiThread(() -> {
                    if (!isActivitySafe()) return;

                    if (progressDialog == null || !progressDialog.isShowing()) {
                        showDetailedUploadProgressDialog("File Upload", status);
                    }
                    updateDetailedProgress(percentage, status, "");
                });
            }

            @Override
            public void onResult(boolean success, String message) {
                runOnUiThread(() -> {
                    if (!isActivitySafe()) return;

                    loadingIndicator.hide();
                    hideDetailedProgressDialog();
                    if (success) {
                        // Add success animation to the file list
                        RecyclerView recyclerView = findViewById(R.id.files_recycler_view);
                        if (recyclerView != null) {
                            AnimationHelper.pulseSuccess(recyclerView);
                        }

                        UIHelper.showSuccess(FileBrowserActivity.this, R.string.upload_success);
                        viewModel.loadFiles(); // Refresh
                    } else {
                        UIHelper.showError(FileBrowserActivity.this, R.string.upload_error, message);
                    }
                });
            }
        };
    }

    /**
     * Beautiful utility methods with elegant implementation.
     */
    private String buildRemotePath(String fileName) {
        String currentPath = viewModel.getCurrentPath().getValue();
        return (currentPath == null || currentPath.equals("root")) ? fileName : currentPath + "/" + fileName;
    }

    /**
     * Gets the file name from a content URI.
     *
     * @param uri The content URI
     * @return The file name, or null if it couldn't be determined
     */
    private String getFileNameFromUri(Uri uri) {
        LogUtils.d("FileBrowserActivity", "Getting file name from URI: " + uri);
        String result = null;
        if (uri.getScheme().equals("content")) {
            LogUtils.d("FileBrowserActivity", "URI scheme is content, querying content resolver");
            try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        result = cursor.getString(nameIndex);
                        LogUtils.d("FileBrowserActivity", "File name from content resolver: " + result);
                    } else {
                        LogUtils.w("FileBrowserActivity", "DISPLAY_NAME column not found in cursor");
                    }
                } else {
                    LogUtils.w("FileBrowserActivity", "Cursor is null or empty");
                }
            } catch (Exception e) {
                LogUtils.e("FileBrowserActivity", "Error querying content resolver: " + e.getMessage());
                // Ignore
            }
        }
        if (result == null) {
            LogUtils.d("FileBrowserActivity", "Falling back to URI path for file name");
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
            LogUtils.d("FileBrowserActivity", "File name from URI path: " + result);
        }
        return result;
    }

    @Override
    public void onParentDirectoryClick() {
        LogUtils.d("FileBrowserActivity", "Parent directory clicked");
        viewModel.navigateUp();
    }

    /**
     * Simplified callback implementations using lambda expressions.
     */

    @Override
    public void onFileOptionsClick(SmbFileItem file) {
        LogUtils.d("FileBrowserActivity", "File options clicked: " + file.getName() + ", isDirectory: " + file.isDirectory());
        // Show options dialog with all options
        showFileOptionsDialog(file);
    }

    /**
     * Initiates the file selection process for uploading.
     */
    private void selectFileToUpload() {
        LogUtils.d("FileBrowserActivity", "Initiating file selection for upload");
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*"); // Allow any file type
        LogUtils.d("FileBrowserActivity", "Starting file picker activity for upload");
        pickFileLauncher.launch(intent);
    }

    private void showCreateFolderDialog() {
        DialogHelper.showCreateFolderDialog(this, createFolderCallback(), viewModel);
    }

    /**
     * Shows the sort dialog.
     */
    private void showSortDialog() {
        LogUtils.d("FileBrowserActivity", "Showing sort dialog");

        // Inflate the dialog layout
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_sort, null);
        LogUtils.d("FileBrowserActivity", "Sort dialog view inflated");

        // Get the sort type radio group
        RadioGroup sortTypeRadioGroup = dialogView.findViewById(R.id.sort_type_radio_group);

        // Get the directories first checkbox
        CheckBox directoriesFirstCheckbox = dialogView.findViewById(R.id.directories_first_checkbox);

        // Set the initial state based on the current settings
        FileBrowserViewModel.SortOption currentSortOption = viewModel.getSortOption().getValue();
        Boolean directoriesFirst = viewModel.getDirectoriesFirst().getValue();

        if (currentSortOption != null) {
            switch (currentSortOption) {
                case NAME:
                    sortTypeRadioGroup.check(R.id.radio_name);
                    break;
                case DATE:
                    sortTypeRadioGroup.check(R.id.radio_date);
                    break;
                case SIZE:
                    sortTypeRadioGroup.check(R.id.radio_size);
                    break;
            }
        }

        directoriesFirstCheckbox.setChecked(directoriesFirst != null ? directoriesFirst : true);

        // Create the dialog
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(R.string.sort_dialog_title).setView(dialogView).setPositiveButton(R.string.sort, (dialog, which) -> {
            LogUtils.d("FileBrowserActivity", "Sort dialog confirmed");

            // Get the selected sort option
            FileBrowserViewModel.SortOption sortOption;
            int selectedRadioButtonId = sortTypeRadioGroup.getCheckedRadioButtonId();
            if (selectedRadioButtonId == R.id.radio_date) {
                sortOption = FileBrowserViewModel.SortOption.DATE;
            } else if (selectedRadioButtonId == R.id.radio_size) {
                sortOption = FileBrowserViewModel.SortOption.SIZE;
            } else {
                sortOption = FileBrowserViewModel.SortOption.NAME;
            }
            LogUtils.d("FileBrowserActivity", "Selected sort option: " + sortOption);

            // Get the directories first option
            boolean dirsFirst = directoriesFirstCheckbox.isChecked();
            LogUtils.d("FileBrowserActivity", "Directories first: " + dirsFirst);

            // Apply the sorting options
            viewModel.setSortOption(sortOption);
            viewModel.setDirectoriesFirst(dirsFirst);
        }).setNegativeButton(R.string.cancel, (dialog, which) -> {
            LogUtils.d("FileBrowserActivity", "Sort dialog cancelled");
            dialog.dismiss();
        });

        // Create and show the dialog
        AlertDialog dialog = builder.create();
        dialog.show();
        LogUtils.d("FileBrowserActivity", "Sort dialog shown");
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            View v = getCurrentFocus();
            if (v instanceof com.google.android.material.textfield.TextInputEditText) {
                LogUtils.d("FileBrowserActivity", "Touch event on TextInputEditText");
                // Check if the touch was outside the focused text field
                float x = event.getRawX();
                float y = event.getRawY();
                int[] location = new int[2];
                v.getLocationOnScreen(location);

                if (x < location[0] || x > location[0] + v.getWidth() || y < location[1] || y > location[1] + v.getHeight()) {
                    LogUtils.d("FileBrowserActivity", "Touch outside of text field, hiding keyboard");
                    // Touch was outside the text field, hide keyboard
                    // Clear focus first to ensure proper IME callback handling
                    v.clearFocus();
                    KeyboardUtils.hideKeyboard(this);
                }
            }
        }
        return super.dispatchTouchEvent(event);
    }

    /**
     * Beautiful and concise callback implementations with new UIHelper features.
     */
    private FileBrowserViewModel.RenameFileCallback createRenameCallback(SmbFileItem file) {
        return (success, message) -> runOnUiThread(() -> {
            if (success) {
                // Use new fluent builder for success with action
                UIHelper.with(this).message(R.string.rename_success).success().action(R.string.undo, () -> {
                    // Potential undo functionality
                    UIHelper.showInfo(this, "Undo not implemented yet");
                }).show();
            } else {
                UIHelper.showError(this, R.string.rename_error, message);
            }
        });
    }

    private FileBrowserViewModel.CreateFolderCallback createFolderCallback() {
        return (success, message) -> runOnUiThread(() -> {
            if (success) {
                // Add success animation to the file list
                RecyclerView recyclerView = findViewById(R.id.files_recycler_view);
                if (recyclerView != null) {
                    AnimationHelper.pulseSuccess(recyclerView);
                }

                // Use builder pattern for enhanced success message
                UIHelper.with(this).message(R.string.folder_created).success().duration(com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show();
            } else {
                UIHelper.showError(this, R.string.folder_creation_error, message);
            }
        });
    }

    /**
     * Shows a dialog for ZIP transfer (Upload/Download of folders as ZIP).
     */
    private void showZipTransferDialog() {
        String[] options = new String[]{getString(R.string.zip_upload_folder), getString(R.string.zip_download_folder)};
        new MaterialAlertDialogBuilder(this).setTitle(R.string.zip_transfer_title).setItems(options, (dialog, which) -> {
            if (which == 0) {
                selectFolderToZipUpload();
            } else if (which == 1) {
                selectZipToDownloadAndUnpack();
            }
        }).setNegativeButton(R.string.cancel, null).show();
    }

    /**
     * Initiates folder selection for ZIP upload.
     */
    private void selectFolderToZipUpload() {
        LogUtils.d("FileBrowserActivity", "Selecting folder for ZIP upload");
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        zipUploadLauncher.launch(intent); // Modern Activity Result API for ZIP upload
    }

    /**
     * Initiates folder selection for folder contents upload.
     */
    private void selectZipToDownloadAndUnpack() {
        LogUtils.d("FileBrowserActivity", "Selecting folder for folder contents upload");
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        zipDownloadLauncher.launch(intent); // Modern Activity Result API for folder contents upload
    }

    private void setZipButtonsEnabled(boolean enabled) {
        // Note: fab_zip_transfer not available in current layout
        // Button state management can be added when UI is updated
        LogUtils.d("FileBrowserActivity", "ZIP buttons enabled state: " + enabled);
    }

    private void showUploadOptionsDialog() {
        LogUtils.d("FileBrowserActivity", "Showing upload options dialog");

        String[] options = {"Upload File", "Upload Folder"};

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle("Upload Options").setItems(options, (dialog, which) -> {
            switch (which) {
                case 0:
                    LogUtils.d("FileBrowserActivity", "User selected upload file");
                    selectFileToUpload();
                    break;
                case 1:
                    LogUtils.d("FileBrowserActivity", "User selected upload folder");
                    showZipTransferDialog();
                    break;
            }
        }).setNegativeButton("Cancel", (dialog, which) -> {
            LogUtils.d("FileBrowserActivity", "Upload options dialog cancelled");
            dialog.dismiss();
        });

        builder.create().show();
    }

    /**
     * Initialize modern Activity Result Launchers to replace deprecated startActivityForResult
     */
    private void initializeActivityResultLaunchers() {
        createFileLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> handleDocumentResult(result, "create_file"));

        pickFileLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> handleDocumentResult(result, "pick_file"));

        createFolderLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> handleDocumentResult(result, "create_folder"));

        zipUploadLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> handleDocumentResult(result, "zip_upload"));

        zipDownloadLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> handleDocumentResult(result, "zip_download"));
    }

    /**
     * Handle results from modern Activity Result API
     */
    private void handleDocumentResult(ActivityResult result, String operation) {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            Uri uri = result.getData().getData();
            if (uri != null) {
                switch (operation) {
                    case "pick_file":
                        handlePickFileResult(uri);
                        break;
                    case "create_file":
                        handleCreateFileResult(uri);
                        break;
                    case "create_folder":
                        handleCreateFolderResult(uri);
                        break;
                    case "zip_upload":
                        handleZipUploadResult(uri);
                        break;
                    case "zip_download":
                        handleZipDownloadResult(uri);
                        break;
                }
            }
        }
    }

    /**
     * Handler methods for different document operations
     */
    private void handlePickFileResult(Uri uri) {
        // Clean up UI state first
        hideKeyboardAndClearFocus();
        handleFileUpload(uri);
    }

    private void handleCreateFileResult(Uri uri) {
        // Clean up UI state first
        hideKeyboardAndClearFocus();
        handleFileDownload(uri);
    }

    private void handleCreateFolderResult(Uri uri) {
        // Clean up UI state first
        hideKeyboardAndClearFocus();
        handleFolderDownload(uri);
    }

    private void handleZipUploadResult(Uri uri) {
        // Clean up UI state first
        hideKeyboardAndClearFocus();
        handleZipUpload(uri);
    }

    private void handleZipDownloadResult(Uri uri) {
        // Clean up UI state first
        hideKeyboardAndClearFocus();
        handleFolderContentsUpload(uri); // Upload folder contents as individual files
    }

    /**
     * Background Service Integration for Multi-File Progress Tracking
     */

    private void bindToBackgroundService() {
        if (!isServiceBound) {
            serviceConnection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    LogUtils.d("FileBrowserActivity", "Background service connected");
                    SmbBackgroundService.LocalBinder binder = (SmbBackgroundService.LocalBinder) service;
                    backgroundService = binder.getService();
                    isServiceBound = true;
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    LogUtils.d("FileBrowserActivity", "Background service disconnected");
                    backgroundService = null;
                    isServiceBound = false;
                }
            };

            Intent serviceIntent = new Intent(this, SmbBackgroundService.class);
            startService(serviceIntent); // Start service first
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        }
    }

    private void unbindFromBackgroundService() {
        if (isServiceBound && serviceConnection != null) {
            LogUtils.d("FileBrowserActivity", "Unbinding from background service");
            unbindService(serviceConnection);
            isServiceBound = false;
            backgroundService = null;
        }
    }

    /**
     * Show progress directly in the UI instead of just hourglass
     */
    private void showProgressInUI(String operationName, String progressText) {
        runOnUiThread(() -> {
            if (loadingIndicator.isShowing()) {
                // Update the loading message with progress
                loadingIndicator.updateMessage(progressText);
                LogUtils.d("FileBrowserActivity", "Progress updated in UI: " + progressText);
            }
        });
    }

    /**
     * Create Progress-Callback for direct UI-Updates
     */
    private FileBrowserViewModel.ProgressCallback createUIProgressCallback() {
        return new FileBrowserViewModel.ProgressCallback() {
            @Override
            public void updateFileProgress(int currentFile, int totalFiles, String currentFileName) {
                int percentage = totalFiles > 0 ? ((currentFile * 100) / totalFiles) : 0;
                String progressText = currentFile + " of " + totalFiles + " files";

                // Show detailed progress dialog for file operations
                if (progressDialog == null || !progressDialog.isShowing()) {
                    showDetailedProgressDialog("Multi-File Download", progressText);
                } else {
                    updateDetailedProgress(percentage, progressText, currentFileName);
                }

                // Also forward to Background Service
                if (isServiceBound && backgroundService != null) {
                    backgroundService.updateFileProgress("Multi-File Download", currentFile, totalFiles, currentFileName);
                }
            }

            @Override
            public void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
                int percentage = totalBytes > 0 ? (int) ((currentBytes * 100) / totalBytes) : 0;
                String progressText = EnhancedFileUtils.formatFileSize(currentBytes) + " / " + EnhancedFileUtils.formatFileSize(totalBytes);

                // Show detailed progress dialog for byte progress
                if (progressDialog == null || !progressDialog.isShowing()) {
                    showDetailedProgressDialog("File Download", progressText);
                } else {
                    updateDetailedProgress(percentage, progressText, fileName);
                }

                // Also forward to Background Service
                if (isServiceBound && backgroundService != null) {
                    backgroundService.updateBytesProgress("File Download", currentBytes, totalBytes, fileName);
                }
            }

            @Override
            public void updateProgress(String progressInfo) {
                showProgressInUI("Progress", progressInfo);

                // Also forward to Background Service
                if (isServiceBound && backgroundService != null) {
                    backgroundService.updateOperationProgress("Progress", progressInfo);
                }
            }
        };
    }

    /**
     * Shows a detailed progress dialog with progress bar and percentage.
     */
    private void showDetailedProgressDialog(String title, String message) {
        runOnUiThread(() -> {
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }

            // Inflate custom progress dialog layout
            View dialogView = getLayoutInflater().inflate(R.layout.dialog_progress, null);

            TextView titleView = dialogView.findViewById(R.id.progress_title);
            progressMessage = dialogView.findViewById(R.id.progress_message);
            progressPercentage = dialogView.findViewById(R.id.progress_percentage);
            progressDetails = dialogView.findViewById(R.id.progress_details);
            downloadProgressBar = dialogView.findViewById(R.id.progress_bar);

            titleView.setText(title);
            progressMessage.setText(message);
            progressPercentage.setText("0%");
            progressDetails.setText("");
            downloadProgressBar.setProgress(0);
            downloadProgressBar.setMax(100);

            // Configure progress bar to prevent visual artifacts
            downloadProgressBar.setVisibility(View.VISIBLE);
            downloadProgressBar.setIndeterminate(false); // Ensure determinate mode

            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
            builder.setView(dialogView).setCancelable(false);

            // Add cancel button
            builder.setNegativeButton(R.string.cancel, (dialog, which) -> {
                LogUtils.d("FileBrowserActivity", "User requested download cancellation from progress dialog");
                viewModel.cancelDownload();
                dialog.dismiss();
            });

            progressDialog = builder.create();
            progressDialog.show();

            LogUtils.d("FileBrowserActivity", "Detailed progress dialog shown");
        });
    }

    /**
     * Updates the detailed progress dialog with current progress.
     */
    private void updateDetailedProgress(int percentage, String statusText, String fileName) {
        runOnUiThread(() -> {
            if (!isActivitySafe()) return;

            if (progressDialog != null && progressDialog.isShowing()) {
                if (downloadProgressBar != null) {
                    downloadProgressBar.setProgress(percentage);
                }
                if (progressPercentage != null) {
                    progressPercentage.setText(percentage + "%");
                }
                if (progressMessage != null) {
                    progressMessage.setText(statusText);
                }
                if (progressDetails != null && fileName != null && !fileName.isEmpty()) {
                    String displayName = fileName.length() > 40 ? fileName.substring(0, 37) + "..." : fileName;
                    progressDetails.setText(displayName);
                }
                LogUtils.d("FileBrowserActivity", "Progress updated: " + percentage + "% - " + statusText);
            }
        });
    }

    /**
     * Hides the detailed progress dialog.
     */
    private void hideDetailedProgressDialog() {
        runOnUiThread(() -> {
            if (!isActivitySafe()) return;

            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
                progressDialog = null;
                LogUtils.d("FileBrowserActivity", "Detailed progress dialog hidden");
            }
        });
    }

    /**
     * Shows a detailed progress dialog for uploads with cancel functionality.
     */
    private void showDetailedUploadProgressDialog(String title, String message) {
        runOnUiThread(() -> {
            // Check if activity is still active to prevent window leaks
            if (!isActivitySafe()) {
                LogUtils.w("FileBrowserActivity", "Activity is finishing/destroyed, not showing progress dialog");
                return;
            }

            try {
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }

                // Inflate custom progress dialog layout
                View dialogView = getLayoutInflater().inflate(R.layout.dialog_progress, null);

                TextView titleView = dialogView.findViewById(R.id.progress_title);
                progressMessage = dialogView.findViewById(R.id.progress_message);
                progressPercentage = dialogView.findViewById(R.id.progress_percentage);
                progressDetails = dialogView.findViewById(R.id.progress_details);
                downloadProgressBar = dialogView.findViewById(R.id.progress_bar);

                titleView.setText(title);
                progressMessage.setText(message);
                progressPercentage.setText("0%");
                progressDetails.setText("");
                downloadProgressBar.setProgress(0);
                downloadProgressBar.setMax(100);
                downloadProgressBar.setVisibility(View.VISIBLE);
                downloadProgressBar.setIndeterminate(false);

                MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
                builder.setView(dialogView).setCancelable(false);

                // Add cancel button for upload
                builder.setNegativeButton(R.string.cancel, (dialog, which) -> {
                    LogUtils.d("FileBrowserActivity", "User requested upload cancellation from progress dialog");
                    viewModel.cancelUpload();
                    dialog.dismiss();
                });

                progressDialog = builder.create();
                progressDialog.show();

                LogUtils.d("FileBrowserActivity", "Detailed upload progress dialog shown");
            } catch (Exception e) {
                LogUtils.e("FileBrowserActivity", "Error showing detailed upload progress dialog: " + e.getMessage());
            }
        });
    }

    private void showDetailedDownloadProgressDialog(String title, String message) {
        runOnUiThread(() -> {
            // Check if activity is still active to prevent window leaks
            if (!isActivitySafe()) {
                LogUtils.w("FileBrowserActivity", "Activity is finishing/destroyed, not showing download progress dialog");
                return;
            }

            try {
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }

                // Inflate custom progress dialog layout
                View dialogView = getLayoutInflater().inflate(R.layout.dialog_progress, null);

                TextView titleView = dialogView.findViewById(R.id.progress_title);
                progressMessage = dialogView.findViewById(R.id.progress_message);
                progressPercentage = dialogView.findViewById(R.id.progress_percentage);
                progressDetails = dialogView.findViewById(R.id.progress_details);
                downloadProgressBar = dialogView.findViewById(R.id.progress_bar);

                titleView.setText(title);
                progressMessage.setText(message);
                progressPercentage.setText("0%");
                progressDetails.setText("");
                downloadProgressBar.setProgress(0);
                downloadProgressBar.setMax(100);
                downloadProgressBar.setVisibility(View.VISIBLE);
                downloadProgressBar.setIndeterminate(false);

                MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
                builder.setView(dialogView).setCancelable(false);

                // Add cancel button for download
                builder.setNegativeButton(R.string.cancel, (dialog, which) -> {
                    LogUtils.d("FileBrowserActivity", "User requested download cancellation from progress dialog");
                    viewModel.cancelDownload();
                    dialog.dismiss();
                });

                progressDialog = builder.create();
                progressDialog.show();

                LogUtils.d("FileBrowserActivity", "Detailed download progress dialog shown");
            } catch (Exception e) {
                LogUtils.e("FileBrowserActivity", "Error showing detailed download progress dialog: " + e.getMessage());
            }
        });
    }

    /**
     * Shows a progress dialog for search operations with cancel functionality.
     */
    private void showSearchProgressDialog() {
        runOnUiThread(() -> {
            if (searchProgressDialog != null && searchProgressDialog.isShowing()) {
                searchProgressDialog.dismiss();
            }

            // Inflate custom progress dialog layout
            View dialogView = getLayoutInflater().inflate(R.layout.dialog_progress, null);

            TextView titleView = dialogView.findViewById(R.id.progress_title);
            TextView progressMessage = dialogView.findViewById(R.id.progress_message);
            TextView progressPercentage = dialogView.findViewById(R.id.progress_percentage);
            TextView progressDetails = dialogView.findViewById(R.id.progress_details);
            ProgressBar searchProgressBar = dialogView.findViewById(R.id.progress_bar);

            titleView.setText(getString(R.string.search_title));
            progressMessage.setText(getString(R.string.searching_files));
            progressPercentage.setText("");
            progressDetails.setText("");

            // Set indeterminate progress for search
            searchProgressBar.setIndeterminate(true);

            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
            builder.setView(dialogView).setCancelable(false);

            // Add cancel button for search
            builder.setNegativeButton(R.string.cancel, (dialog, which) -> {
                LogUtils.d("FileBrowserActivity", "User requested search cancellation from progress dialog");
                viewModel.cancelSearch();
                dialog.dismiss();
            });

            searchProgressDialog = builder.create();
            searchProgressDialog.show();

            LogUtils.d("FileBrowserActivity", "Search progress dialog shown");
        });
    }

    /**
     * Hides the search progress dialog.
     */
    private void hideSearchProgressDialog() {
        runOnUiThread(() -> {
            if (searchProgressDialog != null && searchProgressDialog.isShowing()) {
                searchProgressDialog.dismiss();
                searchProgressDialog = null;
                LogUtils.d("FileBrowserActivity", "Search progress dialog hidden");
            }
        });
    }

    @FunctionalInterface
    private interface FileOperation {
        void execute() throws Exception;
    }
}
