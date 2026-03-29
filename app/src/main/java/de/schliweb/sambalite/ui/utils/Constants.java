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

/**
 * Constants used throughout the SambaLite application. This class holds static final strings for
 * various preferences and states.
 */
public class Constants {
  public static final String PREF_CURRENT_SMB_FOLDER = "current_smb_folder";
  public static final String PREF_CURRENT_SMB_CONNECTION_ID = "current_smb_connection_id";
  public static final String NEEDS_REFRESH = "needs_refresh";
  public static final String PREF_LAST_DOWNLOAD_FOLDER_URI = "last_download_folder_uri";

  /** Private constructor to prevent instantiation. */
  private Constants() {
    // Private constructor to prevent instantiation
  }
}
