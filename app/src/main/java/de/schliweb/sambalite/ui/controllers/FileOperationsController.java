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
        String op = (operationType.equals(OPERATION_UPLOAD) || operationType.equals(OPERATION_FOLDER_UPLOAD)) ? "upload" : "download";
        String itemType = isFolder ? "folder" : "file";
        updateOperationProgress(operationType, file, itemName, 99, "Finalizing " + itemType + " " + op + "...");
    }

    private String titleFor(String operationType, String displayName) {
        String safeName = (displayName == null) ? "" : displayName;
        if (OPERATION_DOWNLOAD.equals(operationType)) return "Downloading: " + safeName;
        if (OPERATION_UPLOAD.equals(operationType) || OPERATION_FOLDER_UPLOAD.equals(operationType))
            return "Uploading: " + safeName;
        if (OPERATION_DELETE.equals(operationType)) return "Deleting: " + safeName;
        if (OPERATION_RENAME.equals(operationType)) return "Renaming: " + safeName;
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
        if (progressCallback instanceof ProgressController pc && pc.isTransferDialogShowing()) {
            try {
                pc.setDetailedProgressDialogCancelAction(onCancel);
            } catch (Throwable ignore) { /* optional */ }
        }
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
                    cb.updateFileProgress(1, 1, displayName);

                    File tempFile = File.createTempFile("download", ".tmp", context.getCacheDir());
                    uiState.setTempFile(tempFile);

                    final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);

                    FileOperationCallbacks.ProgressCallback fileProgress = new FileOperationCallbacks.ProgressCallback() {
                        @Override
                        public void updateFileProgress(int currentFile, int totalFiles, String currentFileName) { /* single file */ }

                        @Override
                        public void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
                            int pct = ProgressFormat.percentOfBytes(currentBytes, totalBytes);
                            String status = ProgressFormat.formatBytes("Downloading", currentBytes, totalBytes);
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
            showError("Download error", e.getMessage() != null ? e.getMessage() : "Invalid destination");
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
                            String status = ProgressFormat.formatIdx("Downloading", currentFile, totalFiles, currentFileName);
                            updateOperationProgress(OPERATION_DOWNLOAD, uiState.getSelectedFile(), currentFileName, pct, status);
                            cb.updateFileProgress(currentFile, totalFiles, currentFileName); // <-- 3 Parameter
                        }

                        @Override
                        public void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
                            int pct = ProgressFormat.percentOfBytes(currentBytes, totalBytes);
                            String status = ProgressFormat.formatBytes("Downloading", currentBytes, totalBytes);
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
                    return Boolean.TRUE;
                }
        );
    }

    public void handleFileUpload(Uri uri) {
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
                        cb.updateProgress("Preparing upload… staging file");
                        FileOperations.copyUriToFile(uri, tempFile, context);
                    } catch (Exception ex) {
                        if (tempFile != null) tempFile.delete();
                        uiState.setTempFile(null);

                        cb.updateProgress("Staging failed: " + ex.getMessage());
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
                            }
                    );
                    return;
                }

                FINALIZING.set(true);
                updateOperationProgress(OPERATION_DOWNLOAD, file, null, SMB_CAP, "Finalizing… preparing copy to destination");

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
                                serviceCb.updateProgress("Finalizing… done");
                                updateOperationProgress(OPERATION_DOWNLOAD, file, null, 100, "Completed");
                                handleOperationSuccess(
                                        OPERATION_DOWNLOAD, file, file != null ? file.getName() : null, false,
                                        "File downloaded successfully",
                                        false,
                                        () -> { if (tempFile != null) tempFile.delete(); }
                                );
                            }

                            @Override
                            public void onError(Exception e) {
                                String txt;
                                if (e instanceof FileOperations.OperationCancelledException) {
                                    txt = "Download cancelled by user during local copy";
                                    handleOperationError(
                                            OPERATION_DOWNLOAD, file,
                                            txt,
                                            "Cancelled",
                                            false,
                                            () -> { if (tempFile != null) tempFile.delete(); }
                                    );
                                } else {
                                    txt = "Error copying file to destination: " + e.getMessage();
                                    handleOperationError(
                                            OPERATION_DOWNLOAD, file,
                                            txt,
                                            "Finalize error",
                                            false,
                                            () -> { if (tempFile != null) tempFile.delete(); }
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
                updateOperationProgress(OPERATION_DOWNLOAD, folder, null, SMB_CAP,
                        "Finalizing… preparing copy to destination");

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
                                String txt = "Finalizing… scanning files";
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
                                String txt = "Folder downloaded successfully";
                                serviceCb.updateProgress(txt);
                                updateOperationProgress(OPERATION_DOWNLOAD, folder, null, 100, "Finalizing… done");
                                handleOperationSuccess(
                                        OPERATION_DOWNLOAD, folder,
                                        folder != null ? folder.getName() : null, true,
                                        txt,
                                        true,
                                        () -> FileOperations.deleteRecursive(tempFolder)
                                );
                            }

                            @Override
                            public void onError(Exception e) {
                                String txt;
                                if (e instanceof FileOperations.OperationCancelledException) {
                                    txt = "Download cancelled by user during local copy";
                                    handleOperationError(
                                            OPERATION_DOWNLOAD, folder,
                                            txt,
                                            "Cancelled",
                                            false,
                                            () -> FileOperations.deleteRecursive(tempFolder)
                                    );
                                } else {
                                    txt = "Error copying folder to destination: " + e.getMessage();
                                    handleOperationError(
                                            OPERATION_DOWNLOAD, folder,
                                            "Error copying folder to destination: " + e.getMessage(),
                                            "Finalize error",
                                            false,
                                            () -> FileOperations.deleteRecursive(tempFolder)
                                    );
                                }
                                serviceCb.updateProgress(txt);
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
