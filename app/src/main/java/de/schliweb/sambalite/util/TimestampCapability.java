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

/**
 * Describes how reliably timestamps can be preserved for a given storage location. Used by the sync
 * logic to decide whether to trust filesystem timestamps or fall back to the metadata database for
 * comparisons.
 */
public enum TimestampCapability {
  /** java.io.File – {@code setLastModified()} works reliably. */
  PRESERVE_SUPPORTED,

  /** MediaStore URI – timestamp setting may work but is not guaranteed. */
  PRESERVE_BEST_EFFORT,

  /** SAF Tree URI – timestamp preservation is not reliably possible. */
  PRESERVE_UNRELIABLE
}
