/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.util;

import android.net.Uri;
import android.provider.MediaStore;
import androidx.annotation.NonNull;

/**
 * Resolves the {@link TimestampCapability} for a given URI, indicating how reliably timestamps can
 * be preserved on that storage location.
 *
 * <p>The sync logic uses this to decide whether to trust filesystem timestamps or fall back to the
 * metadata database for comparisons.
 */
public class StorageCapabilityResolver {

  private static final String TAG = "StorageCapabilityResolver";

  /** Private constructor to prevent instantiation. */
  private StorageCapabilityResolver() {
    // Utility class
  }

  /**
   * Resolves the timestamp capability for the given URI.
   *
   * @param uri the URI to check
   * @return the {@link TimestampCapability} for this URI
   */
  @NonNull
  public static TimestampCapability resolve(@NonNull Uri uri) {
    String scheme = uri.getScheme();

    // file:// URIs → java.io.File, fully supported
    if ("file".equals(scheme)) {
      LogUtils.d(TAG, "[TIMESTAMP] Storage capability: " + uri + " → SUPPORTED (file URI)");
      return TimestampCapability.PRESERVE_SUPPORTED;
    }

    if ("content".equals(scheme)) {
      String authority = uri.getAuthority();

      // MediaStore URIs → best effort
      if (authority != null && authority.equals(MediaStore.AUTHORITY)) {
        LogUtils.d(TAG, "[TIMESTAMP] Storage capability: " + uri + " → BEST_EFFORT (MediaStore)");
        return TimestampCapability.PRESERVE_BEST_EFFORT;
      }

      // External storage provider (often used for Download/Documents on internal storage)
      // These typically support setLastModified via File path
      if (authority != null && authority.equals("com.android.externalstorage.documents")) {
        LogUtils.d(
            TAG,
            "[TIMESTAMP] Storage capability: "
                + uri
                + " → BEST_EFFORT (external storage documents)");
        return TimestampCapability.PRESERVE_BEST_EFFORT;
      }

      // All other content:// URIs (SAF tree URIs, other providers) → unreliable
      LogUtils.d(
          TAG,
          "[TIMESTAMP] Storage capability: "
              + uri
              + " → UNRELIABLE (content URI, authority="
              + authority
              + ")");
      return TimestampCapability.PRESERVE_UNRELIABLE;
    }

    // Unknown scheme → unreliable
    LogUtils.d(
        TAG,
        "[TIMESTAMP] Storage capability: "
            + uri
            + " → UNRELIABLE (unknown scheme: "
            + scheme
            + ")");
    return TimestampCapability.PRESERVE_UNRELIABLE;
  }
}
