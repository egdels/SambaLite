package de.schliweb.sambalite.data.repository;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.util.LogUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Implementation of ConnectionRepository that securely stores connection information
 * using EncryptedSharedPreferences.
 */
@Singleton
public class ConnectionRepositoryImpl implements ConnectionRepository {

    private static final String PREFS_NAME = "sambalite_connections";
    private static final String KEY_CONNECTIONS = "connections";

    private final SharedPreferences encryptedPrefs;

    @Inject
    public ConnectionRepositoryImpl(Context context) {
        LogUtils.d("ConnectionRepositoryImpl", "Initializing ConnectionRepositoryImpl");
        // Initialize EncryptedSharedPreferences
        SharedPreferences prefs;
        try {
            MasterKey masterKey = new MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();

            prefs = EncryptedSharedPreferences.create(context, PREFS_NAME, masterKey, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
            LogUtils.i("ConnectionRepositoryImpl", "EncryptedSharedPreferences initialized successfully");
        } catch (GeneralSecurityException | IOException e) {
            // Fallback to regular SharedPreferences in case of error
            // This should not happen in production, but we need a fallback
            LogUtils.e("ConnectionRepositoryImpl", "Failed to initialize EncryptedSharedPreferences: " + e.getMessage());
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            LogUtils.w("ConnectionRepositoryImpl", "Falling back to regular SharedPreferences");
        }

        this.encryptedPrefs = prefs;
    }

    @Override
    public SmbConnection saveConnection(SmbConnection connection) {
        LogUtils.d("ConnectionRepositoryImpl", "Saving connection: " + connection.getName());
        List<SmbConnection> connections = getAllConnections();

        // Generate ID if it's a new connection
        if (connection.getId() == null || connection.getId().isEmpty()) {
            connection.setId(UUID.randomUUID().toString());
            LogUtils.d("ConnectionRepositoryImpl", "Generated new ID for connection: " + connection.getId());
        } else {
            // Remove existing connection with the same ID
            LogUtils.d("ConnectionRepositoryImpl", "Updating existing connection with ID: " + connection.getId());
            connections.removeIf(c -> c.getId().equals(connection.getId()));
        }

        // Add the connection to the list
        connections.add(connection);

        // Save the updated list
        saveConnectionsToPrefs(connections);
        LogUtils.i("ConnectionRepositoryImpl", "Connection saved successfully: " + connection.getName());

        return connection;
    }


    @Override
    public List<SmbConnection> getAllConnections() {
        LogUtils.d("ConnectionRepositoryImpl", "Getting all connections");
        String connectionsJson = encryptedPrefs.getString(KEY_CONNECTIONS, "[]");

        try {
            JSONArray jsonArray = new JSONArray(connectionsJson);
            List<SmbConnection> connections = new ArrayList<>(jsonArray.length());

            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jsonObject = jsonArray.getJSONObject(i);
                SmbConnection connection = jsonToConnection(jsonObject);
                connections.add(connection);
            }

            LogUtils.d("ConnectionRepositoryImpl", "Retrieved " + connections.size() + " connections");
            return connections;
        } catch (JSONException e) {
            // Return empty list in case of error
            LogUtils.e("ConnectionRepositoryImpl", "Error parsing connections JSON: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public boolean deleteConnection(String id) {
        LogUtils.d("ConnectionRepositoryImpl", "Deleting connection with ID: " + id);
        List<SmbConnection> connections = getAllConnections();
        boolean removed = connections.removeIf(c -> c.getId().equals(id));

        if (removed) {
            LogUtils.i("ConnectionRepositoryImpl", "Connection deleted successfully: " + id);
            saveConnectionsToPrefs(connections);
        } else {
            LogUtils.w("ConnectionRepositoryImpl", "Connection not found for deletion: " + id);
        }

        return removed;
    }


    /**
     * Saves the list of connections to SharedPreferences.
     */
    private void saveConnectionsToPrefs(List<SmbConnection> connections) {
        LogUtils.d("ConnectionRepositoryImpl", "Saving " + connections.size() + " connections to preferences");
        try {
            JSONArray jsonArray = new JSONArray();

            for (SmbConnection connection : connections) {
                JSONObject jsonObject = connectionToJson(connection);
                jsonArray.put(jsonObject);
            }

            encryptedPrefs.edit().putString(KEY_CONNECTIONS, jsonArray.toString()).apply();
            LogUtils.d("ConnectionRepositoryImpl", "Connections saved successfully to preferences");
        } catch (JSONException e) {
            LogUtils.e("ConnectionRepositoryImpl", "Error saving connections to preferences: " + e.getMessage());
        }
    }

    /**
     * Converts a SmbConnection to a JSONObject.
     */
    private JSONObject connectionToJson(SmbConnection connection) throws JSONException {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("id", connection.getId());
        jsonObject.put("name", connection.getName());
        jsonObject.put("server", connection.getServer());
        jsonObject.put("share", connection.getShare());
        jsonObject.put("username", connection.getUsername());
        jsonObject.put("password", connection.getPassword());
        jsonObject.put("domain", connection.getDomain());
        return jsonObject;
    }

    /**
     * Converts a JSONObject to a SmbConnection.
     */
    private SmbConnection jsonToConnection(JSONObject jsonObject) throws JSONException {
        SmbConnection connection = new SmbConnection();
        connection.setId(jsonObject.getString("id"));
        connection.setName(jsonObject.getString("name"));
        connection.setServer(jsonObject.getString("server"));
        connection.setShare(jsonObject.optString("share", ""));
        connection.setUsername(jsonObject.optString("username", ""));
        connection.setPassword(jsonObject.optString("password", ""));
        connection.setDomain(jsonObject.optString("domain", ""));
        return connection;
    }
}
