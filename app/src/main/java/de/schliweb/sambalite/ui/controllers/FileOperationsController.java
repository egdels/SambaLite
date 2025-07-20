package de.schliweb.sambalite.ui.controllers;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import androidx.documentfile.provider.DocumentFile;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.ui.FileListViewModel;
import de.schliweb.sambalite.ui.operations.FileOperationCallbacks;
import de.schliweb.sambalite.ui.operations.FileOperations;
import de.schliweb.sambalite.ui.operations.FileOperationsViewModel;
import de.schliweb.sambalite.util.LogUtils;
import lombok.Setter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Controller for handling file operations in the FileBrowserActivity.
 * Responsible for file operations such as download, upload, delete, rename, and ZIP operations.
 * <p>
 * This controller provides a unified approach to file operations with consistent progress tracking,
 * error handling, and success notification across all operation types. It uses a set of helper methods
 * to standardize how progress is displayed and how operation results are handled.
 */
public class FileOperationsController {

    // Operation type constants for consistent naming
    private static final String OPERATION_DOWNLOAD = "download";
    private static final String OPERATION_UPLOAD = "upload";
    private static final String OPERATION_FOLDER_UPLOAD = "folderContentsUpload";
    private static final String OPERATION_DELETE = "delete";
    private static final String OPERATION_RENAME = "rename";

    private final Context context;
    private final FileOperationsViewModel operationsViewModel;
    private final FileListViewModel fileListViewModel;
    private final FileBrowserUIState uiState;
    // Listener management
    private final List<FileOperationListener> listeners = new ArrayList<>();

    /**
     * User feedback provider for showing success, error, and info messages.
     * This replaces the ProgressCallback for user feedback, providing a more
     * standardized approach across controllers.
     */
    @Setter
    private UserFeedbackProvider userFeedbackProvider;

    /**
     * Progress callback for backward compatibility.
     *
     * @deprecated Use userFeedbackProvider instead for user feedback.
     */
    @Deprecated
    @Setter
    private ProgressCallback progressCallback;

    @Setter
    private ServiceCallback serviceCallback;

    @Setter
    private ActivityResultController activityResultController;

    @Setter
    private DialogController dialogController; // Added for confirmation dialogs

    /**
     * Creates a new FileOperationsController.
     *
     * @param context             The context
     * @param operationsViewModel The FileOperationsViewModel for file operations
     * @param fileListViewModel   The FileListViewModel for file list operations
     *                            FileBrowserViewModel parameter has been removed as it's no longer needed
     * @param uiState             The shared UI state
     */
    public FileOperationsController(Context context, FileOperationsViewModel operationsViewModel, FileListViewModel fileListViewModel, FileBrowserUIState uiState) {
        this.context = context;
        this.operationsViewModel = operationsViewModel;
        this.fileListViewModel = fileListViewModel;
        this.uiState = uiState;
        this.dialogController = null; // Will be set later via setDialogController
    }

    /**
     * Adds a listener for file operation events.
     *
     * @param listener The listener to add
     */
    public void addListener(FileOperationListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Removes a listener for file operation events.
     *
     * @param listener The listener to remove
     */
    public void removeListener(FileOperationListener listener) {
        listeners.remove(listener);
    }

    /**
     * Notifies all listeners that a file operation has started.
     *
     * @param operationType The type of operation
     * @param file          The file being operated on
     */
    private void notifyOperationStarted(String operationType, SmbFileItem file) {
        for (FileOperationListener listener : listeners) {
            listener.onFileOperationStarted(operationType, file);
        }
    }

    /**
     * Notifies all listeners that a file operation has completed.
     *
     * @param operationType The type of operation
     * @param file          The file that was operated on
     * @param success       Whether the operation was successful
     * @param message       A message describing the result
     */
    private void notifyOperationCompleted(String operationType, SmbFileItem file, boolean success, String message) {
        for (FileOperationListener listener : listeners) {
            listener.onFileOperationCompleted(operationType, file, success, message);
        }
    }

    /**
     * Notifies all listeners of progress during a file operation.
     *
     * @param operationType The type of operation
     * @param file          The file being operated on
     * @param progress      The progress percentage (0-100)
     * @param message       A message describing the progress
     */
    private void notifyOperationProgress(String operationType, SmbFileItem file, int progress, String message) {
        for (FileOperationListener listener : listeners) {
            listener.onFileOperationProgress(operationType, file, progress, message);
        }
    }

    /**
     * Updates progress for any operation type with consistent formatting.
     * This method centralizes progress updates to ensure consistent behavior across all operation types.
     * It's a key part of the unified progress tracking system and should be used by all callback implementations
     * to ensure a consistent user experience.
     * <p>
     * The method handles both file and folder operations, using either the file object or the provided
     * item name for display. It updates the progress dialog and notifies listeners about the progress.
     *
     * @param operationType The type of operation (use OPERATION_* constants)
     * @param file          The file being operated on (can be null for folder operations)
     * @param itemName      The name of the file or folder for display (used if file is null)
     * @param percentage    The progress percentage (0-100)
     * @param status        The status message
     */
    private void updateOperationProgress(String operationType, SmbFileItem file, String itemName, int percentage, String status) {
        // Get the display name - either from the file object or the provided itemName
        String displayName = (file != null) ? file.getName() : itemName;

        // Update the progress dialog if available
        if (progressCallback != null) {
            progressCallback.updateDetailedProgress(percentage, status, displayName);
        }

        // Notify listeners about progress
        notifyOperationProgress(operationType, file, percentage, status);

        LogUtils.d("FileOperationsController", "Progress update: " + operationType + " - " + percentage + "% - " + status);
    }

    /**
     * Updates progress to show finalizing state for any operation.
     * This provides a consistent "finalizing" message across all operation types.
     * <p>
     * Part of the unified progress tracking system, this method ensures that all operations
     * show a standardized "Finalizing..." message at 99% completion. This gives users a clear
     * indication that the operation is almost complete and provides a consistent experience
     * across different operation types.
     *
     * @param operationType The type of operation (use OPERATION_* constants)
     * @param file          The file being operated on (can be null for folder operations)
     * @param itemName      The name of the file or folder for display (used if file is null)
     * @param isFolder      Whether the operation is on a folder (affects message formatting)
     */
    private void updateFinalizingProgress(String operationType, SmbFileItem file, String itemName, boolean isFolder) {
        String operationName = operationType.equals(OPERATION_UPLOAD) || operationType.equals(OPERATION_FOLDER_UPLOAD) ? "upload" : "download";
        String itemType = isFolder ? "folder" : "file";
        String status = "Finalizing " + itemType + " " + operationName + "...";

        // Update progress to 99% with finalizing message
        updateOperationProgress(operationType, file, itemName, 99, status);
    }

    /**
     * Gets a FileOperationRequester implementation that can be used by other controllers.
     *
     * @return The FileOperationRequester implementation
     */
    public FileOperationRequester getFileOperationRequester() {
        return new FileOperationRequesterImpl();
    }

    /**
     * Handles downloading a file.
     *
     * @param uri The URI to save the downloaded file to
     */
    public void handleFileDownload(Uri uri) {
        if (uiState.getSelectedFile() == null) return;

        // The operation was already started in requestFileDownload
        // Here we just execute the actual download
        executeFileOperation(() -> {
            File tempFile = File.createTempFile("download", ".tmp", context.getCacheDir());
            uiState.setTempFile(tempFile);

            // Create a progress callback to ensure continuous progress updates
            FileOperationCallbacks.ProgressCallback fileProgressCallback = new FileOperationCallbacks.ProgressCallback() {
                @Override
                public void updateFileProgress(int currentFile, int totalFiles, String currentFileName) {
                    // Not used for single file downloads
                }

                @Override
                public void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
                    // Calculate percentage based on bytes
                    int percentage = totalBytes > 0 ? (int) ((currentBytes * 100) / totalBytes) : 0;

                    // Format bytes as KB/MB/GB for display
                    String currentSize = formatBytes(currentBytes);
                    String totalSize = formatBytes(totalBytes);

                    updateOperationProgress(OPERATION_DOWNLOAD, uiState.getSelectedFile(), null, percentage, "Downloading: " + currentSize + " / " + totalSize);
                }

                @Override
                public void updateProgress(String progressInfo) {
                    // Extract percentage from progress info if possible
                    int percentage = 0;
                    if (progressInfo != null && progressInfo.contains("%")) {
                        try {
                            String percentStr = progressInfo.substring(progressInfo.indexOf(":") + 1, progressInfo.indexOf("%")).trim();
                            percentage = Integer.parseInt(percentStr);
                        } catch (Exception e) {
                            // Use default percentage if parsing fails
                        }
                    }
                    updateOperationProgress(OPERATION_DOWNLOAD, uiState.getSelectedFile(), null, percentage, progressInfo);
                }

                // Simple utility method to format bytes as KB/MB/GB
                private String formatBytes(long bytes) {
                    if (bytes < 1024) return bytes + " B";
                    int exp = (int) (Math.log(bytes) / Math.log(1024));
                    String pre = "KMGTPE".charAt(exp - 1) + "";
                    return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
                }
            };

            operationsViewModel.downloadFile(uiState.getSelectedFile(), tempFile, createFileDownloadCallbackWithNotification(tempFile, uri, uiState.getSelectedFile()), fileProgressCallback);
        }, "Downloading", "download");
    }

    /**
     * Handles downloading a folder.
     *
     * @param uri The URI to save the downloaded folder to
     */
    public void handleFolderDownload(Uri uri) {
        if (uiState.getSelectedFile() == null || !uiState.getSelectedFile().isDirectory()) return;

        executeFileOperation(() -> {
            DocumentFile destFolder = createDestinationFolder(uri);
            File tempFolder = createTempFolder();
            uiState.setTempFile(tempFolder);

            // Create a progress callback to ensure continuous progress updates
            FileOperationCallbacks.ProgressCallback folderProgressCallback = new FileOperationCallbacks.ProgressCallback() {
                @Override
                public void updateFileProgress(int currentFile, int totalFiles, String currentFileName) {
                    // Calculate percentage based on current file and total files
                    int percentage = totalFiles > 0 ? (currentFile * 100) / totalFiles : 0;
                    updateOperationProgress(OPERATION_DOWNLOAD, uiState.getSelectedFile(), null, percentage, "Downloading: " + currentFile + "/" + totalFiles + " - " + currentFileName);
                }

                @Override
                public void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
                    // Simple percentage calculation
                    int percentage = totalBytes > 0 ? (int) ((currentBytes * 100) / totalBytes) : 0;

                    // Format bytes as KB/MB/GB for display
                    String currentSize = formatBytes(currentBytes);
                    String totalSize = formatBytes(totalBytes);

                    updateOperationProgress(OPERATION_DOWNLOAD, uiState.getSelectedFile(), null, percentage, "Downloading: " + currentSize + " / " + totalSize);
                }

                @Override
                public void updateProgress(String progressInfo) {
                    // Extract percentage from progress info if possible
                    int percentage = 0;
                    if (progressInfo != null && progressInfo.contains("%")) {
                        try {
                            String percentStr = progressInfo.substring(progressInfo.indexOf(":") + 1, progressInfo.indexOf("%")).trim();
                            percentage = Integer.parseInt(percentStr);
                        } catch (Exception e) {
                            // Use default percentage if parsing fails
                        }
                    }
                    updateOperationProgress(OPERATION_DOWNLOAD, uiState.getSelectedFile(), null, percentage, progressInfo);
                }

                // Simple utility method to format bytes as KB/MB/GB
                private String formatBytes(long bytes) {
                    if (bytes < 1024) return bytes + " B";
                    int exp = (int) (Math.log(bytes) / Math.log(1024));
                    String pre = "KMGTPE".charAt(exp - 1) + "";
                    return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
                }
            };

            operationsViewModel.downloadFolder(uiState.getSelectedFile(), tempFolder, createFolderDownloadCallbackWithNotification(tempFolder, destFolder, uiState.getSelectedFile()), folderProgressCallback);
        }, "Downloading", "download");
    }

    /**
     * Handles uploading a file.
     *
     * @param uri The URI of the file to upload
     */
    public void handleFileUpload(Uri uri) {
        executeFileOperation(() -> {
            String fileName = getFileNameFromUri(uri);
            if (fileName == null) fileName = "uploaded_file_" + System.currentTimeMillis();

            File tempFile = File.createTempFile("upload", ".tmp", context.getCacheDir());
            uiState.setTempFile(tempFile);

            FileOperations.copyUriToFile(uri, tempFile, context);
            String remotePath = buildRemotePath(fileName);

            // Use null for the file parameter since this is a URI-based upload
            operationsViewModel.uploadFile(tempFile, remotePath, createUploadCallbackWithNotification(null, fileName), createFileExistsCallback(), fileName);
        }, "Uploading", "upload");
    }

    /**
     * Handles uploading folder contents as individual files.
     *
     * @param folderUri The URI of the folder to upload
     */
    public void handleFolderContentsUpload(Uri folderUri) {
        DocumentFile docFolder = DocumentFile.fromTreeUri(context, folderUri);
        String folderName = getDocumentFileName(docFolder, "folder");

        LogUtils.d("FileOperationsController", "Starting folder contents upload: " + folderName);

        // Disable ZIP buttons during folder upload
        if (progressCallback != null) {
            progressCallback.setZipButtonsEnabled(false);
        }

        // Use executeFileOperation for consistent progress dialog handling
        executeFileOperation(() -> {
            // Use FileOperationsViewModel for folder contents operations
            operationsViewModel.uploadFolderContentsFromUri(folderUri, createFolderContentsUploadCallbackWithNotification(folderName), createFileExistsCallback());
        }, "Uploading Folder: " + folderName, "upload");
    }

    /**
     * Executes a file operation with progress tracking and error handling.
     * This method serves as the central execution point for all file operations and manages the entire operation lifecycle:
     * 1. Shows the initial progress dialog
     * 2. Sets up cancellation handling
     * 3. Notifies the background service about operation start
     * 4. Executes the operation on a background thread
     * 5. Handles successful completion and errors
     * 6. Notifies the background service about operation completion
     * 7. Hides the progress dialog after completion
     * <p>
     * The actual operation is executed via the provided operation lambda, which typically creates and uses
     * operation-specific callbacks (created by methods like createFileDownloadCallbackWithNotification).
     * These callbacks handle progress updates and operation-specific success/error handling, while this method
     * manages the overall operation lifecycle.
     *
     * @param operation     The operation to execute as a lambda
     * @param operationName The name of the operation for display
     * @param operationType The type of operation (use OPERATION_* constants)
     */
    private void executeFileOperation(FileOperation operation, String operationName, String operationType) {
        // Show only the detailed progress dialog for better user experience
        LogUtils.d("FileOperationsController", "Starting file operation with detailed progress dialog");

        if (progressCallback != null) {
            // Show detailed progress dialog immediately with initial progress
            // This ensures the dialog is displayed even if progress updates are delayed or infrequent
            String initialMessage = operationType.equals("upload") ? "Preparing upload..." : "Preparing download...";
            progressCallback.showDetailedProgressDialog(operationName, initialMessage);
            progressCallback.updateDetailedProgress(0, initialMessage, uiState.getSelectedFile() != null ? uiState.getSelectedFile().getName() : "");

            // Set cancel action for the detailed progress dialog
            if (progressCallback instanceof ProgressController progressController) {
                progressController.setDetailedProgressDialogCancelAction(() -> {
                    LogUtils.d("FileOperationsController", "User requested " + operationType + " cancellation");

                    // Call appropriate cancellation method based on operation type
                    if (operationType.equals("upload")) {
                        operationsViewModel.cancelUpload();
                    } else {
                        operationsViewModel.cancelDownload();
                    }
                });
            }

            LogUtils.d("FileOperationsController", "Showing detailed progress dialog at start of operation");
        }

        // Additionally use Background Service for notification support
        if (serviceCallback != null) {
            serviceCallback.startOperation(operationName);
        }

        // Create a handler for the main thread to post UI updates
        final Handler mainHandler = new Handler(context.getMainLooper());

        // Execute the operation on a background thread to avoid blocking the UI
        new Thread(() -> {
            try {
                // No need to sleep here - we want to start the operation immediately
                // The progress dialog is already shown on the UI thread

                // Execute the operation on the background thread
                operation.execute();

                // Post successful completion handling back to the UI thread
                mainHandler.post(() -> {
                    // Successful completion - notify background service
                    if (serviceCallback != null) {
                        serviceCallback.finishOperation(operationName, true);
                    }

                    // Note: We don't hide the progress dialog here anymore.
                    // The dialog will be hidden by the callback's onResult method
                    // after any post-processing (like copying files) is complete.
                    // This ensures the dialog remains visible throughout the entire operation.
                });

            } catch (Exception e) {
                // Post error handling back to the UI thread
                mainHandler.post(() -> {
                    // Notify service about operation completion
                    if (serviceCallback != null) {
                        serviceCallback.finishOperation(operationName, false);
                    }

                    // Special handling for user cancellation
                    if (e.getMessage() != null && e.getMessage().contains("cancelled by user")) {
                        LogUtils.i("FileOperationsController", "Operation was cancelled by user");
                        showInfo(operationType.equals("upload") ? "Upload was cancelled" : "Download was cancelled");
                    } else {
                        showError(operationType.equals("upload") ? "Upload error" : "Download error", e.getMessage());
                    }

                    // Hide the progress dialog for errors
                    if (progressCallback != null) {
                        progressCallback.hideLoadingIndicator();
                        progressCallback.hideDetailedProgressDialog();
                    }
                    LogUtils.d("FileOperationsController", "Progress dialog hidden (error case)");
                });
            }
        }).start();
    }

    /**
     * Creates a destination folder for downloading.
     *
     * @param uri The URI of the parent folder
     * @return The created destination folder
     * @throws Exception If the folder cannot be created
     */
    private DocumentFile createDestinationFolder(Uri uri) throws Exception {
        DocumentFile documentFile = DocumentFile.fromTreeUri(context, uri);
        if (documentFile == null || !documentFile.exists() || !documentFile.isDirectory()) {
            throw new Exception("Invalid destination folder");
        }

        DocumentFile subFolder = documentFile.createDirectory(uiState.getSelectedFile().getName());
        if (subFolder == null) {
            throw new Exception("Failed to create folder");
        }
        return subFolder;
    }

    /**
     * Creates a temporary folder for downloading.
     *
     * @return The created temporary folder
     * @throws Exception If the folder cannot be created
     */
    private File createTempFolder() throws Exception {
        File tempFolder = new File(context.getCacheDir(), "download_" + System.currentTimeMillis());
        if (!tempFolder.mkdirs()) {
            throw new Exception("Failed to create temporary folder");
        }
        return tempFolder;
    }

    /**
     * Gets the file name from a URI.
     *
     * @param uri The URI
     * @return The file name, or null if it cannot be determined
     */
    private String getFileNameFromUri(Uri uri) {
        LogUtils.d("FileOperationsController", "Getting file name from URI: " + uri);
        String result = null;
        if (uri.getScheme().equals("content")) {
            LogUtils.d("FileOperationsController", "URI scheme is content, querying content resolver");
            try (android.database.Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        result = cursor.getString(nameIndex);
                        LogUtils.d("FileOperationsController", "File name from content resolver: " + result);
                    } else {
                        LogUtils.w("FileOperationsController", "DISPLAY_NAME column not found in cursor");
                    }
                } else {
                    LogUtils.w("FileOperationsController", "Cursor is null or empty");
                }
            } catch (Exception e) {
                LogUtils.e("FileOperationsController", "Error querying content resolver: " + e.getMessage());
                // Ignore
            }
        }
        if (result == null) {
            LogUtils.d("FileOperationsController", "Falling back to URI path for file name");
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
            LogUtils.d("FileOperationsController", "File name from URI path: " + result);
        }
        return result;
    }

    /**
     * Gets the document file name.
     *
     * @param docFile  The document file
     * @param fallback The fallback name
     * @return The document file name
     */
    private String getDocumentFileName(DocumentFile docFile, String fallback) {
        return docFile != null ? docFile.getName() : fallback;
    }

    /**
     * Builds the remote path for a file.
     *
     * @param fileName The file name
     * @return The remote path
     */
    private String buildRemotePath(String fileName) {
        String currentPath = fileListViewModel.getCurrentPath().getValue();
        return (currentPath == null || currentPath.equals("root")) ? fileName : currentPath + "/" + fileName;
    }

    /**
     * Creates a callback for download operations that notifies listeners.
     *
     * @param tempFile The temporary file
     * @param uri      The URI to save the file to
     * @param file     The file being downloaded
     * @return The callback
     */
    private FileOperationCallbacks.DownloadCallback createFileDownloadCallbackWithNotification(File tempFile, Uri uri, SmbFileItem file) {
        return new FileOperationCallbacks.DownloadCallback() {
            @Override
            public void onProgress(String status, int percentage) {
                // Use the unified progress update method
                updateOperationProgress(OPERATION_DOWNLOAD, file, null, percentage, status);
            }

            @Override
            public void onResult(boolean success, String message) {
                // Note: Service notification is handled by executeFileOperation, so we don't need to do it here

                if (success) {
                    try {
                        // Show finalizing progress using the unified method
                        updateFinalizingProgress(OPERATION_DOWNLOAD, file, null, false);

                        // Copy the file to the final destination
                        FileOperations.copyFileToUri(tempFile, uri, context);

                        // Handle success using the unified method
                        // We don't need to refresh the file list for downloads
                        handleOperationSuccess(OPERATION_DOWNLOAD, file, file != null ? file.getName() : null, false, "File downloaded successfully", false, () -> tempFile.delete() // Custom action to delete the temp file
                        );
                    } catch (Exception e) {
                        // Handle error using the unified method
                        handleOperationError(OPERATION_DOWNLOAD, file, "Error copying file to URI: " + e.getMessage(), null, // Use standard error title
                                false, // Don't refresh file list
                                () -> tempFile.delete() // Custom action to delete the temp file
                        );
                    }
                } else {
                    // Handle error using the unified method
                    handleOperationError(OPERATION_DOWNLOAD, file, message, null, // Use standard error title
                            false, // Don't refresh file list
                            null // No custom action
                    );
                }
            }
        };
    }

    /**
     * Creates a callback for folder download operations that notifies listeners.
     *
     * @param tempFolder The temporary folder
     * @param destFolder The destination folder
     * @param folder     The folder being downloaded
     * @return The callback
     */
    private FileOperationCallbacks.DownloadCallback createFolderDownloadCallbackWithNotification(File tempFolder, DocumentFile destFolder, SmbFileItem folder) {
        return new FileOperationCallbacks.DownloadCallback() {
            @Override
            public void onProgress(String status, int percentage) {
                // Use the unified progress update method
                updateOperationProgress(OPERATION_DOWNLOAD, folder, null, percentage, status);
            }

            @Override
            public void onResult(boolean success, String message) {
                // Note: Service notification is handled by executeFileOperation, so we don't need to do it here

                if (success) {
                    try {
                        // Show finalizing progress using the unified method
                        updateFinalizingProgress(OPERATION_DOWNLOAD, folder, null, true);

                        // Copy the folder to the final destination
                        FileOperations.copyFolderToDocumentFile(tempFolder, destFolder, context);

                        // Handle success using the unified method
                        // For folders, we want to refresh the file list
                        handleOperationSuccess(OPERATION_DOWNLOAD, folder, folder != null ? folder.getName() : null, true, "Folder downloaded successfully", true, // Refresh file list
                                () -> FileOperations.deleteRecursive(tempFolder) // Custom action to clean up temp folder
                        );
                    } catch (Exception e) {
                        // Handle error using the unified method
                        handleOperationError(OPERATION_DOWNLOAD, folder, e.getMessage(), null, // Use standard error title
                                false, // Don't refresh file list on error
                                () -> FileOperations.deleteRecursive(tempFolder) // Custom action to clean up temp folder
                        );
                    }
                } else {
                    // Handle error using the unified method
                    handleOperationError(OPERATION_DOWNLOAD, folder, message, null, // Use standard error title
                            false, // Don't refresh file list on error
                            () -> FileOperations.deleteRecursive(tempFolder) // Custom action to clean up temp folder
                    );
                }
            }
        };
    }

    /**
     * Creates a callback for upload operations that notifies listeners.
     *
     * @param file     The file being uploaded (can be null for URI-based uploads)
     * @param fileName The name of the file being uploaded (used for display)
     * @return The callback
     */
    private FileOperationCallbacks.UploadCallback createUploadCallbackWithNotification(SmbFileItem file, String fileName) {
        return new FileOperationCallbacks.UploadCallback() {
            @Override
            public void onProgress(String status, int percentage) {
                // Use the unified progress update method
                updateOperationProgress(OPERATION_UPLOAD, file, fileName, percentage, status);
            }

            @Override
            public void onResult(boolean success, String message) {
                if (success) {
                    // Show finalizing progress using the unified method
                    updateFinalizingProgress(OPERATION_UPLOAD, file, fileName, false);

                    // Handle success using the unified method
                    // For uploads, we want to refresh the file list to show the new file
                    handleOperationSuccess(OPERATION_UPLOAD, file, fileName, false, // Not a folder
                            "File uploaded successfully", true, // Refresh file list
                            null // No custom action needed
                    );
                } else {
                    // Handle error using the unified method
                    handleOperationError(OPERATION_UPLOAD, file, message, null, // Use standard error title
                            false, // Don't refresh file list on error
                            null // No custom action needed
                    );
                }
            }
        };
    }

    /**
     * Creates a callback for folder contents upload operations that notifies listeners.
     *
     * @param folderName The name of the folder being uploaded
     * @return The callback
     */
    private FileOperationCallbacks.UploadCallback createFolderContentsUploadCallbackWithNotification(String folderName) {
        return new FileOperationCallbacks.UploadCallback() {
            @Override
            public void onProgress(String status, int percentage) {
                // Use the unified progress update method
                updateOperationProgress(OPERATION_FOLDER_UPLOAD, null, folderName, percentage, status);
            }

            @Override
            public void onResult(boolean success, String message) {
                // Special handling: Enable ZIP buttons after folder upload completes (regardless of success/failure)
                if (progressCallback != null) {
                    progressCallback.setZipButtonsEnabled(true);
                }

                if (success) {
                    // Show finalizing progress using the unified method
                    updateFinalizingProgress(OPERATION_FOLDER_UPLOAD, null, folderName, true);

                    // Handle success using the unified method
                    handleOperationSuccess(OPERATION_FOLDER_UPLOAD, null, folderName, true, // Is a folder
                            "Folder contents uploaded successfully", true, // Refresh file list
                            null // No custom action needed
                    );
                } else {
                    LogUtils.e("FileOperationsController", "Folder contents upload failed: " + message);

                    // Special handling for partial failures
                    if (message.contains("incomplete") || message.contains("of")) {
                        // Partial failure - use custom error title and message
                        String enhancedMessage = message + "\n\nSome files were uploaded successfully. Check the server and retry if needed.";

                        // Handle error using the unified method, but with custom title and always refresh
                        handleOperationError(OPERATION_FOLDER_UPLOAD, null, enhancedMessage, "Upload incomplete", // Custom error title for partial failures
                                true, // Always refresh file list for partial failures to show uploaded files
                                null // No custom action needed
                        );
                    } else {
                        // Complete failure - use standard error handling
                        handleOperationError(OPERATION_FOLDER_UPLOAD, null, message, "Folder contents upload error", // Custom error title for complete failures
                                false, // Don't refresh file list for complete failures
                                null // No custom action needed
                        );
                    }
                }
            }
        };
    }

    /**
     * Creates a callback for file existence checks.
     * Uses UserFeedbackProvider if available, falls back to ProgressCallback for backward compatibility.
     *
     * @return The callback
     */
    private FileOperationCallbacks.FileExistsCallback createFileExistsCallback() {
        return (fileName, confirmAction, cancelAction) -> {
            // Check if activity is still active to prevent window leaks
            if (userFeedbackProvider != null) {
                userFeedbackProvider.showFileExistsDialog(fileName, confirmAction, cancelAction);
            } else if (progressCallback != null) {
                progressCallback.showFileExistsDialog(fileName, confirmAction, cancelAction);
            } else {
                // If no feedback provider, just cancel
                cancelAction.run();
            }
        };
    }

    /**
     * Shows a success message.
     * Uses UserFeedbackProvider if available, falls back to ProgressCallback for backward compatibility.
     *
     * @param message The message
     */
    private void showSuccess(String message) {
        if (userFeedbackProvider != null) {
            userFeedbackProvider.showSuccess(message);
        } else if (progressCallback != null) {
            progressCallback.showSuccess(message);
        }
    }

    /**
     * Shows an error message.
     * Uses UserFeedbackProvider if available, falls back to ProgressCallback for backward compatibility.
     *
     * @param title   The title
     * @param message The message
     */
    private void showError(String title, String message) {
        if (userFeedbackProvider != null) {
            userFeedbackProvider.showError(title, message);
        } else if (progressCallback != null) {
            progressCallback.showError(title, message);
        }
    }

    /**
     * Shows an info message.
     * Uses UserFeedbackProvider if available, falls back to ProgressCallback for backward compatibility.
     *
     * @param message The message
     */
    private void showInfo(String message) {
        if (userFeedbackProvider != null) {
            userFeedbackProvider.showInfo(message);
        } else if (progressCallback != null) {
            progressCallback.showInfo(message);
        }
    }

    /**
     * Shows a confirmation dialog.
     * Uses UserFeedbackProvider if available, falls back to a simple info message for backward compatibility.
     *
     * @param title     The title
     * @param message   The message
     * @param onConfirm The action to take when confirmed
     * @param onCancel  The action to take when canceled
     */
    private void showConfirmation(String title, String message, Runnable onConfirm, Runnable onCancel) {
        if (userFeedbackProvider != null) {
            userFeedbackProvider.showConfirmation(title, message, onConfirm, onCancel);
        } else {
            // Fallback for backward compatibility - just show an info message and execute onConfirm
            showInfo(title + ": " + message);
            if (onConfirm != null) {
                onConfirm.run();
            }
        }
    }

    /**
     * Handles successful completion of an operation with standardized messaging and notifications.
     * This method centralizes success handling to ensure consistent behavior across all operation types.
     * <p>
     * Part of the unified operation result handling system, this method provides a standardized way to:
     * - Show success animations
     * - Display consistent success messages
     * - Refresh the file list when needed
     * - Execute custom actions for operation-specific needs
     * - Notify listeners about successful completion
     * - Log success information
     * <p>
     * The method supports customization through several parameters:
     * - Custom success messages can be provided, or a standard message will be generated
     * - File list refreshing can be enabled or disabled based on operation needs
     * - Custom actions can be provided for operation-specific cleanup or additional tasks
     *
     * @param operationType       The type of operation (use OPERATION_* constants)
     * @param file                The file that was operated on (can be null for folder operations)
     * @param itemName            The name of the file or folder for display (used if file is null)
     * @param isFolder            Whether the operation is on a folder (affects message formatting)
     * @param customMessage       Custom success message (if null, a standard message will be generated)
     * @param refreshFileList     Whether to refresh the file list after the operation
     * @param customSuccessAction Additional custom action to perform on success (can be null)
     */
    private void handleOperationSuccess(String operationType, SmbFileItem file, String itemName, boolean isFolder, String customMessage, boolean refreshFileList, Runnable customSuccessAction) {
        // Generate standard success message if custom message not provided
        String successMessage = customMessage;
        if (successMessage == null) {
            String itemType = isFolder ? "folder" : "file";
            String operationName = "";

            if (operationType.equals(OPERATION_DOWNLOAD)) {
                operationName = "downloaded";
            } else if (operationType.equals(OPERATION_UPLOAD) || operationType.equals(OPERATION_FOLDER_UPLOAD)) {
                operationName = "uploaded";
            } else if (operationType.equals(OPERATION_DELETE)) {
                operationName = "deleted";
            } else if (operationType.equals(OPERATION_RENAME)) {
                operationName = "renamed";
            }

            successMessage = itemType.substring(0, 1).toUpperCase() + itemType.substring(1) + " " + operationName + " successfully";
        }

        // Show success animation if available
        if (progressCallback != null) {
            progressCallback.animateSuccess();
        }

        // Show success message
        showSuccess(successMessage);

        // Refresh file list if requested
        if (refreshFileList) {
            fileListViewModel.loadFiles(false); // Refresh without showing loading indicator
        }

        // Execute custom action if provided
        if (customSuccessAction != null) {
            customSuccessAction.run();
        }

        // Notify listeners about successful completion
        notifyOperationCompleted(operationType, file, true, successMessage);

        // Hide the progress dialog now that the operation is truly complete
        // This ensures the dialog remains visible throughout the entire operation,
        // including any post-processing like copying files to their final destination
        if (progressCallback != null) {
            progressCallback.hideLoadingIndicator();
            progressCallback.hideDetailedProgressDialog();
            LogUtils.d("FileOperationsController", "Progress dialog hidden after operation success");
        }

        LogUtils.d("FileOperationsController", "Operation success: " + operationType + " - " + successMessage);
    }

    /**
     * Handles failed completion of an operation with standardized error messaging and notifications.
     * This method centralizes error handling to ensure consistent behavior across all operation types.
     * <p>
     * Part of the unified operation result handling system, this method provides a standardized way to:
     * - Display consistent error messages with appropriate titles
     * - Refresh the file list when needed (e.g., for partial failures where some files were processed)
     * - Execute custom actions for operation-specific error handling
     * - Notify listeners about operation failure
     * - Log error information
     * <p>
     * The method supports customization through several parameters:
     * - Custom error titles can be provided, or a standard title will be generated based on operation type
     * - File list refreshing can be enabled for partial failures or disabled for complete failures
     * - Custom actions can be provided for operation-specific cleanup or additional error handling
     * <p>
     * Special cases like partial failures in folder operations can be handled by providing appropriate
     * custom error titles and enabling file list refreshing to show any files that were successfully processed.
     *
     * @param operationType     The type of operation (use OPERATION_* constants)
     * @param file              The file that was operated on (can be null for folder operations)
     * @param errorMessage      The error message
     * @param customErrorTitle  Custom error title (if null, a standard title will be generated)
     * @param refreshFileList   Whether to refresh the file list after the operation
     * @param customErrorAction Additional custom action to perform on error (can be null)
     */
    private void handleOperationError(String operationType, SmbFileItem file, String errorMessage, String customErrorTitle, boolean refreshFileList, Runnable customErrorAction) {
        // Generate standard error title if custom title not provided
        String errorTitle = customErrorTitle;
        if (errorTitle == null) {
            if (operationType.equals(OPERATION_DOWNLOAD)) {
                errorTitle = "Download error";
            } else if (operationType.equals(OPERATION_UPLOAD) || operationType.equals(OPERATION_FOLDER_UPLOAD)) {
                errorTitle = "Upload error";
            } else if (operationType.equals(OPERATION_DELETE)) {
                errorTitle = "Delete error";
            } else if (operationType.equals(OPERATION_RENAME)) {
                errorTitle = "Rename error";
            } else {
                errorTitle = "Operation error";
            }
        }

        // Show error message
        showError(errorTitle, errorMessage);

        // Refresh file list if requested
        if (refreshFileList) {
            fileListViewModel.loadFiles(false); // Refresh without showing loading indicator
        }

        // Execute custom action if provided
        if (customErrorAction != null) {
            customErrorAction.run();
        }

        // Notify listeners about failed completion
        notifyOperationCompleted(operationType, file, false, errorMessage);

        // Hide the progress dialog now that the operation is truly complete
        // This ensures the dialog remains visible throughout the entire operation,
        // including any post-processing like copying files to their final destination
        if (progressCallback != null) {
            progressCallback.hideLoadingIndicator();
            progressCallback.hideDetailedProgressDialog();
            LogUtils.d("FileOperationsController", "Progress dialog hidden after operation error");
        }

        LogUtils.e("FileOperationsController", "Operation error: " + operationType + " - " + errorTitle + ": " + errorMessage);
    }

    /**
     * Interface for requesting file operations.
     * This interface is implemented by controllers that need to request file operations.
     */
    public interface FileOperationRequester {
        /**
         * Requests a file download.
         *
         * @param file The file to download
         */
        void requestFileOrFolderDownload(SmbFileItem file);

        /**
         * Requests a file upload.
         */
        void requestFileUpload();

        /**
         * Requests a folder contents upload.
         */
        void requestFolderContentsUpload();

        /**
         * Requests a file deletion.
         *
         * @param file The file to delete
         */
        void requestFileDeletion(SmbFileItem file);

        /**
         * Requests a file rename.
         *
         * @param file The file to rename
         */
        void requestFileRename(SmbFileItem file);
    }

    /**
     * Interface for receiving file operation events.
     * This interface is implemented by controllers that need to be notified of file operation events.
     */
    public interface FileOperationListener {
        /**
         * Called when a file operation starts.
         *
         * @param operationType The type of operation
         * @param file          The file being operated on
         */
        void onFileOperationStarted(String operationType, SmbFileItem file);

        /**
         * Called when a file operation completes.
         *
         * @param operationType The type of operation
         * @param file          The file that was operated on
         * @param success       Whether the operation was successful
         * @param message       A message describing the result
         */
        void onFileOperationCompleted(String operationType, SmbFileItem file, boolean success, String message);

        /**
         * Called to report progress during a file operation.
         *
         * @param operationType The type of operation
         * @param file          The file being operated on
         * @param progress      The progress percentage (0-100)
         * @param message       A message describing the progress
         */
        void onFileOperationProgress(String operationType, SmbFileItem file, int progress, String message);
    }

    /**
     * Callback for progress updates.
     */
    public interface ProgressCallback {
        /**
         * Shows a loading indicator.
         *
         * @param message      The message
         * @param cancelable   Whether the operation is cancelable
         * @param cancelAction The action to take when canceled
         */
        void showLoadingIndicator(String message, boolean cancelable, Runnable cancelAction);

        /**
         * Updates the loading message.
         *
         * @param message The message
         */
        void updateLoadingMessage(String message);

        /**
         * Sets whether the cancel button is enabled.
         *
         * @param enabled Whether the button is enabled
         */
        void setCancelButtonEnabled(boolean enabled);

        /**
         * Hides the loading indicator.
         */
        void hideLoadingIndicator();

        /**
         * Shows a detailed progress dialog for file operations (upload, download, etc.).
         *
         * @param title   The title
         * @param message The message
         */
        void showDetailedProgressDialog(String title, String message);

        /**
         * Updates the detailed progress.
         *
         * @param percentage The percentage
         * @param statusText The status text
         * @param fileName   The file name
         */
        void updateDetailedProgress(int percentage, String statusText, String fileName);

        /**
         * Hides the detailed progress dialog.
         */
        void hideDetailedProgressDialog();

        /**
         * Shows progress in the UI.
         *
         * @param operationName The operation name
         * @param progressText  The progress text
         */
        void showProgressInUI(String operationName, String progressText);

        /**
         * Shows a file exists dialog.
         *
         * @param fileName      The file name
         * @param confirmAction The action to take when confirmed
         * @param cancelAction  The action to take when canceled
         */
        void showFileExistsDialog(String fileName, Runnable confirmAction, Runnable cancelAction);

        /**
         * Sets whether the ZIP buttons are enabled.
         *
         * @param enabled Whether the buttons are enabled
         */
        void setZipButtonsEnabled(boolean enabled);

        /**
         * Animates a success.
         */
        void animateSuccess();

        /**
         * Shows a success message.
         *
         * @param message The message
         */
        void showSuccess(String message);

        /**
         * Shows an error message.
         *
         * @param title   The title
         * @param message The message
         */
        void showError(String title, String message);

        /**
         * Shows an info message.
         *
         * @param message The message
         */
        void showInfo(String message);
    }

    /**
     * Callback for service operations.
     */
    public interface ServiceCallback {
        /**
         * Starts an operation.
         *
         * @param operationName The operation name
         */
        void startOperation(String operationName);

        /**
         * Finishes an operation.
         *
         * @param operationName The operation name
         * @param success       Whether the operation was successful
         */
        void finishOperation(String operationName, boolean success);

        /**
         * Updates file progress.
         *
         * @param operationName   The operation name
         * @param currentFile     The current file
         * @param totalFiles      The total files
         * @param currentFileName The current file name
         */
        void updateFileProgress(String operationName, int currentFile, int totalFiles, String currentFileName);

        /**
         * Updates bytes progress.
         *
         * @param operationName The operation name
         * @param currentBytes  The current bytes
         * @param totalBytes    The total bytes
         * @param fileName      The file name
         */
        void updateBytesProgress(String operationName, long currentBytes, long totalBytes, String fileName);

        /**
         * Updates operation progress.
         *
         * @param operationName The operation name
         * @param progressInfo  The progress info
         */
        void updateOperationProgress(String operationName, String progressInfo);
    }

    /**
     * Interface for file operations.
     */
    @FunctionalInterface
    private interface FileOperation {
        /**
         * Executes the operation.
         *
         * @throws Exception If an error occurs
         */
        void execute() throws Exception;
    }

    /**
     * Implementation of the FileOperationRequester interface.
     * This allows other controllers to request file operations from this controller.
     */
    public class FileOperationRequesterImpl implements FileOperationRequester {
        @Override
        public void requestFileOrFolderDownload(SmbFileItem file) {
            // Store the file in the UI state for later use
            uiState.setSelectedFile(file);

            // Notify listeners that a file download operation has started
            notifyOperationStarted("download", file);

            // Call the ActivityResultController to launch the file picker
            if (activityResultController != null) {
                activityResultController.initDownloadFileOrFolder(file);
            } else {
                LogUtils.e("FileOperationsController", "ActivityResultController is null, cannot launch file picker");
            }
        }

        @Override
        public void requestFileUpload() {
            // Notify listeners that a file upload operation has started
            notifyOperationStarted("upload", null);

            // The actual upload will be handled by the ActivityResultController
            // which will launch the file picker and then call handleFileUpload
        }

        @Override
        public void requestFolderContentsUpload() {
            // Notify listeners that a folder contents upload operation has started
            notifyOperationStarted("folderContentsUpload", null);

            // The actual upload will be handled by the ActivityResultController
            // which will launch the folder picker and then call handleFolderContentsUpload
        }

        @Override
        public void requestFileDeletion(SmbFileItem file) {
            // Store the file in the UI state for later use
            uiState.setSelectedFile(file);

            // Notify listeners that a file deletion operation has started
            notifyOperationStarted("delete", file);

            // Show confirmation dialog before deleting the file
            if (dialogController != null) {
                // Use the DialogController to show a confirmation dialog
                dialogController.showDeleteFileConfirmationDialog(file);
            } else {
                LogUtils.e("FileOperationsController", "DialogController is null, cannot show confirmation dialog");
            }
        }

        @Override
        public void requestFileRename(SmbFileItem file) {
            // Store the file in the UI state for later use
            uiState.setSelectedFile(file);

            // Notify listeners that a file rename operation has started
            notifyOperationStarted("rename", file);

            // The actual rename will be handled by the DialogController
            // which will show a rename dialog and then call the appropriate method
        }
    }
}