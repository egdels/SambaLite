package de.schliweb.sambalite.service;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.ui.FileBrowserActivity;
import de.schliweb.sambalite.ui.utils.ProgressFormat;
import de.schliweb.sambalite.util.LogUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Foreground service for SMB background operations (download, upload, search).
 * <p>
 * Key improvements vs. previous implementation:
 * - Reference-counted WakeLock to avoid premature release during concurrent ops
 * - Throttled notification updates to bypass rate limiting
 * - Watchdog with inactivity + absolute timeout and cooperative cancellation flag
 * - Helper APIs for temp files + atomic finalize (temp ➜ destination)
 * - Operation-specific notification intents (tap to open relevant screen/path)
 */
public class SmbBackgroundService extends Service {

    // Actions
    public static final String ACTION_CANCEL = "de.schliweb.sambalite.action.CANCEL";

    // Track running tasks for hard cancel
    private final java.util.concurrent.ConcurrentLinkedQueue<Future<?>> runningFutures = new java.util.concurrent.ConcurrentLinkedQueue<>();


    private static final String TAG = "SmbBackgroundService";

    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "SMB_BACKGROUND_OPERATIONS";

    // UI throttling
    private static final long NOTIFICATION_UPDATE_INTERVAL_MS = 1000; // 1 Hz

    // Timeouts
    private static final int SMB_OPERATION_TIMEOUT_SECONDS = 60;      // hard cap
    private static final int PROGRESS_WATCHDOG_INTERVAL_SECONDS = 30; // idle detector

    // Binder
    private final IBinder binder = new LocalBinder();

    // Concurrency / lifecycle
    private final AtomicInteger activeOperations = new AtomicInteger(0);
    private ExecutorService smbOperationExecutor;               // worker pool
    private ScheduledExecutorService watchdogExecutor;          // watchdog timers

    // Progress & cancellation
    private final AtomicLong lastProgressUpdate = new AtomicLong(0);

    // Android infra
    private PowerManager.WakeLock wakeLock;
    private NotificationManager notificationManager;
    private final android.os.Handler notificationHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    // State for notification texts/intents
    private volatile String currentOperation = "";
    private volatile boolean isRunning = false;
    private volatile long lastNotificationUpdate = 0;
    private volatile Runnable pendingNotificationUpdate = null;

    // Operation context (for deep links in notifications)
    private String connectionId;
    private String searchQuery;
    private int searchType;
    private boolean includeSubfolders;
    private boolean isSearchOperation = false;

    private String uploadPath;
    private String downloadPath;
    private boolean isUploadOperation = false;
    private boolean isDownloadOperation = false;

    // Temp storage for safe transfers
    private File tempDir;

    private static String initialContentForOp(ProgressFormat.Op op) {
        switch (op) {
            case DOWNLOAD:
                return "Preparing download…";
            case UPLOAD:
                return "Preparing upload…";
            case DELETE:
                return "Preparing delete…";
            case RENAME:
                return "Preparing rename…";
            case SEARCH:
                return "Preparing search…";
            default:
                return "Starting…";
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        LogUtils.d(TAG, "Background service created");

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();

        // Worker pools
        smbOperationExecutor = Executors.newFixedThreadPool(2);
        watchdogExecutor = Executors.newSingleThreadScheduledExecutor();

        // Temp directory
        tempDir = new File(getFilesDir(), "temp_transfers");
        if (!tempDir.exists()) {
            boolean created = tempDir.mkdirs();
            LogUtils.d(TAG, "Temp directory created: " + created + ", path=" + tempDir.getAbsolutePath());
        }

        // WakeLock (reference-counted to support parallel ops)
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SambaLite:BackgroundOperations");
        wakeLock.setReferenceCounted(true);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (hasActiveOperations()) {
            Intent i = new Intent(getApplicationContext(), SmbBackgroundService.class);
            startForegroundService(i);
        }
        super.onTaskRemoved(rootIntent);
    }


    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        LogUtils.d(TAG, "Background service destroyed");

        if (smbOperationExecutor != null) smbOperationExecutor.shutdownNow();
        if (watchdogExecutor != null) watchdogExecutor.shutdownNow();

        if (notificationHandler != null) {
            notificationHandler.removeCallbacksAndMessages(null);
        }

        try {
            stopForeground(true);
        } catch (Throwable t) {
            LogUtils.w(TAG, "stopForeground failed (ignored): " + t.getMessage());
        }

        // Temp-Cleanup
        try {
            File[] files = tempDir != null ? tempDir.listFiles() : null;
            if (files != null) for (File f : files) {
                try {
                    f.delete();
                } catch (Throwable ignore) {
                }
            }
        } catch (Exception e) {
            LogUtils.e(TAG, "Error cleaning temp directory", e);
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Throwable ignore) {
            }
            LogUtils.d(TAG, "Wake lock released (service destroy)");
        }
        isRunning = false;
        super.onDestroy();
    }

    @RequiresApi(35)
    @Override
    public void onTimeout(int startId, int fgType) {
        LogUtils.w(TAG, "onTimeout(type=" + fgType + ", startId=" + startId + ")");
        cancelAllOperations("Foreground service time limit reached");
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Throwable ignore) {
            }
        }
        stopSelf();
    }

    public void startOperation(String operationName) {
        int count = activeOperations.incrementAndGet();

        ProgressFormat.Op op = ProgressFormat.Op.fromString(operationName);
        String title = op.label() + "…";
        String content = initialContentForOp(op);

        // Update operation context flags based on the starting operation to ensure
        // the notification PendingIntent points to the correct screen on tap.
        boolean prevSearch = isSearchOperation;
        boolean prevUpload = isUploadOperation;
        boolean prevDownload = isDownloadOperation;
        switch (op) {
            case SEARCH:
                isSearchOperation = true;
                isUploadOperation = false;
                isDownloadOperation = false;
                break;
            case UPLOAD:
                isSearchOperation = false;
                isUploadOperation = true;
                isDownloadOperation = false;
                break;
            case DOWNLOAD:
                isSearchOperation = false;
                isUploadOperation = false;
                isDownloadOperation = true;
                break;
            default:
                // For other operations, disable specific deep links
                isSearchOperation = false;
                isUploadOperation = false;
                isDownloadOperation = false;
                break;
        }
        boolean contextChanged = (prevSearch != isSearchOperation) || (prevUpload != isUploadOperation) || (prevDownload != isDownloadOperation);

        currentOperation = title;
        LogUtils.d(TAG, "Starting operation: " + operationName + " (active=" + count + ")");

        // If the context changed (e.g., Search -> Download), refresh immediately so the tap target updates now.
        if (contextChanged) {
            updateNotificationImmediate(title, content);
        } else {
            updateNotificationThrottled(title, content);
        }

        if (wakeLock != null) {
            wakeLock.acquire();
            LogUtils.d(TAG, "Wake lock ++ (op start): " + operationName);
        }

        lastProgressUpdate.set(System.currentTimeMillis());
        startWatchdog();
    }

    public void finishOperation(String operationName, boolean success) {
        int count = activeOperations.decrementAndGet();
        LogUtils.d(TAG, "Finished operation: " + operationName + " (success=" + success + ", remaining=" + count + ")");

        if (count <= 0) {
            currentOperation = "";
            // Reset context flags to avoid stale tap targets when no ops are running
            isSearchOperation = false;
            isUploadOperation = false;
            isDownloadOperation = false;
            updateNotificationThrottled("SMB Service ready", "Ready for background operations");
        } else {
            updateNotificationThrottled(currentOperation, count + " operations active");
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            LogUtils.d(TAG, "Wake lock -- (op finish): " + operationName);
        }
        if (!hasActiveOperations()) {
            stopWatchdog();
        }
    }


    public void updateOperationProgress(String operationName, String progressInfo) {
        ProgressFormat.Op op = ProgressFormat.Op.fromString(operationName);
        String title = op.label() + "…";
        currentOperation = title; // <—
        updateNotificationThrottled(title, progressInfo != null ? progressInfo : "");
        lastProgressUpdate.set(System.currentTimeMillis());
    }

    public void updateFileProgress(String operationName, int currentFile, int totalFiles, String currentFileName) {
        int percentage = totalFiles > 0 ? ((currentFile * 100) / totalFiles) : 0;

        ProgressFormat.Op op = ProgressFormat.Op.fromString(operationName);
        String title = op.label() + "…";
        String info = ProgressFormat.buildUnified(op, currentFile, totalFiles,
                currentFileName != null ? currentFileName : "");

        if (shouldUpdateFileProgress(currentFile, totalFiles, percentage)) {
            currentOperation = title; // <—
            updateNotificationThrottled(title, info);
        }
        lastProgressUpdate.set(System.currentTimeMillis());
    }


    public void updateBytesProgress(String operationName, long currentBytes, long totalBytes, String fileName) {
        int percentage = ProgressFormat.percentOfBytes(currentBytes, totalBytes);

        ProgressFormat.Op op = ProgressFormat.Op.fromString(operationName);
        String title = op.label() + "…";
        String info = ProgressFormat.formatBytes(op.label(), currentBytes, totalBytes)
                + (fileName != null && !fileName.isEmpty() ? " - " + fileName : "");

        if (shouldUpdateBytesProgress(percentage)) {
            currentOperation = title; // <—
            updateNotificationThrottled(title, info);
        }
        lastProgressUpdate.set(System.currentTimeMillis());
    }

    public boolean hasActiveOperations() {
        return activeOperations.get() > 0;
    }

    public int getActiveOperationCount() {
        return activeOperations.get();
    }

    // Parameters for deep links from notifications
    public void setSearchParameters(String connectionId, String searchQuery, int searchType, boolean includeSubfolders) {
        this.connectionId = connectionId;
        this.searchQuery = searchQuery;
        this.searchType = searchType;
        this.includeSubfolders = includeSubfolders;
        this.isSearchOperation = true;
        this.isUploadOperation = false;
        this.isDownloadOperation = false;
        LogUtils.d(TAG, "Search parameters set: connectionId=" + connectionId + ", query=" + searchQuery);

        // Immediately refresh notification intent to point to Search context
        ProgressFormat.Op op = ProgressFormat.Op.SEARCH;
        String title = op.label() + "…";
        currentOperation = title;
        updateNotificationImmediate(title, initialContentForOp(op));
    }

    public void setUploadParameters(String connectionId, String uploadPath) {
        this.connectionId = connectionId;
        this.uploadPath = uploadPath;
        this.isUploadOperation = true;
        this.isSearchOperation = false;
        this.isDownloadOperation = false;
        LogUtils.d(TAG, "Upload parameters set: connectionId=" + connectionId + ", path=" + uploadPath);

        // Immediately refresh notification intent to point to Upload context
        ProgressFormat.Op op = ProgressFormat.Op.UPLOAD;
        String title = op.label() + "…";
        currentOperation = title;
        updateNotificationImmediate(title, initialContentForOp(op));
    }

    public void setDownloadParameters(String connectionId, String downloadPath) {
        this.connectionId = connectionId;
        this.downloadPath = downloadPath;
        this.isDownloadOperation = true;
        this.isSearchOperation = false;
        this.isUploadOperation = false;
        LogUtils.d(TAG, "Download parameters set: connectionId=" + connectionId + ", path=" + downloadPath);

        // Immediately refresh notification intent to point to Download context
        ProgressFormat.Op op = ProgressFormat.Op.DOWNLOAD;
        String title = op.label() + "…";
        currentOperation = title;
        updateNotificationImmediate(title, initialContentForOp(op));
    }

    public void clearSearchParameters() {
        this.isSearchOperation = false;
    }

    public void clearUploadParameters() {
        this.isUploadOperation = false;
    }

    public void clearDownloadParameters() {
        this.isDownloadOperation = false;
    }

    // ===== Optional: Execute operations with built-in timeouts/cancellation =====

    /**
     * Execute a Callable SMB operation with built-in inactivity + absolute timeouts.
     * The Callable should periodically check {@link #isOperationCancelled()} to abort early.
     */
    public void executeSmbOperation(String operationName, Callable<Boolean> work) {
        if (smbOperationExecutor == null || smbOperationExecutor.isShutdown()) {
            smbOperationExecutor = Executors.newFixedThreadPool(2);
        }
        if (watchdogExecutor == null || watchdogExecutor.isShutdown()) {
            watchdogExecutor = Executors.newSingleThreadScheduledExecutor();
        }

        lastProgressUpdate.set(System.currentTimeMillis());
        startOperation(operationName);

        final OpCtx ctx = new OpCtx(operationName);

        Runnable inactivityCheck = () -> {
            long idle = System.currentTimeMillis() - lastProgressUpdate.get();
            if (hasActiveOperations() && idle > PROGRESS_WATCHDOG_INTERVAL_SECONDS * 1000L) {
                LogUtils.w(TAG, "Inactivity timeout: cancelling " + operationName + " after " + (idle / 1000) + "s idle");
                ctx.cancelled.set(true);
                Future<?> f = ctx.future;
                if (f != null) f.cancel(true);
                updateNotificationImmediate("Operation timed out", "No progress for " + (idle / 1000) + "s, cancelling...");
            }
        };

        Runnable absoluteTimeout = () -> {
            LogUtils.w(TAG, "Absolute timeout reached: cancelling " + operationName);
            ctx.cancelled.set(true);
            Future<?> f = ctx.future;
            if (f != null) f.cancel(true);
            updateNotificationImmediate("Operation timed out", "Exceeded " + SMB_OPERATION_TIMEOUT_SECONDS + "s limit");
        };

        ctx.inactivityTask = watchdogExecutor.scheduleAtFixedRate(
                inactivityCheck,
                PROGRESS_WATCHDOG_INTERVAL_SECONDS,
                PROGRESS_WATCHDOG_INTERVAL_SECONDS,
                TimeUnit.SECONDS);

        ctx.absoluteTimeoutTask = watchdogExecutor.schedule(
                absoluteTimeout,
                SMB_OPERATION_TIMEOUT_SECONDS,
                TimeUnit.SECONDS);

        ctx.future = smbOperationExecutor.submit(() -> {
            try {
                boolean ok = work.call();
                return ok;
            } catch (InterruptedException ie) {
                LogUtils.w(TAG, "Operation interrupted: " + operationName);
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                LogUtils.e(TAG, "Error in SMB operation: " + operationName, e);
                updateNotificationImmediate("Error: " + operationName, e.getMessage());
                return false;
            } finally {
                if (ctx.inactivityTask != null) ctx.inactivityTask.cancel(false);
                if (ctx.absoluteTimeoutTask != null) ctx.absoluteTimeoutTask.cancel(false);
                runningFutures.remove(ctx.future);
                finishOperation(operationName, !ctx.cancelled.get() && !Thread.currentThread().isInterrupted());
            }
        });
        runningFutures.add(ctx.future);
    }

    public boolean isOperationCancelled() {
        return Thread.currentThread().isInterrupted();
    }
    // ===== Transfer helpers (temp file handling) =====

    public File getTempDir() {
        return tempDir;
    }

    public File createTempFile(String originalFileName) throws IOException {
        String extension = "";
        int dot = originalFileName == null ? -1 : originalFileName.lastIndexOf('.');
        if (dot > 0) extension = originalFileName.substring(dot);

        String base = (originalFileName == null ? "transfer" : originalFileName).replaceAll("[^a-zA-Z0-9]", "_");
        if (base.length() > 24) base = base.substring(0, 24);
        String unique = base + "_" + System.currentTimeMillis() + extension;

        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw new IOException("Failed to create temp directory: " + tempDir.getAbsolutePath());
        }
        File f = new File(tempDir, unique);
        if (!f.createNewFile()) {
            throw new IOException("Failed to create temp file: " + f.getAbsolutePath());
        }
        LogUtils.d(TAG, "Created temp file: " + f.getAbsolutePath());
        return f;
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (!isRunning) {
            try {
                startForeground(NOTIFICATION_ID, createNotification("SMB Service ready", "Ready for background operations"));
                isRunning = true;
            } catch (Exception e) {
                boolean isStartNotAllowed =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                                && "android.app.ForegroundServiceStartNotAllowedException".equals(e.getClass().getName());

                if (isStartNotAllowed) {
                    try {
                        notificationManager.notify(
                                NOTIFICATION_ID,
                                createNotification(getString(R.string.app_name),
                                        "Foreground service limit reached. Please try again later."));
                    } catch (Throwable ignore) {
                    }
                    stopSelf();
                    return START_NOT_STICKY;
                } else {
                    stopSelf();
                    return START_NOT_STICKY;
                }
            }
        }

        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_CANCEL.equals(action)) {
                cancelAllOperations("Canceled by user");
                return START_NOT_STICKY;
            }
        }

        if (hasActiveOperations()) startWatchdog();
        else stopWatchdog();

        return START_STICKY;
    }

    private void cancelAllOperations(String reason) {
        for (Future<?> f : runningFutures) {
            try {
                f.cancel(true);
            } catch (Throwable ignored) {
            }
        }
        updateNotificationImmediate("Operation cancelled",
                reason != null ? reason : "Cancelled");
        LogUtils.w(TAG, "All running operations cancelled");
        stopWatchdog(); // optional: cancel watchdog if no ops left
    }

    private void stopWatchdog() {
        if (watchdogExecutor != null && !watchdogExecutor.isShutdown()) {
            watchdogExecutor.shutdownNow();
            LogUtils.d(TAG, "Watchdog stopped");
        }
        watchdogExecutor = null;
    }

    private boolean copyFile(File source, File dest) {
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int len;
            long copied = 0;
            long size = source.length();
            while ((len = fis.read(buf)) > 0) {
                fos.write(buf, 0, len);
                copied += len;
                if ((copied % (1024 * 1024)) == 0) {
                    int pct = size > 0 ? (int) ((copied * 100) / size) : 0;
                    LogUtils.v(TAG, "Copy progress: " + pct + "% (" + copied + "/" + size + ")");
                    lastProgressUpdate.set(System.currentTimeMillis());
                }
                if (isOperationCancelled()) {
                    LogUtils.w(TAG, "Copy cancelled");
                    return false;
                }
            }
            fos.getFD().sync();
            return true;
        } catch (Exception e) {
            LogUtils.e(TAG, "copyFile error", e);
            return false;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "SMB Background Operations", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows the status of SMB operations in the background");
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void updateNotificationThrottled(String title, String content) {
        long now = System.currentTimeMillis();
        LogUtils.d(TAG, "updateNotificationThrottled: now=" + now + ", lastUpdate=" + lastNotificationUpdate + ", interval=" + NOTIFICATION_UPDATE_INTERVAL_MS);
        if (now - lastNotificationUpdate >= NOTIFICATION_UPDATE_INTERVAL_MS) {
            updateNotificationImmediate(title, content);
            lastNotificationUpdate = now;
            return;
        }
        if (pendingNotificationUpdate != null) {
            notificationHandler.removeCallbacks(pendingNotificationUpdate);
        }
        pendingNotificationUpdate = () -> {
            updateNotificationImmediate(title, content);
            lastNotificationUpdate = System.currentTimeMillis();
            pendingNotificationUpdate = null;
        };
        long delay = NOTIFICATION_UPDATE_INTERVAL_MS - (now - lastNotificationUpdate);
        LogUtils.d(TAG, "Throttling notification update: delay=" + delay + "ms");
        notificationHandler.postDelayed(pendingNotificationUpdate, Math.max(delay, 100));
    }

    private void updateNotificationImmediate(String title, String content) {
        if (!isRunning) return;
        Notification notification = createNotification(title, content);
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    private Notification createNotification(String title, String content) {
        PendingIntent contentIntent = buildContentIntent(title);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content))
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        if (!"SMB Service ready".equals(title)) {
            builder.setContentIntent(contentIntent);
        }

        // Show Cancel action only for operations other than Search, Upload, and Download
        boolean isCancelableOp = !(isSearchOperation || isUploadOperation || isDownloadOperation);
        if (hasActiveOperations() && isCancelableOp) {
            Intent cancelIntent = new Intent(this, SmbBackgroundService.class).setAction(ACTION_CANCEL);
            PendingIntent cancelPI = PendingIntent.getService(
                    this, 1, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(R.drawable.ic_notification, getString(R.string.cancel), cancelPI);
        }
        return builder.build();
    }

    private PendingIntent buildContentIntent(String title) {
        Intent toBrowser = null;
        String reqKey = "fallback";

        if (isSearchOperation && connectionId != null) {
            toBrowser = new Intent(this, FileBrowserActivity.class)
                    .setAction("de.schliweb.sambalite.OPEN_FROM_NOTIFICATION.SEARCH")
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("extra_connection_id", connectionId)
                    .putExtra("extra_search_query", searchQuery)
                    .putExtra("extra_search_type", searchType)
                    .putExtra("extra_search_include_subfolders", includeSubfolders)
                    .putExtra("extra_from_search_notification", true)
                    .putExtra("extra_show_progress_dialog", true)
                    .putExtra("extra_operation_name", title);
            reqKey = "search:" + connectionId + ":" + searchQuery;
        } else if (isUploadOperation && connectionId != null && uploadPath != null) {
            toBrowser = new Intent(this, FileBrowserActivity.class)
                    .setAction("de.schliweb.sambalite.OPEN_FROM_NOTIFICATION.UPLOAD")
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("extra_connection_id", connectionId)
                    .putExtra("extra_directory_path", uploadPath)
                    .putExtra("extra_from_upload_notification", true)
                    .putExtra("extra_show_progress_dialog", true)
                    .putExtra("extra_operation_name", title);
            reqKey = "upload:" + connectionId + ":" + uploadPath;
        } else if (isDownloadOperation && connectionId != null && downloadPath != null) {
            toBrowser = new Intent(this, FileBrowserActivity.class)
                    .setAction("de.schliweb.sambalite.OPEN_FROM_NOTIFICATION.DOWNLOAD")
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("extra_connection_id", connectionId)
                    .putExtra("extra_directory_path", downloadPath)
                    .putExtra("extra_from_download_notification", true)
                    .putExtra("extra_show_progress_dialog", true)
                    .putExtra("extra_operation_name", title);
            reqKey = "download:" + connectionId + ":" + downloadPath;
        }

        // Unique requestCode so Android doesn't recycle extras
        int requestCode = reqKey.hashCode();

        if (toBrowser != null) {
            return PendingIntent.getActivity(
                    this, requestCode, toBrowser, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else {
            Intent fallback = new Intent(this, FileBrowserActivity.class)
                    .setAction("de.schliweb.sambalite.OPEN_FROM_NOTIFICATION.GENERIC")
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra("extra_from_generic_notification", true)
                    .putExtra("extra_show_progress_dialog", true)
                    .putExtra("extra_operation_name", title);
            return PendingIntent.getActivity(
                    this, requestCode, fallback, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
    }

    private void startWatchdog() {
        if (watchdogExecutor == null || watchdogExecutor.isShutdown()) {
            watchdogExecutor = Executors.newSingleThreadScheduledExecutor();
        }
        watchdogExecutor.scheduleAtFixedRate(() -> {
            if (hasActiveOperations() && lastProgressUpdate.get() > 0) {
                long idle = System.currentTimeMillis() - lastProgressUpdate.get();
                if (idle > PROGRESS_WATCHDOG_INTERVAL_SECONDS * 1000L) {
                    LogUtils.w(TAG, "Watchdog: stalled op (" + (idle / 1000) + "s) — setting cancel flag");
                    updateNotificationImmediate("Operation timed out",
                            "No progress for " + (idle / 1000) + " seconds. Attempting to cancel.");
                }
            }
        }, PROGRESS_WATCHDOG_INTERVAL_SECONDS, PROGRESS_WATCHDOG_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private boolean shouldUpdateFileProgress(int currentFile, int totalFiles, int percentage) {
        if (currentFile == 1 || currentFile == totalFiles) return true;
        if (percentage % 10 == 0) return true;
        if (percentage % 5 == 0 && totalFiles > 100) return true;
        if (totalFiles > 1000) return currentFile % 50 == 0;
        if (totalFiles > 500) return currentFile % 25 == 0;
        if (totalFiles > 100) return currentFile % 10 == 0;
        if (totalFiles > 50) return currentFile % 5 == 0;
        if (totalFiles > 10) return currentFile % 2 == 0;
        return true;
    }

    private boolean shouldUpdateBytesProgress(int percentage) {
        return percentage % 10 == 0 || percentage >= 95;
    }

    // Context for a scheduled/managed operation
    static final class OpCtx {
        final String name;
        volatile Future<?> future;
        volatile ScheduledFuture<?> inactivityTask;
        volatile ScheduledFuture<?> absoluteTimeoutTask;

        AtomicBoolean cancelled = new AtomicBoolean(false);

        OpCtx(String name) {
            this.name = name;
        }
    }

    public class LocalBinder extends Binder {
        public SmbBackgroundService getService() {
            return SmbBackgroundService.this;
        }
    }
}
