package de.schliweb.sambalite.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.SambaLiteApp;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.di.AppComponent;
import de.schliweb.sambalite.ui.controllers.*;
import de.schliweb.sambalite.ui.operations.FileOperationsViewModel;
import de.schliweb.sambalite.util.LogUtils;
import de.schliweb.sambalite.util.SmartErrorHandler;

import javax.inject.Inject;

/**
 * Refactored Activity for browsing files on an SMB server.
 * This version uses specialized controllers to handle different responsibilities.
 */
public class FileBrowserActivity extends AppCompatActivity implements FileListController.FileClickCallback, FileListController.FileOptionsCallback, FileListController.FileStatisticsCallback, FileOperationsController.FileOperationListener {

    private static final String EXTRA_CONNECTION_ID = "extra_connection_id";
    private static final String EXTRA_SEARCH_QUERY = "extra_search_query";
    private static final String EXTRA_SEARCH_TYPE = "extra_search_type";
    private static final String EXTRA_SEARCH_INCLUDE_SUBFOLDERS = "extra_search_include_subfolders";
    private static final String EXTRA_FROM_SEARCH_NOTIFICATION = "extra_from_search_notification";
    private static final String EXTRA_DIRECTORY_PATH = "extra_directory_path";
    private static final String EXTRA_FROM_UPLOAD_NOTIFICATION = "extra_from_upload_notification";
    private static final String EXTRA_FROM_DOWNLOAD_NOTIFICATION = "extra_from_download_notification";

    @Inject
    ViewModelProvider.Factory viewModelFactory;

    @Inject
    FileBrowserUIState uiState;

    // ViewModels
    private FileListViewModel fileListViewModel;
    private FileOperationsViewModel fileOperationsViewModel;
    private SearchViewModel searchViewModel;

    // Controllers
    private FileListController fileListController;
    private DialogController dialogController;
    private FileOperationsController fileOperationsController;
    private ProgressController progressController;
    private ActivityResultController activityResultController;
    private ServiceController serviceController;
    private InputController inputController;

    // UI Components
    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private View emptyView;
    private TextView currentPathView;
    private SmartErrorHandler errorHandler;

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

    /**
     * Creates an intent to start this activity with search parameters.
     *
     * @param context           The context to use
     * @param connectionId      The ID of the connection to browse
     * @param searchQuery       The search query
     * @param searchType        The type of search (0=all, 1=files only, 2=folders only)
     * @param includeSubfolders Whether to include subfolders in the search
     * @return The intent to start this activity
     */
    public static Intent createSearchIntent(Context context, String connectionId, String searchQuery, int searchType, boolean includeSubfolders) {
        Intent intent = new Intent(context, FileBrowserActivity.class);
        intent.putExtra(EXTRA_CONNECTION_ID, connectionId);
        intent.putExtra(EXTRA_SEARCH_QUERY, searchQuery);
        intent.putExtra(EXTRA_SEARCH_TYPE, searchType);
        intent.putExtra(EXTRA_SEARCH_INCLUDE_SUBFOLDERS, includeSubfolders);
        intent.putExtra(EXTRA_FROM_SEARCH_NOTIFICATION, true);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        LogUtils.d("FileBrowserActivity", "onCreate called");

        // Initialize error handler
        errorHandler = ((SambaLiteApp) getApplication()).getErrorHandler();
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

        // Set up ViewModel observers
        setupViewModelObservers();

        // Initialize shared state
        initializeSharedState();

        // Initialize controllers
        initializeControllers();

        // Set up controller callbacks
        setupControllerCallbacks();

        // Set up UI event listeners
        setupUIEventListeners();

        // Load connection from intent
        loadConnectionFromIntent();
    }

    /**
     * Configures edge-to-edge display for better landscape experience.
     */
    private void configureEdgeToEdgeDisplay() {
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
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    /**
     * Sets up the toolbar.
     */
    private void setupToolbar() {
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("File Browser");
        }
        LogUtils.d("FileBrowserActivity", "Toolbar set up");
    }

    /**
     * Initializes UI components.
     */
    private void initializeUIComponents() {
        recyclerView = findViewById(R.id.files_recycler_view);
        swipeRefreshLayout = findViewById(R.id.swipe_refresh);
        emptyView = findViewById(R.id.empty_state);
        currentPathView = findViewById(R.id.current_path);
        LogUtils.d("FileBrowserActivity", "UI components initialized");
    }

    /**
     * Initializes ViewModels.
     */
    private void initializeViewModels() {
        fileListViewModel = new ViewModelProvider(this, viewModelFactory).get(FileListViewModel.class);
        fileOperationsViewModel = new ViewModelProvider(this, viewModelFactory).get(FileOperationsViewModel.class);
        searchViewModel = new ViewModelProvider(this, viewModelFactory).get(SearchViewModel.class);
        // FileBrowserViewModel initialization removed as it's no longer needed
        LogUtils.d("FileBrowserActivity", "ViewModels initialized");
    }

    /**
     * Initializes shared state.
     */
    private void initializeSharedState() {
        // FileBrowserUIState is now injected by Dagger
        LogUtils.d("FileBrowserActivity", "Shared state initialized");
    }

    /**
     * Sets up observers for ViewModels.
     */
    private void setupViewModelObservers() {
        // Observe search state to show/hide progress dialog and manage background notification
        searchViewModel.isSearching().observe(this, isSearching -> {
            if (isSearching) {
                // Show search progress dialog when search starts
                progressController.showSearchProgressDialog();
                LogUtils.d("FileBrowserActivity", "Search started, showing progress dialog");

                // Start search operation in background service for notification
                String searchQuery = searchViewModel.getCurrentSearchQuery();
                String operationName = "Searching for: " + searchQuery;

                // Get connection ID and search parameters from the SearchViewModel
                String connectionId = searchViewModel.getConnectionId();
                int searchType = searchViewModel.getCurrentSearchType();
                boolean includeSubfolders = searchViewModel.isIncludeSubfolders();

                // Start search operation with all parameters
                serviceController.startSearchOperation(operationName, connectionId, searchQuery, searchType, includeSubfolders);
                serviceController.updateOperationProgress(operationName, "Searching...");
                LogUtils.d("FileBrowserActivity", "Started search operation in background service");
            } else {
                // Hide search progress dialog when search completes
                progressController.hideSearchProgressDialog();
                LogUtils.d("FileBrowserActivity", "Search completed, hiding progress dialog");

                // Finish search operation in background service
                String searchQuery = searchViewModel.getCurrentSearchQuery();
                String operationName = "Searching for: " + searchQuery;
                serviceController.finishOperation(operationName, true);
                LogUtils.d("FileBrowserActivity", "Finished search operation in background service");
            }
        });

        // Observe search results to update the UI and background notification
        searchViewModel.getSearchResults().observe(this, searchResults -> {
            if (searchViewModel.isInSearchMode() && searchResults != null) {
                // Update the file list adapter with search results
                fileListController.updateAdapter(searchResults);
                LogUtils.d("FileBrowserActivity", "Search results updated: " + searchResults.size() + " items");

                // Update search progress in background service
                String searchQuery = searchViewModel.getCurrentSearchQuery();
                String operationName = "Searching for: " + searchQuery;
                String progressInfo = "Found " + searchResults.size() + " results";
                serviceController.updateOperationProgress(operationName, progressInfo);
                LogUtils.d("FileBrowserActivity", "Updated search progress in background service");
            }
        });

        LogUtils.d("FileBrowserActivity", "ViewModel observers set up");
    }

    /**
     * Initializes controllers.
     */
    private void initializeControllers() {
        // Create controllers
        inputController = new InputController(this);
        progressController = new ProgressController(this);
        fileListController = new FileListController(recyclerView, swipeRefreshLayout, emptyView, currentPathView, fileListViewModel, uiState);

        dialogController = new DialogController(this, fileListViewModel, fileOperationsViewModel, searchViewModel, uiState);

        fileOperationsController = new FileOperationsController(this, fileOperationsViewModel, fileListViewModel, uiState);

        activityResultController = new ActivityResultController(this, uiState, inputController);

        serviceController = new ServiceController(this, uiState);

        // Register lifecycle observers
        getLifecycle().addObserver(serviceController);

        // Set ServiceController in FileOperationsViewModel for background notifications
        fileOperationsViewModel.setServiceController(serviceController);

        LogUtils.d("FileBrowserActivity", "Controllers initialized");
    }

    /**
     * Sets up controller callbacks.
     */
    private void setupControllerCallbacks() {
        // Set up FileListController callbacks
        fileListController.setFileClickCallback(this);
        fileListController.setFileOptionsCallback(this);
        fileListController.setFileStatisticsCallback(this);

        // Set up DialogController callbacks
        dialogController.setFileOperationCallback(new DialogController.FileOperationCallback() {
            @Override
            public void onDownloadRequested(SmbFileItem file) {
                fileOperationsController.getFileOperationRequester().requestFileOrFolderDownload(file);
            }
        });
        dialogController.setSearchCallback((query, searchType, includeSubfolders) -> {
            searchViewModel.searchFiles(query, searchType, includeSubfolders);
        });
        dialogController.setUploadCallback(new DialogController.UploadCallback() {
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
        fileOperationsController.setDialogController(dialogController); // Set DialogController for confirmation dialogs

        // Set the FileOperationRequester on the DialogController
        dialogController.setFileOperationRequester(fileOperationsController.getFileOperationRequester());

        // Set up ProgressController search cancellation callback
        progressController.setSearchCancellationCallback(() -> {
            LogUtils.d("FileBrowserActivity", "Search cancellation callback triggered, cancelling search");
            searchViewModel.cancelSearch();
        });
        fileOperationsController.setServiceCallback(new FileOperationsController.ServiceCallback() {
            @Override
            public void startOperation(String operationName) {
                serviceController.startOperation(operationName);
            }

            @Override
            public void finishOperation(String operationName, boolean success) {
                serviceController.finishOperation(operationName, success);
            }

            @Override
            public void updateFileProgress(String operationName, int currentFile, int totalFiles, String currentFileName) {
                serviceController.updateFileProgress(operationName, currentFile, totalFiles, currentFileName);
            }

            @Override
            public void updateBytesProgress(String operationName, long currentBytes, long totalBytes, String fileName) {
                serviceController.updateBytesProgress(operationName, currentBytes, totalBytes, fileName);
            }

            @Override
            public void updateOperationProgress(String operationName, String progressInfo) {
                serviceController.updateOperationProgress(operationName, progressInfo);
            }
        });
        fileOperationsController.addListener(this);

        // Set up ActivityResultController callbacks
        activityResultController.setFileOperationCallback(new ActivityResultController.FileOperationCallback() {
            @Override
            public void onFileUploadResult(Uri uri) {
                fileOperationsController.handleFileUpload(uri);
            }

            @Override
            public void onFileDownloadResult(Uri uri) {
                fileOperationsController.handleFileDownload(uri);
            }

            @Override
            public void onFolderDownloadResult(Uri uri) {
                fileOperationsController.handleFolderDownload(uri);
            }

            @Override
            public void onFolderUploadResult(Uri uri) {
                fileOperationsController.handleFolderContentsUpload(uri);
            }
        });

        LogUtils.d("FileBrowserActivity", "Controller callbacks set up");
    }

    /**
     * Sets up UI event listeners.
     */
    private void setupUIEventListeners() {
        // Set up search button
        findViewById(R.id.search_button).setOnClickListener(v -> {
            LogUtils.d("FileBrowserActivity", "Search button clicked");
            dialogController.showSearchDialog();
        });

        // Set up sort button
        findViewById(R.id.sort_button).setOnClickListener(v -> {
            LogUtils.d("FileBrowserActivity", "Sort button clicked");
            dialogController.showSortDialog();
        });

        // Set up upload button (FAB)
        findViewById(R.id.fab).setOnClickListener(v -> {
            LogUtils.d("FileBrowserActivity", "Main FAB clicked");
            dialogController.showUploadOptionsDialog();
        });

        // Set up create folder button
        findViewById(R.id.fab_create_folder).setOnClickListener(v -> {
            LogUtils.d("FileBrowserActivity", "Create folder button clicked");
            dialogController.showCreateFolderDialog();
        });

        // Set up parent directory button
        findViewById(R.id.parent_directory_button).setOnClickListener(v -> {
            LogUtils.d("FileBrowserActivity", "Parent directory button clicked");
            fileListController.navigateUp();
        });

        // Set up refresh button
        findViewById(R.id.refresh_button).setOnClickListener(v -> {
            LogUtils.d("FileBrowserActivity", "Refresh button clicked");
            fileListViewModel.refreshCurrentDirectory();
        });

        LogUtils.d("FileBrowserActivity", "UI event listeners set up");
    }

    /**
     * Loads the connection from the intent.
     */
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
        MainViewModel mainViewModel = new ViewModelProvider(this, viewModelFactory).get(MainViewModel.class);
        LogUtils.d("FileBrowserActivity", "MainViewModel initialized to load connection");

        mainViewModel.getConnections().observe(this, connections -> {
            LogUtils.d("FileBrowserActivity", "Searching for connection with ID: " + connectionId + " among " + connections.size() + " connections");
            for (SmbConnection connection : connections) {
                if (connection.getId().equals(connectionId)) {
                    LogUtils.i("FileBrowserActivity", "Connection found: " + connection.getName());
                    fileListViewModel.setConnection(connection);

                    // Check if we were opened from a notification and handle it
                    checkAndHandleSearchNotification();
                    checkAndHandleUploadNotification();
                    checkAndHandleDownloadNotification();
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
     * Checks if the activity was opened from a search notification and handles it.
     */
    private void checkAndHandleSearchNotification() {
        Intent intent = getIntent();
        if (intent.getBooleanExtra(EXTRA_FROM_SEARCH_NOTIFICATION, false)) {
            LogUtils.d("FileBrowserActivity", "Activity opened from search notification");

            // Extract search parameters from intent
            String searchQuery = intent.getStringExtra(EXTRA_SEARCH_QUERY);
            int searchType = intent.getIntExtra(EXTRA_SEARCH_TYPE, 0);
            boolean includeSubfolders = intent.getBooleanExtra(EXTRA_SEARCH_INCLUDE_SUBFOLDERS, true);

            if (searchQuery != null && !searchQuery.isEmpty()) {
                LogUtils.i("FileBrowserActivity", "Starting search from notification: " + searchQuery);

                // Start the search
                searchViewModel.searchFiles(searchQuery, searchType, includeSubfolders);
            }
        }
    }

    /**
     * Checks if the activity was opened from an upload notification and handles it.
     */
    private void checkAndHandleUploadNotification() {
        Intent intent = getIntent();
        if (intent.getBooleanExtra(EXTRA_FROM_UPLOAD_NOTIFICATION, false)) {
            LogUtils.d("FileBrowserActivity", "Activity opened from upload notification");

            // Extract directory path from intent
            String directoryPath = intent.getStringExtra(EXTRA_DIRECTORY_PATH);

            if (directoryPath != null && !directoryPath.isEmpty()) {
                LogUtils.i("FileBrowserActivity", "Navigating to upload directory: " + directoryPath);

                // Navigate to the directory
                fileListViewModel.navigateToPath(directoryPath);
            }
        }
    }

    /**
     * Checks if the activity was opened from a download notification and handles it.
     */
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

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle toolbar navigation
        if (item.getItemId() == android.R.id.home) {
            LogUtils.d("FileBrowserActivity", "Toolbar back button clicked");
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        // Delegate to InputController
        inputController.handleTouchEvent(event);
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LogUtils.d("FileBrowserActivity", "onDestroy called");

        // Clean up controllers
        progressController.closeAllDialogs();
        fileOperationsController.removeListener(this);

        // Cancel any ongoing operations
        fileOperationsViewModel.cancelUpload();
        fileOperationsViewModel.cancelDownload();
        searchViewModel.cancelSearch();
    }

    // FileListController.FileClickCallback implementation
    @Override
    public void onFileClick(SmbFileItem file) {
        LogUtils.d("FileBrowserActivity", "File clicked: " + file.getName());
        if (!file.isDirectory()) {
            // Show file info
            progressController.showInfo(file.getName());
            // Show file options
            dialogController.showFileOptionsDialog(file);
        }
    }

    // FileListController.FileOptionsCallback implementation
    @Override
    public void onFileOptionsClick(SmbFileItem file) {
        LogUtils.d("FileBrowserActivity", "File options clicked: " + file.getName());
        dialogController.showFileOptionsDialog(file);
    }

    // FileListController.FileStatisticsCallback implementation
    @Override
    public void onFileStatisticsUpdated(java.util.List<SmbFileItem> files) {
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

    // FileOperationsController.FileOperationListener implementation
    @Override
    public void onFileOperationStarted(String operationType, SmbFileItem file) {
        LogUtils.d("FileBrowserActivity", "File operation started: " + operationType + " on " + (file != null ? file.getName() : "null"));
    }

    @Override
    public void onFileOperationCompleted(String operationType, SmbFileItem file, boolean success, String message) {
        LogUtils.d("FileBrowserActivity", "File operation completed: " + operationType + " on " + (file != null ? file.getName() : "null") + ", success: " + success + ", message: " + message);
    }

    @Override
    public void onFileOperationProgress(String operationType, SmbFileItem file, int progress, String message) {
        LogUtils.d("FileBrowserActivity", "File operation progress: " + operationType + " on " + (file != null ? file.getName() : "null") + ", progress: " + progress + "%, message: " + message);
    }
}