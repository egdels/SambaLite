package de.schliweb.sambalite.data.background;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import de.schliweb.sambalite.service.SmbBackgroundService;
import de.schliweb.sambalite.util.EnhancedFileUtils;
import de.schliweb.sambalite.util.LogUtils;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manager for background-aware SMB connections.
 * Coordinates between the foreground service and SMB operations
 * to ensure reliable background execution.
 */
@Singleton
public class BackgroundSmbManager {

    private static final String TAG = "BackgroundSmbManager";

    private final Context context;
    private final AtomicBoolean serviceConnected = new AtomicBoolean(false);

    private SmbBackgroundService backgroundService;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            LogUtils.d(TAG, "Background service connected");
            SmbBackgroundService.LocalBinder binder = (SmbBackgroundService.LocalBinder) service;
            backgroundService = binder.getService();
            serviceConnected.set(true);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            LogUtils.w(TAG, "Background service disconnected");
            backgroundService = null;
            serviceConnected.set(false);
        }
    };

    @Inject
    public BackgroundSmbManager(Context context) {
        this.context = context;
        initializeService();
    }

    /**
     * Initializes the SMB background service by starting it in the foreground and binding it
     * for direct communication. This method ensures the service is running and establishes
     * a connection for interaction between the application and the service.
     */
    private void initializeService() {
        LogUtils.d(TAG, "Initializing background service");

        // Service starten
        Intent serviceIntent = new Intent(context, SmbBackgroundService.class);
        context.startForegroundService(serviceIntent);

        // Bind service for direct communication
        context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    /**
     * Executes a background operation asynchronously, providing progress updates and completion notifications
     * to a connected background service if available.
     *
     * @param <T>           The type of result produced by the background operation.
     * @param operationId   A unique identifier for the operation.
     * @param operationName A human-readable name for the operation, used for logging and service updates.
     * @param operation     The background operation to execute, which supports progress callbacks.
     * @return A {@link CompletableFuture} representing the pending result of the operation.
     *
     * <p>
     * The method performs the following steps:
     * <ul>
     *   <li>Logs the start of the operation.</li>
     *   <li>Notifies the background service (if connected) about the operation start.</li>
     *   <li>Runs the operation asynchronously, providing progress updates via callbacks.</li>
     *   <li>On completion, notifies the service and completes the future with the result.</li>
     *   <li>On failure, logs the error, notifies the service, and completes the future exceptionally.</li>
     * </ul>
     * </p>
     */
    public <T> CompletableFuture<T> executeBackgroundOperation(String operationId, String operationName, BackgroundOperation<T> operation) {

        LogUtils.d(TAG, "Starting background operation: " + operationName + " (ID: " + operationId + ")");

        CompletableFuture<T> future = new CompletableFuture<>();

        // Inform service about operation start
        if (serviceConnected.get() && backgroundService != null) {
            backgroundService.startOperation(operationName);
        }

        // Execute operation in separate thread
        CompletableFuture.runAsync(() -> {
            try {
                T result = operation.execute(new ProgressCallback() {
                    @Override
                    public void updateProgress(String progressInfo) {
                        if (serviceConnected.get() && backgroundService != null) {
                            backgroundService.updateOperationProgress(operationName, progressInfo);
                        }
                    }

                    @Override
                    public void updateFileProgress(int currentFile, int totalFiles, String currentFileName) {
                        if (serviceConnected.get() && backgroundService != null) {
                            backgroundService.updateFileProgress(operationName, currentFile, totalFiles, currentFileName);
                        }
                    }

                    @Override
                    public void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
                        if (serviceConnected.get() && backgroundService != null) {
                            backgroundService.updateBytesProgress(operationName, currentBytes, totalBytes, fileName);
                        }
                    }
                });

                if (serviceConnected.get() && backgroundService != null) {
                    backgroundService.finishOperation(operationName, true);
                }
                future.complete(result);

            } catch (Exception e) {
                LogUtils.e(TAG, "Background operation failed: " + operationName + " - " + e.getMessage());

                if (serviceConnected.get() && backgroundService != null) {
                    backgroundService.finishOperation(operationName, false);
                }
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Special method for multi-file operations with file counter
     */
    public <T> CompletableFuture<T> executeMultiFileOperation(String operationId, String operationName, int totalFiles, MultiFileOperation<T> operation) {

        LogUtils.d(TAG, "Starting multi-file operation: " + operationName + " (" + totalFiles + " files)");

        return executeBackgroundOperation(operationId, operationName, (ProgressCallback callback) -> {
            return operation.execute(new MultiFileProgressCallback() {
                @Override
                public void updateFileProgress(int currentFile, String currentFileName) {
                    callback.updateFileProgress(currentFile, totalFiles, currentFileName);
                }

                @Override
                public void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
                    callback.updateBytesProgress(currentBytes, totalBytes, fileName);
                }

                @Override
                public void updateProgress(String progressInfo) {
                    callback.updateProgress(progressInfo);
                }
            });
        });
    }

    /**
     * Interface for background operations
     */
    public interface BackgroundOperation<T> {
        T execute(ProgressCallback callback) throws Exception;
    }

    /**
     * Interface for multi-file operations
     */
    public interface MultiFileOperation<T> {
        T execute(MultiFileProgressCallback callback) throws Exception;
    }

    /**
     * Special callback interface for multi-file operations
     */
    public interface MultiFileProgressCallback {
        void updateFileProgress(int currentFile, String currentFileName);

        void updateBytesProgress(long currentBytes, long totalBytes, String fileName);

        void updateProgress(String progressInfo);
    }

    /**
     * Callback for progress updates
     */
    public interface ProgressCallback {
        void updateProgress(String progressInfo);

        /**
         * Update for multi-file operations with file counter
         */
        default void updateFileProgress(int currentFile, int totalFiles, String currentFileName) {
            updateProgress("Datei " + currentFile + " von " + totalFiles + ": " + currentFileName);
        }

        /**
         * Update for bytes progress within a file
         */
        default void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
            // Use floating-point division and rounding for more accurate percentage calculation
            // This ensures the progress bar reaches 100% for large files
            int percentage;
            if (totalBytes > 0) {
                if (currentBytes >= totalBytes) {
                    // Ensure we show 100% when the operation is complete
                    percentage = 100;
                } else if (totalBytes - currentBytes <= 1024) { // Within 1KB of completion
                    // When we're very close to completion, show 100%
                    percentage = 100;
                } else {
                    // Use floating-point division for accurate percentage
                    percentage = (int) Math.round((currentBytes * 100.0) / totalBytes);
                }
            } else {
                percentage = 0;
            }

            String progress = EnhancedFileUtils.formatFileSize(currentBytes) + " / " + EnhancedFileUtils.formatFileSize(totalBytes);
            updateProgress(fileName + ": " + percentage + "% (" + progress + ")");
        }
    }
}
