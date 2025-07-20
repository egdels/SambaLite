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
import de.schliweb.sambalite.ui.FileBrowserActivity;
import de.schliweb.sambalite.ui.MainActivity;
import de.schliweb.sambalite.util.EnhancedFileUtils;
import de.schliweb.sambalite.util.LogUtils;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * SmbBackgroundService is an Android Service designed to handle background operations
 * related to SMB (Server Message Block) tasks such as file uploads, downloads, and searches.
 * This service runs in the foreground to ensure uninterrupted execution, even when the app
 * is not actively in use, and provides notifications for operation progress.
 * <p>
 * This class extends the android.app.Service class and is primarily responsible for:
 * - Managing background operations such as uploading, downloading, and searching for files.
 * - Updating users about the progress or completion of these operations through notifications.
 * - Allowing the configuration of operation-specific parameters (e.g., connections, paths, queries).
 * - Supporting throttled updates to prevent excessive notification updates.
 * <p>
 * The service uses Android's notification system to display progress and status updates to users,
 * and it implements features to manage concurrent operations and throttle updates to adhere
 * to Android's notification rate limits.
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
    // Operation parameters
    private String connectionId;
    private String searchQuery;
    private int searchType;
    private boolean includeSubfolders;
    private boolean isSearchOperation = false;
    // Upload and download operation parameters
    private String uploadPath;
    private String downloadPath;
    private boolean isUploadOperation = false;
    private boolean isDownloadOperation = false;

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
     * Sets search operation parameters
     */
    public void setSearchParameters(String connectionId, String searchQuery, int searchType, boolean includeSubfolders) {
        this.connectionId = connectionId;
        this.searchQuery = searchQuery;
        this.searchType = searchType;
        this.includeSubfolders = includeSubfolders;
        this.isSearchOperation = true;
        this.isUploadOperation = false;
        this.isDownloadOperation = false;
        LogUtils.d(TAG, "Search parameters set: connectionId=" + connectionId + ", query=" + searchQuery);
    }

    /**
     * Sets upload operation parameters
     */
    public void setUploadParameters(String connectionId, String uploadPath) {
        this.connectionId = connectionId;
        this.uploadPath = uploadPath;
        this.isUploadOperation = true;
        this.isSearchOperation = false;
        this.isDownloadOperation = false;
        LogUtils.d(TAG, "Upload parameters set: connectionId=" + connectionId + ", path=" + uploadPath);
    }

    /**
     * Sets download operation parameters
     */
    public void setDownloadParameters(String connectionId, String downloadPath) {
        this.connectionId = connectionId;
        this.downloadPath = downloadPath;
        this.isDownloadOperation = true;
        this.isSearchOperation = false;
        this.isUploadOperation = false;
        LogUtils.d(TAG, "Download parameters set: connectionId=" + connectionId + ", path=" + downloadPath);
    }

    /**
     * Clears search operation parameters
     */
    public void clearSearchParameters() {
        this.isSearchOperation = false;
        LogUtils.d(TAG, "Search parameters cleared");
    }

    /**
     * Clears upload operation parameters
     */
    public void clearUploadParameters() {
        this.isUploadOperation = false;
        LogUtils.d(TAG, "Upload parameters cleared");
    }

    /**
     * Clears download operation parameters
     */
    public void clearDownloadParameters() {
        this.isDownloadOperation = false;
        LogUtils.d(TAG, "Download parameters cleared");
    }

    /**
     * Creates a notification for the foreground service
     */
    private Notification createNotification(String title, String content) {
        PendingIntent pendingIntent;

        if (isSearchOperation && title.startsWith("Searching for:") && connectionId != null) {
            // For search operations, create an intent that opens the RefactoredFileBrowserActivity
            LogUtils.d(TAG, "Creating search-specific notification");
            Intent notificationIntent = new Intent(this, FileBrowserActivity.class);
            notificationIntent.putExtra("extra_connection_id", connectionId);
            notificationIntent.putExtra("extra_search_query", searchQuery);
            notificationIntent.putExtra("extra_search_type", searchType);
            notificationIntent.putExtra("extra_search_include_subfolders", includeSubfolders);
            notificationIntent.putExtra("extra_from_search_notification", true);
            notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else if (isUploadOperation && title.startsWith("Uploading:") && connectionId != null && uploadPath != null) {
            // For upload operations, create an intent that opens the RefactoredFileBrowserActivity
            LogUtils.d(TAG, "Creating upload-specific notification");
            Intent notificationIntent = new Intent(this, FileBrowserActivity.class);
            notificationIntent.putExtra("extra_connection_id", connectionId);
            notificationIntent.putExtra("extra_directory_path", uploadPath);
            notificationIntent.putExtra("extra_from_upload_notification", true);
            notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else if (isDownloadOperation && title.startsWith("Downloading:") && connectionId != null && downloadPath != null) {
            // For download operations, create an intent that opens the RefactoredFileBrowserActivity
            LogUtils.d(TAG, "Creating download-specific notification");
            Intent notificationIntent = new Intent(this, FileBrowserActivity.class);
            notificationIntent.putExtra("extra_connection_id", connectionId);
            notificationIntent.putExtra("extra_directory_path", downloadPath);
            notificationIntent.putExtra("extra_from_download_notification", true);
            notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else {
            // For other operations, create an intent that opens the MainActivity
            Intent notificationIntent = new Intent(this, MainActivity.class);
            pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle(title).setContentText(content).setSmallIcon(R.drawable.ic_notification).setOngoing(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_SERVICE);

        // Only set content intent if it's not the "SMB Service ready" notification
        if (!"SMB Service ready".equals(title)) {
            builder.setContentIntent(pendingIntent);
        }

        return builder.build();
    }

    public class LocalBinder extends Binder {
        public SmbBackgroundService getService() {
            return SmbBackgroundService.this;
        }
    }
}
