/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.SambaLiteApp;
import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.di.AppComponent;
import de.schliweb.sambalite.security.BiometricAuthHelper;
import de.schliweb.sambalite.sync.SyncConfig;
import de.schliweb.sambalite.sync.SyncDirection;
import de.schliweb.sambalite.sync.SyncManager;
import de.schliweb.sambalite.ui.adapters.DiscoveredServerAdapter;
import de.schliweb.sambalite.ui.adapters.SharesAdapter;
import de.schliweb.sambalite.ui.utils.LoadingIndicator;
import de.schliweb.sambalite.util.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;

/**
 * Main entry point for the SambaLite app. Displays a list of saved SMB connections and allows
 * adding new ones.
 */
public class MainActivity extends AppCompatActivity
    implements ConnectionAdapter.OnConnectionClickListener {

  @Inject ViewModelProvider.Factory viewModelFactory;

  @Inject BackgroundSmbManager backgroundSmbManager;

  private MainViewModel viewModel;
  private ConnectionAdapter adapter;
  private SyncConfigAdapter syncAdapter;
  private LoadingIndicator loadingIndicator;
  private com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton fab;
  private NetworkScanner networkScanner;
  private PreferencesManager preferencesManager;

  // Track running sync config IDs from WorkManager to apply after config reloads
  private final Set<String> currentRunningConfigIds = new HashSet<>();
  private boolean currentAnySyncRunning = false;

  // Temporary flags for share discovery to honor per-connection security during discovery
  boolean discoverRequireEncrypt = false;
  boolean discoverRequireSigning = false;

  // Felder (optional, um Erstversuch zu tracken – vermeidet aggressives "ab in die Settings")
  private static final String PREFS = "perm_prefs";
  private static final String KEY_ASKED_NOTIF_ONCE = "asked_notif_once";

  private final ActivityResultLauncher<String> requestNotifPermission =
      registerForActivityResult(
          new ActivityResultContracts.RequestPermission(),
          isGranted -> {
            if (!isGranted) {
              // Beim ersten Mal: Rationale zeigen (falls möglich) statt direkt in Settings zu
              // springen
              if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                showNotifRationale();
              } else {
                // Optional: dezente Snackbar/Dialog mit Button "In Einstellungen öffnen"
                showGoToSettingsDialog();
              }
            }
          });

  private void maybeRequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < 33) return;

    // 1) Ist die Runtime-Permission schon erteilt?
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        != PackageManager.PERMISSION_GRANTED) {

      // Schon mal gefragt? Dann ggf. Rationale – sonst direkt den System-Prompt
      boolean askedOnce =
          getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ASKED_NOTIF_ONCE, false);

      if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
        showNotifRationale(); // erklärt kurz & triggert danach request
      } else {
        // Erster Versuch: direkt anfragen (zeigt System-Dialog)
        if (!askedOnce) {
          getSharedPreferences(PREFS, MODE_PRIVATE)
              .edit()
              .putBoolean(KEY_ASKED_NOTIF_ONCE, true)
              .apply();
          requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else {
          // Wiederholt abgelehnt (oder OEM-Sonderfall): freundlich zu den Einstellungen anbieten
          showGoToSettingsDialog();
        }
      }
      return;
    }

    // 2) Permission ist erteilt -> sind App-Notifications evtl. global aus?
    if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
      // Nicht automatisch springen – erst einen Dialog mit Button anbieten:
      showGoToSettingsDialog();
    }
  }

  @android.annotation.SuppressLint("InlinedApi") // guarded by Build.VERSION.SDK_INT >= 33
  private void showNotifRationale() {
    new MaterialAlertDialogBuilder(this)
        .setTitle(R.string.permission_title_notifications)
        .setMessage(R.string.permission_explain_notifications)
        .setPositiveButton(
            android.R.string.ok,
            (d, w) -> requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS))
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void showGoToSettingsDialog() {
    new MaterialAlertDialogBuilder(this)
        .setTitle(R.string.permission_title_notifications)
        .setMessage(R.string.permission_explain_notifications)
        .setPositiveButton(R.string.open_settings, (d, w) -> openAppNotificationSettings())
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void openAppNotificationSettings() {
    Intent intent =
        new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
    startActivity(intent);
  }

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    LogUtils.d("MainActivity", "onCreate called");

    // Initialize loading indicator
    loadingIndicator = new LoadingIndicator(this);
    LogUtils.d("MainActivity", "Loading indicator initialized");

    // Initialize network scanner
    networkScanner = new NetworkScanner(this);
    LogUtils.d("MainActivity", "Network scanner initialized");

    // Initialize preferences manager
    preferencesManager = new PreferencesManager(this);
    LogUtils.d("MainActivity", "Preferences manager initialized");

    // Get the Dagger component and inject dependencies
    AppComponent appComponent = ((SambaLiteApp) getApplication()).getAppComponent();
    appComponent.inject(this);
    LogUtils.d("MainActivity", "Dependencies injected");

    // Ensure background service is running (restores foreground notification after quit+reopen)
    if (backgroundSmbManager != null) {
      backgroundSmbManager.ensureServiceRunning();
    }

    super.onCreate(savedInstanceState);

    // Configure edge-to-edge display with backward-compatible helper
    EdgeToEdge.enable(this);

    setContentView(R.layout.activity_main);
    LogUtils.d("MainActivity", "Content view set");

    maybeRequestNotificationPermission();
    // Set up the toolbar as action bar
    androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
    if (toolbar != null) {
      setSupportActionBar(toolbar);
      LogUtils.d("MainActivity", "Toolbar set as action bar");
    } else {
      LogUtils.w("MainActivity", "Toolbar not found in layout");
    }

    // Initialize ViewModel
    viewModel = new ViewModelProvider(this, viewModelFactory).get(MainViewModel.class);
    LogUtils.d("MainActivity", "ViewModel initialized");

    // Set up RecyclerView
    RecyclerView recyclerView = findViewById(R.id.connections_recycler_view);
    recyclerView.setLayoutManager(new LinearLayoutManager(this));
    adapter = new ConnectionAdapter();
    adapter.setOnConnectionClickListener(this);
    recyclerView.setAdapter(adapter);
    LogUtils.d("MainActivity", "RecyclerView and adapter set up");

    // Set up Sync RecyclerView
    RecyclerView syncRecyclerView = findViewById(R.id.sync_recycler_view);
    syncRecyclerView.setLayoutManager(new LinearLayoutManager(this));
    syncAdapter = new SyncConfigAdapter();
    syncAdapter.setOnSyncClickListener(
        new SyncConfigAdapter.OnSyncClickListener() {
          @Override
          public void onSyncClick(@NonNull SyncConfig config) {
            LogUtils.d("MainActivity", "Sync config clicked: " + config.getId());
            // For now, we only trigger sync. In future, we could open edit dialog.
          }

          @Override
          public void onOptionsClick(@NonNull View view, @NonNull SyncConfig config) {
            showSyncOptions(view, config);
          }
        });
    syncRecyclerView.setAdapter(syncAdapter);
    LogUtils.d("MainActivity", "Sync RecyclerView and adapter set up");

    // Long click functionality is used for connection options including delete
    LogUtils.d("MainActivity", "Long click functionality for connection options is already set up");

    // Set up FAB for adding new connections with enhanced animation
    fab = findViewById(R.id.fab_add_connection);
    if (fab != null) {
      fab.setOnClickListener(
          v -> {
            v.setEnabled(false);
            EnhancedUIUtils.scaleUp(v);
            showAddConnectionDialog();
            v.postDelayed(() -> v.setEnabled(true), 500);
          });
      EnhancedUIUtils.addRippleEffect(fab);
      LogUtils.d("MainActivity", "FAB set up with enhanced animations");
    } else {
      LogUtils.w("MainActivity", "FAB not found in layout");
    }

    // Get UI elements for empty state management
    View welcomeCard = findViewById(R.id.welcome_card);
    View connectionsHeader = findViewById(R.id.connections_header);
    View syncHeader = findViewById(R.id.sync_header);

    // Observe connections
    viewModel
        .getConnections()
        .observe(
            this,
            connections -> {
              LogUtils.d(
                  "MainActivity", "Connections updated: " + connections.size() + " connections");
              LogUtils.d(
                  "MainActivity",
                  "Connections list: " + (connections != null ? connections.toString() : "null"));
              adapter.setConnections(connections);
              syncAdapter.setConnections(connections);

              // Manage empty state visibility
              if (connections == null || connections.isEmpty()) {
                LogUtils.d("MainActivity", "No connections available - showing welcome card");
                if (welcomeCard != null) welcomeCard.setVisibility(View.VISIBLE);
                if (connectionsHeader != null) connectionsHeader.setVisibility(View.GONE);
              } else {
                LogUtils.d("MainActivity", "Connections available - hiding welcome card");
                if (welcomeCard != null) welcomeCard.setVisibility(View.GONE);
                if (connectionsHeader != null) connectionsHeader.setVisibility(View.VISIBLE);
              }
            });

    // Observe sync configurations
    viewModel
        .getSyncConfigs()
        .observe(
            this,
            syncConfigs -> {
              int size = syncConfigs != null ? syncConfigs.size() : 0;
              LogUtils.d("MainActivity", "Sync configs updated: " + size + " configs");
              List<SyncConfig> configs =
                  syncConfigs != null ? syncConfigs : new java.util.ArrayList<>();
              // Re-apply running state from WorkManager after config reload
              applyRunningState(configs);
              syncAdapter.setSyncConfigs(configs);

              if (size > 0) {
                if (syncHeader != null) syncHeader.setVisibility(View.VISIBLE);
              } else {
                if (syncHeader != null) syncHeader.setVisibility(View.GONE);
              }
            });

    // Observe error messages with enhanced UI feedback
    viewModel
        .getErrorMessage()
        .observe(
            this,
            errorMessage -> {
              if (errorMessage != null && !errorMessage.isEmpty()) {
                LogUtils.w("MainActivity", "Error message received: " + errorMessage);
                EnhancedUIUtils.showError(this, errorMessage);
              }
            });

    // Observe loading state with unified loading indicator
    viewModel
        .isLoading()
        .observe(
            this,
            isLoading -> {
              LogUtils.d("MainActivity", "Loading state changed: " + isLoading);
              if (isLoading) {
                loadingIndicator.show(R.string.loading_files);
              } else {
                loadingIndicator.hide();
              }
            });

    // Log performance metrics

    LogUtils.i("MainActivity", "Memory: " + SimplePerformanceMonitor.getMemoryInfo());

    // Force trigger the initial connections load for debugging
    LogUtils.d("MainActivity", "Triggering initial connections load");
    viewModel.loadConnections();

    // Battery optimization check for better background performance
    checkBatteryOptimizationOnFirstRun();

    // Debug: Check if there are already connections loaded
    if (viewModel.getConnections().getValue() != null) {
      LogUtils.d(
          "MainActivity",
          "ViewModel already has connections: " + viewModel.getConnections().getValue().size());
    } else {
      LogUtils.d("MainActivity", "ViewModel connections are null at startup");
    }

    // Check if a previous operation was cancelled due to swipe-kill
    checkForCancelledOperations();

    // Observe WorkManager for sync status to refresh UI
    WorkManager.getInstance(this)
        .getWorkInfosByTagLiveData(SyncManager.UNIQUE_WORK_NAME)
        .observe(
            this,
            workInfos -> {
              int infoCount = workInfos != null ? workInfos.size() : 0;
              LogUtils.d("MainActivity", "Received " + infoCount + " work infos from WorkManager");

              boolean anySyncRunning = false;
              Set<String> runningConfigIds = new HashSet<>();

              if (workInfos != null) {
                for (WorkInfo workInfo : workInfos) {
                  boolean isRunning = workInfo.getState() == WorkInfo.State.RUNNING;

                  LogUtils.v(
                      "MainActivity",
                      "WorkInfo: ID="
                          + workInfo.getId()
                          + ", State="
                          + workInfo.getState()
                          + ", Tags="
                          + workInfo.getTags());

                  if (isRunning) {
                    String configId = null;
                    boolean isManual = false;
                    for (String tag : workInfo.getTags()) {
                      if (tag.equals("manual_sync")) {
                        isManual = true;
                      }
                      if (tag.startsWith("config_id:")) {
                        configId = tag.substring("config_id:".length());
                      }
                    }
                    if (configId != null) {
                      runningConfigIds.add(configId);
                    } else if (!isManual) {
                      // Periodic RUNNING worker without specific config_id; affects eligible
                      // periodic configs
                      anySyncRunning = true;
                    }
                  }

                  if (workInfo.getState() == WorkInfo.State.SUCCEEDED
                      || workInfo.getState() == WorkInfo.State.FAILED) {
                    LogUtils.i(
                        "MainActivity",
                        "Sync work "
                            + workInfo.getId()
                            + " finished with state: "
                            + workInfo.getState()
                            + ". Refreshing configs.");
                    viewModel.loadSyncConfigs();
                    if (workInfo.getState() == WorkInfo.State.SUCCEEDED) {
                      EnhancedUIUtils.showInfo(
                          MainActivity.this, getString(R.string.sync_completed));
                    } else {
                      EnhancedUIUtils.showError(MainActivity.this, getString(R.string.sync_failed));
                    }
                  }

                  // Detect RETRY: state goes back to ENQUEUED while config was previously running
                  if (workInfo.getState() == WorkInfo.State.ENQUEUED) {
                    String configId = null;
                    for (String tag : workInfo.getTags()) {
                      if (tag.startsWith("config_id:")) {
                        configId = tag.substring("config_id:".length());
                      }
                    }
                    if (configId != null && currentRunningConfigIds.contains(configId)) {
                      LogUtils.i(
                          "MainActivity",
                          "Sync work "
                              + workInfo.getId()
                              + " was retried (ENQUEUED after RUNNING). Config: "
                              + configId);
                      EnhancedUIUtils.showError(
                          MainActivity.this, getString(R.string.sync_cancelled));
                    }
                  }
                }
              }

              LogUtils.d(
                  "MainActivity", "Running config IDs from WorkManager: " + runningConfigIds);

              // Store running state for re-application after config reloads
              currentRunningConfigIds.clear();
              currentRunningConfigIds.addAll(runningConfigIds);
              currentAnySyncRunning = anySyncRunning;

              // Update isRunning state in current list
              List<SyncConfig> currentConfigs = viewModel.getSyncConfigs().getValue();
              if (currentConfigs != null) {
                if (applyRunningState(currentConfigs)) {
                  LogUtils.d("MainActivity", "Notifying adapter of data change");
                  syncAdapter.notifyDataSetChanged();
                }
              }
            });
  }

  /**
   * Applies the current WorkManager running state to the given config list.
   *
   * @param configs the list of sync configurations to update
   * @return true if any config's running state was changed
   */
  private boolean applyRunningState(List<SyncConfig> configs) {
    boolean changed = false;
    for (SyncConfig config : configs) {
      boolean nowRunning;
      if (currentRunningConfigIds.isEmpty()) {
        nowRunning = currentAnySyncRunning && config.isEnabled() && config.getIntervalMinutes() > 0;
      } else {
        nowRunning = currentRunningConfigIds.contains(config.getId());
      }
      if (config.isRunning() != nowRunning) {
        LogUtils.d(
            "MainActivity",
            "Updating config "
                + config.getId()
                + " isRunning: "
                + config.isRunning()
                + " -> "
                + nowRunning);
        config.setRunning(nowRunning);
        changed = true;
      }
    }
    return changed;
  }

  /**
   * Checks SharedPreferences for persisted state from a previous swipe-kill and shows a Snackbar to
   * inform the user about cancelled operations.
   */
  private void checkForCancelledOperations() {
    try {
      SharedPreferences prefs = getSharedPreferences("swipe_kill_state", MODE_PRIVATE);
      boolean hadActiveOps = prefs.getBoolean("had_active_operations", false);
      if (hadActiveOps) {
        String lastOp = prefs.getString("last_operation", "");
        LogUtils.i("MainActivity", "Previous operation was cancelled due to swipe-kill: " + lastOp);

        // Clear the persisted state immediately
        prefs.edit().clear().apply();

        // Show info message after a short delay to ensure the layout is ready
        View rootView = findViewById(android.R.id.content);
        if (rootView != null) {
          rootView.postDelayed(
              () ->
                  de.schliweb.sambalite.ui.utils.UIHelper.showInfo(
                      this, getString(R.string.previous_operation_cancelled)),
              500);
        }
      }
    } catch (Throwable t) {
      LogUtils.w("MainActivity", "Failed to check for cancelled operations: " + t.getMessage());
    }
  }

  @Override
  public void onConnectionClick(@NonNull SmbConnection connection) {
    LogUtils.d("MainActivity", "Connection clicked: " + connection.getName());

    if (preferencesManager.isAuthRequiredForAccess()
        && BiometricAuthHelper.isDeviceAuthAvailable(this)) {
      BiometricAuthHelper.authenticate(
          this,
          getString(R.string.auth_title_access),
          getString(R.string.auth_subtitle_access),
          new BiometricAuthHelper.AuthCallback() {
            @Override
            public void onAuthSuccess() {
              proceedWithConnection(connection);
            }

            @Override
            public void onAuthFailure(String errorMessage) {
              EnhancedUIUtils.showError(
                  MainActivity.this, getString(R.string.auth_failed, errorMessage));
            }

            @Override
            public void onAuthCancelled() {
              LogUtils.d("MainActivity", "Authentication cancelled by user");
            }
          });
    } else {
      proceedWithConnection(connection);
    }
  }

  void proceedWithConnection(@NonNull SmbConnection connection) {

    openFileBrowser(connection);
  }

  private void openFileBrowser(@NonNull SmbConnection connection) {
    Intent intent = FileBrowserActivity.createIntent(this, connection.getId());
    startActivity(intent);
    LogUtils.i(
        "MainActivity",
        "Opening RefactoredFileBrowserActivity for connection: " + connection.getName());
  }

  @Override
  public void onConnectionOptionsClick(@NonNull SmbConnection connection) {
    LogUtils.d("MainActivity", "Connection options clicked: " + connection.getName());
    showConnectionOptionsDialog(connection);
  }

  /**
   * Shows a popup menu with options for a sync configuration.
   *
   * @param view The view to anchor the popup menu to
   * @param config The sync configuration
   */
  private void showSyncOptions(View view, SyncConfig config) {
    androidx.appcompat.widget.PopupMenu popup = new androidx.appcompat.widget.PopupMenu(this, view);
    popup.getMenu().add(0, 1, 0, R.string.sync_now);
    popup.getMenu().add(0, 2, 1, R.string.sync_edit_option);
    popup.getMenu().add(0, 3, 2, R.string.sync_remove_option);

    popup.setOnMenuItemClickListener(
        item -> {
          int itemId = item.getItemId();
          if (itemId == 1) {
            viewModel.triggerSync(config.getId());
            EnhancedUIUtils.showInfo(this, getString(R.string.sync_running));
            return true;
          } else if (itemId == 2) {
            showEditSyncDialog(config);
            return true;
          } else if (itemId == 3) {
            confirmDeleteSync(config);
            return true;
          }
          return false;
        });
    popup.show();
  }

  /**
   * Shows a confirmation dialog before deleting a sync configuration.
   *
   * @param config The sync configuration to delete
   */
  private void confirmDeleteSync(SyncConfig config) {
    new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
        .setTitle(R.string.sync_delete_confirm_title)
        .setMessage(
            getString(R.string.sync_delete_confirm_message, config.getLocalFolderDisplayName()))
        .setPositiveButton(
            R.string.delete,
            (dialog, which) -> {
              viewModel.deleteSyncConfig(config.getId());
              EnhancedUIUtils.showInfo(this, getString(R.string.sync_config_removed));
            })
        .setNegativeButton(R.string.cancel, null)
        .show();
  }

  /**
   * Shows a dialog to edit an existing sync configuration.
   *
   * @param config The sync configuration to edit
   */
  private void showEditSyncDialog(SyncConfig config) {
    LogUtils.d("MainActivity", "Showing edit sync dialog for config: " + config.getId());

    View dialogView = getLayoutInflater().inflate(R.layout.dialog_sync_setup, null);

    RadioGroup directionGroup = dialogView.findViewById(R.id.sync_direction_group);
    Spinner intervalSpinner = dialogView.findViewById(R.id.sync_interval_spinner);
    com.google.android.material.textfield.TextInputEditText remotePathField =
        dialogView.findViewById(R.id.sync_remote_path);
    TextView folderDisplay = dialogView.findViewById(R.id.sync_local_folder_display);
    Button selectFolderButton = dialogView.findViewById(R.id.sync_select_folder_button);

    // Pre-fill remote path and make it non-editable
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
      getString(R.string.sync_interval_manual),
      getString(R.string.sync_interval_15min),
      getString(R.string.sync_interval_30min),
      getString(R.string.sync_interval_1h),
      getString(R.string.sync_interval_6h),
      getString(R.string.sync_interval_12h),
      getString(R.string.sync_interval_24h)
    };
    int[] intervalValues = {0, 15, 30, 60, 360, 720, 1440};

    ArrayAdapter<String> adapter =
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, intervalLabels);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    intervalSpinner.setAdapter(adapter);

    // Pre-select interval
    int currentInterval = config.getIntervalMinutes();
    int selectedIndex = 3; // default: 1h
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

    // Folder picker button (disabled for now as it requires more complex handling of SAF results)
    selectFolderButton.setVisibility(View.GONE);

    new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
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

              // Update config
              config.setDirection(direction);
              config.setIntervalMinutes(intervalMinutes);

              viewModel.updateSyncConfig(config);
              EnhancedUIUtils.showInfo(this, getString(R.string.sync_config_updated));
            })
        .setNegativeButton(R.string.cancel, null)
        .show();
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent event) {
    if (event.getAction() == MotionEvent.ACTION_DOWN) {
      View v = getCurrentFocus();
      if (v instanceof com.google.android.material.textfield.TextInputEditText) {
        // Check if the touch was outside the focused text field
        float x = event.getRawX();
        float y = event.getRawY();
        int[] location = new int[2];
        v.getLocationOnScreen(location);

        if (x < location[0]
            || x > location[0] + v.getWidth()
            || y < location[1]
            || y > location[1] + v.getHeight()) {
          LogUtils.d("MainActivity", "Touch outside of text field, clearing focus");
          // Clear focus instead of hiding keyboard directly to avoid interfering with button clicks
          v.clearFocus();
        }
      }
    }
    return super.dispatchTouchEvent(event);
  }

  /** Shows a dialog for adding a new connection. */
  @SuppressWarnings("deprecation")
  private void showAddConnectionDialog() {
    LogUtils.d("MainActivity", "Showing add connection dialog");
    // Inflate the dialog layout
    View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_connection, null);

    // Get references to the input fields
    com.google.android.material.textfield.TextInputLayout nameLayout =
        dialogView.findViewById(R.id.name_layout);
    com.google.android.material.textfield.TextInputLayout serverLayout =
        dialogView.findViewById(R.id.server_layout);
    com.google.android.material.textfield.TextInputLayout shareLayout =
        dialogView.findViewById(R.id.share_layout);
    com.google.android.material.textfield.TextInputEditText nameEditText =
        dialogView.findViewById(R.id.name_edit_text);
    com.google.android.material.textfield.TextInputEditText serverEditText =
        dialogView.findViewById(R.id.server_edit_text);
    com.google.android.material.textfield.TextInputEditText shareEditText =
        dialogView.findViewById(R.id.share_edit_text);
    com.google.android.material.textfield.TextInputEditText usernameEditText =
        dialogView.findViewById(R.id.username_edit_text);
    com.google.android.material.textfield.TextInputEditText passwordEditText =
        dialogView.findViewById(R.id.password_edit_text);
    com.google.android.material.textfield.TextInputEditText domainEditText =
        dialogView.findViewById(R.id.domain_edit_text);

    Button testConnectionButton = dialogView.findViewById(R.id.test_connection_button);
    Button scanNetworkButton = dialogView.findViewById(R.id.scan_network_button);
    com.google.android.material.materialswitch.MaterialSwitch encryptSwitch =
        dialogView.findViewById(R.id.encrypt_switch);
    com.google.android.material.materialswitch.MaterialSwitch signingSwitch =
        dialogView.findViewById(R.id.signing_switch);
    com.google.android.material.materialswitch.MaterialSwitch asyncTransportSwitch =
        dialogView.findViewById(R.id.async_transport_switch);

    // Get references to shares UI elements
    View sharesSection = dialogView.findViewById(R.id.shares_section);
    ProgressBar sharesProgress = dialogView.findViewById(R.id.shares_progress);
    RecyclerView sharesRecyclerView = dialogView.findViewById(R.id.shares_recycler_view);
    TextView sharesStatusText = dialogView.findViewById(R.id.shares_status_text);

    // Set up shares RecyclerView
    SharesAdapter sharesAdapter = new SharesAdapter();
    sharesRecyclerView.setLayoutManager(new LinearLayoutManager(this));
    sharesRecyclerView.setAdapter(sharesAdapter);

    // Set up share selection listener
    sharesAdapter.setOnShareSelectedListener(
        shareName -> {
          LogUtils.d("MainActivity", "Share selected: " + shareName);
          shareEditText.setText(shareName);
        });

    // Set up server field text watcher for automatic share discovery
    final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    final java.util.concurrent.atomic.AtomicReference<Runnable> pendingDiscovery =
        new java.util.concurrent.atomic.AtomicReference<>();

    serverEditText.addTextChangedListener(
        new TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            // Not needed
          }

          @Override
          public void onTextChanged(CharSequence s, int start, int before, int count) {
            // Cancel any pending discovery
            Runnable pending = pendingDiscovery.get();
            if (pending != null) {
              handler.removeCallbacks(pending);
            }
          }

          @Override
          public void afterTextChanged(Editable s) {
            String serverText = s.toString().trim();

            // Hide shares section if server is empty
            if (serverText.isEmpty()) {
              sharesSection.setVisibility(View.GONE);
              return;
            }

            // Only proceed if server looks like a complete IP address or hostname
            if (isValidServerAddress(serverText)) {
              // Debounce the discovery to avoid excessive network calls
              Runnable discoveryTask =
                  () -> {
                    discoverRequireEncrypt = (encryptSwitch != null && encryptSwitch.isChecked());
                    discoverRequireSigning = (signingSwitch != null && signingSwitch.isChecked());
                    discoverShares(
                        serverText,
                        usernameEditText.getText().toString().trim(),
                        passwordEditText.getText().toString().trim(),
                        domainEditText.getText().toString().trim(),
                        sharesSection,
                        sharesProgress,
                        sharesAdapter,
                        sharesStatusText);
                  };

              pendingDiscovery.set(discoveryTask);
              handler.postDelayed(discoveryTask, 1500); // Wait 1.5 seconds after user stops typing
            } else {
              // Hide shares section for incomplete addresses
              sharesSection.setVisibility(View.GONE);
            }
          }
        });

    // Get references to custom buttons
    Button btnSave = dialogView.findViewById(R.id.btn_save);
    Button btnCancel = dialogView.findViewById(R.id.btn_cancel);

    // Create the dialog without default buttons (using custom buttons instead)
    AlertDialog dialog =
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_new_connection)
            .setView(dialogView)
            .create();

    // Prevent accidental dismissal by touching outside the dialog
    dialog.setCanceledOnTouchOutside(false);

    // Show the dialog
    dialog.show();

    // Ensure the dialog resizes when the keyboard appears so buttons remain visible
    if (dialog.getWindow() != null) {
      dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    // Set up the cancel button click listener
    btnCancel.setOnClickListener(
        v -> {
          LogUtils.d("MainActivity", "Add connection dialog cancelled");
          KeyboardUtils.hideKeyboard(dialog);
          dialog.dismiss();
        });

    // Set up the save button click listener
    btnSave.setOnClickListener(
        v -> {
          // Validate input
          LogUtils.d("MainActivity", "Validating connection input");
          boolean isValid = true;

          String name = nameEditText.getText().toString().trim();
          if (name.isEmpty()) {
            LogUtils.d("MainActivity", "Validation failed: name is empty");
            nameLayout.setError(getString(R.string.error_name_required));
            isValid = false;
          } else {
            nameLayout.setError(null);
          }

          String server = serverEditText.getText().toString().trim();
          if (server.isEmpty()) {
            LogUtils.d("MainActivity", "Validation failed: server is empty");
            serverLayout.setError(getString(R.string.error_server_required));
            isValid = false;
          } else {
            serverLayout.setError(null);
          }

          String share = shareEditText.getText().toString().trim();
          if (share.isEmpty()) {
            LogUtils.d("MainActivity", "Validation failed: share is empty");
            shareLayout.setError(getString(R.string.error_share_required));
            isValid = false;
          } else {
            shareLayout.setError(null);
          }

          // If validation passes, save the connection
          if (isValid) {
            LogUtils.d("MainActivity", "Validation passed, creating connection object");
            SmbConnection connection = new SmbConnection();
            connection.setName(name);
            connection.setServer(server);
            connection.setShare(share);
            connection.setUsername(usernameEditText.getText().toString().trim());
            connection.setPassword(passwordEditText.getText().toString().trim());
            connection.setDomain(domainEditText.getText().toString().trim());
            if (encryptSwitch != null) connection.setEncryptData(encryptSwitch.isChecked());
            if (signingSwitch != null) connection.setSigningRequired(signingSwitch.isChecked());
            if (asyncTransportSwitch != null)
              connection.setAsyncTransport(asyncTransportSwitch.isChecked());

            LogUtils.i("MainActivity", "Saving new connection: " + name);
            viewModel.saveConnection(connection);
            KeyboardUtils.hideKeyboard(dialog);
            dialog.dismiss();
          }
        });

    // Set up the test connection button
    testConnectionButton.setOnClickListener(
        v -> {
          // Validate input
          LogUtils.d("MainActivity", "Validating connection for testing");
          boolean isValid = true;

          String server = serverEditText.getText().toString().trim();
          if (server.isEmpty()) {
            LogUtils.d("MainActivity", "Test validation failed: server is empty");
            serverLayout.setError(getString(R.string.error_server_required));
            isValid = false;
          } else {
            serverLayout.setError(null);
          }

          String share = shareEditText.getText().toString().trim();
          if (share.isEmpty()) {
            LogUtils.d("MainActivity", "Test validation failed: share is empty");
            shareLayout.setError(getString(R.string.error_share_required));
            isValid = false;
          } else {
            shareLayout.setError(null);
          }

          // If validation passes, test the connection
          if (isValid) {
            LogUtils.d("MainActivity", "Test validation passed, creating test connection object");
            SmbConnection testConnection = new SmbConnection();
            testConnection.setServer(server);
            testConnection.setShare(share);
            testConnection.setUsername(usernameEditText.getText().toString().trim());
            testConnection.setPassword(passwordEditText.getText().toString().trim());
            testConnection.setDomain(domainEditText.getText().toString().trim());
            if (encryptSwitch != null) testConnection.setEncryptData(encryptSwitch.isChecked());
            if (signingSwitch != null) testConnection.setSigningRequired(signingSwitch.isChecked());
            if (asyncTransportSwitch != null)
              testConnection.setAsyncTransport(asyncTransportSwitch.isChecked());

            LogUtils.i("MainActivity", "Testing connection to server: " + server);
            testConnection(testConnection);
          }
        });

    // Set up the scan network button
    scanNetworkButton.setOnClickListener(
        v -> {
          LogUtils.d("MainActivity", "Scan network button clicked");
          showNetworkScanDialog(serverEditText, nameEditText);
        });
  }

  /** Shows a dialog with options for a connection. */
  private void showConnectionOptionsDialog(SmbConnection connection) {
    LogUtils.d("MainActivity", "Showing options dialog for connection: " + connection.getName());
    String[] options = {"Edit", "Test Connection", "Delete"};

    AlertDialog.Builder builder = new MaterialAlertDialogBuilder(this);
    builder
        .setTitle(connection.getName())
        .setItems(
            options,
            (dialog, which) -> {
              switch (which) {
                case 0: // Edit
                  LogUtils.d(
                      "MainActivity",
                      "Selected edit option for connection: " + connection.getName());
                  showEditConnectionDialog(connection);
                  break;
                case 1: // Test Connection
                  LogUtils.d(
                      "MainActivity",
                      "Selected test option for connection: " + connection.getName());
                  testConnection(connection);
                  break;
                case 2: // Delete
                  LogUtils.d(
                      "MainActivity",
                      "Selected delete option for connection: " + connection.getName());
                  confirmDeleteConnection(connection);
                  break;
              }
            })
        .setNegativeButton(
            R.string.cancel,
            (dialog, which) -> {
              LogUtils.d("MainActivity", "Connection options dialog cancelled");
              dialog.dismiss();
            })
        .show();
  }

  /** Tests a connection and shows the result. */
  private void testConnection(SmbConnection connection) {
    LogUtils.d(
        "MainActivity",
        "Testing connection to: " + connection.getServer() + "/" + connection.getShare());
    viewModel.testConnection(
        connection,
        (success, message) -> {
          LogUtils.d(
              "MainActivity",
              "Connection test result: " + (success ? "success" : "failure") + " - " + message);
          runOnUiThread(
              () -> {
                if (success) {
                  de.schliweb.sambalite.ui.utils.UIHelper.with(this)
                      .message(message)
                      .success()
                      .show();
                } else {
                  de.schliweb.sambalite.ui.utils.UIHelper.with(this)
                      .message(message)
                      .error()
                      .show();
                }
              });
        });
  }

  /** Shows a dialog for editing an existing connection. */
  @SuppressWarnings("deprecation")
  private void showEditConnectionDialog(SmbConnection connection) {
    LogUtils.d("MainActivity", "Showing edit connection dialog for: " + connection.getName());
    // Inflate the dialog layout
    View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_connection, null);

    // Get references to the input fields
    com.google.android.material.textfield.TextInputLayout nameLayout =
        dialogView.findViewById(R.id.name_layout);
    com.google.android.material.textfield.TextInputLayout serverLayout =
        dialogView.findViewById(R.id.server_layout);
    com.google.android.material.textfield.TextInputLayout shareLayout =
        dialogView.findViewById(R.id.share_layout);
    com.google.android.material.textfield.TextInputEditText nameEditText =
        dialogView.findViewById(R.id.name_edit_text);
    com.google.android.material.textfield.TextInputEditText serverEditText =
        dialogView.findViewById(R.id.server_edit_text);
    com.google.android.material.textfield.TextInputEditText shareEditText =
        dialogView.findViewById(R.id.share_edit_text);
    com.google.android.material.textfield.TextInputEditText usernameEditText =
        dialogView.findViewById(R.id.username_edit_text);
    com.google.android.material.textfield.TextInputEditText passwordEditText =
        dialogView.findViewById(R.id.password_edit_text);
    com.google.android.material.textfield.TextInputEditText domainEditText =
        dialogView.findViewById(R.id.domain_edit_text);

    Button testConnectionButton = dialogView.findViewById(R.id.test_connection_button);
    com.google.android.material.materialswitch.MaterialSwitch encryptSwitchEdit =
        dialogView.findViewById(R.id.encrypt_switch);
    com.google.android.material.materialswitch.MaterialSwitch signingSwitchEdit =
        dialogView.findViewById(R.id.signing_switch);
    com.google.android.material.materialswitch.MaterialSwitch asyncTransportSwitchEdit =
        dialogView.findViewById(R.id.async_transport_switch);
    com.google.android.material.textfield.TextInputLayout passwordLayoutEdit =
        dialogView.findViewById(R.id.password_layout);

    // Protect password reveal with biometric authentication if enabled
    if (passwordLayoutEdit != null
        && preferencesManager.isAuthRequiredForPasswordReveal()
        && BiometricAuthHelper.isDeviceAuthAvailable(this)) {
      passwordLayoutEdit.setEndIconMode(
          com.google.android.material.textfield.TextInputLayout.END_ICON_NONE);
      passwordLayoutEdit.setEndIconMode(
          com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE);
      passwordLayoutEdit.setEndIconOnClickListener(
          v -> {
            if (passwordEditText.getTransformationMethod()
                instanceof android.text.method.PasswordTransformationMethod) {
              BiometricAuthHelper.authenticate(
                  MainActivity.this,
                  getString(R.string.auth_title_password),
                  getString(R.string.auth_subtitle_password),
                  new BiometricAuthHelper.AuthCallback() {
                    @Override
                    public void onAuthSuccess() {
                      passwordEditText.setTransformationMethod(null);
                      passwordEditText.setSelection(passwordEditText.getText().length());
                    }

                    @Override
                    public void onAuthFailure(String errorMessage) {
                      EnhancedUIUtils.showError(
                          MainActivity.this, getString(R.string.auth_failed, errorMessage));
                    }

                    @Override
                    public void onAuthCancelled() {
                      LogUtils.d("MainActivity", "Password reveal auth cancelled");
                    }
                  });
            } else {
              passwordEditText.setTransformationMethod(
                  android.text.method.PasswordTransformationMethod.getInstance());
              passwordEditText.setSelection(passwordEditText.getText().length());
            }
          });
    }

    // Get references to shares UI elements
    View sharesSection = dialogView.findViewById(R.id.shares_section);
    ProgressBar sharesProgress = dialogView.findViewById(R.id.shares_progress);
    RecyclerView sharesRecyclerView = dialogView.findViewById(R.id.shares_recycler_view);
    TextView sharesStatusText = dialogView.findViewById(R.id.shares_status_text);

    // Set up shares RecyclerView
    SharesAdapter sharesAdapter = new SharesAdapter();
    sharesRecyclerView.setLayoutManager(new LinearLayoutManager(this));
    sharesRecyclerView.setAdapter(sharesAdapter);

    // Set up share selection listener
    sharesAdapter.setOnShareSelectedListener(
        shareName -> {
          LogUtils.d("MainActivity", "Share selected: " + shareName);
          shareEditText.setText(shareName);
        });

    // Set up server field text watcher for automatic share discovery
    final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    final java.util.concurrent.atomic.AtomicReference<Runnable> pendingDiscovery =
        new java.util.concurrent.atomic.AtomicReference<>();

    serverEditText.addTextChangedListener(
        new TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            // Not needed
          }

          @Override
          public void onTextChanged(CharSequence s, int start, int before, int count) {
            // Cancel any pending discovery
            Runnable pending = pendingDiscovery.get();
            if (pending != null) {
              handler.removeCallbacks(pending);
            }
          }

          @Override
          public void afterTextChanged(Editable s) {
            String serverText = s.toString().trim();

            // Hide shares section if server is empty
            if (serverText.isEmpty()) {
              sharesSection.setVisibility(View.GONE);
              return;
            }

            // Only proceed if server looks like a complete IP address or hostname
            if (isValidServerAddress(serverText)) {
              // Debounce the discovery to avoid excessive network calls
              Runnable discoveryTask =
                  () -> {
                    discoverRequireEncrypt =
                        (encryptSwitchEdit != null && encryptSwitchEdit.isChecked());
                    discoverRequireSigning =
                        (signingSwitchEdit != null && signingSwitchEdit.isChecked());
                    discoverShares(
                        serverText,
                        usernameEditText.getText().toString().trim(),
                        passwordEditText.getText().toString().trim(),
                        domainEditText.getText().toString().trim(),
                        sharesSection,
                        sharesProgress,
                        sharesAdapter,
                        sharesStatusText);
                  };

              pendingDiscovery.set(discoveryTask);
              handler.postDelayed(discoveryTask, 1500); // Wait 1.5 seconds after user stops typing
            } else {
              // Hide shares section for incomplete addresses
              sharesSection.setVisibility(View.GONE);
            }
          }
        });

    // Pre-populate fields with existing connection data
    nameEditText.setText(connection.getName());
    serverEditText.setText(connection.getServer());
    shareEditText.setText(connection.getShare());
    usernameEditText.setText(connection.getUsername());
    passwordEditText.setText(connection.getPassword());
    domainEditText.setText(connection.getDomain());
    if (encryptSwitchEdit != null) encryptSwitchEdit.setChecked(connection.isEncryptData());
    if (signingSwitchEdit != null) signingSwitchEdit.setChecked(connection.isSigningRequired());
    if (asyncTransportSwitchEdit != null)
      asyncTransportSwitchEdit.setChecked(connection.isAsyncTransport());

    // Get references to custom buttons
    Button btnSave = dialogView.findViewById(R.id.btn_save);
    Button btnCancel = dialogView.findViewById(R.id.btn_cancel);

    // Create the dialog without default buttons (using custom buttons instead)
    AlertDialog dialog =
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_connection)
            .setView(dialogView)
            .create();

    // Prevent accidental dismissal by touching outside the dialog
    dialog.setCanceledOnTouchOutside(false);

    // Show the dialog
    dialog.show();

    // Ensure the dialog resizes when the keyboard appears so buttons remain visible
    if (dialog.getWindow() != null) {
      dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    // Set up the cancel button click listener
    btnCancel.setOnClickListener(
        v -> {
          LogUtils.d("MainActivity", "Edit connection dialog cancelled");
          KeyboardUtils.hideKeyboard(dialog);
          dialog.dismiss();
        });

    // Set up the save button click listener
    btnSave.setOnClickListener(
        v -> {
          // Validate input
          LogUtils.d("MainActivity", "Validating edited connection input");
          boolean isValid = true;

          String name = nameEditText.getText().toString().trim();
          if (name.isEmpty()) {
            LogUtils.d("MainActivity", "Edit validation failed: name is empty");
            nameLayout.setError(getString(R.string.error_name_required));
            isValid = false;
          } else {
            nameLayout.setError(null);
          }

          String server = serverEditText.getText().toString().trim();
          if (server.isEmpty()) {
            LogUtils.d("MainActivity", "Edit validation failed: server is empty");
            serverLayout.setError(getString(R.string.error_server_required));
            isValid = false;
          } else {
            serverLayout.setError(null);
          }

          String share = shareEditText.getText().toString().trim();
          if (share.isEmpty()) {
            LogUtils.d("MainActivity", "Edit validation failed: share is empty");
            shareLayout.setError(getString(R.string.error_share_required));
            isValid = false;
          } else {
            shareLayout.setError(null);
          }

          // If validation passes, update the connection
          if (isValid) {
            LogUtils.d("MainActivity", "Edit validation passed, updating connection object");
            // Create a new connection with the same ID to update the existing one
            SmbConnection updatedConnection = new SmbConnection();
            updatedConnection.setId(connection.getId());
            updatedConnection.setName(name);
            updatedConnection.setServer(server);
            updatedConnection.setShare(share);
            updatedConnection.setUsername(usernameEditText.getText().toString().trim());
            updatedConnection.setPassword(passwordEditText.getText().toString().trim());
            updatedConnection.setDomain(domainEditText.getText().toString().trim());

            if (encryptSwitchEdit != null)
              updatedConnection.setEncryptData(encryptSwitchEdit.isChecked());
            if (signingSwitchEdit != null)
              updatedConnection.setSigningRequired(signingSwitchEdit.isChecked());
            if (asyncTransportSwitchEdit != null)
              updatedConnection.setAsyncTransport(asyncTransportSwitchEdit.isChecked());

            LogUtils.i("MainActivity", "Updating connection: " + name);
            viewModel.saveConnection(updatedConnection);
            KeyboardUtils.hideKeyboard(dialog);
            dialog.dismiss();
          }
        });

    // Set up the test connection button
    testConnectionButton.setOnClickListener(
        v -> {
          // Validate input
          LogUtils.d("MainActivity", "Validating connection for testing (edit dialog)");
          boolean isValid = true;

          String server = serverEditText.getText().toString().trim();
          if (server.isEmpty()) {
            LogUtils.d("MainActivity", "Edit test validation failed: server is empty");
            serverLayout.setError(getString(R.string.error_server_required));
            isValid = false;
          } else {
            serverLayout.setError(null);
          }

          String share = shareEditText.getText().toString().trim();
          if (share.isEmpty()) {
            LogUtils.d("MainActivity", "Edit test validation failed: share is empty");
            shareLayout.setError(getString(R.string.error_share_required));
            isValid = false;
          } else {
            shareLayout.setError(null);
          }

          // If validation passes, test the connection
          if (isValid) {
            LogUtils.d(
                "MainActivity", "Edit test validation passed, creating test connection object");
            SmbConnection testConnection = new SmbConnection();
            testConnection.setServer(server);
            testConnection.setShare(share);
            testConnection.setUsername(usernameEditText.getText().toString().trim());
            testConnection.setPassword(passwordEditText.getText().toString().trim());
            testConnection.setDomain(domainEditText.getText().toString().trim());

            LogUtils.i(
                "MainActivity", "Testing connection to server: " + server + " (from edit dialog)");
            if (encryptSwitchEdit != null)
              testConnection.setEncryptData(encryptSwitchEdit.isChecked());
            if (signingSwitchEdit != null)
              testConnection.setSigningRequired(signingSwitchEdit.isChecked());
            if (asyncTransportSwitchEdit != null)
              testConnection.setAsyncTransport(asyncTransportSwitchEdit.isChecked());
            testConnection(testConnection);
          }
        });
  }

  /** Shows a confirmation dialog for deleting a connection. */
  private void confirmDeleteConnection(SmbConnection connection) {
    LogUtils.d("MainActivity", "Showing delete confirmation dialog for: " + connection.getName());
    new MaterialAlertDialogBuilder(this)
        .setTitle(R.string.delete_connection)
        .setMessage(getString(R.string.confirm_delete_connection, connection.getName()))
        .setPositiveButton(
            R.string.delete,
            (dialog, which) -> {
              LogUtils.i(
                  "MainActivity", "Confirming deletion of connection: " + connection.getName());
              viewModel.deleteConnection(connection.getId());
            })
        .setNegativeButton(
            R.string.cancel,
            (dialog, which) -> {
              LogUtils.d(
                  "MainActivity", "Deletion cancelled for connection: " + connection.getName());
            })
        .show();
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    LogUtils.d("MainActivity", "Creating options menu");
    getMenuInflater().inflate(R.menu.menu_main, menu);
    LogUtils.d("MainActivity", "Options menu created with " + menu.size() + " items");
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == R.id.action_transfer_queue) {
      LogUtils.d("MainActivity", "Transfer queue menu item selected");
      startActivity(TransferQueueActivity.createIntent(this));
      return true;
    }
    if (item.getItemId() == R.id.action_security_settings) {
      LogUtils.d("MainActivity", "Security settings menu item selected");
      authenticateBeforeSecuritySettings();
      return true;
    }
    if (item.getItemId() == R.id.action_system_monitor) {
      LogUtils.d("MainActivity", "System Monitor menu item selected");
      Intent intent = SystemMonitorActivity.createIntent(this);
      startActivity(intent);
      return true;
    }
    if (item.getItemId() == R.id.action_quit) {
      LogUtils.d("MainActivity", "Quit menu item selected");
      handleQuit();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  private void handleQuit() {
    if (backgroundSmbManager != null && backgroundSmbManager.hasActiveOperations()) {
      int count = backgroundSmbManager.getActiveOperationCount();
      new MaterialAlertDialogBuilder(this)
          .setTitle(R.string.quit_app_confirm_title)
          .setMessage(
              getResources().getQuantityString(R.plurals.quit_app_confirm_message, count, count))
          .setPositiveButton(R.string.quit_app_confirm_positive, (dialog, which) -> performQuit())
          .setNegativeButton(R.string.cancel, null)
          .show();
    } else {
      performQuit();
    }
  }

  private void performQuit() {
    if (backgroundSmbManager != null) {
      backgroundSmbManager.requestStopService();
    }
    finishAffinity();
  }

  /**
   * Requires authentication before opening the security settings dialog if any auth setting is
   * currently enabled. This prevents unauthorized users from disabling security protections.
   */
  private void authenticateBeforeSecuritySettings() {
    boolean anyAuthEnabled =
        preferencesManager.isAuthRequiredForAccess()
            || preferencesManager.isAuthRequiredForPasswordReveal();

    if (anyAuthEnabled && BiometricAuthHelper.isDeviceAuthAvailable(this)) {
      BiometricAuthHelper.authenticate(
          this,
          getString(R.string.auth_title_access),
          getString(R.string.auth_subtitle_security_settings),
          new BiometricAuthHelper.AuthCallback() {
            @Override
            public void onAuthSuccess() {
              showSecuritySettingsDialog();
            }

            @Override
            public void onAuthFailure(String errorMessage) {
              EnhancedUIUtils.showError(
                  MainActivity.this, getString(R.string.auth_failed, errorMessage));
            }

            @Override
            public void onAuthCancelled() {
              LogUtils.d("MainActivity", "Security settings authentication cancelled by user");
            }
          });
    } else {
      showSecuritySettingsDialog();
    }
  }

  /** Shows a dialog for configuring security settings. */
  private void showSecuritySettingsDialog() {
    LogUtils.d("MainActivity", "Showing security settings dialog");

    boolean deviceAuthAvailable = BiometricAuthHelper.isDeviceAuthAvailable(this);

    View dialogView = getLayoutInflater().inflate(R.layout.dialog_security_settings, null);
    com.google.android.material.materialswitch.MaterialSwitch authAccessSwitch =
        dialogView.findViewById(R.id.auth_access_switch);
    com.google.android.material.materialswitch.MaterialSwitch authPasswordSwitch =
        dialogView.findViewById(R.id.auth_password_reveal_switch);
    TextView authNotAvailableText = dialogView.findViewById(R.id.auth_not_available_text);

    // Set current values
    authAccessSwitch.setChecked(preferencesManager.isAuthRequiredForAccess());
    authPasswordSwitch.setChecked(preferencesManager.isAuthRequiredForPasswordReveal());

    // Disable switches if device auth is not available
    if (!deviceAuthAvailable) {
      authAccessSwitch.setEnabled(false);
      authPasswordSwitch.setEnabled(false);
      authNotAvailableText.setVisibility(View.VISIBLE);
    } else {
      authNotAvailableText.setVisibility(View.GONE);
    }

    new MaterialAlertDialogBuilder(this)
        .setTitle(R.string.security_settings_title)
        .setView(dialogView)
        .setPositiveButton(
            R.string.save,
            (dialog, which) -> {
              preferencesManager.saveAuthRequiredForAccess(authAccessSwitch.isChecked());
              preferencesManager.saveAuthRequiredForPasswordReveal(authPasswordSwitch.isChecked());
              LogUtils.i(
                  "MainActivity",
                  "Security settings saved: access="
                      + authAccessSwitch.isChecked()
                      + ", passwordReveal="
                      + authPasswordSwitch.isChecked());
            })
        .setNegativeButton(R.string.cancel, null)
        .show();
  }

  /** Shows the network scan dialog to discover SMB servers. */
  private void showNetworkScanDialog(
      com.google.android.material.textfield.TextInputEditText serverEditText,
      com.google.android.material.textfield.TextInputEditText nameEditText) {
    LogUtils.d("MainActivity", "Showing network scan dialog");

    // Check if scanning is supported
    if (!networkScanner.isScanningSupported()) {
      EnhancedUIUtils.showError(
          this, "Network scanning is not available. Please check your network connection.");
      return;
    }

    // Inflate the dialog layout
    View dialogView = getLayoutInflater().inflate(R.layout.dialog_network_scan, null);

    // Get UI elements with null checks
    View scanProgressSection = dialogView.findViewById(R.id.scan_progress_section);
    View serverListSection = dialogView.findViewById(R.id.server_list_section);
    ProgressBar progressIndicator = dialogView.findViewById(R.id.scan_progress_indicator);
    TextView scanStatusText = dialogView.findViewById(R.id.scan_status_text);
    TextView scanProgressText = dialogView.findViewById(R.id.scan_progress_text);
    TextView serversFoundLabel = dialogView.findViewById(R.id.servers_found_label);
    androidx.recyclerview.widget.RecyclerView serversRecyclerView =
        dialogView.findViewById(R.id.servers_recycler_view);
    TextView noServersText = dialogView.findViewById(R.id.no_servers_text);
    LinearLayout customButtonBar = dialogView.findViewById(R.id.custom_button_bar);
    Button customCancelButton = dialogView.findViewById(R.id.btn_cancel);
    Button customScanButton = dialogView.findViewById(R.id.btn_scan);
    Button customUseServerButton = dialogView.findViewById(R.id.btn_use_server);

    // Check for critical UI elements
    if (customButtonBar == null) {
      LogUtils.e(
          "MainActivity",
          "Critical UI element customButtonBar is null - dialog layout may be missing elements");
      EnhancedUIUtils.showError(
          this, "Network scan dialog layout is incomplete. Please check the app installation.");
      return;
    }

    if (serversRecyclerView == null) {
      LogUtils.e("MainActivity", "Critical UI element serversRecyclerView is null");
      EnhancedUIUtils.showError(this, "Network scan dialog is not properly configured.");
      return;
    }

    // Set up RecyclerView
    DiscoveredServerAdapter adapter = new DiscoveredServerAdapter();
    serversRecyclerView.setLayoutManager(
        new androidx.recyclerview.widget.LinearLayoutManager(this));
    serversRecyclerView.setAdapter(adapter);

    // Create the dialog without default buttons (use custom ones)
    AlertDialog scanDialog =
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scan_network)
            .setView(dialogView)
            .create();

    // Set up custom buttons
    scanDialog.setOnShowListener(
        dialogInterface -> {
          // Show custom button bar
          if (customButtonBar != null) {
            customButtonBar.setVisibility(View.VISIBLE);
          }

          // Initially hide the "OK" button - will be shown when server is selected
          if (customUseServerButton != null) {
            customUseServerButton.setVisibility(View.GONE);
          }

          // Set up button click listeners
          if (customCancelButton != null) {
            customCancelButton.setOnClickListener(
                v -> {
                  LogUtils.d("MainActivity", "Network scan cancelled by user");
                  networkScanner.cancelScan();
                  scanDialog.dismiss();
                });
          }

          if (customScanButton != null) {
            customScanButton.setOnClickListener(
                v -> {
                  LogUtils.d("MainActivity", "Starting network scan");
                  startNetworkScan(
                      scanProgressSection,
                      serverListSection,
                      progressIndicator,
                      scanStatusText,
                      scanProgressText,
                      serversFoundLabel,
                      noServersText,
                      adapter,
                      customScanButton,
                      customUseServerButton);
                });
          }

          if (customUseServerButton != null) {
            customUseServerButton.setOnClickListener(
                v -> {
                  NetworkScanner.DiscoveredServer selectedServer = adapter.getSelectedServer();
                  if (selectedServer != null) {
                    LogUtils.d(
                        "MainActivity",
                        "Using selected server: " + selectedServer.getDisplayName());

                    // Auto-fill the connection form
                    serverEditText.setText(selectedServer.getIpAddress());

                    // Generate a name if empty
                    if (nameEditText.getText().toString().trim().isEmpty()) {
                      String suggestedName =
                          selectedServer.getHostname() != null
                              ? selectedServer.getHostname()
                              : "Server " + selectedServer.getIpAddress();
                      nameEditText.setText(suggestedName);
                    }

                    EnhancedUIUtils.showInfo(
                        MainActivity.this,
                        getString(R.string.server_info_applied, selectedServer.getDisplayName()));
                    scanDialog.dismiss();
                  }
                  // Note: Since OK button is only visible when server is selected,
                  // the else case should never happen
                });
          }
        });

    // Set up server selection
    adapter.setOnServerSelectedListener(
        server -> {
          LogUtils.d("MainActivity", "Server selected: " + server.getDisplayName());
          // Show and update the "OK" button when server is selected
          if (customUseServerButton != null) {
            customUseServerButton.setVisibility(View.VISIBLE);
            customUseServerButton.setText(R.string.ok);
          }
        });

    // Prevent accidental dismissal by touching outside the dialog
    scanDialog.setCanceledOnTouchOutside(false);
    scanDialog.setCancelable(false);

    // Show the dialog
    scanDialog.show();

    // Start initial scan automatically
    startNetworkScan(
        scanProgressSection,
        serverListSection,
        progressIndicator,
        scanStatusText,
        scanProgressText,
        serversFoundLabel,
        noServersText,
        adapter,
        customScanButton,
        customUseServerButton);
  }

  /** Starts the network scan operation. */
  private void startNetworkScan(
      View scanProgressSection,
      View serverListSection,
      ProgressBar progressIndicator,
      TextView scanStatusText,
      TextView scanProgressText,
      TextView serversFoundLabel,
      TextView noServersText,
      DiscoveredServerAdapter adapter,
      Button scanButton,
      Button okButton) {

    LogUtils.d("MainActivity", "Starting network scan operation");

    // Show progress section, hide server list (with null checks)
    if (scanProgressSection != null) {
      scanProgressSection.setVisibility(View.VISIBLE);
    }
    if (serverListSection != null) {
      serverListSection.setVisibility(View.GONE);
    }

    // Hide both scan and OK buttons during scanning
    if (scanButton != null) {
      scanButton.setVisibility(View.GONE);
    }
    if (okButton != null) {
      okButton.setVisibility(View.GONE);
    }

    // Reset progress (with null checks)
    if (progressIndicator != null) {
      progressIndicator.setProgress(0);
    }
    if (scanStatusText != null) {
      scanStatusText.setText(R.string.scanning_network);
    }
    if (scanProgressText != null) {
      scanProgressText.setText(R.string.hosts_scan_progress);
    }

    // Clear previous results
    if (adapter != null) {
      adapter.setServers(new java.util.ArrayList<>());
    }

    // Start the scan
    networkScanner.scanLocalNetwork(
        new NetworkScanner.ScanProgressListener() {
          @Override
          public void onProgressUpdate(int scannedHosts, int totalHosts, String currentHost) {
            runOnUiThread(
                () -> {
                  int progress = (int) ((scannedHosts / (float) totalHosts) * 100);
                  if (progressIndicator != null) {
                    progressIndicator.setProgress(progress);
                  }
                  if (scanProgressText != null) {
                    scanProgressText.setText(
                        getResources()
                            .getQuantityString(
                                R.plurals.hosts_scanned, scannedHosts, scannedHosts, totalHosts));
                  }
                  if (scanStatusText != null) {
                    scanStatusText.setText(getString(R.string.currently_scanning, currentHost));
                  }
                });
          }

          @Override
          public void onServerFound(NetworkScanner.DiscoveredServer server) {
            runOnUiThread(
                () -> {
                  LogUtils.d("MainActivity", "Found SMB server: " + server.getDisplayName());
                  if (adapter != null) {
                    adapter.addServer(server);
                    // Update the label with count
                    int serverCount = adapter.getItemCount();
                    LogUtils.d(
                        "MainActivity", "Added server to adapter. New count: " + serverCount);
                    if (serversFoundLabel != null) {
                      serversFoundLabel.setText(
                          getResources()
                              .getQuantityString(
                                  R.plurals.servers_found_count, serverCount, serverCount));
                    }
                  }
                });
          }

          @Override
          public void onScanComplete(java.util.List<NetworkScanner.DiscoveredServer> servers) {
            runOnUiThread(
                () -> {
                  int serverCount = (adapter != null) ? adapter.getItemCount() : 0;
                  LogUtils.i(
                      "MainActivity",
                      "Network scan completed. Scanner found "
                          + servers.size()
                          + " servers, adapter has "
                          + serverCount
                          + " servers");

                  // Hide progress section, show results (with null checks)
                  if (scanProgressSection != null) {
                    scanProgressSection.setVisibility(View.GONE);
                  }
                  if (serverListSection != null) {
                    serverListSection.setVisibility(View.VISIBLE);
                  }

                  // Show scan button again
                  if (scanButton != null) {
                    scanButton.setVisibility(View.VISIBLE);
                    scanButton.setEnabled(true);
                    scanButton.setText(R.string.scan_network);
                  }

                  if (serverCount == 0) {
                    if (noServersText != null) {
                      noServersText.setVisibility(View.VISIBLE);
                    }
                    if (serversFoundLabel != null) {
                      serversFoundLabel.setVisibility(View.GONE);
                    }
                    // Keep OK button hidden when no servers found
                    if (okButton != null) {
                      okButton.setVisibility(View.GONE);
                    }
                  } else {
                    if (noServersText != null) {
                      noServersText.setVisibility(View.GONE);
                    }
                    if (serversFoundLabel != null) {
                      serversFoundLabel.setVisibility(View.VISIBLE);
                      serversFoundLabel.setText(
                          getResources()
                              .getQuantityString(
                                  R.plurals.servers_found_count, serverCount, serverCount));
                    }
                    // OK button will be shown when user selects a server
                  }

                  EnhancedUIUtils.showInfo(MainActivity.this, getString(R.string.scan_complete));
                });
          }

          @Override
          public void onScanError(String error) {
            runOnUiThread(
                () -> {
                  LogUtils.e("MainActivity", "Network scan error: " + error);

                  // Hide progress section, show results (with null checks)
                  if (scanProgressSection != null) {
                    scanProgressSection.setVisibility(View.GONE);
                  }
                  if (serverListSection != null) {
                    serverListSection.setVisibility(View.VISIBLE);
                  }

                  // Show scan button again
                  if (scanButton != null) {
                    scanButton.setVisibility(View.VISIBLE);
                    scanButton.setEnabled(true);
                    scanButton.setText(R.string.scan_network);
                  }

                  // Keep OK button hidden on error
                  if (okButton != null) {
                    okButton.setVisibility(View.GONE);
                  }

                  // Show error
                  EnhancedUIUtils.showError(
                      MainActivity.this, getString(R.string.scan_error, error));
                  if (noServersText != null) {
                    noServersText.setVisibility(View.VISIBLE);
                  }
                });
          }
        });
  }

  /**
   * Checks if the server address is complete enough to attempt a connection. Validates IP addresses
   * (xxx.xxx.xxx.xxx) and hostnames.
   */
  boolean isValidServerAddress(String serverAddress) {
    if (serverAddress == null || serverAddress.trim().isEmpty()) {
      return false;
    }

    String trimmed = serverAddress.trim();

    // Check if it looks like a complete IP address
    if (trimmed.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
      return true;
    }

    // Check if it looks like a hostname (contains at least one letter and no incomplete IP
    // patterns)
    if (trimmed.matches(".*[a-zA-Z].*") && !trimmed.matches("\\d+\\.\\d*\\.?\\d*\\.?\\d*")) {
      return trimmed.length() >= 3; // Minimum reasonable hostname length
    }

    return false;
  }

  /** Discovers available shares on the specified server. */
  void discoverShares(
      String server,
      String username,
      String password,
      String domain,
      View sharesSection,
      ProgressBar sharesProgress,
      SharesAdapter sharesAdapter,
      TextView sharesStatusText) {
    LogUtils.d("MainActivity", "Discovering shares on server: " + server);

    // Show shares section and progress
    sharesSection.setVisibility(View.VISIBLE);
    sharesProgress.setVisibility(View.VISIBLE);
    sharesStatusText.setText(R.string.discovering_shares);

    // Clear previous shares
    sharesAdapter.clearShares();

    // Create a temporary connection for share discovery
    SmbConnection tempConnection = new SmbConnection();
    tempConnection.setServer(server);
    tempConnection.setUsername(username);
    tempConnection.setPassword(password);
    tempConnection.setDomain(domain);
    // Honor current security toggles for discovery
    tempConnection.setEncryptData(discoverRequireEncrypt);
    tempConnection.setSigningRequired(discoverRequireSigning);

    viewModel.listShares(
        tempConnection,
        new MainViewModel.ShareListCallback() {
          @Override
          public void onSuccess(java.util.List<String> shares) {
            runOnUiThread(
                () -> {
                  LogUtils.d(
                      "MainActivity", "Found " + shares.size() + " shares on server: " + server);
                  sharesProgress.setVisibility(View.GONE);

                  if (shares.isEmpty()) {
                    sharesStatusText.setText(R.string.no_shares_found);
                  } else {
                    sharesStatusText.setText(R.string.tap_share_to_select);
                    sharesAdapter.setShares(shares);
                  }
                });
          }

          @Override
          public void onError(String error) {
            runOnUiThread(
                () -> {
                  LogUtils.w("MainActivity", "Failed to discover shares: " + error);
                  sharesProgress.setVisibility(View.GONE);
                  sharesStatusText.setText(R.string.could_not_connect_check_credentials);
                  sharesAdapter.clearShares();
                });
          }
        });
  }

  @Override
  protected void onStart() {
    super.onStart();
    if (backgroundSmbManager != null) {
      backgroundSmbManager.onActivityStarted();
    }
    viewModel.loadSyncConfigs();
  }

  @Override
  protected void onStop() {
    super.onStop();
    if (backgroundSmbManager != null) {
      backgroundSmbManager.onActivityStopped();
    }
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    // Clean up network scanner
    if (networkScanner != null) {
      networkScanner.shutdown();
    }
    LogUtils.d("MainActivity", "onDestroy called");
  }

  /** Checks battery optimization settings on first app start */
  private void checkBatteryOptimizationOnFirstRun() {
    if (BatteryOptimizationUtils.shouldShowBatteryOptimizationDialog(this)) {
      // Show dialog only after short delay so MainActivity is fully loaded
      findViewById(android.R.id.content)
          .postDelayed(
              () -> {
                BatteryOptimizationUtils.requestBatteryOptimizationExemption(this);
              },
              2000); // 2 seconds delay
    }
  }
}
