/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb2.SMBApiException;
import java.io.FileNotFoundException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Cause of a sync error, as far as it can be told from the exception. The System Monitor groups the
 * errors of a run by category and shows a hint per category, so the user sees "8× access denied"
 * with what to do instead of eight raw exception messages.
 */
public enum SyncErrorCategory {
  /** The server refused access (share or folder permissions). */
  ACCESS_DENIED,
  /**
   * The name is not allowed on the server: it contains a character SMB forbids (found before the
   * upload), or the server said so.
   */
  INVALID_NAME,
  /**
   * The server failed without a telling status on a name that ends with a dot or a space. SMB
   * allows such names, but Windows servers often reject or strip them, so this is a likely cause,
   * not a certain one.
   */
  INVALID_NAME_LIKELY,
  /** The path is longer than the server accepts. */
  NAME_TOO_LONG,
  /** The server volume is full. */
  SERVER_FULL,
  /** The file is opened by another program on the server. */
  FILE_IN_USE,
  /** The file or folder disappeared while the sync ran. */
  NOT_FOUND,
  /** The connection to the server failed or dropped. */
  NETWORK,
  /** The server rejected the username or password. */
  AUTH,
  /** The share does not exist (any more) on the server. */
  SHARE_NOT_FOUND,
  /** The local file or folder could not be read or written. */
  LOCAL_ACCESS,
  /** The local folder of the sync is gone or its permission was revoked. */
  LOCAL_FOLDER_MISSING,
  /** Too little free space on the device. */
  LOCAL_SPACE,
  /** A whole folder could not be listed, so its content was not synced in this run. */
  FOLDER_SKIPPED,
  /** Anything else. */
  OTHER;

  private static final int MAX_CAUSE_DEPTH = 8;

  /** Returns the category of {@code error}; see {@link #classify(Throwable, String)}. */
  @NonNull
  public static SyncErrorCategory classify(@Nullable Throwable error) {
    return classify(error, null);
  }

  /**
   * Returns the category of {@code error}. SMB status codes win over everything else; the cause
   * chain and the messages are searched as well, because SMBJ often wraps the {@link
   * SMBApiException}. When the server gives no telling status and {@code fileName} ends with a dot
   * or a space, the name is the likely cause. Names with characters SMB forbids never reach the
   * server: the sync skips them before the upload ({@link #hasForbiddenSmbCharacter}).
   */
  @NonNull
  public static SyncErrorCategory classify(@Nullable Throwable error, @Nullable String fileName) {
    SyncErrorCategory found = null;
    Throwable t = error;
    for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH && found == null; depth++) {
      found = fromThrowable(t);
      t = t.getCause();
    }
    if (found == null) {
      t = error;
      for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH && found == null; depth++) {
        found = fromMessage(t.getMessage());
        t = t.getCause();
      }
    }
    if ((found == null || found == OTHER) && fileName != null && endsWithDotOrSpace(fileName)) {
      return INVALID_NAME_LIKELY;
    }
    return found != null ? found : OTHER;
  }

  @Nullable
  private static SyncErrorCategory fromThrowable(@NonNull Throwable t) {
    if (t instanceof SMBApiException) {
      SyncErrorCategory c = fromStatus(((SMBApiException) t).getStatus());
      if (c != null) return c;
    }
    if (t instanceof SocketTimeoutException
        || t instanceof ConnectException
        || t instanceof NoRouteToHostException
        || t instanceof UnknownHostException
        || t instanceof SocketException) {
      return NETWORK;
    }
    String simpleName = t.getClass().getSimpleName();
    if ("TransportException".equals(simpleName)) {
      return NETWORK;
    }
    if ("InsufficientDiskSpaceException".equals(simpleName)) {
      return LOCAL_SPACE;
    }
    if ("LocalFolderMissingException".equals(simpleName)) {
      return LOCAL_FOLDER_MISSING;
    }
    if (t instanceof SecurityException || t instanceof FileNotFoundException) {
      return LOCAL_ACCESS;
    }
    return null;
  }

  @Nullable
  static SyncErrorCategory fromStatus(@Nullable NtStatus status) {
    if (status == null) return null;
    switch (status) {
      case STATUS_ACCESS_DENIED:
        return ACCESS_DENIED;
      case STATUS_OBJECT_NAME_INVALID:
        return INVALID_NAME;
      case STATUS_NAME_TOO_LONG:
        return NAME_TOO_LONG;
      case STATUS_DISK_FULL:
        return SERVER_FULL;
      case STATUS_SHARING_VIOLATION:
        return FILE_IN_USE;
      case STATUS_OBJECT_NAME_NOT_FOUND:
      case STATUS_OBJECT_PATH_NOT_FOUND:
        return NOT_FOUND;
      case STATUS_LOGON_FAILURE:
      case STATUS_ACCOUNT_DISABLED:
      case STATUS_PASSWORD_EXPIRED:
        return AUTH;
      case STATUS_BAD_NETWORK_NAME:
      case STATUS_NETWORK_NAME_DELETED:
        return SHARE_NOT_FOUND;
      case STATUS_CONNECTION_DISCONNECTED:
      case STATUS_CONNECTION_RESET:
      case STATUS_IO_TIMEOUT:
        return NETWORK;
      default:
        return null;
    }
  }

  @Nullable
  private static SyncErrorCategory fromMessage(@Nullable String message) {
    if (message == null) return null;
    String m = message.toUpperCase(Locale.ROOT);
    for (NtStatus status :
        new NtStatus[] {
          NtStatus.STATUS_ACCESS_DENIED,
          NtStatus.STATUS_OBJECT_NAME_INVALID,
          NtStatus.STATUS_NAME_TOO_LONG,
          NtStatus.STATUS_DISK_FULL,
          NtStatus.STATUS_SHARING_VIOLATION,
          NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
          NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
          NtStatus.STATUS_LOGON_FAILURE,
          NtStatus.STATUS_BAD_NETWORK_NAME,
          NtStatus.STATUS_CONNECTION_RESET,
          NtStatus.STATUS_IO_TIMEOUT
        }) {
      if (m.contains(status.name())) {
        return fromStatus(status);
      }
    }
    if (m.contains("NO SPACE LEFT") || m.contains("ENOSPC")) {
      return LOCAL_SPACE;
    }
    return null;
  }

  /**
   * Returns the first character of {@code name} that SMB does not allow in names: one of {@code \ /
   * : * ? " < > |} or a control character; else {@code null}. Such a name cannot exist on any SMB
   * server, and some servers would take a {@code :} for a stream name, so it is never sent.
   */
  @Nullable
  public static Character forbiddenSmbCharacter(@NonNull String name) {
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c < 0x20 || "\\/:*?\"<>|".indexOf(c) >= 0) {
        return c;
      }
    }
    return null;
  }

  /** Returns whether {@code name} contains a character SMB does not allow in names. */
  public static boolean hasForbiddenSmbCharacter(@NonNull String name) {
    return forbiddenSmbCharacter(name) != null;
  }

  /**
   * Returns whether {@code name} ends with a dot or a space. SMB allows that, but Windows servers
   * often reject or strip it.
   */
  public static boolean endsWithDotOrSpace(@NonNull String name) {
    if (name.isEmpty()) return false;
    char last = name.charAt(name.length() - 1);
    return last == '.' || last == ' ';
  }

  /** Returns a short message for {@code error}: its message, else its class name. */
  @NonNull
  public static String describe(@Nullable Throwable error) {
    if (error == null) return "";
    String message = error.getMessage();
    if (message == null || message.isEmpty()) {
      return error.getClass().getSimpleName();
    }
    return message;
  }
}
