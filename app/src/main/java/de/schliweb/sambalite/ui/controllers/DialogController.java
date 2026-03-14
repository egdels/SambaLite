package de.schliweb.sambalite.ui.controllers;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.sync.SyncConfig;
import de.schliweb.sambalite.sync.SyncDirection;
import de.schliweb.sambalite.ui.*;
import de.schliweb.sambalite.ui.controllers.FileOperationsController.FileOperationRequester;
import de.schliweb.sambalite.ui.dialogs.DialogHelper;
import de.schliweb.sambalite.ui.operations.FileOperationCallbacks;
import de.schliweb.sambalite.ui.operations.FileOperationsViewModel;
import de.schliweb.sambalite.util.LogUtils;
import java.lang.ref.WeakReference;
import lombok.Setter;

/**
 * Controller for managing dialogs in the FileBrowserActivity. Handles file operation dialogs,
 * search dialogs, sort dialogs, and upload/download dialogs.
 *
 * <p>The Activity context is held via a {@link WeakReference} to prevent memory leaks when
 * background threads outlive the Activity lifecycle.
 */
public class DialogController {

  private final WeakReference<Context> contextRef;
  private final FileListViewModel fileListViewModel;
  private final FileOperationsViewModel fileOperationsViewModel;
  private final FileBrowserUIState uiState;

  @Setter private FileOperationRequester fileOperationRequester;

  @Setter private FileOperationCallback fileOperationCallback;

  @Setter private SearchCallback searchCallback;

  @Setter private UploadCallback uploadCallback;

  @Setter private SyncSetupCallback syncSetupCallback;

  @Setter private FolderSyncCallback folderSyncCallback;

  @Setter private FileOpenCallback fileOpenCallback;

  /**
   * User feedback provider for showing success, error, and info messages. This provides a
   * standardized approach to user feedback across controllers.
   */
  @Setter private UserFeedbackProvider userFeedbackProvider;

  /**
   * Returns the context if still available, or {@code null} if the Activity has been destroyed and
   * garbage-collected.
   */
  @Nullable
  private Context getContext() {
    return contextRef.get();
  }

  /**
   * Creates a new DialogController.
   *
   * @param context The context
   * @param fileListViewModel The FileListViewModel for file list operations
   * @param fileOperationsViewModel The FileOperationsViewModel for file operations
   * @param searchViewModel The SearchViewModel for search operations
   * @param uiState The shared UI state
   */
  public DialogController(
      @NonNull Context context,
      @NonNull FileListViewModel fileListViewModel,
      @NonNull FileOperationsViewModel fileOperationsViewModel,
      @NonNull SearchViewModel searchViewModel,
      @NonNull FileBrowserUIState uiState) {
    this.contextRef = new WeakReference<>(context);
    this.fileListViewModel = fileListViewModel;
    this.fileOperationsViewModel = fileOperationsViewModel;
    this.uiState = uiState;
  }

  public DialogController(
      @NonNull Context context,
      @NonNull ShareReceiverViewModel viewModel,
      @NonNull FileBrowserUIState uiState) {
    this.contextRef = new WeakReference<>(context);
    // For ShareReceiverActivity, we do not need file list or operations view models.
    // Instead, we only need the ShareReceiverViewModel and the UI state.
    this.fileListViewModel = null;
    this.fileOperationsViewModel = null;
    this.uiState = uiState;
    LogUtils.d("DialogController", "Created for ShareReceiverActivity");
  }

  /**
   * Shows a dialog with options for a file.
   *
   * @param file The file to show options for
   */
  public void showFileOptionsDialog(@NonNull SmbFileItem file) {
    Context context = getContext();
    if (context == null) return;

    LogUtils.d("DialogController", "Showing file options dialog for: " + file.getName());

    // Store the selected file in the UI state
    uiState.setSelectedFile(file);

    String[] options;
    if (file.isDirectory()) {
      // Check if this folder already has a sync config
      boolean hasSyncConfig = folderSyncCallback != null && folderSyncCallback.hasSyncConfig(file);
      if (hasSyncConfig) {
        options =
            new String[] {
              context.getString(R.string.download),
              context.getString(R.string.rename),
              context.getString(R.string.delete),
              context.getString(R.string.sync_now),
              context.getString(R.string.sync_edit_option),
              context.getString(R.string.sync_remove_option)
            };
      } else {
        options =
            new String[] {
              context.getString(R.string.download),
              context.getString(R.string.rename),
              context.getString(R.string.delete),
              context.getString(R.string.sync_folder_option)
            };
      }
    } else {
      options =
          new String[] {
            context.getString(R.string.open_file),
            context.getString(R.string.download),
            context.getString(R.string.rename),
            context.getString(R.string.delete)
          };
    }

    new MaterialAlertDialogBuilder(context)
        .setTitle(file.getName())
        .setItems(
            options,
            (dialog, which) -> {
              // For non-directory files, options are shifted by 1 due to "Open" at index 0
              int adjustedWhich = !file.isDirectory() ? which - 1 : which;
              if (!file.isDirectory() && which == 0) {
                // Open file
                if (fileOpenCallback != null) {
                  fileOpenCallback.onOpenRequested(file);
                }
                return;
              }
              switch (adjustedWhich) {
                case 0: // Download
                  if (fileOperationCallback != null) {
                    fileOperationCallback.onDownloadRequested(file);
                  }
                  break;
                case 1: // Rename
                  // First request the rename operation through the requester
                  if (fileOperationRequester != null) {
                    fileOperationRequester.requestFileRename(file);
                  }
                  // Then show the rename dialog
                  showRenameFileDialog(file);
                  break;
                case 2: // Delete
                  // Request the delete operation through the requester
                  // The requester will show the confirmation dialog
                  if (fileOperationRequester != null) {
                    fileOperationRequester.requestFileDeletion(file);
                  }
                  break;
                case 3: // Sync now or setup (only for directories)
                  if (folderSyncCallback != null) {
                    boolean hasSync = folderSyncCallback.hasSyncConfig(file);
                    if (hasSync) {
                      folderSyncCallback.onSyncNowRequested(file);
                    } else {
                      folderSyncCallback.onSetupSyncRequested(file);
                    }
                  }
                  break;
                case 4: // Sync edit (only for directories with sync)
                  if (folderSyncCallback != null) {
                    folderSyncCallback.onEditSyncRequested(file);
                  }
                  break;
                case 5: // Sync remove (only for directories with sync)
                  if (folderSyncCallback != null) {
                    folderSyncCallback.onRemoveSyncRequested(file);
                  }
                  break;
              }
            })
        .show();
  }

  /**
   * Shows a dialog to rename a file.
   *
   * @param file The file to rename
   */
  public void showRenameFileDialog(@NonNull SmbFileItem file) {
    Context context = getContext();
    if (context == null) return;

    LogUtils.d("DialogController", "Showing rename file dialog for: " + file.getName());
    DialogHelper.showRenameDialog(
        context,
        file.getName(),
        newName -> {
          if (newName != null && !newName.isEmpty()) {
            // The requestFileRename method has already been called before showing this dialog
            // Now we just need to perform the actual rename operation
            fileOperationsViewModel.renameFile(file, newName, createRenameCallback());
          }
        });
  }

  /**
   * Shows a confirmation dialog for file deletion.
   *
   * @param file The file to delete
   */
  public void showDeleteFileConfirmationDialog(@NonNull SmbFileItem file) {
    Context context = getContext();
    if (context == null) return;

    LogUtils.d(
        "DialogController", "Showing delete file confirmation dialog for: " + file.getName());
    DialogHelper.showConfirmationDialog(
        context,
        context.getString(R.string.delete_file_dialog_title),
        context.getString(R.string.confirm_delete_file, file.getName()),
        (dialog, which) -> deleteFileWithFeedback(file));
  }

  /**
   * Deletes a file with feedback. Uses UserFeedbackProvider if available, falls back to
   * DialogHelper for backward compatibility.
   *
   * @param file The file to delete
   */
  private void deleteFileWithFeedback(SmbFileItem file) {
    LogUtils.d("DialogController", "Deleting file with feedback: " + file.getName());
    fileOperationsViewModel.deleteFile(
        file,
        (success, message) -> {
          Context context = getContext();
          if (context == null) return;

          if (success) {
            if (userFeedbackProvider != null) {
              userFeedbackProvider.showSuccess(context.getString(R.string.delete_success));
            } else {
              DialogHelper.showToast(context, context.getString(R.string.delete_success));
            }
          } else {
            if (userFeedbackProvider != null) {
              userFeedbackProvider.showError(context.getString(R.string.delete_error), message);
            } else {
              DialogHelper.showErrorDialog(
                  context, context.getString(R.string.delete_error), message);
            }
          }
        });
  }

  /** Shows a dialog to create a new folder. */
  public void showCreateFolderDialog() {
    Context context = getContext();
    if (context == null) return;

    LogUtils.d("DialogController", "Showing create folder dialog");
    DialogHelper.showInputDialog(
        context,
        context.getString(R.string.create_folder),
        context.getString(R.string.create_folder_dialog_title),
        context.getString(R.string.folder_name),
        folderName -> {
          if (folderName != null && !folderName.isEmpty()) {
            fileOperationsViewModel.createFolder(folderName, createFolderCallback());
          }
        });
  }

  /** Shows a dialog for sorting files. */
  public void showSortDialog() {
    Context context = getContext();
    if (context == null) return;

    LogUtils.d("DialogController", "Showing sort dialog");

    View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_sort, null);
    RadioGroup sortGroup = dialogView.findViewById(R.id.sort_type_radio_group);
    CheckBox directoriesFirstCheckbox = dialogView.findViewById(R.id.directories_first_checkbox);
    CheckBox showHiddenFilesCheckbox = dialogView.findViewById(R.id.show_hidden_files_checkbox);

    // Set initial values
    FileSortOption currentSortOption = fileListViewModel.getSortOption().getValue();
    boolean directoriesFirst = fileListViewModel.getDirectoriesFirst().getValue();
    boolean showHiddenFiles =
        Boolean.TRUE.equals(fileListViewModel.getShowHiddenFiles().getValue());

    if (currentSortOption == FileSortOption.NAME) {
      sortGroup.check(R.id.radio_name);
    } else if (currentSortOption == FileSortOption.DATE) {
      sortGroup.check(R.id.radio_date);
    } else if (currentSortOption == FileSortOption.SIZE) {
      sortGroup.check(R.id.radio_size);
    }

    directoriesFirstCheckbox.setChecked(directoriesFirst);
    showHiddenFilesCheckbox.setChecked(showHiddenFiles);

    new MaterialAlertDialogBuilder(context)
        .setTitle(R.string.sort_by)
        .setView(dialogView)
        .setPositiveButton(
            R.string.ok,
            (dialog, which) -> {
              // Get selected sort option
              FileSortOption sortOption;
              int checkedId = sortGroup.getCheckedRadioButtonId();
              if (checkedId == R.id.radio_date) {
                sortOption = FileSortOption.DATE;
              } else if (checkedId == R.id.radio_size) {
                sortOption = FileSortOption.SIZE;
              } else {
                sortOption = FileSortOption.NAME;
              }

              // Apply sort settings
              fileListViewModel.setSortOption(sortOption);
              fileListViewModel.setDirectoriesFirst(directoriesFirstCheckbox.isChecked());
              fileListViewModel.setShowHiddenFiles(showHiddenFilesCheckbox.isChecked());
            })
        .setNegativeButton(R.string.cancel, null)
        .show();
  }

  /** Shows a dialog for searching files. */
  public void showSearchDialog() {
    Context context = getContext();
    if (context == null) return;

    LogUtils.d("DialogController", "Showing search dialog");
    DialogHelper.showSearchDialog(
        context,
        (query, searchType, includeSubfolders) -> {
          if (query != null && !query.isEmpty()) {
            if (searchCallback != null) {
              searchCallback.onSearchRequested(query, searchType, includeSubfolders);
            }
          }
        });
  }

  /** Shows a dialog for upload options. */
  public void showUploadOptionsDialog() {
    Context context = getContext();
    if (context == null) return;

    LogUtils.d("DialogController", "Showing upload options dialog");

    String[] options =
        new String[] {
          context.getString(R.string.upload), context.getString(R.string.zip_download_folder)
        };

    new MaterialAlertDialogBuilder(context)
        .setTitle(R.string.upload)
        .setItems(
            options,
            (dialog, which) -> {
              switch (which) {
                case 0: // Upload file
                  // First request the file upload operation through the requester
                  if (fileOperationRequester != null) {
                    fileOperationRequester.requestFileUpload();
                  }
                  // Then call the callback to handle the actual upload
                  if (uploadCallback != null) {
                    uploadCallback.onFileUploadRequested();
                  }
                  break;
                case 1: // Upload folder contents
                  // First request the folder contents upload operation through the requester
                  if (fileOperationRequester != null) {
                    fileOperationRequester.requestFolderContentsUpload();
                  }
                  // Then call the callback to handle the actual upload
                  if (uploadCallback != null) {
                    uploadCallback.onFolderContentsUploadRequested();
                  }
                  break;
              }
            })
        .show();
  }

  /**
   * Creates a callback for rename operations. Uses UserFeedbackProvider if available, falls back to
   * DialogHelper for backward compatibility.
   *
   * @return The callback
   */
  private FileOperationCallbacks.RenameFileCallback createRenameCallback() {
    return (success, message) -> {
      Context context = getContext();
      if (context == null) return;

      if (success) {
        if (userFeedbackProvider != null) {
          userFeedbackProvider.showSuccess(context.getString(R.string.rename_success));
        } else {
          DialogHelper.showToast(context, context.getString(R.string.rename_success));
        }
      } else {
        if (userFeedbackProvider != null) {
          userFeedbackProvider.showError(context.getString(R.string.rename_error), message);
        } else {
          DialogHelper.showErrorDialog(context, context.getString(R.string.rename_error), message);
        }
      }
    };
  }

  /**
   * Creates a callback for folder creation operations. Uses UserFeedbackProvider if available,
   * falls back to DialogHelper for backward compatibility.
   *
   * @return The callback
   */
  private FileOperationCallbacks.CreateFolderCallback createFolderCallback() {
    return (success, message) -> {
      Context context = getContext();
      if (context == null) return;

      if (success) {
        if (userFeedbackProvider != null) {
          userFeedbackProvider.showSuccess(context.getString(R.string.folder_created));
        } else {
          DialogHelper.showToast(context, context.getString(R.string.folder_created));
        }
      } else {
        if (userFeedbackProvider != null) {
          userFeedbackProvider.showError(
              context.getString(R.string.folder_creation_error), message);
        } else {
          DialogHelper.showErrorDialog(
              context, context.getString(R.string.folder_creation_error), message);
        }
      }
    };
  }

  /** Shows a dialog when no target folder is set for sharing. */
  public void showNeedsTargetFolderDialog() {
    Context context = getContext();
    if (context == null) return;

    LogUtils.d("DialogController", "Showing needs target folder dialog");
    DialogHelper.showNeedsTargetFolderDialog(
        context,
        () -> {
          Context ctx = getContext();
          if (ctx == null) return;
          // Navigate to MainActivity to select folder
          Intent intent = new Intent(ctx, MainActivity.class);
          ctx.startActivity(intent);
          ((Activity) ctx).finish();
        },
        () -> {
          Context ctx = getContext();
          if (ctx == null) return;
          // Cancel and finish
          ((Activity) ctx).finish();
        });
  }

  /**
   * Shows a confirmation dialog before uploading shared files.
   *
   * @param fileCount Number of files to upload
   * @param targetFolder Target folder for upload
   * @param onUpload Callback when user confirms upload
   * @param onCancel Callback when user cancels upload
   */
  public void showShareUploadConfirmationDialog(
      int fileCount,
      @NonNull String targetFolder,
      @Nullable Runnable onUpload,
      @Nullable Runnable onCancel) {
    showShareUploadConfirmationDialog(fileCount, targetFolder, onUpload, null, onCancel);
  }

  /**
   * Shows a confirmation dialog before uploading shared files with an optional folder change
   * button.
   *
   * @param fileCount Number of files to upload
   * @param targetFolder Target folder for upload
   * @param onUpload Callback when user confirms upload
   * @param onChangeFolder Callback when user wants to change the target folder (may be null)
   * @param onCancel Callback when user cancels upload
   */
  public void showShareUploadConfirmationDialog(
      int fileCount,
      @NonNull String targetFolder,
      @NonNull Runnable onUpload,
      @NonNull Runnable onChangeFolder,
      @NonNull Runnable onCancel) {
    Context context = getContext();
    if (context == null) return;

    LogUtils.d("DialogController", "Showing share upload confirmation dialog with custom cancel");
    DialogHelper.showShareUploadConfirmationDialog(
        context, fileCount, targetFolder, onUpload, onChangeFolder, onCancel);
  }

  /**
   * Shows a dialog after successful uploads with option to view uploaded files.
   *
   * @param uploadedCount Number of successfully uploaded files
   * @param totalCount Total number of files attempted
   * @param failedCount Number of failed uploads
   * @param onViewFiles Callback when user wants to view uploaded files
   */
  public void showUploadCompleteDialog(
      int uploadedCount, int totalCount, int failedCount, @Nullable Runnable onViewFiles) {
    Context context = getContext();
    if (context == null) return;

    LogUtils.d("DialogController", "Showing upload complete dialog");
    DialogHelper.showUploadCompleteDialog(
        context,
        uploadedCount,
        totalCount,
        failedCount,
        onViewFiles,
        () -> {
          Context ctx = getContext();
          if (ctx == null) return;
          // Close and finish
          ((Activity) ctx).finish();
        });
  }

  /**
   * Shows a dialog when a file already exists during upload.
   *
   * @param fileName Name of the existing file
   * @param onOverwrite Callback when user chooses to overwrite
   * @param onCancel Callback when user cancels
   */
  public void showFileExistsDialog(
      @NonNull String fileName, @NonNull Runnable onOverwrite, @NonNull Runnable onCancel) {
    Context context = getContext();
    if (context == null) return;

    LogUtils.d("DialogController", "Showing file exists dialog for: " + fileName);
    DialogHelper.showFileExistsDialog(context, fileName, onOverwrite, onCancel);
  }

  /** Callback for opening a file with an external app. */
  public interface FileOpenCallback {
    void onOpenRequested(@NonNull SmbFileItem file);
  }

  /** Callback for file operations. */
  public interface FileOperationCallback {
    /**
     * Called when a download is requested.
     *
     * @param file The file to download
     */
    void onDownloadRequested(@NonNull SmbFileItem file);
  }

  /** Callback for search operations. */
  public interface SearchCallback {
    /**
     * Called when a search is requested.
     *
     * @param query The search query
     * @param searchType The type of items to search for (0=all, 1=files only, 2=folders only)
     * @param includeSubfolders Whether to include subfolders in the search
     */
    void onSearchRequested(@NonNull String query, int searchType, boolean includeSubfolders);
  }

  /** Callback for upload operations. */
  public interface UploadCallback {
    /** Called when a file upload is requested. */
    void onFileUploadRequested();

    /** Called when a folder contents upload is requested. */
    void onFolderContentsUploadRequested();
  }

  /** Callback interface for folder-level sync operations from the context menu. */
  public interface FolderSyncCallback {
    /** Called when the user wants to set up sync for a folder. */
    void onSetupSyncRequested(@NonNull SmbFileItem folder);

    /** Called when the user wants to sync a folder immediately. */
    void onSyncNowRequested(@NonNull SmbFileItem folder);

    /** Called when the user wants to edit sync for a folder. */
    void onEditSyncRequested(@NonNull SmbFileItem folder);

    /** Called when the user wants to remove sync from a folder. */
    void onRemoveSyncRequested(@NonNull SmbFileItem folder);

    /** Checks if the given folder already has a sync configuration. */
    boolean hasSyncConfig(@NonNull SmbFileItem folder);
  }

  public interface SyncSetupCallback {
    /**
     * Called when sync setup is confirmed with the selected parameters.
     *
     * @param direction the sync direction
     * @param intervalMinutes the sync interval in minutes
     * @param remotePath the remote path
     */
    void onSyncSetupRequested(
        @NonNull SyncDirection direction, int intervalMinutes, @NonNull String remotePath);

    /** Called when the user wants to select a local folder for sync. */
    void onSyncFolderPickRequested();
  }

  /**
   * Shows the sync setup dialog.
   *
   * @param currentRemotePath the current remote path to pre-fill
   */
  public void showSyncSetupDialog(@NonNull String currentRemotePath) {
    Context context = getContext();
    if (context == null) return;

    LogUtils.d("DialogController", "Showing sync setup dialog");

    View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_sync_setup, null);

    RadioGroup directionGroup = dialogView.findViewById(R.id.sync_direction_group);
    Spinner intervalSpinner = dialogView.findViewById(R.id.sync_interval_spinner);
    TextView remotePathField = dialogView.findViewById(R.id.sync_remote_path);
    TextView folderDisplay = dialogView.findViewById(R.id.sync_local_folder_display);
    View selectFolderButton = dialogView.findViewById(R.id.sync_select_folder_button);

    // Pre-fill remote path and make it non-editable
    if (currentRemotePath != null && !currentRemotePath.isEmpty()) {
      remotePathField.setText(currentRemotePath);
    }
    remotePathField.setEnabled(false);

    // Setup interval spinner
    String[] intervalLabels = {
      context.getString(R.string.sync_interval_15min),
      context.getString(R.string.sync_interval_30min),
      context.getString(R.string.sync_interval_1h),
      context.getString(R.string.sync_interval_6h),
      context.getString(R.string.sync_interval_12h),
      context.getString(R.string.sync_interval_24h)
    };
    int[] intervalValues = {15, 30, 60, 360, 720, 1440};

    ArrayAdapter<String> adapter =
        new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, intervalLabels);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    intervalSpinner.setAdapter(adapter);
    intervalSpinner.setSelection(2); // Default: Every hour

    // Folder picker button
    selectFolderButton.setOnClickListener(
        v -> {
          if (syncSetupCallback != null) {
            syncSetupCallback.onSyncFolderPickRequested();
          }
        });

    // Store reference to folder display for updating from outside
    uiState.setSyncFolderDisplay(folderDisplay);

    new MaterialAlertDialogBuilder(context)
        .setTitle(R.string.sync_setup_title)
        .setView(dialogView)
        .setPositiveButton(
            R.string.save,
            (dialog, which) -> {
              // Get direction
              SyncDirection direction = SyncDirection.BIDIRECTIONAL;
              int checkedId = directionGroup.getCheckedRadioButtonId();
              if (checkedId == R.id.radio_local_to_remote) {
                direction = SyncDirection.LOCAL_TO_REMOTE;
              } else if (checkedId == R.id.radio_remote_to_local) {
                direction = SyncDirection.REMOTE_TO_LOCAL;
              }

              // Get interval
              int intervalMinutes = intervalValues[intervalSpinner.getSelectedItemPosition()];

              // Get remote path
              String remotePath = remotePathField.getText().toString().trim();

              if (syncSetupCallback != null) {
                syncSetupCallback.onSyncSetupRequested(direction, intervalMinutes, remotePath);
              }
            })
        .setNegativeButton(R.string.cancel, null)
        .show();
  }

  /**
   * Shows the sync edit dialog with pre-filled values from an existing config.
   *
   * @param config the existing sync configuration to edit
   */
  public void showSyncEditDialog(@NonNull SyncConfig config) {
    Context context = getContext();
    if (context == null) return;

    LogUtils.d("DialogController", "Showing sync edit dialog for config: " + config.getId());

    View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_sync_setup, null);

    RadioGroup directionGroup = dialogView.findViewById(R.id.sync_direction_group);
    Spinner intervalSpinner = dialogView.findViewById(R.id.sync_interval_spinner);
    TextView remotePathField = dialogView.findViewById(R.id.sync_remote_path);
    TextView folderDisplay = dialogView.findViewById(R.id.sync_local_folder_display);
    View selectFolderButton = dialogView.findViewById(R.id.sync_select_folder_button);

    // Pre-fill remote path and make it non-editable in edit mode
    if (config.getRemotePath() != null && !config.getRemotePath().isEmpty()) {
      remotePathField.setText(config.getRemotePath());
    }
    remotePathField.setEnabled(false);

    // Pre-fill direction
    switch (config.getDirection()) {
      case LOCAL_TO_REMOTE:
        directionGroup.check(R.id.radio_local_to_remote);
        break;
      case REMOTE_TO_LOCAL:
        directionGroup.check(R.id.radio_remote_to_local);
        break;
      case BIDIRECTIONAL:
      default:
        directionGroup.check(R.id.radio_bidirectional);
        break;
    }

    // Setup interval spinner
    String[] intervalLabels = {
      context.getString(R.string.sync_interval_15min),
      context.getString(R.string.sync_interval_30min),
      context.getString(R.string.sync_interval_1h),
      context.getString(R.string.sync_interval_6h),
      context.getString(R.string.sync_interval_12h),
      context.getString(R.string.sync_interval_24h)
    };
    int[] intervalValues = {15, 30, 60, 360, 720, 1440};

    ArrayAdapter<String> adapter =
        new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, intervalLabels);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    intervalSpinner.setAdapter(adapter);

    // Pre-select interval
    int currentInterval = config.getIntervalMinutes();
    int selectedIndex = 2; // default: 1h
    for (int i = 0; i < intervalValues.length; i++) {
      if (intervalValues[i] == currentInterval) {
        selectedIndex = i;
        break;
      }
    }
    intervalSpinner.setSelection(selectedIndex);

    // Pre-fill local folder display
    if (config.getLocalFolderDisplayName() != null
        && !config.getLocalFolderDisplayName().isEmpty()) {
      folderDisplay.setText(config.getLocalFolderDisplayName());
    }
    if (config.getLocalFolderUri() != null) {
      uiState.setSyncFolderUri(android.net.Uri.parse(config.getLocalFolderUri()));
      uiState.setSyncFolderDisplayName(config.getLocalFolderDisplayName());
    }

    // Folder picker button
    selectFolderButton.setOnClickListener(
        v -> {
          if (syncSetupCallback != null) {
            syncSetupCallback.onSyncFolderPickRequested();
          }
        });

    // Store reference to folder display for updating from outside
    uiState.setSyncFolderDisplay(folderDisplay);

    new MaterialAlertDialogBuilder(context)
        .setTitle(R.string.sync_edit_option)
        .setView(dialogView)
        .setPositiveButton(
            R.string.save,
            (dialog, which) -> {
              // Get direction
              SyncDirection direction = SyncDirection.BIDIRECTIONAL;
              int checkedId = directionGroup.getCheckedRadioButtonId();
              if (checkedId == R.id.radio_local_to_remote) {
                direction = SyncDirection.LOCAL_TO_REMOTE;
              } else if (checkedId == R.id.radio_remote_to_local) {
                direction = SyncDirection.REMOTE_TO_LOCAL;
              }

              // Get interval
              int intervalMinutes = intervalValues[intervalSpinner.getSelectedItemPosition()];

              // Get remote path
              String remotePath = remotePathField.getText().toString().trim();

              if (syncSetupCallback != null) {
                syncSetupCallback.onSyncSetupRequested(direction, intervalMinutes, remotePath);
              }
            })
        .setNegativeButton(
            R.string.cancel,
            (dialog, which) -> {
              // Clear editing state so the config is not removed on cancel
              uiState.setEditingSyncConfigId(null);
              uiState.setSyncFolderUri(null);
              uiState.setSyncFolderDisplayName(null);
              uiState.setSyncFolderDisplay(null);
            })
        .setOnCancelListener(
            dialog -> {
              // Also handle back button / outside tap dismissal
              uiState.setEditingSyncConfigId(null);
              uiState.setSyncFolderUri(null);
              uiState.setSyncFolderDisplayName(null);
              uiState.setSyncFolderDisplay(null);
            })
        .show();
  }

  /**
   * Shows a dialog to manage existing sync configurations.
   *
   * @param configs the list of sync configurations
   * @param onDelete callback when a config should be deleted
   * @param onToggle callback when a config should be enabled/disabled
   * @param onSyncNow callback when immediate sync is requested
   */
  public void showManageSyncConfigsDialog(
      @NonNull java.util.List<SyncConfig> configs,
      @NonNull java.util.function.Consumer<String> onDelete,
      @NonNull java.util.function.BiConsumer<String, Boolean> onToggle,
      @NonNull Runnable onSyncNow) {
    Context context = getContext();
    if (context == null) return;

    LogUtils.d("DialogController", "Showing manage sync configs dialog");

    if (configs.isEmpty()) {
      new MaterialAlertDialogBuilder(context)
          .setTitle(R.string.sync_manage_title)
          .setMessage(R.string.sync_no_configs)
          .setPositiveButton(R.string.ok, null)
          .show();
      return;
    }

    String[] items = new String[configs.size()];
    for (int i = 0; i < configs.size(); i++) {
      SyncConfig config = configs.get(i);
      String status =
          config.isEnabled()
              ? context.getString(R.string.sync_enabled)
              : context.getString(R.string.sync_disabled);
      String lastSync =
          config.getLastSyncTimestamp() > 0
              ? android.text.format.DateFormat.format(
                      "yyyy-MM-dd HH:mm", config.getLastSyncTimestamp())
                  .toString()
              : context.getString(R.string.sync_never);
      items[i] =
          config.getLocalFolderDisplayName()
              + " → "
              + config.getRemotePath()
              + "\n"
              + status
              + " | "
              + context.getString(R.string.sync_last_sync, lastSync);
    }

    new MaterialAlertDialogBuilder(context)
        .setTitle(R.string.sync_manage_title)
        .setItems(
            items,
            (dialog, which) -> {
              SyncConfig selected = configs.get(which);
              showSyncConfigOptionsDialog(selected, onDelete, onToggle);
            })
        .setNeutralButton(
            R.string.sync_now,
            (dialog, which) -> {
              if (onSyncNow != null) onSyncNow.run();
            })
        .setNegativeButton(R.string.close, null)
        .show();
  }

  /** Shows options for a single sync configuration. */
  private void showSyncConfigOptionsDialog(
      SyncConfig config,
      java.util.function.Consumer<String> onDelete,
      java.util.function.BiConsumer<String, Boolean> onToggle) {
    Context context = getContext();
    if (context == null) return;

    String toggleLabel =
        config.isEnabled()
            ? context.getString(R.string.sync_disabled)
            : context.getString(R.string.sync_enabled);
    String[] options = {toggleLabel, context.getString(R.string.delete)};

    new MaterialAlertDialogBuilder(context)
        .setTitle(config.getLocalFolderDisplayName())
        .setItems(
            options,
            (dialog, which) -> {
              switch (which) {
                case 0: // Toggle
                  if (onToggle != null) {
                    onToggle.accept(config.getId(), !config.isEnabled());
                  }
                  break;
                case 1: // Delete
                  if (onDelete != null) {
                    onDelete.accept(config.getId());
                  }
                  break;
              }
            })
        .setNegativeButton(R.string.cancel, null)
        .show();
  }
}
