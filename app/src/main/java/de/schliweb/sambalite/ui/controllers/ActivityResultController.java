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

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.ui.utils.PreferenceUtils;
import de.schliweb.sambalite.util.LogUtils;
import java.util.ArrayList;
import java.util.List;
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
  private ActivityResultLauncher<Intent> syncFolderLauncher;

  @Setter private FileOperationCallback fileOperationCallback;

  /**
   * Constructs an instance of ActivityResultController.
   *
   * <p>This controller is responsible for managing activity result launchers used for file and
   * folder operations and interacts with the UI state and input controller of the file browser.
   *
   * @param activity The associated AppCompatActivity that uses this controller
   * @param uiState The shared UI state of the file browser
   * @param inputController The input controller for handling keyboard and focus management
   */
  public ActivityResultController(
      @NonNull AppCompatActivity activity,
      @NonNull FileBrowserUIState uiState,
      @NonNull InputController inputController) {
    this.activity = activity;
    this.uiState = uiState;
    this.inputController = inputController;

    // Initialize activity result launchers
    initializeActivityResultLaunchers();

    LogUtils.d("ActivityResultController", "ActivityResultController initialized");
  }

  /** Initializes the activity result launchers. */
  private void initializeActivityResultLaunchers() {
    createFileLauncher =
        activity.registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> handleDocumentResult(result, "create_file"));

    pickFileLauncher =
        activity.registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> handleDocumentResult(result, "pick_file"));

    createFolderLauncher =
        activity.registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> handleDocumentResult(result, "create_folder"));

    pickFolderLauncher =
        activity.registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> handleDocumentResult(result, "pick_folder"));

    syncFolderLauncher =
        activity.registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> handleDocumentResult(result, "sync_folder"));

    LogUtils.d("ActivityResultController", "Activity result launchers initialized");
  }

  /**
   * Handles results from activity result launchers.
   *
   * @param result The activity result
   * @param operation The operation type
   */
  private void handleDocumentResult(ActivityResult result, String operation) {
    LogUtils.d(
        "ActivityResultController",
        "handleDocumentResult: operation="
            + operation
            + ", resultCode="
            + result.getResultCode()
            + ", hasData="
            + (result.getData() != null)
            + ", dataUri="
            + (result.getData() != null ? result.getData().getData() : "null")
            + ", flags="
            + (result.getData() != null ? result.getData().getFlags() : "no-data"));
    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
      Intent data = result.getData();

      // Handle multi-file selection for upload
      if ("pick_file".equals(operation)) {
        List<Uri> uris = extractMultipleUris(data);
        if (!uris.isEmpty()) {
          if (uris.size() == 1) {
            uiState.setSelectedUri(uris.get(0));
            handlePickFileResult(uris.get(0));
          } else {
            handleMultiplePickFileResult(uris);
          }
          return;
        }
      }

      Uri uri = data.getData();
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
          case "sync_folder":
            handleSyncFolderResult(uri);
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

    // Persist write permission so the TransferWorker can access the URI after app restart/retry
    try {
      activity
          .getContentResolver()
          .takePersistableUriPermission(
              uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    } catch (Exception e) {
      LogUtils.w(
          "ActivityResultController",
          "takePersistableUriPermission failed for download target: " + e.getMessage());
    }

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
    LogUtils.d(
        "ActivityResultController",
        "handleCreateFolderResult: uri="
            + uri
            + ", isMultiDownloadPending="
            + uiState.isMultiDownloadPending()
            + ", pendingItems="
            + (uiState.getPendingMultiDownloadItems() != null
                ? uiState.getPendingMultiDownloadItems().size()
                : "null"));

    // Persist read/write permission so the TransferWorker can access child URIs after restart
    try {
      activity
          .getContentResolver()
          .takePersistableUriPermission(
              uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    } catch (Exception e) {
      LogUtils.w(
          "ActivityResultController",
          "takePersistableUriPermission failed for folder download target: " + e.getMessage());
    }

    // Clean up UI state first
    inputController.hideKeyboardAndClearFocus();

    // Delegate to the file operation callback
    if (fileOperationCallback != null) {
      fileOperationCallback.onFolderDownloadResult(uri);
    }
  }

  /**
   * Handles the result of picking a folder.
   *
   * <p>This method hides the keyboard, clears UI focus, and delegates the folder upload result to
   * the file operation callback, if available.
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
   * Handles the result of picking a folder for sync.
   *
   * @param uri The URI of the picked folder
   */
  private void handleSyncFolderResult(Uri uri) {
    inputController.hideKeyboardAndClearFocus();

    if (fileOperationCallback != null) {
      fileOperationCallback.onSyncFolderSelected(uri);
    }
  }

  /** Initiates folder selection for sync. */
  public void selectFolderForSync() {
    LogUtils.d("ActivityResultController", "Selecting folder for sync");
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    intent.addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    syncFolderLauncher.launch(intent);
  }

  /**
   * Initiates the download of a file or folder.
   *
   * @param file The file or folder to download
   */
  public void initDownloadFileOrFolder(@NonNull SmbFileItem file) {
    LogUtils.d(
        "ActivityResultController",
        "Initiating download for: "
            + file.getName()
            + ", type: "
            + (file.isDirectory() ? "directory" : "file")
            + (file.isFile() ? ", size: " + file.getSize() + " bytes" : ""));

    // Store the file to download in the UI state
    uiState.setSelectedFile(file);

    if (file.isDirectory()) {
      // For directories, we need to create a folder picker
      Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
      intent.addFlags(
          Intent.FLAG_GRANT_READ_URI_PERMISSION
              | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
              | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
      // Set initial URI to the last used download folder so the picker opens there directly
      Uri lastUri = PreferenceUtils.getLastDownloadFolderUri(activity);
      if (lastUri != null) {
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, lastUri);
      }
      LogUtils.d(
          "ActivityResultController", "Created folder picker intent for folder: " + file.getName());

      // Start the folder picker activity
      LogUtils.d("ActivityResultController", "Starting folder picker activity for download");
      createFolderLauncher.launch(intent);
    } else {
      // For files, create a file picker
      Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
      intent.addCategory(Intent.CATEGORY_OPENABLE);
      intent.setType("*/*"); // Allow any file type
      intent.putExtra(Intent.EXTRA_TITLE, file.getName()); // Suggest the file name
      // Request persistable permission so the Worker can access the URI after app restart
      intent.addFlags(
          Intent.FLAG_GRANT_READ_URI_PERMISSION
              | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
              | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
      LogUtils.d(
          "ActivityResultController",
          "Created file picker intent with suggested name: " + file.getName());

      // Start the file picker activity
      LogUtils.d("ActivityResultController", "Starting file picker activity for download");
      createFileLauncher.launch(intent);
    }
  }

  /** Initiates the file selection process for uploading (supports multi-selection). */
  public void selectFileToUpload() {
    LogUtils.d(
        "ActivityResultController", "Initiating file selection for upload (multi-select enabled)");
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("*/*"); // Allow any file type
    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    LogUtils.d("ActivityResultController", "Starting file picker activity for upload");
    pickFileLauncher.launch(intent);
  }

  /** Initiates folder selection for folder contents upload. */
  public void selectFolderToUpload() {
    LogUtils.d("ActivityResultController", "Selecting folder for folder contents upload");
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    pickFolderLauncher.launch(intent);
  }

  /**
   * Initiates folder selection for multi-file download target. Reuses the folder download callback
   * path (onFolderDownloadResult).
   */
  public void selectFolderForDownloadTarget() {
    LogUtils.d(
        "ActivityResultController",
        "Selecting folder for multi-file download target"
            + ", isMultiDownloadPending="
            + uiState.isMultiDownloadPending()
            + ", pendingItems="
            + (uiState.getPendingMultiDownloadItems() != null
                ? uiState.getPendingMultiDownloadItems().size()
                : "null"));
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    intent.addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    // Set initial URI to the last used download folder so the picker opens there directly
    Uri lastUri = PreferenceUtils.getLastDownloadFolderUri(activity);
    if (lastUri != null) {
      intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, lastUri);
    }
    // Use createFolderLauncher so the result routes to onFolderDownloadResult
    createFolderLauncher.launch(intent);
  }

  /** Extracts multiple URIs from an Intent (ClipData for multi-select, or single getData()). */
  private List<Uri> extractMultipleUris(@NonNull Intent data) {
    List<Uri> uris = new ArrayList<>();
    ClipData clipData = data.getClipData();
    if (clipData != null) {
      for (int i = 0; i < clipData.getItemCount(); i++) {
        Uri uri = clipData.getItemAt(i).getUri();
        if (uri != null) {
          uris.add(uri);
        }
      }
    } else if (data.getData() != null) {
      uris.add(data.getData());
    }
    return uris;
  }

  /**
   * Handles multiple file pick results for upload.
   *
   * @param uris The URIs of the picked files
   */
  private void handleMultiplePickFileResult(@NonNull List<Uri> uris) {
    inputController.hideKeyboardAndClearFocus();
    if (fileOperationCallback != null) {
      fileOperationCallback.onMultipleFileUploadResult(uris);
    }
  }

  /** Callback for file operations. */
  public interface FileOperationCallback {
    /**
     * Called when a file upload result is received.
     *
     * @param uri The URI of the file to upload
     */
    void onFileUploadResult(@NonNull Uri uri);

    /**
     * Called when multiple files are selected for upload.
     *
     * @param uris The URIs of the files to upload
     */
    void onMultipleFileUploadResult(@NonNull List<Uri> uris);

    /**
     * Called when a file download result is received.
     *
     * @param uri The URI to save the downloaded file to
     */
    void onFileDownloadResult(@NonNull Uri uri);

    /**
     * Called when a folder download result is received.
     *
     * @param uri The URI to save the downloaded folder to
     */
    void onFolderDownloadResult(@NonNull Uri uri);

    /**
     * Called when a folder contents upload result is received.
     *
     * @param uri The URI of the folder to upload contents from
     */
    void onFolderUploadResult(@NonNull Uri uri);

    /**
     * Called when a folder is selected for sync.
     *
     * @param uri The URI of the folder to sync
     */
    void onSyncFolderSelected(@NonNull Uri uri);
  }
}
