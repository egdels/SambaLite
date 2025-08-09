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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ViewModel für Dateioperationen (Download, Upload, Create, Delete, Rename).
 * Keine direkte Service-/Notification-Steuerung mehr.
 */
public class FileOperationsViewModel extends ViewModel {

    private final SmbRepository smbRepository;
    private final ExecutorService executor;
    private final android.content.Context context; // sollte Application-Context sein
    private final FileBrowserState state;
    private final FileListViewModel fileListViewModel;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Inject
    public FileOperationsViewModel(SmbRepository smbRepository,
                                   android.content.Context context,
                                   FileBrowserState state,
                                   FileListViewModel fileListViewModel) {
        this.smbRepository = smbRepository;
        this.context = context;
        this.state = state;
        this.fileListViewModel = fileListViewModel;
        this.executor = Executors.newSingleThreadExecutor();
        LogUtils.d("FileOperationsViewModel", "FileOperationsViewModel initialized");
    }

    /** Download abbrechen. */
    public void cancelDownload() {
        LogUtils.d("FileOperationsViewModel", "Download cancellation requested from UI");
        state.setDownloadCancelled(true);
        smbRepository.cancelDownload();
    }

    /** Upload abbrechen. */
    public void cancelUpload() {
        LogUtils.d("FileOperationsViewModel", "Upload cancellation requested from UI");
        state.setUploadCancelled(true);
        smbRepository.cancelUpload();
    }

    /** Datei herunterladen (ohne Progress). */
    public void downloadFile(SmbFileItem file, File localFile, FileOperationCallbacks.DownloadCallback callback) {
        downloadFile(file, localFile, callback, null);
    }

    /** Datei herunterladen (mit optionalem Progress). */
    public void downloadFile(SmbFileItem file,
                             File localFile,
                             FileOperationCallbacks.DownloadCallback callback,
                             FileOperationCallbacks.ProgressCallback progressCallback) {
        if (state.getConnection() == null || file == null || !file.isFile()) {
            LogUtils.w("FileOperationsViewModel", "Cannot download: invalid file or connection");
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(false, "Invalid file or connection"));
            }
            return;
        }

        LogUtils.d("FileOperationsViewModel", "Downloading file: " + file.getName() + " to " + localFile.getAbsolutePath());

        executor.execute(() -> {
            try {
                state.setDownloadCancelled(false);

                if (state.isDownloadCancelled()) {
                    LogUtils.d("FileOperationsViewModel", "Download cancelled before starting");
                    handleDownloadCancellation(localFile, "file download pre-start cancellation", callback, "Download cancelled by user");
                    return;
                }

                if (progressCallback != null) {
                    LogUtils.d("FileOperationsViewModel", "Using progress-aware file download");
                    smbRepository.downloadFileWithProgress(
                            state.getConnection(),
                            file.getPath(),
                            localFile,
                            new BackgroundSmbManager.ProgressCallback() {
                                @Override
                                public void updateProgress(String progressInfo) {
                                    // an internen Progress-Callback weiterleiten (kann non-UI sein)
                                    progressCallback.updateProgress(progressInfo);
                                    // UI-Callback stets über Main-Thread
                                    if (callback != null) {
                                        int percentage = parseProgressPercentage(progressInfo);
                                        mainHandler.post(() -> callback.onProgress(progressInfo, percentage));
                                    }
                                }

                                @Override
                                public void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
                                    progressCallback.updateBytesProgress(currentBytes, totalBytes, fileName);
                                    if (callback != null) {
                                        int percentage = calculateAccuratePercentage(currentBytes, totalBytes);
                                        String status = "Download: " + percentage + "% (" +
                                                EnhancedFileUtils.formatFileSize(currentBytes) + " / " +
                                                EnhancedFileUtils.formatFileSize(totalBytes) + ")";
                                        mainHandler.post(() -> callback.onProgress(status, percentage));
                                    }
                                }
                            }
                    );
                } else {
                    LogUtils.d("FileOperationsViewModel", "Using standard file download (no progress)");
                    smbRepository.downloadFile(state.getConnection(), file.getPath(), localFile);
                }

                LogUtils.i("FileOperationsViewModel", "File downloaded successfully: " + file.getName());
                if (callback != null) {
                    mainHandler.post(() -> callback.onResult(true, "File downloaded successfully"));
                }
            } catch (Exception e) {
                LogUtils.e("FileOperationsViewModel", "Download failed: " + e.getMessage());

                if (state.isDownloadCancelled() || (e.getMessage() != null && e.getMessage().contains("cancelled by user"))) {
                    LogUtils.i("FileOperationsViewModel", "Download was cancelled by user");
                    handleDownloadCancellation(localFile, "file download user cancellation", callback, "Download cancelled by user");
                } else {
                    cleanupDownloadFiles(localFile, "file download failure");
                    if (callback != null) {
                        String msg = "Download failed: " + e.getMessage();
                        mainHandler.post(() -> callback.onResult(false, msg));
                    }
                    state.setErrorMessage("Failed to download file: " + e.getMessage());
                }
            }
        });
    }

    /** Ordner herunterladen (mit Progress). */
    public void downloadFolder(SmbFileItem folder,
                               File localFolder,
                               FileOperationCallbacks.DownloadCallback callback,
                               FileOperationCallbacks.ProgressCallback progressCallback) {
        if (state.getConnection() == null || folder == null || !folder.isDirectory()) {
            LogUtils.w("FileOperationsViewModel", "Cannot download: invalid folder or connection");
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(false, "Invalid folder or connection"));
            }
            return;
        }

        LogUtils.d("FileOperationsViewModel", "Downloading folder: " + folder.getName() + " to " + localFolder.getAbsolutePath());

        executor.execute(() -> {
            try {
                state.setDownloadCancelled(false);

                if (state.isDownloadCancelled()) {
                    LogUtils.d("FileOperationsViewModel", "Folder download cancelled before starting");
                    handleDownloadCancellation(localFolder, "folder download pre-start cancellation", callback, "Download cancelled by user");
                    return;
                }

                if (progressCallback != null) {
                    LogUtils.d("FileOperationsViewModel", "Using progress-aware folder download");
                    smbRepository.downloadFolderWithProgress(
                            state.getConnection(),
                            folder.getPath(),
                            localFolder,
                            new BackgroundSmbManager.MultiFileProgressCallback() {
                                private int lastProgressPercentage = 0;

                                @Override
                                public void updateFileProgress(int currentFile, String currentFileName) {
                                    int percentage = lastProgressPercentage;
                                    int totalFiles = -1;
                                    String actualFileName = currentFileName;

                                    if (currentFileName != null && currentFileName.startsWith("[PROGRESS:")) {
                                        try {
                                            int endBracket = currentFileName.indexOf("]");
                                            if (endBracket > 0) {
                                                String progressPart = currentFileName.substring(10, endBracket);
                                                String[] parts = progressPart.split(":");
                                                if (parts.length >= 3) {
                                                    percentage = Integer.parseInt(parts[0]);
                                                    totalFiles = Integer.parseInt(parts[2]);
                                                    actualFileName = currentFileName.substring(endBracket + 1);
                                                    lastProgressPercentage = percentage;
                                                }
                                            }
                                        } catch (Exception e) {
                                            LogUtils.w("FileOperationsViewModel", "Error parsing progress information: " + e.getMessage());
                                        }
                                    }

                                    progressCallback.updateFileProgress(currentFile, totalFiles, actualFileName);

                                    if (callback != null) {
                                        String status = "Downloading: " + percentage + "% - " + actualFileName;
                                        int p = percentage;
                                        mainHandler.post(() -> callback.onProgress(status, p));
                                    }
                                }

                                @Override
                                public void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
                                    progressCallback.updateBytesProgress(currentBytes, totalBytes, fileName);
                                    if (callback != null) {
                                        int percentage = calculateAccuratePercentage(currentBytes, totalBytes);
                                        String status = "Download: " + percentage + "% (" +
                                                EnhancedFileUtils.formatFileSize(currentBytes) + " / " +
                                                EnhancedFileUtils.formatFileSize(totalBytes) + ")";
                                        int p = percentage;
                                        mainHandler.post(() -> callback.onProgress(status, p));
                                        lastProgressPercentage = percentage;
                                    }
                                }

                                @Override
                                public void updateProgress(String progressInfo) {
                                    progressCallback.updateProgress(progressInfo);
                                    if (callback != null) {
                                        int percentage = parseProgressPercentage(progressInfo);
                                        int p = percentage;
                                        mainHandler.post(() -> callback.onProgress(progressInfo, p));
                                        if (percentage > 0) lastProgressPercentage = percentage;
                                    }
                                }
                            }
                    );
                } else {
                    LogUtils.d("FileOperationsViewModel", "Using standard folder download (no progress)");
                    smbRepository.downloadFolder(state.getConnection(), folder.getPath(), localFolder);
                }

                LogUtils.i("FileOperationsViewModel", "Folder downloaded successfully: " + folder.getName());
                if (callback != null) {
                    mainHandler.post(() -> callback.onResult(true, "Folder downloaded successfully"));
                }
            } catch (Exception e) {
                LogUtils.e("FileOperationsViewModel", "Folder download failed: " + e.getMessage());

                if (state.isDownloadCancelled() || (e.getMessage() != null && e.getMessage().contains("cancelled by user"))) {
                    LogUtils.i("FileOperationsViewModel", "Folder download was cancelled by user");
                    handleDownloadCancellation(localFolder, "folder download user cancellation", callback, "Download cancelled by user");
                } else {
                    cleanupDownloadFiles(localFolder, "folder download failure");
                    if (callback != null) {
                        String msg = "Download failed: " + e.getMessage();
                        mainHandler.post(() -> callback.onResult(false, msg));
                    }
                    state.setErrorMessage("Failed to download folder: " + e.getMessage());
                }
            }
        });
    }

    /** Datei hochladen (existiert?-Check + ggf. Dialog über Callback). */
    public void uploadFile(File localFile,
                           String remotePath,
                           FileOperationCallbacks.UploadCallback callback,
                           FileOperationCallbacks.FileExistsCallback fileExistsCallback,
                           String displayFileName) {
        if (state.getConnection() == null || localFile == null || !localFile.exists()) {
            LogUtils.w("FileOperationsViewModel", "Cannot upload: invalid file or connection");
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(false, "Invalid file or connection"));
            }
            return;
        }

        LogUtils.d("FileOperationsViewModel", "Checking if file exists before uploading: " + remotePath);
        state.setLoading(true);

        executor.execute(() -> {
            try {
                boolean fileExists = smbRepository.fileExists(state.getConnection(), remotePath);

                if (fileExists && fileExistsCallback != null) {
                    LogUtils.d("FileOperationsViewModel", "File already exists, asking for confirmation: " + remotePath);
                    state.setLoading(false);

                    Runnable confirmAction = () -> {
                        LogUtils.d("FileOperationsViewModel", "User confirmed overwrite, uploading file: " + localFile.getName());
                        performUpload(localFile, remotePath, callback);
                    };

                    Runnable cancelAction = () -> {
                        LogUtils.d("FileOperationsViewModel", "User cancelled file upload for: " + localFile.getName());
                        if (callback != null) {
                            mainHandler.post(() -> callback.onResult(false, "Upload cancelled by user"));
                        }
                    };

                    mainHandler.post(() -> {
                        String nameToDisplay = displayFileName != null ? displayFileName : localFile.getName();
                        fileExistsCallback.onFileExists(nameToDisplay, confirmAction, cancelAction);
                    });
                } else {
                    LogUtils.d("FileOperationsViewModel", "File doesn't exist or no callback provided, proceeding with upload");
                    performUpload(localFile, remotePath, callback);
                }
            } catch (Exception e) {
                LogUtils.e("FileOperationsViewModel", "Error checking if file exists: " + e.getMessage());
                state.setLoading(false);
                if (callback != null) {
                    String msg = "Error checking if file exists: " + e.getMessage();
                    mainHandler.post(() -> callback.onResult(false, msg));
                }
                state.setErrorMessage("Failed to check if file exists: " + e.getMessage());
            }
        });
    }

    /** Interner Upload mit Progress. */
    private void performUpload(File localFile,
                               String remotePath,
                               FileOperationCallbacks.UploadCallback callback) {
        LogUtils.d("FileOperationsViewModel", "Uploading file: " + localFile.getName() + " to " + remotePath);
        state.setLoading(true);

        executor.execute(() -> {
            try {
                state.setUploadCancelled(false);

                smbRepository.uploadFileWithProgress(
                        state.getConnection(),
                        localFile,
                        remotePath,
                        new BackgroundSmbManager.ProgressCallback() {
                            @Override
                            public void updateProgress(String progressInfo) {
                                if (callback != null) {
                                    int percentage = parseProgressPercentage(progressInfo);
                                    mainHandler.post(() -> callback.onProgress(progressInfo, percentage));
                                }
                            }

                            @Override
                            public void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
                                if (callback != null) {
                                    int percentage = calculateAccuratePercentage(currentBytes, totalBytes);
                                    String status = "Upload: " + percentage + "% (" +
                                            EnhancedFileUtils.formatFileSize(currentBytes) + " / " +
                                            EnhancedFileUtils.formatFileSize(totalBytes) + ")";
                                    mainHandler.post(() -> callback.onProgress(status, percentage));
                                }
                            }
                        }
                );

                LogUtils.i("FileOperationsViewModel", "File uploaded successfully: " + localFile.getName());
                state.setLoading(false);
                if (callback != null) {
                    mainHandler.post(() -> callback.onResult(true, "File uploaded successfully"));
                }

                invalidateCacheAndRefreshUI();
            } catch (Exception e) {
                LogUtils.e("FileOperationsViewModel", "Upload failed: " + e.getMessage());
                state.setLoading(false);

                if (state.isUploadCancelled() || (e.getMessage() != null && e.getMessage().contains("cancelled by user"))) {
                    LogUtils.i("FileOperationsViewModel", "Upload was cancelled by user");
                    if (callback != null) {
                        mainHandler.post(() -> callback.onResult(false, "Upload cancelled by user"));
                    }
                    fileListViewModel.refreshCurrentDirectory();
                } else {
                    if (callback != null) {
                        String msg = "Upload failed: " + e.getMessage();
                        mainHandler.post(() -> callback.onResult(false, msg));
                    }
                    state.setErrorMessage("Failed to upload file: " + e.getMessage());
                }
            }
        });
    }

    /** Ordner anlegen. */
    public void createFolder(String folderName, FileOperationCallbacks.CreateFolderCallback callback) {
        if (state.getConnection() == null || folderName == null || folderName.isEmpty()) {
            LogUtils.w("FileOperationsViewModel", "Cannot create folder: invalid folder name or connection");
            if (callback != null) mainHandler.post(() -> callback.onResult(false, "Invalid folder name or connection"));
            return;
        }

        LogUtils.d("FileOperationsViewModel", "Creating folder: " + folderName + " in path: " + state.getCurrentPathString());
        state.setLoading(true);

        executor.execute(() -> {
            try {
                smbRepository.createDirectory(state.getConnection(), state.getCurrentPathString(), folderName);
                LogUtils.i("FileOperationsViewModel", "Folder created successfully: " + folderName);
                state.setLoading(false);
                if (callback != null) mainHandler.post(() -> callback.onResult(true, "Folder created successfully"));

                IntelligentCacheManager.getInstance().invalidateSearchCache(state.getConnection(), state.getCurrentPathString());
                String cachePattern = "conn_" + state.getConnection().getId() + "_path_" + state.getCurrentPathString().hashCode();
                IntelligentCacheManager.getInstance().invalidateSync(cachePattern);
                fileListViewModel.refreshCurrentDirectory();
            } catch (Exception e) {
                LogUtils.e("FileOperationsViewModel", "Folder creation failed: " + e.getMessage());
                state.setLoading(false);
                if (callback != null) mainHandler.post(() -> callback.onResult(false, "Folder creation failed: " + e.getMessage()));
                state.setErrorMessage("Failed to create folder: " + e.getMessage());
            }
        });
    }

    /** Löschen. */
    public void deleteFile(SmbFileItem file, FileOperationCallbacks.DeleteFileCallback callback) {
        if (state.getConnection() == null || file == null) {
            LogUtils.w("FileOperationsViewModel", "Cannot delete: invalid file or connection");
            if (callback != null) mainHandler.post(() -> callback.onResult(false, "Invalid file or connection"));
            return;
        }

        LogUtils.d("FileOperationsViewModel", "Deleting file: " + file.getName() + " at path: " + file.getPath());
        state.setLoading(true);

        executor.execute(() -> {
            try {
                smbRepository.deleteFile(state.getConnection(), file.getPath());
                LogUtils.i("FileOperationsViewModel", "File deleted successfully: " + file.getName());
                state.setLoading(false);
                if (callback != null) mainHandler.post(() -> callback.onResult(true, "File deleted successfully"));

                String cachePattern = "conn_" + state.getConnection().getId() + "_path_" + state.getCurrentPathString().hashCode();
                IntelligentCacheManager.getInstance().invalidateSync(cachePattern);
                IntelligentCacheManager.getInstance().invalidateSearchCache(state.getConnection(), state.getCurrentPathString());
                fileListViewModel.refreshCurrentDirectory();
            } catch (Exception e) {
                LogUtils.e("FileOperationsViewModel", "File deletion failed: " + e.getMessage());
                state.setLoading(false);
                if (callback != null) mainHandler.post(() -> callback.onResult(false, "File deletion failed: " + e.getMessage()));
                state.setErrorMessage("Failed to delete file: " + e.getMessage());
            }
        });
    }

    /** Umbenennen. */
    public void renameFile(SmbFileItem file, String newName, FileOperationCallbacks.RenameFileCallback callback) {
        if (state.getConnection() == null || file == null || newName == null || newName.isEmpty()) {
            LogUtils.w("FileOperationsViewModel", "Cannot rename: invalid file, name, or connection");
            if (callback != null) mainHandler.post(() -> callback.onResult(false, "Invalid file, name, or connection"));
            return;
        }

        LogUtils.d("FileOperationsViewModel", "Renaming file: " + file.getName() + " to " + newName);
        state.setLoading(true);

        executor.execute(() -> {
            try {
                smbRepository.renameFile(state.getConnection(), file.getPath(), newName);
                LogUtils.i("FileOperationsViewModel", "File renamed successfully: " + file.getName() + " to " + newName);
                state.setLoading(false);
                if (callback != null) mainHandler.post(() -> callback.onResult(true, "File renamed successfully"));

                String cachePattern = "conn_" + state.getConnection().getId() + "_path_" + state.getCurrentPathString().hashCode();
                IntelligentCacheManager.getInstance().invalidateSync(cachePattern);
                IntelligentCacheManager.getInstance().invalidateSearchCache(state.getConnection(), state.getCurrentPathString());

                if (file.isDirectory()) {
                    LogUtils.d("FileOperationsViewModel", "Renaming directory, invalidating directory cache: " + file.getPath());
                    String dirCachePattern = "conn_" + state.getConnection().getId() + "_path_" + file.getPath().hashCode();
                    IntelligentCacheManager.getInstance().invalidateSync(dirCachePattern);
                    IntelligentCacheManager.getInstance().invalidateSearchCache(state.getConnection(), file.getPath());
                }

                fileListViewModel.refreshCurrentDirectory();
            } catch (Exception e) {
                LogUtils.e("FileOperationsViewModel", "File rename failed: " + e.getMessage());
                state.setLoading(false);
                if (callback != null) mainHandler.post(() -> callback.onResult(false, "File rename failed: " + e.getMessage()));
                state.setErrorMessage("Failed to rename file: " + e.getMessage());
            }
        });
    }

    /** Folder-Contents-Upload (ohne Service-Nutzung). */
    public void uploadFolderContentsFromUri(android.net.Uri localFolderUri,
                                            FileOperationCallbacks.UploadCallback callback,
                                            FileOperationCallbacks.FileExistsCallback fileExistsCallback) {
        LogUtils.d("FileOperationsViewModel", "Starting folder contents upload from URI: " + localFolderUri);

        executor.execute(() -> {
            List<FileUploadTask> uploadTasks = new ArrayList<>();
            List<String> createdFolders = new ArrayList<>();
            int totalFiles = 0;
            int successfulUploads = 0;
            int skippedFiles = 0;
            int finalTotalFiles = 0;

            try {
                state.setUploadCancelled(false);

                mainHandler.post(() -> callback.onProgress("Analyzing folder structure...", 0));

                DocumentFile sourceFolder = DocumentFile.fromTreeUri(context, localFolderUri);
                if (sourceFolder == null || !sourceFolder.isDirectory()) throw new Exception("Invalid folder selected");

                uploadTasks = scanFolderForUpload(sourceFolder, "", uploadTasks);
                totalFiles = uploadTasks.size();
                finalTotalFiles = totalFiles;
                final int finalTotalFilesForLambda = finalTotalFiles;

                if (totalFiles == 0) {
                    mainHandler.post(() -> callback.onResult(true, "Folder is empty - nothing to upload"));
                    return;
                }

                LogUtils.d("FileOperationsViewModel", "Found " + totalFiles + " files to upload");
                mainHandler.post(() -> callback.onProgress("Creating folder structure...", 5));

                createFolderStructure(uploadTasks, createdFolders);

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
                    } catch (Exception e) {
                        LogUtils.e("FileOperationsViewModel", "Failed to upload file " + task.fileName + ": " + e.getMessage());
                    }
                }

                mainHandler.post(() -> callback.onProgress("Verifying upload integrity...", 95));

                int failedFiles = totalFiles - successfulUploads - skippedFiles;

                if (successfulUploads < totalFiles) {
                    StringBuilder message = new StringBuilder();
                    message.append("Upload incomplete: ").append(successfulUploads)
                            .append(" of ").append(finalTotalFiles).append(" files uploaded successfully");
                    if (skippedFiles > 0) message.append(", ").append(skippedFiles).append(" file(s) skipped");
                    if (failedFiles > 0) message.append(", ").append(failedFiles).append(" file(s) failed");

                    LogUtils.w("FileOperationsViewModel", message.toString());
                    String text = message + ". Check the server and retry if needed.";
                    mainHandler.post(() -> callback.onResult(false, text));
                } else {
                    LogUtils.i("FileOperationsViewModel", "All " + finalTotalFiles + " files uploaded successfully");

                    IntelligentCacheManager.getInstance().invalidateSearchCache(state.getConnection(), state.getCurrentPathString());
                    String cachePattern = "conn_" + state.getConnection().getId() + "_path_" + state.getCurrentPathString().hashCode();
                    IntelligentCacheManager.getInstance().invalidateSync(cachePattern);

                    String successMessage = "All " + finalTotalFilesForLambda + " files uploaded successfully";
                    if (skippedFiles > 0) successMessage = successfulUploads + " files uploaded successfully, " + skippedFiles + " files skipped";
                    final String finalSuccessMessage = successMessage;

                    mainHandler.post(() -> {
                        callback.onProgress("Upload completed successfully!", 100);
                        callback.onResult(true, finalSuccessMessage);
                        fileListViewModel.refreshCurrentDirectory();
                    });
                }

            } catch (Exception e) {
                LogUtils.e("FileOperationsViewModel", "Error uploading folder contents: " + e.getMessage());
                mainHandler.post(() -> callback.onProgress("Cleaning up incomplete upload...", 95));

                cleanupIncompleteUpload(createdFolders, uploadTasks, successfulUploads);

                String errorMessage = "Folder upload failed: " + e.getMessage();
                if (successfulUploads > 0) errorMessage += " (" + successfulUploads + " of " + finalTotalFiles + " files were uploaded)";
                final String finalErrorMessage = errorMessage;

                mainHandler.post(() -> {
                    callback.onResult(false, finalErrorMessage);
                    fileListViewModel.refreshCurrentDirectory();
                });
            }
        });
    }

    /* ===== Utility & Cleanup ===== */

    private void invalidateCacheAndRefreshUI() {
        String cachePattern = "conn_" + state.getConnection().getId() + "_path_" + state.getCurrentPathString().hashCode();
        IntelligentCacheManager.getInstance().invalidateSync(cachePattern);
        IntelligentCacheManager.getInstance().invalidateSearchCache(state.getConnection(), state.getCurrentPathString());
        fileListViewModel.refreshCurrentDirectory();
    }

    private void cleanupLocalTempFile(File tempFile, String operationContext) {
        if (tempFile != null && tempFile.exists()) {
            try {
                boolean deleted = tempFile.delete();
                if (deleted) {
                    LogUtils.d("FileOperationsViewModel", "Cleaned up temp file for " + operationContext + ": " + tempFile.getAbsolutePath());
                } else {
                    LogUtils.w("FileOperationsViewModel", "Failed to clean up temp file for " + operationContext + ": " + tempFile.getAbsolutePath());
                    tempFile.deleteOnExit();
                }
            } catch (Exception e) {
                LogUtils.w("FileOperationsViewModel", "Error cleaning up temp file for " + operationContext + ": " + e.getMessage());
                tempFile.deleteOnExit();
            }
        }
    }

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

    private void handleDownloadCancellation(File localFile,
                                            String operationContext,
                                            FileOperationCallbacks.DownloadCallback callback,
                                            String userMessage) {
        LogUtils.i("FileOperationsViewModel", "Handling download cancellation for " + operationContext);
        state.setLoading(false);
        cleanupDownloadFiles(localFile, operationContext);
        if (callback != null) {
            mainHandler.post(() -> callback.onResult(false, userMessage));
        }
    }

    private int parseProgressPercentage(String progressInfo) {
        if (progressInfo == null || progressInfo.isEmpty()) return 0;
        try {
            int percentIndex = progressInfo.indexOf('%');
            if (percentIndex > 0) {
                int spaceIndex = progressInfo.lastIndexOf(' ', percentIndex);
                if (spaceIndex >= 0 && spaceIndex < percentIndex) {
                    String percentStr = progressInfo.substring(spaceIndex + 1, percentIndex).trim();
                    return Integer.parseInt(percentStr);
                }
            }
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            LogUtils.w("FileOperationsViewModel", "Error parsing progress percentage: " + e.getMessage());
        }
        return 0;
    }

    private int calculateAccuratePercentage(long currentBytes, long totalBytes) {
        if (totalBytes <= 0) return 0;
        if (currentBytes >= totalBytes) return 100;
        if (totalBytes - currentBytes <= 1024) return 100;
        return (int) Math.round((currentBytes * 100.0) / totalBytes);
    }

    private List<FileUploadTask> scanFolderForUpload(DocumentFile folder, String relativePath, List<FileUploadTask> tasks) {
        DocumentFile[] files = folder.listFiles();
        if (files == null) return tasks;

        for (DocumentFile file : files) {
            if (state.isUploadCancelled()) break;

            String fileName = file.getName();
            if (fileName == null) continue;

            String currentRelativePath = relativePath.isEmpty() ? fileName : relativePath + "/" + fileName;
            String serverPath = state.getCurrentPathString() + "/" + currentRelativePath;

            if (file.isDirectory()) {
                scanFolderForUpload(file, currentRelativePath, tasks);
            } else if (file.isFile()) {
                tasks.add(new FileUploadTask(file, relativePath, fileName, serverPath));
            }
        }
        return tasks;
    }

    private void createFolderStructure(List<FileUploadTask> tasks, List<String> createdFolders) throws Exception {
        Set<String> relativeFoldersToCreate = new HashSet<>();

        for (FileUploadTask task : tasks) {
            String relativeFolderPath = task.relativePath;
            if (!relativeFolderPath.isEmpty()) {
                String[] pathParts = relativeFolderPath.split("/");
                String pathSoFar = "";
                for (String part : pathParts) {
                    pathSoFar = pathSoFar.isEmpty() ? part : pathSoFar + "/" + part;
                    relativeFoldersToCreate.add(pathSoFar);
                }
            }
        }

        List<String> sortedRelativeFolders = new ArrayList<>(relativeFoldersToCreate);
        sortedRelativeFolders.sort((a, b) -> Integer.compare(a.split("/").length, b.split("/").length));

        for (String relativeFolder : sortedRelativeFolders) {
            if (state.isUploadCancelled()) break;

            String[] pathParts = relativeFolder.split("/");
            String folderName = pathParts[pathParts.length - 1];
            String relativeParentPath = pathParts.length > 1 ? relativeFolder.substring(0, relativeFolder.lastIndexOf("/")) : "";

            String fullParentPath = relativeParentPath.isEmpty()
                    ? state.getCurrentPathString()
                    : state.getCurrentPathString() + "/" + relativeParentPath;

            String fullFolderPath = state.getCurrentPathString() + "/" + relativeFolder;

            try {
                smbRepository.createDirectory(state.getConnection(), fullParentPath, folderName);
                createdFolders.add(fullFolderPath);
                LogUtils.d("FileOperationsViewModel", "Created folder: " + fullFolderPath);
            } catch (Exception e) {
                LogUtils.d("FileOperationsViewModel", "Folder creation skipped (may already exist): " + fullFolderPath + " - " + e.getMessage());
            }
        }
    }

    private void uploadSingleFileFromTask(FileUploadTask task,
                                          FileOperationCallbacks.FileExistsCallback fileExistsCallback,
                                          FileOperationCallbacks.UploadCallback callback,
                                          Integer currentFileIndex,
                                          Integer totalFiles) throws Exception {
        boolean fileExists = smbRepository.fileExists(state.getConnection(), task.serverPath);

        if (fileExists && fileExistsCallback != null) {
            LogUtils.d("FileOperationsViewModel", "File already exists on server: " + task.serverPath);

            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicBoolean shouldOverwrite = new java.util.concurrent.atomic.AtomicBoolean(false);
            final java.util.concurrent.atomic.AtomicBoolean userCancelled = new java.util.concurrent.atomic.AtomicBoolean(false);

            Runnable confirmAction = () -> { shouldOverwrite.set(true); latch.countDown(); };
            Runnable cancelAction  = () -> { userCancelled.set(true);  latch.countDown(); };

            mainHandler.post(() -> fileExistsCallback.onFileExists(task.fileName, confirmAction, cancelAction));

            boolean decisionMade = latch.await(60, java.util.concurrent.TimeUnit.SECONDS);
            if (!decisionMade) throw new Exception("User decision timeout for file: " + task.fileName);
            if (userCancelled.get()) throw new FileSkippedException("User chose to skip file: " + task.fileName);
            if (!shouldOverwrite.get()) throw new Exception("Unexpected decision state for file: " + task.fileName);

            smbRepository.deleteFile(state.getConnection(), task.serverPath);
        }

        File tempFile = File.createTempFile("upload", ".tmp", context.getCacheDir());
        try {
            try (java.io.InputStream input = context.getContentResolver().openInputStream(task.file.getUri());
                 java.io.FileOutputStream output = new java.io.FileOutputStream(tempFile)) {

                if (input == null) throw new Exception("Cannot read file: " + task.fileName);

                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    if (state.isUploadCancelled()) throw new Exception("Upload cancelled");
                    output.write(buffer, 0, bytesRead);
                }
            }

            if (callback != null && currentFileIndex != null && totalFiles != null) {
                BackgroundSmbManager.ProgressCallback progressCallback = new BackgroundSmbManager.ProgressCallback() {
                    @Override
                    public void updateProgress(String progressInfo) {
                        int filePercentage = 0;
                        if (progressInfo.contains("%")) {
                            try {
                                String percentStr = progressInfo.substring(progressInfo.indexOf("Upload: ") + 8);
                                percentStr = percentStr.substring(0, percentStr.indexOf("%"));
                                filePercentage = Integer.parseInt(percentStr);
                            } catch (Exception ignored) { }
                        }
                        final int overallPercentage = 10
                                + (int) ((currentFileIndex - 1) * (80.0 / totalFiles))
                                + (int) ((filePercentage * (80.0 / totalFiles)) / 100);

                        final String progressMessage = "Uploading " + currentFileIndex + " of " + totalFiles + " files (" + filePercentage + "%)";
                        mainHandler.post(() -> callback.onProgress(progressMessage, overallPercentage));
                    }
                };
                smbRepository.uploadFileWithProgress(state.getConnection(), tempFile, task.serverPath, progressCallback);
            } else {
                smbRepository.uploadFile(state.getConnection(), tempFile, task.serverPath);
            }
        } finally {
            cleanupLocalTempFile(tempFile, "individual file upload completion");
        }
    }

    private void cleanupIncompleteUpload(List<String> createdFolders, List<FileUploadTask> tasks, int successfulUploads) {
        try {
            LogUtils.d("FileOperationsViewModel", "Starting cleanup of incomplete upload");

            for (int i = 0; i < successfulUploads && i < tasks.size(); i++) {
                try {
                    smbRepository.deleteFile(state.getConnection(), tasks.get(i).serverPath);
                    LogUtils.d("FileOperationsViewModel", "Cleaned up uploaded file: " + tasks.get(i).fileName);
                } catch (Exception e) {
                    LogUtils.w("FileOperationsViewModel", "Could not clean up file " + tasks.get(i).fileName + ": " + e.getMessage());
                }
            }

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

    private record FileUploadTask(DocumentFile file, String relativePath, String fileName, String serverPath) {}

    private static class FileSkippedException extends Exception {
        public FileSkippedException(String message) { super(message); }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        executor.shutdownNow();
    }
}
