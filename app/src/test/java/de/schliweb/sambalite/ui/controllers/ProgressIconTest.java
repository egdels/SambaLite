/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui.controllers;

import static org.junit.Assert.assertEquals;

import de.schliweb.sambalite.R;
import org.junit.Test;

/** The progress dialog shows an icon that matches the operation. */
public class ProgressIconTest {

  private static int icon(String title, String message) {
    return ProgressController.progressIconFor(
        title, message, "Uploading…", "Downloading…", "Opening %s…");
  }

  @Test
  public void upload_showsUploadIcon() {
    assertEquals(R.drawable.ic_upload, icon("Uploading…", "Preparing transfer…"));
  }

  @Test
  public void downloadAndOpen_showDownloadIcon() {
    assertEquals(R.drawable.ic_download, icon("Downloading…", "Preparing transfer…"));
    assertEquals(R.drawable.ic_download, icon("Opening aa_big.bin…", "Preparing…"));
  }

  @Test
  public void deleteOrOther_showsNeutralIcon() {
    assertEquals(R.drawable.ic_sync, icon("Deleting…", "Preparing…"));
    assertEquals(R.drawable.ic_sync, icon("Transfer", "Preparing transfer…"));
  }
}
