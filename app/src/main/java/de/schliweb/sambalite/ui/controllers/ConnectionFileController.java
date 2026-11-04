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

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.data.connectionfile.ConnectionFile;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.ui.MainViewModel;
import de.schliweb.sambalite.util.EnhancedUIUtils;
import de.schliweb.sambalite.util.LogUtils;
import java.util.ArrayList;
import java.util.List;

/**
 * Import and export of connection files on the main screen (issue #48): the system file pickers,
 * the import preview, the export selection and the result messages. The file work itself runs in
 * {@link MainViewModel}, which also keeps the preview and the pending export across rotations.
 */
public final class ConnectionFileController {

  /** Runs an action once the user passed the app's access protection, if that is turned on. */
  public interface AccessGate {
    void runProtected(@NonNull Runnable action);
  }

  private static final String TAG = "ConnectionFileController";

  private final AppCompatActivity activity;
  private final MainViewModel viewModel;
  private final AccessGate accessGate;
  private final ActivityResultLauncher<String[]> openLauncher;
  private final ActivityResultLauncher<String> createLauncher;

  @Nullable private AlertDialog importDialog;

  /** Must be created in {@code onCreate}, because it registers activity result launchers. */
  public ConnectionFileController(
      @NonNull AppCompatActivity activity,
      @NonNull MainViewModel viewModel,
      @NonNull AccessGate accessGate) {
    this.activity = activity;
    this.viewModel = viewModel;
    this.accessGate = accessGate;
    this.openLauncher =
        activity.registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
              if (uri != null) {
                viewModel.readConnectionFile(activity.getContentResolver(), uri);
              }
            });
    this.createLauncher =
        activity.registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"),
            uri -> viewModel.writePendingExport(activity.getContentResolver(), uri));

    viewModel.getImportPreview().observe(activity, this::showImportPreview);
    viewModel.getConnectionFileMessage().observe(activity, this::showMessage);
  }

  /** Lets the user pick a connection file. */
  public void startImport() {
    // Connection files arrive by mail or messenger with all kinds of MIME types; the parser
    // rejects anything that is not one
    openLauncher.launch(new String[] {"*/*"});
  }

  /** Lets the user choose connections to export, behind the access protection if it is on. */
  public void startExport() {
    List<SmbConnection> connections = viewModel.getConnections().getValue();
    if (connections == null || connections.isEmpty()) {
      EnhancedUIUtils.showInfo(activity, activity.getString(R.string.export_no_connections));
      return;
    }
    accessGate.runProtected(() -> showExportSelection(connections));
  }

  /** Closes an open preview without forgetting it, so the recreated activity shows it again. */
  public void onDestroy() {
    if (importDialog != null) {
      importDialog.setOnCancelListener(null);
      importDialog.dismiss();
      importDialog = null;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Import

  private void showImportPreview(@Nullable MainViewModel.ImportPreview preview) {
    if (preview == null || (importDialog != null && importDialog.isShowing())) return;

    View view = LayoutInflater.from(activity).inflate(R.layout.dialog_connection_file, null);
    ((TextView) view.findViewById(R.id.file_dialog_message))
        .setText(R.string.import_select_connections);
    if (preview.result.containsPasswords()) {
      TextView warning = view.findViewById(R.id.file_dialog_warning);
      warning.setText(R.string.import_passwords_warning);
      warning.setVisibility(View.VISIBLE);
    }

    LinearLayout list = view.findViewById(R.id.file_dialog_list);
    List<ConnectionFile.Entry> entries = preview.result.entries;
    for (int i = 0; i < entries.size(); i++) {
      final int index = i;
      MaterialCheckBox box = new MaterialCheckBox(activity);
      box.setText(describe(entries.get(i).connection, preview.alreadySaved[i]));
      box.setChecked(preview.selected[i]);
      box.setOnCheckedChangeListener((b, checked) -> preview.selected[index] = checked);
      list.addView(box);
    }

    if (!preview.result.skipped.isEmpty()) {
      TextView skipped = view.findViewById(R.id.file_dialog_skipped);
      skipped.setText(describeSkipped(preview.result.skipped));
      skipped.setVisibility(View.VISIBLE);
    }

    MaterialAlertDialogBuilder builder =
        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.action_import_connections)
            .setView(view)
            .setNegativeButton(R.string.cancel, (d, w) -> finishImport(null));
    if (!entries.isEmpty()) {
      builder.setPositiveButton(R.string.import_button, (d, w) -> finishImport(preview));
    }
    importDialog = builder.create();
    importDialog.setOnCancelListener(d -> finishImport(null));
    importDialog.show();
  }

  private void finishImport(@Nullable MainViewModel.ImportPreview preview) {
    importDialog = null;
    viewModel.clearImportPreview();
    if (preview == null) return;
    List<SmbConnection> selected = new ArrayList<>();
    for (int i = 0; i < preview.selected.length; i++) {
      if (preview.selected[i]) selected.add(preview.result.entries.get(i).connection);
    }
    LogUtils.i(TAG, "Importing " + selected.size() + " connections");
    if (!selected.isEmpty()) viewModel.importConnections(selected);
  }

  private String describe(SmbConnection connection, boolean alreadySaved) {
    StringBuilder text = new StringBuilder(connection.getName()).append('\n');
    text.append(connection.getServer());
    if (connection.getPort() != SmbConnection.DEFAULT_PORT) {
      text.append(':').append(connection.getPort());
    }
    if (connection.getShare() != null && !connection.getShare().isEmpty()) {
      text.append('/').append(connection.getShare());
    }
    if (connection.isCredentialsRequired()) {
      text.append(" · ").append(activity.getString(R.string.connection_credentials_needed));
    }
    if (alreadySaved) {
      text.append(" · ").append(activity.getString(R.string.import_already_saved));
    }
    return text.toString();
  }

  private String describeSkipped(List<ConnectionFile.SkippedEntry> skipped) {
    StringBuilder text = new StringBuilder(activity.getString(R.string.import_skipped_header));
    for (ConnectionFile.SkippedEntry entry : skipped) {
      String reason = activity.getString(reasonFor(entry.error));
      text.append('\n')
          .append(
              entry.name != null
                  ? activity.getString(
                      R.string.import_skipped_entry, entry.position, entry.name, reason)
                  : activity.getString(
                      R.string.import_skipped_entry_unnamed, entry.position, reason));
    }
    return text.toString();
  }

  private static int reasonFor(ConnectionFile.EntryError error) {
    switch (error) {
      case INVALID_SERVER:
        return R.string.import_error_invalid_server;
      case INVALID_PORT:
        return R.string.import_error_invalid_port;
      case INVALID_SHARE:
        return R.string.invalid_share_name_title;
      case MISSING_SERVER:
      default:
        return R.string.import_error_missing_server;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Export

  private void showExportSelection(List<SmbConnection> connections) {
    View view = LayoutInflater.from(activity).inflate(R.layout.dialog_connection_file, null);
    ((TextView) view.findViewById(R.id.file_dialog_message))
        .setText(R.string.export_select_connections);
    MaterialCheckBox usernames = view.findViewById(R.id.file_dialog_option);
    usernames.setText(R.string.export_include_usernames);
    usernames.setVisibility(View.VISIBLE);

    LinearLayout list = view.findViewById(R.id.file_dialog_list);
    List<MaterialCheckBox> boxes = new ArrayList<>();
    for (SmbConnection connection : connections) {
      MaterialCheckBox box = new MaterialCheckBox(activity);
      box.setText(describe(connection, false));
      box.setChecked(true);
      list.addView(box);
      boxes.add(box);
    }

    new MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.action_export_connections)
        .setView(view)
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(
            R.string.export_button,
            (d, w) -> {
              List<String> ids = new ArrayList<>();
              for (int i = 0; i < boxes.size(); i++) {
                if (boxes.get(i).isChecked()) ids.add(connections.get(i).getId());
              }
              if (ids.isEmpty()) return;
              viewModel.setPendingExport(ids, usernames.isChecked());
              createLauncher.launch(ConnectionFile.DEFAULT_FILE_NAME);
            })
        .show();
  }

  // ---------------------------------------------------------------------------------------------
  // Messages

  private void showMessage(@Nullable MainViewModel.ConnectionFileMessage message) {
    if (message == null) return;
    viewModel.clearConnectionFileMessage();
    if (message.pluralsRes != 0) {
      String text =
          activity
              .getResources()
              .getQuantityString(message.pluralsRes, message.count, message.count);
      if (message.credentialsNeeded > 0) {
        text += " " + activity.getString(R.string.import_credentials_hint);
      }
      EnhancedUIUtils.showInfo(activity, text);
    } else if (message.textRes == R.string.import_no_connections) {
      EnhancedUIUtils.showInfo(activity, activity.getString(message.textRes));
    } else {
      EnhancedUIUtils.showError(activity, activity.getString(message.textRes, message.detail));
    }
  }
}
