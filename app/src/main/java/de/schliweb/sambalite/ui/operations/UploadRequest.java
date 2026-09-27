/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui.operations;

import android.net.Uri;
import androidx.annotation.NonNull;

/** A local file that is to be uploaded to a remote path. */
public final class UploadRequest {

  /** SAF URI of the local file. */
  @NonNull public final Uri uri;

  /** Share-relative remote path of the target file, e.g. {@code Photos/2024/a.jpg}. */
  @NonNull public final String remotePath;

  /** File name shown in the queue UI and in conflict dialogs. */
  @NonNull public final String displayName;

  /** File size in bytes, 0 if unknown. */
  public final long fileSize;

  public UploadRequest(
      @NonNull Uri uri, @NonNull String remotePath, @NonNull String displayName, long fileSize) {
    this.uri = uri;
    this.remotePath = remotePath;
    this.displayName = displayName;
    this.fileSize = fileSize;
  }

  /** The share-relative directory part of {@link #remotePath}, empty for the share root. */
  @NonNull
  public String remoteDirectory() {
    int slash = remotePath.lastIndexOf('/');
    return slash < 0 ? "" : remotePath.substring(0, slash);
  }

  /** The file name part of {@link #remotePath}. */
  @NonNull
  public String remoteFileName() {
    int slash = remotePath.lastIndexOf('/');
    return slash < 0 ? remotePath : remotePath.substring(slash + 1);
  }
}
