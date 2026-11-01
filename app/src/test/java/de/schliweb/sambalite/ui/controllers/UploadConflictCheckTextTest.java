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

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import de.schliweb.sambalite.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The "checking files" notice of an upload into one folder uses proper singular and plural. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class UploadConflictCheckTextTest {

  private static String text(int files) {
    Context c = ApplicationProvider.getApplicationContext();
    return c.getResources()
        .getQuantityString(R.plurals.upload_conflict_check_one_folder, files, files);
  }

  @Test
  public void english() {
    assertEquals("Checking 1 file on the server…", text(1));
    assertEquals("Checking 4 files on the server…", text(4));
  }

  @Test
  @Config(qualifiers = "pl")
  public void polish() {
    assertEquals("Sprawdzanie 1 pliku na serwerze…", text(1));
    assertEquals("Sprawdzanie 3 plików na serwerze…", text(3));
    assertEquals("Sprawdzanie 5 plików na serwerze…", text(5));
  }
}
