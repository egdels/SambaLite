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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.robolectric.Shadows.shadowOf;

import android.content.DialogInterface;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.sync.SyncConfig;
import de.schliweb.sambalite.ui.FileListViewModel;
import de.schliweb.sambalite.ui.SearchViewModel;
import de.schliweb.sambalite.ui.operations.FileOperationsViewModel;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

/**
 * The sync setup and edit dialogs share the picked local folder and the id of the config being
 * edited through the app-wide {@link FileBrowserUIState}. A folder picked in a cancelled dialog or
 * an edit that was rejected must not leak into the next setup dialog.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = de.schliweb.sambalite.SambaLiteApp.class)
public class SyncSetupDialogStateTest {

  private static final Uri OLD_FOLDER =
      Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FOld");

  private FileBrowserUIState uiState;
  private DialogController dialogController;

  @Before
  public void setUp() {
    ActivityController<AppCompatActivity> controller =
        Robolectric.buildActivity(AppCompatActivity.class);
    controller.get().setTheme(R.style.Theme_SambaLite);
    AppCompatActivity activity = controller.setup().get();
    uiState = new FileBrowserUIState();
    dialogController =
        new DialogController(
            activity,
            mock(FileListViewModel.class),
            mock(FileOperationsViewModel.class),
            mock(SearchViewModel.class),
            uiState);
  }

  @Test
  public void setupDialog_startsWithoutFolderOrEditFromAnEarlierDialog() {
    uiState.setSyncFolderUri(OLD_FOLDER);
    uiState.setSyncFolderDisplayName("Old");
    uiState.setEditingSyncConfigId("config-being-edited");

    dialogController.showSyncSetupDialog("QA/M3");

    assertNull(uiState.getSyncFolderUri());
    assertNull(uiState.getSyncFolderDisplayName());
    assertNull(uiState.getEditingSyncConfigId());
  }

  @Test
  public void setupDialog_cancelForgetsThePickedFolder() {
    dialogController.showSyncSetupDialog("QA/M2");
    // The user picks a local folder, then cancels the dialog.
    uiState.setSyncFolderUri(OLD_FOLDER);
    uiState.setSyncFolderDisplayName("Old");

    AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
    dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
    shadowOf(Looper.getMainLooper()).idle();

    assertNull(uiState.getSyncFolderUri());
    assertNull(uiState.getSyncFolderDisplayName());
  }

  @Test
  public void editDialog_keepsTheConfigFolderUntilCancelled() {
    SyncConfig config = new SyncConfig();
    config.setId("edit-id");
    config.setRemotePath("QA/Down");
    config.setLocalFolderUri(OLD_FOLDER.toString());
    config.setLocalFolderDisplayName("Old");
    uiState.setEditingSyncConfigId("edit-id");

    dialogController.showSyncEditDialog(config);
    assertEquals(OLD_FOLDER, uiState.getSyncFolderUri());
    assertEquals("edit-id", uiState.getEditingSyncConfigId());

    AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
    dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
    shadowOf(Looper.getMainLooper()).idle();

    assertNull(uiState.getSyncFolderUri());
    assertNull(uiState.getEditingSyncConfigId());
  }

  @Test
  public void dialogs_showOneTitleAndLabelledSwitches() {
    dialogController.showSyncSetupDialog("QA/M2");
    AlertDialog setup = (AlertDialog) ShadowDialog.getLatestDialog();
    assertEquals(
        "Set Up Folder Sync",
        ((TextView) setup.findViewById(R.id.sync_dialog_title)).getText().toString());
    // No second title above the layout's own header.
    View alertTitle = setup.findViewById(androidx.appcompat.R.id.alertTitle);
    assertTrue(alertTitle == null || !alertTitle.isShown());
    assertEquals(
        "WiFi only",
        setup.findViewById(R.id.sync_wifi_only_switch).getContentDescription().toString());
    assertEquals(
        "Mirror mode",
        setup.findViewById(R.id.sync_mirror_switch).getContentDescription().toString());
    setup.dismiss();

    SyncConfig config = new SyncConfig();
    config.setId("edit-id");
    config.setRemotePath("QA/Down");
    dialogController.showSyncEditDialog(config);
    AlertDialog edit = (AlertDialog) ShadowDialog.getLatestDialog();
    assertEquals(
        "Edit Sync", ((TextView) edit.findViewById(R.id.sync_dialog_title)).getText().toString());
  }
}
