package de.schliweb.sambalite.ui.controllers;

import android.content.Context;
import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;
import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.ui.FileListViewModel;
import de.schliweb.sambalite.ui.operations.FileOperationCallbacks;
import de.schliweb.sambalite.ui.operations.FileOperations;
import de.schliweb.sambalite.ui.operations.FileOperationsViewModel;
import de.schliweb.sambalite.ui.utils.ProgressFormat;
import de.schliweb.sambalite.util.LogUtils;
import lombok.Setter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static de.schliweb.sambalite.ui.utils.ProgressFormat.normalizePercentInStatus;

/**
 * The FileOperationsController class is responsible for managing and executing various file operations
 * such as download, upload, delete, and rename. It integrates with different components of the application
 * like ViewModels, listeners, and user feedback mechanisms to maintain operability and user interactivity.
 */
public class FileOperationsController {

    private static final int FINALIZE_WINDOW_PCT = 10;  // for SAF copy operation
    private static final int SMB_CAP = 100 - FINALIZE_WINDOW_PCT;

    // Operation type constants
    private static final String OPERATION_DOWNLOAD = "download";
    private static final String OPERATION_UPLOAD = "upload";
    private static final String OPERATION_FOLDER_UPLOAD = "folderContentsUpload";
    private static final String OPERATION_DELETE = "delete";
    private static final String OPERATION_RENAME = "rename";

    private final Context context;
    private final FileOperationsViewModel operationsViewModel;
    private final FileListViewModel fileListViewModel;
    private final FileBrowserUIState uiState;

    private final BackgroundSmbManager backgroundSmbManager;

    private final List<FileOperationListener> listeners = new ArrayList<>();

    @Setter
    private UserFeedbackProvider userFeedbackProvider; // preferred UX surface

    @Setter
    private ProgressCallback progressCallback; // legacy feedback path
    @Setter
    private ActivityResultController activityResultController;
    @Setter
    private DialogController dialogController;

    public FileOperationsController(Context context,
                                    FileOperationsViewModel operationsViewModel,
                                    FileListViewModel fileListViewModel,
                                    FileBrowserUIState uiState, BackgroundSmbManager backgroundSmbManager) {
        this.context = context;
        this.operationsViewModel = operationsViewModel;
        this.fileListViewModel = fileListViewModel;
        this.uiState = uiState;
        this.backgroundSmbManager = backgroundSmbManager;
        this.dialogController = null;
    }

    // Listener wiring
    public void addListener(FileOperationListener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(FileOperationListener listener) {
        listeners.remove(listener);
    }

    private void notifyOperationStarted(String operationType, SmbFileItem file) {
        for (FileOperationListener l : listeners) l.onFileOperationStarted(operationType, file);
    }

    private void notifyOperationCompleted(String operationType, SmbFileItem file, boolean success, String message) {
        for (FileOperationListener l : listeners) l.onFileOperationCompleted(operationType, file, success, message);
    }

    private void notifyOperationProgress(String operationType, SmbFileItem file, int progress, String message) {
        for (FileOperationListener l : listeners) l.onFileOperationProgress(operationType, file, progress, message);
    }

    // ---- Unified progress helpers ----
    private void updateOperationProgress(String operationType, SmbFileItem file, String itemName, int percentage, String status) {
        notifyOperationProgress(operationType, file, percentage, status);
        LogUtils.d("FileOperationsController", "Progress: " + operationType + " " + percentage + "% - " + status);
    }

    private void updateFinalizingProgress(String operationType, SmbFileItem file, String itemName, boolean isFolder) {
        String op = (operationType.equals(OPERATION_UPLOAD) || operationType.equals(OPERATION_FOLDER_UPLOAD))
                ? context.getString(de.schliweb.sambalite.R.string.uploading)
                : context.getString(de.schliweb.sambalite.R.string.downloading);
        String itemType = isFolder
                ? context.getString(de.schliweb.sambalite.R.string.folders_tab)
                : context.getString(de.schliweb.sambalite.R.string.files_tab);
        String msg = context.getString(de.schliweb.sambalite.R.string.finalizing_operation, itemType, op);
        updateOperationProgress(operationType, file, itemName, 99, msg);
    }

    private String titleFor(String operationType, String displayName) {
        String safeName = (displayName == null) ? "" : displayName;
        if (OPERATION_DOWNLOAD.equals(operationType))
            return context.getString(de.schliweb.sambalite.R.string.downloading_colon, safeName);
        if (OPERATION_UPLOAD.equals(operationType) || OPERATION_FOLDER_UPLOAD.equals(operationType))
            return context.getString(de.schliweb.sambalite.R.string.uploading_colon, safeName);
        if (OPERATION_DELETE.equals(operationType))
            return context.getString(de.schliweb.sambalite.R.string.deleting_colon, safeName);
        if (OPERATION_RENAME.equals(operationType))
            return context.getString(de.schliweb.sambalite.R.string.renaming_colon, safeName);
        return safeName;
    }

    // ---- Public requester ----
    public FileOperationRequester getFileOperationRequester() {
        return new FileOperationRequesterImpl();
    }

    /**
     * Öffnet KEINEN Transfer-Dialog mehr (macht die Activity).
     * Hängt – falls vorhanden – nur die Cancel-Action an einen bereits offenen Dialog.
     */
    private void showProgressShell(String operationType, String operationName, Runnable onCancel) {
        if (progressCallback instanceof ProgressController pc) {
            try {
                // Attach cancel action immediately; ProgressController will cache it if dialog not yet visible
                pc.setDetailedProgressDialogCancelAction(onCancel);
            } catch (Throwable ignore) { /* optional */ }
        }
    }

    // ---- Batch summary helper ----
    private String summarizeBatch(String base, boolean cancelled, int failedCount) {
        String summary = base != null ? base : "";
        if (cancelled) {
            summary += "; " + context.getString(de.schliweb.sambalite.R.string.cancelled_suffix);
        }
        if (failedCount > 0) {
            summary += "; " + context.getString(de.schliweb.sambalite.R.string.failed_count_suffix, failedCount);
        }
        return summary;
    }

    // ---- File operations ----
    public void handleFileDownload(Uri uri) {
        if (uiState.getSelectedFile() == null) return;
        final String displayName = uiState.getSelectedFile().getName();
        final String opTitle = titleFor(OPERATION_DOWNLOAD, displayName);

        final AtomicBoolean cancelFinalize = new AtomicBoolean(false);

        showProgressShell(OPERATION_DOWNLOAD, opTitle, () -> {
            cancelFinalize.set(true);
            operationsViewModel.cancelDownload();
        });

        backgroundSmbManager.executeMultiFileOperation(
                "fileDownload:" + System.currentTimeMillis(),
                opTitle,
                cb -> {
                    try {
                        cb.updateFileProgress(1, 1, displayName);

                        File tempFile;
                        try {
                            tempFile = File.createTempFile("download", ".tmp", context.getCacheDir());
                            uiState.setTempFile(tempFile);
                        } catch (Exception e) {
                            // Early failure creating temp file: report and bail out gracefully
                            if (progressCallback != null) {
                                progressCallback.showError(
                                        context.getString(de.schliweb.sambalite.R.string.download_error),
                                        context.getString(de.schliweb.sambalite.R.string.failed_to_download_file_with_reason, e.getMessage())
                                );
                            }
                            return Boolean.TRUE;
                        }

                        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);

                        FileOperationCallbacks.ProgressCallback fileProgress = new FileOperationCallbacks.ProgressCallback() {
                            @Override
                            public void updateFileProgress(int currentFile, int totalFiles, String currentFileName) { /* single file */ }

                            @Override
                            public void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
                                int pct = ProgressFormat.percentOfBytes(currentBytes, totalBytes);
                                String status = ProgressFormat.formatBytes(context.getString(de.schliweb.sambalite.R.string.downloading), currentBytes, totalBytes);
                                updateOperationProgress(OPERATION_DOWNLOAD, uiState.getSelectedFile(), null, pct, status);
                                cb.updateBytesProgress(currentBytes, totalBytes, fileName);
                            }

                            @Override
                            public void updateProgress(String progressInfo) {
                                int p = ProgressFormat.parsePercent(progressInfo);
                                updateOperationProgress(OPERATION_DOWNLOAD, uiState.getSelectedFile(), null, p, progressInfo);
                                cb.updateProgress(progressInfo);
                            }
                        };

                        FileOperationCallbacks.DownloadCallback inner = createFileDownloadCallbackWithNotification(
                                tempFile, uri, uiState.getSelectedFile(), cancelFinalize, cb
                        );

                        FileOperationCallbacks.DownloadCallback wrapped = new FileOperationCallbacks.DownloadCallback() {
                            @Override
                            public void onProgress(String status, int percentage) {
                                inner.onProgress(status, percentage);
                            }

                            @Override
                            public void onResult(boolean success, String message) {
                                try {
                                    inner.onResult(success, message);
                                } finally {
                                    done.countDown();
                                }
                            }
                        };

                        operationsViewModel.downloadFile(
                                uiState.getSelectedFile(),
                                tempFile,
                                wrapped,
                                fileProgress
                        );

                        done.await();
                    } finally {
                        uiState.setTempFile(null);
                        try {
                            if (progressCallback != null) progressCallback.hideDetailedProgressDialog();
                        } catch (Throwable ignore) {
                        }
                    }
                    return Boolean.TRUE;
                }
        );
    }


    public void handleFolderDownload(Uri uri) {
        if (uiState.getSelectedFile() == null || !uiState.getSelectedFile().isDirectory()) return;
        final String folderName = uiState.getSelectedFile().getName();
        final String opTitle = titleFor(OPERATION_DOWNLOAD, folderName);

        final AtomicBoolean cancelFinalize = new AtomicBoolean(false);

        showProgressShell(OPERATION_DOWNLOAD, opTitle, () -> {
            cancelFinalize.set(true);
            operationsViewModel.cancelDownload();
        });

        final DocumentFile destFolder;
        final File tempFolder;
        try {
            destFolder = createDestinationFolder(uri);
            tempFolder = createTempFolder();
            uiState.setTempFile(tempFolder);
        } catch (Exception e) {
            showError(
                    context.getString(de.schliweb.sambalite.R.string.download_error),
                    e.getMessage() != null ? e.getMessage() : context.getString(de.schliweb.sambalite.R.string.download_error_invalid_destination)
            );
            try {
                if (progressCallback != null) progressCallback.hideDetailedProgressDialog();
            } catch (Throwable ignore) {
            }
            return;
        }

        backgroundSmbManager.executeMultiFileOperation(
                "folderDownload:" + System.currentTimeMillis(),
                opTitle,
                cb -> {
                    final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);

                    FileOperationCallbacks.ProgressCallback folderProgress = new FileOperationCallbacks.ProgressCallback() {
                        @Override
                        public void updateFileProgress(int currentFile, int totalFiles, String currentFileName) {
                            int pct = totalFiles > 0 ? (currentFile * 100) / totalFiles : 0;
                            String status = ProgressFormat.formatIdx(context.getString(de.schliweb.sambalite.R.string.downloading), currentFile, totalFiles, currentFileName);
                            updateOperationProgress(OPERATION_DOWNLOAD, uiState.getSelectedFile(), currentFileName, pct, status);
                            cb.updateFileProgress(currentFile, totalFiles, currentFileName); // <-- 3 Parameter
                        }

                        @Override
                        public void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
                            int pct = ProgressFormat.percentOfBytes(currentBytes, totalBytes);
                            String status = ProgressFormat.formatBytes(context.getString(de.schliweb.sambalite.R.string.downloading), currentBytes, totalBytes);
                            updateOperationProgress(OPERATION_DOWNLOAD, uiState.getSelectedFile(), fileName, pct, status);
                            cb.updateBytesProgress(currentBytes, totalBytes, fileName);
                        }

                        @Override
                        public void updateProgress(String progressInfo) {
                            updateOperationProgress(OPERATION_DOWNLOAD, uiState.getSelectedFile(), null,
                                    ProgressFormat.parsePercent(progressInfo), progressInfo);
                            cb.updateProgress(progressInfo);
                        }
                    };


                    FileOperationCallbacks.DownloadCallback inner = createFolderDownloadCallbackWithNotification(
                            tempFolder, destFolder, uiState.getSelectedFile(), cancelFinalize, cb
                    );
                    FileOperationCallbacks.DownloadCallback wrapped = new FileOperationCallbacks.DownloadCallback() {
                        @Override
                        public void onProgress(String status, int percentage) {
                            inner.onProgress(status, percentage);
                        }

                        @Override
                        public void onResult(boolean success, String message) {
                            try {
                                inner.onResult(success, message);
                            } finally {
                                done.countDown();
                            }
                        }
                    };

                    operationsViewModel.downloadFolder(
                            uiState.getSelectedFile(),
                            tempFolder,
                            wrapped,
                            folderProgress
                    );

                    done.await();
                    // Clear temp reference after operation completes; deletion handled in callbacks
                    try {
                        uiState.setTempFile(null);
                    } catch (Throwable ignore) {
                    }
                    // As a safety net, ensure the progress dialog is hidden after folder download completes
                    try {
                        if (progressCallback != null) progressCallback.hideDetailedProgressDialog();
                    } catch (Throwable ignore) {
                    }
                    return Boolean.TRUE;
                }
        );
    }

    public void handleFileUpload(Uri uri) {
        // Best-effort ensure the app holds read grant for the URI before background staging
        trySelfGrantRead(uri);
        final String fileNameFromUri = getFileNameFromUri(uri);
        final String fileName = fileNameFromUri != null ? fileNameFromUri : "uploaded_file_" + System.currentTimeMillis();
        final String opTitle = titleFor(OPERATION_UPLOAD, fileName);

        showProgressShell(OPERATION_UPLOAD, opTitle, () -> operationsViewModel.cancelUpload());

        backgroundSmbManager.executeMultiFileOperation(
                "fileUpload:" + System.currentTimeMillis(),
                opTitle,
                cb -> {
                    cb.updateFileProgress(1, 1, fileName);

                    File tempFile = null;
                    try {
                        tempFile = File.createTempFile("upload", ".tmp", context.getCacheDir());
                        uiState.setTempFile(tempFile);
                        cb.updateProgress(context.getString(de.schliweb.sambalite.R.string.preparing_upload_staging_file));
                        FileOperations.copyUriToFile(uri, tempFile, context);
                    } catch (Exception ex) {
                        if (tempFile != null) tempFile.delete();
                        uiState.setTempFile(null);

                        cb.updateProgress(context.getString(de.schliweb.sambalite.R.string.staging_failed_with_reason, ex.getMessage()));
                        throw ex;
                    }

                    String remotePath = buildRemotePath(fileName);

                    final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);


                    FileOperationCallbacks.UploadCallback inner = createUploadCallbackWithNotification(null, fileName);
                    File finalTempFile = tempFile;
                    FileOperationCallbacks.UploadCallback wrapped = new FileOperationCallbacks.UploadCallback() {
                        @Override
                        public void onProgress(String status, int percentage) {
                            inner.onProgress(status, percentage);
                            cb.updateProgress(status);
                        }

                        @Override
                        public void onResult(boolean success, String message) {
                            try {
                                inner.onResult(success, message);
                            } finally {
                                if (finalTempFile != null) finalTempFile.delete();
                                uiState.setTempFile(null);
                                done.countDown();
                            }
                        }
                    };

                    operationsViewModel.uploadFile(
                            tempFile,
                            remotePath,
                            wrapped,
                            createFileExistsCallback(),
                            fileName
                    );

                    done.await();
                    return Boolean.TRUE;
                }
        );
    }

    public void handleFolderContentsUpload(Uri folderUri) {
        DocumentFile docFolder = DocumentFile.fromTreeUri(context, folderUri);
        String folderName = getDocumentFileName(docFolder, "folder");
        final String opTitle = titleFor(OPERATION_UPLOAD, folderName);
        LogUtils.d("FileOperationsController", "Starting folder contents upload: " + folderName);

        if (progressCallback != null) progressCallback.setZipButtonsEnabled(false);
        showProgressShell(OPERATION_UPLOAD, opTitle, () -> operationsViewModel.cancelUpload());

        backgroundSmbManager.executeMultiFileOperation(
                "folderContentsUpload:" + System.currentTimeMillis(),
                opTitle,
                cb -> {
                    final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);

                    FileOperationCallbacks.UploadCallback inner =
                            createFolderContentsUploadCallbackWithNotification(folderName);

                    FileOperationCallbacks.UploadCallback wrapped = new FileOperationCallbacks.UploadCallback() {
                        @Override
                        public void onProgress(String status, int percentage) {
                            inner.onProgress(status, percentage);
                        }

                        @Override
                        public void onResult(boolean success, String message) {
                            try {
                                inner.onResult(success, message);
                            } finally {
                                done.countDown();
                            }
                        }
                    };

                    operationsViewModel.uploadFolderContentsFromUri(
                            folderUri,
                            wrapped,
                            createFileExistsCallback(),
                            cb
                    );

                    done.await();
                    return Boolean.TRUE;
                }
        );
    }

    /**
     * Batch upload multiple URIs within one service operation. Files are processed sequentially.
     */
    public void handleMultipleFileUploads(java.util.List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        final int total = uris.size();
        final String opTitle = titleFor(OPERATION_UPLOAD, "multiple files");

        showProgressShell(OPERATION_UPLOAD, opTitle, () -> operationsViewModel.cancelUpload());

        backgroundSmbManager.executeMultiFileOperation(
                "batchUpload:" + System.currentTimeMillis(),
                opTitle,
                cb -> {
                    // Keep consolidated upload state active across the entire batch
                    operationsViewModel.beginBatchUpload();
                    try {
                        int index = 0;
                        for (Uri uri : uris) {
                            // Best-effort ensure the app holds read grant for the URI before background staging
                            trySelfGrantRead(uri);
                            final String fileNameFromUri = getFileNameFromUri(uri);
                            final String fileName = fileNameFromUri != null ? fileNameFromUri : "uploaded_file_" + System.currentTimeMillis();
                            final int current = ++index;
                            cb.updateFileProgress(current, total, fileName);

                            File tempFile = null;
                            try {
                                tempFile = File.createTempFile("upload", ".tmp", context.getCacheDir());
                                uiState.setTempFile(tempFile);
                                cb.updateProgress("Preparing upload… staging file");
                                FileOperations.copyUriToFile(uri, tempFile, context);
                            } catch (Exception ex) {
                                if (tempFile != null) tempFile.delete();
                                uiState.setTempFile(null);
                                cb.updateProgress("Staging failed for " + fileName + ": " + ex.getMessage());
                                LogUtils.e("FileOperationsController", "Staging failed for " + fileName + ": " + ex.getMessage());
                                continue; // skip to next file
                            }

                            String remotePath = buildRemotePath(fileName);
                            final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);

                            FileOperationCallbacks.UploadCallback inner = createUploadCallbackWithNotification(null, fileName);
                            File finalTempFile = tempFile;
                            FileOperationCallbacks.UploadCallback wrapped = new FileOperationCallbacks.UploadCallback() {
                                @Override
                                public void onProgress(String status, int percentage) {
                                    inner.onProgress(status, percentage);
                                    cb.updateProgress(status);
                                }

                                @Override
                                public void onResult(boolean success, String message) {
                                    try {
                                        inner.onResult(success, message);
                                    } finally {
                                        if (finalTempFile != null) finalTempFile.delete();
                                        uiState.setTempFile(null);
                                        done.countDown();
                                    }
                                }
                            };

                            operationsViewModel.uploadFile(
                                    tempFile,
                                    remotePath,
                                    wrapped,
                                    createFileExistsCallback(),
                                    fileName
                            );

                            try {
                                done.await();
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                cb.updateProgress(context.getString(de.schliweb.sambalite.R.string.upload_interrupted_with_name, fileName));
                                break;
                            }
                        }
                    } finally {
                        operationsViewModel.endBatchUpload();
                    }
                    return Boolean.TRUE;
                }
        );
    }

    // --- Multi-select: Batch Delete implementation ---
    public void handleMultipleFileDelete(java.util.List<SmbFileItem> files) {
        if (files == null || files.isEmpty()) {
            if (progressCallback != null)
                progressCallback.showInfo(context.getString(de.schliweb.sambalite.R.string.multi_no_files_selected));
            return;
        }
        // Filter only regular files (ignore directories for v1)
        java.util.ArrayList<SmbFileItem> toProcess = new java.util.ArrayList<>();
        for (SmbFileItem f : files) {
            if (f != null && f.isFile() && f.getPath() != null) toProcess.add(f);
        }
        if (toProcess.isEmpty()) {
            if (progressCallback != null)
                progressCallback.showInfo(context.getString(de.schliweb.sambalite.R.string.multi_only_files_delete));
            return;
        }
        final int total = toProcess.size();
        final String title = context.getString(de.schliweb.sambalite.R.string.delete);
        final String confirmMsg = context.getString(de.schliweb.sambalite.R.string.confirm_delete_multiple, total);

        de.schliweb.sambalite.ui.dialogs.DialogHelper.showConfirmationDialog(
                context,
                context.getString(de.schliweb.sambalite.R.string.delete_multiple_title),
                confirmMsg,
                (dialog, which) -> {
                    final String opTitle = title + " (" + total + ")";
                    final java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean(false);

                    // Notify listeners that the operation is starting (to allow immediate UI reset of selection)
                    try {
                        notifyOperationStarted(OPERATION_DELETE, null);
                    } catch (Throwable ignore) {
                    }

                    // Show a cancelable progress dialog specifically for batch delete
                    if (progressCallback != null) {
                        progressCallback.showDetailedProgressDialog(opTitle, context.getString(de.schliweb.sambalite.R.string.preparing_ellipsis));
                        if (progressCallback instanceof ProgressController pc) {
                            pc.setDetailedProgressDialogCancelAction(() -> cancel.set(true));
                        }
                    }

                    backgroundSmbManager.executeMultiFileOperation(
                            "batchDelete:" + System.currentTimeMillis(),
                            opTitle,
                            cb -> {
                                int current = 0;
                                int successCount = 0;
                                java.util.ArrayList<String> failed = new java.util.ArrayList<>();

                                for (SmbFileItem f : toProcess) {
                                    if (cancel.get()) {
                                        failed.add("(cancelled)");
                                        break;
                                    }
                                    current++;
                                    String fileName = f.getName();
                                    try {
                                        cb.updateFileProgress(current, total, fileName);
                                        if (progressCallback != null) {
                                            int pct = (int) Math.round((current - 1) * 100.0 / total);
                                            progressCallback.updateDetailedProgress(pct, context.getString(de.schliweb.sambalite.R.string.deleting_ellipsis), fileName);
                                        }
                                        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
                                        final boolean[] ok = new boolean[1];
                                        operationsViewModel.deleteFile(f, (s, msg) -> {
                                            ok[0] = s;
                                            done.countDown();
                                        });
                                        try {
                                            done.await();
                                        } catch (InterruptedException ie) {
                                            Thread.currentThread().interrupt();
                                            failed.add(fileName + " " + context.getString(de.schliweb.sambalite.R.string.interrupted_suffix));
                                            break;
                                        }
                                        if (ok[0]) successCount++;
                                        else failed.add(fileName);
                                    } catch (Throwable t) {
                                        failed.add(fileName + ": " + t.getMessage());
                                    }
                                }

                                String base = context.getString(de.schliweb.sambalite.R.string.multi_delete_summary_success, successCount, total);
                                String summary = summarizeBatch(base, cancel.get(), failed.size());
                                if (progressCallback != null) {
                                    progressCallback.showInfo(summary);
                                    progressCallback.hideDetailedProgressDialog();
                                }
                                // Ensure fresh list state
                                fileListViewModel.refreshCurrentDirectory();
                                // Notify listeners that the batch delete operation has completed (for selection reset)
                                try {
                                    boolean opSuccess = failed.isEmpty() && !cancel.get();
                                    notifyOperationCompleted(OPERATION_DELETE, null, opSuccess, summary);
                                } catch (Throwable ignore) {
                                }
                                return Boolean.TRUE;
                            }
                    );
                }
        );
    }

    public void handleMultipleFileDownloads(java.util.List<SmbFileItem> files) {
        if (files == null || files.isEmpty()) {
            if (progressCallback != null)
                progressCallback.showInfo(context.getString(de.schliweb.sambalite.R.string.multi_no_files_selected));
            return;
        }
        // Filter to files only (ignore directories v1)
        java.util.ArrayList<SmbFileItem> toProcess = new java.util.ArrayList<>();
        for (SmbFileItem f : files) {
            if (f != null && f.isFile() && f.getPath() != null) toProcess.add(f);
        }
        if (toProcess.isEmpty()) {
            if (progressCallback != null)
                progressCallback.showInfo(context.getString(de.schliweb.sambalite.R.string.multi_only_files_download));
            return;
        }
        // Store pending list in shared UI state and request a target folder
        uiState.setPendingMultiDownloadItems(toProcess);
        uiState.setMultiDownloadPending(true);
        if (activityResultController != null) {
            activityResultController.selectFolderForDownloadTarget();
        } else {
            LogUtils.e("FileOperationsController", "ActivityResultController is null; cannot choose destination folder");
            if (progressCallback != null) progressCallback.showError(
                    context.getString(de.schliweb.sambalite.R.string.download_error),
                    context.getString(de.schliweb.sambalite.R.string.download_error_cannot_open_folder_picker)
            );
        }
    }

    /**
     * Continues a previously initiated multi-file download after the user picked a destination folder.
     */
    public void handleMultipleFileDownloadsWithTargetUri(Uri folderUri) {
        java.util.List<SmbFileItem> pending = uiState.getPendingMultiDownloadItems();
        uiState.setMultiDownloadPending(false);
        uiState.setPendingMultiDownloadItems(null);
        if (pending == null || pending.isEmpty()) {
            if (progressCallback != null)
                progressCallback.showInfo(context.getString(de.schliweb.sambalite.R.string.multi_no_files_selected));
            return;
        }
        final int total = pending.size();
        final String opTitle = titleFor(OPERATION_DOWNLOAD, context.getString(de.schliweb.sambalite.R.string.multiple_files));
        final AtomicBoolean cancelFinalize = new AtomicBoolean(false);

        // Notify listeners that the operation is starting (to allow immediate UI reset of selection)
        try {
            notifyOperationStarted(OPERATION_DOWNLOAD, null);
        } catch (Throwable ignore) {
        }

        // Best-effort: persist read/write permission to the chosen destination folder
        try {
            final int flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            context.getContentResolver().takePersistableUriPermission(folderUri, flags);
        } catch (Exception e) {
            de.schliweb.sambalite.util.LogUtils.w("FileOperationsController", "Persistable URI permission failed: " + e.getMessage());
            try {
                if (progressCallback != null) {
                    progressCallback.showInfo(context.getString(de.schliweb.sambalite.R.string.saf_persist_permission_failed));
                }
            } catch (Throwable ignore) {
            }
        }

        showProgressShell(OPERATION_DOWNLOAD, opTitle, () -> {
            cancelFinalize.set(true);
            operationsViewModel.cancelDownload();
        });

        backgroundSmbManager.executeMultiFileOperation(
                "batchDownload:" + System.currentTimeMillis(),
                opTitle,
                cb -> {
                    try {
                        DocumentFile destDir = DocumentFile.fromTreeUri(context, folderUri);
                        if (destDir == null || !destDir.canWrite()) {
                            if (progressCallback != null) {
                                progressCallback.showError(
                                        context.getString(de.schliweb.sambalite.R.string.download_error),
                                        context.getString(de.schliweb.sambalite.R.string.download_error_invalid_destination)
                                );
                                // Ensure any detailed progress UI is dismissed even if we didn't explicitly show it here
                                try {
                                    progressCallback.hideDetailedProgressDialog();
                                } catch (Throwable ignore) {
                                }
                            }
                            // Notify completion (failure) so UI can reset selection
                            try {
                                notifyOperationCompleted(OPERATION_DOWNLOAD, null, false, context.getString(de.schliweb.sambalite.R.string.download_error_invalid_destination));
                            } catch (Throwable ignore) {
                            }
                            return Boolean.TRUE;
                        }
                        int index = 0;
                        int success = 0;
                        java.util.ArrayList<String> failed = new java.util.ArrayList<>();

                        for (SmbFileItem f : pending) {
                            if (cancelFinalize.get()) break;
                            index++;
                            String fileName = f.getName();
                            cb.updateFileProgress(index, total, fileName);
                            // Choose a unique destination file name
                            String targetName = generateUniqueName(destDir, fileName);
                            // Re-check cancellation before creating target file to avoid unnecessary work
                            if (cancelFinalize.get()) break;
                            DocumentFile outDoc = null;
                            try {
                                outDoc = destDir.createFile("*/*", targetName);
                                if (outDoc == null)
                                    throw new Exception(context.getString(de.schliweb.sambalite.R.string.failed_to_create_target_file));
                            } catch (Exception e) {
                                failed.add(fileName + ": " + e.getMessage());
                                continue;
                            }

                            File tempFile = null;
                            final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);

                            try {
                                tempFile = File.createTempFile("download", ".tmp", context.getCacheDir());
                                uiState.setTempFile(tempFile);
                                // Re-check cancellation after creating temp file; clean up immediately if cancelled
                                if (cancelFinalize.get()) {
                                    try {
                                        if (tempFile != null && tempFile.exists()) tempFile.delete();
                                    } catch (Throwable ignore) {
                                    }
                                    try {
                                        uiState.setTempFile(null);
                                    } catch (Throwable ignore) {
                                    }
                                    break;
                                }
                            } catch (Exception e) {
                                failed.add(fileName + ": " + context.getString(de.schliweb.sambalite.R.string.cannot_create_temp_file));
                                continue;
                            }

                            FileOperationCallbacks.ProgressCallback fileProgress = new FileOperationCallbacks.ProgressCallback() {
                                @Override
                                public void updateFileProgress(int currentFile, int totalFiles, String currentFileName) { /* aggregated by service */ }

                                @Override
                                public void updateBytesProgress(long currentBytes, long totalBytes, String curName) {
                                    int pct = ProgressFormat.percentOfBytes(currentBytes, totalBytes);
                                    String status = ProgressFormat.formatBytes(context.getString(de.schliweb.sambalite.R.string.downloading), currentBytes, totalBytes);
                                    updateOperationProgress(OPERATION_DOWNLOAD, f, null, pct, status);
                                    cb.updateBytesProgress(currentBytes, totalBytes, curName);
                                }

                                @Override
                                public void updateProgress(String progressInfo) {
                                    int p = ProgressFormat.parsePercent(progressInfo);
                                    updateOperationProgress(OPERATION_DOWNLOAD, f, null, p, progressInfo);
                                    cb.updateProgress(progressInfo);
                                }
                            };

                            FileOperationCallbacks.DownloadCallback inner = createFileDownloadCallbackWithNotification(
                                    tempFile, outDoc.getUri(), f, cancelFinalize, cb
                            );
                            final boolean[] ok = new boolean[1];
                            FileOperationCallbacks.DownloadCallback wrapped = new FileOperationCallbacks.DownloadCallback() {
                                @Override
                                public void onProgress(String status, int percentage) {
                                    inner.onProgress(status, percentage);
                                }

                                @Override
                                public void onResult(boolean s, String message) {
                                    try {
                                        ok[0] = s;
                                        inner.onResult(s, message);
                                    } finally {
                                        done.countDown();
                                    }
                                }
                            };

                            operationsViewModel.downloadFile(
                                    f,
                                    tempFile,
                                    wrapped,
                                    fileProgress
                            );

                            try {
                                done.await();
                                if (ok[0]) {
                                    success++;
                                } else {
                                    failed.add(fileName);
                                }
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                failed.add(fileName + " " + context.getString(de.schliweb.sambalite.R.string.interrupted_suffix));
                                break;
                            }
                        }

                        String base = context.getString(de.schliweb.sambalite.R.string.multi_download_summary, success, total);
                        String summary = summarizeBatch(base, cancelFinalize.get(), failed.size());
                        if (progressCallback != null) {
                            progressCallback.showInfo(summary);
                            // Close any detailed progress dialog to ensure clean UX after batch completes
                            try {
                                progressCallback.hideDetailedProgressDialog();
                            } catch (Throwable ignore) {
                            }
                        }
                        // Notify listeners about completion to reset selection
                        try {
                            boolean opSuccess = failed.isEmpty() && !cancelFinalize.get();
                            notifyOperationCompleted(OPERATION_DOWNLOAD, null, opSuccess, summary);
                        } catch (Throwable ignore) {
                        }
                        return Boolean.TRUE;
                    } catch (Exception e) {
                        try {
                            if (progressCallback != null) {
                                progressCallback.showError(
                                        context.getString(de.schliweb.sambalite.R.string.download_error),
                                        context.getString(de.schliweb.sambalite.R.string.download_failed_with_reason, e.getMessage())
                                );
                                try {
                                    progressCallback.hideDetailedProgressDialog();
                                } catch (Throwable ignore) {
                                }
                            }
                        } catch (Throwable ignore) {
                        }
                        // Notify completion (failure) so UI can reset selection
                        try {
                            String err = context.getString(de.schliweb.sambalite.R.string.download_failed_with_reason, e.getMessage());
                            notifyOperationCompleted(OPERATION_DOWNLOAD, null, false, err);
                        } catch (Throwable ignore2) {
                        }
                        return Boolean.TRUE;
                    } finally {
                        // Final safety: clear temp ref and ensure dialog is hidden
                        try {
                            uiState.setTempFile(null);
                        } catch (Throwable ignore) {
                        }
                        try {
                            if (progressCallback != null) progressCallback.hideDetailedProgressDialog();
                        } catch (Throwable ignore) {
                        }
                    }
                }
        );
    }

    private void trySelfGrantRead(Uri uri) {
        try {
            context.grantUriPermission(context.getPackageName(), uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            LogUtils.w("FileOperationsController", "Self-grant read failed: " + e.getMessage());
        }
        try {
            // Attempt to persist if possible (will fail silently if not persistable)
            context.getContentResolver().takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            // Not all providers allow this; ignore
        }
    }

    // ---- Helpers for download destinations ----
    private DocumentFile createDestinationFolder(Uri uri) throws Exception {
        DocumentFile documentFile = DocumentFile.fromTreeUri(context, uri);
        if (documentFile == null || !documentFile.exists() || !documentFile.isDirectory())
            throw new Exception("Invalid destination folder");
        DocumentFile subFolder = documentFile.createDirectory(uiState.getSelectedFile().getName());
        if (subFolder == null) throw new Exception("Failed to create folder");
        return subFolder;
    }

    private File createTempFolder() throws Exception {
        File tempFolder = new File(context.getCacheDir(), "download_" + System.currentTimeMillis());
        if (!tempFolder.mkdirs()) throw new Exception("Failed to create temporary folder");
        return tempFolder;
    }

    private String getFileNameFromUri(Uri uri) {
        LogUtils.d("FileOperationsController", "Getting file name from URI: " + uri);
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (android.database.Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) result = cursor.getString(nameIndex);
                }
            } catch (Exception e) {
                LogUtils.e("FileOperationsController", "Error querying content resolver: " + e.getMessage());
            }
        }
        if (result == null) {
            result = uri.getPath();
            if (result != null) {
                int cut = result.lastIndexOf('/');
                if (cut != -1) result = result.substring(cut + 1);
            }
        }
        return result;
    }

    private String getDocumentFileName(DocumentFile docFile, String fallback) {
        return docFile != null ? docFile.getName() : fallback;
    }

    private String buildRemotePath(String fileName) {
        String currentPath = fileListViewModel.getCurrentPath().getValue();
        return (currentPath == null || currentPath.equals("root")) ? fileName : currentPath + "/" + fileName;
    }

    // ---- Name conflict helpers for SAF ----
    private boolean documentChildExists(DocumentFile dir, String name) {
        if (dir == null) return false;
        DocumentFile[] children = dir.listFiles();
        if (children == null) return false;
        for (DocumentFile child : children) {
            if (child != null && name.equals(child.getName())) return true;
        }
        return false;
    }

    private String generateUniqueName(DocumentFile dir, String original) {
        if (original == null || original.isEmpty()) original = "file";
        if (!documentChildExists(dir, original)) return original;
        String name = original;
        String base = original;
        String ext = "";
        int dot = original.lastIndexOf('.');
        if (dot > 0 && dot < original.length() - 1) {
            base = original.substring(0, dot);
            ext = original.substring(dot); // includes the dot
        }
        int idx = 1;
        while (documentChildExists(dir, base + " (" + idx + ")" + ext)) {
            idx++;
        }
        return base + " (" + idx + ")" + ext;
    }

    // ---- Callbacks ----
    private FileOperationCallbacks.DownloadCallback createFileDownloadCallbackWithNotification(
            File tempFile, Uri uri, SmbFileItem file, AtomicBoolean cancelFinalize, BackgroundSmbManager.MultiFileProgressCallback serviceCb) {

        final java.util.concurrent.atomic.AtomicBoolean FINALIZING = new java.util.concurrent.atomic.AtomicBoolean(false);

        return new FileOperationCallbacks.DownloadCallback() {
            @Override
            public void onProgress(String status, int percentage) {
                if (FINALIZING.get()) return;
                int mapped = Math.max(0, Math.min(SMB_CAP, percentage));
                String normalized = normalizePercentInStatus(status, mapped);
                updateOperationProgress(OPERATION_DOWNLOAD, file, null, mapped, normalized);
            }

            @Override
            public void onResult(boolean success, String message) {
                if (!success) {
                    handleOperationError(
                            OPERATION_DOWNLOAD, file, message, null, false,
                            () -> {
                                if (tempFile != null) tempFile.delete();
                                try {
                                    uiState.setTempFile(null);
                                } catch (Throwable ignore) {
                                }
                            }
                    );
                    return;
                }

                FINALIZING.set(true);
                updateFinalizingProgress(OPERATION_DOWNLOAD, file, null, false);

                final java.util.function.IntUnaryOperator mapFinal = p -> SMB_CAP + Math.max(
                        0,
                        Math.min(FINALIZE_WINDOW_PCT, (int) Math.round(p * (FINALIZE_WINDOW_PCT / 100.0)))
                );

                FileOperations.copyFileToUriAsync(
                        tempFile,
                        uri,
                        context,
                        new FileOperations.Callback() {
                            @Override
                            public boolean isCancelled() {
                                return cancelFinalize.get();
                            }

                            @Override
                            public void onProgress(int percent, String status) {
                                int mapped = mapFinal.applyAsInt(percent);
                                String normalized = normalizePercentInStatus(status, mapped);
                                updateOperationProgress(OPERATION_DOWNLOAD, file, null, mapped, normalized);
                                serviceCb.updateProgress(normalized);
                            }

                            @Override
                            public void onDone() {
                                serviceCb.updateProgress(context.getString(de.schliweb.sambalite.R.string.finalizing_done));
                                updateOperationProgress(OPERATION_DOWNLOAD, file, null, 100, context.getString(de.schliweb.sambalite.R.string.finalizing_done));
                                handleOperationSuccess(
                                        OPERATION_DOWNLOAD, file, file != null ? file.getName() : null, false,
                                        context.getString(de.schliweb.sambalite.R.string.download_success),
                                        false,
                                        () -> {
                                            if (tempFile != null) tempFile.delete();
                                            try {
                                                uiState.setTempFile(null);
                                            } catch (Throwable ignore) {
                                            }
                                        }
                                );
                            }

                            @Override
                            public void onError(Exception e) {
                                String txt;
                                if (e instanceof FileOperations.OperationCancelledException) {
                                    txt = context.getString(de.schliweb.sambalite.R.string.download_cancelled_local_copy);
                                    handleOperationError(
                                            OPERATION_DOWNLOAD, file,
                                            txt,
                                            "Cancelled",
                                            false,
                                            () -> {
                                                if (tempFile != null) tempFile.delete();
                                                try {
                                                    uiState.setTempFile(null);
                                                } catch (Throwable ignore) {
                                                }
                                            }
                                    );
                                } else {
                                    txt = context.getString(de.schliweb.sambalite.R.string.error_copying_file_to_destination, e.getMessage());
                                    handleOperationError(
                                            OPERATION_DOWNLOAD, file,
                                            txt,
                                            "Finalize error",
                                            false,
                                            () -> {
                                                if (tempFile != null) tempFile.delete();
                                                try {
                                                    uiState.setTempFile(null);
                                                } catch (Throwable ignore) {
                                                }
                                            }
                                    );
                                }
                                serviceCb.updateProgress(txt);
                            }
                        }
                );
            }
        };
    }

    private FileOperationCallbacks.DownloadCallback createFolderDownloadCallbackWithNotification(
            File tempFolder, DocumentFile destFolder, SmbFileItem folder, AtomicBoolean cancelFinalize, BackgroundSmbManager.MultiFileProgressCallback serviceCb) {

        final java.util.concurrent.atomic.AtomicBoolean FINALIZING = new java.util.concurrent.atomic.AtomicBoolean(false);

        final java.util.function.IntUnaryOperator mapSmb = p -> Math.max(0, Math.min(SMB_CAP, p)); // 0..SMB_CAP
        final java.util.function.IntUnaryOperator mapFinal = p -> SMB_CAP + Math.max(
                0,
                Math.min(FINALIZE_WINDOW_PCT, (int) Math.round(p * (FINALIZE_WINDOW_PCT / 100.0)))
        );
        return new FileOperationCallbacks.DownloadCallback() {
            @Override
            public void onProgress(String status, int percentage) {
                if (FINALIZING.get()) return;
                int mapped = mapSmb.applyAsInt(percentage);
                String normalized = normalizePercentInStatus(status, mapped);
                updateOperationProgress(OPERATION_DOWNLOAD, folder, null, mapped, normalized);
            }

            @Override
            public void onResult(boolean success, String message) {
                if (!success) {
                    handleOperationError(OPERATION_DOWNLOAD, folder, message, null, false,
                            () -> FileOperations.deleteRecursive(tempFolder));
                    return;
                }

                FINALIZING.set(true);
                updateFinalizingProgress(OPERATION_DOWNLOAD, folder, null, true);

                FileOperations.copyFolderAsync(
                        tempFolder,
                        destFolder,
                        context,
                        new FileOperations.Callback() {
                            @Override
                            public boolean isCancelled() {
                                return cancelFinalize.get();
                            }

                            @Override
                            public void onStart() {
                                String txt = context.getString(de.schliweb.sambalite.R.string.finalizing_scanning_files);
                                updateOperationProgress(OPERATION_DOWNLOAD, folder, null, SMB_CAP, txt);
                                serviceCb.updateProgress(txt);
                            }

                            @Override
                            public void onProgress(int percent, String status) {
                                int mapped = mapFinal.applyAsInt(percent);
                                String normalized = normalizePercentInStatus(status, mapped);
                                updateOperationProgress(OPERATION_DOWNLOAD, folder, null, mapped, normalized);
                                serviceCb.updateProgress(normalized);
                            }

                            @Override
                            public void onFileCopied(String name, long bytes) { /* no-op */ }

                            @Override
                            public void onDone() {
                                String txt = context.getString(de.schliweb.sambalite.R.string.folder_download_success);
                                serviceCb.updateProgress(txt);
                                updateOperationProgress(OPERATION_DOWNLOAD, folder, null, 100, context.getString(de.schliweb.sambalite.R.string.finalizing_done));
                                handleOperationSuccess(
                                        OPERATION_DOWNLOAD, folder,
                                        folder != null ? folder.getName() : null, true,
                                        txt,
                                        true,
                                        () -> FileOperations.deleteRecursive(tempFolder)
                                );
                                // Ensure the progress dialog is closed after successful folder download
                                try {
                                    if (progressCallback != null) progressCallback.hideDetailedProgressDialog();
                                } catch (Throwable ignore) {
                                }
                            }

                            @Override
                            public void onError(Exception e) {
                                String txt;
                                if (e instanceof FileOperations.OperationCancelledException) {
                                    txt = context.getString(de.schliweb.sambalite.R.string.download_cancelled_local_copy);
                                    handleOperationError(
                                            OPERATION_DOWNLOAD, folder,
                                            txt,
                                            "Cancelled",
                                            false,
                                            () -> FileOperations.deleteRecursive(tempFolder)
                                    );
                                } else {
                                    txt = context.getString(de.schliweb.sambalite.R.string.error_copying_folder_to_destination, e.getMessage());
                                    handleOperationError(
                                            OPERATION_DOWNLOAD, folder,
                                            txt,
                                            "Finalize error",
                                            false,
                                            () -> FileOperations.deleteRecursive(tempFolder)
                                    );
                                }
                                serviceCb.updateProgress(txt);
                                // Ensure the progress dialog is closed after error/cancel in folder download
                                try {
                                    if (progressCallback != null) progressCallback.hideDetailedProgressDialog();
                                } catch (Throwable ignore) {
                                }
                            }
                        }
                );
            }
        };
    }

    private FileOperationCallbacks.UploadCallback createUploadCallbackWithNotification(SmbFileItem file, String fileName) {
        return new FileOperationCallbacks.UploadCallback() {
            @Override
            public void onProgress(String status, int percentage) {
                updateOperationProgress(OPERATION_UPLOAD, file, fileName, percentage, status);
            }

            @Override
            public void onResult(boolean success, String message) {
                if (success) {
                    updateFinalizingProgress(OPERATION_UPLOAD, file, fileName, false);
                    handleOperationSuccess(OPERATION_UPLOAD, file, fileName, false,
                            context.getString(de.schliweb.sambalite.R.string.upload_success), true, null);
                } else {
                    handleOperationError(OPERATION_UPLOAD, file, message, null, false, null);
                }
            }
        };
    }

    private FileOperationCallbacks.UploadCallback createFolderContentsUploadCallbackWithNotification(String folderName) {
        return new FileOperationCallbacks.UploadCallback() {
            @Override
            public void onProgress(String status, int percentage) {
                updateOperationProgress(OPERATION_FOLDER_UPLOAD, null, folderName, percentage, status);
            }

            @Override
            public void onResult(boolean success, String message) {
                if (progressCallback != null) progressCallback.setZipButtonsEnabled(true);
                if (success) {
                    updateFinalizingProgress(OPERATION_FOLDER_UPLOAD, null, folderName, true);
                    handleOperationSuccess(OPERATION_FOLDER_UPLOAD, null, folderName, true,
                            context.getString(de.schliweb.sambalite.R.string.folder_contents_upload_success), true, null);
                } else {
                    if (message != null && (message.contains("incomplete") || message.contains("of"))) {
                        String enhanced = message + "\n\nSome files were uploaded successfully. Check the server and retry if needed.";
                        handleOperationError(OPERATION_FOLDER_UPLOAD, null, enhanced, "Upload incomplete", true, null);
                    } else {
                        handleOperationError(OPERATION_FOLDER_UPLOAD, null, message, "Folder contents upload error", false, null);
                    }
                }
            }
        };
    }

    // ---- File exists dialog ----
    private FileOperationCallbacks.FileExistsCallback createFileExistsCallback() {
        return (fileName, confirmAction, cancelAction) -> {
            if (userFeedbackProvider != null) {
                userFeedbackProvider.showFileExistsDialog(fileName, confirmAction, cancelAction);
            } else if (progressCallback != null) {
                progressCallback.showFileExistsDialog(fileName, confirmAction, cancelAction);
            } else {
                cancelAction.run();
            }
        };
    }

    // ---- Feedback shims ----
    private void showSuccess(String message) {
        if (userFeedbackProvider != null) userFeedbackProvider.showSuccess(message);
        else if (progressCallback != null) progressCallback.showSuccess(message);
    }

    private void showError(String title, String message) {
        if (userFeedbackProvider != null) userFeedbackProvider.showError(title, message);
        else if (progressCallback != null) progressCallback.showError(title, message);
    }

    private void showInfo(String message) {
        if (userFeedbackProvider != null) userFeedbackProvider.showInfo(message);
        else if (progressCallback != null) progressCallback.showInfo(message);
    }

    // ---- Success/Error unifiers ----
    private void handleOperationSuccess(String operationType, SmbFileItem file, String itemName, boolean isFolder,
                                        String customMessage, boolean refreshFileList, Runnable customSuccessAction) {
        String successMessage = customMessage;
        if (successMessage == null) {
            String itemType = isFolder ? "folder" : "file";
            String opName = OPERATION_DOWNLOAD.equals(operationType) ? "downloaded" :
                    (OPERATION_UPLOAD.equals(operationType) || OPERATION_FOLDER_UPLOAD.equals(operationType)) ? "uploaded" :
                            OPERATION_DELETE.equals(operationType) ? "deleted" :
                                    OPERATION_RENAME.equals(operationType) ? "renamed" : "processed";
            successMessage = Character.toUpperCase(itemType.charAt(0)) + itemType.substring(1) + " " + opName + " successfully";
        }
        if (progressCallback != null) progressCallback.animateSuccess();
        // Dismiss any progress dialog first so the success Snackbar is visible above the navbar
        if (progressCallback != null) {
            progressCallback.hideLoadingIndicator();
            LogUtils.d("FileOperationsController", "Progress (short UI) cleaned up before showing success");
        }
        showSuccess(successMessage);
        if (refreshFileList) fileListViewModel.loadFiles(false);
        if (customSuccessAction != null) customSuccessAction.run();
        notifyOperationCompleted(operationType, file, true, successMessage);
    }

    private void handleOperationError(String operationType, SmbFileItem file, String errorMessage, String customErrorTitle,
                                      boolean refreshFileList, Runnable customErrorAction) {
        String errorTitle = customErrorTitle;
        if (errorTitle == null) {
            errorTitle = OPERATION_DOWNLOAD.equals(operationType) ? "Download error" :
                    (OPERATION_UPLOAD.equals(operationType) || OPERATION_FOLDER_UPLOAD.equals(operationType)) ? "Upload error" :
                            OPERATION_DELETE.equals(operationType) ? "Delete error" :
                                    OPERATION_RENAME.equals(operationType) ? "Rename error" : "Operation error";
        }
        showError(errorTitle, errorMessage);
        if (refreshFileList) fileListViewModel.loadFiles(false);
        if (customErrorAction != null) customErrorAction.run();
        notifyOperationCompleted(operationType, file, false, errorMessage);
        if (progressCallback != null) {
            progressCallback.hideLoadingIndicator();
            LogUtils.d("FileOperationsController", "Progress (short UI) cleaned up after error");
        }
    }

    // ---- Interfaces ----
    public interface FileOperationRequester {
        void requestFileOrFolderDownload(SmbFileItem file);

        void requestFileUpload();

        void requestFolderContentsUpload();

        void requestFileDeletion(SmbFileItem file);

        void requestFileRename(SmbFileItem file);
    }

    public interface FileOperationListener {
        void onFileOperationStarted(String operationType, SmbFileItem file);

        void onFileOperationCompleted(String operationType, SmbFileItem file, boolean success, String message);

        void onFileOperationProgress(String operationType, SmbFileItem file, int progress, String message);
    }

    public interface ProgressCallback {
        void showLoadingIndicator(String message, boolean cancelable, Runnable cancelAction);

        void updateLoadingMessage(String message);

        void setCancelButtonEnabled(boolean enabled);

        void hideLoadingIndicator();

        void showDetailedProgressDialog(String title, String message);

        void updateDetailedProgress(int percentage, String statusText, String fileName);

        void hideDetailedProgressDialog();

        void showProgressInUI(String operationName, String progressText);

        void showFileExistsDialog(String fileName, Runnable confirmAction, Runnable cancelAction);

        void setZipButtonsEnabled(boolean enabled);

        void animateSuccess();

        void showSuccess(String message);

        void showError(String title, String message);

        void showInfo(String message);
    }

    public class FileOperationRequesterImpl implements FileOperationRequester {
        @Override
        public void requestFileOrFolderDownload(SmbFileItem file) {
            uiState.setSelectedFile(file);
            notifyOperationStarted("download", file);
            if (activityResultController != null) activityResultController.initDownloadFileOrFolder(file);
            else LogUtils.e("FileOperationsController", "ActivityResultController is null, cannot launch file picker");
        }

        @Override
        public void requestFileUpload() {
            notifyOperationStarted("upload", null); /* activity launches picker later */
        }

        @Override
        public void requestFolderContentsUpload() {
            notifyOperationStarted("folderContentsUpload", null); /* activity launches picker later */
        }

        @Override
        public void requestFileDeletion(SmbFileItem file) {
            uiState.setSelectedFile(file);
            notifyOperationStarted("delete", file);
            if (dialogController != null) dialogController.showDeleteFileConfirmationDialog(file);
            else LogUtils.e("FileOperationsController", "DialogController is null, cannot show confirmation dialog");
        }

        @Override
        public void requestFileRename(SmbFileItem file) {
            uiState.setSelectedFile(file);
            notifyOperationStarted("rename", file);
            // Actual rename handled by DialogController elsewhere
        }

    }
}
