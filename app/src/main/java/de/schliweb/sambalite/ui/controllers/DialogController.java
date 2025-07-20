package de.schliweb.sambalite.ui.controllers;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.RadioGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.ui.FileListViewModel;
import de.schliweb.sambalite.ui.FileSortOption;
import de.schliweb.sambalite.ui.SearchViewModel;
import de.schliweb.sambalite.ui.controllers.FileOperationsController.FileOperationRequester;
import de.schliweb.sambalite.ui.dialogs.DialogHelper;
import de.schliweb.sambalite.ui.operations.FileOperationCallbacks;
import de.schliweb.sambalite.ui.operations.FileOperationsViewModel;
import de.schliweb.sambalite.util.LogUtils;
import lombok.Setter;

/**
 * Controller for managing dialogs in the FileBrowserActivity.
 * Handles file operation dialogs, search dialogs, sort dialogs, and upload/download dialogs.
 */
public class DialogController {

    private final Context context;
    private final FileListViewModel fileListViewModel;
    private final FileOperationsViewModel fileOperationsViewModel;
    private final SearchViewModel searchViewModel;
    private final FileBrowserUIState uiState;

    @Setter
    private FileOperationRequester fileOperationRequester;

    @Setter
    private FileOperationCallback fileOperationCallback;

    @Setter
    private SearchCallback searchCallback;

    @Setter
    private UploadCallback uploadCallback;

    /**
     * User feedback provider for showing success, error, and info messages.
     * This provides a standardized approach to user feedback across controllers.
     */
    @Setter
    private UserFeedbackProvider userFeedbackProvider;

    /**
     * Creates a new DialogController.
     *
     * @param context                 The context
     * @param fileListViewModel       The FileListViewModel for file list operations
     * @param fileOperationsViewModel The FileOperationsViewModel for file operations
     * @param searchViewModel         The SearchViewModel for search operations
     * @param uiState                 The shared UI state
     */
    public DialogController(Context context, FileListViewModel fileListViewModel, FileOperationsViewModel fileOperationsViewModel, SearchViewModel searchViewModel, FileBrowserUIState uiState) {
        this.context = context;
        this.fileListViewModel = fileListViewModel;
        this.fileOperationsViewModel = fileOperationsViewModel;
        this.searchViewModel = searchViewModel;
        this.uiState = uiState;
    }

    /**
     * Shows a dialog with options for a file.
     *
     * @param file The file to show options for
     */
    public void showFileOptionsDialog(SmbFileItem file) {
        LogUtils.d("DialogController", "Showing file options dialog for: " + file.getName());

        // Store the selected file in the UI state
        uiState.setSelectedFile(file);

        String[] options;
        if (file.isDirectory()) {
            options = new String[]{context.getString(R.string.download), context.getString(R.string.rename), context.getString(R.string.delete)};
        } else {
            options = new String[]{context.getString(R.string.download), context.getString(R.string.rename), context.getString(R.string.delete)};
        }

        new MaterialAlertDialogBuilder(context).setTitle(file.getName()).setItems(options, (dialog, which) -> {
            switch (which) {
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
            }
        }).show();
    }

    /**
     * Shows a dialog to rename a file.
     *
     * @param file The file to rename
     */
    public void showRenameFileDialog(SmbFileItem file) {
        LogUtils.d("DialogController", "Showing rename file dialog for: " + file.getName());
        DialogHelper.showRenameDialog(context, file.getName(), newName -> {
            if (newName != null && !newName.isEmpty()) {
                // The requestFileRename method has already been called before showing this dialog
                // Now we just need to perform the actual rename operation
                fileOperationsViewModel.renameFile(file, newName, createRenameCallback(file));
            }
        });
    }

    /**
     * Shows a confirmation dialog for file deletion.
     *
     * @param file The file to delete
     */
    public void showDeleteFileConfirmationDialog(SmbFileItem file) {
        LogUtils.d("DialogController", "Showing delete file confirmation dialog for: " + file.getName());
        DialogHelper.showConfirmationDialog(context, context.getString(R.string.delete_file_dialog_title), context.getString(R.string.confirm_delete_file, file.getName()), (dialog, which) -> deleteFileWithFeedback(file));
    }

    /**
     * Deletes a file with feedback.
     * Uses UserFeedbackProvider if available, falls back to DialogHelper for backward compatibility.
     *
     * @param file The file to delete
     */
    private void deleteFileWithFeedback(SmbFileItem file) {
        LogUtils.d("DialogController", "Deleting file with feedback: " + file.getName());
        fileOperationsViewModel.deleteFile(file, (success, message) -> {
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
                    DialogHelper.showErrorDialog(context, context.getString(R.string.delete_error), message);
                }
            }
        });
    }

    /**
     * Shows a dialog to create a new folder.
     */
    public void showCreateFolderDialog() {
        LogUtils.d("DialogController", "Showing create folder dialog");
        DialogHelper.showInputDialog(context, context.getString(R.string.create_folder), context.getString(R.string.create_folder_dialog_title), context.getString(R.string.folder_name), folderName -> {
            if (folderName != null && !folderName.isEmpty()) {
                fileOperationsViewModel.createFolder(folderName, createFolderCallback());
            }
        });
    }

    /**
     * Shows a dialog for sorting files.
     */
    public void showSortDialog() {
        LogUtils.d("DialogController", "Showing sort dialog");

        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_sort, null);
        RadioGroup sortGroup = dialogView.findViewById(R.id.sort_type_radio_group);
        CheckBox directoriesFirstCheckbox = dialogView.findViewById(R.id.directories_first_checkbox);

        // Set initial values
        FileSortOption currentSortOption = fileListViewModel.getSortOption().getValue();
        boolean directoriesFirst = fileListViewModel.getDirectoriesFirst().getValue();

        if (currentSortOption == FileSortOption.NAME) {
            sortGroup.check(R.id.radio_name);
        } else if (currentSortOption == FileSortOption.DATE) {
            sortGroup.check(R.id.radio_date);
        } else if (currentSortOption == FileSortOption.SIZE) {
            sortGroup.check(R.id.radio_size);
        }

        directoriesFirstCheckbox.setChecked(directoriesFirst);

        new MaterialAlertDialogBuilder(context).setTitle(R.string.sort_by).setView(dialogView).setPositiveButton(R.string.ok, (dialog, which) -> {
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
        }).setNegativeButton(R.string.cancel, null).show();
    }

    /**
     * Shows a dialog for searching files.
     */
    public void showSearchDialog() {
        LogUtils.d("DialogController", "Showing search dialog");
        DialogHelper.showSearchDialog(context, (query, searchType, includeSubfolders) -> {
            if (query != null && !query.isEmpty()) {
                if (searchCallback != null) {
                    searchCallback.onSearchRequested(query, searchType, includeSubfolders);
                }
            }
        });
    }

    /**
     * Shows a dialog for upload options.
     */
    public void showUploadOptionsDialog() {
        LogUtils.d("DialogController", "Showing upload options dialog");

        String[] options = new String[]{context.getString(R.string.upload), context.getString(R.string.zip_download_folder)};

        new MaterialAlertDialogBuilder(context).setTitle(R.string.upload).setItems(options, (dialog, which) -> {
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
        }).show();
    }

    /**
     * Creates a callback for rename operations.
     * Uses UserFeedbackProvider if available, falls back to DialogHelper for backward compatibility.
     *
     * @param file The file being renamed
     * @return The callback
     */
    private FileOperationCallbacks.RenameFileCallback createRenameCallback(SmbFileItem file) {
        return (success, message) -> {
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
     * Creates a callback for folder creation operations.
     * Uses UserFeedbackProvider if available, falls back to DialogHelper for backward compatibility.
     *
     * @return The callback
     */
    private FileOperationCallbacks.CreateFolderCallback createFolderCallback() {
        return (success, message) -> {
            if (success) {
                if (userFeedbackProvider != null) {
                    userFeedbackProvider.showSuccess(context.getString(R.string.folder_created));
                } else {
                    DialogHelper.showToast(context, context.getString(R.string.folder_created));
                }
            } else {
                if (userFeedbackProvider != null) {
                    userFeedbackProvider.showError(context.getString(R.string.folder_creation_error), message);
                } else {
                    DialogHelper.showErrorDialog(context, context.getString(R.string.folder_creation_error), message);
                }
            }
        };
    }

    /**
     * Callback for file operations.
     */
    public interface FileOperationCallback {
        /**
         * Called when a download is requested.
         *
         * @param file The file to download
         */
        void onDownloadRequested(SmbFileItem file);
    }

    /**
     * Callback for search operations.
     */
    public interface SearchCallback {
        /**
         * Called when a search is requested.
         *
         * @param query             The search query
         * @param searchType        The type of items to search for (0=all, 1=files only, 2=folders only)
         * @param includeSubfolders Whether to include subfolders in the search
         */
        void onSearchRequested(String query, int searchType, boolean includeSubfolders);
    }

    /**
     * Callback for upload operations.
     */
    public interface UploadCallback {
        /**
         * Called when a file upload is requested.
         */
        void onFileUploadRequested();

        /**
         * Called when a folder contents upload is requested.
         */
        void onFolderContentsUploadRequested();

    }
}