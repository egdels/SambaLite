package de.schliweb.sambalite.ui.controllers;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.util.LogUtils;
import lombok.Setter;

/**
 * The ActivityResultController is responsible for managing activity result workflows, including
 * file and folder selection, creation, and upload operations. It provides an interface to register
 * callbacks for handling results from these operations and ensures UI state updates and keyboard
 * management during interactions.
 */
public class ActivityResultController {

    private final AppCompatActivity activity;
    private final FileBrowserUIState uiState;
    private final InputController inputController;

    // Activity Result Launchers
    private ActivityResultLauncher<Intent> createFileLauncher;
    private ActivityResultLauncher<Intent> pickFileLauncher;
    private ActivityResultLauncher<Intent> createFolderLauncher;
    private ActivityResultLauncher<Intent> pickFolderLauncher;

    @Setter
    private FileOperationCallback fileOperationCallback;

    /**
     * Constructs an instance of ActivityResultController.
     * <p>
     * This controller is responsible for managing activity result launchers
     * used for file and folder operations and interacts with the UI state
     * and input controller of the file browser.
     *
     * @param activity        The associated AppCompatActivity that uses this controller
     * @param uiState         The shared UI state of the file browser
     * @param inputController The input controller for handling keyboard and focus management
     */
    public ActivityResultController(AppCompatActivity activity, FileBrowserUIState uiState, InputController inputController) {
        this.activity = activity;
        this.uiState = uiState;
        this.inputController = inputController;

        // Initialize activity result launchers
        initializeActivityResultLaunchers();

        LogUtils.d("ActivityResultController", "ActivityResultController initialized");
    }

    /**
     * Initializes the activity result launchers.
     */
    private void initializeActivityResultLaunchers() {
        createFileLauncher = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> handleDocumentResult(result, "create_file"));

        pickFileLauncher = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> handleDocumentResult(result, "pick_file"));

        createFolderLauncher = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> handleDocumentResult(result, "create_folder"));

        pickFolderLauncher = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> handleDocumentResult(result, "pick_folder"));

        LogUtils.d("ActivityResultController", "Activity result launchers initialized");
    }

    /**
     * Handles results from activity result launchers.
     *
     * @param result    The activity result
     * @param operation The operation type
     */
    private void handleDocumentResult(ActivityResult result, String operation) {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            Uri uri = result.getData().getData();
            if (uri != null) {
                uiState.setSelectedUri(uri);

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
                    case "pick_folder":
                        handlePickFolderResult(uri);
                        break;
                }
            }
        }
    }

    /**
     * Handles a file pick result.
     *
     * @param uri The URI of the picked file
     */
    private void handlePickFileResult(Uri uri) {
        // Clean up UI state first
        inputController.hideKeyboardAndClearFocus();

        // Delegate to the file operation callback
        if (fileOperationCallback != null) {
            fileOperationCallback.onFileUploadResult(uri);
        }
    }

    /**
     * Handles a file creation result.
     *
     * @param uri The URI of the created file
     */
    private void handleCreateFileResult(Uri uri) {
        // Clean up UI state first
        inputController.hideKeyboardAndClearFocus();

        // Delegate to the file operation callback
        if (fileOperationCallback != null) {
            fileOperationCallback.onFileDownloadResult(uri);
        }
    }

    /**
     * Handles a folder creation result.
     *
     * @param uri The URI of the created folder
     */
    private void handleCreateFolderResult(Uri uri) {
        // Clean up UI state first
        inputController.hideKeyboardAndClearFocus();

        // Delegate to the file operation callback
        if (fileOperationCallback != null) {
            fileOperationCallback.onFolderDownloadResult(uri);
        }
    }

    /**
     * Handles the result of picking a folder.
     * <p>
     * This method hides the keyboard, clears UI focus, and delegates the folder
     * upload result to the file operation callback, if available.
     *
     * @param uri The URI of the picked folder
     */
    private void handlePickFolderResult(Uri uri) {
        // Clean up UI state first
        inputController.hideKeyboardAndClearFocus();

        // Delegate to the file operation callback
        if (fileOperationCallback != null) {
            fileOperationCallback.onFolderUploadResult(uri);
        }
    }

    /**
     * Initiates the download of a file or folder.
     *
     * @param file The file or folder to download
     */
    public void initDownloadFileOrFolder(SmbFileItem file) {
        LogUtils.d("ActivityResultController", "Initiating download for: " + file.getName() + ", type: " + (file.isDirectory() ? "directory" : "file") + (file.isFile() ? ", size: " + file.getSize() + " bytes" : ""));

        // Store the file to download in the UI state
        uiState.setSelectedFile(file);

        if (file.isDirectory()) {
            // For directories, we need to create a folder picker
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            LogUtils.d("ActivityResultController", "Created folder picker intent for folder: " + file.getName());

            // Start the folder picker activity
            LogUtils.d("ActivityResultController", "Starting folder picker activity for download");
            createFolderLauncher.launch(intent);
        } else {
            // For files, create a file picker
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*"); // Allow any file type
            intent.putExtra(Intent.EXTRA_TITLE, file.getName()); // Suggest the file name
            LogUtils.d("ActivityResultController", "Created file picker intent with suggested name: " + file.getName());

            // Start the file picker activity
            LogUtils.d("ActivityResultController", "Starting file picker activity for download");
            createFileLauncher.launch(intent);
        }
    }

    /**
     * Initiates the file selection process for uploading.
     */
    public void selectFileToUpload() {
        LogUtils.d("ActivityResultController", "Initiating file selection for upload");
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*"); // Allow any file type
        LogUtils.d("ActivityResultController", "Starting file picker activity for upload");
        pickFileLauncher.launch(intent);
    }

    /**
     * Initiates folder selection for folder contents upload.
     */
    public void selectFolderToUpload() {
        LogUtils.d("ActivityResultController", "Selecting folder for folder contents upload");
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        pickFolderLauncher.launch(intent);
    }

    /**
     * Callback for file operations.
     */
    public interface FileOperationCallback {
        /**
         * Called when a file upload result is received.
         *
         * @param uri The URI of the file to upload
         */
        void onFileUploadResult(Uri uri);

        /**
         * Called when a file download result is received.
         *
         * @param uri The URI to save the downloaded file to
         */
        void onFileDownloadResult(Uri uri);

        /**
         * Called when a folder download result is received.
         *
         * @param uri The URI to save the downloaded folder to
         */
        void onFolderDownloadResult(Uri uri);


        /**
         * Called when a folder contents upload result is received.
         *
         * @param uri The URI of the folder to upload contents from
         */
        void onFolderUploadResult(Uri uri);
    }
}