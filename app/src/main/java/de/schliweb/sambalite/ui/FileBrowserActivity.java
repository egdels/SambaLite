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
import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.di.AppComponent;
import de.schliweb.sambalite.ui.controllers.*;
import de.schliweb.sambalite.ui.operations.FileOperationsViewModel;
import de.schliweb.sambalite.ui.utils.PreferenceUtils;
import de.schliweb.sambalite.util.LogUtils;
import de.schliweb.sambalite.util.SmartErrorHandler;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

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

    @Inject
    BackgroundSmbManager backgroundSmbManager;

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
    // ServiceController removed; using BackgroundSmbManager directly
    private InputController inputController;

    // UI Components
    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private View emptyView;
    private TextView currentPathView;
    private FloatingActionButton fab;
    private FloatingActionButton fabCreateFolder;
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

    public static Intent createIntentFromUploadNotification(Context context, String connectionId, String directoryPath) {
        Intent intent = new Intent(context, FileBrowserActivity.class);
        intent.putExtra(EXTRA_CONNECTION_ID, connectionId);
        intent.putExtra(EXTRA_DIRECTORY_PATH, directoryPath);
        intent.putExtra(EXTRA_FROM_UPLOAD_NOTIFICATION, true);
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
     * Configures edge-to-edge display for better landscape experience without deprecated flags.
     */
    private void configureEdgeToEdgeDisplay() {
        Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
        window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
    }

    /**
     * Sets up the toolbar.
     */
    private void setupToolbar() {
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(getString(R.string.file_browser_title));
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
        fab = findViewById(R.id.fab);
        fabCreateFolder = findViewById(R.id.fab_create_folder);
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
        searchViewModel.isSearching().observe(this, isSearching -> {
            final String query = searchViewModel.getCurrentSearchQuery();
            final String opName = "Searching for: " + query;
            final String connId = searchViewModel.getConnectionId();
            final int type = searchViewModel.getCurrentSearchType();
            final boolean includeSubs = searchViewModel.isIncludeSubfolders();

            if (isSearching) {
                // 1) UI
                progressController.showSearchProgressDialog();

                // 2) Service: Set context for deep link in notification
                backgroundSmbManager.setSearchContext(connId, query, type, includeSubs);

                // 3) Service: Start search operation, that runs until the search is complete
                backgroundSmbManager.executeBackgroundOperation("search:" + query, opName, callback -> {
                    // initial progress message
                    callback.updateProgress("Searching...");

                    // Update progress every 800 ms until the search is complete
                    while (Boolean.TRUE.equals(searchViewModel.isSearching().getValue())) {
                        // actual hit count, if available
                        java.util.List<SmbFileItem> results = searchViewModel.getSearchResults().getValue();
                        if (results != null) {
                            callback.updateProgress("Found " + results.size() + " results");
                        }
                        try {
                            Thread.sleep(800);
                        } catch (InterruptedException ie) {
                            throw ie;
                        }
                    }
                    return true;
                });

            } else {
                // Search is complete -> close progress dialog
                progressController.hideSearchProgressDialog();
            }
        });


        searchViewModel.getSearchResults().observe(this, searchResults -> {
            if (searchViewModel.isInSearchMode() && searchResults != null) {
                fileListController.updateAdapter(searchResults);
                LogUtils.d("FileBrowserActivity", "Search results updated: " + searchResults.size() + " items");
                // No direct Service call needed here – the executeOperation loop handles it.
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

        fileOperationsController = new FileOperationsController(this, fileOperationsViewModel, fileListViewModel, uiState, backgroundSmbManager);

        activityResultController = new ActivityResultController(this, uiState, inputController);

        // ServiceController removed; BackgroundSmbManager handles service binding internally.

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
        fileListController.setFolderChangeCallback(this::onRemoteFolderChanged);

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
        fab.setOnClickListener(v -> {
            LogUtils.d("FileBrowserActivity", "Main FAB clicked");
            dialogController.showUploadOptionsDialog();
        });

        // Set up create folder button
        fabCreateFolder.setOnClickListener(v -> {
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

        // Auto-hide/show FABs on scroll
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
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
                    // If at top or idle, ensure FABs are visible
                    boolean canScrollUp = recyclerView.canScrollVertically(-1);
                    if (!canScrollUp) {
                        if (!fab.isShown()) fab.show();
                        if (!fabCreateFolder.isShown()) fabCreateFolder.show();
                    }
                }
            }
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
     * Callback for when the remote folder changes.
     * Updates the current SMB folder in preferences.
     *
     * @param newRemotePath The new remote path
     */
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
                boolean alreadySearching = Boolean.TRUE.equals(searchViewModel.isSearching().getValue());
                if (alreadySearching) {
                    LogUtils.i("FileBrowserActivity", "Search already in progress – ensuring dialog is visible, not restarting search");
                    // Just make sure the dialog is visible; the observer will keep it updated
                    progressController.showSearchProgressDialog();
                } else {
                    LogUtils.i("FileBrowserActivity", "Starting search from notification: " + searchQuery);
                    // Start the search
                    searchViewModel.searchFiles(searchQuery, searchType, includeSubfolders);
                }
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
                // Navigate to the directory with proper hierarchy to enable up navigation
                fileListViewModel.navigateToPathWithHierarchy(directoryPath);
                fileListViewModel.refreshCurrentDirectory();
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
    protected void onResume() {
        super.onResume();
        LogUtils.d("FileBrowserActivity", "onResume called");

        // Refresh the current directory when resuming
        if (PreferenceUtils.getNeedsRefresh(this)) {
            LogUtils.i("FileBrowserActivity", "Update needed, refreshing current directory");
            PreferenceUtils.setNeedsRefresh(this, false);
            fileListViewModel.refreshCurrentDirectory();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        LogUtils.d("FileBrowserActivity", "onNewIntent received: " + (intent != null ? intent.getAction() : "null"));
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
        } catch (Throwable ignore) {
        }

        if (newConnId != null && !newConnId.equals(currentConnId)) {
            LogUtils.i("FileBrowserActivity", "New intent targets different connection. Reloading connection context.");
            loadConnectionFromIntent();
        } else {
            // Same connection – handle the specific notification scenarios directly
            checkAndHandleSearchNotification();
            checkAndHandleUploadNotification();
            checkAndHandleDownloadNotification();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle toolbar navigation
        if (item.getItemId() == android.R.id.home) {
            LogUtils.d("FileBrowserActivity", "Toolbar back button clicked");
            // If we are in search mode, exit search and return to the search start folder
            if (searchViewModel != null && searchViewModel.isInSearchMode()) {
                // Cancel any in-flight search and return to the starting folder
                searchViewModel.cancelSearch();
                return true;
            }
            // Try to navigate up within the folder hierarchy first
            if (fileListController != null && fileListController.navigateUp()) {
                return true; // consumed by navigating up
            }
            // Already at top-level -> finish to return to connections
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
    public void onBackPressed() {
        LogUtils.d("FileBrowserActivity", "System back pressed");
        // If we are in search mode, exit search and return to the search start folder
        if (searchViewModel != null && searchViewModel.isInSearchMode()) {
            searchViewModel.cancelSearch();
            return;
        }
        // Try to navigate up within the folder hierarchy first
        if (fileListController != null && fileListController.navigateUp()) {
            return; // consumed by navigating up
        }
        // Already at top-level -> default behavior (finishes the activity)
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LogUtils.d("FileBrowserActivity", "onDestroy called");

        // Clean up controllers
        progressController.closeAllDialogs();
        fileOperationsController.removeListener(this);

        // Only cancel operations if the Activity is actually finishing (user leaving the screen),
        // not during configuration changes or lifecycle churn caused by new intents/back stack tweaks.
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