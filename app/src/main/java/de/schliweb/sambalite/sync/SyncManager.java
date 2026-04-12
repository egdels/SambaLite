/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.sync;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import de.schliweb.sambalite.util.LogUtils;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Manages folder synchronization scheduling and configuration. Coordinates between SyncRepository
 * (persistence) and WorkManager (scheduling).
 */
@Singleton
public class SyncManager {

  private static final String TAG = "SyncManager";
  public static final String UNIQUE_WORK_NAME = "sambalite_folder_sync";
  private static final int MIN_INTERVAL_MINUTES = 15;

  private final Context context;
  private final SyncRepository syncRepository;

  @Inject
  public SyncManager(@NonNull Context context, @NonNull SyncRepository syncRepository) {
    this.context = context.getApplicationContext();
    this.syncRepository = syncRepository;
    LogUtils.d(TAG, "SyncManager initialized");
  }

  /**
   * Adds a new sync configuration and schedules periodic sync.
   *
   * @param connectionId the connection ID to use
   * @param localFolderUri the URI of the local folder (from SAF picker)
   * @param remotePath the remote path on the SMB share
   * @param localFolderDisplayName display name of the local folder
   * @param direction the sync direction
   * @param intervalMinutes the sync interval in minutes (minimum 15)
   * @return the saved SyncConfig
   */
  public @NonNull SyncConfig addSyncConfig(
      @NonNull String connectionId,
      @NonNull Uri localFolderUri,
      @NonNull String remotePath,
      @NonNull String localFolderDisplayName,
      @NonNull SyncDirection direction,
      int intervalMinutes) {
    return addSyncConfig(
        connectionId,
        localFolderUri,
        remotePath,
        localFolderDisplayName,
        direction,
        intervalMinutes,
        false);
  }

  /**
   * Adds a new sync configuration and schedules periodic sync.
   *
   * @param connectionId the connection ID to use
   * @param localFolderUri the URI of the local folder (from SAF picker)
   * @param remotePath the remote path on the SMB share
   * @param localFolderDisplayName display name of the local folder
   * @param direction the sync direction
   * @param intervalMinutes the sync interval in minutes (minimum 15)
   * @param wifiOnly whether to sync on WiFi only
   * @return the saved SyncConfig
   */
  public @NonNull SyncConfig addSyncConfig(
      @NonNull String connectionId,
      @NonNull Uri localFolderUri,
      @NonNull String remotePath,
      @NonNull String localFolderDisplayName,
      @NonNull SyncDirection direction,
      int intervalMinutes,
      boolean wifiOnly) {
    LogUtils.d(TAG, "Adding sync config for connection: " + connectionId);

    // Take persistable URI permission
    try {
      context
          .getContentResolver()
          .takePersistableUriPermission(
              localFolderUri,
              Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
      LogUtils.d(TAG, "Persistable URI permission taken for: " + localFolderUri);
    } catch (SecurityException e) {
      LogUtils.w(TAG, "Could not take persistable URI permission: " + e.getMessage());
    }

    SyncConfig config = new SyncConfig();
    config.setConnectionId(connectionId);
    config.setLocalFolderUri(localFolderUri.toString());
    config.setRemotePath(remotePath);
    config.setLocalFolderDisplayName(localFolderDisplayName);
    config.setDirection(direction);
    config.setIntervalMinutes(
        intervalMinutes <= 0 ? 0 : Math.max(intervalMinutes, MIN_INTERVAL_MINUTES));
    config.setWifiOnly(wifiOnly);

    SyncConfig saved = syncRepository.saveSyncConfig(config);
    LogUtils.i(TAG, "Sync config added: " + saved.getId());

    schedulePeriodicSync();
    return saved;
  }

  /**
   * Removes a sync configuration and cancels periodic sync if no configs remain.
   *
   * @param configId the ID of the configuration to remove
   * @return true if the configuration was found and removed
   */
  public boolean removeSyncConfig(@NonNull String configId) {
    LogUtils.d(TAG, "Removing sync config: " + configId);
    boolean removed = syncRepository.deleteSyncConfig(configId);

    if (removed) {
      List<SyncConfig> remaining = syncRepository.getAllEnabledConfigs();
      if (remaining.isEmpty()) {
        cancelPeriodicSync();
      } else {
        schedulePeriodicSync();
      }
    }

    return removed;
  }

  /**
   * Enables or disables a sync configuration.
   *
   * @param configId the ID of the configuration
   * @param enabled whether the configuration should be enabled
   */
  public void setConfigEnabled(@NonNull String configId, boolean enabled) {
    LogUtils.d(TAG, "Setting config " + configId + " enabled: " + enabled);
    List<SyncConfig> configs = syncRepository.getAllSyncConfigs();

    for (SyncConfig config : configs) {
      if (config.getId().equals(configId)) {
        config.setEnabled(enabled);
        syncRepository.saveSyncConfig(config);
        break;
      }
    }

    List<SyncConfig> enabledConfigs = syncRepository.getAllEnabledConfigs();
    if (enabledConfigs.isEmpty()) {
      cancelPeriodicSync();
    } else {
      schedulePeriodicSync();
    }
  }

  /**
   * Updates an existing sync configuration.
   *
   * @param config the configuration to update
   */
  public void updateSyncConfig(@NonNull SyncConfig config) {
    LogUtils.d(TAG, "Updating sync config: " + config.getId());
    syncRepository.saveSyncConfig(config);

    if (config.isEnabled()) {
      schedulePeriodicSync();
    } else {
      List<SyncConfig> enabledConfigs = syncRepository.getAllEnabledConfigs();
      if (enabledConfigs.isEmpty()) {
        cancelPeriodicSync();
      } else {
        schedulePeriodicSync();
      }
    }
  }

  /**
   * Returns all sync configurations.
   *
   * @return list of all sync configurations
   */
  public @NonNull List<SyncConfig> getAllSyncConfigs() {
    return syncRepository.getAllSyncConfigs();
  }

  /** Triggers an immediate one-time sync for all enabled configurations. */
  public void triggerImmediateSync() {
    LogUtils.i(TAG, "Triggering immediate sync for all configs");

    Constraints constraints =
        new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();

    OneTimeWorkRequest request =
        new OneTimeWorkRequest.Builder(FolderSyncWorker.class)
            .setConstraints(constraints)
            .addTag(UNIQUE_WORK_NAME)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build();

    WorkManager.getInstance(context)
        .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request);
  }

  /**
   * Triggers an immediate one-time sync for a specific configuration.
   *
   * @param configId the ID of the sync configuration to sync
   */
  public void triggerImmediateSync(@NonNull String configId) {
    LogUtils.i(TAG, "Triggering immediate sync for config: " + configId);

    Constraints constraints =
        new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();

    androidx.work.Data inputData =
        new androidx.work.Data.Builder()
            .putString(FolderSyncWorker.KEY_SYNC_CONFIG_ID, configId)
            .build();

    OneTimeWorkRequest request =
        new OneTimeWorkRequest.Builder(FolderSyncWorker.class)
            .setConstraints(constraints)
            .setInputData(inputData)
            .addTag(UNIQUE_WORK_NAME)
            .addTag("manual_sync")
            .addTag("config_id:" + configId)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build();

    WorkManager.getInstance(context)
        .enqueueUniqueWork(UNIQUE_WORK_NAME + "_" + configId, ExistingWorkPolicy.REPLACE, request);
  }

  /** Schedules periodic sync based on the minimum interval of all enabled configs. */
  public void schedulePeriodicSync() {
    List<SyncConfig> enabledConfigs = syncRepository.getAllEnabledConfigs();
    if (enabledConfigs.isEmpty()) {
      LogUtils.d(TAG, "No enabled configs, not scheduling periodic sync");
      return;
    }

    int minInterval = Integer.MAX_VALUE;
    boolean allManual = true;
    for (SyncConfig config : enabledConfigs) {
      if (config.getIntervalMinutes() > 0) {
        minInterval = Math.min(minInterval, config.getIntervalMinutes());
        allManual = false;
      }
    }
    if (allManual) {
      LogUtils.d(TAG, "All enabled configs are manual-only, cancelling periodic sync");
      cancelPeriodicSync();
      return;
    }
    minInterval = Math.max(minInterval, MIN_INTERVAL_MINUTES);

    LogUtils.i(TAG, "Scheduling periodic sync with interval: " + minInterval + " minutes");

    Constraints constraints =
        new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();

    PeriodicWorkRequest request =
        new PeriodicWorkRequest.Builder(FolderSyncWorker.class, minInterval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag(UNIQUE_WORK_NAME)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build();

    WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request);
  }

  /** Cancels the periodic sync. */
  public void cancelPeriodicSync() {
    LogUtils.i(TAG, "Cancelling periodic sync");
    WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME);
  }

  /**
   * Removes all sync configurations for a specific connection.
   *
   * @param connectionId the connection ID whose configs should be removed
   * @return the number of removed configurations
   */
  public int removeConfigsForConnection(@NonNull String connectionId) {
    LogUtils.d(TAG, "Removing sync configs for connection: " + connectionId);
    int removed = syncRepository.deleteConfigsForConnection(connectionId);

    if (removed > 0) {
      List<SyncConfig> remaining = syncRepository.getAllEnabledConfigs();
      if (remaining.isEmpty()) {
        cancelPeriodicSync();
      } else {
        schedulePeriodicSync();
      }
    }

    return removed;
  }
}
