/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui;

/**
 * Enum for file sorting options. Extracted from FileBrowserViewModel to avoid circular
 * dependencies.
 */
public enum FileSortOption {
  NAME,
  DATE,
  SIZE
}
