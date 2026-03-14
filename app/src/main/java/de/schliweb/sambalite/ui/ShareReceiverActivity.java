package de.schliweb.sambalite.ui;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.SambaLiteApp;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.repository.ConnectionRepository;
import de.schliweb.sambalite.di.AppComponent;
import de.schliweb.sambalite.ui.controllers.DialogController;
import de.schliweb.sambalite.ui.controllers.FileBrowserUIState;
import de.schliweb.sambalite.ui.dialogs.DialogHelper;
import de.schliweb.sambalite.ui.utils.PreferenceUtils;
import de.schliweb.sambalite.util.LogUtils;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;

public class ShareReceiverActivity extends AppCompatActivity {
  private static final String TAG = "ShareReceiverActivity";

  @Inject ConnectionRepository connectionRepository;

  @Inject ViewModelProvider.Factory viewModelFactory;

  @Inject FileBrowserUIState uiState;

  private ShareReceiverViewModel viewModel;

  private final List<Uri> shareUris = new ArrayList<>();
  private String targetSmbFolder;

  private DialogController dialogController;

  private boolean folderChangeInProgress = false;

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // Lightweight transparent content to host confirmation dialogs if needed
    setContentView(R.layout.activity_transparent);

    // Inject dependencies
    AppComponent appComponent = ((SambaLiteApp) getApplication()).getAppComponent();
    appComponent.inject(this);

    viewModel = new ViewModelProvider(this, viewModelFactory).get(ShareReceiverViewModel.class);

    dialogController = new DialogController(this, viewModel, uiState);

    // Handle the incoming intent immediately
    handleIntent(getIntent());
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    handleIntent(intent);
  }

  private void handleIntent(Intent intent) {
    LogUtils.d(TAG, "Received intent: " + intent.getAction());

    if (Intent.ACTION_SEND.equals(intent.getAction())
        || Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
      shareUris.clear();

      if (Intent.ACTION_SEND.equals(intent.getAction())) {
        Uri uri = getParcelableExtraCompat(intent, Intent.EXTRA_STREAM);
        if (uri != null) shareUris.add(uri);
      } else {
        ArrayList<Uri> uris = getParcelableArrayListExtraCompat(intent, Intent.EXTRA_STREAM);
        if (uris != null) shareUris.addAll(uris);
      }

      // If no stream URI, check for shared text (e.g. from Notes, Chrome)
      if (shareUris.isEmpty() && Intent.ACTION_SEND.equals(intent.getAction())) {
        String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (sharedText != null && !sharedText.isEmpty()) {
          Uri textUri = createTempTextFile(sharedText, intent.getStringExtra(Intent.EXTRA_SUBJECT));
          if (textUri != null) {
            shareUris.add(textUri);
          }
        }
      }

      if (shareUris.isEmpty()) {
        finish();
        return;
      }

      String currentFolder = PreferenceUtils.getCurrentSmbFolder(this);
      LogUtils.d(TAG, "Retrieved saved folder from preferences: " + currentFolder);

      if (currentFolder == null || currentFolder.isEmpty()) {
        LogUtils.d(TAG, "No saved folder found in preferences");
        showNeedsTargetFolderDialog();
      } else {
        showShareDialog(currentFolder);
      }
    }
  }

  private void showNeedsTargetFolderDialog() {
    dialogController.showNeedsTargetFolderDialog();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (folderChangeInProgress) {
      folderChangeInProgress = false;
      String newFolder = PreferenceUtils.getCurrentSmbFolder(this);
      LogUtils.d(TAG, "Returned from folder selection, new folder: " + newFolder);
      if (newFolder != null && !newFolder.isEmpty() && !shareUris.isEmpty()) {
        showShareDialog(newFolder);
      } else {
        showNeedsTargetFolderDialog();
      }
    }
  }

  private void showShareDialog(String currentFolder) {
    LogUtils.d(TAG, "Confirming share upload for folder: " + currentFolder);
    dialogController.showShareUploadConfirmationDialog(
        shareUris.size(),
        currentFolder,
        () -> {
          try {
            startUploadsViaFileBrowser();
          } catch (IOException e) {
            LogUtils.e(TAG, "Error starting uploads: " + e.getMessage());
            DialogHelper.showErrorDialog(this, getString(R.string.upload_failed), e.getMessage());
            finish();
          }
        },
        () -> {
          // Show connection selection dialog, then open FileBrowserActivity for folder browsing
          showConnectionSelectionForFolderChange();
        },
        this::finish);
  }

  private void showConnectionSelectionForFolderChange() {
    List<SmbConnection> connections = connectionRepository.getAllConnections();
    if (connections == null || connections.isEmpty()) {
      DialogHelper.showErrorDialog(
          this, getString(R.string.share_needs_target_folder_title), "No connections available");
      return;
    }
    if (connections.size() == 1) {
      // Only one connection, go directly to file browser
      openFileBrowserForFolderChange(connections.get(0));
      return;
    }
    // Show connection picker
    String[] names = new String[connections.size()];
    for (int i = 0; i < connections.size(); i++) {
      names[i] = connections.get(i).getName();
    }
    new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
        .setTitle(R.string.share_change_folder)
        .setItems(names, (dialog, which) -> openFileBrowserForFolderChange(connections.get(which)))
        .setNegativeButton(
            R.string.cancel,
            (dialog, which) -> showShareDialog(PreferenceUtils.getCurrentSmbFolder(this)))
        .setCancelable(false)
        .show();
  }

  private void openFileBrowserForFolderChange(SmbConnection connection) {
    folderChangeInProgress = true;
    Intent intent = FileBrowserActivity.createFolderPickerIntent(this, connection.getId());
    startActivity(intent);
  }

  private SmbConnection getConnectionFromTargetPath(String smbTargetFolder) {
    String connectionName = getConnectionNameFromTargetFolder(smbTargetFolder);
    LogUtils.d(TAG, "Extracted connection name from target folder: " + connectionName);

    List<SmbConnection> connections = connectionRepository.getAllConnections();
    if (connections == null || connections.isEmpty()) return null;

    String target = connectionName == null ? "" : connectionName.trim();
    for (SmbConnection conn : connections) {
      String name = conn.getName() == null ? "" : conn.getName().trim();
      if (name.equalsIgnoreCase(target)) return conn;
    }

    if (connections.size() == 1) {
      LogUtils.w(
          TAG,
          "No match for connection '"
              + connectionName
              + "'. Falling back to the only saved connection '"
              + connections.get(0).getName()
              + "'");
      return connections.get(0);
    }
    return null;
  }

  private void startUploadsViaFileBrowser() throws IOException {
    LogUtils.d(
        TAG, "Preparing Share handoff to FileBrowserActivity for " + shareUris.size() + " items");
    targetSmbFolder = PreferenceUtils.getCurrentSmbFolder(this);
    if (targetSmbFolder == null || targetSmbFolder.isEmpty())
      throw new IOException("No target folder set");

    String connectionId = PreferenceUtils.getCurrentSmbConnectionId(this);
    if (connectionId == null || connectionId.isEmpty()) {
      SmbConnection conn = getConnectionFromTargetPath(targetSmbFolder);
      if (conn == null) throw new IOException("Connection not found for target folder");
      connectionId = conn.getId();
    }

    String directoryPath = getPathFromTargetFolder(targetSmbFolder);
    ArrayList<Uri> uris = new ArrayList<>(shareUris);

    // Propagate URI read grants to our app and the next Activity
    try {
      for (Uri u : uris) {
        grantUriPermission(getPackageName(), u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      }
    } catch (Exception e) {
      LogUtils.w(TAG, "Self-granting URI permissions failed: " + e.getMessage());
    }

    Intent intent =
        FileBrowserActivity.createIntentForShareUpload(this, connectionId, directoryPath, uris);
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    // Attach ClipData with all URIs so the permission flag applies to each
    try {
      if (!uris.isEmpty()) {
        ClipData clipData = ClipData.newUri(getContentResolver(), "shared", uris.get(0));
        for (int i = 1; i < uris.size(); i++) {
          clipData.addItem(new ClipData.Item(uris.get(i)));
        }
        intent.setClipData(clipData);
      }
    } catch (Exception e) {
      LogUtils.w(TAG, "Setting ClipData for share handoff failed: " + e.getMessage());
    }

    startActivity(intent);
    finish();
  }

  private String getConnectionNameFromTargetFolder(String targetFolder) {
    if (targetFolder == null || targetFolder.isEmpty()) return "";
    int slashIdx = targetFolder.indexOf("/");
    if (slashIdx > 0) return targetFolder.substring(0, slashIdx);
    return targetFolder;
  }

  private String getPathFromTargetFolder(String targetFolder) {
    if (targetFolder == null) return "/";
    int slashIdx = targetFolder.indexOf("/");
    if (slashIdx > 0) {
      String path = targetFolder.substring(slashIdx + 1);
      return path.isEmpty() ? "/" : path;
    }
    return "/";
  }

  private Uri createTempTextFile(String text, String subject) {
    try {
      String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
      String baseName =
          (subject != null && !subject.isEmpty())
              ? subject.replaceAll("[^a-zA-Z0-9._-]", "_")
              : "shared_text";
      String fileName = baseName + "_" + timestamp + ".txt";

      File cacheDir = new File(getCacheDir(), "shared_text");
      if (!cacheDir.exists() && !cacheDir.mkdirs()) {
        LogUtils.e(TAG, "Failed to create cache directory for shared text");
        return null;
      }

      File textFile = new File(cacheDir, fileName);
      try (OutputStreamWriter writer =
          new OutputStreamWriter(new FileOutputStream(textFile), StandardCharsets.UTF_8)) {
        writer.write(text);
      }

      LogUtils.d(TAG, "Created temp text file: " + textFile.getAbsolutePath());
      return Uri.fromFile(textFile);
    } catch (IOException e) {
      LogUtils.e(TAG, "Failed to create temp text file: " + e.getMessage());
      return null;
    }
  }

  private Uri getParcelableExtraCompat(Intent intent, String key) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      return intent.getParcelableExtra(key, Uri.class);
    } else {
      return androidx.core.content.IntentCompat.getParcelableExtra(intent, key, Uri.class);
    }
  }

  @SuppressWarnings("NonApiType")
  private ArrayList<Uri> getParcelableArrayListExtraCompat(Intent intent, String key) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      return intent.getParcelableArrayListExtra(key, Uri.class);
    } else {
      return androidx.core.content.IntentCompat.getParcelableArrayListExtra(intent, key, Uri.class);
    }
  }
}
