package de.schliweb.sambalite.ui.utils;

import android.content.Context;

/**
 * Utility class for managing preferences related to the SambaLite application.
 * Provides methods to set and get the current SMB folder and refresh state.
 */
public class PreferenceUtils {

    private static final String PREF_NAME = "sambalite_prefs";

    /**
     * Private constructor to prevent instantiation.
     */
    private PreferenceUtils() {
        // Private constructor to prevent instantiation
    }

    /**
     * Sets the current SMB folder path in shared preferences.
     * Backward-compatible API retained for existing callers.
     *
     * @param context the application context
     * @param path    the path to set as the current SMB folder
     */
    public static void setCurrentSmbFolder(Context context, String path) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(Constants.PREF_CURRENT_SMB_FOLDER, path)
                .apply();
    }

    /**
     * Stores both the current connection ID and the path in preferences.
     * Also persists the path under the legacy key for backward compatibility.
     */
    public static void setCurrentSmbContext(Context context, String connectionId, String path) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(Constants.PREF_CURRENT_SMB_CONNECTION_ID, connectionId)
                .putString(Constants.PREF_CURRENT_SMB_FOLDER, path)
                .apply();
    }

    /**
     * Gets the current SMB folder path from shared preferences.
     *
     * @param context the application context
     * @return the current SMB folder path, or null if not set
     */
    public static String getCurrentSmbFolder(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(Constants.PREF_CURRENT_SMB_FOLDER, null);
    }

    /**
     * Returns the last used connection ID for the SMB context, if available.
     * Can be null if the app hasn't stored it yet (older versions).
     */
    public static String getCurrentSmbConnectionId(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(Constants.PREF_CURRENT_SMB_CONNECTION_ID, null);
    }

    /**
     * Sets the needs refresh state in shared preferences.
     *
     * @param context the application context
     * @param value   true if a refresh is needed, false otherwise
     */
    public static void setNeedsRefresh(Context context, boolean value) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(Constants.NEEDS_REFRESH, value)
                .apply();
    }

    /**
     * Gets the needs refresh state from shared preferences.
     *
     * @param context the application context
     * @return true if a refresh is needed, false otherwise
     */
    public static boolean getNeedsRefresh(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(Constants.NEEDS_REFRESH, false);
    }

}
