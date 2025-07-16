package de.schliweb.sambalite.ui.dialogs;

import android.app.Activity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.RadioGroup;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.ui.FileBrowserViewModel;
import de.schliweb.sambalite.util.KeyboardUtils;
import de.schliweb.sambalite.util.LogUtils;

/**
 * Helper class for creating and managing dialogs in FileBrowserActivity.
 * Reduces complexity by centralizing dialog creation logic.
 */
public class DialogHelper {

    /**
     * Creates a search dialog with validation and callback handling.
     */
    public static void showSearchDialog(Activity activity, FileBrowserViewModel viewModel) {
        LogUtils.d("DialogHelper", "Showing search dialog");

        View dialogView = activity.getLayoutInflater().inflate(R.layout.dialog_search, null);
        TextInputEditText searchQueryEditText = dialogView.findViewById(R.id.search_query_edit_text);
        TextInputLayout searchQueryLayout = dialogView.findViewById(R.id.search_query_layout);
        RadioGroup searchTypeRadioGroup = dialogView.findViewById(R.id.search_type_radio_group);
        CheckBox includeSubfoldersCheckbox = dialogView.findViewById(R.id.include_subfolders_checkbox);

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setTitle(R.string.search_files).setView(dialogView).setPositiveButton(R.string.search, null).setNegativeButton(R.string.cancel, null).create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> handleSearchClick(dialog, searchQueryEditText, searchQueryLayout, searchTypeRadioGroup, includeSubfoldersCheckbox, viewModel, activity));
        });

        dialog.show();
        setupDialogFocus(activity, searchQueryEditText);
    }

    /**
     * Creates a rename dialog with validation.
     */
    public static void showRenameDialog(Activity activity, SmbFileItem file, FileBrowserViewModel.RenameFileCallback callback, FileBrowserViewModel viewModel) {
        LogUtils.d("DialogHelper", "Showing rename dialog for: " + file.getName());

        View dialogView = activity.getLayoutInflater().inflate(R.layout.dialog_rename_file, null);
        TextInputEditText fileNameEditText = dialogView.findViewById(R.id.file_name_edit_text);
        fileNameEditText.setText(file.getName());

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setTitle(R.string.rename_dialog_title).setView(dialogView).setPositiveButton(R.string.rename, null).setNegativeButton(R.string.cancel, null).create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> handleRenameClick(dialog, fileNameEditText, file, callback, viewModel, activity));
        });

        dialog.show();
        setupDialogFocus(activity, fileNameEditText);
    }

    /**
     * Creates a folder creation dialog.
     */
    public static void showCreateFolderDialog(Activity activity, FileBrowserViewModel.CreateFolderCallback callback, FileBrowserViewModel viewModel) {
        LogUtils.d("DialogHelper", "Showing create folder dialog");

        View dialogView = activity.getLayoutInflater().inflate(R.layout.dialog_create_folder, null);
        TextInputEditText folderNameEditText = dialogView.findViewById(R.id.folder_name_edit_text);
        TextInputLayout folderNameLayout = dialogView.findViewById(R.id.folder_name_layout);

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setTitle(R.string.create_folder_dialog_title).setView(dialogView).setPositiveButton(R.string.create_folder, null).setNegativeButton(R.string.cancel, null).create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> handleCreateFolderClick(dialog, folderNameEditText, folderNameLayout, callback, viewModel));
        });

        dialog.show();
        setupDialogFocus(activity, folderNameEditText);
    }

    private static void handleSearchClick(AlertDialog dialog, TextInputEditText searchQueryEditText, TextInputLayout searchQueryLayout, RadioGroup searchTypeRadioGroup, CheckBox includeSubfoldersCheckbox, FileBrowserViewModel viewModel, Activity activity) {
        String query = searchQueryEditText.getText().toString().trim();
        if (query.isEmpty()) {
            searchQueryLayout.setError(activity.getString(R.string.search_query_hint));
            return;
        }

        searchQueryLayout.setError(null);

        int searchType = getSearchType(searchTypeRadioGroup);
        boolean includeSubfolders = includeSubfoldersCheckbox.isChecked();

        viewModel.searchFiles(query, searchType, includeSubfolders);
        dialog.dismiss();
    }

    private static void handleRenameClick(AlertDialog dialog, TextInputEditText fileNameEditText, SmbFileItem file, FileBrowserViewModel.RenameFileCallback callback, FileBrowserViewModel viewModel, Activity activity) {
        String newName = fileNameEditText.getText().toString().trim();
        if (newName.isEmpty()) {
            fileNameEditText.setError(activity.getString(R.string.error_name_required));
            return;
        }

        KeyboardUtils.hideKeyboard(activity);
        viewModel.renameFile(file, newName, callback);
        dialog.dismiss();
    }

    private static void handleCreateFolderClick(AlertDialog dialog, TextInputEditText folderNameEditText, TextInputLayout folderNameLayout, FileBrowserViewModel.CreateFolderCallback callback, FileBrowserViewModel viewModel) {
        String folderName = folderNameEditText.getText().toString().trim();
        if (folderName.isEmpty()) {
            folderNameLayout.setError("Folder name is required");
            return;
        }

        folderNameLayout.setError(null);
        viewModel.createFolder(folderName, callback);
        dialog.dismiss();
    }

    private static int getSearchType(RadioGroup searchTypeRadioGroup) {
        int selectedId = searchTypeRadioGroup.getCheckedRadioButtonId();
        if (selectedId == R.id.radio_files) return 1; // Files only
        if (selectedId == R.id.radio_folders) return 2; // Folders only
        return 0; // All items
    }

    private static void setupDialogFocus(Activity activity, TextInputEditText editText) {
        View currentFocus = activity.getCurrentFocus();
        if (currentFocus != null && currentFocus != editText) {
            currentFocus.clearFocus();
        }
        editText.requestFocus();
        KeyboardUtils.showKeyboard(activity, editText);
    }
}
