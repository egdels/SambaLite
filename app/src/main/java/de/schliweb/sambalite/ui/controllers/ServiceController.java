package de.schliweb.sambalite.ui.controllers;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.util.LogUtils;

import javax.inject.Inject;
import java.util.concurrent.CompletableFuture;

/**
 * Controller for handling background operations.
 * This controller is responsible for starting and managing background operations.
 */
public class ServiceController implements LifecycleEventObserver {

    private static final String TAG = "ServiceController";

    private final AppCompatActivity activity;
    private final FileBrowserUIState uiState;
    private final BackgroundSmbManager bg;

    @Inject
    public ServiceController(AppCompatActivity activity,
                             FileBrowserUIState uiState,
                             BackgroundSmbManager backgroundSmbManager) {
        this.activity = activity;
        this.uiState = uiState;
        this.bg = backgroundSmbManager;

        activity.getLifecycle().addObserver(this);
        LogUtils.d(TAG, "ServiceController initialized");
        uiState.setServiceBound(bg.isServiceConnected());
        uiState.setBackgroundService(null); // Set to null, because we are not bound yet.
    }

    @Override
    public void onStateChanged(LifecycleOwner source, Lifecycle.Event event) {
        if (event == Lifecycle.Event.ON_RESUME || event == Lifecycle.Event.ON_START) {
            uiState.setServiceBound(bg.isServiceConnected());
        }
    }

    /**
     * Execute a background operation.
     *
     * @param operationId
     * @param operationName
     * @param operation
     * @param <T>
     * @return
     */
    public <T> CompletableFuture<T> executeOperation(
            String operationId,
            String operationName,
            BackgroundSmbManager.BackgroundOperation<T> operation) {
        LogUtils.d(TAG, "executeOperation -> " + operationName);
        return bg.executeBackgroundOperation(operationId, operationName, operation);
    }

    /**
     * Execute a multi-file background operation.
     *
     * @param operationId
     * @param operationName
     * @param totalFiles
     * @param operation
     * @param <T>
     * @return
     */
    public <T> CompletableFuture<T> executeMultiFileOperation(
            String operationId,
            String operationName,
            int totalFiles,
            BackgroundSmbManager.MultiFileOperation<T> operation) {
        LogUtils.d(TAG, "executeMultiFileOperation -> " + operationName + " (" + totalFiles + ")");
        return bg.executeMultiFileOperation(operationId, operationName, totalFiles, operation);
    }

    public void setSearchContext(String connectionId, String query, int type, boolean includeSubs) {
        bg.setSearchContext(connectionId, query, type, includeSubs);
    }

    public void setUploadContext(String connectionId, String uploadPath) {
        bg.setUploadContext(connectionId, uploadPath);
    }

    public void setDownloadContext(String connectionId, String downloadPath) {
        bg.setDownloadContext(connectionId, downloadPath);
    }

    /**
     * Cancel-All (z. B. von der Notification-Aktion).
     */
    public void requestCancelAll() {
        bg.requestCancelAllOperations();
    }

    public void cleanup() {
        activity.getLifecycle().removeObserver(this);
        // kein unbind nötig; Manager kümmert sich.
        LogUtils.d(TAG, "ServiceController cleanup done");
    }

    public void startOperation(String operationName) {
        bg.startOperation(operationName);
    }

    public void updateOperationProgress(String n, String info) {
        bg.updateOperationProgress(n, info);
    }

    public void updateFileProgress(String n, int c, int t, String f) {
        bg.updateFileProgress(n, c, t, f);
    }

    public void updateBytesProgress(String n, long cb, long tb, String f) {
        bg.updateBytesProgress(n, cb, tb, f);
    }

    public void finishOperation(String n, boolean ok) {
        bg.finishOperation(n, ok);
    }

    // Parameter
    public void setSearchParameters(String id, String q, int type, boolean sub) {
        bg.setSearchParameters(id, q, type, sub);
    }

    public void setUploadParameters(String id, String path) {
        bg.setUploadParameters(id, path);
    }

    public void setDownloadParameters(String id, String path) {
        bg.setDownloadParameters(id, path);
    }

}
