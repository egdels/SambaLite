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
import static org.junit.Assert.assertNotEquals;

import android.net.Uri;
import de.schliweb.sambalite.ui.operations.UploadRequest;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The entries of the "Files already exist" list name each file by its path below the current
 * folder, so two files of the same name from different subfolders are separate entries.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FileOperationsControllerConflictLabelTest {

  private static UploadRequest request(String remotePath, String name) {
    return new UploadRequest(Uri.parse("content://test/" + remotePath), remotePath, name, 1);
  }

  @Test
  public void label_isPathBelowCurrentFolder() {
    UploadRequest a = request("QA3/Case/a/readme.txt", "readme.txt");
    UploadRequest b = request("QA3/Case/b/readme.txt", "readme.txt");

    assertEquals("a/readme.txt", FileOperationsController.conflictLabel(a, "QA3/Case"));
    assertEquals("b/readme.txt", FileOperationsController.conflictLabel(b, "QA3/Case"));
    assertNotEquals(
        FileOperationsController.conflictLabel(a, "QA3/Case"),
        FileOperationsController.conflictLabel(b, "QA3/Case"));
  }

  @Test
  public void label_atShareRootIsTheRemotePath() {
    assertEquals(
        "a/readme.txt",
        FileOperationsController.conflictLabel(request("a/readme.txt", "readme.txt"), ""));
    assertEquals(
        "x.txt", FileOperationsController.conflictLabel(request("Docs/x.txt", "x.txt"), "Docs"));
  }

  @Test
  public void label_outsideCurrentFolderFallsBackToName() {
    assertEquals(
        "x.txt",
        FileOperationsController.conflictLabel(request("Other/x.txt", "x.txt"), "Docs"));
  }
}
