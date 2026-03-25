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

import android.webkit.MimeTypeMap;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Locale;

/** Utility class for determining MIME types from file names. */
public final class MimeTypeUtils {

  private MimeTypeUtils() {}

  /**
   * Returns the MIME type for a file based on its extension.
   *
   * @param fileName the file name (may include path)
   * @return the MIME type, or "application/octet-stream" if unknown
   */
  @NonNull
  public static String getMimeType(@Nullable String fileName) {
    if (fileName == null) return "application/octet-stream";
    String extension =
        fileName.contains(".")
            ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
            : "";
    if (extension.isEmpty()) return "application/octet-stream";
    String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
    return mimeType != null ? mimeType : "application/octet-stream";
  }
}
