package de.schliweb.sambalite.util;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import lombok.Getter;
import lombok.Setter;

/**
 * Activity Lifecycle Tracker for the app
 * Monitors app foreground/background transitions to improve SMB connection resilience
 */
public class SambaLiteLifecycleTracker implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "SambaLiteLifecycleTracker";

    private static SambaLiteLifecycleTracker instance;
    private int activityCount = 0;
    @Getter
    private boolean isAppInForeground = true;
    @Setter
    private LifecycleListener lifecycleListener;

    // Background operation cancellation callback
    @Setter
    private BackgroundOperationCanceller backgroundOperationCanceller;

    private SambaLiteLifecycleTracker() {
        LogUtils.d(TAG, "Lifecycle tracker initialized");
    }

    public static SambaLiteLifecycleTracker getInstance() {
        if (instance == null) {
            instance = new SambaLiteLifecycleTracker();
        }
        return instance;
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        LogUtils.d(TAG, "Activity created: " + activity.getClass().getSimpleName());
    }

    @Override
    public void onActivityStarted(Activity activity) {
        LogUtils.d(TAG, "Activity started: " + activity.getClass().getSimpleName());
        activityCount++;

        if (!isAppInForeground) {
            isAppInForeground = true;
            LogUtils.i(TAG, "App moved to FOREGROUND");

            if (lifecycleListener != null) {
                lifecycleListener.onAppMovedToForeground();
            }
        }
    }

    @Override
    public void onActivityResumed(Activity activity) {
        LogUtils.d(TAG, "Activity resumed: " + activity.getClass().getSimpleName());
    }

    @Override
    public void onActivityPaused(Activity activity) {
        LogUtils.d(TAG, "Activity paused: " + activity.getClass().getSimpleName());
    }

    @Override
    public void onActivityStopped(Activity activity) {
        LogUtils.d(TAG, "Activity stopped: " + activity.getClass().getSimpleName());
        activityCount--;

        if (activityCount <= 0 && isAppInForeground) {
            isAppInForeground = false;
            LogUtils.i(TAG, "App moved to BACKGROUND");

            // Cancel ongoing background operations to prevent hanging
            if (backgroundOperationCanceller != null) {
                LogUtils.i(TAG, "Cancelling background operations due to app background transition");
                backgroundOperationCanceller.cancelOngoingOperations();
            }

            if (lifecycleListener != null) {
                lifecycleListener.onAppMovedToBackground();
            }
        }
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        LogUtils.d(TAG, "Activity save instance state: " + activity.getClass().getSimpleName());
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        LogUtils.d(TAG, "Activity destroyed: " + activity.getClass().getSimpleName());
    }

    // Callbacks for background/foreground transitions
    public interface LifecycleListener {
        void onAppMovedToBackground();
        void onAppMovedToForeground();
    }

    // Interface for cancelling background operations
    public interface BackgroundOperationCanceller {
        void cancelOngoingOperations();
    }
}
