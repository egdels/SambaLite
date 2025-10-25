package de.schliweb.sambalite.ui.operations;

import android.os.Handler;
import android.os.Looper;
import androidx.documentfile.provider.DocumentFile;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import de.schliweb.sambalite.cache.IntelligentCacheManager;
import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.ui.FileBrowserState;
import de.schliweb.sambalite.ui.FileListViewModel;
import de.schliweb.sambalite.ui.utils.ProgressFormat;
import de.schliweb.sambalite.util.LogUtils;

import javax.inject.Inject;
import java.io.File;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class FileOperationsViewModel extends ViewModel {

    private final SmbRepository smbRepository;
    private final ExecutorService executor;
    private final android.content.Context context;
    private final FileBrowserState state;
    private final FileListViewModel fileListViewModel;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final AtomicInteger uploadCount = new AtomicInteger(0);
    private final AtomicInteger downloadCount = new AtomicInteger(0);

    private final MutableLiveData<Boolean> uploading = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> downloading = new MutableLiveData<>(false);
    private final MediatorLiveData<Boolean> anyOperationActive = new MediatorLiveData<>();

    public LiveData<Boolean> isUploading() {
        return uploading;
    }

    public LiveData<Boolean> isDownloading() {
        return downloading;
    }

    public LiveData<Boolean> isAnyOperationActive() {
        return anyOperationActive;
    }

    /**
     * Marks the beginning of a batch upload. Keeps the consolidated upload state active
     * across multiple individual file uploads to avoid closing/reopening the progress UI.
     */
    public void beginBatchUpload() {
        incUpload();
    }

    /**
     * Marks the end of a previously started batch upload.
     */
    public void endBatchUpload() {
        decUpload();
    }

    private void incUpload() {
        int c = uploadCount.incrementAndGet();
        mainHandler.post(() -> uploading.setValue(c > 0));
    }

    private void decUpload() {
        int c = Math.max(0, uploadCount.decrementAndGet());
        mainHandler.post(() -> uploading.setValue(c > 0));
    }

    private void incDownload() {
        int c = downloadCount.incrementAndGet();
        mainHandler.post(() -> downloading.setValue(c > 0));
    }

    private void decDownload() {
        int c = Math.max(0, downloadCount.decrementAndGet());
        mainHandler.post(() -> downloading.setValue(c > 0));
    }

    public record TransferProgress(int percentage, String statusText, String fileName) {
    }

    private final MutableLiveData<TransferProgress> transferProgress = new MutableLiveData<>();

    public LiveData<TransferProgress> getTransferProgress() {
        return transferProgress;
    }

    private void emitProgress(String status, int pct, String fileName) {
        int clamped = Math.max(0, Math.min(100, pct));
        transferProgress.postValue(new TransferProgress(clamped, status, fileName));
    }

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

        anyOperationActive.setValue(false);
        anyOperationActive.addSource(uploading, u ->
                anyOperationActive.setValue(Boolean.TRUE.equals(u) || Boolean.TRUE.equals(downloading.getValue())));
        anyOperationActive.addSource(downloading, d ->
                anyOperationActive.setValue(Boolean.TRUE.equals(d) || Boolean.TRUE.equals(uploading.getValue())));
    }

    public void cancelDownload() {
        LogUtils.d("FileOperationsViewModel", "Download cancellation requested from UI");
        state.setDownloadCancelled(true);
        smbRepository.cancelDownload();
    }

    /**
     * Returns true if a download has been cancelled in the current session/batch.
     */
    public boolean isDownloadCancelled() {
        return state.isDownloadCancelled();
    }

    public void cancelUpload() {
        LogUtils.d("FileOperationsViewModel", "Upload cancellation requested from UI");
        state.setUploadCancelled(true);
        smbRepository.cancelUpload();
    }

    public void downloadFile(SmbFileItem file, File localFile, FileOperationCallbacks.DownloadCallback callback) {
        downloadFile(file, localFile, callback, null);
    }

    public void downloadFile(SmbFileItem file,
                             File localFile,
                             FileOperationCallbacks.DownloadCallback callback,
                             FileOperationCallbacks.ProgressCallback progressCallback) {
        if (state.getConnection() == null || file == null || !file.isFile()) {
            LogUtils.w("FileOperationsViewModel", "Cannot download: invalid file or connection");
            if (callback != null) {
                String msg = context.getString(de.schliweb.sambalite.R.string.invalid_file_or_connection);
                mainHandler.post(() -> callback.onResult(false, msg));
            }
            return;
        }

        LogUtils.d("FileOperationsViewModel", "Downloading file: " + file.getName() + " to " + localFile.getAbsolutePath());

        incDownload();
        executor.execute(() -> {
            try {
                state.setDownloadCancelled(false);

                if (state.isDownloadCancelled()) {
                    LogUtils.d("FileOperationsViewModel", "Download cancelled before starting");
                    handleDownloadCancellation(localFile, "file download pre-start cancellation", callback, context.getString(de.schliweb.sambalite.R.string.download_cancelled_by_user));
                    return;
                }

                if (progressCallback != null) {
                    LogUtils.d("FileOperationsViewModel", "Using progress-aware file download");

                    final ProgressThrottler throttle = new ProgressThrottler(PROGRESS_THROTTLE_MS);
                    final int[] lastPctBox = {0};

                    // Seed initial progress so the UI can show the file name immediately
                    String initialStatus = context.getString(de.schliweb.sambalite.R.string.downloading_colon, file.getName());
                    emitProgress(initialStatus, 0, file.getName());
                    if (callback != null) {
                        mainHandler.post(() -> callback.onProgress(initialStatus, 0));
                    }

                    smbRepository.downloadFileWithProgress(
                            state.getConnection(),
                            file.getPath(),
                            localFile,
                            new BackgroundSmbManager.ProgressCallback() {
                                @Override
                                public void updateProgress(String progressInfo) {
                                    progressCallback.updateProgress(progressInfo);
                                    if (callback != null) {
                                        int raw = ProgressFormat.parsePercent(progressInfo);
                                        int pct = ensureMonotonicDownloadPct(raw, lastPctBox[0]);
                                        if (throttle.allow(pct)) {
                                            int p = pct;
                                            emitProgress(progressInfo, p, file.getName());
                                            mainHandler.post(() -> callback.onProgress(progressInfo, p));
                                        }
                                        if (pct > lastPctBox[0]) lastPctBox[0] = pct;
                                    }
                                }

                                @Override
                                public void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
                                    progressCallback.updateBytesProgress(currentBytes, totalBytes, fileName);
                                    if (callback != null) {
                                        int raw = calculateAccuratePercentage(currentBytes, totalBytes);
                                        int pct = ensureMonotonicDownloadPct(raw, lastPctBox[0]);
                                        if (throttle.allow(pct)) {
                                            String status = ProgressFormat.formatBytes(context.getString(de.schliweb.sambalite.R.string.downloading), currentBytes, totalBytes);
                                            emitProgress(status, pct, fileName);
                                            int p = pct;
                                            mainHandler.post(() -> callback.onProgress(status, p));
                                        }
                                        if (pct > lastPctBox[0]) lastPctBox[0] = pct;
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
                    String ok = context.getString(de.schliweb.sambalite.R.string.download_success);
                    mainHandler.post(() -> callback.onResult(true, ok));
                }
            } catch (Exception e) {
                LogUtils.e("FileOperationsViewModel", "Download failed: " + e.getMessage());

                if (state.isDownloadCancelled() || (e.getMessage() != null && e.getMessage().contains("cancelled by user"))) {
                    LogUtils.i("FileOperationsViewModel", "Download was cancelled by user");
                    handleDownloadCancellation(localFile, "file download user cancellation", callback, context.getString(de.schliweb.sambalite.R.string.download_cancelled_by_user));
                } else {
                    cleanupDownloadFiles(localFile, "file download failure");
                    if (callback != null) {
                        String msg = context.getString(de.schliweb.sambalite.R.string.download_failed_with_reason, e.getMessage());
                        mainHandler.post(() -> callback.onResult(false, msg));
                    }
                    state.setErrorMessage(context.getString(de.schliweb.sambalite.R.string.failed_to_download_file_with_reason, e.getMessage()));
                }
            } finally {
                decDownload();
            }
        });
    }

    public void downloadFolder(SmbFileItem folder,
                               File localFolder,
                               FileOperationCallbacks.DownloadCallback callback,
                               FileOperationCallbacks.ProgressCallback progressCallback) {
        if (state.getConnection() == null || folder == null || !folder.isDirectory()) {
            LogUtils.w("FileOperationsViewModel", "Cannot download: invalid folder or connection");
            if (callback != null) {
                String msg = context.getString(de.schliweb.sambalite.R.string.invalid_folder_or_connection);
                mainHandler.post(() -> callback.onResult(false, msg));
            }
            return;
        }

        LogUtils.d("FileOperationsViewModel", "Downloading folder: " + folder.getName() + " to " + localFolder.getAbsolutePath());

        incDownload();
        executor.execute(() -> {
            try {
                state.setDownloadCancelled(false);

                if (state.isDownloadCancelled()) {
                    LogUtils.d("FileOperationsViewModel", "Folder download cancelled before starting");
                    handleDownloadCancellation(localFolder, "folder download pre-start cancellation", callback, context.getString(de.schliweb.sambalite.R.string.download_cancelled_by_user));
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
                                private final ProgressThrottler throttle = new ProgressThrottler(PROGRESS_THROTTLE_MS);

                                private int lastCurrentFile = 0;
                                private int lastTotalFiles = 1;

                                @Override
                                public void updateFileProgress(int currentFile, int totalFiles, String currentFileName) {
                                    if (currentFile > 0) lastCurrentFile = currentFile;
                                    if (totalFiles > 0) lastTotalFiles = totalFiles;

                                    if (progressCallback != null) {
                                        progressCallback.updateFileProgress(currentFile, totalFiles, currentFileName);
                                    }

                                    int pct = ensureMonotonicDownloadPct(
                                            (int) Math.floor(((Math.max(currentFile - 1, 0)) * 100.0) / Math.max(lastTotalFiles, 1)),
                                            lastProgressPercentage
                                    );

                                    if (callback != null && throttle.allow(pct)) {
                                        String status = (lastTotalFiles > 0 && lastCurrentFile > 0)
                                                ? ProgressFormat.formatIdx(context.getString(de.schliweb.sambalite.R.string.downloading), lastCurrentFile, lastTotalFiles, currentFileName)
                                                : context.getString(de.schliweb.sambalite.R.string.downloading_colon, currentFileName);
                                        final int p = pct;
                                        emitProgress(status, pct, currentFileName);
                                        mainHandler.post(() -> callback.onProgress(status, p));
                                    }
                                    if (pct > lastProgressPercentage) lastProgressPercentage = pct;
                                }

                                @Override
                                public void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
                                    int filePct = calculateAccuratePercentage(currentBytes, totalBytes);

                                    int overallRaw = (lastTotalFiles > 0)
                                            ? (int) Math.floor(((Math.max(lastCurrentFile - 1, 0) * 100.0) + filePct) / lastTotalFiles)
                                            : filePct;

                                    int pct = ensureMonotonicDownloadPct(overallRaw, lastProgressPercentage);
                                    if (callback != null && throttle.allow(pct)) {
                                        String base = (lastTotalFiles > 0 && lastCurrentFile > 0)
                                                ? ProgressFormat.formatIdx(context.getString(de.schliweb.sambalite.R.string.downloading), lastCurrentFile, lastTotalFiles, fileName)
                                                : context.getString(de.schliweb.sambalite.R.string.downloading_colon, fileName);

                                        String status = (filePct >= 0 && totalBytes > 0)
                                                ? base + " • " + filePct + "% (" + ProgressFormat.formatBytesOnly(currentBytes, totalBytes) + ")"
                                                : base;

                                        emitProgress(status, pct, fileName);
                                        final int p = pct;
                                        mainHandler.post(() -> callback.onProgress(status, p));
                                    }
                                    if (pct > lastProgressPercentage) lastProgressPercentage = pct;
                                }

                                @Override
                                public void updateProgress(String progressInfo) {
                                    if (progressInfo != null && progressInfo.startsWith("File progress:")) return;

                                    int raw = ProgressFormat.parsePercent(progressInfo);
                                    int pct = ensureMonotonicDownloadPct(raw, lastProgressPercentage);
                                    if (callback != null && throttle.allow(pct)) {
                                        emitProgress(progressInfo, pct, null);
                                        final int p = pct;
                                        mainHandler.post(() -> callback.onProgress(progressInfo, p));
                                    }
                                    if (pct > lastProgressPercentage) lastProgressPercentage = pct;
                                }
                            }
                    );
                } else {
                    LogUtils.d("FileOperationsViewModel", "Using standard folder download (no progress)");
                    smbRepository.downloadFolder(state.getConnection(), folder.getPath(), localFolder);
                }

                LogUtils.i("FileOperationsViewModel", "Folder downloaded successfully: " + folder.getName());
                if (callback != null) {
                    String ok = context.getString(de.schliweb.sambalite.R.string.folder_download_success);
                    mainHandler.post(() -> callback.onResult(true, ok));
                }
            } catch (Exception e) {
                LogUtils.e("FileOperationsViewModel", "Folder download failed: " + e.getMessage());

                if (state.isDownloadCancelled() || (e.getMessage() != null && e.getMessage().contains("cancelled by user"))) {
                    LogUtils.i("FileOperationsViewModel", "Folder download was cancelled by user");
                    handleDownloadCancellation(localFolder, "folder download user cancellation", callback, context.getString(de.schliweb.sambalite.R.string.download_cancelled_by_user));
                } else {
                    cleanupDownloadFiles(localFolder, "folder download failure");
                    if (callback != null) {
                        String msg = context.getString(de.schliweb.sambalite.R.string.download_failed_with_reason, e.getMessage());
                        mainHandler.post(() -> callback.onResult(false, msg));
                    }
                    state.setErrorMessage("Failed to download folder: " + e.getMessage());
                }
            } finally {
                decDownload();
            }
        });
    }

    public void uploadFile(File localFile,
                           String remotePath,
                           FileOperationCallbacks.UploadCallback callback,
                           FileOperationCallbacks.FileExistsCallback fileExistsCallback,
                           String displayFileName) {
        if (state.getConnection() == null || localFile == null || !localFile.exists()) {
            LogUtils.w("FileOperationsViewModel", "Cannot upload: invalid file or connection");
            if (callback != null) {
                String msg = context.getString(de.schliweb.sambalite.R.string.invalid_file_or_connection);
                mainHandler.post(() -> callback.onResult(false, msg));
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
                            String msg = context.getString(de.schliweb.sambalite.R.string.upload_cancelled_by_user);
                            mainHandler.post(() -> callback.onResult(false, msg));
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
                    String msg = context.getString(de.schliweb.sambalite.R.string.error_checking_file_exists, e.getMessage());
                    mainHandler.post(() -> callback.onResult(false, msg));
                }
                state.setErrorMessage(context.getString(de.schliweb.sambalite.R.string.error_checking_file_exists, e.getMessage()));
            }
        });
    }

    private void performUpload(File localFile,
                               String remotePath,
                               FileOperationCallbacks.UploadCallback callback) {
        LogUtils.d("FileOperationsViewModel", "Uploading file: " + localFile.getName() + " to " + remotePath);
        state.setLoading(true);

        incUpload();
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
                                    int percentage = ProgressFormat.parsePercent(progressInfo);
                                    emitProgress(progressInfo, percentage, localFile.getName());
                                    mainHandler.post(() -> callback.onProgress(progressInfo, percentage));
                                }
                            }

                            @Override
                            public void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
                                if (callback != null) {
                                    String status = ProgressFormat.formatBytes(context.getString(de.schliweb.sambalite.R.string.uploading), currentBytes, totalBytes);
                                    int p = calculateAccuratePercentage(currentBytes, totalBytes);
                                    emitProgress(status, p, fileName);
                                    mainHandler.post(() -> callback.onProgress(status, p));
                                }
                            }
                        }
                );

                LogUtils.i("FileOperationsViewModel", "File uploaded successfully: " + localFile.getName());
                state.setLoading(false);
                if (callback != null) {
                    String ok = context.getString(de.schliweb.sambalite.R.string.upload_success);
                    mainHandler.post(() -> callback.onResult(true, ok));
                }

                invalidateCacheAndRefreshUI();
            } catch (Exception e) {
                LogUtils.e("FileOperationsViewModel", "Upload failed: " + e.getMessage());
                state.setLoading(false);

                if (state.isUploadCancelled() || (e.getMessage() != null && e.getMessage().contains("cancelled by user"))) {
                    LogUtils.i("FileOperationsViewModel", "Upload was cancelled by user");
                    if (callback != null) {
                        String msg = context.getString(de.schliweb.sambalite.R.string.upload_cancelled_by_user);
                        mainHandler.post(() -> callback.onResult(false, msg));
                    }
                    fileListViewModel.refreshCurrentDirectory();
                } else {
                    if (callback != null) {
                        String msg = context.getString(de.schliweb.sambalite.R.string.upload_failed_with_reason, e.getMessage());
                        mainHandler.post(() -> callback.onResult(false, msg));
                    }
                    state.setErrorMessage(context.getString(de.schliweb.sambalite.R.string.failed_to_upload_file_with_reason, e.getMessage()));
                }
            } finally {
                decUpload();
            }
        });
    }

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
                if (callback != null)
                    mainHandler.post(() -> callback.onResult(false, "Folder creation failed: " + e.getMessage()));
                state.setErrorMessage("Failed to create folder: " + e.getMessage());
            }
        });
    }

    public void deleteFile(SmbFileItem file, FileOperationCallbacks.DeleteFileCallback callback) {
        if (state.getConnection() == null || file == null) {
            LogUtils.w("FileOperationsViewModel", "Cannot delete: invalid file or connection");
            if (callback != null) {
                String msg = context.getString(de.schliweb.sambalite.R.string.invalid_file_or_connection);
                mainHandler.post(() -> callback.onResult(false, msg));
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
                    String ok = context.getString(de.schliweb.sambalite.R.string.delete_success);
                    mainHandler.post(() -> callback.onResult(true, ok));
                }

                String cachePattern = "conn_" + state.getConnection().getId() + "_path_" + state.getCurrentPathString().hashCode();
                IntelligentCacheManager.getInstance().invalidateSync(cachePattern);
                IntelligentCacheManager.getInstance().invalidateSearchCache(state.getConnection(), state.getCurrentPathString());
                fileListViewModel.refreshCurrentDirectory();
            } catch (Exception e) {
                LogUtils.e("FileOperationsViewModel", "File deletion failed: " + e.getMessage());
                state.setLoading(false);
                if (callback != null) {
                    String msg = context.getString(de.schliweb.sambalite.R.string.delete_failed_with_reason, e.getMessage());
                    mainHandler.post(() -> callback.onResult(false, msg));
                }
                state.setErrorMessage(context.getString(de.schliweb.sambalite.R.string.failed_to_delete_file_with_reason, e.getMessage()));
            }
        });
    }

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
                if (callback != null)
                    mainHandler.post(() -> callback.onResult(false, "File rename failed: " + e.getMessage()));
                state.setErrorMessage("Failed to rename file: " + e.getMessage());
            }
        });
    }

    public void uploadFolderContentsFromUri(android.net.Uri localFolderUri,
                                            FileOperationCallbacks.UploadCallback callback,
                                            FileOperationCallbacks.FileExistsCallback fileExistsCallback,
                                            BackgroundSmbManager.MultiFileProgressCallback serviceProgress) {
        LogUtils.d("FileOperationsViewModel", "Starting folder contents upload from URI (with service bridge): " + localFolderUri);

        incUpload();
        executor.execute(() -> {
            List<FileUploadTask> uploadTasks = new ArrayList<>();
            List<String> uploadedServerPaths = new ArrayList<>();
            List<String> createdFolders = new ArrayList<>();
            int totalFiles = 0;
            int successfulUploads = 0;
            int skippedFiles = 0;
            int finalTotalFiles = 0;

            try {
                state.setUploadCancelled(false);

                mainHandler.post(() -> {
                    if (callback != null) callback.onProgress("Analyzing folder structure...", 0);
                });
                emitProgress("Analyzing folder structure...", 0, null);
                if (serviceProgress != null) serviceProgress.updateProgress("Analyzing folder structure...");

                DocumentFile sourceFolder = DocumentFile.fromTreeUri(context, localFolderUri);
                if (sourceFolder == null || !sourceFolder.isDirectory()) throw new Exception("Invalid folder selected");

                uploadTasks = scanFolderForUpload(sourceFolder, "", uploadTasks);
                totalFiles = uploadTasks.size();
                finalTotalFiles = totalFiles;
                final int finalTotalFilesForLambda = finalTotalFiles;

                if (totalFiles == 0) {
                    if (callback != null)
                        mainHandler.post(() -> callback.onResult(true, "Folder is empty - nothing to upload"));
                    if (serviceProgress != null) serviceProgress.updateProgress("Folder empty – nothing to upload");
                    return;
                }

                LogUtils.d("FileOperationsViewModel", "Found " + totalFiles + " files to upload");
                mainHandler.post(() -> {
                    if (callback != null) callback.onProgress("Creating folder structure...", 5);
                });
                emitProgress("Creating folder structure...", 5, null);
                if (serviceProgress != null) serviceProgress.updateProgress("Creating folder structure...");

                createFolderStructure(uploadTasks, createdFolders);

                for (int i = 0; i < uploadTasks.size(); i++) {
                    if (state.isUploadCancelled()) {
                        LogUtils.d("FileOperationsViewModel", "Upload cancelled by user at file " + (i + 1) + " of " + finalTotalFilesForLambda);
                        throw new Exception("Upload cancelled by user");
                    }

                    FileUploadTask task = uploadTasks.get(i);
                    final int currentFileIndex = i + 1;

                    if (serviceProgress != null) {
                        serviceProgress.updateFileProgress(currentFileIndex, finalTotalFiles, task.fileName);
                    }

                    try {
                        uploadSingleFileFromTask(task,
                                fileExistsCallback,
                                callback,
                                currentFileIndex,
                                finalTotalFilesForLambda,
                                serviceProgress
                        );
                        successfulUploads++;
                        uploadedServerPaths.add(task.serverPath);
                        LogUtils.d("FileOperationsViewModel", "Successfully uploaded file " + currentFileIndex + ": " + task.fileName);
                    } catch (FileSkippedException e) {
                        LogUtils.d("FileOperationsViewModel", "File skipped by user: " + task.fileName);
                        skippedFiles++;
                    } catch (Exception e) {
                        LogUtils.e("FileOperationsViewModel", "Failed to upload file " + task.fileName + ": " + e.getMessage());
                    }
                }

                mainHandler.post(() -> {
                    if (callback != null) callback.onProgress("Verifying upload integrity...", 95);
                });
                if (serviceProgress != null) serviceProgress.updateProgress("Verifying upload integrity...");

                int failedFiles = totalFiles - successfulUploads - skippedFiles;

                if (successfulUploads < totalFiles) {
                    StringBuilder message = new StringBuilder();
                    message.append("Upload incomplete: ").append(successfulUploads)
                            .append(" of ").append(finalTotalFiles).append(" files uploaded successfully");
                    if (skippedFiles > 0) message.append(", ").append(skippedFiles).append(" file(s) skipped");
                    if (failedFiles > 0) message.append(", ").append(failedFiles).append(" file(s) failed");

                    String text = message + ". Check the server and retry if needed.";
                    LogUtils.w("FileOperationsViewModel", text);

                    final String finalText = text;
                    mainHandler.post(() -> {
                        if (callback != null) callback.onResult(false, finalText);
                    });
                    if (serviceProgress != null) serviceProgress.updateProgress("Upload incomplete");
                } else {
                    LogUtils.i("FileOperationsViewModel", "All " + finalTotalFiles + " files uploaded successfully");

                    IntelligentCacheManager.getInstance().invalidateSearchCache(state.getConnection(), state.getCurrentPathString());
                    String cachePattern = "conn_" + state.getConnection().getId() + "_path_" + state.getCurrentPathString().hashCode();
                    IntelligentCacheManager.getInstance().invalidateSync(cachePattern);

                    String successMessage = "All " + finalTotalFilesForLambda + " files uploaded successfully";
                    if (skippedFiles > 0)
                        successMessage = successfulUploads + " files uploaded successfully, " + skippedFiles + " files skipped";
                    final String finalSuccessMessage = successMessage;

                    final String uiMsg = "Upload completed successfully!";
                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onProgress(uiMsg, 100);
                            callback.onResult(true, finalSuccessMessage);
                        }
                        fileListViewModel.refreshCurrentDirectory();
                    });
                    if (serviceProgress != null) serviceProgress.updateProgress(uiMsg);
                }

            } catch (Exception e) {
                LogUtils.e("FileOperationsViewModel", "Error uploading folder contents: " + e.getMessage());
                mainHandler.post(() -> {
                    if (callback != null) callback.onProgress("Cleaning up incomplete upload...", 95);
                });
                if (serviceProgress != null) serviceProgress.updateProgress("Cleaning up incomplete upload...");

                cleanupIncompleteUpload(createdFolders, uploadedServerPaths);
                String errorMessage = "Folder upload failed: " + e.getMessage();
                if (successfulUploads > 0)
                    errorMessage += " (" + successfulUploads + " of " + finalTotalFiles + " files were uploaded)";
                final String finalErrorMessage = errorMessage;

                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onResult(false, finalErrorMessage);
                        fileListViewModel.refreshCurrentDirectory();
                    }
                });
                if (serviceProgress != null) serviceProgress.updateProgress("Upload failed");
            } finally {
                decUpload();
            }
        });
    }

    public void uploadFolderContentsFromUri(android.net.Uri localFolderUri,
                                            FileOperationCallbacks.UploadCallback callback,
                                            FileOperationCallbacks.FileExistsCallback fileExistsCallback) {
        // Backwards-compat: keine Service-Bridge
        uploadFolderContentsFromUri(localFolderUri, callback, fileExistsCallback, null);
    }

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
                                          Integer totalFiles,
                                          BackgroundSmbManager.MultiFileProgressCallback serviceProgress) throws Exception {
        boolean fileExists = smbRepository.fileExists(state.getConnection(), task.serverPath);

        if (fileExists && fileExistsCallback != null) {
            LogUtils.d("FileOperationsViewModel", "File already exists on server: " + task.serverPath);

            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicBoolean shouldOverwrite = new java.util.concurrent.atomic.AtomicBoolean(false);
            final java.util.concurrent.atomic.AtomicBoolean userCancelled = new java.util.concurrent.atomic.AtomicBoolean(false);

            Runnable confirmAction = () -> {
                shouldOverwrite.set(true);
                latch.countDown();
            };
            Runnable cancelAction = () -> {
                userCancelled.set(true);
                latch.countDown();
            };

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

            if (currentFileIndex != null && totalFiles != null) {
                BackgroundSmbManager.ProgressCallback progressCallback = new BackgroundSmbManager.ProgressCallback() {
                    @Override
                    public void updateProgress(String progressInfo) {
                        int filePercentage = ProgressFormat.parsePercent(progressInfo);
                        final int overallPercentage = 10
                                + (int) ((currentFileIndex - 1) * (80.0 / totalFiles))
                                + (int) ((filePercentage * (80.0 / totalFiles)) / 100);

                        String progressMessage = ProgressFormat.formatIdx(
                                "Uploading", currentFileIndex, totalFiles, task.fileName
                        ) + " • " + filePercentage + "%";

                        emitProgress(progressMessage, overallPercentage, task.fileName);
                        mainHandler.post(() -> callback.onProgress(progressMessage, overallPercentage));

                        if (serviceProgress != null) {
                            serviceProgress.updateProgress(progressMessage);
                        }
                    }

                    @Override
                    public void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
                        if (callback != null) {
                            String status = ProgressFormat.formatIdx(
                                    "Uploading", currentFileIndex, totalFiles, fileName
                            ) + " • " + ProgressFormat.percentOfBytes(currentBytes, totalBytes) + "% (" +
                                    ProgressFormat.formatBytesOnly(currentBytes, totalBytes) + ")";

                            int overallRaw = (int) Math.floor(
                                    ((currentFileIndex - 1) * 100.0 + ProgressFormat.percentOfBytes(currentBytes, totalBytes))
                                            / totalFiles
                            );

                            emitProgress(status, overallRaw, fileName);

                            mainHandler.post(() -> callback.onProgress(status, overallRaw));
                        }

                        if (serviceProgress != null) {
                            serviceProgress.updateBytesProgress(currentBytes, totalBytes, fileName);
                        }
                    }
                };

                if (serviceProgress != null) {
                    serviceProgress.updateFileProgress(currentFileIndex, totalFiles, task.fileName);
                }

                smbRepository.uploadFileWithProgress(state.getConnection(), tempFile, task.serverPath, progressCallback);
            } else {
                smbRepository.uploadFile(state.getConnection(), tempFile, task.serverPath);
            }
        } finally {
            cleanupLocalTempFile(tempFile, "individual file upload completion");
        }
    }

    private void cleanupIncompleteUpload(List<String> createdFolders, List<String> uploadedServerPaths) {
        try {
            LogUtils.d("FileOperationsViewModel", "Starting cleanup of incomplete upload");

            for (String path : uploadedServerPaths) {
                try {
                    smbRepository.deleteFile(state.getConnection(), path);
                    LogUtils.d("FileOperationsViewModel", "Cleaned up uploaded file: " + path);
                } catch (Exception e) {
                    LogUtils.w("FileOperationsViewModel", "Could not clean up file " + path + ": " + e.getMessage());
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

    private record FileUploadTask(DocumentFile file, String relativePath, String fileName, String serverPath) {
    }

    private static class FileSkippedException extends Exception {
        public FileSkippedException(String message) {
            super(message);
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        // Allow in-flight operations to complete so background uploads/downloads aren't interrupted
        executor.shutdown();
    }

    private static final long PROGRESS_THROTTLE_MS = 100; // ~10 Updates/Sek.
    private static final int PROGRESS_MIN_DELTA = 1;   // mind. +1% für sofortige Ausgabe

    private static int clampDownloadPct(int pct) {
        if (pct < 0) return 0;
        if (pct > 99) return 99; // niemals 100% vor onResult()
        return pct;
    }

    private int ensureMonotonicDownloadPct(int pct, int last) {
        pct = clampDownloadPct(pct);
        return Math.max(last, pct);
    }

    /**
     * Zeitbasierter Gate für Progress-Events.
     */
    private static final class ProgressThrottler {
        private final long minIntervalNs;
        private long lastEmitNs = 0;
        private int lastPct = -1;

        ProgressThrottler(long minMillis) {
            this.minIntervalNs = TimeUnit.MILLISECONDS.toNanos(minMillis);
        }

        /**
         * true → Event senden; false → unterdrücken
         */
        synchronized boolean allow(int pct) {
            long now = System.nanoTime();
            boolean deltaReached = (lastPct < 0) || (pct - lastPct >= PROGRESS_MIN_DELTA);
            boolean timeReached = (now - lastEmitNs) >= minIntervalNs;
            if (deltaReached || timeReached) {
                lastEmitNs = now;
                if (pct > lastPct) lastPct = pct;
                return true;
            }
            return false;
        }

        synchronized void reset() {
            lastEmitNs = 0;
            lastPct = -1;
        }
    }

}
