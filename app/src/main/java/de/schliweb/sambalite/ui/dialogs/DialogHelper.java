package de.schliweb.sambalite.ui.dialogs;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.util.KeyboardUtils;
import de.schliweb.sambalite.util.LogUtils;

/**
 * Helper class for creating and managing dialogs in FileBrowserActivity.
 * Reduces complexity by centralizing dialog creation logic.
 */
public class DialogHelper {


    /**
     * This method has been replaced by the implementation in DialogController.showCreateFolderDialog()
     * which uses DialogHelper.showInputDialog() and FileOperationsViewModel.createFolder().
     */

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

    /**
     * Shows a toast message.
     *
     * @param context The context
     * @param message The message to show
     */
    public static void showToast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    /**
     * Shows an error dialog.
     *
     * @param context The context
     * @param title   The dialog title
     * @param message The error message
     */
    public static void showErrorDialog(Context context, String title, String message) {
        new MaterialAlertDialogBuilder(context).setTitle(title).setMessage(message).setPositiveButton(android.R.string.ok, null).show();
    }

    /**
     * Shows a confirmation dialog.
     *
     * @param context  The context
     * @param title    The dialog title
     * @param message  The dialog message
     * @param listener The listener for the positive button click
     */
    public static void showConfirmationDialog(Context context, String title, String message, DialogInterface.OnClickListener listener) {
        new MaterialAlertDialogBuilder(context).setTitle(title).setMessage(message).setPositiveButton(android.R.string.yes, listener).setNegativeButton(android.R.string.no, null).show();
    }

    /**
     * Shows a dialog for renaming a file.
     *
     * @param context  The context
     * @param fileName The current file name
     * @param callback The callback for the new name
     */
    public static void showRenameDialog(Context context, String fileName, RenameCallback callback) {
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_rename_file, null);
        TextInputEditText fileNameEditText = dialogView.findViewById(R.id.file_name_edit_text);
        fileNameEditText.setText(fileName);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context).setTitle(R.string.rename_dialog_title).setView(dialogView).setPositiveButton(R.string.rename, null).setNegativeButton(R.string.cancel, null).create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String newName = fileNameEditText.getText().toString().trim();
                if (newName.isEmpty()) {
                    fileNameEditText.setError(context.getString(R.string.error_name_required));
                    return;
                }

                if (context instanceof Activity) {
                    KeyboardUtils.hideKeyboard((Activity) context);
                }
                callback.onNameEntered(newName);
                dialog.dismiss();
            });
        });

        dialog.show();
        if (context instanceof Activity) {
            setupDialogFocus((Activity) context, fileNameEditText);
        }
    }

    /**
     * Shows a dialog for entering text.
     *
     * @param context  The context
     * @param title    The dialog title
     * @param message  The dialog message
     * @param hint     The input hint
     * @param callback The callback for the entered text
     */
    public static void showInputDialog(Context context, String title, String message, String hint, InputCallback callback) {
        LogUtils.d("DialogHelper", "Showing input dialog");

        // Create an EditText with padding
        final EditText input = new EditText(context);
        input.setHint(hint);

        // Add padding to the EditText
        int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding, padding, padding);

        // Create the dialog
        AlertDialog dialog = new MaterialAlertDialogBuilder(context).setTitle(title).setMessage(message).setView(input).setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create();

        // Set up the button click listener
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String text = input.getText().toString().trim();
                if (text.isEmpty()) {
                    input.setError("Input is required");
                    return;
                }

                if (context instanceof Activity) {
                    KeyboardUtils.hideKeyboard((Activity) context);
                }
                callback.onTextEntered(text);
                dialog.dismiss();
            });
        });

        // Show the dialog
        dialog.show();

        // Focus the input field
        input.requestFocus();
        if (context instanceof Activity) {
            KeyboardUtils.showKeyboard(context, input);
        }
    }

    /**
     * Shows a dialog for searching files.
     *
     * @param context  The context
     * @param callback The callback for the search parameters
     */
    public static void showSearchDialog(Context context, SearchCallback callback) {
        LogUtils.d("DialogHelper", "Showing search dialog");

        // Create a simple search dialog with EditText and options
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_search, null);

        // If the layout exists, use it; otherwise, create a simple dialog
        if (dialogView != null) {
            try {
                TextInputEditText searchQueryEditText = dialogView.findViewById(R.id.search_query_edit_text);
                TextInputLayout searchQueryLayout = dialogView.findViewById(R.id.search_query_layout);
                RadioGroup searchTypeRadioGroup = dialogView.findViewById(R.id.search_type_radio_group);
                CheckBox includeSubfoldersCheckbox = dialogView.findViewById(R.id.include_subfolders_checkbox);

                if (searchQueryEditText != null && searchTypeRadioGroup != null && includeSubfoldersCheckbox != null) {
                    // All views found, create the dialog with the layout
                    AlertDialog dialog = new MaterialAlertDialogBuilder(context).setTitle(R.string.search_files).setView(dialogView).setPositiveButton(R.string.search, null).setNegativeButton(R.string.cancel, null).create();

                    dialog.setOnShowListener(d -> {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                            String query = searchQueryEditText.getText().toString().trim();
                            if (query.isEmpty()) {
                                searchQueryLayout.setError(context.getString(R.string.search_query_hint));
                                return;
                            }

                            searchQueryLayout.setError(null);

                            int searchType = getSearchType(searchTypeRadioGroup);
                            boolean includeSubfolders = includeSubfoldersCheckbox.isChecked();

                            if (context instanceof Activity) {
                                KeyboardUtils.hideKeyboard((Activity) context);
                            }
                            callback.onSearchRequested(query, searchType, includeSubfolders);
                            dialog.dismiss();
                        });
                    });

                    dialog.show();
                    if (context instanceof Activity && searchQueryEditText != null) {
                        setupDialogFocus((Activity) context, searchQueryEditText);
                    }
                    return;
                }
            } catch (Exception e) {
                LogUtils.e("DialogHelper", "Error creating search dialog with layout: " + e.getMessage());
            }
        }

        // Fallback to a simple dialog if the layout doesn't exist or has issues
        final EditText input = new EditText(context);
        input.setHint("Enter search query");

        // Add padding to the EditText
        int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding, padding, padding);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context).setTitle("Search Files").setView(input).setPositiveButton(android.R.string.ok, (d, which) -> {
            String query = input.getText().toString().trim();
            if (!query.isEmpty()) {
                // Default to searching all files with no subfolders
                callback.onSearchRequested(query, 0, false);
            }
        }).setNegativeButton(android.R.string.cancel, null).create();

        dialog.show();
        input.requestFocus();
        if (context instanceof Activity) {
            KeyboardUtils.showKeyboard(context, input);
        }
    }

    /**
     * Callback for rename operations.
     */
    public interface RenameCallback {
        /**
         * Called when a new name is entered.
         *
         * @param newName The new name
         */
        void onNameEntered(String newName);
    }

    /**
     * Callback for input operations.
     */
    public interface InputCallback {
        /**
         * Called when text is entered.
         *
         * @param text The entered text
         */
        void onTextEntered(String text);
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
}
