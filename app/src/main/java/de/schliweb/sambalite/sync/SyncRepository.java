package de.schliweb.sambalite.sync;

import android.content.Context;
import android.content.SharedPreferences;
import de.schliweb.sambalite.util.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Repository for persisting folder synchronization configurations using SharedPreferences. */
@Singleton
public class SyncRepository {

  private static final String TAG = "SyncRepository";
  private static final String PREFS_NAME = "sambalite_sync_configs";
  private static final String KEY_SYNC_CONFIGS = "sync_configs";

  private final SharedPreferences prefs;

  @Inject
  public SyncRepository(Context context) {
    LogUtils.d(TAG, "Initializing SyncRepository");
    this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  /**
   * Saves a sync configuration. Generates a UUID if the config has no ID.
   *
   * @param config the sync configuration to save
   * @return the saved configuration (with ID set)
   */
  public SyncConfig saveSyncConfig(SyncConfig config) {
    LogUtils.d(TAG, "Saving sync config: " + config);
    List<SyncConfig> configs = getAllSyncConfigs();

    if (config.getId() == null || config.getId().isEmpty()) {
      config.setId(UUID.randomUUID().toString());
      LogUtils.d(TAG, "Generated new ID for sync config: " + config.getId());
    } else {
      LogUtils.d(TAG, "Updating existing sync config with ID: " + config.getId());
      configs.removeIf(c -> c.getId().equals(config.getId()));
    }

    configs.add(config);
    saveConfigsToPrefs(configs);
    LogUtils.i(TAG, "Sync config saved successfully: " + config.getId());
    return config;
  }

  /**
   * Returns all sync configurations.
   *
   * @return list of all sync configurations
   */
  public List<SyncConfig> getAllSyncConfigs() {
    LogUtils.d(TAG, "Getting all sync configs");
    String json = prefs.getString(KEY_SYNC_CONFIGS, "[]");

    try {
      JSONArray jsonArray = new JSONArray(json);
      List<SyncConfig> configs = new ArrayList<>(jsonArray.length());

      for (int i = 0; i < jsonArray.length(); i++) {
        JSONObject jsonObject = jsonArray.getJSONObject(i);
        configs.add(jsonToConfig(jsonObject));
      }

      LogUtils.d(TAG, "Retrieved " + configs.size() + " sync configs");
      return configs;
    } catch (JSONException e) {
      LogUtils.e(TAG, "Error parsing sync configs JSON: " + e.getMessage());
      return new ArrayList<>();
    }
  }

  /**
   * Returns all enabled sync configurations.
   *
   * @return list of enabled sync configurations
   */
  public List<SyncConfig> getAllEnabledConfigs() {
    List<SyncConfig> all = getAllSyncConfigs();
    List<SyncConfig> enabled = new ArrayList<>();
    for (SyncConfig config : all) {
      if (config.isEnabled()) {
        enabled.add(config);
      }
    }
    return enabled;
  }

  /**
   * Returns all enabled sync configurations for a specific connection.
   *
   * @param connectionId the connection ID to filter by
   * @return list of enabled sync configurations for the connection
   */
  public List<SyncConfig> getEnabledConfigsForConnection(String connectionId) {
    List<SyncConfig> all = getAllSyncConfigs();
    List<SyncConfig> result = new ArrayList<>();
    for (SyncConfig config : all) {
      if (config.isEnabled() && connectionId.equals(config.getConnectionId())) {
        result.add(config);
      }
    }
    return result;
  }

  /**
   * Deletes a sync configuration by ID.
   *
   * @param configId the ID of the configuration to delete
   * @return true if the configuration was found and deleted
   */
  public boolean deleteSyncConfig(String configId) {
    LogUtils.d(TAG, "Deleting sync config with ID: " + configId);
    List<SyncConfig> configs = getAllSyncConfigs();
    boolean removed = configs.removeIf(c -> c.getId().equals(configId));

    if (removed) {
      LogUtils.i(TAG, "Sync config deleted successfully: " + configId);
      saveConfigsToPrefs(configs);
    } else {
      LogUtils.w(TAG, "Sync config not found for deletion: " + configId);
    }

    return removed;
  }

  /**
   * Deletes all sync configurations for a specific connection.
   *
   * @param connectionId the connection ID whose configs should be deleted
   * @return the number of deleted configurations
   */
  public int deleteConfigsForConnection(String connectionId) {
    LogUtils.d(TAG, "Deleting sync configs for connection: " + connectionId);
    List<SyncConfig> configs = getAllSyncConfigs();
    int originalSize = configs.size();
    configs.removeIf(c -> connectionId.equals(c.getConnectionId()));
    int removed = originalSize - configs.size();

    if (removed > 0) {
      saveConfigsToPrefs(configs);
      LogUtils.i(TAG, "Deleted " + removed + " sync configs for connection: " + connectionId);
    }

    return removed;
  }

  /**
   * Updates the last sync timestamp for a specific configuration.
   *
   * @param configId the ID of the configuration to update
   * @param timestamp the new timestamp value
   */
  public void updateLastSyncTimestamp(String configId, long timestamp) {
    LogUtils.d(TAG, "Updating last sync timestamp for config: " + configId);
    List<SyncConfig> configs = getAllSyncConfigs();

    for (SyncConfig config : configs) {
      if (config.getId().equals(configId)) {
        config.setLastSyncTimestamp(timestamp);
        saveConfigsToPrefs(configs);
        LogUtils.d(TAG, "Last sync timestamp updated for config: " + configId);
        return;
      }
    }

    LogUtils.w(TAG, "Sync config not found for timestamp update: " + configId);
  }

  /** Saves the list of configurations to SharedPreferences. */
  private void saveConfigsToPrefs(List<SyncConfig> configs) {
    LogUtils.d(TAG, "Saving " + configs.size() + " sync configs to preferences");
    try {
      JSONArray jsonArray = new JSONArray();

      for (SyncConfig config : configs) {
        jsonArray.put(configToJson(config));
      }

      prefs.edit().putString(KEY_SYNC_CONFIGS, jsonArray.toString()).apply();
      LogUtils.d(TAG, "Sync configs saved successfully to preferences");
    } catch (JSONException e) {
      LogUtils.e(TAG, "Error saving sync configs to preferences: " + e.getMessage());
    }
  }

  /** Converts a SyncConfig to a JSONObject. */
  private JSONObject configToJson(SyncConfig config) throws JSONException {
    JSONObject json = new JSONObject();
    json.put("id", config.getId());
    json.put("connectionId", config.getConnectionId());
    json.put("localFolderUri", config.getLocalFolderUri());
    json.put("remotePath", config.getRemotePath());
    json.put("localFolderDisplayName", config.getLocalFolderDisplayName());
    json.put("enabled", config.isEnabled());
    json.put("lastSyncTimestamp", config.getLastSyncTimestamp());
    json.put("direction", config.getDirection().name());
    json.put("intervalMinutes", config.getIntervalMinutes());
    return json;
  }

  /** Converts a JSONObject to a SyncConfig. */
  private SyncConfig jsonToConfig(JSONObject json) throws JSONException {
    SyncConfig config = new SyncConfig();
    config.setId(json.getString("id"));
    config.setConnectionId(json.optString("connectionId", ""));
    config.setLocalFolderUri(json.optString("localFolderUri", ""));
    config.setRemotePath(json.optString("remotePath", ""));
    config.setLocalFolderDisplayName(json.optString("localFolderDisplayName", ""));
    config.setEnabled(json.optBoolean("enabled", true));
    config.setLastSyncTimestamp(json.optLong("lastSyncTimestamp", 0));
    config.setDirection(SyncDirection.valueOf(json.optString("direction", "BIDIRECTIONAL")));
    config.setIntervalMinutes(json.optInt("intervalMinutes", 60));
    return config;
  }
}
