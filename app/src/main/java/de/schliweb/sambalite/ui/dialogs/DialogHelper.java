/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui.dialogs;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.util.KeyboardUtils;
import de.schliweb.sambalite.util.LogUtils;

/**
 * Helper class for creating and managing dialogs in FileBrowserActivity. Reduces complexity by
 * centralizing dialog creation logic.
 */
public class DialogHelper {

  /**
   * This method has been replaced by the implementation in
   * DialogController.showCreateFolderDialog() which uses DialogHelper.showInputDialog() and
   * FileOperationsViewModel.createFolder().
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
  public static void showToast(@NonNull Context context, @NonNull String message) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
  }

  /**
   * Shows an error dialog.
   *
   * @param context The context
   * @param title The dialog title
   * @param message The error message
   */
  public static void showErrorDialog(
      @NonNull Context context, @NonNull String title, @NonNull String message) {
    new MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(android.R.string.ok, null)
        .show();
  }

  /**
   * Shows a confirmation dialog.
   *
   * @param context The context
   * @param title The dialog title
   * @param message The dialog message
   * @param listener The listener for the positive button click
   */
  public static void showConfirmationDialog(
      @NonNull Context context,
      @NonNull String title,
      @NonNull String message,
      @Nullable DialogInterface.OnClickListener listener) {
    new MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(android.R.string.ok, listener)
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  /**
   * Shows a dialog for renaming a file.
   *
   * @param context The context
   * @param fileName The current file name
   * @param callback The callback for the new name
   */
  public static void showRenameDialog(
      @NonNull Context context, @NonNull String fileName, @Nullable RenameCallback callback) {
    View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_rename_file, null);
    TextInputEditText fileNameEditText = dialogView.findViewById(R.id.file_name_edit_text);
    fileNameEditText.setText(fileName);

    AlertDialog dialog =
        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.rename_dialog_title)
            .setView(dialogView)
            .setPositiveButton(R.string.rename, null)
            .setNegativeButton(R.string.cancel, null)
            .create();

    dialog.setOnShowListener(
        d -> {
          dialog
              .getButton(AlertDialog.BUTTON_POSITIVE)
              .setOnClickListener(
                  v -> {
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

    dialog.setOnDismissListener(
        d -> {
          fileNameEditText.clearFocus();
          if (context instanceof Activity) {
            Activity activity = (Activity) context;
            KeyboardUtils.hideKeyboard(activity);
            // Post a delayed hide to handle devices where keyboard reappears after dismiss
            activity
                .getWindow()
                .getDecorView()
                .postDelayed(
                    () -> {
                      KeyboardUtils.hideKeyboard(activity);
                    },
                    100);
          }
        });

    dialog.show();
    if (context instanceof Activity) {
      setupDialogFocus((Activity) context, fileNameEditText);
    }
  }

  /**
   * Shows a dialog for entering text.
   *
   * @param context The context
   * @param title The dialog title
   * @param message The dialog message
   * @param hint The input hint
   * @param callback The callback for the entered text
   */
  public static void showInputDialog(
      @NonNull Context context,
      @NonNull String title,
      @NonNull String message,
      @NonNull String hint,
      @Nullable InputCallback callback) {
    LogUtils.d("DialogHelper", "Showing input dialog");

    // Create an EditText with padding
    final EditText input = new EditText(context);
    input.setHint(hint);

    // Add padding to the EditText
    int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
    input.setPadding(padding, padding, padding, padding);

    // Create the dialog
    AlertDialog dialog =
        new MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();

    // Set up the button click listener
    dialog.setOnShowListener(
        d -> {
          dialog
              .getButton(AlertDialog.BUTTON_POSITIVE)
              .setOnClickListener(
                  v -> {
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

    dialog.setOnDismissListener(
        d -> {
          input.clearFocus();
          if (context instanceof Activity) {
            Activity activity = (Activity) context;
            KeyboardUtils.hideKeyboard(activity);
            // Post a delayed hide to handle devices where keyboard reappears after dismiss
            activity
                .getWindow()
                .getDecorView()
                .postDelayed(
                    () -> {
                      KeyboardUtils.hideKeyboard(activity);
                    },
                    100);
          }
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
   * @param context The context
   * @param callback The callback for the search parameters
   */
  public static void showSearchDialog(@NonNull Context context, @Nullable SearchCallback callback) {
    LogUtils.d("DialogHelper", "Showing search dialog");

    // Create a simple search dialog with EditText and options
    View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_search, null);

    // If the layout exists, use it; otherwise, create a simple dialog
    if (dialogView != null) {
      try {
        TextInputEditText searchQueryEditText =
            dialogView.findViewById(R.id.search_query_edit_text);
        TextInputLayout searchQueryLayout = dialogView.findViewById(R.id.search_query_layout);
        RadioGroup searchTypeRadioGroup = dialogView.findViewById(R.id.search_type_radio_group);
        CompoundButton includeSubfoldersCheckbox =
            dialogView.findViewById(R.id.include_subfolders_checkbox);

        if (searchQueryEditText != null
            && searchTypeRadioGroup != null
            && includeSubfoldersCheckbox != null) {
          // All views found, create the dialog with the layout
          AlertDialog dialog =
              new MaterialAlertDialogBuilder(context)
                  .setView(dialogView)
                  .setPositiveButton(R.string.search, null)
                  .setNegativeButton(R.string.cancel, null)
                  .create();

          dialog.setOnShowListener(
              d -> {
                dialog
                    .getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(
                        v -> {
                          String query = searchQueryEditText.getText().toString().trim();
                          if (query.isEmpty()) {
                            searchQueryLayout.setError(
                                context.getString(R.string.search_query_hint));
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

          dialog.setOnDismissListener(
              d -> {
                searchQueryEditText.clearFocus();
                if (context instanceof Activity) {
                  Activity activity = (Activity) context;
                  KeyboardUtils.hideKeyboard(activity);
                  activity
                      .getWindow()
                      .getDecorView()
                      .postDelayed(
                          () -> {
                            KeyboardUtils.hideKeyboard(activity);
                          },
                          100);
                }
              });

          dialog.show();
          if (dialog.getWindow() != null) {
            //noinspection deprecation
            dialog
                .getWindow()
                .setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
          }
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

    AlertDialog dialog =
        new MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.search_files))
            .setView(input)
            .setPositiveButton(
                android.R.string.ok,
                (d, which) -> {
                  String query = input.getText().toString().trim();
                  if (!query.isEmpty()) {
                    // Default to searching all files with no subfolders
                    callback.onSearchRequested(query, 0, false);
                  }
                })
            .setNegativeButton(android.R.string.cancel, null)
            .create();

    dialog.show();
    input.requestFocus();
    if (context instanceof Activity) {
      KeyboardUtils.showKeyboard(context, input);
    }
  }

  /**
   * Shows a dialog when no target folder is set for sharing.
   *
   * @param context The context
   * @param onSelectFolder Callback when user wants to select a folder
   * @param onCancel Callback when user cancels
   */
  public static void showNeedsTargetFolderDialog(
      @NonNull Context context, @Nullable Runnable onSelectFolder, @Nullable Runnable onCancel) {
    LogUtils.d("DialogHelper", "Showing needs target folder dialog");
    View dialogView =
        LayoutInflater.from(context).inflate(R.layout.dialog_share_needs_target, null);
    new MaterialAlertDialogBuilder(context)
        .setView(dialogView)
        .setPositiveButton(
            R.string.share_needs_target_folder_select, (dialog, which) -> onSelectFolder.run())
        .setNegativeButton(R.string.cancel, (dialog, which) -> onCancel.run())
        .setCancelable(false)
        .show();
  }

  /**
   * Shows a dialog to confirm uploading shared files.
   *
   * @param context The context
   * @param fileCount Number of files to upload
   * @param targetFolder Target folder path
   * @param onUpload Callback when user confirms upload
   * @param onCancel Callback when user cancels
   */
  public static void showShareUploadConfirmationDialog(
      @NonNull Context context,
      int fileCount,
      @NonNull String targetFolder,
      @NonNull Runnable onUpload,
      @NonNull Runnable onCancel) {
    showShareUploadConfirmationDialog(context, fileCount, targetFolder, onUpload, null, onCancel);
  }

  /**
   * Shows a dialog to confirm uploading shared files with an optional folder change button.
   *
   * @param context The context
   * @param fileCount Number of files to upload
   * @param targetFolder Target folder path
   * @param onUpload Callback when user confirms upload
   * @param onChangeFolder Callback when user wants to change the target folder (may be null)
   * @param onCancel Callback when user cancels
   */
  public static void showShareUploadConfirmationDialog(
      @NonNull Context context,
      int fileCount,
      @NonNull String targetFolder,
      @NonNull Runnable onUpload,
      @NonNull Runnable onChangeFolder,
      @NonNull Runnable onCancel) {
    LogUtils.d(
        "DialogHelper", "Showing share upload confirmation dialog for " + fileCount + " files");
    View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_share_upload, null);
    android.widget.TextView messageView = dialogView.findViewById(R.id.share_upload_message);
    messageView.setText(
        context
            .getResources()
            .getQuantityString(
                R.plurals.share_upload_full_message, fileCount, fileCount, targetFolder));
    MaterialAlertDialogBuilder builder =
        new MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .setPositiveButton(R.string.share_upload_select, (dialog, which) -> onUpload.run())
            .setNegativeButton(R.string.cancel, (dialog, which) -> onCancel.run())
            .setCancelable(false);
    if (onChangeFolder != null) {
      builder.setNeutralButton(
          R.string.share_change_folder, (dialog, which) -> onChangeFolder.run());
    }
    builder.show();
  }

  /**
   * Shows a dialog after successful uploads with option to view uploaded files.
   *
   * @param context The context
   * @param uploadedCount Number of successfully uploaded files
   * @param totalCount Total number of files attempted
   * @param failedCount Number of failed uploads
   * @param onViewFiles Callback when user wants to view uploaded files
   * @param onClose Callback when user wants to close
   */
  public static void showUploadCompleteDialog(
      @NonNull Context context,
      int uploadedCount,
      int totalCount,
      int failedCount,
      @NonNull Runnable onViewFiles,
      @NonNull Runnable onClose) {
    LogUtils.d("DialogHelper", "Showing upload complete dialog");

    String message =
        context
            .getResources()
            .getQuantityString(
                R.plurals.upload_complete_message, uploadedCount, uploadedCount, totalCount);
    if (failedCount > 0) {
      message +=
          " "
              + context
                  .getResources()
                  .getQuantityString(R.plurals.upload_some_failed, failedCount, failedCount);
    }

    View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_upload_complete, null);
    android.widget.TextView messageView = dialogView.findViewById(R.id.upload_complete_message);
    messageView.setText(message);
    new MaterialAlertDialogBuilder(context)
        .setView(dialogView)
        .setPositiveButton(R.string.view_uploaded_files, (dialog, which) -> onViewFiles.run())
        .setNegativeButton(R.string.close, (dialog, which) -> onClose.run())
        .setCancelable(false)
        .show();
  }

  /**
   * Shows a dialog when a file already exists during upload.
   *
   * @param context The context
   * @param fileName Name of the existing file
   * @param onOverwrite Callback when user chooses to overwrite
   * @param onCancel Callback when user cancels
   */
  public static void showFileExistsDialog(
      @NonNull Context context,
      @NonNull String fileName,
      @Nullable Runnable onOverwrite,
      @Nullable Runnable onCancel) {
    LogUtils.d("DialogHelper", "Showing file exists dialog for: " + fileName);
    View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_file_exists, null);
    android.widget.TextView messageView = dialogView.findViewById(R.id.file_exists_message);
    messageView.setText(context.getString(R.string.file_exists_message, fileName));
    AlertDialog dialog =
        new MaterialAlertDialogBuilder(context).setView(dialogView).setCancelable(false).create();
    dialogView
        .findViewById(R.id.file_exists_overwrite_button)
        .setOnClickListener(
            v -> {
              dialog.dismiss();
              if (onOverwrite != null) onOverwrite.run();
            });
    dialogView
        .findViewById(R.id.file_exists_cancel_button)
        .setOnClickListener(
            v -> {
              dialog.dismiss();
              if (onCancel != null) onCancel.run();
            });
    dialog.show();
  }

  /**
   * Shows a dialog listing files that already exist on the server, allowing the user to
   * individually deselect files they do not want to overwrite.
   *
   * @param context The context
   * @param fileNames List of file names that already exist
   * @param onConfirm Callback with the list of selected (to-overwrite) file names
   * @param onCancel Callback when user cancels or skips all
   */
  public static void showMultiFileExistsDialog(
      @NonNull Context context,
      @NonNull java.util.List<String> fileNames,
      @NonNull java.util.function.Consumer<java.util.List<String>> onConfirm,
      @Nullable Runnable onCancel) {
    LogUtils.d(
        "DialogHelper", "Showing multi file exists dialog for " + fileNames.size() + " files");
    View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_file_exists_multi, null);

    android.widget.TextView summaryView = dialogView.findViewById(R.id.files_exist_summary);
    summaryView.setText(
        context.getString(R.string.files_exist_summary, fileNames.size(), fileNames.size()));

    android.widget.LinearLayout listContainer = dialogView.findViewById(R.id.files_exist_list);
    android.widget.CheckBox selectAllCheckBox =
        dialogView.findViewById(R.id.files_exist_select_all);

    // Create checkboxes for each file
    java.util.List<android.widget.CheckBox> checkBoxes = new java.util.ArrayList<>();
    for (String name : fileNames) {
      com.google.android.material.checkbox.MaterialCheckBox cb =
          new com.google.android.material.checkbox.MaterialCheckBox(context);
      cb.setText(name);
      cb.setChecked(true);
      cb.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
      cb.setPadding(0, 4, 0, 4);
      listContainer.addView(cb);
      checkBoxes.add(cb);
    }

    // Select-all toggle logic
    selectAllCheckBox.setOnCheckedChangeListener(
        (buttonView, isChecked) -> {
          for (android.widget.CheckBox cb : checkBoxes) {
            cb.setChecked(isChecked);
          }
        });

    // Update select-all state when individual checkboxes change
    for (android.widget.CheckBox cb : checkBoxes) {
      cb.setOnCheckedChangeListener(
          (buttonView, isChecked) -> {
            boolean allChecked = true;
            for (android.widget.CheckBox c : checkBoxes) {
              if (!c.isChecked()) {
                allChecked = false;
                break;
              }
            }
            // Temporarily remove listener to avoid recursion
            selectAllCheckBox.setOnCheckedChangeListener(null);
            selectAllCheckBox.setChecked(allChecked);
            selectAllCheckBox.setOnCheckedChangeListener(
                (bv, ic) -> {
                  for (android.widget.CheckBox c2 : checkBoxes) {
                    c2.setChecked(ic);
                  }
                });
          });
    }

    AlertDialog dialog =
        new MaterialAlertDialogBuilder(context).setView(dialogView).setCancelable(false).create();

    dialogView
        .findViewById(R.id.files_exist_overwrite_button)
        .setOnClickListener(
            v -> {
              java.util.List<String> selected = new java.util.ArrayList<>();
              for (int i = 0; i < checkBoxes.size(); i++) {
                if (checkBoxes.get(i).isChecked()) {
                  selected.add(fileNames.get(i));
                }
              }
              dialog.dismiss();
              onConfirm.accept(selected);
            });

    dialogView
        .findViewById(R.id.files_exist_skip_button)
        .setOnClickListener(
            v -> {
              dialog.dismiss();
              if (onCancel != null) onCancel.run();
            });

    dialog.show();
  }

  /** Callback for rename operations. */
  public interface RenameCallback {
    /**
     * Called when a new name is entered.
     *
     * @param newName The new name
     */
    void onNameEntered(@NonNull String newName);
  }

  /** Callback for input operations. */
  public interface InputCallback {
    /**
     * Called when text is entered.
     *
     * @param text The entered text
     */
    void onTextEntered(@NonNull String text);
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
}
