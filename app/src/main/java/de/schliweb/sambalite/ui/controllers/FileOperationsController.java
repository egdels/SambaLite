/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui.controllers;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.transfer.db.PendingTransferDao;
import de.schliweb.sambalite.transfer.db.TransferDatabase;
import de.schliweb.sambalite.ui.FileListViewModel;
import de.schliweb.sambalite.ui.operations.FileOperationsViewModel;
import de.schliweb.sambalite.util.LogUtils;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import lombok.Setter;

/**
 * The FileOperationsController class is responsible for managing and executing various file
 * operations such as download, upload, delete, and rename. It integrates with different components
 * of the application like ViewModels, listeners, and user feedback mechanisms to maintain
 * operability and user interactivity.
 */
public class FileOperationsController {

  // Operation type constants
  private static final String OPERATION_DOWNLOAD = "download";
  private static final String OPERATION_UPLOAD = "upload";
  private static final String OPERATION_FOLDER_UPLOAD = "folderContentsUpload";
  private static final String OPERATION_DELETE = "delete";
  private static final String OPERATION_RENAME = "rename";

  final Context context;
  final FileOperationsViewModel operationsViewModel;
  final FileListViewModel fileListViewModel;
  final FileBrowserUIState uiState;

  final BackgroundSmbManager backgroundSmbManager;

  final List<FileOperationListener> listeners = new ArrayList<>();

  @Setter private UserFeedbackProvider userFeedbackProvider; // preferred UX surface

  @Setter ProgressCallback progressCallback; // legacy feedback path
  @Setter ActivityResultController activityResultController;
  @Setter DialogController dialogController;

  public FileOperationsController(
      @NonNull Context context,
      @NonNull FileOperationsViewModel operationsViewModel,
      @NonNull FileListViewModel fileListViewModel,
      @NonNull FileBrowserUIState uiState,
      @NonNull BackgroundSmbManager backgroundSmbManager) {
    this.context = context;
    this.operationsViewModel = operationsViewModel;
    this.fileListViewModel = fileListViewModel;
    this.uiState = uiState;
    this.backgroundSmbManager = backgroundSmbManager;
    this.dialogController = null;
  }

  // Listener wiring
  public void addListener(@Nullable FileOperationListener listener) {
    if (listener != null && !listeners.contains(listener)) listeners.add(listener);
  }

  public void removeListener(@Nullable FileOperationListener listener) {
    listeners.remove(listener);
  }

  void notifyOperationStarted(String operationType, SmbFileItem file) {
    for (FileOperationListener l : listeners) l.onFileOperationStarted(operationType, file);
  }

  void notifyOperationCompleted(
      String operationType, SmbFileItem file, boolean success, String message) {
    for (FileOperationListener l : listeners)
      l.onFileOperationCompleted(operationType, file, success, message);
  }

  void notifyOperationProgress(
      String operationType, SmbFileItem file, int progress, String message) {
    for (FileOperationListener l : listeners)
      l.onFileOperationProgress(operationType, file, progress, message);
  }

  // ---- Unified progress helpers ----
  void updateOperationProgress(
      String operationType, SmbFileItem file, String itemName, int percentage, String status) {
    notifyOperationProgress(operationType, file, percentage, status);
    LogUtils.d(
        "FileOperationsController",
        "Progress: " + operationType + " " + percentage + "% - " + status);
  }

  void updateFinalizingProgress(
      String operationType, SmbFileItem file, String itemName, boolean isFolder) {
    String op =
        (operationType.equals(OPERATION_UPLOAD) || operationType.equals(OPERATION_FOLDER_UPLOAD))
            ? context.getString(de.schliweb.sambalite.R.string.uploading)
            : context.getString(de.schliweb.sambalite.R.string.downloading);
    String itemType =
        isFolder
            ? context.getString(de.schliweb.sambalite.R.string.folders_tab)
            : context.getString(de.schliweb.sambalite.R.string.files_tab);
    String msg =
        context.getString(de.schliweb.sambalite.R.string.finalizing_operation, itemType, op);
    updateOperationProgress(operationType, file, itemName, 99, msg);
  }

  String titleFor(String operationType, String displayName) {
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
  public @NonNull FileOperationRequester getFileOperationRequester() {
    return new FileOperationRequesterImpl();
  }

  /**
   * Öffnet sofort den Fortschrittsdialog und hängt die Cancel-Action an, damit der Benutzer
   * unmittelbar Feedback erhält, noch bevor der Hintergrunddienst gebunden ist.
   */
  void showProgressShell(String operationType, String operationName, Runnable onCancel) {
    if (progressCallback instanceof ProgressController pc) {
      try {
        // Show the progress dialog immediately so the user sees feedback right away,
        // even before the background service is bound and the operation callback fires.
        String title;
        if (OPERATION_DOWNLOAD.equals(operationType)) {
          title = context.getString(de.schliweb.sambalite.R.string.downloading);
        } else if (OPERATION_UPLOAD.equals(operationType)
            || OPERATION_FOLDER_UPLOAD.equals(operationType)) {
          title = context.getString(de.schliweb.sambalite.R.string.uploading);
        } else {
          title = context.getString(de.schliweb.sambalite.R.string.transfer_title);
        }
        String message = context.getString(de.schliweb.sambalite.R.string.preparing_transfer);
        pc.showDetailedProgressDialog(title, message);
        pc.setDetailedProgressDialogCancelAction(onCancel);
      } catch (Throwable ignored) {
        /* optional */
      }
    }
  }

  // ---- Batch summary helper ----
  String summarizeBatch(String base, boolean cancelled, int failedCount) {
    String summary = base != null ? base : "";
    if (cancelled) {
      summary += "; " + context.getString(de.schliweb.sambalite.R.string.cancelled_suffix);
    }
    if (failedCount > 0) {
      summary +=
          "; "
              + context
                  .getResources()
                  .getQuantityString(
                      de.schliweb.sambalite.R.plurals.failed_count_suffix,
                      failedCount,
                      failedCount);
    }
    return summary;
  }

  // ---- File operations ----
  public void handleFileDownload(@NonNull Uri uri) {
    if (uiState.getSelectedFile() == null) return;
    SmbFileItem file = uiState.getSelectedFile();
    String displayName = file.getName();
    String remotePath = file.getPath();
    long fileSize = file.getSize();

    // Enqueue into persistent transfer queue — no blocking dialog
    operationsViewModel.enqueueDownload(
        uri, remotePath, displayName, fileSize, java.util.UUID.randomUUID().toString());

    if (progressCallback != null) {
      progressCallback.showSuccess(
          context.getString(de.schliweb.sambalite.R.string.transfer_added_to_queue));
    }
    LogUtils.i("FileOperationsController", "Enqueued single file download: " + displayName);
  }

  public void handleFolderDownload(@NonNull Uri uri) {
    if (uiState.getSelectedFile() == null || !uiState.getSelectedFile().isDirectory()) return;

    // Best-effort: persist read/write permission to the chosen destination folder
    try {
      final int flags =
          android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
              | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
      context.getContentResolver().takePersistableUriPermission(uri, flags);
    } catch (Exception e) {
      de.schliweb.sambalite.util.LogUtils.w(
          "FileOperationsController", "Persistable URI permission failed: " + e.getMessage());
    }

    // Remember the chosen folder so the picker opens here next time
    de.schliweb.sambalite.ui.utils.PreferenceUtils.setLastDownloadFolderUri(context, uri);

    SmbFileItem folder = uiState.getSelectedFile();
    String folderName = folder.getName();
    String remotePath = folder.getPath();

    // Enqueue into persistent transfer queue — no blocking dialog
    operationsViewModel.enqueueFolderDownload(remotePath, uri, folderName);

    if (progressCallback != null) {
      progressCallback.showSuccess(
          context.getString(de.schliweb.sambalite.R.string.transfer_added_to_queue));
    }
    LogUtils.i("FileOperationsController", "Enqueued folder download: " + folderName);
  }

  public void handleFileUpload(@NonNull Uri uri) {
    trySelfGrantRead(uri);
    final String fileNameFromUri = getFileNameFromUri(uri);
    final String fileName =
        fileNameFromUri != null ? fileNameFromUri : "uploaded_file_" + System.currentTimeMillis();
    String remotePath = buildRemotePath(fileName);
    long fileSize = getFileSizeFromUri(uri);
    String batchId = java.util.UUID.randomUUID().toString();

    // Check for duplicate in queue and file existence on server before enqueuing
    new Thread(
            () -> {
              PendingTransferDao dao = TransferDatabase.getInstance(context).pendingTransferDao();
              boolean alreadyQueued = dao.countActiveForPath(remotePath) > 0;
              boolean existsOnServer = operationsViewModel.checkFileExists(remotePath);

              new android.os.Handler(android.os.Looper.getMainLooper())
                  .post(
                      () -> {
                        if (alreadyQueued) {
                          // Show duplicate confirmation dialog
                          showDuplicateQueueDialog(
                              fileName,
                              () -> {
                                if (existsOnServer) {
                                  showOverwriteDialog(
                                      fileName,
                                      () ->
                                          enqueueAndNotify(
                                              uri,
                                              remotePath,
                                              fileName,
                                              fileSize,
                                              batchId,
                                              "overwrite+duplicate"));
                                } else {
                                  enqueueAndNotify(
                                      uri,
                                      remotePath,
                                      fileName,
                                      fileSize,
                                      batchId,
                                      "duplicate-confirmed");
                                }
                              });
                        } else if (existsOnServer) {
                          showOverwriteDialog(
                              fileName,
                              () ->
                                  enqueueAndNotify(
                                      uri, remotePath, fileName, fileSize, batchId, "overwrite"));
                        } else {
                          enqueueAndNotify(uri, remotePath, fileName, fileSize, batchId, null);
                        }
                      });
            })
        .start();
  }

  /** Enqueues an upload and shows a success notification. */
  private void enqueueAndNotify(
      Uri uri,
      String remotePath,
      String fileName,
      long fileSize,
      String batchId,
      @Nullable String reason) {
    operationsViewModel.enqueueUpload(uri, remotePath, fileName, fileSize, batchId);
    showSuccess(context.getString(de.schliweb.sambalite.R.string.transfer_added_to_queue));
    String suffix = reason != null ? " (" + reason + ")" : "";
    LogUtils.i(
        "FileOperationsController", "Enqueued single file upload" + suffix + ": " + fileName);
  }

  /** Shows a dialog asking the user to confirm re-uploading a file already in the queue. */
  private void showDuplicateQueueDialog(String fileName, Runnable confirmAction) {
    if (progressCallback == null) return;
    new com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setTitle(de.schliweb.sambalite.R.string.transfer_already_queued_title)
        .setMessage(
            context.getString(
                de.schliweb.sambalite.R.string.transfer_already_queued_message, fileName))
        .setPositiveButton(
            de.schliweb.sambalite.R.string.transfer_already_queued_confirm,
            (dialog, which) -> confirmAction.run())
        .setNegativeButton(
            de.schliweb.sambalite.R.string.cancel,
            (dialog, which) ->
                LogUtils.i(
                    "FileOperationsController",
                    "Upload cancelled by user (already queued): " + fileName))
        .setCancelable(false)
        .show();
  }

  /** Shows the file-exists overwrite confirmation dialog. */
  private void showOverwriteDialog(String fileName, Runnable confirmAction) {
    if (progressCallback != null) {
      progressCallback.showFileExistsDialog(
          fileName,
          confirmAction,
          () ->
              LogUtils.i(
                  "FileOperationsController",
                  "Upload cancelled by user (file exists): " + fileName));
    }
  }

  public void handleFolderContentsUpload(@NonNull Uri folderUri) {
    DocumentFile docFolder = DocumentFile.fromTreeUri(context, folderUri);
    String folderName = getDocumentFileName(docFolder, "folder");

    // Scan folder and check for existing files on a background thread
    new Thread(
            () -> {
              if (docFolder == null || !docFolder.isDirectory()) {
                LogUtils.w("FileOperationsController", "Invalid folder URI: " + folderUri);
                return;
              }

              // Collect all files from the folder recursively
              String basePath = fileListViewModel.getCurrentPathInternal();
              if (basePath == null) basePath = "";
              java.util.List<FileToUpload> allFiles = new java.util.ArrayList<>();
              scanFolderFiles(docFolder, basePath, "", allFiles);

              if (allFiles.isEmpty()) {
                new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(
                        () ->
                            showSuccess(
                                context.getString(
                                    de.schliweb.sambalite.R.string.transfer_added_to_queue)));
                return;
              }

              // Check which files already exist on the server or are already queued
              PendingTransferDao dao = TransferDatabase.getInstance(context).pendingTransferDao();
              java.util.List<FileToUpload> existingFiles = new java.util.ArrayList<>();
              java.util.List<FileToUpload> queuedFiles = new java.util.ArrayList<>();
              for (FileToUpload f : allFiles) {
                if (dao.countActiveForPath(f.remotePath) > 0) {
                  queuedFiles.add(f);
                }
                if (operationsViewModel.checkFileExists(f.remotePath)) {
                  existingFiles.add(f);
                }
              }

              new android.os.Handler(android.os.Looper.getMainLooper())
                  .post(
                      () -> {
                        // Determine which conflicts to show
                        boolean hasQueued = !queuedFiles.isEmpty();
                        boolean hasExisting = !existingFiles.isEmpty();

                        java.util.function.Consumer<java.util.Set<String>> doEnqueueFiltered =
                            (excludedNames) -> {
                              String batchId = java.util.UUID.randomUUID().toString();
                              int count = 0;
                              for (FileToUpload f : allFiles) {
                                if (excludedNames != null
                                    && excludedNames.contains(f.displayName)) {
                                  continue;
                                }
                                operationsViewModel.enqueueUpload(
                                    f.uri, f.remotePath, f.displayName, f.fileSize, batchId);
                                count++;
                              }
                              if (count > 0) {
                                showSuccess(
                                    context.getString(
                                        de.schliweb.sambalite.R.string.transfer_added_to_queue));
                              }
                              LogUtils.i(
                                  "FileOperationsController",
                                  "Enqueued folder upload: "
                                      + folderName
                                      + " (batch="
                                      + batchId
                                      + ")");
                              LogUtils.i(
                                  "FileOperationsController",
                                  "Folder upload: user selected "
                                      + count
                                      + " of "
                                      + allFiles.size()
                                      + " existing files to overwrite");
                            };

                        Runnable doEnqueue = () -> doEnqueueFiltered.accept(null);

                        if (hasQueued) {
                          String queuedNames = buildConflictNames(queuedFiles);
                          showDuplicateQueueDialog(
                              queuedNames,
                              () -> {
                                if (hasExisting) {
                                  showMultiFileExistsDialog(existingFiles, doEnqueueFiltered);
                                } else {
                                  doEnqueue.run();
                                }
                              });
                        } else if (hasExisting) {
                          showMultiFileExistsDialog(existingFiles, doEnqueueFiltered);
                        } else {
                          doEnqueue.run();
                        }
                      });
            })
        .start();
  }

  /**
   * Shows the multi-file exists dialog for batch uploads, allowing the user to deselect individual
   * files. Unselected existing files are excluded from the upload.
   */
  private void showMultiFileExistsDialog(
      java.util.List<FileToUpload> existingFiles,
      java.util.function.Consumer<java.util.Set<String>> doEnqueueFiltered) {
    if (existingFiles.size() == 1) {
      showOverwriteDialog(existingFiles.get(0).displayName, () -> doEnqueueFiltered.accept(null));
    } else {
      java.util.List<String> names = new java.util.ArrayList<>();
      for (FileToUpload f : existingFiles) {
        names.add(f.displayName);
      }
      de.schliweb.sambalite.ui.dialogs.DialogHelper.showMultiFileExistsDialog(
          context,
          names,
          selectedNames -> {
            // Build set of existing file names that were NOT selected (to exclude)
            java.util.Set<String> selectedSet = new java.util.HashSet<>(selectedNames);
            java.util.Set<String> excludedNames = new java.util.HashSet<>();
            for (FileToUpload f : existingFiles) {
              if (!selectedSet.contains(f.displayName)) {
                excludedNames.add(f.displayName);
              }
            }
            doEnqueueFiltered.accept(excludedNames);
            LogUtils.i(
                "FileOperationsController",
                "Multi upload: user selected "
                    + selectedNames.size()
                    + " of "
                    + existingFiles.size()
                    + " existing files to overwrite, excluded "
                    + excludedNames.size());
          },
          () -> {
            // Skip all existing files, but still upload non-existing ones
            java.util.Set<String> allExistingNames = new java.util.HashSet<>();
            for (FileToUpload f : existingFiles) {
              allExistingNames.add(f.displayName);
            }
            doEnqueueFiltered.accept(allExistingNames);
            LogUtils.i(
                "FileOperationsController",
                "Multi upload: user skipped all " + existingFiles.size() + " existing files");
          });
    }
  }

  /** Builds a display string of conflicting file names (max 3 + count). */
  private static String buildConflictNames(java.util.List<FileToUpload> files) {
    StringBuilder names = new StringBuilder();
    for (int i = 0; i < files.size(); i++) {
      if (i > 0) names.append(", ");
      if (i >= 3) {
        names.append("… (+").append(files.size() - 3).append(")");
        break;
      }
      names.append(files.get(i).displayName);
    }
    return files.size() == 1 ? files.get(0).displayName : names.toString();
  }

  /** Helper class to hold file info during folder scan for existence check. */
  private static class FileToUpload {
    final Uri uri;
    final String remotePath;
    final String displayName;
    final long fileSize;

    FileToUpload(Uri uri, String remotePath, String displayName, long fileSize) {
      this.uri = uri;
      this.remotePath = remotePath;
      this.displayName = displayName;
      this.fileSize = fileSize;
    }
  }

  /** Recursively scans a DocumentFile folder and collects file info for existence checking. */
  private void scanFolderFiles(
      DocumentFile folder,
      String remoteBasePath,
      String relativePath,
      java.util.List<FileToUpload> result) {
    DocumentFile[] files = folder.listFiles();
    if (files == null) return;

    for (DocumentFile file : files) {
      String fileName = file.getName();
      if (fileName == null) continue;

      String currentRelative = relativePath.isEmpty() ? fileName : relativePath + "/" + fileName;

      if (file.isDirectory()) {
        scanFolderFiles(file, remoteBasePath, currentRelative, result);
      } else if (file.isFile()) {
        String remotePath =
            remoteBasePath.isEmpty() ? currentRelative : remoteBasePath + "/" + currentRelative;
        result.add(new FileToUpload(file.getUri(), remotePath, fileName, file.length()));
      }
    }
  }

  /** Batch upload multiple URIs. Files are enqueued into the persistent transfer queue. */
  public void handleMultipleFileUploads(@NonNull java.util.List<Uri> uris) {
    handleMultipleFileUploads(uris, null);
  }

  /**
   * Batch upload multiple URIs to a specific target directory. If {@code targetDirectoryPath} is
   * non-null it overrides the ViewModel's current path for building remote paths. Checks for
   * existing files on the server and duplicates in the queue before enqueuing.
   */
  public void handleMultipleFileUploads(
      @NonNull java.util.List<Uri> uris, @Nullable String targetDirectoryPath) {
    if (uris == null || uris.isEmpty()) return;

    // Collect file info on the calling thread (URIs are lightweight)
    java.util.List<FileToUpload> filesToUpload = new java.util.ArrayList<>();
    for (Uri uri : uris) {
      trySelfGrantRead(uri);
      final String fileNameFromUri = getFileNameFromUri(uri);
      final String fileName =
          fileNameFromUri != null ? fileNameFromUri : "uploaded_file_" + System.currentTimeMillis();
      String remotePath =
          targetDirectoryPath != null && !targetDirectoryPath.isEmpty()
              ? targetDirectoryPath + "/" + fileName
              : buildRemotePath(fileName);
      long fileSize = getFileSizeFromUri(uri);
      filesToUpload.add(new FileToUpload(uri, remotePath, fileName, fileSize));
    }

    // Check for conflicts on a background thread
    new Thread(
            () -> {
              PendingTransferDao dao = TransferDatabase.getInstance(context).pendingTransferDao();
              java.util.List<FileToUpload> existingFiles = new java.util.ArrayList<>();
              java.util.List<FileToUpload> queuedFiles = new java.util.ArrayList<>();

              for (FileToUpload f : filesToUpload) {
                if (dao.countActiveForPath(f.remotePath) > 0) {
                  queuedFiles.add(f);
                }
                if (operationsViewModel.checkFileExists(f.remotePath)) {
                  existingFiles.add(f);
                }
              }

              new android.os.Handler(android.os.Looper.getMainLooper())
                  .post(
                      () -> {
                        boolean hasQueued = !queuedFiles.isEmpty();
                        boolean hasExisting = !existingFiles.isEmpty();

                        java.util.function.Consumer<java.util.Set<String>> doEnqueueFiltered =
                            (excludedNames) -> {
                              String batchId = java.util.UUID.randomUUID().toString();
                              int count = 0;
                              for (FileToUpload f : filesToUpload) {
                                if (excludedNames != null
                                    && excludedNames.contains(f.displayName)) {
                                  continue;
                                }
                                operationsViewModel.enqueueUpload(
                                    f.uri, f.remotePath, f.displayName, f.fileSize, batchId);
                                count++;
                              }
                              if (count > 0) {
                                showSuccess(
                                    context.getString(
                                        de.schliweb.sambalite.R.string.transfer_added_to_queue));
                              }
                              LogUtils.i(
                                  "FileOperationsController",
                                  "Enqueued "
                                      + count
                                      + " files for upload (batch="
                                      + batchId
                                      + ")");
                            };

                        Runnable doEnqueue = () -> doEnqueueFiltered.accept(null);

                        if (hasQueued) {
                          String queuedNames = buildConflictNames(queuedFiles);
                          showDuplicateQueueDialog(
                              queuedNames,
                              () -> {
                                if (hasExisting) {
                                  showMultiFileExistsDialog(existingFiles, doEnqueueFiltered);
                                } else {
                                  doEnqueue.run();
                                }
                              });
                        } else if (hasExisting) {
                          showMultiFileExistsDialog(existingFiles, doEnqueueFiltered);
                        } else {
                          doEnqueue.run();
                        }
                      });
            })
        .start();
  }

  /**
   * Deletes the source file if it resides in the shared_text cache directory (i.e. it was created
   * by ShareReceiverActivity for a text share).
   */
  void cleanupSharedTextSourceFile(Uri uri) {
    if (uri == null || !"file".equals(uri.getScheme())) return;
    try {
      File sourceFile = new File(uri.getPath());
      File sharedTextDir = new File(context.getCacheDir(), "shared_text");
      if (sourceFile.exists()
          && sourceFile.getParentFile() != null
          && sourceFile.getParentFile().equals(sharedTextDir)) {
        if (sourceFile.delete()) {
          LogUtils.d(
              "FileOperationsController",
              "Deleted shared text cache file: " + sourceFile.getName());
        }
      }
    } catch (Exception e) {
      LogUtils.w(
          "FileOperationsController",
          "Failed to clean up shared text source file: " + e.getMessage());
    }
  }

  // --- Multi-select: Batch Delete implementation ---
  public void handleMultipleFileDelete(@NonNull java.util.List<SmbFileItem> files) {
    if (files == null || files.isEmpty()) {
      if (progressCallback != null)
        progressCallback.showInfo(
            context.getString(de.schliweb.sambalite.R.string.multi_no_files_selected));
      return;
    }
    // Filter files and directories with valid paths
    java.util.ArrayList<SmbFileItem> toProcess = new java.util.ArrayList<>();
    for (SmbFileItem f : files) {
      if (f != null && (f.isFile() || f.isDirectory()) && f.getPath() != null) toProcess.add(f);
    }
    if (toProcess.isEmpty()) {
      if (progressCallback != null)
        progressCallback.showInfo(
            context.getString(de.schliweb.sambalite.R.string.multi_no_files_selected));
      return;
    }
    final int total = toProcess.size();
    final String title = context.getString(de.schliweb.sambalite.R.string.delete);
    String confirmMsg =
        context
            .getResources()
            .getQuantityString(
                de.schliweb.sambalite.R.plurals.confirm_delete_multiple, total, total);

    // Add folder warning if directories are selected
    boolean hasFolders = false;
    for (SmbFileItem f : toProcess) {
      if (f.isDirectory()) {
        hasFolders = true;
        break;
      }
    }
    if (hasFolders) {
      confirmMsg +=
          "\n\n"
              + context.getString(
                  de.schliweb.sambalite.R.string.multi_delete_includes_folders_warning);
    }

    de.schliweb.sambalite.ui.dialogs.DialogHelper.showConfirmationDialog(
        context,
        context.getString(de.schliweb.sambalite.R.string.delete_multiple_title),
        confirmMsg,
        (dialog, which) -> {
          final String opTitle = title + " (" + total + ")";
          final java.util.concurrent.atomic.AtomicBoolean cancel =
              new java.util.concurrent.atomic.AtomicBoolean(false);

          // Notify listeners that the operation is starting (to allow immediate UI reset of
          // selection)
          try {
            notifyOperationStarted(OPERATION_DELETE, null);
          } catch (Throwable ignored) {
          }

          // Show a cancelable progress dialog specifically for batch delete
          if (progressCallback != null) {
            progressCallback.showDetailedProgressDialog(
                opTitle, context.getString(de.schliweb.sambalite.R.string.preparing_ellipsis));
            if (progressCallback instanceof ProgressController pc) {
              pc.setDetailedProgressDialogCancelAction(() -> cancel.set(true));
            }
          }

          var unused =
              backgroundSmbManager.executeMultiFileOperation(
                  "batchDelete:" + System.currentTimeMillis(),
                  opTitle,
                  cb -> {
                    // Collect all paths for batch deletion in a single SMB session
                    java.util.ArrayList<String> paths = new java.util.ArrayList<>();
                    java.util.LinkedHashMap<String, String> pathToName =
                        new java.util.LinkedHashMap<>();
                    for (SmbFileItem f : toProcess) {
                      paths.add(f.getPath());
                      pathToName.put(f.getPath(), f.getName());
                    }

                    // Update progress before starting
                    cb.updateFileProgress(0, total, toProcess.get(0).getName());
                    if (progressCallback != null) {
                      progressCallback.updateDetailedProgress(
                          0,
                          context.getString(de.schliweb.sambalite.R.string.deleting_ellipsis),
                          toProcess.get(0).getName());
                    }

                    // Use batch delete: single SMB session for all files
                    java.util.List<String> failedPaths;
                    try {
                      failedPaths = operationsViewModel.deleteFilesBatch(paths);
                    } catch (Exception e) {
                      LogUtils.e(
                          "FileOperationsController", "Batch delete failed: " + e.getMessage());
                      failedPaths = paths; // all failed
                    }

                    int successCount = total - failedPaths.size();
                    java.util.ArrayList<String> failed = new java.util.ArrayList<>();
                    for (String fp : failedPaths) {
                      String name = pathToName.getOrDefault(fp, fp);
                      failed.add(name);
                    }

                    // Update final progress
                    cb.updateFileProgress(total, total, "");
                    if (progressCallback != null) {
                      progressCallback.updateDetailedProgress(
                          100,
                          context.getString(de.schliweb.sambalite.R.string.deleting_ellipsis),
                          "");
                    }

                    String base =
                        context
                            .getResources()
                            .getQuantityString(
                                de.schliweb.sambalite.R.plurals.multi_delete_summary_success,
                                successCount,
                                successCount,
                                total);
                    String summary = summarizeBatch(base, cancel.get(), failed.size());
                    if (progressCallback != null) {
                      progressCallback.showInfo(summary);
                      progressCallback.hideDetailedProgressDialog();
                    }
                    // Refresh file list once after all deletions (cache already
                    // invalidated by deleteFilesBatch)
                    fileListViewModel.refreshCurrentDirectory();
                    // Notify listeners that the batch delete operation has completed (for selection
                    // reset)
                    try {
                      boolean opSuccess = failed.isEmpty() && !cancel.get();
                      notifyOperationCompleted(OPERATION_DELETE, null, opSuccess, summary);
                    } catch (Throwable ignored) {
                    }
                    return Boolean.TRUE;
                  });
        });
  }

  public void handleMultipleFileDownloads(@NonNull java.util.List<SmbFileItem> files) {
    if (files == null || files.isEmpty()) {
      if (progressCallback != null)
        progressCallback.showInfo(
            context.getString(de.schliweb.sambalite.R.string.multi_no_files_selected));
      return;
    }
    // Filter files and directories with valid paths
    java.util.ArrayList<SmbFileItem> toProcess = new java.util.ArrayList<>();
    for (SmbFileItem f : files) {
      if (f != null && (f.isFile() || f.isDirectory()) && f.getPath() != null) toProcess.add(f);
    }
    if (toProcess.isEmpty()) {
      if (progressCallback != null)
        progressCallback.showInfo(
            context.getString(de.schliweb.sambalite.R.string.multi_no_files_selected));
      return;
    }
    // Store pending list in shared UI state and request a target folder
    uiState.setPendingMultiDownloadItems(toProcess);
    uiState.setMultiDownloadPending(true);
    if (activityResultController != null) {
      activityResultController.selectFolderForDownloadTarget();
    } else {
      LogUtils.e(
          "FileOperationsController",
          "ActivityResultController is null; cannot choose destination folder");
      if (progressCallback != null)
        progressCallback.showError(
            context.getString(de.schliweb.sambalite.R.string.download_error),
            context.getString(
                de.schliweb.sambalite.R.string.download_error_cannot_open_folder_picker));
    }
  }

  /**
   * Continues a previously initiated multi-file download after the user picked a destination
   * folder. Enqueues all pending files into the persistent transfer queue.
   */
  public void handleMultipleFileDownloadsWithTargetUri(@NonNull Uri folderUri) {
    LogUtils.d(
        "FileOperationsController",
        "handleMultipleFileDownloadsWithTargetUri: folderUri=" + folderUri);
    java.util.List<SmbFileItem> pending = uiState.getPendingMultiDownloadItems();
    uiState.setMultiDownloadPending(false);
    uiState.setPendingMultiDownloadItems(null);
    if (pending == null || pending.isEmpty()) {
      if (progressCallback != null)
        progressCallback.showInfo(
            context.getString(de.schliweb.sambalite.R.string.multi_no_files_selected));
      return;
    }

    // Best-effort: persist read/write permission to the chosen destination folder
    try {
      final int flags =
          android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
              | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
      context.getContentResolver().takePersistableUriPermission(folderUri, flags);
    } catch (Exception e) {
      de.schliweb.sambalite.util.LogUtils.w(
          "FileOperationsController", "Persistable URI permission failed: " + e.getMessage());
    }

    // Remember the chosen folder so the picker opens here next time
    de.schliweb.sambalite.ui.utils.PreferenceUtils.setLastDownloadFolderUri(context, folderUri);

    // Enqueue into persistent transfer queue — no blocking dialog
    operationsViewModel.enqueueMultiFileDownload(pending, folderUri);

    if (progressCallback != null) {
      progressCallback.showSuccess(
          context.getString(de.schliweb.sambalite.R.string.transfer_added_to_queue));
    }
    LogUtils.i(
        "FileOperationsController", "Enqueued multi-file download: " + pending.size() + " files");
  }

  void trySelfGrantRead(Uri uri) {
    try {
      context.grantUriPermission(
          context.getPackageName(), uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
    } catch (Exception e) {
      LogUtils.w("FileOperationsController", "Self-grant read failed: " + e.getMessage());
    }
    try {
      // Attempt to persist if possible (will fail silently if not persistable)
      context
          .getContentResolver()
          .takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
    } catch (Exception e) {
      // Not all providers allow this; ignore
    }
  }

  // ---- Helpers for download destinations ----
  DocumentFile createDestinationFolder(Uri uri) throws Exception {
    DocumentFile documentFile = DocumentFile.fromTreeUri(context, uri);
    if (documentFile == null || !documentFile.exists() || !documentFile.isDirectory())
      throw new Exception("Invalid destination folder");
    DocumentFile subFolder = documentFile.createDirectory(uiState.getSelectedFile().getName());
    if (subFolder == null) throw new Exception("Failed to create folder");
    return subFolder;
  }

  File createTempFolder() throws Exception {
    File tempFolder = new File(context.getCacheDir(), "download_" + System.currentTimeMillis());
    if (!tempFolder.mkdirs()) throw new Exception("Failed to create temporary folder");
    return tempFolder;
  }

  private String getFileNameFromUri(Uri uri) {
    LogUtils.d("FileOperationsController", "Getting file name from URI: " + uri);
    String result = null;
    if ("content".equals(uri.getScheme())) {
      try (android.database.Cursor cursor =
          context.getContentResolver().query(uri, null, null, null, null)) {
        if (cursor != null && cursor.moveToFirst()) {
          int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
          if (nameIndex != -1) result = cursor.getString(nameIndex);
        }
      } catch (Exception e) {
        LogUtils.e(
            "FileOperationsController", "Error querying content resolver: " + e.getMessage());
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

  private long getFileSizeFromUri(Uri uri) {
    if (uri == null) return 0;
    try (android.database.Cursor cursor =
        context.getContentResolver().query(uri, null, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        int sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
        if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
          return cursor.getLong(sizeIndex);
        }
      }
    } catch (Exception e) {
      LogUtils.w("FileOperationsController", "Could not determine file size: " + e.getMessage());
    }
    return 0;
  }

  private String buildRemotePath(String fileName) {
    String currentPath = fileListViewModel.getCurrentPathInternal();
    return (currentPath == null || currentPath.isEmpty()) ? fileName : currentPath + "/" + fileName;
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

  // ---- Success/Error unifiers ----
  void handleOperationSuccess(
      String operationType,
      SmbFileItem file,
      String itemName,
      boolean isFolder,
      String customMessage,
      boolean refreshFileList,
      Runnable customSuccessAction) {
    String successMessage = customMessage;
    if (successMessage == null) {
      String itemType = isFolder ? "folder" : "file";
      String opName =
          OPERATION_DOWNLOAD.equals(operationType)
              ? "downloaded"
              : (OPERATION_UPLOAD.equals(operationType)
                      || OPERATION_FOLDER_UPLOAD.equals(operationType))
                  ? "uploaded"
                  : OPERATION_DELETE.equals(operationType)
                      ? "deleted"
                      : OPERATION_RENAME.equals(operationType) ? "renamed" : "processed";
      successMessage =
          Character.toUpperCase(itemType.charAt(0))
              + itemType.substring(1)
              + " "
              + opName
              + " successfully";
    }
    if (progressCallback != null) progressCallback.animateSuccess();
    // Dismiss any progress dialog first so the success Snackbar is visible above the navbar
    if (progressCallback != null) {
      progressCallback.hideLoadingIndicator();
      LogUtils.d(
          "FileOperationsController", "Progress (short UI) cleaned up before showing success");
    }
    showSuccess(successMessage);
    if (refreshFileList) fileListViewModel.loadFiles(false);
    if (customSuccessAction != null) customSuccessAction.run();
    notifyOperationCompleted(operationType, file, true, successMessage);
  }

  void handleOperationError(
      String operationType,
      SmbFileItem file,
      String errorMessage,
      String customErrorTitle,
      boolean refreshFileList,
      Runnable customErrorAction) {
    String errorTitle = customErrorTitle;
    if (errorTitle == null) {
      errorTitle =
          OPERATION_DOWNLOAD.equals(operationType)
              ? "Download error"
              : (OPERATION_UPLOAD.equals(operationType)
                      || OPERATION_FOLDER_UPLOAD.equals(operationType))
                  ? "Upload error"
                  : OPERATION_DELETE.equals(operationType)
                      ? "Delete error"
                      : OPERATION_RENAME.equals(operationType) ? "Rename error" : "Operation error";
    }
    showError(errorTitle, errorMessage);
    if (refreshFileList) fileListViewModel.loadFiles(false);
    if (customErrorAction != null) customErrorAction.run();
    notifyOperationCompleted(operationType, file, false, errorMessage);
    if (progressCallback != null) {
      progressCallback.hideLoadingIndicator();
      progressCallback.hideDetailedProgressDialog();
      LogUtils.d("FileOperationsController", "Progress (short UI) cleaned up after error");
    }
  }

  // ---- Interfaces ----
  public interface FileOperationRequester {
    void requestFileOrFolderDownload(@NonNull SmbFileItem file);

    void requestFileUpload();

    void requestFolderContentsUpload();

    void requestFileDeletion(@NonNull SmbFileItem file);

    void requestFileRename(@NonNull SmbFileItem file);
  }

  public interface FileOperationListener {
    void onFileOperationStarted(@NonNull String operationType, @NonNull SmbFileItem file);

    void onFileOperationCompleted(
        @NonNull String operationType,
        @NonNull SmbFileItem file,
        boolean success,
        @NonNull String message);

    void onFileOperationProgress(
        @NonNull String operationType,
        @NonNull SmbFileItem file,
        int progress,
        @NonNull String message);
  }

  public interface ProgressCallback {
    void showLoadingIndicator(
        @NonNull String message, boolean cancelable, @NonNull Runnable cancelAction);

    void updateLoadingMessage(@NonNull String message);

    void setCancelButtonEnabled(boolean enabled);

    void hideLoadingIndicator();

    void showDetailedProgressDialog(@NonNull String title, @NonNull String message);

    void updateDetailedProgress(
        int percentage, @NonNull String statusText, @NonNull String fileName);

    void hideDetailedProgressDialog();

    void showProgressInUI(@NonNull String operationName, @NonNull String progressText);

    void showFileExistsDialog(
        @NonNull String fileName, @NonNull Runnable confirmAction, @NonNull Runnable cancelAction);

    void setZipButtonsEnabled(boolean enabled);

    void animateSuccess();

    void showSuccess(@NonNull String message);

    void showError(@NonNull String title, @NonNull String message);

    void showInfo(@NonNull String message);
  }

  public class FileOperationRequesterImpl implements FileOperationRequester {
    @Override
    public void requestFileOrFolderDownload(@NonNull SmbFileItem file) {
      uiState.setSelectedFile(file);
      notifyOperationStarted("download", file);
      if (activityResultController != null) activityResultController.initDownloadFileOrFolder(file);
      else
        LogUtils.e(
            "FileOperationsController",
            "ActivityResultController is null, cannot launch file picker");
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
    public void requestFileDeletion(@NonNull SmbFileItem file) {
      uiState.setSelectedFile(file);
      notifyOperationStarted("delete", file);
      if (dialogController != null) dialogController.showDeleteFileConfirmationDialog(file);
      else
        LogUtils.e(
            "FileOperationsController",
            "DialogController is null, cannot show confirmation dialog");
    }

    @Override
    public void requestFileRename(@NonNull SmbFileItem file) {
      uiState.setSelectedFile(file);
      notifyOperationStarted("rename", file);
      // Actual rename handled by DialogController elsewhere
    }
  }
}
