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
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The selection count in the file browser's toolbar uses singular and plural forms. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = de.schliweb.sambalite.SambaLiteApp.class)
public class SelectionSubtitleTest {

  private static FileBrowserActivity activity() {
    return Robolectric.buildActivity(FileBrowserActivity.class).get();
  }

  @Test
  public void english() {
    FileBrowserActivity a = activity();
    assertEquals("1 file, 2 folders selected", a.selectionSubtitle(1, 2));
    assertEquals("3 files, 1 folder selected", a.selectionSubtitle(3, 1));
    assertEquals("1 file selected", a.selectionSubtitle(1, 0));
    assertEquals("1 folder selected", a.selectionSubtitle(0, 1));
    assertEquals("4 folders selected", a.selectionSubtitle(0, 4));
  }

  @Test
  @Config(qualifiers = "de")
  public void german() {
    FileBrowserActivity a = activity();
    assertEquals("1 Datei, 2 Ordner ausgewählt", a.selectionSubtitle(1, 2));
    assertEquals("1 Datei ausgewählt", a.selectionSubtitle(1, 0));
    assertEquals("5 Dateien ausgewählt", a.selectionSubtitle(5, 0));
  }

  @Test
  @Config(qualifiers = "pl")
  public void polish() {
    FileBrowserActivity a = activity();
    assertEquals("Zaznaczono: 3 pliki", a.selectionSubtitle(3, 0));
    assertEquals("Zaznaczono: 5 plików, 1 folder", a.selectionSubtitle(5, 1));
  }
}
