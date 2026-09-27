/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.transfer;

import android.content.Context;
import androidx.annotation.NonNull;

/**
 * The number of persisted SAF grants an app may still take.
 *
 * <p>Android keeps at most {@link #ANDROID_MAX_PERSISTED_GRANTS} persisted URI grants per app
 * ({@code UriGrantsManagerService.MAX_PERSISTED_URI_GRANTS}). When an app takes more, the system
 * silently drops the oldest ones, including the grants of download folders and sync folders. A
 * multi-file upload therefore may only persist per-file grants while the selection fits into the
 * remaining budget; larger selections need a single grant on the parent folder instead.
 */
public final class PersistedGrantBudget {

  /** Android's per-app cap on persisted URI grants. */
  public static final int ANDROID_MAX_PERSISTED_GRANTS = 512;

  /** Grants kept free for download targets, sync folders and small later uploads. */
  public static final int RESERVE = 64;

  private PersistedGrantBudget() {}

  /**
   * Returns how many further persisted grants fit under the cap, minus the reserve.
   *
   * @param context Any context; the application context is used
   */
  public static int remaining(@NonNull Context context) {
    int used;
    try {
      used =
          context.getApplicationContext().getContentResolver().getPersistedUriPermissions().size();
    } catch (Exception e) {
      used = 0;
    }
    return capacity(used);
  }

  /** The remaining budget for a given number of already persisted grants, never negative. */
  static int capacity(int persistedCount) {
    return Math.max(0, ANDROID_MAX_PERSISTED_GRANTS - RESERVE - persistedCount);
  }

  /** Whether {@code count} additional per-file grants can be persisted without evicting others. */
  public static boolean fits(@NonNull Context context, int count) {
    return count <= remaining(context);
  }
}
