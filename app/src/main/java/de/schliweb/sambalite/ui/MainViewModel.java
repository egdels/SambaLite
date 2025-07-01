package de.schliweb.sambalite.ui;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.repository.ConnectionRepository;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.util.LogUtils;

import javax.inject.Inject;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * ViewModel for the MainActivity.
 * Handles the list of connections and operations on them.
 */
public class MainViewModel extends ViewModel {

    private final ConnectionRepository connectionRepository;
    private final SmbRepository smbRepository;
    private final Executor executor;

    private final MutableLiveData<List<SmbConnection>> connections = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(false);
    private final MutableLiveData<String> errorMessage = new MutableLiveData<>();

    @Inject
    public MainViewModel(ConnectionRepository connectionRepository, SmbRepository smbRepository) {
        this.connectionRepository = connectionRepository;
        this.smbRepository = smbRepository;
        this.executor = Executors.newSingleThreadExecutor();

        LogUtils.d("MainViewModel", "MainViewModel initialized");

        // Load connections when the ViewModel is created
        loadConnections();
    }

    /**
     * Gets the list of connections as LiveData.
     */
    public LiveData<List<SmbConnection>> getConnections() {
        return connections;
    }

    /**
     * Gets the loading state as LiveData.
     */
    public LiveData<Boolean> isLoading() {
        return isLoading;
    }

    /**
     * Gets the error message as LiveData.
     */
    public LiveData<String> getErrorMessage() {
        return errorMessage;
    }

    /**
     * Loads the list of connections from the repository.
     */
    public void loadConnections() {
        LogUtils.d("MainViewModel", "Loading connections");
        executor.execute(() -> {
            try {
                List<SmbConnection> connectionList = connectionRepository.getAllConnections();
                connections.postValue(connectionList);
                LogUtils.d("MainViewModel", "Loaded " + connectionList.size() + " connections");
            } catch (Exception e) {
                LogUtils.e("MainViewModel", "Failed to load connections: " + e.getMessage());
                errorMessage.postValue("Failed to load connections: " + e.getMessage());
            }
        });
    }

    /**
     * Saves a connection to the repository.
     *
     * @param connection The connection to save
     */
    public void saveConnection(SmbConnection connection) {
        LogUtils.d("MainViewModel", "Saving connection: " + connection.getName());
        executor.execute(() -> {
            try {
                connectionRepository.saveConnection(connection);
                LogUtils.i("MainViewModel", "Connection saved successfully: " + connection.getName());
                loadConnections(); // Reload the list
            } catch (Exception e) {
                LogUtils.e("MainViewModel", "Failed to save connection: " + e.getMessage());
                errorMessage.postValue("Failed to save connection: " + e.getMessage());
            }
        });
    }

    /**
     * Deletes a connection from the repository.
     *
     * @param connectionId The ID of the connection to delete
     */
    public void deleteConnection(String connectionId) {
        LogUtils.d("MainViewModel", "Deleting connection with ID: " + connectionId);
        executor.execute(() -> {
            try {
                connectionRepository.deleteConnection(connectionId);
                LogUtils.i("MainViewModel", "Connection deleted successfully: " + connectionId);
                loadConnections(); // Reload the list
            } catch (Exception e) {
                LogUtils.e("MainViewModel", "Failed to delete connection: " + e.getMessage());
                errorMessage.postValue("Failed to delete connection: " + e.getMessage());
            }
        });
    }

    /**
     * Tests a connection to an SMB server.
     *
     * @param connection The connection to test
     * @param callback   Callback to be called with the result
     */
    public void testConnection(SmbConnection connection, ConnectionTestCallback callback) {
        LogUtils.d("MainViewModel", "Testing connection to: " + connection.getServer() + "/" + connection.getShare());
        isLoading.setValue(true);

        executor.execute(() -> {
            try {
                boolean success = smbRepository.testConnection(connection);
                isLoading.postValue(false);
                if (success) {
                    LogUtils.i("MainViewModel", "Connection test successful: " + connection.getName());
                } else {
                    LogUtils.w("MainViewModel", "Connection test failed: " + connection.getName());
                }
                callback.onResult(success, success ? "Connection successful" : "Connection failed");
            } catch (Exception e) {
                LogUtils.e("MainViewModel", "Connection test error: " + e.getMessage());
                isLoading.postValue(false);
                callback.onResult(false, "Connection failed: " + e.getMessage());
            }
        });
    }

    /**
     * Callback interface for connection testing.
     */
    public interface ConnectionTestCallback {
        void onResult(boolean success, String message);
    }
}
