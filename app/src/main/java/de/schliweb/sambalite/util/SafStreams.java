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

import android.content.ContentResolver;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.FileNotFoundException;
import java.io.OutputStream;

/** Opens SAF documents for writing. */
public final class SafStreams {

  private SafStreams() {}

  /**
   * Opens a document for writing its complete new content.
   *
   * <p>Mode {@code "w"} does not truncate on every provider: the external storage provider keeps
   * the old length since Android 10, so a shorter new version left the tail of the old file behind
   * (and a size check afterwards failed). Mode {@code "wt"} truncates; providers that do not know
   * it get {@code "w"}.
   */
  @Nullable
  public static OutputStream openForOverwrite(@NonNull ContentResolver resolver, @NonNull Uri uri)
      throws FileNotFoundException {
    try {
      return resolver.openOutputStream(uri, "wt");
    } catch (IllegalArgumentException | UnsupportedOperationException | FileNotFoundException e) {
      LogUtils.w("SafStreams", "Mode wt not supported for " + uri + ", using w: " + e.getMessage());
      return resolver.openOutputStream(uri, "w");
    }
  }
}
