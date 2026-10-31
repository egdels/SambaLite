/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui.adapters;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Tests the remote folder shown in the transfer queue to tell same-named files apart. */
public class TransferItemRemoteFolderTest {

  @Test
  public void folderOfAFileInASubfolder() {
    assertEquals("Uploads", TransferItemAdapter.ViewHolder.remoteFolder("Uploads/foto_01.jpg"));
    assertEquals(
        "2026", TransferItemAdapter.ViewHolder.remoteFolder("/Photos/2026/IMG_0001.jpg"));
  }

  @Test
  public void backslashesAreFolderSeparatorsToo() {
    assertEquals("Docs", TransferItemAdapter.ViewHolder.remoteFolder("share\\Docs\\a.pdf"));
  }

  @Test
  public void fileInTheShareRootHasNoFolder() {
    assertEquals("", TransferItemAdapter.ViewHolder.remoteFolder("readme.txt"));
    assertEquals("", TransferItemAdapter.ViewHolder.remoteFolder("/readme.txt"));
    assertEquals("", TransferItemAdapter.ViewHolder.remoteFolder(null));
  }
}
