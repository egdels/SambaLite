package de.schliweb.sambalite.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import de.schliweb.sambalite.R;
import de.schliweb.sambalite.SambaLiteApp;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.repository.ConnectionRepository;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.di.AppComponent;
import de.schliweb.sambalite.ui.controllers.DialogController;
import de.schliweb.sambalite.ui.controllers.FileBrowserUIState;
import de.schliweb.sambalite.ui.controllers.ProgressController;
import de.schliweb.sambalite.ui.dialogs.DialogHelper;
import de.schliweb.sambalite.ui.operations.FileOperationCallbacks;
import de.schliweb.sambalite.ui.operations.FileOperations;
import de.schliweb.sambalite.ui.utils.PreferenceUtils;
import de.schliweb.sambalite.util.LogUtils;


public class ShareReceiverActivity extends AppCompatActivity implements
        FileOperationCallbacks.UploadCallback,
        FileOperationCallbacks.FileExistsCallback {
    private static final String TAG = "ShareReceiverActivity";

    @Inject
    SmbRepository smbRepository;

    @Inject
    ConnectionRepository connectionRepository;

    @Inject
    ViewModelProvider.Factory viewModelFactory;

    @Inject
    FileBrowserUIState uiState;

    private ShareReceiverViewModel viewModel;

    private final List<Uri> shareUris = new ArrayList<>();
    private final List<File> tempFiles = new ArrayList<>(); // Track temp files for cleanup
    private int uploadedCount = 0;
    private int failedCount = 0;
    private ProgressController progressController;
    private String targetSmbFolder; // Store target folder for navigation
    private SmbConnection connection;

    private DialogController dialogController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Make activity visible and bring to front for progress dialog visibility
        setContentView(R.layout.activity_transparent);

        // Bring activity to front when upload starts
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Inject dependencies
        AppComponent appComponent = ((SambaLiteApp) getApplication()).getAppComponent();
        appComponent.inject(this);

        viewModel = new ViewModelProvider(this, viewModelFactory).get(ShareReceiverViewModel.class);
        progressController = new ProgressController(this);

        dialogController = new DialogController(this, viewModel, uiState);

        // Setup back press handling
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                LogUtils.d(TAG, "Back button pressed, finishing ShareReceiverActivity");
                finish();
            }
        });

        // Handle the incoming intent immediately
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        LogUtils.d(TAG, "Received intent: " + intent.getAction());

        // Check for shared content
        if (Intent.ACTION_SEND.equals(intent.getAction()) || Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            shareUris.clear();

            if (Intent.ACTION_SEND.equals(intent.getAction())) {
                Uri uri = getParcelableExtraCompat(intent, Intent.EXTRA_STREAM);
                if (uri != null) {
                    shareUris.add(uri);
                }
            } else {
                ArrayList<Uri> uris = getParcelableArrayListExtraCompat(intent, Intent.EXTRA_STREAM);
                if (uris != null) {
                    shareUris.addAll(uris);
                }
            }

            if (shareUris.isEmpty()) {
                finish();
                return;
            }

            // Check if current SMB folder is set
            String currentFolder = PreferenceUtils.getCurrentSmbFolder(this);
            LogUtils.d(TAG, "Retrieved saved folder from preferences: " + currentFolder);

            if (currentFolder == null || currentFolder.isEmpty()) {
                LogUtils.d(TAG, "No saved folder found in preferences");
                showNeedsTargetFolderDialog();
            } else {
                // Current folder exists, confirm upload
                showShareDialog(currentFolder);
            }
        }
    }

    private void showNeedsTargetFolderDialog() {
        dialogController.showNeedsTargetFolderDialog();
    }

    private void showShareDialog(String currentFolder) {
        LogUtils.d(TAG, "Loading connection from current folder: " + currentFolder);
        dialogController.showShareUploadConfirmationDialog(shareUris.size(), currentFolder, () -> {
            try {
                // Bring activity to foreground when starting upload
                bringToForeground();
                startUploads();
            } catch (IOException e) {
                LogUtils.e(TAG, "Error starting uploads: " + e.getMessage());
                DialogHelper.showErrorDialog(this, getString(R.string.upload_failed), e.getMessage());
                cleanupTempFiles(); // Cleanup on error
                finish();
            }
        }, () -> {
            // User cancelled the upload dialog - cleanup temp files before finishing
            LogUtils.d(TAG, "User cancelled upload dialog");
            cleanupTempFiles();
            finish();
        });
    }

    /**
     * Bring activity to foreground for progress dialog visibility
     */
    private void bringToForeground() {
        LogUtils.d(TAG, "Bringing ShareReceiverActivity to foreground for progress dialog");

        // Move task to front
        moveTaskToBack(false);

        // Start a new intent to bring this activity to front
        Intent intent = new Intent(this, ShareReceiverActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT |
                       Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private SmbConnection getConnectionFromTargetPath(String smbTargetFolder) {
        // Extract connection name from the target folder
        String connectionName = smbTargetFolder;
        int slashIdx = smbTargetFolder.indexOf("/");
        if (slashIdx > 0) {
            connectionName = smbTargetFolder.substring(0, slashIdx);
        }
        LogUtils.d(TAG, "Extracted connection name from target folder: " + connectionName);
        // Get the connection from the repository
        List<SmbConnection> connections = connectionRepository.getAllConnections();
        SmbConnection connection = null;
        for (SmbConnection conn : connections) {
            if (conn.getName().equals(connectionName)) {
                connection = conn;
            }
        }
        return connection;
    }

    private void startUploads() throws IOException {
        LogUtils.d(TAG, "Starting uploads for " + shareUris.size() + " items");
        targetSmbFolder = PreferenceUtils.getCurrentSmbFolder(this);
        connection = getConnectionFromTargetPath(targetSmbFolder);
        if (connection == null) {
            LogUtils.d(TAG, "No connection found for name " + targetSmbFolder);
            Toast.makeText(this, getString(R.string.upload_failed) , Toast.LENGTH_LONG).show();
            progressController.hideDetailedProgressDialog();
            finish();
            return;
        }

        viewModel.setConnection(connection);

        // Show progress dialog using the same ProgressController as the main app
        progressController.showDetailedProgressDialog(
            getString(R.string.uploading_files),
            getString(R.string.preparing_upload)
        );



        // Now start the actual uploads through the ViewModel
        for (Uri uri : shareUris) {
            File tempFile = FileOperations.createTempFileFromUri(this, uri);
            tempFiles.add(tempFile); // Add temp file to list for cleanup
            String fileName = FileOperations.getDisplayNameFromUri(this, uri);

            // Fix path construction to avoid duplication of share name
            // targetSmbFolder format: "connectionName/folder/subfolder"
            // We need to extract just the path after the connection name
            String smbTargetPath = getPathFromTargetFolder(targetSmbFolder);
            if (!smbTargetPath.endsWith("/")) smbTargetPath += "/";
            smbTargetPath += fileName;
            LogUtils.d(TAG, "Uploading " + fileName + " to " + smbTargetPath);
            viewModel.uploadFile(tempFile, smbTargetPath, this, this, fileName);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LogUtils.d(TAG, "onDestroy called, checking upload status");
        // Check if there are any uploads in progress
        if (uploadedCount + failedCount < shareUris.size()) {
            LogUtils.d(TAG, "Cancelling incomplete uploads in onDestroy");
            viewModel.cancelUpload();
        } else {
            LogUtils.d(TAG, "All uploads completed, no need to cancel");
        }
        progressController.hideDetailedProgressDialog();

        // Always cleanup temporary files when activity is destroyed
        cleanupTempFiles();
    }

    /**
     * Clean up all temporary files created during the share operation
     */
    private void cleanupTempFiles() {
        LogUtils.d(TAG, "Cleaning up " + tempFiles.size() + " temporary files");
        int deletedCount = 0;
        int failedDeleteCount = 0;

        for (File tempFile : tempFiles) {
            if (tempFile != null && tempFile.exists()) {
                boolean deleted = tempFile.delete();
                if (deleted) {
                    deletedCount++;
                    LogUtils.d(TAG, "Successfully deleted temp file: " + tempFile.getAbsolutePath());
                } else {
                    failedDeleteCount++;
                    LogUtils.w(TAG, "Failed to delete temp file: " + tempFile.getAbsolutePath());
                }
            }
        }

        LogUtils.d(TAG, "Temp file cleanup completed - Deleted: " + deletedCount + ", Failed: " + failedDeleteCount);
        tempFiles.clear();
    }

    @Override
    public void onResult(final boolean success, final String message) {
        runOnUiThread(() -> {
            if (success) {
                LogUtils.d(TAG, "Upload successful: " + message);
                uploadedCount++;
                Toast.makeText(this, getString(R.string.upload_success) + ": " + message, Toast.LENGTH_SHORT).show();
            } else {
                LogUtils.d(TAG, "Upload failed: " + message);
                failedCount++;
                Toast.makeText(this, getString(R.string.upload_failed) + ": " + message, Toast.LENGTH_LONG).show();
            }

            // Check if all uploads are completed
            if (uploadedCount + failedCount == shareUris.size()) {
                progressController.hideDetailedProgressDialog();

                // Cleanup temporary files now that all uploads are completed
                cleanupTempFiles();

                // If at least one upload was successful and we have a target folder, navigate to it
                if (uploadedCount > 0 && targetSmbFolder != null) {
                    showUploadCompleteDialog();
                } else {
                    finish();
                }
            }
        });
    }

    @Override
    public void onProgress(final String status, final int percentage) {
        runOnUiThread(() -> {
            LogUtils.d(TAG, "Upload-Fortschritt: " + status + " (" + percentage + "%)");
            progressController.updateDetailedProgress(percentage, status, status);
        });
    }

    @Override
    public void onFileExists(final String fileName, final Runnable confirmAction, final Runnable cancelAction) {
        runOnUiThread(() -> {
            LogUtils.d(TAG, "Datei existiert bereits: " + fileName);
            dialogController.showFileExistsDialog(fileName, confirmAction, cancelAction);
        });
    }

    /**
     * Shows dialog after successful uploads with option to view uploaded files
     */
    private void showUploadCompleteDialog() {
        dialogController.showUploadCompleteDialog(uploadedCount, shareUris.size(), failedCount,
            this::navigateToUploadedFolder);
    }

    /**
     * Navigate to the FileBrowserActivity showing the folder where files were uploaded
     */
    private void navigateToUploadedFolder() {
        String folderPath = getPathFromTargetFolder(targetSmbFolder);
        String connectionId = connection.getId();
        LogUtils.d(TAG, "Navigating to uploaded folder: " + folderPath);
        LogUtils.d(TAG, "Connection id found: " + connectionId);
        Intent intent = FileBrowserActivity.createIntentFromUploadNotification(this, connectionId, folderPath);
        startActivity(intent);
        finish();
    }

    /**
     * Extract path from target folder (everything after connection name)
     */
    private String getPathFromTargetFolder(String targetFolder) {
        if (targetFolder == null) return "/";
        int slashIdx = targetFolder.indexOf("/");
        if (slashIdx > 0) {
            // Extract path after connection name, but remove leading slash to avoid double slashes
            String path = targetFolder.substring(slashIdx + 1);
            return path.isEmpty() ? "/" : path;
        }
        return "/";
    }

    /**
     * Android API compatibility helper for getParcelableExtra
     */
    @SuppressWarnings("deprecation")
    private Uri getParcelableExtraCompat(Intent intent, String key) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(key, Uri.class);
        } else {
            return intent.getParcelableExtra(key);
        }
    }

    /**
     * Android API compatibility helper for getParcelableArrayListExtra
     */
    @SuppressWarnings("deprecation")
    private ArrayList<Uri> getParcelableArrayListExtraCompat(Intent intent, String key) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableArrayListExtra(key, Uri.class);
        } else {
            return intent.getParcelableArrayListExtra(key);
        }
    }
}
