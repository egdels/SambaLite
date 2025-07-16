package de.schliweb.sambalite.service;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.ui.MainActivity;
import de.schliweb.sambalite.util.EnhancedFileUtils;
import de.schliweb.sambalite.util.LogUtils;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Foreground Service for long-running SMB operations in the background.
 * This service ensures that downloads and uploads continue even when
 * the app goes into background or the system tries to terminate the app.
 */
public class SmbBackgroundService extends Service {

    private static final String TAG = "SmbBackgroundService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "SMB_BACKGROUND_OPERATIONS";
    private static final long NOTIFICATION_UPDATE_INTERVAL_MS = 1000; // Max 1 update per second
    private final IBinder binder = new LocalBinder();
    private final AtomicInteger activeOperations = new AtomicInteger(0);
    private final android.os.Handler notificationHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private NotificationManager notificationManager;
    private volatile String currentOperation = "";
    private volatile boolean isRunning = false;
    // Notification throttling for better performance
    private volatile long lastNotificationUpdate = 0;
    private volatile Runnable pendingNotificationUpdate = null;

    @Override
    public void onCreate() {
        super.onCreate();
        LogUtils.d(TAG, "Background service created");

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();

        // Wake lock for reliable background operations
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SambaLite:BackgroundOperations");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LogUtils.d(TAG, "Background service started");

        if (!isRunning) {
            startForeground(NOTIFICATION_ID, createNotification("SMB Service ready", "Ready for background operations"));
            isRunning = true;

            // Activate wake lock
            if (!wakeLock.isHeld()) {
                wakeLock.acquire(10 * 60 * 1000L); // 10 minutes maximum
                LogUtils.d(TAG, "Wake lock acquired");
            }
        }

        // Service should automatically restart if terminated
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        LogUtils.d(TAG, "Background service destroyed");

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            LogUtils.d(TAG, "Wake lock released");
        }

        isRunning = false;
        super.onDestroy();
    }

    /**
     * Starts a new background operation
     */
    public void startOperation(String operationName) {
        int count = activeOperations.incrementAndGet();
        currentOperation = operationName;

        LogUtils.d(TAG, "Starting operation: " + operationName + " (active operations: " + count + ")");

        // Update notification
        updateNotificationThrottled(operationName, "Running operation...");

        // Extend wake lock if necessary
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(10 * 60 * 1000L);
            LogUtils.d(TAG, "Wake lock re-acquired for operation: " + operationName);
        }
    }

    /**
     * Finishes a background operation
     */
    public void finishOperation(String operationName, boolean success) {
        int count = activeOperations.decrementAndGet();

        LogUtils.d(TAG, "Finished operation: " + operationName + " (success: " + success + ", remaining operations: " + count + ")");

        if (count <= 0) {
            currentOperation = "";
            updateNotificationThrottled("SMB Service ready", "Ready for background operations");

            // Release wake lock when no operations are active
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                LogUtils.d(TAG, "Wake lock released - no active operations");
            }
        } else {
            updateNotificationThrottled(currentOperation, count + " operations active");
        }
    }

    /**
     * Updates the progress information of an operation
     */
    public void updateOperationProgress(String operationName, String progressInfo) {
        currentOperation = operationName;
        updateNotificationThrottled(operationName, progressInfo);
    }

    /**
     * Updates the progress information of an operation with file counter
     */
    public void updateFileProgress(String operationName, int currentFile, int totalFiles, String currentFileName) {
        // Calculate percentage
        int percentage = totalFiles > 0 ? ((currentFile * 100) / totalFiles) : 0;

        // Create progress info with percentage
        String progressInfo = percentage + "% (" + currentFile + "/" + totalFiles + ")";
        if (currentFileName != null && !currentFileName.isEmpty()) {
            // Shorten filename if too long for notification
            String displayName = currentFileName.length() > 25 ? currentFileName.substring(0, 22) + "..." : currentFileName;
            progressInfo += " " + displayName;
        }

        currentOperation = operationName + " (" + percentage + "%)";

        // Throttle file progress updates based on file count and percentage
        if (shouldUpdateFileProgress(currentFile, totalFiles, percentage)) {
            updateNotificationThrottled(operationName, progressInfo);
            LogUtils.d(TAG, "File progress updated (throttled): " + progressInfo);
        } else {
            LogUtils.v(TAG, "File progress skipped (throttling): " + progressInfo);
        }
    }

    /**
     * Updates the progress information of an operation with bytes progress
     */
    public void updateBytesProgress(String operationName, long currentBytes, long totalBytes, String fileName) {
        int percentage = totalBytes > 0 ? (int) ((currentBytes * 100) / totalBytes) : 0;
        String progressInfo = fileName + ": " + percentage + "% (" + EnhancedFileUtils.formatFileSize(currentBytes) + " / " + EnhancedFileUtils.formatFileSize(totalBytes) + ")";

        // Throttle bytes progress updates - only at 10% steps or at end
        if (shouldUpdateBytesProgress(percentage)) {
            updateNotificationThrottled(operationName, progressInfo);
        }
    }

    /**
     * Decides if file progress update should be shown (anti-spam)
     */
    private boolean shouldUpdateFileProgress(int currentFile, int totalFiles, int percentage) {
        // Always show: First and last file
        if (currentFile == 1 || currentFile == totalFiles) {
            return true;
        }

        // Always show: Important percentage milestones (every 5% or 10%)
        if (percentage % 10 == 0) {
            return true; // 10%, 20%, 30%, etc.
        }
        if (percentage % 5 == 0 && totalFiles > 100) {
            return true; // For many files: 5%, 15%, 25%, etc.
        }

        // Fallback: For very many files show less frequently
        if (totalFiles > 1000) {
            return currentFile % 50 == 0; // Every 50 files as fallback
        }

        if (totalFiles > 500) {
            return currentFile % 25 == 0; // Every 25 files as fallback
        }

        if (totalFiles > 100) {
            return currentFile % 10 == 0; // Every 10 files as fallback
        }

        if (totalFiles > 50) {
            return currentFile % 5 == 0; // Every 5 files as fallback
        }

        if (totalFiles > 10) {
            return currentFile % 2 == 0; // Every 2 files as fallback
        }

        // For very few files (≤10): Show every file
        return true;
    }

    /**
     * Decides if bytes progress update should be shown (anti-spam)
     */
    private boolean shouldUpdateBytesProgress(int percentage) {
        // Show only at 10% steps: 10%, 20%, 30%, ..., 100%
        return percentage % 10 == 0 || percentage >= 95;
    }

    /**
     * Throttled notification update - prevents Android notification rate limiting
     */
    private void updateNotificationThrottled(String title, String content) {
        long currentTime = System.currentTimeMillis();

        // Immediate update only if enough time has passed
        if (currentTime - lastNotificationUpdate >= NOTIFICATION_UPDATE_INTERVAL_MS) {
            updateNotificationImmediate(title, content);
            lastNotificationUpdate = currentTime;
            return;
        }

        // Otherwise: Schedule update for later
        if (pendingNotificationUpdate != null) {
            notificationHandler.removeCallbacks(pendingNotificationUpdate);
        }

        pendingNotificationUpdate = () -> {
            updateNotificationImmediate(title, content);
            lastNotificationUpdate = System.currentTimeMillis();
            pendingNotificationUpdate = null;
        };

        long delay = NOTIFICATION_UPDATE_INTERVAL_MS - (currentTime - lastNotificationUpdate);
        notificationHandler.postDelayed(pendingNotificationUpdate, Math.max(delay, 100));
    }

    /**
     * Immediate notification update (without throttling)
     */
    private void updateNotificationImmediate(String title, String content) {
        if (isRunning) {
            Notification notification = createNotification(title, content);
            notificationManager.notify(NOTIFICATION_ID, notification);
        }
    }

    /**
     * Checks if the service is actively performing background operations
     */
    public boolean hasActiveOperations() {
        return activeOperations.get() > 0;
    }

    /**
     * Returns the number of active operations
     */
    public int getActiveOperationCount() {
        return activeOperations.get();
    }

    /**
     * Creates the notification channel for Android 8.0+
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "SMB Background Operations", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows the status of SMB operations in the background");
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
            LogUtils.d(TAG, "Notification channel created");
        }
    }

    /**
     * Creates a notification for the foreground service
     */
    private Notification createNotification(String title, String content) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle(title).setContentText(content).setSmallIcon(R.drawable.ic_notification) // Fallback to a standard icon
                .setContentIntent(pendingIntent).setOngoing(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build();
    }

    public class LocalBinder extends Binder {
        public SmbBackgroundService getService() {
            return SmbBackgroundService.this;
        }
    }
}
