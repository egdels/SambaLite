package de.schliweb.sambalite.ui.operations;

import android.os.Handler;
import android.os.Looper;
import androidx.documentfile.provider.DocumentFile;
import androidx.lifecycle.ViewModel;
import de.schliweb.sambalite.cache.IntelligentCacheManager;
import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.ui.FileBrowserState;
import de.schliweb.sambalite.ui.FileListViewModel;
import de.schliweb.sambalite.util.EnhancedFileUtils;
import de.schliweb.sambalite.util.LogUtils;

import javax.inject.Inject;
import java.io.File;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * ViewModel for handling file operations such as download, upload, create, delete, and rename.
 * This is part of the refactored FileBrowserViewModel, focusing only on file operations.
 */
public class FileOperationsViewModel extends ViewModel {

    private final SmbRepository smbRepository;
    private final Executor executor;
    private final android.content.Context context;
    private final FileBrowserState state;
    private final FileListViewModel fileListViewModel;

    // ServiceController for background notifications
    private de.schliweb.sambalite.ui.controllers.ServiceController serviceController;

    @Inject
    public FileOperationsViewModel(SmbRepository smbRepository, android.content.Context context, FileBrowserState state, FileListViewModel fileListViewModel) {
        this.smbRepository = smbRepository;
        this.context = context;
        this.state = state;
        this.fileListViewModel = fileListViewModel;
        this.executor = Executors.newSingleThreadExecutor();
        LogUtils.d("FileOperationsViewModel", "FileOperationsViewModel initialized");
    }

    /**
     * Sets the ServiceController for background notifications.
     * This method should be called after the ViewModel is created.
     *
     * @param serviceController The ServiceController to use for background notifications
     */
    public void setServiceController(de.schliweb.sambalite.ui.controllers.ServiceController serviceController) {
        this.serviceController = serviceController;
        LogUtils.d("FileOperationsViewModel", "ServiceController set");
    }

    /**
     * Cancels any ongoing download operation.
     */
    public void cancelDownload() {
        LogUtils.d("FileOperationsViewModel", "Download cancellation requested from UI");
        state.setDownloadCancelled(true);
        // Also cancel at the repository level to stop network transfer
        smbRepository.cancelDownload();
    }

    /**
     * Cancels any ongoing upload operation.
     */
    public void cancelUpload() {
        LogUtils.d("FileOperationsViewModel", "Upload cancellation requested from UI");
        state.setUploadCancelled(true);
        // Also cancel at the repository level to stop network transfer
        smbRepository.cancelUpload();
    }

    /**
     * Downloads a file from the SMB server.
     *
     * @param file      The file to download
     * @param localFile The local file to save the downloaded file to
     * @param callback  The callback to be called when the download is complete
     */
    public void downloadFile(SmbFileItem file, File localFile, FileOperationCallbacks.DownloadCallback callback) {
        downloadFile(file, localFile, callback, null);
    }

    /**
     * Downloads a file from the SMB server with progress tracking.
     *
     * @param file             The file to download
     * @param localFile        The local file to save the downloaded file to
     * @param callback         The callback to be called when the download is complete
     * @param progressCallback The callback for progress updates during download
     */
    public void downloadFile(SmbFileItem file, File localFile, FileOperationCallbacks.DownloadCallback callback, FileOperationCallbacks.ProgressCallback progressCallback) {
        if (state.getConnection() == null || file == null || !file.isFile()) {
            LogUtils.w("FileOperationsViewModel", "Cannot download: invalid file or connection");
            if (callback != null) {
                callback.onResult(false, "Invalid file or connection");
            }
            return;
        }

        LogUtils.d("FileOperationsViewModel", "Downloading file: " + file.getName() + " to " + localFile.getAbsolutePath());
        // Don't set loading state to prevent loading circle from being shown during downloads

        // Create operation name for background notification
        String operationName = "Downloading: " + file.getName();

        // Start background notification if ServiceController is available
        if (serviceController != null) {
            LogUtils.d("FileOperationsViewModel", "Starting download operation in background service: " + operationName);

            // Get the parent directory path for the download
            String filePath = file.getPath();
            String parentPath = "/";

            // Extract parent path from the file path
            if (filePath != null && !filePath.isEmpty()) {
                int lastSlashIndex = filePath.lastIndexOf('/');
                if (lastSlashIndex > 0) {
                    parentPath = filePath.substring(0, lastSlashIndex);
                }
            }

            // Set download parameters in the service controller
            serviceController.setDownloadParameters(state.getConnection().getId(), parentPath);

            // Start the operation
            serviceController.startOperation(operationName);
            serviceController.updateOperationProgress(operationName, "Starting download...");
        }

        executor.execute(() -> {
            try {
                // Reset download cancellation flag at the start of download
                state.setDownloadCancelled(false);

                // Check for cancellation before starting
                if (state.isDownloadCancelled()) {
                    LogUtils.d("FileOperationsViewModel", "Download cancelled before starting");

                    // Finish background notification if ServiceController is available
                    if (serviceController != null) {
                        serviceController.finishOperation(operationName, false);
                    }

                    handleDownloadCancellation(localFile, "file download pre-start cancellation", callback, "Download cancelled by user");
                    return;
                }

                // Use progress-aware download if callback is provided
                if (progressCallback != null) {
                    LogUtils.d("FileOperationsViewModel", "Using progress-aware file download");
                    smbRepository.downloadFileWithProgress(state.getConnection(), file.getPath(), localFile, new BackgroundSmbManager.ProgressCallback() {
                        @Override
                        public void updateProgress(String progressInfo) {
                            progressCallback.updateProgress(progressInfo);

                            // Also send progress to DownloadCallback if available
                            if (callback != null) {
                                // Parse progress percentage from string format like "Download: 45% (...)"
                                int percentage = parseProgressPercentage(progressInfo);
                                callback.onProgress(progressInfo, percentage);
                            }

                            // Update background notification if ServiceController is available
                            if (serviceController != null) {
                                serviceController.updateOperationProgress(operationName, progressInfo);
                            }
                        }

                        @Override
                        public void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
                            progressCallback.updateBytesProgress(currentBytes, totalBytes, fileName);

                            // Also send progress to DownloadCallback if available
                            if (callback != null) {
                                int percentage = calculateAccuratePercentage(currentBytes, totalBytes);
                                String status = "Download: " + percentage + "% (" + EnhancedFileUtils.formatFileSize(currentBytes) + " / " + EnhancedFileUtils.formatFileSize(totalBytes) + ")";
                                callback.onProgress(status, percentage);
                            }

                            // Update background notification if ServiceController is available
                            if (serviceController != null) {
                                serviceController.updateBytesProgress(operationName, currentBytes, totalBytes, fileName);
                            }
                        }
                    });
                } else {
                    LogUtils.d("FileOperationsViewModel", "Using standard file download (no progress)");

                    // For downloads without progress callback, we still want to update the notification
                    // periodically to show that the download is still running
                    if (serviceController != null) {
                        serviceController.updateOperationProgress(operationName, "Downloading file...");
                    }

                    smbRepository.downloadFile(state.getConnection(), file.getPath(), localFile);
                }

                LogUtils.i("FileOperationsViewModel", "File downloaded successfully: " + file.getName());
                // Don't set loading state to prevent loading circle from being shown during downloads

                // Finish background notification if ServiceController is available
                if (serviceController != null) {
                    serviceController.finishOperation(operationName, true);
                    LogUtils.d("FileOperationsViewModel", "Finished download operation in background service: " + operationName);
                }

                if (callback != null) {
                    callback.onResult(true, "File downloaded successfully");
                }
            } catch (Exception e) {
                LogUtils.e("FileOperationsViewModel", "Download failed: " + e.getMessage());

                // Don't set loading state to prevent loading circle from being shown during downloads

                // Check if this was a user cancellation
                if (state.isDownloadCancelled() || (e.getMessage() != null && e.getMessage().contains("cancelled by user"))) {
                    LogUtils.i("FileOperationsViewModel", "Download was cancelled by user");

                    // Finish background notification if ServiceController is available
                    if (serviceController != null) {
                        serviceController.finishOperation(operationName, false);
                        LogUtils.d("FileOperationsViewModel", "Finished download operation in background service (cancelled): " + operationName);
                    }

                    handleDownloadCancellation(localFile, "file download user cancellation", callback, "Download cancelled by user");
                } else {
                    // Regular download failure - cleanup and report error
                    cleanupDownloadFiles(localFile, "file download failure");

                    // Finish background notification if ServiceController is available
                    if (serviceController != null) {
                        serviceController.finishOperation(operationName, false);
                        LogUtils.d("FileOperationsViewModel", "Finished download operation in background service (failed): " + operationName);
                    }

                    if (callback != null) {
                        callback.onResult(false, "Download failed: " + e.getMessage());
                    }
                    state.setErrorMessage("Failed to download file: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Downloads a folder from the SMB server with progress tracking.
     *
     * @param folder           The folder to download
     * @param localFolder      The local folder to save the downloaded folder to
     * @param callback         The callback to be called when the download is complete
     * @param progressCallback The callback for progress updates during download
     */
    public void downloadFolder(SmbFileItem folder, File localFolder, FileOperationCallbacks.DownloadCallback callback, FileOperationCallbacks.ProgressCallback progressCallback) {
        if (state.getConnection() == null || folder == null || !folder.isDirectory()) {
            LogUtils.w("FileOperationsViewModel", "Cannot download: invalid folder or connection");
            if (callback != null) {
                callback.onResult(false, "Invalid folder or connection");
            }
            return;
        }

        LogUtils.d("FileOperationsViewModel", "Downloading folder: " + folder.getName() + " to " + localFolder.getAbsolutePath());
        // Don't set loading state to prevent loading circle from being shown during downloads
        executor.execute(() -> {
            try {
                // Reset download cancellation flag at the start of download
                state.setDownloadCancelled(false);

                // Check for cancellation before starting
                if (state.isDownloadCancelled()) {
                    LogUtils.d("FileOperationsViewModel", "Folder download cancelled before starting");
                    handleDownloadCancellation(localFolder, "folder download pre-start cancellation", callback, "Download cancelled by user");
                    return;
                }

                // Use Background Manager for Multi-File Progress if available
                if (progressCallback != null) {
                    LogUtils.d("FileOperationsViewModel", "Using progress-aware folder download");
                    smbRepository.downloadFolderWithProgress(state.getConnection(), folder.getPath(), localFolder, new BackgroundSmbManager.MultiFileProgressCallback() {
                        // Store the last extracted percentage from progress updates
                        private int lastProgressPercentage = 0;

                        @Override
                        public void updateFileProgress(int currentFile, String currentFileName) {
                            // Parse the enhanced file name if it contains progress information
                            int percentage = lastProgressPercentage; // Default to last percentage
                            int totalFiles = -1;
                            String actualFileName = currentFileName;

                            // Check if the file name contains progress information
                            if (currentFileName != null && currentFileName.startsWith("[PROGRESS:")) {
                                try {
                                    // Extract progress information from the file name
                                    // Format: [PROGRESS:percentage:currentFile:totalFiles]currentFileName
                                    int endBracket = currentFileName.indexOf("]");
                                    if (endBracket > 0) {
                                        String progressPart = currentFileName.substring(10, endBracket); // Skip "[PROGRESS:"
                                        String[] parts = progressPart.split(":");
                                        if (parts.length >= 3) {
                                            percentage = Integer.parseInt(parts[0]);
                                            // currentFile is already provided as a parameter
                                            totalFiles = Integer.parseInt(parts[2]);
                                            actualFileName = currentFileName.substring(endBracket + 1);

                                            // Update the last progress percentage
                                            lastProgressPercentage = percentage;
                                        }
                                    }
                                } catch (Exception e) {
                                    LogUtils.w("FileOperationsViewModel", "Error parsing progress information: " + e.getMessage());
                                    // Continue with default values if parsing fails
                                }
                            }

                            // Forward the progress update with the actual file name
                            progressCallback.updateFileProgress(currentFile, totalFiles, actualFileName);

                            // Also send progress to DownloadCallback if available
                            if (callback != null) {
                                String status = "Downloading: " + percentage + "% - " + actualFileName;
                                callback.onProgress(status, percentage);
                            }
                        }

                        @Override
                        public void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
                            progressCallback.updateBytesProgress(currentBytes, totalBytes, fileName);

                            // Also send progress to DownloadCallback if available
                            if (callback != null) {
                                int percentage = calculateAccuratePercentage(currentBytes, totalBytes);
                                String status = "Download: " + percentage + "% (" + EnhancedFileUtils.formatFileSize(currentBytes) + " / " + EnhancedFileUtils.formatFileSize(totalBytes) + ")";
                                callback.onProgress(status, percentage);

                                // Update the last progress percentage
                                lastProgressPercentage = percentage;
                            }
                        }

                        @Override
                        public void updateProgress(String progressInfo) {
                            progressCallback.updateProgress(progressInfo);

                            // Also send progress to DownloadCallback if available
                            if (callback != null) {
                                int percentage = parseProgressPercentage(progressInfo);
                                callback.onProgress(progressInfo, percentage);

                                // Update the last progress percentage if we successfully extracted one
                                if (percentage > 0) {
                                    lastProgressPercentage = percentage;
                                }
                            }
                        }
                    });
                } else {
                    LogUtils.d("FileOperationsViewModel", "Using standard folder download (no progress)");
                    smbRepository.downloadFolder(state.getConnection(), folder.getPath(), localFolder);
                }

                LogUtils.i("FileOperationsViewModel", "Folder downloaded successfully: " + folder.getName());
                // Don't set loading state to prevent loading circle from being shown during downloads
                if (callback != null) {
                    callback.onResult(true, "Folder downloaded successfully");
                }
            } catch (Exception e) {
                LogUtils.e("FileOperationsViewModel", "Folder download failed: " + e.getMessage());

                // Don't set loading state to prevent loading circle from being shown during downloads

                // Check if this was a user cancellation
                if (state.isDownloadCancelled() || (e.getMessage() != null && e.getMessage().contains("cancelled by user"))) {
                    LogUtils.i("FileOperationsViewModel", "Folder download was cancelled by user");
                    handleDownloadCancellation(localFolder, "folder download user cancellation", callback, "Download cancelled by user");
                } else {
                    // Regular download failure - cleanup and report error
                    cleanupDownloadFiles(localFolder, "folder download failure");

                    if (callback != null) {
                        callback.onResult(false, "Download failed: " + e.getMessage());
                    }
                    state.setErrorMessage("Failed to download folder: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Uploads a file to the SMB server.
     *
     * @param localFile          The local file to upload
     * @param remotePath         The path on the SMB server to upload the file to
     * @param callback           The callback to be called when the upload is complete
     * @param fileExistsCallback The callback to be called if the file already exists
     * @param displayFileName    The actual filename to display in the confirmation dialog (if null, localFile.getName() will be used)
     */
    public void uploadFile(File localFile, String remotePath, FileOperationCallbacks.UploadCallback callback, FileOperationCallbacks.FileExistsCallback fileExistsCallback, String displayFileName) {
        if (state.getConnection() == null || localFile == null || !localFile.exists()) {
            LogUtils.w("FileOperationsViewModel", "Cannot upload: invalid file or connection");
            if (callback != null) {
                callback.onResult(false, "Invalid file or connection");
            }
            return;
        }

        LogUtils.d("FileOperationsViewModel", "Checking if file exists before uploading: " + remotePath);
        state.setLoading(true);
        executor.execute(() -> {
            try {
                // Check if the file already exists
                boolean fileExists = smbRepository.fileExists(state.getConnection(), remotePath);

                if (fileExists && fileExistsCallback != null) {
                    LogUtils.d("FileOperationsViewModel", "File already exists, asking for confirmation: " + remotePath);
                    state.setLoading(false);

                    // Create a runnable that will be executed if the user confirms the overwrite
                    Runnable confirmAction = () -> {
                        LogUtils.d("FileOperationsViewModel", "User confirmed overwrite, uploading file: " + localFile.getName());
                        performUpload(localFile, remotePath, callback);
                    };

                    // Create a runnable that will be executed if the user cancels
                    Runnable cancelAction = () -> {
                        LogUtils.d("FileOperationsViewModel", "User cancelled file upload for: " + localFile.getName());
                        // For regular file uploads, we notify the callback about cancellation
                        new Handler(Looper.getMainLooper()).post(() -> callback.onResult(false, "Upload cancelled by user"));
                    };

                    // Call the callback to show the confirmation dialog
                    new Handler(Looper.getMainLooper()).post(() -> {
                        // Use the display filename if provided, otherwise use the local file name
                        String nameToDisplay = displayFileName != null ? displayFileName : localFile.getName();
                        fileExistsCallback.onFileExists(nameToDisplay, confirmAction, cancelAction);
                    });
                } else {
                    // File doesn't exist or no callback provided, proceed with upload
                    LogUtils.d("FileOperationsViewModel", "File doesn't exist or no callback provided, proceeding with upload");
                    performUpload(localFile, remotePath, callback);
                }
            } catch (Exception e) {
                LogUtils.e("FileOperationsViewModel", "Error checking if file exists: " + e.getMessage());
                state.setLoading(false);
                if (callback != null) {
                    callback.onResult(false, "Error checking if file exists: " + e.getMessage());
                }
                state.setErrorMessage("Failed to check if file exists: " + e.getMessage());
            }
        });
    }

    /**
     * Performs the actual upload of a file.
     *
     * @param localFile  The local file to upload
     * @param remotePath The path on the SMB server to upload the file to
     * @param callback   The callback to be called when the upload is complete
     */
    private void performUpload(File localFile, String remotePath, FileOperationCallbacks.UploadCallback callback) {
        LogUtils.d("FileOperationsViewModel", "Uploading file: " + localFile.getName() + " to " + remotePath);
        state.setLoading(true);

        // Create operation name for background notification
        String operationName = "Uploading: " + localFile.getName();

        // Start background notification if ServiceController is available
        if (serviceController != null) {
            LogUtils.d("FileOperationsViewModel", "Starting upload operation in background service: " + operationName);

            // Extract directory path from the remote path
            String directoryPath = "/";
            if (remotePath != null && !remotePath.isEmpty()) {
                int lastSlashIndex = remotePath.lastIndexOf('/');
                if (lastSlashIndex > 0) {
                    directoryPath = remotePath.substring(0, lastSlashIndex);
                }
            }

            // Set upload parameters in the service controller
            serviceController.setUploadParameters(state.getConnection().getId(), directoryPath);

            // Start the operation
            serviceController.startOperation(operationName);
            serviceController.updateOperationProgress(operationName, "Starting upload...");
        }

        executor.execute(() -> {
            try {
                // Reset upload cancellation flag at the start of upload
                state.setUploadCancelled(false);

                // Use progress-aware upload if callback is provided
                smbRepository.uploadFileWithProgress(state.getConnection(), localFile, remotePath, new BackgroundSmbManager.ProgressCallback() {
                    @Override
                    public void updateProgress(String progressInfo) {
                        if (callback != null) {
                            // Parse progress percentage from string format like "Upload: 45% (...)"
                            int percentage = parseProgressPercentage(progressInfo);
                            new Handler(Looper.getMainLooper()).post(() -> {
                                callback.onProgress(progressInfo, percentage);
                            });
                        }

                        // Update background notification if ServiceController is available
                        if (serviceController != null) {
                            serviceController.updateOperationProgress(operationName, progressInfo);
                        }
                    }

                    @Override
                    public void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
                        if (callback != null) {
                            int percentage = calculateAccuratePercentage(currentBytes, totalBytes);
                            String status = "Upload: " + percentage + "% (" + EnhancedFileUtils.formatFileSize(currentBytes) + " / " + EnhancedFileUtils.formatFileSize(totalBytes) + ")";
                            new Handler(Looper.getMainLooper()).post(() -> {
                                callback.onProgress(status, percentage);
                            });
                        }

                        // Update background notification if ServiceController is available
                        if (serviceController != null) {
                            serviceController.updateBytesProgress(operationName, currentBytes, totalBytes, fileName);
                        }
                    }
                });

                LogUtils.i("FileOperationsViewModel", "File uploaded successfully: " + localFile.getName());
                state.setLoading(false);

                // Finish background notification if ServiceController is available
                if (serviceController != null) {
                    serviceController.finishOperation(operationName, true);
                    LogUtils.d("FileOperationsViewModel", "Finished upload operation in background service: " + operationName);
                }

                if (callback != null) {
                    callback.onResult(true, "File uploaded successfully");
                }

                // Invalidate cache for the current path since we've added a new file
                invalidateCacheAndRefreshUI();
            } catch (Exception e) {
                LogUtils.e("FileOperationsViewModel", "Upload failed: " + e.getMessage());
                state.setLoading(false);

                // Check if this was a user cancellation
                if (state.isUploadCancelled() || (e.getMessage() != null && e.getMessage().contains("cancelled by user"))) {
                    LogUtils.i("FileOperationsViewModel", "Upload was cancelled by user");

                    // Finish background notification if ServiceController is available
                    if (serviceController != null) {
                        serviceController.finishOperation(operationName, false);
                        LogUtils.d("FileOperationsViewModel", "Finished upload operation in background service (cancelled): " + operationName);
                    }

                    if (callback != null) {
                        callback.onResult(false, "Upload cancelled by user");
                    }

                    // Refresh the file list to ensure UI is up-to-date after cancellation
                    // This is necessary because the server might have partially created files
                    fileListViewModel.refreshCurrentDirectory();
                } else {
                    // Finish background notification if ServiceController is available
                    if (serviceController != null) {
                        serviceController.finishOperation(operationName, false);
                        LogUtils.d("FileOperationsViewModel", "Finished upload operation in background service (failed): " + operationName);
                    }

                    if (callback != null) {
                        callback.onResult(false, "Upload failed: " + e.getMessage());
                    }
                    state.setErrorMessage("Failed to upload file: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Creates a new folder in the current directory.
     *
     * @param folderName The name of the folder to create
     * @param callback   The callback to be called when the folder creation is complete
     */
    public void createFolder(String folderName, FileOperationCallbacks.CreateFolderCallback callback) {
        if (state.getConnection() == null || folderName == null || folderName.isEmpty()) {
            LogUtils.w("FileOperationsViewModel", "Cannot create folder: invalid folder name or connection");
            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    callback.onResult(false, "Invalid folder name or connection");
                });
            }
            return;
        }

        LogUtils.d("FileOperationsViewModel", "Creating folder: " + folderName + " in path: " + state.getCurrentPathString());
        state.setLoading(true);
        executor.execute(() -> {
            try {
                smbRepository.createDirectory(state.getConnection(), state.getCurrentPathString(), folderName);
                LogUtils.i("FileOperationsViewModel", "Folder created successfully: " + folderName);
                state.setLoading(false);
                if (callback != null) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        callback.onResult(true, "Folder created successfully");
                    });
                }
                // Invalidate caches since directory structure changed
                IntelligentCacheManager.getInstance().invalidateSearchCache(state.getConnection(), state.getCurrentPathString());
                // CRITICAL: Also invalidate the file list cache for current path so loadFiles() 
                // will fetch fresh data from server instead of showing stale cached data
                // Use synchronous invalidation to prevent race conditions
                String cachePattern = "conn_" + state.getConnection().getId() + "_path_" + state.getCurrentPathString().hashCode();
                IntelligentCacheManager.getInstance().invalidateSync(cachePattern);
                // Update file list after folder creation with force refresh to ensure latest data from server
                fileListViewModel.refreshCurrentDirectory();
            } catch (Exception e) {
                LogUtils.e("FileOperationsViewModel", "Folder creation failed: " + e.getMessage());
                state.setLoading(false);
                if (callback != null) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        callback.onResult(false, "Folder creation failed: " + e.getMessage());
                    });
                }
                state.setErrorMessage("Failed to create folder: " + e.getMessage());
            }
        });
    }

    /**
     * Deletes a file or directory.
     *
     * @param file     The file or directory to delete
     * @param callback The callback to be called when the deletion is complete
     */
    public void deleteFile(SmbFileItem file, FileOperationCallbacks.DeleteFileCallback callback) {
        if (state.getConnection() == null || file == null) {
            LogUtils.w("FileOperationsViewModel", "Cannot delete: invalid file or connection");
            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    callback.onResult(false, "Invalid file or connection");
                });
            }
            return;
        }

        LogUtils.d("FileOperationsViewModel", "Deleting file: " + file.getName() + " at path: " + file.getPath());
        state.setLoading(true);
        executor.execute(() -> {
            try {
                smbRepository.deleteFile(state.getConnection(), file.getPath());
                LogUtils.i("FileOperationsViewModel", "File deleted successfully: " + file.getName());
                state.setLoading(false);
                if (callback != null) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        callback.onResult(true, "File deleted successfully");
                    });
                }
                // Cache invalidation for file operations that change directory contents
                // Use synchronous invalidation to prevent race conditions with loadFiles()
                String cachePattern = "conn_" + state.getConnection().getId() + "_path_" + state.getCurrentPathString().hashCode();
                IntelligentCacheManager.getInstance().invalidateSync(cachePattern);

                // Also invalidate search cache for this path since file structure changed
                IntelligentCacheManager.getInstance().invalidateSearchCache(state.getConnection(), state.getCurrentPathString());

                // Refresh the file list after deletion with force refresh to ensure deleted files are not shown
                fileListViewModel.refreshCurrentDirectory();
            } catch (Exception e) {
                LogUtils.e("FileOperationsViewModel", "File deletion failed: " + e.getMessage());
                state.setLoading(false);
                if (callback != null) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        callback.onResult(false, "File deletion failed: " + e.getMessage());
                    });
                }
                state.setErrorMessage("Failed to delete file: " + e.getMessage());
            }
        });
    }

    /**
     * Renames a file or directory.
     *
     * @param file     The file or directory to rename
     * @param newName  The new name for the file or directory
     * @param callback The callback to be called when the rename is complete
     */
    public void renameFile(SmbFileItem file, String newName, FileOperationCallbacks.RenameFileCallback callback) {
        if (state.getConnection() == null || file == null || newName == null || newName.isEmpty()) {
            LogUtils.w("FileOperationsViewModel", "Cannot rename: invalid file, name, or connection");
            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    callback.onResult(false, "Invalid file, name, or connection");
                });
            }
            return;
        }

        LogUtils.d("FileOperationsViewModel", "Renaming file: " + file.getName() + " to " + newName);
        state.setLoading(true);
        executor.execute(() -> {
            try {
                smbRepository.renameFile(state.getConnection(), file.getPath(), newName);
                LogUtils.i("FileOperationsViewModel", "File renamed successfully: " + file.getName() + " to " + newName);
                state.setLoading(false);
                if (callback != null) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        callback.onResult(true, "File renamed successfully");
                    });
                }
                // Cache invalidation for file operations that change directory contents
                // Use synchronous invalidation to prevent race conditions with loadFiles()
                String cachePattern = "conn_" + state.getConnection().getId() + "_path_" + state.getCurrentPathString().hashCode();
                IntelligentCacheManager.getInstance().invalidateSync(cachePattern);

                // Also invalidate search cache for this path since file structure changed
                IntelligentCacheManager.getInstance().invalidateSearchCache(state.getConnection(), state.getCurrentPathString());

                // ADDITIONAL: If renaming a directory, also invalidate cache for the directory itself
                // since its path has changed and any cached content inside it is now invalid
                if (file.isDirectory()) {
                    LogUtils.d("FileOperationsViewModel", "Renaming directory, invalidating directory cache: " + file.getPath());
                    String dirCachePattern = "conn_" + state.getConnection().getId() + "_path_" + file.getPath().hashCode();
                    IntelligentCacheManager.getInstance().invalidateSync(dirCachePattern);
                    // Also invalidate search cache for the directory itself
                    IntelligentCacheManager.getInstance().invalidateSearchCache(state.getConnection(), file.getPath());
                }

                // Refresh the file list after rename with force refresh to ensure latest data from server
                fileListViewModel.refreshCurrentDirectory();
            } catch (Exception e) {
                LogUtils.e("FileOperationsViewModel", "File rename failed: " + e.getMessage());
                state.setLoading(false);
                if (callback != null) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        callback.onResult(false, "File rename failed: " + e.getMessage());
                    });
                }
                state.setErrorMessage("Failed to rename file: " + e.getMessage());
            }
        });
    }

    /**
     * Invalidates the cache and refreshes the UI.
     */
    private void invalidateCacheAndRefreshUI() {
        // Invalidate cache for the current path
        String cachePattern = "conn_" + state.getConnection().getId() + "_path_" + state.getCurrentPathString().hashCode();
        IntelligentCacheManager.getInstance().invalidateSync(cachePattern);

        // Also invalidate search cache for this path
        IntelligentCacheManager.getInstance().invalidateSearchCache(state.getConnection(), state.getCurrentPathString());

        // Refresh the file list with force refresh to ensure latest data from server
        fileListViewModel.refreshCurrentDirectory();
    }

    /**
     * Cleans up local temporary files.
     *
     * @param tempFile         The temporary file to clean up
     * @param operationContext The context of the operation for logging
     */
    private void cleanupLocalTempFile(File tempFile, String operationContext) {
        if (tempFile != null && tempFile.exists()) {
            try {
                boolean deleted = tempFile.delete();
                if (deleted) {
                    LogUtils.d("FileOperationsViewModel", "Cleaned up temp file for " + operationContext + ": " + tempFile.getAbsolutePath());
                } else {
                    LogUtils.w("FileOperationsViewModel", "Failed to clean up temp file for " + operationContext + ": " + tempFile.getAbsolutePath());
                    // Schedule for deletion on exit as fallback
                    tempFile.deleteOnExit();
                }
            } catch (Exception e) {
                LogUtils.w("FileOperationsViewModel", "Error cleaning up temp file for " + operationContext + ": " + e.getMessage());
                // Schedule for deletion on exit as fallback
                tempFile.deleteOnExit();
            }
        }
    }

    /**
     * Handles upload cancellation.
     *
     * @param localTempFile    The local temporary file
     * @param remoteZipName    The remote zip name
     * @param operationContext The context of the operation for logging
     * @param callback         The callback to be called when the cancellation is complete
     * @param userMessage      The message to show to the user
     */
    private void handleUploadCancellation(File localTempFile, String remoteZipName, String operationContext, FileOperationCallbacks.UploadCallback callback, String userMessage) {
        LogUtils.i("FileOperationsViewModel", "Handling upload cancellation for " + operationContext);
        state.setLoading(false);

        // Clean up any temporary files
        cleanupUploadFiles(localTempFile, remoteZipName, operationContext);

        // Notify the callback
        if (callback != null) {
            callback.onResult(false, userMessage);
        }

        // Refresh the file list to ensure UI is up-to-date after cancellation
        // This is necessary because the server might have partially created files
        fileListViewModel.refreshCurrentDirectory();
    }

    /**
     * Cleans up upload files.
     *
     * @param localTempFile    The local temporary file
     * @param remoteZipName    The remote zip name
     * @param operationContext The context of the operation for logging
     */
    private void cleanupUploadFiles(File localTempFile, String remoteZipName, String operationContext) {
        // Clean up local temp file
        cleanupLocalTempFile(localTempFile, operationContext);

        // Try to clean up remote file if it was partially uploaded
        if (remoteZipName != null && state.getConnection() != null) {
            try {
                // Check if the file exists on the server
                if (smbRepository.fileExists(state.getConnection(), remoteZipName)) {
                    // Delete the partial file
                    smbRepository.deleteFile(state.getConnection(), remoteZipName);
                    LogUtils.d("FileOperationsViewModel", "Cleaned up remote partial file for " + operationContext + ": " + remoteZipName);
                }
            } catch (Exception e) {
                LogUtils.w("FileOperationsViewModel", "Error cleaning up remote file for " + operationContext + ": " + e.getMessage());
            }
        }
    }

    /**
     * Cleans up download files.
     *
     * @param localFile        The local file
     * @param operationContext The context of the operation for logging
     */
    private void cleanupDownloadFiles(File localFile, String operationContext) {
        if (localFile != null && localFile.exists()) {
            try {
                boolean deleted = localFile.delete();
                if (deleted) {
                    LogUtils.d("FileOperationsViewModel", "Cleaned up local file for " + operationContext + ": " + localFile.getAbsolutePath());
                } else {
                    LogUtils.w("FileOperationsViewModel", "Failed to clean up local file for " + operationContext + ": " + localFile.getAbsolutePath());
                }
            } catch (Exception e) {
                LogUtils.w("FileOperationsViewModel", "Error cleaning up local file for " + operationContext + ": " + e.getMessage());
            }
        }
    }

    /**
     * Handles download cancellation.
     *
     * @param localFile        The local file
     * @param operationContext The context of the operation for logging
     * @param callback         The callback to be called when the cancellation is complete
     * @param userMessage      The message to show to the user
     */
    private void handleDownloadCancellation(File localFile, String operationContext, FileOperationCallbacks.DownloadCallback callback, String userMessage) {
        LogUtils.i("FileOperationsViewModel", "Handling download cancellation for " + operationContext);
        state.setLoading(false);

        // Clean up any temporary files
        cleanupDownloadFiles(localFile, operationContext);

        // Notify the callback
        if (callback != null) {
            callback.onResult(false, userMessage);
        }
    }

    /**
     * Parses the progress percentage from a progress information string.
     *
     * @param progressInfo The progress information string
     * @return The progress percentage
     */
    private int parseProgressPercentage(String progressInfo) {
        if (progressInfo == null || progressInfo.isEmpty()) {
            return 0;
        }

        try {
            // Try to extract percentage from format like "Download: 45% (...)"
            int percentIndex = progressInfo.indexOf('%');
            if (percentIndex > 0) {
                // Look for the last space before the percentage
                int spaceIndex = progressInfo.lastIndexOf(' ', percentIndex);
                if (spaceIndex >= 0 && spaceIndex < percentIndex) {
                    String percentStr = progressInfo.substring(spaceIndex + 1, percentIndex).trim();
                    return Integer.parseInt(percentStr);
                }
            }
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            LogUtils.w("FileOperationsViewModel", "Error parsing progress percentage: " + e.getMessage());
        }

        return 0; // Default if we can't parse
    }

    /**
     * Calculates a percentage value from bytes progress with improved accuracy.
     * This method ensures the progress reaches 100% for large files by using
     * floating-point division and handling edge cases.
     *
     * @param currentBytes The current number of bytes processed
     * @param totalBytes   The total number of bytes to process
     * @return The percentage value (0-100)
     */
    private int calculateAccuratePercentage(long currentBytes, long totalBytes) {
        if (totalBytes <= 0) {
            return 0;
        }

        if (currentBytes >= totalBytes) {
            // Ensure we show 100% when the operation is complete
            return 100;
        } else if (totalBytes - currentBytes <= 1024) { // Within 1KB of completion
            // When we're very close to completion, show 100%
            return 100;
        } else {
            // Use floating-point division for accurate percentage
            return (int) Math.round((currentBytes * 100.0) / totalBytes);
        }
    }

    /**
     * Uploads a folder's contents as individual files with robust error handling and integrity verification.
     *
     * @param localFolderUri     The URI of the local folder to upload
     * @param callback           Callback for result notification with detailed progress
     * @param fileExistsCallback Callback to handle when individual files already exist (optional)
     */
    public void uploadFolderContentsFromUri(android.net.Uri localFolderUri, FileOperationCallbacks.UploadCallback callback, FileOperationCallbacks.FileExistsCallback fileExistsCallback) {
        LogUtils.d("FileOperationsViewModel", "Starting folder contents upload from URI: " + localFolderUri);

        executor.execute(() -> {
            List<FileUploadTask> uploadTasks = new ArrayList<>();
            List<String> createdFolders = new ArrayList<>();
            int totalFiles = 0;
            int successfulUploads = 0;
            int skippedFiles = 0;
            int finalTotalFiles = 0; // Declare here so it's accessible in catch block

            try {
                // Reset upload cancellation flag
                state.setUploadCancelled(false);

                // Phase 1: Analyze folder structure and create upload plan
                new Handler(Looper.getMainLooper()).post(() -> callback.onProgress("Analyzing folder structure...", 0));

                DocumentFile sourceFolder = DocumentFile.fromTreeUri(context, localFolderUri);
                if (sourceFolder == null || !sourceFolder.isDirectory()) {
                    throw new Exception("Invalid folder selected");
                }

                // Recursively scan all files and create upload tasks
                uploadTasks = scanFolderForUpload(sourceFolder, "", uploadTasks);
                totalFiles = uploadTasks.size();
                finalTotalFiles = totalFiles; // Assign value to already declared variable
                final int finalTotalFilesForLambda = finalTotalFiles; // Create truly final copy for lambda expressions

                if (totalFiles == 0) {
                    new Handler(Looper.getMainLooper()).post(() -> callback.onResult(true, "Folder is empty - nothing to upload"));
                    return;
                }

                LogUtils.d("FileOperationsViewModel", "Found " + totalFiles + " files to upload");

                // Phase 2: Create folder structure on server
                new Handler(Looper.getMainLooper()).post(() -> callback.onProgress("Creating folder structure...", 5));

                createFolderStructure(uploadTasks, createdFolders);

                // Phase 3: Upload files with progress tracking
                for (int i = 0; i < uploadTasks.size(); i++) {
                    if (state.isUploadCancelled()) {
                        LogUtils.d("FileOperationsViewModel", "Upload cancelled by user at file " + (i + 1) + " of " + finalTotalFilesForLambda);
                        throw new Exception("Upload cancelled by user");
                    }

                    FileUploadTask task = uploadTasks.get(i);
                    final int currentFileIndex = i + 1;

                    try {
                        uploadSingleFileFromTask(task, fileExistsCallback, callback, currentFileIndex, finalTotalFilesForLambda);
                        successfulUploads++;
                        LogUtils.d("FileOperationsViewModel", "Successfully uploaded file " + currentFileIndex + ": " + task.fileName);
                    } catch (FileSkippedException e) {
                        LogUtils.d("FileOperationsViewModel", "File skipped by user: " + task.fileName);
                        skippedFiles++;
                        // Continue with next file - skipped files are not counted as successful or failed
                    } catch (Exception e) {
                        LogUtils.e("FileOperationsViewModel", "Failed to upload file " + task.fileName + ": " + e.getMessage());
                        // Continue with next file instead of stopping completely
                    }
                }

                // Phase 4: Verify upload integrity
                new Handler(Looper.getMainLooper()).post(() -> callback.onProgress("Verifying upload integrity...", 95));

                int failedFiles = totalFiles - successfulUploads - skippedFiles;

                if (successfulUploads < totalFiles) {
                    StringBuilder message = new StringBuilder();
                    message.append("Upload incomplete: ").append(successfulUploads).append(" of ").append(finalTotalFiles).append(" files uploaded successfully");

                    if (skippedFiles > 0) {
                        message.append(", ").append(skippedFiles).append(" file(s) skipped");
                    }
                    if (failedFiles > 0) {
                        message.append(", ").append(failedFiles).append(" file(s) failed");
                    }

                    LogUtils.w("FileOperationsViewModel", message.toString());

                    // Show detailed warning to user
                    new Handler(Looper.getMainLooper()).post(() -> callback.onResult(false, message + ". Check the server and retry if needed."));
                } else {
                    LogUtils.i("FileOperationsViewModel", "All " + finalTotalFiles + " files uploaded successfully");

                    // Invalidate caches since directory contents changed
                    IntelligentCacheManager.getInstance().invalidateSearchCache(state.getConnection(), state.getCurrentPathString());
                    String cachePattern = "conn_" + state.getConnection().getId() + "_path_" + state.getCurrentPathString().hashCode();
                    IntelligentCacheManager.getInstance().invalidateSync(cachePattern);

                    String successMessage = "All " + finalTotalFilesForLambda + " files uploaded successfully";
                    if (skippedFiles > 0) {
                        successMessage = successfulUploads + " files uploaded successfully, " + skippedFiles + " files skipped";
                    }

                    final String finalSuccessMessage = successMessage;
                    new Handler(Looper.getMainLooper()).post(() -> {
                        callback.onProgress("Upload completed successfully!", 100);
                        callback.onResult(true, finalSuccessMessage);
                        // Refresh the file list to show uploaded content
                        // Use refreshCurrentDirectory instead of loadFiles to force a refresh from the server
                        fileListViewModel.refreshCurrentDirectory();
                    });
                }

            } catch (Exception e) {
                LogUtils.e("FileOperationsViewModel", "Error uploading folder contents: " + e.getMessage());

                // Robust cleanup on failure
                new Handler(Looper.getMainLooper()).post(() -> callback.onProgress("Cleaning up incomplete upload...", 95));

                cleanupIncompleteUpload(createdFolders, uploadTasks, successfulUploads);

                String errorMessage = "Folder upload failed: " + e.getMessage();
                if (successfulUploads > 0) {
                    errorMessage += " (" + successfulUploads + " of " + finalTotalFiles + " files were uploaded)";
                }
                final String finalErrorMessage = errorMessage; // Create final copy for lambda

                new Handler(Looper.getMainLooper()).post(() -> {
                    callback.onResult(false, finalErrorMessage);

                    // Refresh the file list to ensure UI is up-to-date after cancellation
                    // This is necessary because the server might have partially created files or folders
                    fileListViewModel.refreshCurrentDirectory();
                });
            }
        });
    }

    private List<FileUploadTask> scanFolderForUpload(DocumentFile folder, String relativePath, List<FileUploadTask> tasks) {
        DocumentFile[] files = folder.listFiles();
        if (files == null) return tasks;

        for (DocumentFile file : files) {
            if (state.isUploadCancelled()) break;

            String fileName = file.getName();
            if (fileName == null) continue;

            String currentRelativePath = relativePath.isEmpty() ? fileName : relativePath + "/" + fileName;
            // Always use currentPath as the base directory for uploads, ensuring files are uploaded to the current directory
            String serverPath = state.getCurrentPathString() + "/" + currentRelativePath;

            if (file.isDirectory()) {
                // Recursively scan subdirectories
                scanFolderForUpload(file, currentRelativePath, tasks);
            } else if (file.isFile()) {
                // Add file to upload tasks
                tasks.add(new FileUploadTask(file, relativePath, fileName, serverPath));
            }
        }

        return tasks;
    }

    private void createFolderStructure(List<FileUploadTask> tasks, List<String> createdFolders) throws Exception {
        Set<String> relativeFoldersToCreate = new HashSet<>();

        // Collect all unique relative folder paths from serverPath (excluding the filename)
        for (FileUploadTask task : tasks) {
            // Extract the relative path part from the serverPath
            // The serverPath is constructed as currentPath + "/" + currentRelativePath in scanFolderForUpload
            String relativeFolderPath = task.relativePath;

            if (!relativeFolderPath.isEmpty()) {
                // Add all parent directories that need to be created
                String[] pathParts = relativeFolderPath.split("/");
                String pathSoFar = "";

                for (String part : pathParts) {
                    pathSoFar = pathSoFar.isEmpty() ? part : pathSoFar + "/" + part;
                    relativeFoldersToCreate.add(pathSoFar);
                }
            }
        }

        // Create folders in correct order (parent directories first)
        List<String> sortedRelativeFolders = new ArrayList<>(relativeFoldersToCreate);
        sortedRelativeFolders.sort((a, b) -> Integer.compare(a.split("/").length, b.split("/").length));

        for (String relativeFolder : sortedRelativeFolders) {
            if (state.isUploadCancelled()) break;

            // Extract the folder name and parent path
            String[] pathParts = relativeFolder.split("/");
            String folderName = pathParts[pathParts.length - 1];
            String relativeParentPath = pathParts.length > 1 ? relativeFolder.substring(0, relativeFolder.lastIndexOf("/")) : "";

            // Construct the full parent path by combining currentPath with relativeParentPath
            String fullParentPath = relativeParentPath.isEmpty() ? state.getCurrentPathString() : state.getCurrentPathString() + "/" + relativeParentPath;

            // Construct the full folder path for logging and tracking
            String fullFolderPath = state.getCurrentPathString() + "/" + relativeFolder;

            try {
                smbRepository.createDirectory(state.getConnection(), fullParentPath, folderName);
                createdFolders.add(fullFolderPath);
                LogUtils.d("FileOperationsViewModel", "Created folder: " + fullFolderPath);
            } catch (Exception e) {
                // Folder might already exist, which is fine
                LogUtils.d("FileOperationsViewModel", "Folder creation skipped (may already exist): " + fullFolderPath + " - " + e.getMessage());
            }
        }
    }

    /**
     * Uploads a single file from a FileUploadTask with optional progress tracking.
     *
     * @param task               The file upload task containing file information
     * @param fileExistsCallback Callback to handle when the file already exists
     * @param callback           Optional callback to report progress updates (can be null)
     * @param currentFileIndex   The index of the current file being uploaded (only used if callback is provided)
     * @param totalFiles         The total number of files to upload (only used if callback is provided)
     * @throws Exception if an error occurs during the upload
     */
    private void uploadSingleFileFromTask(FileUploadTask task, FileOperationCallbacks.FileExistsCallback fileExistsCallback, FileOperationCallbacks.UploadCallback callback, Integer currentFileIndex, Integer totalFiles) throws Exception {
        // Check if file already exists on server
        boolean fileExists = smbRepository.fileExists(state.getConnection(), task.serverPath);

        if (fileExists && fileExistsCallback != null) {
            // File exists and we have a callback to handle this situation
            LogUtils.d("FileOperationsViewModel", "File already exists on server: " + task.serverPath);

            // Use a CountDownLatch to wait for user decision in synchronous context
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicBoolean shouldOverwrite = new java.util.concurrent.atomic.AtomicBoolean(false);
            final java.util.concurrent.atomic.AtomicBoolean userCancelled = new java.util.concurrent.atomic.AtomicBoolean(false);

            // Create confirm and cancel actions
            Runnable confirmAction = () -> {
                LogUtils.d("FileOperationsViewModel", "User confirmed overwrite for file: " + task.fileName);
                shouldOverwrite.set(true);
                latch.countDown();
            };

            Runnable cancelAction = () -> {
                LogUtils.d("FileOperationsViewModel", "User cancelled upload for file: " + task.fileName);
                userCancelled.set(true);
                latch.countDown();
            };

            // Show confirmation dialog on main thread
            new Handler(Looper.getMainLooper()).post(() -> {
                fileExistsCallback.onFileExists(task.fileName, confirmAction, cancelAction);
            });

            // Wait for user decision (with timeout)
            boolean decisionMade = latch.await(60, java.util.concurrent.TimeUnit.SECONDS);

            if (!decisionMade) {
                throw new Exception("User decision timeout for file: " + task.fileName);
            }

            if (userCancelled.get()) {
                LogUtils.d("FileOperationsViewModel", "User chose to skip file: " + task.fileName);
                throw new FileSkippedException("User chose to skip file: " + task.fileName);
            }

            if (!shouldOverwrite.get()) {
                throw new Exception("Unexpected decision state for file: " + task.fileName);
            }

            // User confirmed overwrite, delete existing file first
            LogUtils.d("FileOperationsViewModel", "Deleting existing file before upload: " + task.serverPath);
            smbRepository.deleteFile(state.getConnection(), task.serverPath);
        }

        // Proceed with upload (either file didn't exist or user confirmed overwrite)
        // Create temporary file from DocumentFile
        java.io.File tempFile = java.io.File.createTempFile("upload", ".tmp", context.getCacheDir());

        try {
            // Copy DocumentFile content to temporary file
            try (java.io.InputStream input = context.getContentResolver().openInputStream(task.file.getUri()); java.io.FileOutputStream output = new java.io.FileOutputStream(tempFile)) {

                if (input == null) throw new Exception("Cannot read file: " + task.fileName);

                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    if (state.isUploadCancelled()) throw new Exception("Upload cancelled");
                    output.write(buffer, 0, bytesRead);
                }
            }

            // Check if we need to track progress
            if (callback != null && currentFileIndex != null && totalFiles != null) {
                // Create progress callback to forward to upload callback
                BackgroundSmbManager.ProgressCallback progressCallback = new BackgroundSmbManager.ProgressCallback() {
                    @Override
                    public void updateProgress(String progressInfo) {
                        // Parse progress info if it contains percentage
                        int filePercentage = 0;
                        if (progressInfo.contains("%")) {
                            try {
                                String percentStr = progressInfo.substring(progressInfo.indexOf("Upload: ") + 8);
                                percentStr = percentStr.substring(0, percentStr.indexOf("%"));
                                filePercentage = Integer.parseInt(percentStr);
                            } catch (Exception e) {
                                // Fallback to 0 if parsing fails
                                filePercentage = 0;
                            }
                        }

                        // Calculate overall progress: base progress (10%) + file progress contribution to the remaining 80%
                        // Each file contributes (80 / totalFiles)% to the overall progress
                        // Current progress = 10% (base) + ((currentFileIndex - 1) * (80 / totalFiles))% (completed files) 
                        //                  + (filePercentage * (80 / totalFiles) / 100)% (current file progress)
                        final int overallPercentage = 10 + (int) ((currentFileIndex - 1) * (80.0 / totalFiles)) + (int) ((filePercentage * (80.0 / totalFiles)) / 100);

                        final String progressMessage = "Uploading " + currentFileIndex + " of " + totalFiles + " files (" + filePercentage + "%)";

                        new Handler(Looper.getMainLooper()).post(() -> {
                            callback.onProgress(progressMessage, overallPercentage);
                        });
                    }
                };

                // Upload the file to server with progress tracking
                smbRepository.uploadFileWithProgress(state.getConnection(), tempFile, task.serverPath, progressCallback);
            } else {
                // Upload the file to server without progress tracking
                smbRepository.uploadFile(state.getConnection(), tempFile, task.serverPath);
            }
        } finally {
            // Always cleanup temp file using centralized method
            cleanupLocalTempFile(tempFile, "individual file upload completion");
        }
    }

    private void cleanupIncompleteUpload(List<String> createdFolders, List<FileUploadTask> tasks, int successfulUploads) {
        try {
            LogUtils.d("FileOperationsViewModel", "Starting cleanup of incomplete upload");

            // Delete partially uploaded files (best effort)
            for (int i = 0; i < successfulUploads && i < tasks.size(); i++) {
                try {
                    smbRepository.deleteFile(state.getConnection(), tasks.get(i).serverPath);
                    LogUtils.d("FileOperationsViewModel", "Cleaned up uploaded file: " + tasks.get(i).fileName);
                } catch (Exception e) {
                    LogUtils.w("FileOperationsViewModel", "Could not clean up file " + tasks.get(i).fileName + ": " + e.getMessage());
                }
            }

            // Delete created folders (in reverse order)
            Collections.reverse(createdFolders);
            for (String folderPath : createdFolders) {
                try {
                    smbRepository.deleteFile(state.getConnection(), folderPath);
                    LogUtils.d("FileOperationsViewModel", "Cleaned up created folder: " + folderPath);
                } catch (Exception e) {
                    LogUtils.w("FileOperationsViewModel", "Could not clean up folder " + folderPath + ": " + e.getMessage());
                }
            }

        } catch (Exception e) {
            LogUtils.e("FileOperationsViewModel", "Error during cleanup: " + e.getMessage());
        }
    }

    private record FileUploadTask(DocumentFile file, String relativePath, String fileName, String serverPath) {
    }

    /**
     * Exception thrown when a user chooses to skip a file during upload.
     */
    private static class FileSkippedException extends Exception {
        public FileSkippedException(String message) {
            super(message);
        }
    }
}