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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.repository.ConnectionRepository;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.sync.SyncConfig;
import de.schliweb.sambalite.sync.SyncManager;
import de.schliweb.sambalite.util.LogUtils;
import de.schliweb.sambalite.util.SmartErrorHandler;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import javax.inject.Inject;

/** ViewModel for the MainActivity. Handles the list of connections and operations on them. */
public class MainViewModel extends ViewModel {

  private final ConnectionRepository connectionRepository;
  private final SmbRepository smbRepository;
  private final SyncManager syncManager;
  private final MutableLiveData<List<SyncConfig>> syncConfigs = new MutableLiveData<>();
  private final Executor executor;
  private final SmartErrorHandler errorHandler;

  private final MutableLiveData<List<SmbConnection>> connections = new MutableLiveData<>();
  private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(false);
  private final MutableLiveData<ErrorMessage> errorMessage = new MutableLiveData<>();

  /**
   * An error for the main screen: a translated text (with one {@code %1$s} for the detail) plus the
   * technical detail. The activity resolves the text, so the message follows the app language.
   */
  public static final class ErrorMessage {
    @androidx.annotation.StringRes public final int textRes;
    @NonNull public final String detail;

    ErrorMessage(@androidx.annotation.StringRes int textRes, @Nullable String detail) {
      this.textRes = textRes;
      this.detail = detail != null ? detail : "";
    }
  }

  /** Kept in the ViewModel so finished syncs are not announced again after a recreation. */
  private final SyncWorkReporter syncWorkReporter = new SyncWorkReporter();

  @Inject
  public MainViewModel(
      @NonNull ConnectionRepository connectionRepository,
      @NonNull SmbRepository smbRepository,
      @NonNull SyncManager syncManager) {
    this.connectionRepository = connectionRepository;
    this.smbRepository = smbRepository;
    this.syncManager = syncManager;
    this.executor = Executors.newSingleThreadExecutor();
    this.errorHandler = SmartErrorHandler.getInstance();

    LogUtils.d("MainViewModel", "MainViewModel initialized");

    // Load connections when the ViewModel is created
    loadConnections();

    // Debug: Add immediate debug log for connections
    LogUtils.d("MainViewModel", "MainViewModel created, connections LiveData initialized");
    loadSyncConfigs();
  }

  /** Gets the list of connections as LiveData. */
  public @NonNull LiveData<List<SmbConnection>> getConnections() {
    return connections;
  }

  /** Gets the list of sync configurations as LiveData. */
  public @NonNull LiveData<List<SyncConfig>> getSyncConfigs() {
    return syncConfigs;
  }

  /** Gets the loading state as LiveData. */
  public @NonNull LiveData<Boolean> isLoading() {
    return isLoading;
  }

  /** Gets the error message as LiveData. */
  public @NonNull LiveData<ErrorMessage> getErrorMessage() {
    return errorMessage;
  }

  /** Loads sync configurations from the database. */
  public void loadSyncConfigs() {
    executor.execute(
        () -> {
          try {
            LogUtils.d("MainViewModel", "Loading sync configurations");
            List<SyncConfig> configs = syncManager.getAllSyncConfigs();
            syncConfigs.postValue(configs);
            LogUtils.d(
                "MainViewModel",
                "Loaded " + (configs != null ? configs.size() : 0) + " sync configurations");
          } catch (Exception e) {
            LogUtils.e("MainViewModel", "Failed to load sync configurations: " + e.getMessage());
            errorHandler.recordError(
                e, "MainViewModel.loadSyncConfigs", SmartErrorHandler.ErrorSeverity.MEDIUM);
          }
        });
  }

  /**
   * Triggers an immediate synchronization for a specific configuration.
   *
   * @param configId The ID of the sync configuration to trigger
   */
  public void triggerSync(@NonNull String configId) {
    executor.execute(
        () -> {
          try {
            LogUtils.i("MainViewModel", "Triggering manual sync for config: " + configId);
            syncManager.triggerImmediateSync(configId);
          } catch (Exception e) {
            LogUtils.e("MainViewModel", "Failed to trigger sync: " + e.getMessage());
            errorHandler.recordError(
                e, "MainViewModel.triggerSync", SmartErrorHandler.ErrorSeverity.MEDIUM);
          }
        });
  }

  /**
   * Pauses or resumes a sync configuration. A paused sync keeps its settings and its sync state but
   * does not run until it is resumed.
   *
   * @param configId The ID of the sync configuration
   * @param enabled false to pause, true to resume
   */
  public void setSyncEnabled(@NonNull String configId, boolean enabled) {
    executor.execute(
        () -> {
          try {
            syncManager.setConfigEnabled(configId, enabled);
          } catch (Exception e) {
            LogUtils.e("MainViewModel", "Failed to change sync state: " + e.getMessage());
            errorHandler.recordError(
                e, "MainViewModel.setSyncEnabled", SmartErrorHandler.ErrorSeverity.MEDIUM);
          }
          loadSyncConfigs();
        });
  }

  /**
   * Stops a running synchronization of a specific configuration.
   *
   * @param configId The ID of the sync configuration to stop
   * @param stopPeriodicRun true if the config is synced by the periodic run, not by "Sync Now"
   */
  public void stopSync(@NonNull String configId, boolean stopPeriodicRun) {
    executor.execute(
        () -> {
          try {
            syncManager.stopSync(configId, stopPeriodicRun);
          } catch (Exception e) {
            LogUtils.e("MainViewModel", "Failed to stop sync: " + e.getMessage());
            errorHandler.recordError(
                e, "MainViewModel.stopSync", SmartErrorHandler.ErrorSeverity.MEDIUM);
          }
        });
  }

  /**
   * Updates an existing sync configuration.
   *
   * @param config The sync configuration to update
   */
  public void updateSyncConfig(@NonNull SyncConfig config) {
    LogUtils.d("MainViewModel", "Updating sync config: " + config.getId());
    syncManager.updateSyncConfig(config);
    loadSyncConfigs();
  }

  /**
   * Deletes a sync configuration.
   *
   * @param configId The ID of the sync configuration to delete
   */
  public void deleteSyncConfig(@NonNull String configId) {
    LogUtils.d("MainViewModel", "Deleting sync configuration with ID: " + configId);
    executor.execute(
        () -> {
          try {
            syncManager.removeSyncConfig(configId);
            LogUtils.i("MainViewModel", "Sync configuration deleted successfully: " + configId);
            loadSyncConfigs(); // Reload the list
          } catch (Exception e) {
            LogUtils.e("MainViewModel", "Failed to delete sync configuration: " + e.getMessage());
            errorHandler.recordError(
                e, "MainViewModel.deleteSyncConfig", SmartErrorHandler.ErrorSeverity.MEDIUM);
          }
        });
  }

  /** Loads the list of connections from the repository. */
  public void loadConnections() {
    LogUtils.d("MainViewModel", "Loading connections");
    executor.execute(
        () -> {
          try {
            List<SmbConnection> connectionList = connectionRepository.getAllConnections();
            connections.postValue(connectionList);
            LogUtils.d("MainViewModel", "Loaded " + connectionList.size() + " connections");
          } catch (Exception e) {
            LogUtils.e("MainViewModel", "Failed to load connections: " + e.getMessage());

            // Record connection error
            errorHandler.recordError(
                e, "MainViewModel.loadConnections", SmartErrorHandler.ErrorSeverity.HIGH);

            errorMessage.postValue(
                new ErrorMessage(R.string.connections_load_failed, e.getMessage()));
          }
        });
  }

  /**
   * Saves a connection to the repository.
   *
   * @param connection The connection to save
   */
  public void saveConnection(@NonNull SmbConnection connection) {
    LogUtils.d("MainViewModel", "Saving connection: " + connection.getName());
    executor.execute(
        () -> {
          try {
            connectionRepository.saveConnection(connection);
            LogUtils.i("MainViewModel", "Connection saved successfully: " + connection.getName());
            loadConnections(); // Reload the list
          } catch (Exception e) {
            LogUtils.e("MainViewModel", "Failed to save connection: " + e.getMessage());

            // Record save error
            errorHandler.recordError(
                e, "MainViewModel.saveConnection", SmartErrorHandler.ErrorSeverity.MEDIUM);

            errorMessage.postValue(
                new ErrorMessage(R.string.connection_save_failed, e.getMessage()));
          }
        });
  }

  /**
   * Deletes a connection from the repository.
   *
   * @param connectionId The ID of the connection to delete
   */
  public void deleteConnection(@NonNull String connectionId) {
    LogUtils.d("MainViewModel", "Deleting connection with ID: " + connectionId);
    executor.execute(
        () -> {
          try {
            connectionRepository.deleteConnection(connectionId);
            syncManager.removeConfigsForConnection(connectionId);
            LogUtils.i("MainViewModel", "Connection deleted successfully: " + connectionId);
            loadConnections(); // Reload the list
            loadSyncConfigs(); // Its sync configs are gone as well
          } catch (Exception e) {
            LogUtils.e("MainViewModel", "Failed to delete connection: " + e.getMessage());

            // Record delete error
            errorHandler.recordError(
                e, "MainViewModel.deleteConnection", SmartErrorHandler.ErrorSeverity.MEDIUM);

            errorMessage.postValue(
                new ErrorMessage(R.string.connection_delete_failed, e.getMessage()));
          }
        });
  }

  /**
   * Tests a connection to an SMB server.
   *
   * @param connection The connection to test
   * @param callback Callback to be called with the result
   */
  public void testConnection(
      @NonNull SmbConnection connection, @Nullable ConnectionTestCallback callback) {
    LogUtils.d(
        "MainViewModel",
        "Testing connection to: " + connection.getServer() + "/" + connection.getShare());
    isLoading.setValue(true);

    executor.execute(
        () -> {
          try {
            boolean success = smbRepository.testConnection(connection);
            String detail = null;
            if (success) {
              try {
                detail = smbRepository.getConnectionSecurityInfo(connection);
              } catch (Exception e) {
                LogUtils.w(
                    "MainViewModel", "Could not read connection security: " + e.getMessage());
              }
            }
            isLoading.postValue(false);
            if (success) {
              LogUtils.i("MainViewModel", "Connection test successful: " + detail);
            } else {
              LogUtils.w("MainViewModel", "Connection test failed: " + connection.getName());
            }
            callback.onResult(success, detail);
          } catch (Exception e) {
            LogUtils.e("MainViewModel", "Connection test error: " + e.getMessage());

            // Record network/SMB error
            errorHandler.recordError(
                e, "MainViewModel.testConnection", SmartErrorHandler.ErrorSeverity.MEDIUM);

            isLoading.postValue(false);
            callback.onResult(false, describeFailure(e));
          }
        });
  }

  /**
   * Joins the messages of an exception and its causes. The repository wraps SMB errors (e.g. {@code
   * STATUS_LOGON_FAILURE}) in a generic "Failed to execute share operation" exception, so the
   * reason is only found in the cause chain.
   *
   * @param error the exception thrown by the connection test
   * @return the messages of the exception and its causes, separated by ": "
   */
  @NonNull
  public static String describeFailure(@NonNull Throwable error) {
    StringBuilder text = new StringBuilder();
    java.util.Set<Throwable> seen =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    for (Throwable t = error; t != null && seen.add(t); t = t.getCause()) {
      String message = t.getMessage();
      if (message == null || message.trim().isEmpty()) {
        message = t.getClass().getSimpleName();
      }
      if (text.indexOf(message) >= 0) {
        continue;
      }
      if (text.length() > 0) {
        text.append(": ");
      }
      text.append(message.trim());
    }
    return text.toString();
  }

  /**
   * Checks whether the given default folder exists on the server. If the existence check itself
   * fails (e.g. server unreachable), the folder is treated as existing so saving is not blocked.
   *
   * @param connection The connection to check the folder on
   * @param path The share-relative folder path to check
   * @param callback Callback to be called with the result
   */
  public void checkDefaultFolder(
      @NonNull SmbConnection connection,
      @NonNull String path,
      @NonNull DefaultFolderCheckCallback callback) {
    LogUtils.d("MainViewModel", "Checking default folder existence: " + path);
    isLoading.setValue(true);

    executor.execute(
        () -> {
          boolean exists;
          try {
            exists = smbRepository.folderExists(connection, path);
          } catch (Exception e) {
            LogUtils.w("MainViewModel", "Default folder check failed: " + e.getMessage());
            // Cannot verify (e.g. server offline): do not block saving
            exists = true;
          }
          isLoading.postValue(false);
          callback.onResult(exists);
        });
  }

  /** Lists shares available on the specified server. */
  public void listShares(@NonNull SmbConnection connection, @Nullable ShareListCallback callback) {
    LogUtils.d("MainViewModel", "Listing shares for server: " + connection.getServer());
    executor.execute(
        () -> {
          try {
            List<String> shares = smbRepository.listShares(connection);
            LogUtils.i(
                "MainViewModel",
                "Found " + shares.size() + " shares on server: " + connection.getServer());
            callback.onSuccess(shares);
          } catch (Exception e) {
            String error =
                "Failed to list shares on server " + connection.getServer() + ": " + e.getMessage();
            LogUtils.e("MainViewModel", error);
            LogUtils.e(e, "Share listing exception details");

            // Record SMB/network error
            errorHandler.recordError(
                e, "MainViewModel.listShares", SmartErrorHandler.ErrorSeverity.MEDIUM);

            callback.onError(error);
          }
        });
  }

  /** Callback interface for default folder existence checks. */
  public interface DefaultFolderCheckCallback {
    void onResult(boolean exists);
  }

  /** Callback interface for connection testing. */
  public interface ConnectionTestCallback {
    /**
     * @param success whether the connection could be opened
     * @param detail on success the negotiated protocol and cipher (may be null), on failure the
     *     technical error including its causes
     */
    void onResult(boolean success, @Nullable String detail);
  }

  /** Callback interface for share listing operations. */
  public interface ShareListCallback {
    void onSuccess(@NonNull List<String> shares);

    void onError(@NonNull String error);
  }

  /** Returns the tracker of finished sync work already announced on the main screen. */
  @NonNull
  SyncWorkReporter getSyncWorkReporter() {
    return syncWorkReporter;
  }
}
