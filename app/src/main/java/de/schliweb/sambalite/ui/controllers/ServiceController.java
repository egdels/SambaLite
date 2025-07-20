package de.schliweb.sambalite.ui.controllers;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import de.schliweb.sambalite.service.SmbBackgroundService;
import de.schliweb.sambalite.util.LogUtils;

/**
 * Controller for managing background service binding and communication.
 * This controller is responsible for binding to the background service,
 * unbinding from the background service, and communicating with the
 * background service for operations like starting/stopping operations
 * and updating progress.
 */
public class ServiceController implements LifecycleEventObserver {

    private final AppCompatActivity activity;
    private final FileBrowserUIState uiState;

    private ServiceConnection serviceConnection;
    private boolean isServiceBound = false;
    private SmbBackgroundService backgroundService;

    /**
     * Creates a new ServiceController.
     *
     * @param activity The activity
     * @param uiState  The shared UI state
     */
    public ServiceController(AppCompatActivity activity, FileBrowserUIState uiState) {
        this.activity = activity;
        this.uiState = uiState;

        // Register for lifecycle events to automatically bind/unbind the service
        activity.getLifecycle().addObserver(this);

        LogUtils.d("ServiceController", "ServiceController initialized");
    }

    /**
     * Handles lifecycle events to automatically bind/unbind the service.
     *
     * @param source The lifecycle owner
     * @param event  The lifecycle event
     */
    @Override
    public void onStateChanged(LifecycleOwner source, Lifecycle.Event event) {
        if (event == Lifecycle.Event.ON_RESUME) {
            bindToBackgroundService();
        } else if (event == Lifecycle.Event.ON_PAUSE) {
            unbindFromBackgroundService();
        }
    }

    /**
     * Binds to the background service.
     */
    public void bindToBackgroundService() {
        if (!isServiceBound) {
            serviceConnection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    LogUtils.d("ServiceController", "Background service connected");
                    SmbBackgroundService.LocalBinder binder = (SmbBackgroundService.LocalBinder) service;
                    backgroundService = binder.getService();
                    isServiceBound = true;

                    // Update the UI state
                    uiState.setBackgroundService(backgroundService);
                    uiState.setServiceBound(true);
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    LogUtils.d("ServiceController", "Background service disconnected");
                    backgroundService = null;
                    isServiceBound = false;

                    // Update the UI state
                    uiState.setBackgroundService(null);
                    uiState.setServiceBound(false);
                }
            };

            Intent serviceIntent = new Intent(activity, SmbBackgroundService.class);
            activity.startService(serviceIntent); // Start service first
            activity.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

            LogUtils.d("ServiceController", "Binding to background service");
        }
    }

    /**
     * Unbinds from the background service.
     */
    public void unbindFromBackgroundService() {
        if (isServiceBound && serviceConnection != null) {
            LogUtils.d("ServiceController", "Unbinding from background service");
            activity.unbindService(serviceConnection);
            isServiceBound = false;
            backgroundService = null;

            // Update the UI state
            uiState.setBackgroundService(null);
            uiState.setServiceBound(false);
        }
    }

    /**
     * Starts an operation in the background service.
     *
     * @param operationName The name of the operation
     */
    public void startOperation(String operationName) {
        if (isServiceBound && backgroundService != null) {
            LogUtils.d("ServiceController", "Starting operation in background service: " + operationName);
            backgroundService.startOperation(operationName);
        }
    }

    /**
     * Starts a search operation in the background service.
     *
     * @param operationName     The name of the operation
     * @param connectionId      The ID of the connection being searched
     * @param searchQuery       The search query
     * @param searchType        The type of search (0=all, 1=files only, 2=folders only)
     * @param includeSubfolders Whether to include subfolders in the search
     */
    public void startSearchOperation(String operationName, String connectionId, String searchQuery, int searchType, boolean includeSubfolders) {
        if (isServiceBound && backgroundService != null) {
            LogUtils.d("ServiceController", "Starting search operation in background service: " + operationName + ", connectionId: " + connectionId + ", query: " + searchQuery);

            // Set search parameters in the service
            backgroundService.setSearchParameters(connectionId, searchQuery, searchType, includeSubfolders);

            // Start the operation
            backgroundService.startOperation(operationName);
        }
    }

    /**
     * Finishes an operation in the background service.
     *
     * @param operationName The name of the operation
     * @param success       Whether the operation was successful
     */
    public void finishOperation(String operationName, boolean success) {
        if (isServiceBound && backgroundService != null) {
            LogUtils.d("ServiceController", "Finishing operation in background service: " + operationName + ", success: " + success);

            // Clear search parameters if this was a search operation
            if (operationName.startsWith("Searching for:")) {
                backgroundService.clearSearchParameters();
            }

            backgroundService.finishOperation(operationName, success);
        }
    }

    /**
     * Updates file progress in the background service.
     *
     * @param operationName   The name of the operation
     * @param currentFile     The current file index
     * @param totalFiles      The total number of files
     * @param currentFileName The name of the current file
     */
    public void updateFileProgress(String operationName, int currentFile, int totalFiles, String currentFileName) {
        if (isServiceBound && backgroundService != null) {
            LogUtils.d("ServiceController", "Updating file progress in background service: " + operationName + ", " + currentFile + "/" + totalFiles + ", " + currentFileName);
            backgroundService.updateFileProgress(operationName, currentFile, totalFiles, currentFileName);
        }
    }

    /**
     * Updates bytes progress in the background service.
     *
     * @param operationName The name of the operation
     * @param currentBytes  The current number of bytes
     * @param totalBytes    The total number of bytes
     * @param fileName      The name of the file
     */
    public void updateBytesProgress(String operationName, long currentBytes, long totalBytes, String fileName) {
        if (isServiceBound && backgroundService != null) {
            LogUtils.d("ServiceController", "Updating bytes progress in background service: " + operationName + ", " + currentBytes + "/" + totalBytes + ", " + fileName);
            backgroundService.updateBytesProgress(operationName, currentBytes, totalBytes, fileName);
        }
    }

    /**
     * Updates operation progress in the background service.
     *
     * @param operationName The name of the operation
     * @param progressInfo  The progress information
     */
    public void updateOperationProgress(String operationName, String progressInfo) {
        if (isServiceBound && backgroundService != null) {
            LogUtils.d("ServiceController", "Updating operation progress in background service: " + operationName + ", " + progressInfo);
            backgroundService.updateOperationProgress(operationName, progressInfo);
        }
    }

    /**
     * Sets upload operation parameters in the background service.
     *
     * @param connectionId The ID of the connection
     * @param uploadPath   The path where the file is being uploaded
     */
    public void setUploadParameters(String connectionId, String uploadPath) {
        if (isServiceBound && backgroundService != null) {
            LogUtils.d("ServiceController", "Setting upload parameters: connectionId=" + connectionId + ", path=" + uploadPath);
            backgroundService.setUploadParameters(connectionId, uploadPath);
        }
    }

    /**
     * Sets download operation parameters in the background service.
     *
     * @param connectionId The ID of the connection
     * @param downloadPath The path where the file is being downloaded from
     */
    public void setDownloadParameters(String connectionId, String downloadPath) {
        if (isServiceBound && backgroundService != null) {
            LogUtils.d("ServiceController", "Setting download parameters: connectionId=" + connectionId + ", path=" + downloadPath);
            backgroundService.setDownloadParameters(connectionId, downloadPath);
        }
    }

    /**
     * Cleans up resources when the controller is no longer needed.
     * This should be called when the activity is destroyed.
     */
    public void cleanup() {
        LogUtils.d("ServiceController", "Cleaning up ServiceController");
        activity.getLifecycle().removeObserver(this);
        unbindFromBackgroundService();
    }
}