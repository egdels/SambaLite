package de.schliweb.sambalite.data.background;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;
import de.schliweb.sambalite.service.SmbBackgroundService;
import de.schliweb.sambalite.util.EnhancedFileUtils;
import de.schliweb.sambalite.util.LogUtils;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manager for handling background SMB operations via a bound service.
 * This class ensures the service is started and bound, and manages operation execution.
 */
@Singleton
public class BackgroundSmbManager {

    private static final long SERVICE_WAIT_MS = 2000;
    private static final String TAG = "BackgroundSmbManager";
    private final java.util.concurrent.ScheduledExecutorService delayExec =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    private final Context appContext;

    private final AtomicBoolean serviceConnected = new AtomicBoolean(false);
    private final AtomicBoolean bindingInProgress = new AtomicBoolean(false);
    private final Queue<Runnable> pendingOps = new ArrayDeque<>();
    private volatile SmbBackgroundService service;

    @Inject
    public BackgroundSmbManager(Context context) {
        this.appContext = context.getApplicationContext();
        ensureServiceStartedAndBound();
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            LogUtils.i(TAG, "Service connected");
            SmbBackgroundService.LocalBinder b = (SmbBackgroundService.LocalBinder) binder;
            service = b.getService();
            serviceConnected.set(true);
            bindingInProgress.set(false);
            drainPendingQueue();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            LogUtils.w(TAG, "Service disconnected");
            serviceConnected.set(false);
            service = null;
            ensureServiceStartedAndBound();
        }
    };

    private void ensureServiceStartedAndBound() {
        if (!bindingInProgress.compareAndSet(false, true)) return;

        try {
            Intent i = new Intent(appContext, SmbBackgroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(i);
            } else {
                appContext.startService(i);
            }
            boolean bound = appContext.bindService(i, conn, Context.BIND_AUTO_CREATE);
            LogUtils.d(TAG, "bindService -> " + bound);
        } catch (Throwable t) {
            LogUtils.e(TAG, "ensureServiceStartedAndBound failed: " + t.getMessage());
            bindingInProgress.set(false);
        }
    }

    public void shutdown() {
        try {
            if (serviceConnected.get()) appContext.unbindService(conn);
        } catch (Throwable ignore) {
        } finally {
            serviceConnected.set(false);
            service = null;
            pendingOps.clear();
            delayExec.shutdownNow();
        }
    }

    public <T> CompletableFuture<T> executeBackgroundOperation(
            String operationId,
            String operationName,
            BackgroundOperation<T> operation) {

        Objects.requireNonNull(operationName, "operationName");
        Objects.requireNonNull(operation, "operation");

        final CompletableFuture<T> result = new CompletableFuture<>();

        ensureServiceStartedAndBound();

        if (serviceConnected.get() && service != null) {
            delegateToService(operationName, operation, result);
            return result;
        }

        appContext.getMainExecutor().execute(() -> {
            appContext.getMainExecutor().execute(() -> {
            }); // noop to ensure executor initialized
        });
        delayExec.schedule(() -> {
            if (serviceConnected.get() && service != null) {
                delegateToService(operationName, operation, result);
            } else {
                LogUtils.d(TAG, "Queue operation (waiting for service): " + operationName);
                synchronized (pendingOps) {
                    pendingOps.add(() -> delegateToService(operationName, operation, result));
                }
            }
        }, SERVICE_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);

        return result;
    }

    public <T> CompletableFuture<T> executeMultiFileOperation(
            String operationId,
            String operationName,
            MultiFileOperation<T> operation) {

        return executeBackgroundOperation(operationId, operationName, (ProgressCallback cb) ->
                operation.execute(new MultiFileProgressCallback() {
                    @Override
                    public void updateFileProgress(int currentFile, int totalFiles, String currentFileName) {
                        cb.updateFileProgress(currentFile, totalFiles, currentFileName); // <-- echtes totalFiles durchreichen
                    }

                    @Override
                    public void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
                        cb.updateBytesProgress(currentBytes, totalBytes, fileName);
                    }

                    @Override
                    public void updateProgress(String progressInfo) {
                        cb.updateProgress(progressInfo);
                    }
                })
        );

    }

    public void requestCancelAllOperations() {
        try {
            Intent cancel = new Intent(appContext, SmbBackgroundService.class)
                    .setAction(SmbBackgroundService.ACTION_CANCEL);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(cancel);
            } else {
                appContext.startService(cancel);
            }
            LogUtils.i(TAG, "Cancel request sent to service");
        } catch (Throwable t) {
            LogUtils.e(TAG, "Failed to send cancel request: " + t.getMessage());
        }
    }

    private <T> void delegateToService(
            String operationName,
            BackgroundOperation<T> operation,
            CompletableFuture<T> future) {

        SmbBackgroundService svc = service;
        if (svc == null) {
            LogUtils.w(TAG, "Service null on delegate, re-queue: " + operationName);
            synchronized (pendingOps) {
                pendingOps.add(() -> delegateToService(operationName, operation, future));
            }
            ensureServiceStartedAndBound();
            return;
        }

        try {
            svc.executeSmbOperation(operationName, () -> {
                try {
                    T r = operation.execute(new ProgressCallback() {
                        @Override
                        public void updateProgress(String info) {
                            svc.updateOperationProgress(operationName, info);
                        }

                        @Override
                        public void updateFileProgress(int currentFile, int totalFiles, String currentFileName) {
                            svc.updateFileProgress(operationName, currentFile, totalFiles, currentFileName);
                        }

                        @Override
                        public void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
                            svc.updateBytesProgress(operationName, currentBytes, totalBytes, fileName);
                        }
                    });
                    future.complete(r);
                    return true;
                } catch (Exception ex) {
                    future.completeExceptionally(ex);
                    throw ex;
                }
            });
        } catch (Exception startEx) {
            LogUtils.e(TAG, "delegateToService failed, re-queue: " + startEx.getMessage());
            synchronized (pendingOps) {
                pendingOps.add(() -> delegateToService(operationName, operation, future));
            }
            ensureServiceStartedAndBound();
        }
    }

    private void drainPendingQueue() {
        final Queue<Runnable> copy;
        synchronized (pendingOps) {
            if (pendingOps.isEmpty()) return;
            copy = new ArrayDeque<>(pendingOps);
            pendingOps.clear();
        }
        LogUtils.d(TAG, "Draining pending ops: " + copy.size());
        while (!copy.isEmpty() && serviceConnected.get() && service != null) {
            try {
                copy.poll().run();
            } catch (Throwable t) {
                LogUtils.e(TAG, "Pending op failed: " + t.getMessage());
            }
        }
        if (!copy.isEmpty()) {
            synchronized (pendingOps) {
                pendingOps.addAll(copy);
            }
            ensureServiceStartedAndBound();
        }
    }

    public void setSearchContext(String connectionId, String searchQuery, int searchType, boolean includeSubfolders) {
        if (serviceConnected.get() && service != null) {
            service.setSearchParameters(connectionId, searchQuery, searchType, includeSubfolders);
        }
    }

    public void setUploadContext(String connectionId, String uploadPath) {
        if (serviceConnected.get() && service != null) {
            service.setUploadParameters(connectionId, uploadPath);
        }
    }

    public void setDownloadContext(String connectionId, String downloadPath) {
        if (serviceConnected.get() && service != null) {
            service.setDownloadParameters(connectionId, downloadPath);
        }
    }

    // ===== Interfaces =====


    public boolean isServiceConnected() {
        return serviceConnected.get();
    }

    public void startOperation(String name) {
        if (serviceConnected.get() && service != null) {
            service.startOperation(name);
        } else {
            LogUtils.v(TAG, "startOperation skipped (service not connected): " + name);
        }
    }

    public void updateOperationProgress(String name, String info) {
        if (serviceConnected.get() && service != null) {
            service.updateOperationProgress(name, info);
        }
    }

    public void updateFileProgress(String name, int current, int total, String file) {
        if (serviceConnected.get() && service != null) {
            service.updateFileProgress(name, current, total, file);
        }
    }

    public void updateBytesProgress(String name, long cur, long total, String file) {
        if (serviceConnected.get() && service != null) {
            service.updateBytesProgress(name, cur, total, file);
        }
    }

    public void finishOperation(String name, boolean success) {
        if (serviceConnected.get() && service != null) {
            service.finishOperation(name, success);
        } else {
            LogUtils.v(TAG, "finishOperation skipped (service not connected): " + name);
        }
    }

    public interface BackgroundOperation<T> {
        T execute(ProgressCallback callback) throws Exception;
    }

    public interface MultiFileOperation<T> {
        T execute(MultiFileProgressCallback callback) throws Exception;
    }

    public interface MultiFileProgressCallback {
        void updateFileProgress(int currentFile, int totalFiles, String currentFileName);

        void updateBytesProgress(long currentBytes, long totalBytes, String fileName);

        void updateProgress(String progressInfo);
    }

    public interface ProgressCallback {
        void updateProgress(String progressInfo);

        default void updateFileProgress(int currentFile, int totalFiles, String currentFileName) {
            updateProgress("Datei " + currentFile + " von " + totalFiles + ": " + currentFileName);
        }

        default void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
            int pct;
            if (totalBytes > 0) {
                pct = (currentBytes >= totalBytes || (totalBytes - currentBytes) <= 1024)
                        ? 100 : (int) Math.round((currentBytes * 100.0) / totalBytes);
            } else {
                pct = 0;
            }
            String progress = EnhancedFileUtils.formatFileSize(currentBytes)
                    + " / " + EnhancedFileUtils.formatFileSize(totalBytes);
            updateProgress(fileName + ": " + pct + "% (" + progress + ")");
        }
    }


}
