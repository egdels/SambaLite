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
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import de.schliweb.sambalite.R;
import java.util.Locale;

/**
 * Turns the error stored with a failed transfer into a short reason for the transfer queue. The
 * worker stores either a message written for the user (e.g. that access to the local file was
 * lost), which is shown as it is, or a technical one such as {@code STATUS_ACCESS_DENIED
 * (0xc0000022): Create failed for \\server\share\file}, which is replaced by a readable reason. The
 * full text stays available under "Error details".
 */
public final class TransferErrorText {

  private TransferErrorText() {}

  /**
   * Returns the text to show in the transfer queue for a stored error.
   *
   * @param context for the string resources
   * @param error the stored error, may be null
   * @return a readable reason, the stored text if it is already readable, or null if there is none
   */
  @Nullable
  public static String shortReason(@NonNull Context context, @Nullable String error) {
    if (error == null || error.trim().isEmpty()) {
      return null;
    }
    int res = reasonRes(error);
    return res != 0 ? context.getString(res) : error.trim();
  }

  /**
   * Returns a readable reason for a failed file operation such as delete, rename or create folder.
   * The repository wraps the real error (e.g. {@code STATUS_ACCESS_DENIED} or "Target path already
   * exists") in a generic "Failed to execute share operation" exception, so the whole cause chain
   * is mapped; if nothing matches, the message of the innermost cause is shown.
   *
   * @param context for the string resources
   * @param error the exception of the failed operation
   * @return a readable reason, never empty
   */
  @NonNull
  public static String reasonFor(@NonNull Context context, @NonNull Throwable error) {
    int res = reasonRes(de.schliweb.sambalite.ui.MainViewModel.describeFailure(error));
    if (res != 0) {
      return context.getString(res);
    }
    String innermost = null;
    java.util.Set<Throwable> seen =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    for (Throwable t = error; t != null && seen.add(t); t = t.getCause()) {
      if (t.getMessage() != null && !t.getMessage().trim().isEmpty()) {
        innermost = t.getMessage().trim();
      }
    }
    return innermost != null ? innermost : context.getString(R.string.transfer_error_server);
  }

  /**
   * Maps a technical error to a readable reason.
   *
   * @param error the stored error
   * @return the string resource of the reason, or 0 if the error is not technical and is shown as
   *     it is
   */
  @StringRes
  static int reasonRes(@Nullable String error) {
    if (error == null || error.trim().isEmpty()) {
      return 0;
    }
    String e = error.toUpperCase(Locale.ROOT);
    if (e.contains("ACCESS_DENIED")) {
      return R.string.transfer_error_access_denied;
    }
    if (containsAny(e, "OBJECT_NAME_COLLISION", "ALREADY EXISTS")) {
      return R.string.transfer_error_exists;
    }
    if (containsAny(
        e, "LOGON_FAILURE", "WRONG_PASSWORD", "PASSWORD_EXPIRED", "ACCOUNT_DISABLED", "ACCOUNT_")) {
      return R.string.transfer_error_login;
    }
    if (containsAny(e, "DISK_FULL", "QUOTA_EXCEEDED")) {
      return R.string.transfer_error_disk_full;
    }
    if (containsAny(e, "SHARING_VIOLATION", "FILE_LOCK_CONFLICT", "LOCK_NOT_GRANTED")) {
      return R.string.transfer_error_in_use;
    }
    if (containsAny(
        e, "OBJECT_NAME_NOT_FOUND", "OBJECT_PATH_NOT_FOUND", "NO_SUCH_FILE", "BAD_NETWORK_NAME")) {
      return R.string.transfer_error_not_found;
    }
    if (containsAny(
        e,
        "CONNECTION ERROR",
        "CONNECTION NOT FOUND",
        "CONNECTEXCEPTION",
        "CONNECTION REFUSED",
        "CONNECTION RESET",
        "SOCKETTIMEOUT",
        "TIMED OUT",
        "TIMEOUT",
        "TRANSPORTEXCEPTION",
        "BROKEN PIPE",
        "UNKNOWNHOST",
        "NOROUTETOHOST",
        "NETWORK IS UNREACHABLE",
        "NETWORK_NAME_DELETED",
        "CONNECTION_DISCONNECTED")) {
      return R.string.transfer_error_connection;
    }
    if (containsAny(e, "STATUS_", "EXCEPTION", "(0X")) {
      return R.string.transfer_error_server;
    }
    return 0;
  }

  private static boolean containsAny(String text, String... parts) {
    for (String part : parts) {
      if (text.contains(part)) {
        return true;
      }
    }
    return false;
  }
}
