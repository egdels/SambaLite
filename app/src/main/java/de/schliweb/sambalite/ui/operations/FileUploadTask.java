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

import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;
import lombok.Getter;
import lombok.Setter;

/**
 * Represents a file upload task. Extracted from FileBrowserViewModel to be used by
 * FileOperationsViewModel.
 */
@Getter
@Setter
public class FileUploadTask {
  private DocumentFile file;
  private String relativePath;
  private boolean isDirectory;
  private boolean uploaded;
  private boolean skipped;

  public FileUploadTask(
      @NonNull DocumentFile file, @NonNull String relativePath, boolean isDirectory) {
    this.file = file;
    this.relativePath = relativePath;
    this.isDirectory = isDirectory;
    this.uploaded = false;
    this.skipped = false;
  }
}
