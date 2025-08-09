package de.schliweb.sambalite.ui.controllers;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import androidx.documentfile.provider.DocumentFile;
import de.schliweb.sambalite.data.background.BackgroundSmbManager;
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
 * Unifies progress tracking, error handling, and service notifications.
 */
public class FileOperationsController {

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

    @Setter private UserFeedbackProvider userFeedbackProvider; // preferred UX surface
    @Deprecated @Setter private ProgressCallback progressCallback; // legacy feedback path
    @Setter private ActivityResultController activityResultController;
    @Setter private DialogController dialogController;

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
    public void addListener(FileOperationListener listener) { if (listener != null && !listeners.contains(listener)) listeners.add(listener); }
    public void removeListener(FileOperationListener listener) { listeners.remove(listener); }
    private void notifyOperationStarted(String operationType, SmbFileItem file) { for (FileOperationListener l : listeners) l.onFileOperationStarted(operationType, file); }
    private void notifyOperationCompleted(String operationType, SmbFileItem file, boolean success, String message) { for (FileOperationListener l : listeners) l.onFileOperationCompleted(operationType, file, success, message); }
    private void notifyOperationProgress(String operationType, SmbFileItem file, int progress, String message) { for (FileOperationListener l : listeners) l.onFileOperationProgress(operationType, file, progress, message); }

    // ---- Unified progress helpers ----
    private void updateOperationProgress(String operationType, SmbFileItem file, String itemName, int percentage, String status) {
        String displayName = (file != null) ? file.getName() : itemName;
        if (progressCallback != null) progressCallback.updateDetailedProgress(percentage, status, displayName);
        notifyOperationProgress(operationType, file, percentage, status);
        // Forward to service notification as well
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
        if (OPERATION_UPLOAD.equals(operationType) || OPERATION_FOLDER_UPLOAD.equals(operationType)) return "Uploading: " + safeName;
        if (OPERATION_DELETE.equals(operationType)) return "Deleting: " + safeName;
        if (OPERATION_RENAME.equals(operationType)) return "Renaming: " + safeName;
        return safeName;
    }

    // ---- Public requester ----
    public FileOperationRequester getFileOperationRequester() { return new FileOperationRequesterImpl(); }

    // ---- File operations ----
    public void handleFileDownload(Uri uri) {
        if (uiState.getSelectedFile() == null) return;
        final String displayName = uiState.getSelectedFile().getName();
        final String opTitle = titleFor(OPERATION_DOWNLOAD, displayName);

        executeFileOperation(() -> {
            File tempFile = File.createTempFile("download", ".tmp", context.getCacheDir());
            uiState.setTempFile(tempFile);

            FileOperationCallbacks.ProgressCallback fileProgress = new FileOperationCallbacks.ProgressCallback() {
                @Override public void updateFileProgress(int currentFile, int totalFiles, String currentFileName) {
                    // single file: ignorieren, aber trotzdem Service updaten
                    backgroundSmbManagerProgress().updateFileProgress(1, 1, displayName);
                }
                @Override public void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
                    int pct = totalBytes > 0 ? (int)Math.round((currentBytes * 100.0)/totalBytes) : 0;
                    String status = "Downloading: " + formatBytes(currentBytes) + " / " + formatBytes(totalBytes);
                    updateOperationProgress(OPERATION_DOWNLOAD, uiState.getSelectedFile(), null, pct, status);
                    backgroundSmbManagerProgress().updateBytesProgress(currentBytes, totalBytes, fileName);
                }
                @Override public void updateProgress(String progressInfo) {
                    int percentage = extractPercent(progressInfo);
                    updateOperationProgress(OPERATION_DOWNLOAD, uiState.getSelectedFile(), null, percentage, progressInfo);
                    backgroundSmbManagerProgress().updateProgress(progressInfo);
                }
                private String formatBytes(long b){ if(b<1024)return b+" B"; int e=(int)(Math.log(b)/Math.log(1024)); return String.format("%.1f %sB", b/Math.pow(1024,e), "KMGTPE".charAt(e-1)); }
            };

            operationsViewModel.downloadFile(
                    uiState.getSelectedFile(),
                    tempFile,
                    createFileDownloadCallbackWithNotification(tempFile, uri, uiState.getSelectedFile()),
                    fileProgress
            );
        }, opTitle, OPERATION_DOWNLOAD);
    }

    // kleine Hilfe, um in Lambdas Zugriff auf cb zu haben:
    private BackgroundSmbManager.ProgressCallback backgroundSmbManagerProgress() {
        // wird vom Manager bei executeBackgroundOperation eingesetzt (Thread-lokal via Closure);
        // hier tricksen wir nicht – Übergabe passiert in den konkreten Callbacks weiter unten:
        return new BackgroundSmbManager.ProgressCallback() {
            @Override public void updateProgress(String info) { /* wird beim Aufruf vom Manager ersetzt */ }
        };
    }

    public void handleFolderDownload(Uri uri) {
        if (uiState.getSelectedFile() == null || !uiState.getSelectedFile().isDirectory()) return;
        final String folderName = uiState.getSelectedFile().getName();
        final String opTitle = titleFor(OPERATION_DOWNLOAD, folderName);

        executeFileOperation(() -> {
            DocumentFile destFolder = createDestinationFolder(uri);
            File tempFolder = createTempFolder();
            uiState.setTempFile(tempFolder);

            backgroundSmbManager.executeMultiFileOperation(
                    "folderDownload:" + System.currentTimeMillis(),
                    opTitle,
                    /* totalFiles unknown here → 0; die echte Zahl updaten wir dynamisch */ 0,
                    cb -> {
                        FileOperationCallbacks.ProgressCallback folderProgress = new FileOperationCallbacks.ProgressCallback() {
                            @Override public void updateFileProgress(int currentFile, int totalFiles, String currentFileName) {
                                int pct = totalFiles > 0 ? (currentFile * 100) / totalFiles : 0;
                                String status = "Downloading: " + currentFile + "/" + totalFiles + " - " + currentFileName;
                                updateOperationProgress(OPERATION_DOWNLOAD, uiState.getSelectedFile(), null, pct, status);
                                cb.updateFileProgress(currentFile, currentFileName);
                            }
                            @Override public void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
                                int pct = totalBytes > 0 ? (int)Math.round((currentBytes * 100.0)/totalBytes) : 0;
                                String status = "Downloading: " + formatBytes(currentBytes) + " / " + formatBytes(totalBytes);
                                updateOperationProgress(OPERATION_DOWNLOAD, uiState.getSelectedFile(), null, pct, status);
                                cb.updateBytesProgress(currentBytes, totalBytes, fileName);
                            }
                            @Override public void updateProgress(String progressInfo) {
                                updateOperationProgress(OPERATION_DOWNLOAD, uiState.getSelectedFile(), null, extractPercent(progressInfo), progressInfo);
                                cb.updateProgress(progressInfo);
                            }
                            private String formatBytes(long b){ if(b<1024)return b+" B"; int e=(int)(Math.log(b)/Math.log(1024)); return String.format("%.1f %sB", b/Math.pow(1024,e), "KMGTPE".charAt(e-1)); }
                        };

                        operationsViewModel.downloadFolder(
                                uiState.getSelectedFile(),
                                tempFolder,
                                createFolderDownloadCallbackWithNotification(tempFolder, destFolder, uiState.getSelectedFile()),
                                folderProgress
                        );
                        return Boolean.TRUE;
                    }
            );
        }, opTitle, OPERATION_DOWNLOAD);
    }

    public void handleFileUpload(Uri uri) {
        final String fileNameFromUri = getFileNameFromUri(uri);
        final String fileName = fileNameFromUri != null ? fileNameFromUri : "uploaded_file_" + System.currentTimeMillis();
        final String opTitle = titleFor(OPERATION_UPLOAD, fileName);

        executeFileOperation(() -> {
            File tempFile = File.createTempFile("upload", ".tmp", context.getCacheDir());
            uiState.setTempFile(tempFile);
            FileOperations.copyUriToFile(uri, tempFile, context);
            String remotePath = buildRemotePath(fileName);

            // Progress für Service & UI
            FileOperationCallbacks.ProgressCallback upProg = new FileOperationCallbacks.ProgressCallback() {
                @Override public void updateFileProgress(int currentFile, int totalFiles, String currentFileName) { /* single file */ }
                @Override public void updateBytesProgress(long currentBytes, long totalBytes, String file) {
                    int pct = totalBytes > 0 ? (int)Math.round((currentBytes * 100.0)/totalBytes) : 0;
                    String status = "Uploading: " + currentBytes + " / " + totalBytes;
                    updateOperationProgress(OPERATION_UPLOAD, null, fileName, pct, status);
                    // cb.updateBytesProgress(...) → siehe Pattern oben, wenn du den cb hier hineinreichst
                }
                @Override public void updateProgress(String progressInfo) {
                    updateOperationProgress(OPERATION_UPLOAD, null, fileName, extractPercent(progressInfo), progressInfo);
                    // cb.updateProgress(progressInfo);
                }
            };

            operationsViewModel.uploadFile(
                    tempFile,
                    remotePath,
                    createUploadCallbackWithNotification(null, fileName),
                    createFileExistsCallback(),
                    fileName
            );
        }, opTitle, OPERATION_UPLOAD);
    }

    public void handleFolderContentsUpload(Uri folderUri) {
        DocumentFile docFolder = DocumentFile.fromTreeUri(context, folderUri);
        String folderName = getDocumentFileName(docFolder, "folder");
        final String opTitle = titleFor(OPERATION_UPLOAD, folderName);
        LogUtils.d("FileOperationsController", "Starting folder contents upload: " + folderName);

        if (progressCallback != null) progressCallback.setZipButtonsEnabled(false);

        executeFileOperation(() -> {
            FileOperationCallbacks.ProgressCallback upProg = new FileOperationCallbacks.ProgressCallback() {
                @Override public void updateFileProgress(int currentFile, int totalFiles, String currentFileName) {
                    int pct = totalFiles > 0 ? (currentFile * 100) / totalFiles : 0;
                    String status = "Uploading: " + currentFile + "/" + totalFiles + " - " + currentFileName;
                    updateOperationProgress(OPERATION_FOLDER_UPLOAD, null, folderName, pct, status);
                    // cb.updateFileProgress(currentFile, totalFiles, currentFileName);
                }
                @Override public void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
                    int pct = totalBytes > 0 ? (int)Math.round((currentBytes * 100.0)/totalBytes) : 0;
                    updateOperationProgress(OPERATION_FOLDER_UPLOAD, null, folderName, pct,
                            "Uploading: " + currentBytes + " / " + totalBytes);
                    // cb.updateBytesProgress(currentBytes, totalBytes, fileName);
                }
                @Override public void updateProgress(String info) {
                    updateOperationProgress(OPERATION_FOLDER_UPLOAD, null, folderName, extractPercent(info), info);
                    // cb.updateProgress(info);
                }
            };

            operationsViewModel.uploadFolderContentsFromUri(
                    folderUri,
                    createFolderContentsUploadCallbackWithNotification(folderName),
                    createFileExistsCallback()
            );
        }, opTitle, OPERATION_FOLDER_UPLOAD);
    }


    // ---- Core execution wrapper ----
    private void executeFileOperation(FileOperation localWork,
                                      String operationName,
                                      String operationType) {

        LogUtils.d("FileOperationsController", "Starting operation via BackgroundSmbManager: " + operationName);

        if (progressCallback != null) {
            String initial = operationType.equals("upload") ? "Preparing upload..." : "Preparing download...";
            progressCallback.showDetailedProgressDialog(operationName, initial);
            String selected = uiState.getSelectedFile() != null ? uiState.getSelectedFile().getName() : "";
            progressCallback.updateDetailedProgress(0, initial, selected);
            if (progressCallback instanceof ProgressController pc) {
                pc.setDetailedProgressDialogCancelAction(() -> {
                    LogUtils.d("FileOperationsController", "User requested " + operationType + " cancellation");
                    if (operationType.equals("upload")) operationsViewModel.cancelUpload(); else operationsViewModel.cancelDownload();
                });
            }
        }

        // Alles läuft im Service – Progress kommt über cb.* in die Notification:
        backgroundSmbManager.executeBackgroundOperation(
                // eine halbwegs stabile ID wählen
                operationType + ":" + System.currentTimeMillis(),
                operationName,
                cb -> {
                    // lokale Arbeit ausführen; dabei in den FileOperationCallbacks unten cb.update*() aufrufen!
                    localWork.execute();
                    return null;
                }
        ).whenComplete((ok, err) -> {
            // UI-abschluss auf dem Main-Thread
            new Handler(context.getMainLooper()).post(() -> {
                if (err != null) {
                    // Fehlermeldung
                    String t = operationType.equals("upload") ? "Upload error" : "Download error";
                    showError(t, err.getMessage() != null ? err.getMessage() : "Unexpected error");
                    if (progressCallback != null) {
                        progressCallback.hideLoadingIndicator();
                        progressCallback.hideDetailedProgressDialog();
                    }
                } else {
                    // Erfolg wird in den spezifischen Callbacks (onResult) finalisiert
                    // (Dialog bleibt offen bis dorthin – wie bisher)
                }
            });
        });
    }

    // ---- Helpers for download destinations ----
    private DocumentFile createDestinationFolder(Uri uri) throws Exception {
        DocumentFile documentFile = DocumentFile.fromTreeUri(context, uri);
        if (documentFile == null || !documentFile.exists() || !documentFile.isDirectory()) throw new Exception("Invalid destination folder");
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
            if (result != null) { int cut = result.lastIndexOf('/'); if (cut != -1) result = result.substring(cut + 1); }
        }
        return result;
    }

    private String getDocumentFileName(DocumentFile docFile, String fallback) { return docFile != null ? docFile.getName() : fallback; }

    private String buildRemotePath(String fileName) {
        String currentPath = fileListViewModel.getCurrentPath().getValue();
        return (currentPath == null || currentPath.equals("root")) ? fileName : currentPath + "/" + fileName;
    }

    // ---- Callbacks ----
    private FileOperationCallbacks.DownloadCallback createFileDownloadCallbackWithNotification(File tempFile, Uri uri, SmbFileItem file) {
        final String opTitle = titleFor(OPERATION_DOWNLOAD, file != null ? file.getName() : "");
        return new FileOperationCallbacks.DownloadCallback() {
            @Override public void onProgress(String status, int percentage) {
                updateOperationProgress(OPERATION_DOWNLOAD, file, null, percentage, status);
            }
            @Override public void onResult(boolean success, String message) {
                if (success) {
                    try {
                        updateFinalizingProgress(OPERATION_DOWNLOAD, file, null, false);
                        FileOperations.copyFileToUri(tempFile, uri, context);
                        handleOperationSuccess(OPERATION_DOWNLOAD, file, file != null ? file.getName() : null, false,
                                "File downloaded successfully", false, () -> tempFile.delete());
                    } catch (Exception e) {
                        handleOperationError(OPERATION_DOWNLOAD, file, "Error copying file to URI: " + e.getMessage(), null,
                                false, () -> tempFile.delete());
                    }
                } else {
                    handleOperationError(OPERATION_DOWNLOAD, file, message, null, false, null);
                }
            }
        };
    }

    private FileOperationCallbacks.DownloadCallback createFolderDownloadCallbackWithNotification(File tempFolder, DocumentFile destFolder, SmbFileItem folder) {
        final String opTitle = titleFor(OPERATION_DOWNLOAD, folder != null ? folder.getName() : "");
        return new FileOperationCallbacks.DownloadCallback() {
            @Override public void onProgress(String status, int percentage) {
                updateOperationProgress(OPERATION_DOWNLOAD, folder, null, percentage, status);
            }
            @Override public void onResult(boolean success, String message) {
                if (success) {
                    try {
                        updateFinalizingProgress(OPERATION_DOWNLOAD, folder, null, true);
                        FileOperations.copyFolderToDocumentFile(tempFolder, destFolder, context);
                        handleOperationSuccess(OPERATION_DOWNLOAD, folder, folder != null ? folder.getName() : null, true,
                                "Folder downloaded successfully", true, () -> FileOperations.deleteRecursive(tempFolder));
                    } catch (Exception e) {
                        handleOperationError(OPERATION_DOWNLOAD, folder, e.getMessage(), null,
                                false, () -> FileOperations.deleteRecursive(tempFolder));
                    }
                } else {
                    handleOperationError(OPERATION_DOWNLOAD, folder, message, null, false,
                            () -> FileOperations.deleteRecursive(tempFolder));
                }
            }
        };
    }

    private FileOperationCallbacks.UploadCallback createUploadCallbackWithNotification(SmbFileItem file, String fileName) {
        final String opTitle = titleFor(OPERATION_UPLOAD, fileName);
        return new FileOperationCallbacks.UploadCallback() {
            @Override public void onProgress(String status, int percentage) {
                updateOperationProgress(OPERATION_UPLOAD, file, fileName, percentage, status);
            }
            @Override public void onResult(boolean success, String message) {
                if (success) {
                    updateFinalizingProgress(OPERATION_UPLOAD, file, fileName, false);
                    handleOperationSuccess(OPERATION_UPLOAD, file, fileName, false,
                            "File uploaded successfully", true, null);
                } else {
                    handleOperationError(OPERATION_UPLOAD, file, message, null, false, null);
                }
            }
        }; }

    private FileOperationCallbacks.UploadCallback createFolderContentsUploadCallbackWithNotification(String folderName) {
        final String opTitle = titleFor(OPERATION_UPLOAD, folderName);
        return new FileOperationCallbacks.UploadCallback() {
            @Override public void onProgress(String status, int percentage) {
                updateOperationProgress(OPERATION_FOLDER_UPLOAD, null, folderName, percentage, status);
            }
            @Override public void onResult(boolean success, String message) {
                if (progressCallback != null) progressCallback.setZipButtonsEnabled(true);
                if (success) {
                    updateFinalizingProgress(OPERATION_FOLDER_UPLOAD, null, folderName, true);
                    handleOperationSuccess(OPERATION_FOLDER_UPLOAD, null, folderName, true,
                            "Folder contents uploaded successfully", true, null);
                } else {
                    if (message != null && (message.contains("incomplete") || message.contains("of"))) {
                        String enhanced = message + "\n\nSome files were uploaded successfully. Check the server and retry if needed.";
                        handleOperationError(OPERATION_FOLDER_UPLOAD, null, enhanced, "Upload incomplete", true, null);
                    } else {
                        handleOperationError(OPERATION_FOLDER_UPLOAD, null, message, "Folder contents upload error", false, null);
                    }
                }
            }
        }; }

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
    private void showSuccess(String message) { if (userFeedbackProvider != null) userFeedbackProvider.showSuccess(message); else if (progressCallback != null) progressCallback.showSuccess(message); }
    private void showError(String title, String message) { if (userFeedbackProvider != null) userFeedbackProvider.showError(title, message); else if (progressCallback != null) progressCallback.showError(title, message); }
    private void showInfo(String message) { if (userFeedbackProvider != null) userFeedbackProvider.showInfo(message); else if (progressCallback != null) progressCallback.showInfo(message); }

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
        showSuccess(successMessage);
        if (refreshFileList) fileListViewModel.loadFiles(false);
        if (customSuccessAction != null) customSuccessAction.run();
        notifyOperationCompleted(operationType, file, true, successMessage);
        if (progressCallback != null) {
            progressCallback.hideLoadingIndicator();
            progressCallback.hideDetailedProgressDialog();
            LogUtils.d("FileOperationsController", "Progress dialog hidden after success");
        }
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
            progressCallback.hideDetailedProgressDialog();
            LogUtils.d("FileOperationsController", "Progress dialog hidden after error");
        }
    }

    // Percent helper
    private int extractPercent(String progressInfo) {
        if (progressInfo == null) return 0;
        try {
            int colon = progressInfo.indexOf(":");
            int pct = progressInfo.indexOf("%", Math.max(0, colon));
            if (pct > 0) {
                String s = progressInfo.substring(colon >= 0 ? colon + 1 : 0, pct).replaceAll("[^0-9]", "").trim();
                if (!s.isEmpty()) return Integer.parseInt(s);
            }
        } catch (Exception ignore) {}
        return 0;
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

    /** Bridges to the Foreground Service (typically implemented via ServiceController). */
    public interface ServiceCallback {
        void startOperation(String operationName);
        void finishOperation(String operationName, boolean success);
        void updateFileProgress(String operationName, int currentFile, int totalFiles, String currentFileName);
        void updateBytesProgress(String operationName, long currentBytes, long totalBytes, String fileName);
        void updateOperationProgress(String operationName, String progressInfo);
        // Optional (can be no-op in implementation): provide setters if you want deep-link intents
        default void setUploadParameters(String connectionId, String uploadPath) {}
        default void setDownloadParameters(String connectionId, String downloadPath) {}
        default void setSearchParameters(String connectionId, String query, int searchType, boolean includeSubfolders) {}
    }

    @FunctionalInterface private interface FileOperation { void execute() throws Exception; }

    public class FileOperationRequesterImpl implements FileOperationRequester {
        @Override public void requestFileOrFolderDownload(SmbFileItem file) {
            uiState.setSelectedFile(file);
            notifyOperationStarted("download", file);
            if (activityResultController != null) activityResultController.initDownloadFileOrFolder(file);
            else LogUtils.e("FileOperationsController", "ActivityResultController is null, cannot launch file picker");
        }
        @Override public void requestFileUpload() { notifyOperationStarted("upload", null); /* activity launches picker later */ }
        @Override public void requestFolderContentsUpload() { notifyOperationStarted("folderContentsUpload", null); /* activity launches picker later */ }
        @Override public void requestFileDeletion(SmbFileItem file) {
            uiState.setSelectedFile(file);
            notifyOperationStarted("delete", file);
            if (dialogController != null) dialogController.showDeleteFileConfirmationDialog(file);
            else LogUtils.e("FileOperationsController", "DialogController is null, cannot show confirmation dialog");
        }
        @Override public void requestFileRename(SmbFileItem file) {
            uiState.setSelectedFile(file);
            notifyOperationStarted("rename", file);
            // Actual rename handled by DialogController elsewhere
        }
    }
}
