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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Tests the folder shown below a search result. */
public class FileAdapterLocationTest {

  @Test
  public void entryInASubfolder_showsItsFolder() {
    assertEquals("/Photos/2026", FileAdapter.locationOf("Photos/2026/IMG_1.jpg"));
    assertEquals("/Docs", FileAdapter.locationOf("/Docs/report.pdf"));
  }

  @Test
  public void entryInTheShareRoot_showsSlash() {
    assertEquals("/", FileAdapter.locationOf("readme.txt"));
    assertEquals("/", FileAdapter.locationOf(null));
  }

  @Test
  public void backslashesAndTrailingSlashesAreHandled() {
    assertEquals("/Docs/Old", FileAdapter.locationOf("Docs\\Old\\a.txt"));
    assertEquals("/Projekte", FileAdapter.locationOf("Projekte/Kunde/"));
  }
}
