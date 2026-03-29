/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui.utils;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Utility class for managing preferences related to the SambaLite application. Provides methods to
 * set and get the current SMB folder and refresh state.
 */
public class PreferenceUtils {

  private static final String PREF_NAME = "sambalite_prefs";

  /** Private constructor to prevent instantiation. */
  private PreferenceUtils() {
    // Private constructor to prevent instantiation
  }

  /**
   * Sets the current SMB folder path in shared preferences. Backward-compatible API retained for
   * existing callers.
   *
   * @param context the application context
   * @param path the path to set as the current SMB folder
   */
  public static void setCurrentSmbFolder(@NonNull Context context, @NonNull String path) {
    context
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(Constants.PREF_CURRENT_SMB_FOLDER, path)
        .apply();
  }

  /**
   * Stores both the current connection ID and the path in preferences. Also persists the path under
   * the legacy key for backward compatibility.
   */
  public static void setCurrentSmbContext(
      @NonNull Context context, @NonNull String connectionId, @NonNull String path) {
    context
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
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
  public static @NonNull String getCurrentSmbFolder(@NonNull Context context) {
    return context
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .getString(Constants.PREF_CURRENT_SMB_FOLDER, null);
  }

  /**
   * Returns the last used connection ID for the SMB context, if available. Can be null if the app
   * hasn't stored it yet (older versions).
   */
  public static @NonNull String getCurrentSmbConnectionId(@NonNull Context context) {
    return context
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .getString(Constants.PREF_CURRENT_SMB_CONNECTION_ID, null);
  }

  /**
   * Sets the needs refresh state in shared preferences.
   *
   * @param context the application context
   * @param value true if a refresh is needed, false otherwise
   */
  public static void setNeedsRefresh(@NonNull Context context, boolean value) {
    context
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
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
  public static boolean getNeedsRefresh(@NonNull Context context) {
    return context
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .getBoolean(Constants.NEEDS_REFRESH, false);
  }

  /**
   * Stores the last used download folder URI so the folder picker can reopen it. The URI is
   * converted to a document URI (via {@link android.provider.DocumentsContract}) so that the SAF
   * picker recognises the folder and immediately offers the "Use this folder" option.
   *
   * @param context the application context
   * @param uri the tree URI of the download folder (as returned by ACTION_OPEN_DOCUMENT_TREE)
   */
  public static void setLastDownloadFolderUri(@NonNull Context context, @NonNull Uri uri) {
    // Convert tree URI → document URI so EXTRA_INITIAL_URI works correctly in the picker
    String docUri;
    try {
      String treeDocId = android.provider.DocumentsContract.getTreeDocumentId(uri);
      docUri =
          android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, treeDocId).toString();
    } catch (Exception e) {
      // Fallback: store the original URI
      docUri = uri.toString();
    }
    context
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(Constants.PREF_LAST_DOWNLOAD_FOLDER_URI, docUri)
        .apply();
  }

  /**
   * Returns the last used download folder URI, or null if none was stored.
   *
   * @param context the application context
   * @return the last download folder URI, or null
   */
  @Nullable
  public static Uri getLastDownloadFolderUri(@NonNull Context context) {
    String uriString =
        context
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(Constants.PREF_LAST_DOWNLOAD_FOLDER_URI, null);
    return uriString != null ? Uri.parse(uriString) : null;
  }
}
