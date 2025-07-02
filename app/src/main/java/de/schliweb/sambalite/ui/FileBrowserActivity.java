package de.schliweb.sambalite.ui;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.SambaLiteApp;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.di.AppComponent;
import de.schliweb.sambalite.util.KeyboardUtils;
import de.schliweb.sambalite.util.LogUtils;

import javax.inject.Inject;
import java.io.*;

/**
 * Activity for browsing files on an SMB server.
 */
public class FileBrowserActivity extends AppCompatActivity implements FileAdapter.OnFileClickListener, FileAdapter.OnFileLongClickListener {

    private static final String EXTRA_CONNECTION_ID = "extra_connection_id";
    private static final int REQUEST_CODE_CREATE_FILE = 1;
    private static final int REQUEST_CODE_PICK_FILE = 2;
    private static final int REQUEST_CODE_CREATE_FOLDER = 3;
    @Inject
    ViewModelProvider.Factory viewModelFactory;
    private boolean isActivityVisible = true;
    private FileBrowserViewModel viewModel;
    private FileAdapter adapter;
    private SwipeRefreshLayout swipeRefreshLayout;
    private TextView emptyView;
    private View progressBar;
    private TextView currentPathView;
    private View cancelSearchButton;
    private SmbFileItem fileToDownload;
    private FileAdapter.OnFileLongClickListener onFileLongClickListener;

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
        isActivityVisible = true;
        LogUtils.d("FileBrowserActivity", "visibilityChanged oldVisibility=false newVisibility=true");
    }

    @Override
    protected void onPause() {
        super.onPause();
        isActivityVisible = false;
        LogUtils.d("FileBrowserActivity", "visibilityChanged oldVisibility=true newVisibility=false");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        LogUtils.d("FileBrowserActivity", "onCreate called");
        // Get the Dagger component and inject dependencies
        AppComponent appComponent = ((SambaLiteApp) getApplication()).getAppComponent();
        appComponent.inject(this);
        LogUtils.d("FileBrowserActivity", "Dependencies injected");

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_browser);
        LogUtils.d("FileBrowserActivity", "Content view set");

        // Navigation is now handled by the Navigation-Bar
        LogUtils.d("FileBrowserActivity", "Using Navigation-Bar for navigation");

        // Initialize views
        RecyclerView recyclerView = findViewById(R.id.files_recycler_view);
        swipeRefreshLayout = findViewById(R.id.swipe_refresh);
        emptyView = findViewById(R.id.empty_view);
        progressBar = findViewById(R.id.progress_bar);
        currentPathView = findViewById(R.id.current_path);
        cancelSearchButton = findViewById(R.id.fab_cancel_search);
        LogUtils.d("FileBrowserActivity", "Views initialized");

        // Set up cancel search button
        cancelSearchButton.setOnClickListener(v -> {
            LogUtils.d("FileBrowserActivity", "Cancel search button clicked");
            viewModel.cancelSearch();
        });
        LogUtils.d("FileBrowserActivity", "Cancel search button set up");

        // Set up search FAB
        findViewById(R.id.fab_search).setOnClickListener(v -> {
            LogUtils.d("FileBrowserActivity", "Search FAB clicked");
            showSearchDialog();
        });
        LogUtils.d("FileBrowserActivity", "Search FAB set up");

        // Set up sort FAB
        findViewById(R.id.fab_sort).setOnClickListener(v -> {
            LogUtils.d("FileBrowserActivity", "Sort FAB clicked");
            showSortDialog();
        });
        LogUtils.d("FileBrowserActivity", "Sort FAB set up");

        // Set up upload button
        findViewById(R.id.fab_upload).setOnClickListener(v -> {
            LogUtils.d("FileBrowserActivity", "Upload button clicked");
            selectFileToUpload();
        });
        LogUtils.d("FileBrowserActivity", "Upload button set up");

        // Set up create folder button
        findViewById(R.id.fab_create_folder).setOnClickListener(v -> {
            LogUtils.d("FileBrowserActivity", "Create folder button clicked");
            showCreateFolderDialog();
        });
        LogUtils.d("FileBrowserActivity", "Create folder button set up");

        // Set up RecyclerView
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FileAdapter();
        adapter.setOnFileClickListener(this);
        adapter.setOnFileLongClickListener(this);
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

        // Observe error messages
        viewModel.getErrorMessage().observe(this, errorMessage -> {
            if (errorMessage != null && !errorMessage.isEmpty()) {
                LogUtils.w("FileBrowserActivity", "Error message received: " + errorMessage);
                Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
            }
        });
        LogUtils.d("FileBrowserActivity", "Error message observer set up");

        // Observe current path
        viewModel.getCurrentPath().observe(this, path -> {
            LogUtils.d("FileBrowserActivity", "Current path updated: " + path);
            currentPathView.setText(path);
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
                emptyView.setText(R.string.no_search_results);
                emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
                recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);

                // We're in search mode
                LogUtils.d("FileBrowserActivity", "In search mode");
            } else {
                // Not in search mode
                LogUtils.d("FileBrowserActivity", "Not in search mode");
                emptyView.setText(R.string.empty_directory);
            }
        });
        LogUtils.d("FileBrowserActivity", "Search results observer set up");

        // Observe searching state
        viewModel.isSearching().observe(this, isSearching -> {
            LogUtils.d("FileBrowserActivity", "Searching state changed: " + isSearching);
            if (isSearching) {
                progressBar.setVisibility(View.VISIBLE);
                cancelSearchButton.setVisibility(View.VISIBLE);
            } else {
                progressBar.setVisibility(View.GONE);
                cancelSearchButton.setVisibility(View.GONE);
            }
        });
        LogUtils.d("FileBrowserActivity", "Searching state observer set up");

        // Get connection ID from intent
        String connectionId = getIntent().getStringExtra(EXTRA_CONNECTION_ID);
        if (connectionId == null) {
            LogUtils.e("FileBrowserActivity", "No connection ID specified in intent");
            Toast.makeText(this, "No connection specified", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "Connection not found", Toast.LENGTH_SHORT).show();
            finish();
        });
        LogUtils.d("FileBrowserActivity", "Connection observer set up");
    }

    /**
     * Shows the search dialog.
     */
    private void showSearchDialog() {
        LogUtils.d("FileBrowserActivity", "Showing search dialog");

        // Inflate the dialog layout
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_search, null);
        LogUtils.d("FileBrowserActivity", "Search dialog view inflated");

        // Get the search query input field
        com.google.android.material.textfield.TextInputEditText searchQueryEditText = dialogView.findViewById(R.id.search_query_edit_text);
        com.google.android.material.textfield.TextInputLayout searchQueryLayout = dialogView.findViewById(R.id.search_query_layout);

        // Get the search type radio group
        RadioGroup searchTypeRadioGroup = dialogView.findViewById(R.id.search_type_radio_group);

        // Get the include subfolders checkbox
        CheckBox includeSubfoldersCheckbox = dialogView.findViewById(R.id.include_subfolders_checkbox);

        // Create the dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.search_files).setView(dialogView).setPositiveButton(R.string.search, null) // Set in onShowListener to prevent auto-dismiss
                .setNegativeButton(R.string.cancel, (dialog, which) -> {
                    LogUtils.d("FileBrowserActivity", "Search dialog cancelled");
                    dialog.dismiss();
                });

        // Create and show the dialog
        AlertDialog dialog = builder.create();
        LogUtils.d("FileBrowserActivity", "Search dialog created");

        // Set up the positive button click listener
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialogInterface) {
                Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                positiveButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        LogUtils.d("FileBrowserActivity", "Search button clicked in dialog");

                        // Get the search query
                        String query = searchQueryEditText.getText().toString().trim();
                        LogUtils.d("FileBrowserActivity", "Search query entered: " + query);

                        // Validate the search query
                        if (query.isEmpty()) {
                            LogUtils.w("FileBrowserActivity", "Search query is empty");
                            searchQueryLayout.setError(getString(R.string.search_query_hint));
                            return;
                        }

                        // Clear any previous errors
                        searchQueryLayout.setError(null);

                        // Get the search type
                        int searchType = 0; // Default: All
                        int selectedRadioButtonId = searchTypeRadioGroup.getCheckedRadioButtonId();
                        if (selectedRadioButtonId == R.id.radio_files) {
                            searchType = 1; // Files only
                        } else if (selectedRadioButtonId == R.id.radio_folders) {
                            searchType = 2; // Folders only
                        }
                        LogUtils.d("FileBrowserActivity", "Search type selected: " + searchType);

                        // Get the include subfolders option
                        boolean includeSubfolders = includeSubfoldersCheckbox.isChecked();
                        LogUtils.d("FileBrowserActivity", "Include subfolders: " + includeSubfolders);

                        // Perform the search
                        viewModel.searchFiles(query, searchType, includeSubfolders);

                        // Dismiss the dialog
                        dialog.dismiss();
                    }
                });
            }
        });

        dialog.show();
        LogUtils.d("FileBrowserActivity", "Search dialog shown");

        // Set focus to the search query input field
        // Ensure any previous focus is cleared first
        View currentFocus = getCurrentFocus();
        if (currentFocus != null && currentFocus != searchQueryEditText) {
            currentFocus.clearFocus();
        }
        searchQueryEditText.requestFocus();
        KeyboardUtils.showKeyboard(this, searchQueryEditText);
        LogUtils.d("FileBrowserActivity", "Focus set to search query input field");
    }

    @Override
    public void onFileClick(SmbFileItem file) {
        LogUtils.d("FileBrowserActivity", "File clicked: " + file.getName() + ", isDirectory: " + file.isDirectory());
        if (file.isDirectory()) {
            LogUtils.d("FileBrowserActivity", "Navigating to directory: " + file.getName());
            viewModel.navigateToDirectory(file);
        } else {
            LogUtils.d("FileBrowserActivity", "Showing options for file: " + file.getName());
            showFileOptionsDialog(file, false); // false indicates it's from a click action, not a long click
        }
    }

    /**
     * Shows a dialog with options for a file or folder.
     * For files, only the download option is shown when clicked.
     * For files that are long-clicked, all options (download, rename, delete) are shown.
     * For folders, all options (download, rename, delete) are shown.
     *
     * @param file            The file or folder to show options for
     * @param isFromLongClick Whether this dialog was triggered by a long click action
     */
    private void showFileOptionsDialog(SmbFileItem file, boolean isFromLongClick) {
        LogUtils.d("FileBrowserActivity", "Showing options dialog for: " + file.getName() + ", isDirectory: " + file.isDirectory() + ", isFromLongClick: " + isFromLongClick);
        String[] options;

        if (file.isFile() && !isFromLongClick) {
            // Only show download option for files when clicked (not long-clicked)
            options = new String[]{getString(R.string.download)};
        } else {
            // Show all options for folders and for files that are long-clicked
            options = new String[]{getString(R.string.download), getString(R.string.rename), getString(R.string.delete_file)};
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(file.getName()).setItems(options, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (file.isFile() && !isFromLongClick) {
                    // For files that were clicked (not long-clicked), only download option is available
                    LogUtils.d("FileBrowserActivity", "Download option selected for file: " + file.getName());
                    downloadFile(file);
                } else {
                    // For folders and for files that were long-clicked, handle all options
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
                }
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
        LogUtils.d("FileBrowserActivity", "Showing rename dialog for file: " + file.getName());

        // Inflate the dialog layout
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_rename_file, null);
        LogUtils.d("FileBrowserActivity", "Rename dialog view inflated");

        // Get the file name input field
        final TextInputEditText fileNameEditText = dialogView.findViewById(R.id.file_name_edit_text);
        fileNameEditText.setText(file.getName());

        // Create the dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.rename_dialog_title).setView(dialogView).setPositiveButton(R.string.rename, null) // Set listener later to prevent automatic dismissal
                .setNegativeButton(R.string.cancel, (dialog, which) -> {
                    LogUtils.d("FileBrowserActivity", "Rename dialog cancelled");
                    dialog.dismiss();
                });

        // Create the dialog
        final AlertDialog dialog = builder.create();

        // Set the positive button click listener
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialogInterface) {
                Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                positiveButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String newName = fileNameEditText.getText().toString().trim();
                        LogUtils.d("FileBrowserActivity", "Attempting to rename file to: " + newName);

                        if (newName.isEmpty()) {
                            LogUtils.w("FileBrowserActivity", "New name is empty");
                            fileNameEditText.setError(getString(R.string.error_name_required));
                            return;
                        }

                        // Hide keyboard
                        KeyboardUtils.hideKeyboard(FileBrowserActivity.this);

                        // Show a progress indicator
                        Toast.makeText(FileBrowserActivity.this, R.string.rename, Toast.LENGTH_SHORT).show();

                        // Rename the file
                        viewModel.renameFile(file, newName, new FileBrowserViewModel.RenameFileCallback() {
                            @Override
                            public void onResult(boolean success, String message) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (success) {
                                            LogUtils.i("FileBrowserActivity", "File renamed successfully: " + file.getName() + " to " + newName);
                                            Snackbar.make(findViewById(android.R.id.content), R.string.rename_success, Snackbar.LENGTH_LONG).show();
                                            dialog.dismiss();
                                        } else {
                                            LogUtils.e("FileBrowserActivity", "Error renaming file: " + message);
                                            Snackbar.make(findViewById(android.R.id.content), getString(R.string.rename_error) + ": " + message, Snackbar.LENGTH_LONG).show();
                                        }
                                    }
                                });
                            }
                        });
                    }
                });
            }
        });

        dialog.show();
        LogUtils.d("FileBrowserActivity", "Rename dialog shown");

        // Set focus to the file name input field
        fileNameEditText.requestFocus();
        fileNameEditText.selectAll();
        KeyboardUtils.showKeyboard(this, fileNameEditText);
    }

    /**
     * Shows a confirmation dialog to delete a file.
     */
    private void showDeleteFileConfirmationDialog(SmbFileItem file) {
        LogUtils.d("FileBrowserActivity", "Showing delete confirmation dialog for file: " + file.getName());

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.delete_file_dialog_title).setMessage(getString(R.string.confirm_delete_file, file.getName())).setPositiveButton(R.string.delete, (dialog, which) -> {
            LogUtils.d("FileBrowserActivity", "Delete confirmed for file: " + file.getName());

            // Show a progress indicator
            Toast.makeText(this, R.string.delete_file, Toast.LENGTH_SHORT).show();

            // Delete the file
            viewModel.deleteFile(file, new FileBrowserViewModel.DeleteFileCallback() {
                @Override
                public void onResult(boolean success, String message) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (success) {
                                LogUtils.i("FileBrowserActivity", "File deleted successfully: " + file.getName());
                                Snackbar.make(findViewById(android.R.id.content), R.string.delete_success, Snackbar.LENGTH_LONG).show();
                            } else {
                                LogUtils.e("FileBrowserActivity", "Error deleting file: " + message);
                                Snackbar.make(findViewById(android.R.id.content), getString(R.string.delete_error) + ": " + message, Snackbar.LENGTH_LONG).show();
                            }
                        }
                    });
                }
            });
        }).setNegativeButton(R.string.cancel, (dialog, which) -> {
            LogUtils.d("FileBrowserActivity", "Delete cancelled for file: " + file.getName());
            dialog.dismiss();
        });

        AlertDialog dialog = builder.create();
        dialog.show();
        LogUtils.d("FileBrowserActivity", "Delete confirmation dialog shown");
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
            startActivityForResult(intent, REQUEST_CODE_CREATE_FOLDER);
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
            startActivityForResult(intent, REQUEST_CODE_CREATE_FILE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        LogUtils.d("FileBrowserActivity", "onActivityResult: requestCode=" + requestCode + ", resultCode=" + resultCode);
        super.onActivityResult(requestCode, resultCode, data);

        // Ensure keyboard is hidden when returning from another activity
        // Clear focus on any focused view first to ensure proper IME callback handling
        View currentFocus = getCurrentFocus();
        if (currentFocus != null) {
            currentFocus.clearFocus();
        }
        KeyboardUtils.hideKeyboard(this);

        if (requestCode == REQUEST_CODE_PICK_FILE && resultCode == Activity.RESULT_OK && data != null) {
            LogUtils.d("FileBrowserActivity", "File picked for upload");
            // Handle file selection for upload
            Uri uri = data.getData();
            if (uri != null) {
                LogUtils.d("FileBrowserActivity", "File URI for upload: " + uri);
                handleFileUpload(uri);
            } else {
                LogUtils.w("FileBrowserActivity", "Null URI returned from file picker");
            }
        } else if (requestCode == REQUEST_CODE_CREATE_FILE && resultCode == Activity.RESULT_OK && data != null) {
            LogUtils.d("FileBrowserActivity", "Destination selected for download");
            Uri uri = data.getData();
            if (uri != null && fileToDownload != null) {
                LogUtils.d("FileBrowserActivity", "Destination URI for download: " + uri);
                // Show a progress indicator
                Toast.makeText(this, R.string.downloading, Toast.LENGTH_SHORT).show();

                // Create a temporary file to download to
                try {
                    File tempFile = File.createTempFile("download", ".tmp", getCacheDir());
                    LogUtils.d("FileBrowserActivity", "Created temporary file for download: " + tempFile.getAbsolutePath());

                    // Download the file
                    LogUtils.d("FileBrowserActivity", "Starting download of: " + fileToDownload.getName());
                    viewModel.downloadFile(fileToDownload, tempFile, new FileBrowserViewModel.DownloadCallback() {
                        @Override
                        public void onResult(boolean success, String message) {
                            LogUtils.d("FileBrowserActivity", "Download result: " + (success ? "success" : "failure") + " - " + message);
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (success) {
                                        LogUtils.d("FileBrowserActivity", "Download successful, copying to destination URI");
                                        // Copy the downloaded file to the selected location
                                        try {
                                            copyFileToUri(tempFile, uri);
                                            LogUtils.i("FileBrowserActivity", "File downloaded and saved successfully: " + fileToDownload.getName());
                                            Snackbar.make(findViewById(android.R.id.content), R.string.download_success, Snackbar.LENGTH_LONG).show();
                                        } catch (Exception e) {
                                            LogUtils.e("FileBrowserActivity", "Error copying file to destination: " + e.getMessage());
                                            Snackbar.make(findViewById(android.R.id.content), getString(R.string.download_error) + ": " + e.getMessage(), Snackbar.LENGTH_LONG).show();
                                        } finally {
                                            // Delete the temporary file
                                            LogUtils.d("FileBrowserActivity", "Deleting temporary file: " + tempFile.getAbsolutePath());
                                            tempFile.delete();
                                        }
                                    } else {
                                        LogUtils.e("FileBrowserActivity", "Download failed: " + message);

                                        // Create a more user-friendly message for file not found errors
                                        String userMessage;
                                        if (message.contains("File not found")) {
                                            String fileName = fileToDownload.getName();
                                            userMessage = getString(R.string.download_error) + ": " + getString(R.string.file_not_found, fileName);
                                            LogUtils.d("FileBrowserActivity", "Showing user-friendly file not found message");
                                        } else {
                                            userMessage = getString(R.string.download_error) + ": " + message;
                                        }

                                        Snackbar.make(findViewById(android.R.id.content), userMessage, Snackbar.LENGTH_LONG).show();
                                    }
                                }
                            });
                        }
                    });
                } catch (Exception e) {
                    LogUtils.e("FileBrowserActivity", "Error setting up download: " + e.getMessage());
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.download_error) + ": " + e.getMessage(), Snackbar.LENGTH_LONG).show();
                }
            } else {
                LogUtils.w("FileBrowserActivity", "Null URI or fileToDownload in onActivityResult");
            }
        } else if (requestCode == REQUEST_CODE_CREATE_FOLDER && resultCode == Activity.RESULT_OK && data != null) {
            LogUtils.d("FileBrowserActivity", "Destination folder selected for folder download");
            Uri uri = data.getData();
            if (uri != null && fileToDownload != null && fileToDownload.isDirectory()) {
                LogUtils.d("FileBrowserActivity", "Destination folder URI for download: " + uri);
                // Show a progress indicator
                Toast.makeText(this, R.string.downloading, Toast.LENGTH_SHORT).show();

                try {
                    // Get the document file from the URI
                    DocumentFile documentFile = DocumentFile.fromTreeUri(this, uri);
                    if (documentFile == null || !documentFile.exists() || !documentFile.isDirectory()) {
                        LogUtils.e("FileBrowserActivity", "Invalid destination folder");
                        Snackbar.make(findViewById(android.R.id.content), getString(R.string.download_error) + ": Invalid destination folder", Snackbar.LENGTH_LONG).show();
                        return;
                    }

                    // Create a subfolder with the name of the folder being downloaded
                    DocumentFile subFolder = documentFile.createDirectory(fileToDownload.getName());
                    if (subFolder == null) {
                        LogUtils.e("FileBrowserActivity", "Failed to create subfolder: " + fileToDownload.getName());
                        Snackbar.make(findViewById(android.R.id.content), getString(R.string.download_error) + ": Failed to create folder", Snackbar.LENGTH_LONG).show();
                        return;
                    }

                    // Create a temporary folder to download to
                    File tempFolder = new File(getCacheDir(), "download_" + System.currentTimeMillis());
                    if (!tempFolder.mkdirs()) {
                        LogUtils.e("FileBrowserActivity", "Failed to create temporary folder: " + tempFolder.getAbsolutePath());
                        Snackbar.make(findViewById(android.R.id.content), getString(R.string.download_error) + ": Failed to create temporary folder", Snackbar.LENGTH_LONG).show();
                        return;
                    }
                    LogUtils.d("FileBrowserActivity", "Created temporary folder for download: " + tempFolder.getAbsolutePath());

                    // Download the folder
                    LogUtils.d("FileBrowserActivity", "Starting download of folder: " + fileToDownload.getName());
                    viewModel.downloadFolder(fileToDownload, tempFolder, new FileBrowserViewModel.DownloadCallback() {
                        @Override
                        public void onResult(boolean success, String message) {
                            LogUtils.d("FileBrowserActivity", "Download result: " + (success ? "success" : "failure") + " - " + message);
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (success) {
                                        LogUtils.d("FileBrowserActivity", "Download successful, copying to destination URI");
                                        // Copy the downloaded folder contents to the selected location
                                        try {
                                            copyFolderContentsToDocumentFile(tempFolder, subFolder);
                                            LogUtils.i("FileBrowserActivity", "Folder downloaded and saved successfully: " + fileToDownload.getName());
                                            Snackbar.make(findViewById(android.R.id.content), R.string.download_success, Snackbar.LENGTH_LONG).show();
                                        } catch (Exception e) {
                                            LogUtils.e("FileBrowserActivity", "Error copying folder to destination: " + e.getMessage());
                                            Snackbar.make(findViewById(android.R.id.content), getString(R.string.download_error) + ": " + e.getMessage(), Snackbar.LENGTH_LONG).show();
                                        } finally {
                                            // Delete the temporary folder
                                            LogUtils.d("FileBrowserActivity", "Deleting temporary folder: " + tempFolder.getAbsolutePath());
                                            deleteRecursive(tempFolder);
                                        }
                                    } else {
                                        LogUtils.e("FileBrowserActivity", "Download failed: " + message);

                                        // Create a more user-friendly message for folder not found errors
                                        String userMessage;
                                        if (message.contains("Folder not found")) {
                                            String folderName = fileToDownload.getName();
                                            userMessage = getString(R.string.download_error) + ": " + getString(R.string.file_not_found, folderName);
                                            LogUtils.d("FileBrowserActivity", "Showing user-friendly folder not found message");
                                        } else {
                                            userMessage = getString(R.string.download_error) + ": " + message;
                                        }

                                        Snackbar.make(findViewById(android.R.id.content), userMessage, Snackbar.LENGTH_LONG).show();
                                    }
                                }
                            });
                        }
                    });
                } catch (Exception e) {
                    LogUtils.e("FileBrowserActivity", "Error setting up folder download: " + e.getMessage());
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.download_error) + ": " + e.getMessage(), Snackbar.LENGTH_LONG).show();
                }
            } else {
                LogUtils.w("FileBrowserActivity", "Null URI or fileToDownload in onActivityResult");
            }
        } else {
            LogUtils.d("FileBrowserActivity", "Activity result not handled: requestCode=" + requestCode + ", resultCode=" + resultCode);
        }
    }

    /**
     * Copies a file to a content URI.
     */
    private void copyFileToUri(File file, Uri uri) throws Exception {
        LogUtils.d("FileBrowserActivity", "Copying file to URI: " + uri + ", file size: " + file.length() + " bytes");
        try (FileOutputStream outputStream = (FileOutputStream) getContentResolver().openOutputStream(uri); java.io.FileInputStream inputStream = new java.io.FileInputStream(file)) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalBytesCopied = 0;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                totalBytesCopied += bytesRead;
            }
            LogUtils.d("FileBrowserActivity", "File copied successfully: " + totalBytesCopied + " bytes");
        } catch (Exception e) {
            LogUtils.e("FileBrowserActivity", "Error copying file to URI: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Copies the contents of a folder to a DocumentFile.
     */
    private void copyFolderContentsToDocumentFile(File sourceFolder, DocumentFile destFolder) throws Exception {
        LogUtils.d("FileBrowserActivity", "Copying folder contents from: " + sourceFolder.getAbsolutePath() + " to: " + destFolder.getUri());

        File[] files = sourceFolder.listFiles();
        if (files == null) {
            LogUtils.w("FileBrowserActivity", "No files found in source folder: " + sourceFolder.getAbsolutePath());
            return;
        }

        LogUtils.d("FileBrowserActivity", "Found " + files.length + " items to copy");

        for (File file : files) {
            if (file.isDirectory()) {
                // Create a subfolder in the destination
                LogUtils.d("FileBrowserActivity", "Creating subfolder: " + file.getName());
                DocumentFile newFolder = destFolder.createDirectory(file.getName());
                if (newFolder == null) {
                    LogUtils.e("FileBrowserActivity", "Failed to create subfolder: " + file.getName());
                    throw new IOException("Failed to create subfolder: " + file.getName());
                }

                // Recursively copy the subfolder contents
                copyFolderContentsToDocumentFile(file, newFolder);
            } else {
                // Create a file in the destination
                LogUtils.d("FileBrowserActivity", "Creating file: " + file.getName());
                DocumentFile newFile = destFolder.createFile("*/*", file.getName());
                if (newFile == null) {
                    LogUtils.e("FileBrowserActivity", "Failed to create file: " + file.getName());
                    throw new IOException("Failed to create file: " + file.getName());
                }

                // Copy the file contents
                Uri uri = newFile.getUri();
                try (OutputStream outputStream = getContentResolver().openOutputStream(uri); java.io.FileInputStream inputStream = new java.io.FileInputStream(file)) {

                    if (outputStream == null) {
                        LogUtils.e("FileBrowserActivity", "Failed to open output stream for: " + uri);
                        throw new IOException("Failed to open output stream");
                    }

                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalBytesCopied = 0;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        totalBytesCopied += bytesRead;
                    }
                    LogUtils.d("FileBrowserActivity", "File copied successfully: " + file.getName() + ", " + totalBytesCopied + " bytes");
                }
            }
        }

        LogUtils.i("FileBrowserActivity", "Folder contents copied successfully");
    }

    /**
     * Recursively deletes a file or directory.
     */
    private void deleteRecursive(File fileOrDirectory) {
        LogUtils.d("FileBrowserActivity", "Deleting: " + fileOrDirectory.getAbsolutePath());

        if (fileOrDirectory.isDirectory()) {
            File[] files = fileOrDirectory.listFiles();
            if (files != null) {
                for (File child : files) {
                    deleteRecursive(child);
                }
            }
        }

        boolean deleted = fileOrDirectory.delete();
        if (!deleted) {
            LogUtils.w("FileBrowserActivity", "Failed to delete: " + fileOrDirectory.getAbsolutePath());
        } else {
            LogUtils.d("FileBrowserActivity", "Successfully deleted: " + fileOrDirectory.getAbsolutePath());
        }
    }

    /**
     * Handles the file upload process.
     *
     * @param uri The URI of the selected file
     */
    private void handleFileUpload(Uri uri) {
        LogUtils.d("FileBrowserActivity", "Handling file upload from URI: " + uri);
        try {
            // Show a progress indicator
            Toast.makeText(this, R.string.uploading, Toast.LENGTH_SHORT).show();

            // Get the file name from the URI
            String fileName = getFileNameFromUri(uri);
            if (fileName == null) {
                LogUtils.w("FileBrowserActivity", "Could not determine filename from URI, using timestamp");
                fileName = "uploaded_file_" + System.currentTimeMillis();
            }
            LogUtils.d("FileBrowserActivity", "File name for upload: " + fileName);

            // Create a temporary file to store the content
            File tempFile = File.createTempFile("upload", ".tmp", getCacheDir());
            LogUtils.d("FileBrowserActivity", "Created temporary file for upload: " + tempFile.getAbsolutePath());

            // Copy the content from the URI to the temporary file
            LogUtils.d("FileBrowserActivity", "Copying content from URI to temporary file");
            try (InputStream inputStream = getContentResolver().openInputStream(uri); FileOutputStream outputStream = new FileOutputStream(tempFile)) {

                if (inputStream == null) {
                    LogUtils.e("FileBrowserActivity", "Failed to open input stream from URI");
                    throw new IOException("Failed to open input stream");
                }

                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytesCopied = 0;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    totalBytesCopied += bytesRead;
                }
                LogUtils.d("FileBrowserActivity", "Content copied to temporary file: " + totalBytesCopied + " bytes");
            }

            // Get the current path for upload
            String currentPath = viewModel.getCurrentPath().getValue();
            if (currentPath == null) {
                currentPath = "";
            }
            LogUtils.d("FileBrowserActivity", "Current path for upload: " + currentPath);

            // Construct the remote path
            String remotePath;
            if (currentPath.equals("root")) {
                // If at root directory, don't add the "root/" prefix
                remotePath = fileName;
            } else {
                remotePath = currentPath + "/" + fileName;
            }
            LogUtils.d("FileBrowserActivity", "Remote path for upload: " + remotePath);

            // Create final copies for use in lambda
            final String finalFileName = fileName;
            final File finalTempFile = tempFile;

            // Upload the file with file existence check
            LogUtils.d("FileBrowserActivity", "Starting upload to remote path: " + remotePath);
            viewModel.uploadFile(finalTempFile, remotePath, new FileBrowserViewModel.UploadCallback() {
                @Override
                public void onResult(boolean success, String message) {
                    LogUtils.d("FileBrowserActivity", "Upload result: " + (success ? "success" : "failure") + " - " + message);
                    runOnUiThread(() -> {
                        if (success) {
                            Toast.makeText(FileBrowserActivity.this, "Upload erfolgreich", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(FileBrowserActivity.this, "Upload fehlgeschlagen: " + message, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }, (existingFileName, confirmAction) -> {
                // This is the FileExistsCallback that will be called if the file exists
                runOnUiThread(() -> {
                    new AlertDialog.Builder(FileBrowserActivity.this).setTitle(R.string.file_exists).setMessage(getString(R.string.file_exists_message, existingFileName)).setPositiveButton(R.string.overwrite, (dialog, which) -> {
                        // Datei überschreiben
                        confirmAction.run();
                    }).setNegativeButton(R.string.cancel, null).show();
                });
            }, finalFileName);
        } catch (Exception e) {
            LogUtils.e("FileBrowserActivity", "Error handling file upload: " + e.getMessage());
            Snackbar.make(findViewById(android.R.id.content), getString(R.string.upload_error) + ": " + e.getMessage(), Snackbar.LENGTH_LONG).show();
        }
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

    @Override
    public boolean onFileLongClick(SmbFileItem file) {
        LogUtils.d("FileBrowserActivity", "File long clicked: " + file.getName() + ", isDirectory: " + file.isDirectory());
        // Show options dialog with all options
        showFileOptionsDialog(file, true); // true indicates it's from a long click action
        return true; // Return true to indicate the long click was handled
    }

    @Override
    public boolean onParentDirectoryLongClick() {
        LogUtils.d("FileBrowserActivity", "Parent directory long clicked");
        // No special handling needed for parent directory long click
        return false; // Return false to allow other handlers to process this event
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
        startActivityForResult(intent, REQUEST_CODE_PICK_FILE);
    }

    /**
     * Shows a dialog for creating a new folder.
     */
    private void showCreateFolderDialog() {
        LogUtils.d("FileBrowserActivity", "Showing create folder dialog");

        // Inflate the dialog layout
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_create_folder, null);
        LogUtils.d("FileBrowserActivity", "Dialog view inflated");

        // Get the folder name input field
        com.google.android.material.textfield.TextInputEditText folderNameEditText = dialogView.findViewById(R.id.folder_name_edit_text);
        com.google.android.material.textfield.TextInputLayout folderNameLayout = dialogView.findViewById(R.id.folder_name_layout);
        LogUtils.d("FileBrowserActivity", "Dialog views retrieved");

        // Create the dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.create_folder_dialog_title).setView(dialogView).setPositiveButton(R.string.create_folder, null) // Set in onShowListener to prevent auto-dismiss
                .setNegativeButton(R.string.cancel, (dialog, which) -> {
                    LogUtils.d("FileBrowserActivity", "Create folder dialog cancelled");
                    dialog.dismiss();
                });

        // Create and show the dialog
        AlertDialog dialog = builder.create();
        LogUtils.d("FileBrowserActivity", "Dialog created");

        // Set up the positive button click listener
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialogInterface) {
                Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                positiveButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        LogUtils.d("FileBrowserActivity", "Create folder button clicked in dialog");

                        // Get the folder name
                        String folderName = folderNameEditText.getText().toString().trim();
                        LogUtils.d("FileBrowserActivity", "Folder name entered: " + folderName);

                        // Validate the folder name
                        if (folderName.isEmpty()) {
                            LogUtils.w("FileBrowserActivity", "Folder name is empty");
                            folderNameLayout.setError("Folder name is required");
                            return;
                        }

                        // Clear any previous errors
                        folderNameLayout.setError(null);

                        // Create the folder
                        LogUtils.d("FileBrowserActivity", "Creating folder: " + folderName);
                        viewModel.createFolder(folderName, new FileBrowserViewModel.CreateFolderCallback() {
                            @Override
                            public void onResult(boolean success, String message) {
                                LogUtils.d("FileBrowserActivity", "Folder creation result: " + (success ? "success" : "failure") + " - " + message);
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (success) {
                                            LogUtils.i("FileBrowserActivity", "Folder created successfully: " + folderName);
                                            Snackbar.make(findViewById(android.R.id.content), R.string.folder_created, Snackbar.LENGTH_LONG).show();
                                            dialog.dismiss();
                                        } else {
                                            LogUtils.e("FileBrowserActivity", "Folder creation failed: " + message);
                                            Snackbar.make(findViewById(android.R.id.content), getString(R.string.folder_creation_error) + ": " + message, Snackbar.LENGTH_LONG).show();
                                        }
                                    }
                                });
                            }
                        });
                    }
                });
            }
        });

        dialog.show();
        LogUtils.d("FileBrowserActivity", "Dialog shown");

        // Set focus to the folder name input field
        // Ensure any previous focus is cleared first
        View currentFocus = getCurrentFocus();
        if (currentFocus != null && currentFocus != folderNameEditText) {
            currentFocus.clearFocus();
        }
        folderNameEditText.requestFocus();
        KeyboardUtils.showKeyboard(this, folderNameEditText);
        LogUtils.d("FileBrowserActivity", "Focus set to folder name input field");
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
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
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
}
