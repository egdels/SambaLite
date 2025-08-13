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

    public void setSearchContext(String connectionId, String query, int type, boolean includeSubs) {
        bg.setSearchContext(connectionId, query, type, includeSubs);
    }

    public void cleanup() {
        activity.getLifecycle().removeObserver(this);
        LogUtils.d(TAG, "ServiceController cleanup done");
    }

}
