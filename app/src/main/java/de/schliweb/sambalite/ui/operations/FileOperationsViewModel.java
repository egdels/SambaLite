/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui.operations;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import de.schliweb.sambalite.cache.IntelligentCacheManager;
import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.transfer.TransferWorker;
import de.schliweb.sambalite.transfer.db.PendingTransfer;
import de.schliweb.sambalite.transfer.db.PendingTransferDao;
import de.schliweb.sambalite.transfer.db.TransferDatabase;
import de.schliweb.sambalite.ui.FileBrowserState;
import de.schliweb.sambalite.ui.FileListViewModel;
import de.schliweb.sambalite.ui.utils.ProgressFormat;
import de.schliweb.sambalite.util.LogUtils;
import de.schliweb.sambalite.util.OpenFileCacheManager;
import java.io.File;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;

@SuppressWarnings("KotlinPropertyAccess") // LiveData getters intentionally return wrapped types
public class FileOperationsViewModel extends ViewModel {

  final SmbRepository smbRepository;
  final ExecutorService executor;
  final android.content.Context context;
  final FileBrowserState state;
  final FileListViewModel fileListViewModel;
  final BackgroundSmbManager backgroundSmbManager;
  final TransferActionLog transferActionLog;

  final Handler mainHandler = new Handler(Looper.getMainLooper());
  private volatile boolean cleared = false;

  final AtomicInteger uploadCount = new AtomicInteger(0);
  final AtomicInteger downloadCount = new AtomicInteger(0);

  final MutableLiveData<Boolean> uploading = new MutableLiveData<>(false);
  final MutableLiveData<Boolean> downloading = new MutableLiveData<>(false);
  final MutableLiveData<Boolean> finalizing = new MutableLiveData<>(false);
  final MediatorLiveData<Boolean> anyOperationActive = new MediatorLiveData<>();

  public @NonNull LiveData<Boolean> isUploading() {
    return uploading;
  }

  public @NonNull LiveData<Boolean> isDownloading() {
    return downloading;
  }

  public @NonNull LiveData<Boolean> isFinalizing() {
    return finalizing;
  }

  public void setFinalizing(boolean value) {
    mainHandler.post(() -> finalizing.setValue(value));
  }

  public @NonNull LiveData<Boolean> isAnyOperationActive() {
    return anyOperationActive;
  }

  /**
   * Marks the beginning of a batch upload. Keeps the consolidated upload state active across
   * multiple individual file uploads to avoid closing/reopening the progress UI.
   */
  public void beginBatchUpload() {
    incUpload();
  }

  /** Marks the end of a previously started batch upload. */
  public void endBatchUpload() {
    decUpload();
  }

  void incUpload() {
    uploadCount.incrementAndGet();
    mainHandler.post(() -> uploading.setValue(uploadCount.get() > 0));
  }

  void decUpload() {
    uploadCount.updateAndGet(v -> Math.max(0, v - 1));
    mainHandler.post(() -> uploading.setValue(uploadCount.get() > 0));
  }

  public void incDownload() {
    downloadCount.incrementAndGet();
    mainHandler.post(() -> downloading.setValue(downloadCount.get() > 0));
  }

  public void decDownload() {
    downloadCount.updateAndGet(v -> Math.max(0, v - 1));
    mainHandler.post(() -> downloading.setValue(downloadCount.get() > 0));
  }

  public record TransferProgress(int percentage, String statusText, String fileName) {}

  final MutableLiveData<TransferProgress> transferProgress = new MutableLiveData<>();

  public @NonNull LiveData<TransferProgress> getTransferProgress() {
    return transferProgress;
  }

  public void emitProgress(@NonNull String status, int pct, @NonNull String fileName) {
    int clamped = Math.max(0, Math.min(100, pct));
    transferProgress.postValue(new TransferProgress(clamped, status, fileName));
  }

  @Inject
  public FileOperationsViewModel(
      @NonNull SmbRepository smbRepository,
      @NonNull android.content.Context context,
      @NonNull FileBrowserState state,
      @NonNull FileListViewModel fileListViewModel,
      @NonNull BackgroundSmbManager backgroundSmbManager) {
    this.smbRepository = smbRepository;
    this.context = context.getApplicationContext();
    this.state = state;
    this.fileListViewModel = fileListViewModel;
    this.backgroundSmbManager = backgroundSmbManager;
    this.executor = Executors.newSingleThreadExecutor();
    this.transferActionLog = new TransferActionLog(this.context);
    LogUtils.d("FileOperationsViewModel", "FileOperationsViewModel initialized");

    anyOperationActive.setValue(false);
    anyOperationActive.addSource(
        uploading,
        u ->
            anyOperationActive.setValue(
                Boolean.TRUE.equals(u) || Boolean.TRUE.equals(downloading.getValue())));
    anyOperationActive.addSource(
        downloading,
        d ->
            anyOperationActive.setValue(
                Boolean.TRUE.equals(d) || Boolean.TRUE.equals(uploading.getValue())));
  }

  public void cancelDownload() {
    LogUtils.d("FileOperationsViewModel", "Download cancellation requested from UI");
    state.setDownloadCancelled(true);
    smbRepository.cancelDownload();
  }

  /** Returns true if a download has been cancelled in the current session/batch. */
  public boolean isDownloadCancelled() {
    return state.isDownloadCancelled();
  }

  public void cancelUpload() {
    LogUtils.d("FileOperationsViewModel", "Upload cancellation requested from UI");
    state.setUploadCancelled(true);
    smbRepository.cancelUpload();
  }

  /**
   * Downloads a file into the open-file cache directory for viewing with an external app. Reports
   * download progress via the {@link #getTransferProgress()} LiveData and supports cancellation.
   *
   * @param file the remote file to download
   * @param onSuccess called on the main thread with the local cache file on success
   * @param onError called on the main thread with an error message on failure
   */
  public void downloadToCache(
      @NonNull SmbFileItem file,
      @NonNull java.util.function.Consumer<File> onSuccess,
      @NonNull java.util.function.Consumer<String> onError) {
    if (state.getConnection() == null || file == null || !file.isFile()) {
      LogUtils.w("FileOperationsViewModel", "Cannot download to cache: invalid file or connection");
      String msg = context.getString(de.schliweb.sambalite.R.string.invalid_file_or_connection);
      mainHandler.post(() -> onError.accept(msg));
      return;
    }

    File cacheDir = OpenFileCacheManager.getCacheDir(context);
    File targetFile = new File(cacheDir, file.getName());

    // Check if file already exists in cache with matching size and timestamp
    if (targetFile.exists() && targetFile.length() > 0) {
      boolean sizeMatches = file.getSize() <= 0 || targetFile.length() == file.getSize();
      boolean notStale =
          file.getLastModified() == null
              || targetFile.lastModified() >= file.getLastModified().getTime();
      if (sizeMatches && notStale) {
        LogUtils.d(
            "FileOperationsViewModel",
            "Cache hit (size+timestamp valid): "
                + file.getName()
                + " (local="
                + targetFile.length()
                + " bytes, remote="
                + file.getSize()
                + " bytes)");
        transferActionLog.log(TransferActionLog.Action.CACHE_HIT, file.getName());
        mainHandler.post(() -> onSuccess.accept(targetFile));
        return;
      }
      transferActionLog.log(
          TransferActionLog.Action.CACHE_MISS, file.getName(), "stale cache entry");
      LogUtils.d(
          "FileOperationsViewModel",
          "Cache stale, re-downloading: "
              + file.getName()
              + " (localSize="
              + targetFile.length()
              + ", remoteSize="
              + file.getSize()
              + ", localMod="
              + targetFile.lastModified()
              + ", remoteMod="
              + (file.getLastModified() != null ? file.getLastModified().getTime() : "null")
              + ")");
      targetFile.delete();
    }

    // Enforce cache size limit before downloading
    OpenFileCacheManager.enforceMaxSize(context);

    transferActionLog.log(TransferActionLog.Action.DOWNLOAD_STARTED, file.getName());
    LogUtils.d(
        "FileOperationsViewModel",
        "Downloading file to cache: " + file.getName() + " -> " + targetFile.getAbsolutePath());

    backgroundSmbManager.ensureServiceRunning();
    String opName = "Opening: " + file.getName();
    backgroundSmbManager.startOperation(opName);

    state.setDownloadCancelled(false);

    String initialStatus = ProgressFormat.Op.DOWNLOAD.label() + ": " + file.getName();
    emitProgress(initialStatus, 0, file.getName());

    executor.execute(
        () -> {
          try {
            final ProgressThrottler throttle = new ProgressThrottler(PROGRESS_THROTTLE_MS);
            final int[] lastPctBox = {0};

            smbRepository.downloadFileWithProgress(
                state.getConnection(),
                file.getPath(),
                targetFile,
                new BackgroundSmbManager.ProgressCallback() {
                  @Override
                  public void updateProgress(String progressInfo) {
                    backgroundSmbManager.updateOperationProgress(opName, progressInfo);
                    int raw = ProgressFormat.parsePercent(progressInfo);
                    int pct = ensureMonotonicDownloadPct(raw, lastPctBox[0]);
                    if (throttle.allow(pct)) {
                      emitProgress(progressInfo, pct, file.getName());
                    }
                    if (pct > lastPctBox[0]) lastPctBox[0] = pct;
                  }

                  @Override
                  public void updateBytesProgress(
                      long currentBytes, long totalBytes, String fileName) {
                    backgroundSmbManager.updateBytesProgress(
                        opName, currentBytes, totalBytes, fileName);
                    int raw = calculateAccuratePercentage(currentBytes, totalBytes);
                    int pct = ensureMonotonicDownloadPct(raw, lastPctBox[0]);
                    if (throttle.allow(pct)) {
                      String status =
                          ProgressFormat.formatBytes(
                              ProgressFormat.Op.DOWNLOAD.label(), currentBytes, totalBytes);
                      emitProgress(status, pct, file.getName());
                    }
                    if (pct > lastPctBox[0]) lastPctBox[0] = pct;
                  }
                });

            if (state.isDownloadCancelled()) {
              LogUtils.i(
                  "FileOperationsViewModel",
                  "Open-file download was cancelled by user: " + file.getName());
              if (targetFile.exists()) {
                targetFile.delete();
              }
              String msg =
                  context.getString(de.schliweb.sambalite.R.string.download_cancelled_by_user);
              mainHandler.post(() -> onError.accept(msg));
              return;
            }

            // Integrity check: verify downloaded file size matches remote size
            if (file.getSize() > 0
                && targetFile.exists()
                && targetFile.length() != file.getSize()) {
              LogUtils.w(
                  "FileOperationsViewModel",
                  "Download integrity check failed: expected "
                      + file.getSize()
                      + " bytes, got "
                      + targetFile.length()
                      + " bytes for "
                      + file.getName());
              targetFile.delete();
              throw new java.io.IOException(
                  "Download incomplete: expected "
                      + file.getSize()
                      + " bytes, got "
                      + targetFile.length());
            }

            // Enforce cache size limit after download to prevent unbounded growth
            // Exclude the just-downloaded file so it is not evicted before being opened
            OpenFileCacheManager.enforceMaxSize(context, targetFile);

            LogUtils.d(
                "FileOperationsViewModel",
                "File downloaded to cache successfully: " + file.getName());
            transferActionLog.log(TransferActionLog.Action.DOWNLOAD_COMPLETED, file.getName());
            mainHandler.post(() -> onSuccess.accept(targetFile));
          } catch (Exception e) {
            LogUtils.e(
                "FileOperationsViewModel", "Failed to download file to cache: " + e.getMessage());
            transferActionLog.log(
                TransferActionLog.Action.DOWNLOAD_FAILED, file.getName(), e.getMessage());
            // Clean up partial file
            if (targetFile.exists()) {
              targetFile.delete();
            }
            if (state.isDownloadCancelled()
                || (e.getMessage() != null && e.getMessage().contains("cancelled by user"))) {
              String msg =
                  context.getString(de.schliweb.sambalite.R.string.download_cancelled_by_user);
              mainHandler.post(() -> onError.accept(msg));
            } else {
              mainHandler.post(() -> onError.accept(e.getMessage()));
            }
          } finally {
            backgroundSmbManager.finishOperation(opName, true);
          }
        });
  }

  public void downloadFile(
      @NonNull SmbFileItem file,
      @NonNull File localFile,
      @Nullable FileOperationCallbacks.DownloadCallback callback) {
    downloadFile(file, localFile, callback, null);
  }

  public void downloadFile(
      @NonNull SmbFileItem file,
      @NonNull File localFile,
      @Nullable FileOperationCallbacks.DownloadCallback callback,
      @Nullable FileOperationCallbacks.ProgressCallback progressCallback) {
    if (state.getConnection() == null || file == null || !file.isFile()) {
      LogUtils.w("FileOperationsViewModel", "Cannot download: invalid file or connection");
      if (callback != null) {
        String msg = context.getString(de.schliweb.sambalite.R.string.invalid_file_or_connection);
        mainHandler.post(() -> callback.onResult(false, msg));
      }
      return;
    }

    transferActionLog.log(TransferActionLog.Action.DOWNLOAD_STARTED, file.getName());
    LogUtils.d(
        "FileOperationsViewModel",
        "Downloading file: " + file.getName() + " to " + localFile.getAbsolutePath());

    backgroundSmbManager.ensureServiceRunning();
    String dlOpName = "Downloading: " + file.getName();
    backgroundSmbManager.startOperation(dlOpName);
    incDownload();
    executor.execute(
        () -> {
          try {
            state.setDownloadCancelled(false);

            if (progressCallback != null) {
              LogUtils.d("FileOperationsViewModel", "Using progress-aware file download");

              final ProgressThrottler throttle = new ProgressThrottler(PROGRESS_THROTTLE_MS);
              final int[] lastPctBox = {0};

              // Seed initial progress so the UI can show the file name immediately
              String initialStatus = ProgressFormat.Op.DOWNLOAD.label() + ": " + file.getName();
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
                      backgroundSmbManager.updateOperationProgress(dlOpName, progressInfo);
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
                    public void updateBytesProgress(
                        long currentBytes, long totalBytes, String fileName) {
                      backgroundSmbManager.updateBytesProgress(
                          dlOpName, currentBytes, totalBytes, fileName);
                      progressCallback.updateBytesProgress(currentBytes, totalBytes, fileName);
                      if (callback != null) {
                        int raw = calculateAccuratePercentage(currentBytes, totalBytes);
                        int pct = ensureMonotonicDownloadPct(raw, lastPctBox[0]);
                        if (throttle.allow(pct)) {
                          String status =
                              ProgressFormat.formatBytes(
                                  ProgressFormat.Op.DOWNLOAD.label(), currentBytes, totalBytes);
                          emitProgress(status, pct, file.getName());
                          int p = pct;
                          mainHandler.post(() -> callback.onProgress(status, p));
                        }
                        if (pct > lastPctBox[0]) lastPctBox[0] = pct;
                      }
                    }
                  });
            } else {
              LogUtils.d("FileOperationsViewModel", "Using standard file download (no progress)");
              smbRepository.downloadFile(state.getConnection(), file.getPath(), localFile);
            }

            // Integrity check: verify downloaded file size matches remote size
            if (file.getSize() > 0 && localFile.exists() && localFile.length() != file.getSize()) {
              LogUtils.w(
                  "FileOperationsViewModel",
                  "Download integrity check failed: expected "
                      + file.getSize()
                      + " bytes, got "
                      + localFile.length()
                      + " bytes for "
                      + file.getName());
              cleanupDownloadFiles(localFile, "file download integrity failure");
              throw new java.io.IOException(
                  "Download incomplete: expected "
                      + file.getSize()
                      + " bytes, got "
                      + localFile.length());
            }

            LogUtils.i(
                "FileOperationsViewModel", "File downloaded successfully: " + file.getName());
            transferActionLog.log(TransferActionLog.Action.DOWNLOAD_COMPLETED, file.getName());
            backgroundSmbManager.finishOperation(dlOpName, true);
            if (callback != null) {
              String ok = context.getString(de.schliweb.sambalite.R.string.download_success);
              mainHandler.post(() -> callback.onResult(true, ok));
            }
          } catch (Exception e) {
            LogUtils.e("FileOperationsViewModel", "Download failed: " + e.getMessage());
            transferActionLog.log(
                TransferActionLog.Action.DOWNLOAD_FAILED, file.getName(), e.getMessage());
            backgroundSmbManager.finishOperation(dlOpName, false);

            if (state.isDownloadCancelled()
                || (e.getMessage() != null && e.getMessage().contains("cancelled by user"))) {
              LogUtils.i("FileOperationsViewModel", "Download was cancelled by user");
              handleDownloadCancellation(
                  localFile,
                  "file download user cancellation",
                  callback,
                  context.getString(de.schliweb.sambalite.R.string.download_cancelled_by_user));
            } else {
              cleanupDownloadFiles(localFile, "file download failure");
              if (callback != null) {
                String msg =
                    context.getString(
                        de.schliweb.sambalite.R.string.download_failed_with_reason, e.getMessage());
                mainHandler.post(() -> callback.onResult(false, msg));
              }
              state.setErrorMessage(
                  context.getString(
                      de.schliweb.sambalite.R.string.failed_to_download_file_with_reason,
                      e.getMessage()));
            }
          } finally {
            decDownload();
          }
        });
  }

  public void downloadFolder(
      @NonNull SmbFileItem folder,
      @NonNull File localFolder,
      @Nullable FileOperationCallbacks.DownloadCallback callback,
      @Nullable FileOperationCallbacks.ProgressCallback progressCallback) {
    if (state.getConnection() == null || folder == null || !folder.isDirectory()) {
      LogUtils.w("FileOperationsViewModel", "Cannot download: invalid folder or connection");
      if (callback != null) {
        String msg = context.getString(de.schliweb.sambalite.R.string.invalid_folder_or_connection);
        mainHandler.post(() -> callback.onResult(false, msg));
      }
      return;
    }

    transferActionLog.log(TransferActionLog.Action.DOWNLOAD_STARTED, folder.getName());
    LogUtils.d(
        "FileOperationsViewModel",
        "Downloading folder: " + folder.getName() + " to " + localFolder.getAbsolutePath());

    backgroundSmbManager.ensureServiceRunning();
    String folderDlOpName = "Downloading folder: " + folder.getName();
    backgroundSmbManager.startOperation(folderDlOpName);
    incDownload();
    executor.execute(
        () -> {
          try {
            state.setDownloadCancelled(false);

            if (progressCallback != null) {
              LogUtils.d("FileOperationsViewModel", "Using progress-aware folder download");
              smbRepository.downloadFolderWithProgress(
                  state.getConnection(),
                  folder.getPath(),
                  localFolder,
                  new BackgroundSmbManager.MultiFileProgressCallback() {
                    private int lastProgressPercentage = 0;
                    private final ProgressThrottler throttle =
                        new ProgressThrottler(PROGRESS_THROTTLE_MS);

                    private int lastCurrentFile = 0;
                    private int lastTotalFiles = 1;

                    @Override
                    public void updateFileProgress(
                        int currentFile, int totalFiles, String currentFileName) {
                      if (currentFile > 0) lastCurrentFile = currentFile;
                      if (totalFiles > 0) lastTotalFiles = totalFiles;

                      if (progressCallback != null) {
                        progressCallback.updateFileProgress(
                            currentFile, totalFiles, currentFileName);
                      }

                      int pct =
                          ensureMonotonicDownloadPct(
                              (int)
                                  Math.floor(
                                      (Math.max(currentFile - 1, 0) * 100.0)
                                          / Math.max(lastTotalFiles, 1)),
                              lastProgressPercentage);

                      if (callback != null && throttle.allow(pct)) {
                        String status =
                            (lastTotalFiles > 0 && lastCurrentFile > 0)
                                ? ProgressFormat.formatIdx(
                                    ProgressFormat.Op.DOWNLOAD.label(),
                                    lastCurrentFile,
                                    lastTotalFiles,
                                    currentFileName)
                                : ProgressFormat.Op.DOWNLOAD.label() + ": " + currentFileName;
                        final int p = pct;
                        emitProgress(status, pct, currentFileName);
                        mainHandler.post(() -> callback.onProgress(status, p));
                      }
                      if (pct > lastProgressPercentage) lastProgressPercentage = pct;
                    }

                    @Override
                    public void updateBytesProgress(
                        long currentBytes, long totalBytes, String fileName) {
                      int filePct = calculateAccuratePercentage(currentBytes, totalBytes);

                      int overallRaw =
                          (lastTotalFiles > 0)
                              ? (int)
                                  Math.floor(
                                      ((Math.max(lastCurrentFile - 1, 0) * 100.0) + filePct)
                                          / lastTotalFiles)
                              : filePct;

                      int pct = ensureMonotonicDownloadPct(overallRaw, lastProgressPercentage);
                      if (callback != null && throttle.allow(pct)) {
                        String base =
                            (lastTotalFiles > 0 && lastCurrentFile > 0)
                                ? ProgressFormat.formatIdx(
                                    ProgressFormat.Op.DOWNLOAD.label(),
                                    lastCurrentFile,
                                    lastTotalFiles,
                                    fileName)
                                : ProgressFormat.Op.DOWNLOAD.label() + ": " + fileName;

                        String status =
                            (filePct >= 0 && totalBytes > 0)
                                ? base
                                    + " • "
                                    + filePct
                                    + "% ("
                                    + ProgressFormat.formatBytesOnly(currentBytes, totalBytes)
                                    + ")"
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
                  });
            } else {
              LogUtils.d("FileOperationsViewModel", "Using standard folder download (no progress)");
              smbRepository.downloadFolder(state.getConnection(), folder.getPath(), localFolder);
            }

            LogUtils.i(
                "FileOperationsViewModel", "Folder downloaded successfully: " + folder.getName());
            transferActionLog.log(TransferActionLog.Action.DOWNLOAD_COMPLETED, folder.getName());
            backgroundSmbManager.finishOperation(folderDlOpName, true);
            if (callback != null) {
              String ok = context.getString(de.schliweb.sambalite.R.string.folder_download_success);
              mainHandler.post(() -> callback.onResult(true, ok));
            }
          } catch (Exception e) {
            LogUtils.e("FileOperationsViewModel", "Folder download failed: " + e.getMessage());
            transferActionLog.log(
                TransferActionLog.Action.DOWNLOAD_FAILED, folder.getName(), e.getMessage());
            backgroundSmbManager.finishOperation(folderDlOpName, false);

            if (state.isDownloadCancelled()
                || (e.getMessage() != null && e.getMessage().contains("cancelled by user"))) {
              LogUtils.i("FileOperationsViewModel", "Folder download was cancelled by user");
              handleDownloadCancellation(
                  localFolder,
                  "folder download user cancellation",
                  callback,
                  context.getString(de.schliweb.sambalite.R.string.download_cancelled_by_user));
            } else {
              cleanupDownloadFiles(localFolder, "folder download failure");
              if (callback != null) {
                String msg =
                    context.getString(
                        de.schliweb.sambalite.R.string.download_failed_with_reason, e.getMessage());
                mainHandler.post(() -> callback.onResult(false, msg));
              }
              state.setErrorMessage("Failed to download folder: " + e.getMessage());
            }
          } finally {
            decDownload();
          }
        });
  }

  /** Synchronously checks whether a remote file exists. Must be called from a background thread. */
  public boolean checkFileExists(@NonNull String remotePath) {
    if (state.getConnection() == null) return false;
    try {
      return smbRepository.fileExists(state.getConnection(), remotePath);
    } catch (Exception e) {
      LogUtils.e("FileOperationsViewModel", "Error checking if file exists: " + e.getMessage());
      return false;
    }
  }

  public void createFolder(
      @NonNull String folderName, @Nullable FileOperationCallbacks.CreateFolderCallback callback) {
    if (state.getConnection() == null || folderName == null || folderName.isEmpty()) {
      LogUtils.w(
          "FileOperationsViewModel", "Cannot create folder: invalid folder name or connection");
      if (callback != null)
        mainHandler.post(() -> callback.onResult(false, "Invalid folder name or connection"));
      return;
    }

    LogUtils.d(
        "FileOperationsViewModel",
        "Creating folder: " + folderName + " in path: " + state.getCurrentPathString());
    backgroundSmbManager.ensureServiceRunning();
    String createOpName = "Creating folder: " + folderName;
    backgroundSmbManager.startOperation(createOpName);
    state.setLoading(true);

    executor.execute(
        () -> {
          try {
            smbRepository.createDirectory(
                state.getConnection(), state.getCurrentPathString(), folderName);
            LogUtils.i("FileOperationsViewModel", "Folder created successfully: " + folderName);
            state.setLoading(false);
            backgroundSmbManager.finishOperation(createOpName, true);
            if (callback != null)
              mainHandler.post(() -> callback.onResult(true, "Folder created successfully"));

            IntelligentCacheManager.getInstance()
                .invalidateSearchCache(state.getConnection(), state.getCurrentPathString());
            String cachePattern =
                "conn_"
                    + state.getConnection().getId()
                    + "_path_"
                    + state.getCurrentPathString().hashCode();
            IntelligentCacheManager.getInstance().invalidateSync(cachePattern);
            fileListViewModel.refreshCurrentDirectory();
          } catch (Exception e) {
            LogUtils.e("FileOperationsViewModel", "Folder creation failed: " + e.getMessage());
            state.setLoading(false);
            backgroundSmbManager.finishOperation(createOpName, false);
            if (callback != null)
              mainHandler.post(
                  () -> callback.onResult(false, "Folder creation failed: " + e.getMessage()));
            state.setErrorMessage("Failed to create folder: " + e.getMessage());
          }
        });
  }

  public void deleteFile(
      @NonNull SmbFileItem file, @Nullable FileOperationCallbacks.DeleteFileCallback callback) {
    deleteFile(file, callback, false);
  }

  /**
   * Deletes a file on the SMB server.
   *
   * @param file the file to delete
   * @param callback optional callback for result notification
   * @param skipRefresh if true, skips the directory refresh after deletion (useful for batch
   *     operations where a single refresh is done at the end)
   */
  public void deleteFile(
      @NonNull SmbFileItem file,
      @Nullable FileOperationCallbacks.DeleteFileCallback callback,
      boolean skipRefresh) {
    if (state.getConnection() == null || file == null) {
      LogUtils.w("FileOperationsViewModel", "Cannot delete: invalid file or connection");
      if (callback != null) {
        String msg = context.getString(de.schliweb.sambalite.R.string.invalid_file_or_connection);
        mainHandler.post(() -> callback.onResult(false, msg));
      }
      return;
    }

    LogUtils.d(
        "FileOperationsViewModel",
        "Deleting file: " + file.getName() + " at path: " + file.getPath());
    backgroundSmbManager.ensureServiceRunning();
    String deleteOpName = "Deleting: " + file.getName();
    backgroundSmbManager.startOperation(deleteOpName);
    state.setLoading(true);

    executor.execute(
        () -> {
          try {
            smbRepository.deleteFile(state.getConnection(), file.getPath());
            LogUtils.i("FileOperationsViewModel", "File deleted successfully: " + file.getName());
            state.setLoading(false);
            backgroundSmbManager.finishOperation(deleteOpName, true);
            if (callback != null) {
              String ok = context.getString(de.schliweb.sambalite.R.string.delete_success);
              mainHandler.post(() -> callback.onResult(true, ok));
            }

            String cachePattern =
                "conn_"
                    + state.getConnection().getId()
                    + "_path_"
                    + state.getCurrentPathString().hashCode();
            IntelligentCacheManager.getInstance().invalidateSync(cachePattern);
            IntelligentCacheManager.getInstance()
                .invalidateSearchCache(state.getConnection(), state.getCurrentPathString());
            if (!skipRefresh) {
              fileListViewModel.refreshCurrentDirectory();
            }
          } catch (Exception e) {
            LogUtils.e("FileOperationsViewModel", "File deletion failed: " + e.getMessage());
            state.setLoading(false);
            backgroundSmbManager.finishOperation(deleteOpName, false);
            if (callback != null) {
              String msg =
                  context.getString(
                      de.schliweb.sambalite.R.string.delete_failed_with_reason, e.getMessage());
              mainHandler.post(() -> callback.onResult(false, msg));
            }
            state.setErrorMessage(
                context.getString(
                    de.schliweb.sambalite.R.string.failed_to_delete_file_with_reason,
                    e.getMessage()));
          }
        });
  }

  /**
   * Deletes multiple files in a single SMB session. This method is synchronous and should be called
   * from a background thread. It does not trigger a directory refresh — the caller is responsible
   * for refreshing after the batch completes.
   *
   * @param paths the list of file paths to delete
   * @return a list of paths that failed to delete
   * @throws Exception if the connection itself fails
   */
  @NonNull
  public List<String> deleteFilesBatch(@NonNull List<String> paths) throws Exception {
    if (state.getConnection() == null) {
      LogUtils.w("FileOperationsViewModel", "Cannot batch delete: no connection");
      return new ArrayList<>(paths);
    }
    LogUtils.d("FileOperationsViewModel", "Batch deleting " + paths.size() + " files");
    List<String> failed = smbRepository.deleteFiles(state.getConnection(), paths);
    // Invalidate cache once after all deletions
    if (state.getConnection() != null) {
      String cachePattern =
          "conn_"
              + state.getConnection().getId()
              + "_path_"
              + state.getCurrentPathString().hashCode();
      IntelligentCacheManager.getInstance().invalidateSync(cachePattern);
      IntelligentCacheManager.getInstance()
          .invalidateSearchCache(state.getConnection(), state.getCurrentPathString());
    }
    return failed;
  }

  public void renameFile(
      @NonNull SmbFileItem file,
      @NonNull String newName,
      @Nullable FileOperationCallbacks.RenameFileCallback callback) {
    if (state.getConnection() == null || file == null || newName == null || newName.isEmpty()) {
      LogUtils.w("FileOperationsViewModel", "Cannot rename: invalid file, name, or connection");
      if (callback != null)
        mainHandler.post(() -> callback.onResult(false, "Invalid file, name, or connection"));
      return;
    }

    LogUtils.d("FileOperationsViewModel", "Renaming file: " + file.getName() + " to " + newName);
    backgroundSmbManager.ensureServiceRunning();
    String renameOpName = "Renaming: " + file.getName();
    backgroundSmbManager.startOperation(renameOpName);
    state.setLoading(true);

    executor.execute(
        () -> {
          try {
            smbRepository.renameFile(state.getConnection(), file.getPath(), newName);
            LogUtils.i(
                "FileOperationsViewModel",
                "File renamed successfully: " + file.getName() + " to " + newName);
            state.setLoading(false);
            backgroundSmbManager.finishOperation(renameOpName, true);
            if (callback != null)
              mainHandler.post(() -> callback.onResult(true, "File renamed successfully"));

            String cachePattern =
                "conn_"
                    + state.getConnection().getId()
                    + "_path_"
                    + state.getCurrentPathString().hashCode();
            IntelligentCacheManager.getInstance().invalidateSync(cachePattern);
            IntelligentCacheManager.getInstance()
                .invalidateSearchCache(state.getConnection(), state.getCurrentPathString());

            if (file.isDirectory()) {
              LogUtils.d(
                  "FileOperationsViewModel",
                  "Renaming directory, invalidating directory cache: " + file.getPath());
              String dirCachePattern =
                  "conn_" + state.getConnection().getId() + "_path_" + file.getPath().hashCode();
              IntelligentCacheManager.getInstance().invalidateSync(dirCachePattern);
              IntelligentCacheManager.getInstance()
                  .invalidateSearchCache(state.getConnection(), file.getPath());
            }

            fileListViewModel.refreshCurrentDirectory();
          } catch (Exception e) {
            LogUtils.e("FileOperationsViewModel", "File rename failed: " + e.getMessage());
            state.setLoading(false);
            backgroundSmbManager.finishOperation(renameOpName, false);
            if (callback != null)
              mainHandler.post(
                  () -> callback.onResult(false, "File rename failed: " + e.getMessage()));
            state.setErrorMessage("Failed to rename file: " + e.getMessage());
          }
        });
  }

  private void cleanupDownloadFiles(File localFile, String operationContext) {
    if (localFile != null && localFile.exists()) {
      try {
        boolean deleted = localFile.delete();
        if (deleted) {
          LogUtils.d(
              "FileOperationsViewModel",
              "Cleaned up local file for " + operationContext + ": " + localFile.getAbsolutePath());
        } else {
          LogUtils.w(
              "FileOperationsViewModel",
              "Failed to clean up local file for "
                  + operationContext
                  + ": "
                  + localFile.getAbsolutePath());
        }
      } catch (Exception e) {
        LogUtils.w(
            "FileOperationsViewModel",
            "Error cleaning up local file for " + operationContext + ": " + e.getMessage());
      }
    }
  }

  private void handleDownloadCancellation(
      File localFile,
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

  int calculateAccuratePercentage(long currentBytes, long totalBytes) {
    if (totalBytes <= 0) return 0;
    if (currentBytes >= totalBytes) return 100;
    if (totalBytes - currentBytes <= 1024) return 100;
    return (int) Math.round((currentBytes * 100.0) / totalBytes);
  }

  // ── Phase 2: Queue-based upload integration ──────────────────────────────────

  /**
   * Enqueues a single file upload into the persistent transfer queue and starts the TransferWorker.
   * Returns immediately — no blocking dialog.
   *
   * @param sourceUri SAF URI of the local file
   * @param remotePath full remote SMB path
   * @param displayName human-readable file name for the queue UI
   * @param fileSize file size in bytes (0 if unknown)
   * @param batchId batch identifier grouping related transfers
   */
  public void enqueueUpload(
      @NonNull Uri sourceUri,
      @NonNull String remotePath,
      @NonNull String displayName,
      long fileSize,
      @NonNull String batchId) {
    executor.execute(
        () -> {
          if (state.getConnection() == null) {
            LogUtils.w("FileOperationsViewModel", "Cannot enqueue upload: no connection");
            return;
          }

          PendingTransferDao dao = TransferDatabase.getInstance(context).pendingTransferDao();

          PendingTransfer transfer = new PendingTransfer();
          transfer.transferType = "UPLOAD";
          transfer.localUri = sourceUri.toString();
          transfer.remotePath = remotePath;
          transfer.connectionId = state.getConnection().getId();
          transfer.displayName = displayName;
          transfer.fileSize = fileSize;
          transfer.bytesTransferred = 0;
          transfer.status = "PENDING";
          transfer.createdAt = System.currentTimeMillis();
          transfer.updatedAt = System.currentTimeMillis();
          transfer.batchId = batchId;

          long id = dao.insert(transfer);
          LogUtils.i(
              "FileOperationsViewModel",
              "Enqueued upload #" + id + ": " + displayName + " (" + fileSize + " bytes)");

          startTransferWorker();
        });
  }

  /**
   * Scans a folder (via SAF DocumentFile) and enqueues all contained files as a batch upload.
   * Folder structure is preserved via the remote path hierarchy; the TransferWorker creates
   * directories automatically.
   *
   * @param folderUri SAF URI of the local folder
   * @return the generated batch ID for observing progress
   */
  public @NonNull String enqueueFolderUpload(@NonNull Uri folderUri) {
    String batchId = UUID.randomUUID().toString();

    executor.execute(
        () -> {
          if (state.getConnection() == null) {
            LogUtils.w("FileOperationsViewModel", "Cannot enqueue folder: no connection");
            return;
          }

          DocumentFile folder = DocumentFile.fromTreeUri(context, folderUri);
          if (folder == null || !folder.isDirectory()) {
            LogUtils.w("FileOperationsViewModel", "Invalid folder URI: " + folderUri);
            return;
          }

          List<PendingTransfer> transfers = new ArrayList<>();
          scanFolderForQueue(folder, state.getCurrentPathString(), "", transfers, batchId, 0);

          if (transfers.isEmpty()) {
            LogUtils.i("FileOperationsViewModel", "No files found in folder for upload");
            return;
          }

          PendingTransferDao dao = TransferDatabase.getInstance(context).pendingTransferDao();
          dao.insertAll(transfers);
          LogUtils.i(
              "FileOperationsViewModel",
              "Enqueued " + transfers.size() + " files from folder (batch=" + batchId + ")");

          startTransferWorker();
        });

    return batchId;
  }

  /** Recursively scans a DocumentFile folder and builds PendingTransfer entries for each file. */
  private void scanFolderForQueue(
      DocumentFile folder,
      String remoteBasePath,
      String relativePath,
      List<PendingTransfer> transfers,
      String batchId,
      int sortOrderStart) {
    DocumentFile[] files = folder.listFiles();
    if (files == null) return;

    int sortOrder = sortOrderStart;
    for (DocumentFile file : files) {
      String fileName = file.getName();
      if (fileName == null) continue;

      String currentRelative = relativePath.isEmpty() ? fileName : relativePath + "/" + fileName;

      if (file.isDirectory()) {
        scanFolderForQueue(file, remoteBasePath, currentRelative, transfers, batchId, sortOrder);
      } else if (file.isFile()) {
        PendingTransfer t = new PendingTransfer();
        t.transferType = "UPLOAD";
        t.localUri = file.getUri().toString();
        t.remotePath = remoteBasePath + "/" + currentRelative;
        t.connectionId = state.getConnection().getId();
        t.displayName = fileName;
        t.mimeType = file.getType();
        t.fileSize = file.length();
        t.bytesTransferred = 0;
        t.status = "PENDING";
        t.createdAt = System.currentTimeMillis();
        t.updatedAt = System.currentTimeMillis();
        t.batchId = batchId;
        t.sortOrder = sortOrder++;
        transfers.add(t);
      }
    }
  }

  /**
   * Enqueues a remote SMB folder for download by inserting a single DOWNLOAD_DIRECTORY placeholder
   * into the transfer queue. The TransferWorker will resolve the directory contents using its own
   * SMB connection, avoiding dependency on the (possibly closed) UI-layer SMB session.
   *
   * @param remoteFolderPath internal SMB path of the remote folder (without share name)
   * @param destFolderUri SAF URI of the local destination folder
   * @param folderName display name of the remote folder
   * @return the generated batch ID for observing progress
   */
  public @NonNull String enqueueFolderDownload(
      @NonNull String remoteFolderPath, @NonNull Uri destFolderUri, @NonNull String folderName) {
    String batchId = UUID.randomUUID().toString();

    executor.execute(
        () -> {
          if (state.getConnection() == null) {
            LogUtils.w("FileOperationsViewModel", "Cannot enqueue folder download: no connection");
            return;
          }

          PendingTransfer t = new PendingTransfer();
          t.transferType = "DOWNLOAD_DIRECTORY";
          t.localUri = destFolderUri.toString();
          t.remotePath = remoteFolderPath;
          t.connectionId = state.getConnection().getId();
          t.displayName = folderName;
          t.mimeType = "";
          t.fileSize = 0;
          t.bytesTransferred = 0;
          t.status = "PENDING";
          t.createdAt = System.currentTimeMillis();
          t.updatedAt = System.currentTimeMillis();
          t.batchId = batchId;
          t.sortOrder = 0;

          PendingTransferDao dao = TransferDatabase.getInstance(context).pendingTransferDao();
          dao.insertAll(java.util.Collections.singletonList(t));
          LogUtils.i(
              "FileOperationsViewModel",
              "Enqueued folder download as DOWNLOAD_DIRECTORY: "
                  + folderName
                  + " (batch="
                  + batchId
                  + ")");

          startTransferWorker();
        });

    return batchId;
  }

  /**
   * Enqueues multiple files for download into the persistent transfer queue. Creates local SAF
   * documents for each file in the destination folder. Returns immediately — no blocking dialog.
   *
   * @param files list of remote SMB files to download
   * @param destFolderUri SAF URI of the local destination folder
   * @return the generated batch ID for observing progress
   */
  public @NonNull String enqueueMultiFileDownload(
      @NonNull List<SmbFileItem> files, @NonNull Uri destFolderUri) {
    String batchId = UUID.randomUUID().toString();

    executor.execute(
        () -> {
          if (state.getConnection() == null) {
            LogUtils.w("FileOperationsViewModel", "Cannot enqueue multi-download: no connection");
            return;
          }

          DocumentFile destDir = DocumentFile.fromTreeUri(context, destFolderUri);
          if (destDir == null || !destDir.isDirectory()) {
            LogUtils.w("FileOperationsViewModel", "Invalid destination folder: " + destFolderUri);
            return;
          }

          PendingTransferDao dao = TransferDatabase.getInstance(context).pendingTransferDao();
          List<PendingTransfer> transfers = new ArrayList<>();
          int sortOrder = 0;

          for (SmbFileItem file : files) {
            if (file == null || file.getName() == null) continue;

            if (file.isDirectory()) {
              // Enqueue directories as DOWNLOAD_DIRECTORY — the TransferWorker will
              // resolve their contents using its own SMB connection.  This avoids
              // depending on the current (possibly closed) SMB session.
              PendingTransfer t = new PendingTransfer();
              t.transferType = "DOWNLOAD_DIRECTORY";
              t.localUri = destFolderUri.toString();
              t.remotePath = file.getPath();
              t.connectionId = state.getConnection().getId();
              t.displayName = file.getName();
              t.mimeType = "";
              t.fileSize = 0;
              t.bytesTransferred = 0;
              t.status = "PENDING";
              t.createdAt = System.currentTimeMillis();
              t.updatedAt = System.currentTimeMillis();
              t.batchId = batchId;
              t.sortOrder = sortOrder++;
              transfers.add(t);
            } else if (file.isFile()) {
              String mimeType = "application/octet-stream";
              DocumentFile localFile = destDir.createFile(mimeType, file.getName());
              if (localFile == null) {
                LogUtils.w(
                    "FileOperationsViewModel", "Cannot create local file: " + file.getName());
                continue;
              }

              PendingTransfer t = new PendingTransfer();
              t.transferType = "DOWNLOAD";
              t.localUri = localFile.getUri().toString();
              t.remotePath = file.getPath();
              t.connectionId = state.getConnection().getId();
              t.displayName = file.getName();
              t.mimeType = mimeType;
              t.fileSize = file.getSize();
              t.bytesTransferred = 0;
              t.status = "PENDING";
              t.createdAt = System.currentTimeMillis();
              t.updatedAt = System.currentTimeMillis();
              t.batchId = batchId;
              t.sortOrder = sortOrder++;
              transfers.add(t);
            }
          }

          if (transfers.isEmpty()) {
            LogUtils.i("FileOperationsViewModel", "No files to enqueue for multi-download");
            return;
          }

          dao.insertAll(transfers);
          LogUtils.i(
              "FileOperationsViewModel",
              "Enqueued " + transfers.size() + " files for multi-download (batch=" + batchId + ")");

          startTransferWorker();
        });

    return batchId;
  }

  /**
   * Enqueues a single file download into the persistent transfer queue and starts the
   * TransferWorker. Returns immediately — no blocking dialog.
   *
   * <p>The caller must create the target SAF document before calling this method (e.g. via {@code
   * DocumentsContract.createDocument()}) and pass the resulting URI as {@code targetUri}.
   *
   * @param targetUri SAF URI of the local target file (already created)
   * @param remotePath internal SMB path (without share name)
   * @param displayName human-readable file name for the queue UI
   * @param fileSize file size in bytes (0 if unknown)
   * @param batchId batch identifier grouping related transfers
   */
  public void enqueueDownload(
      @NonNull Uri targetUri,
      @NonNull String remotePath,
      @NonNull String displayName,
      long fileSize,
      @NonNull String batchId) {
    executor.execute(
        () -> {
          if (state.getConnection() == null) {
            LogUtils.w("FileOperationsViewModel", "Cannot enqueue download: no connection");
            return;
          }

          PendingTransferDao dao = TransferDatabase.getInstance(context).pendingTransferDao();

          PendingTransfer transfer = new PendingTransfer();
          transfer.transferType = "DOWNLOAD";
          transfer.localUri = targetUri.toString();
          transfer.remotePath = remotePath;
          transfer.connectionId = state.getConnection().getId();
          transfer.displayName = displayName;
          transfer.fileSize = fileSize;
          transfer.bytesTransferred = 0;
          transfer.status = "PENDING";
          transfer.createdAt = System.currentTimeMillis();
          transfer.updatedAt = System.currentTimeMillis();
          transfer.batchId = batchId;

          long id = dao.insert(transfer);
          LogUtils.i(
              "FileOperationsViewModel",
              "Enqueued download #" + id + ": " + displayName + " (" + fileSize + " bytes)");

          startTransferWorker();
        });
  }

  /**
   * Starts the TransferWorker via WorkManager. Uses KEEP policy so that a running worker is NOT
   * cancelled when new transfers are enqueued. The worker loops internally to pick up newly added
   * transfers from the DB.
   */
  void startTransferWorker() {
    OneTimeWorkRequest request =
        new OneTimeWorkRequest.Builder(TransferWorker.class)
            .setConstraints(
                new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build();

    WorkManager.getInstance(context)
        .enqueueUniqueWork("transfer_queue", ExistingWorkPolicy.KEEP, request);
    LogUtils.d("FileOperationsViewModel", "TransferWorker enqueued (KEEP policy)");
  }

  @Override
  protected void onCleared() {
    super.onCleared();
    cleared = true;
    executor.shutdownNow();
  }

  /** Returns true if this ViewModel has been cleared and should no longer be used. */
  boolean isCleared() {
    return cleared;
  }

  private static final long PROGRESS_THROTTLE_MS = 100; // ~10 Updates/Sek.
  private static final int PROGRESS_MIN_DELTA = 1; // mind. +1% für sofortige Ausgabe

  private static int clampDownloadPct(int pct) {
    if (pct < 0) return 0;
    if (pct > 99) return 99; // niemals 100% vor onResult()
    return pct;
  }

  int ensureMonotonicDownloadPct(int pct, int last) {
    pct = clampDownloadPct(pct);
    return Math.max(last, pct);
  }

  /** Zeitbasierter Gate für Progress-Events. */
  private static final class ProgressThrottler {
    private final long minIntervalNs;
    private long lastEmitNs = 0;
    private int lastPct = -1;

    ProgressThrottler(long minMillis) {
      this.minIntervalNs = TimeUnit.MILLISECONDS.toNanos(minMillis);
    }

    /** true → Event senden; false → unterdrücken */
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
  }
}
