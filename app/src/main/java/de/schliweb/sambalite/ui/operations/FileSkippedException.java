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

/**
 * Exception thrown when a file upload is skipped. Extracted from FileBrowserViewModel to be used by
 * FileOperationsViewModel.
 */
public class FileSkippedException extends Exception {

  /**
   * Creates a new FileSkippedException with the specified message.
   *
   * @param message The exception message
   */
  public FileSkippedException(@NonNull String message) {
    super(message);
  }
}
