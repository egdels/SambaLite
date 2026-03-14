package de.schliweb.sambalite.data.background;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.schliweb.sambalite.service.SmbBackgroundService;
import de.schliweb.sambalite.util.EnhancedFileUtils;
import de.schliweb.sambalite.util.LogUtils;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Manager for handling background SMB operations via a bound service. This class ensures the
 * service is started and bound, and manages operation execution.
 */
@Singleton
public class BackgroundSmbManager {

  private static final long SERVICE_WAIT_MS = 2000;
  private static final String TAG = "BackgroundSmbManager";
  private final java.util.concurrent.ScheduledExecutorService delayExec =
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
  final Context appContext;

  final AtomicBoolean serviceConnected = new AtomicBoolean(false);
  final AtomicBoolean bindingInProgress = new AtomicBoolean(false);
  final AtomicBoolean stopRequested = new AtomicBoolean(false);
  private final AtomicInteger visibleActivityCount = new AtomicInteger(0);
  final Queue<Runnable> pendingOps = new ArrayDeque<>();
  volatile SmbBackgroundService service;

  @Inject
  public BackgroundSmbManager(@NonNull Context context) {
    this.appContext = context.getApplicationContext();
    ensureServiceStartedAndBound();
  }

  final ServiceConnection conn =
      new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
          LogUtils.i(TAG, "Service connected");
          SmbBackgroundService.LocalBinder b = (SmbBackgroundService.LocalBinder) binder;
          SmbBackgroundService svc = b.getService();
          if (svc == null) {
            LogUtils.w(TAG, "Service connected but reference already cleared");
            bindingInProgress.set(false);
            return;
          }
          service = svc;
          // If the service was stopped via notification button, mirror the flag
          if (svc.isStopRequested()) {
            stopRequested.set(true);
          }
          serviceConnected.set(true);
          bindingInProgress.set(false);
          drainPendingQueue();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
          LogUtils.w(TAG, "Service disconnected");
          serviceConnected.set(false);
          service = null;
          if (!stopRequested.get()) {
            ensureServiceStartedAndBound();
          } else {
            LogUtils.i(TAG, "Service disconnected after stop request — not restarting");
            bindingInProgress.set(false);
          }
        }
      };

  void ensureServiceStartedAndBound() {
    if (stopRequested.get()) {
      LogUtils.d(TAG, "Resetting stopRequested flag for new service start");
      stopRequested.set(false);
    }
    if (!bindingInProgress.compareAndSet(false, true)) return;

    Intent i = new Intent(appContext, SmbBackgroundService.class);
    try {
      appContext.startForegroundService(i);
    } catch (Throwable t) {
      LogUtils.e(TAG, "startForegroundService failed, falling back to bind: " + t.getMessage());
    }
    try {
      boolean bound = appContext.bindService(i, conn, Context.BIND_AUTO_CREATE);
      LogUtils.d(TAG, "bindService -> " + bound);
    } catch (Throwable t) {
      LogUtils.e(TAG, "bindService failed: " + t.getMessage());
      bindingInProgress.set(false);
    }
  }

  /**
   * Ensures the background service is running and bound. Call this when the app is (re)opened to
   * restore the foreground notification. If the service is already bound but was stopped (e.g. via
   * notification stop button), only a startForegroundService intent is sent to restore the
   * notification without re-binding.
   */
  public void ensureServiceRunning() {
    if (serviceConnected.get() && service != null) {
      // Service is still bound but may have been stopped via notification.
      // Send a start intent to restore the foreground notification without re-binding.
      if (stopRequested.get()) {
        LogUtils.d(TAG, "Resetting stopRequested flag for service restart (already bound)");
        stopRequested.set(false);
      }
      try {
        Intent i = new Intent(appContext, SmbBackgroundService.class);
        appContext.startForegroundService(i);
        LogUtils.d(TAG, "Sent start intent to already-bound service to restore notification");
      } catch (Throwable t) {
        LogUtils.e(TAG, "Failed to restart already-bound service: " + t.getMessage());
      }
      return;
    }
    ensureServiceStartedAndBound();
  }

  public void shutdown() {
    try {
      if (serviceConnected.get()) appContext.unbindService(conn);
    } catch (Throwable ignored) {
    } finally {
      serviceConnected.set(false);
      service = null;
      pendingOps.clear();
      delayExec.shutdownNow();
    }
  }

  public <T> @NonNull CompletableFuture<T> executeBackgroundOperation(
      @NonNull String operationId,
      @NonNull String operationName,
      @NonNull BackgroundOperation<T> operation) {

    Objects.requireNonNull(operationName, "operationName");
    Objects.requireNonNull(operation, "operation");

    final CompletableFuture<T> result = new CompletableFuture<>();

    ensureServiceStartedAndBound();

    if (serviceConnected.get() && service != null) {
      delegateToService(operationName, operation, result);
      return result;
    }

    appContext
        .getMainExecutor()
        .execute(
            () -> {
              appContext.getMainExecutor().execute(() -> {}); // noop to ensure executor initialized
            });
    delayExec.schedule(
        () -> {
          if (serviceConnected.get() && service != null) {
            delegateToService(operationName, operation, result);
          } else {
            LogUtils.d(TAG, "Queue operation (waiting for service): " + operationName);
            synchronized (pendingOps) {
              pendingOps.add(() -> delegateToService(operationName, operation, result));
            }
          }
        },
        SERVICE_WAIT_MS,
        java.util.concurrent.TimeUnit.MILLISECONDS);

    return result;
  }

  public <T> @NonNull CompletableFuture<T> executeMultiFileOperation(
      @NonNull String operationId,
      @NonNull String operationName,
      @NonNull MultiFileOperation<T> operation) {

    return executeBackgroundOperation(
        operationId,
        operationName,
        (ProgressCallback cb) ->
            operation.execute(
                new MultiFileProgressCallback() {
                  @Override
                  public void updateFileProgress(
                      int currentFile, int totalFiles, String currentFileName) {
                    cb.updateFileProgress(
                        currentFile,
                        totalFiles,
                        currentFileName); // <-- echtes totalFiles durchreichen
                  }

                  @Override
                  public void updateBytesProgress(
                      long currentBytes, long totalBytes, String fileName) {
                    cb.updateBytesProgress(currentBytes, totalBytes, fileName);
                  }

                  @Override
                  public void updateProgress(String progressInfo) {
                    cb.updateProgress(progressInfo);
                  }
                }));
  }

  public void requestCancelAllOperations() {
    Intent cancel =
        new Intent(appContext, SmbBackgroundService.class)
            .setAction(SmbBackgroundService.ACTION_CANCEL);
    try {
      appContext.startForegroundService(cancel);
      LogUtils.i(TAG, "Cancel request sent to service");
    } catch (Throwable t) {
      LogUtils.e(
          TAG, "startForegroundService for cancel failed, falling back to bind: " + t.getMessage());
      try {
        appContext.bindService(cancel, conn, Context.BIND_AUTO_CREATE);
      } catch (Throwable t2) {
        LogUtils.e(TAG, "bindService for cancel also failed: " + t2.getMessage());
      }
    }
  }

  <T> void delegateToService(
      String operationName, BackgroundOperation<T> operation, CompletableFuture<T> future) {

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
      svc.executeSmbOperation(
          operationName,
          () -> {
            try {
              T r =
                  operation.execute(
                      new ProgressCallback() {
                        @Override
                        public void updateProgress(String info) {
                          svc.updateOperationProgress(operationName, info);
                        }

                        @Override
                        public void updateFileProgress(
                            int currentFile, int totalFiles, String currentFileName) {
                          svc.updateFileProgress(
                              operationName, currentFile, totalFiles, currentFileName);
                        }

                        @Override
                        public void updateBytesProgress(
                            long currentBytes, long totalBytes, String fileName) {
                          svc.updateBytesProgress(
                              operationName, currentBytes, totalBytes, fileName);
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

  void drainPendingQueue() {
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

  public void setSearchContext(
      @NonNull String connectionId,
      @NonNull String searchQuery,
      int searchType,
      boolean includeSubfolders) {
    if (serviceConnected.get() && service != null) {
      service.setSearchParameters(connectionId, searchQuery, searchType, includeSubfolders);
    }
  }

  public void setUploadContext(@NonNull String connectionId, @NonNull String uploadPath) {
    if (serviceConnected.get() && service != null) {
      service.setUploadParameters(connectionId, uploadPath);
    }
  }

  public void setDownloadContext(@NonNull String connectionId, @NonNull String downloadPath) {
    if (serviceConnected.get() && service != null) {
      service.setDownloadParameters(connectionId, downloadPath);
    }
  }

  // ===== Interfaces =====

  /**
   * Notifies the manager that an activity has become visible. Called from {@code onStart()} of
   * MainActivity and FileBrowserActivity.
   */
  public void onActivityStarted() {
    visibleActivityCount.incrementAndGet();
  }

  /**
   * Notifies the manager that an activity is no longer visible. Called from {@code onStop()} of
   * MainActivity and FileBrowserActivity. When no activities are visible and no operations are
   * active, the service is auto-stopped.
   */
  public void onActivityStopped() {
    int count = visibleActivityCount.decrementAndGet();
    if (count <= 0) {
      visibleActivityCount.set(0);
      // If no operations are active, stop the service immediately
      if (!hasActiveOperations()) {
        LogUtils.i(TAG, "All activities stopped and no active operations — auto-stopping service");
        requestStopService();
      }
    }
  }

  public boolean isServiceConnected() {
    return serviceConnected.get();
  }

  public boolean hasActiveOperations() {
    SmbBackgroundService svc = service;
    return svc != null && svc.hasActiveOperations();
  }

  public int getActiveOperationCount() {
    SmbBackgroundService svc = service;
    return svc != null ? svc.getActiveOperationCount() : 0;
  }

  public void requestStopService() {
    stopRequested.set(true);
    // Unbind first so stopSelf() in the service can actually stop it
    try {
      if (serviceConnected.getAndSet(false)) {
        appContext.unbindService(conn);
      }
    } catch (Throwable t) {
      LogUtils.w(TAG, "unbindService failed (ignored): " + t.getMessage());
    }
    service = null;
    // Reset bindingInProgress so the next ensureServiceStartedAndBound() can proceed.
    // unbindService() does NOT trigger onServiceDisconnected, so we must reset here.
    bindingInProgress.set(false);
    try {
      Intent stop =
          new Intent(appContext, SmbBackgroundService.class)
              .setAction(SmbBackgroundService.ACTION_STOP);
      appContext.startForegroundService(stop);
      LogUtils.i(TAG, "Stop request sent to service");
    } catch (Throwable t) {
      LogUtils.e(TAG, "Failed to send stop request: " + t.getMessage());
    }
  }

  public void startOperation(@NonNull String name) {
    if (serviceConnected.get() && service != null) {
      service.startOperation(name);
    } else {
      LogUtils.v(TAG, "startOperation skipped (service not connected): " + name);
    }
  }

  public void updateOperationProgress(@NonNull String name, @NonNull String info) {
    if (serviceConnected.get() && service != null) {
      service.updateOperationProgress(name, info);
    }
  }

  public void updateFileProgress(
      @NonNull String name, int current, int total, @NonNull String file) {
    if (serviceConnected.get() && service != null) {
      service.updateFileProgress(name, current, total, file);
    }
  }

  public void updateBytesProgress(
      @NonNull String name, long cur, long total, @NonNull String file) {
    if (serviceConnected.get() && service != null) {
      service.updateBytesProgress(name, cur, total, file);
    }
  }

  public void finishOperation(@NonNull String name, boolean success) {
    if (serviceConnected.get() && service != null) {
      service.finishOperation(name, success);
      // Auto-stop service when all transfers are done and no activity is visible
      if (!service.hasActiveOperations() && visibleActivityCount.get() <= 0) {
        LogUtils.i(
            TAG, "All operations finished and MainActivity not visible — auto-stopping service");
        requestStopService();
      }
    } else {
      LogUtils.v(TAG, "finishOperation skipped (service not connected): " + name);
    }
  }

  public interface BackgroundOperation<T> {
    @NonNull
    T execute(@Nullable ProgressCallback callback) throws Exception;
  }

  public interface MultiFileOperation<T> {
    @NonNull
    T execute(@Nullable MultiFileProgressCallback callback) throws Exception;
  }

  public interface MultiFileProgressCallback {
    void updateFileProgress(int currentFile, int totalFiles, @NonNull String currentFileName);

    void updateBytesProgress(long currentBytes, long totalBytes, @NonNull String fileName);

    void updateProgress(@NonNull String progressInfo);
  }

  public interface ProgressCallback {
    void updateProgress(@NonNull String progressInfo);

    default void updateFileProgress(
        int currentFile, int totalFiles, @NonNull String currentFileName) {
      updateProgress("Datei " + currentFile + " von " + totalFiles + ": " + currentFileName);
    }

    default void updateBytesProgress(long currentBytes, long totalBytes, @NonNull String fileName) {
      int pct;
      if (totalBytes > 0) {
        pct =
            (currentBytes >= totalBytes || (totalBytes - currentBytes) <= 1024)
                ? 100
                : (int) Math.round((currentBytes * 100.0) / totalBytes);
      } else {
        pct = 0;
      }
      String progress =
          EnhancedFileUtils.formatFileSize(currentBytes)
              + " / "
              + EnhancedFileUtils.formatFileSize(totalBytes);
      updateProgress(fileName + ": " + pct + "% (" + progress + ")");
    }
  }
}
