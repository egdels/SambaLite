package de.schliweb.sambalite.util;

import android.util.Log;
import timber.log.Timber;
import timber.log.Timber.DebugTree;

/**
 * Utility class for logging using Timber.
 * Provides a consistent interface for logging throughout the application.
 */
public class LogUtils {

    private static final String DEFAULT_TAG = "SambaLite";

    /**
     * Private constructor to prevent instantiation.
     */
    private LogUtils() {
        // Private constructor to prevent instantiation
    }

    /**
     * Initialize Timber for the application.
     * Should be called in the Application class.
     *
     * @param isDebugBuild whether the app is running in debug mode
     */
    public static void init(boolean isDebugBuild) {
        if (isDebugBuild) {
            // Plant a debug tree in debug builds
            Timber.plant(new DebugTree());
        } else {
            // Plant a release tree in release builds
            Timber.plant(new ReleaseTree());
        }
    }

    /**
     * Log a verbose message.
     *
     * @param message the message to log
     */
    public static void v(String message) {
        Timber.v(message);
    }

    /**
     * Log a verbose message with a tag.
     *
     * @param tag     the tag to use
     * @param message the message to log
     */
    public static void v(String tag, String message) {
        Timber.tag(tag).v(message);
    }

    /**
     * Log a debug message.
     *
     * @param message the message to log
     */
    public static void d(String message) {
        Timber.d(message);
    }

    /**
     * Log a debug message with a tag.
     *
     * @param tag     the tag to use
     * @param message the message to log
     */
    public static void d(String tag, String message) {
        Timber.tag(tag).d(message);
    }

    /**
     * Log an info message.
     *
     * @param message the message to log
     */
    public static void i(String message) {
        Timber.i(message);
    }

    /**
     * Log an info message with a tag.
     *
     * @param tag     the tag to use
     * @param message the message to log
     */
    public static void i(String tag, String message) {
        Timber.tag(tag).i(message);
    }

    /**
     * Log a warning message.
     *
     * @param message the message to log
     */
    public static void w(String message) {
        Timber.w(message);
    }

    /**
     * Log a warning message with a tag.
     *
     * @param tag     the tag to use
     * @param message the message to log
     */
    public static void w(String tag, String message) {
        Timber.tag(tag).w(message);
    }

    /**
     * Log a warning message with a throwable.
     *
     * @param t       the throwable to log
     * @param message the message to log
     */
    public static void w(Throwable t, String message) {
        Timber.w(t, message);
    }

    /**
     * Log an error message.
     *
     * @param message the message to log
     */
    public static void e(String message) {
        Timber.e(message);
    }

    /**
     * Log an error message with a tag.
     *
     * @param tag     the tag to use
     * @param message the message to log
     */
    public static void e(String tag, String message) {
        Timber.tag(tag).e(message);
    }

    /**
     * Log an error message with a throwable.
     *
     * @param t       the throwable to log
     * @param message the message to log
     */
    public static void e(Throwable t, String message) {
        Timber.e(t, message);
    }

    /**
     * A custom Timber tree for release builds.
     * Filters out logs below a certain priority and logs to Crashlytics.
     */
    private static class ReleaseTree extends Timber.Tree {
        @Override
        protected void log(int priority, String tag, String message, Throwable t) {
            // Only log warnings and errors in release builds
            if (priority < Log.INFO) {
                return;
            }

            // Use default tag if none provided
            String logTag = tag != null ? tag : DEFAULT_TAG;

            // Log to Android's log system
            if (t != null) {
                Log.println(priority, logTag, message + "\n" + Log.getStackTraceString(t));
            } else {
                Log.println(priority, logTag, message);
            }
        }
    }
}
