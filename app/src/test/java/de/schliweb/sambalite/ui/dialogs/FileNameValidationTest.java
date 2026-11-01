/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui.dialogs;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import de.schliweb.sambalite.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Names for rename and create folder must stay inside the current folder and be valid on SMB. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FileNameValidationTest {

  @Test
  public void validNames_areAccepted() {
    assertEquals(0, DialogHelper.fileNameError("notes2.txt"));
    assertEquals(0, DialogHelper.fileNameError("Grüße – ä ö ü ß 中文.txt"));
    assertEquals(0, DialogHelper.fileNameError("New folder"));
    assertEquals(0, DialogHelper.fileNameError(".hidden"));
  }

  @Test
  public void emptyName_isRequired() {
    assertEquals(R.string.error_file_name_required, DialogHelper.fileNameError(""));
  }

  @Test
  public void pathsAndForbiddenCharacters_areRejected() {
    for (String name :
        new String[] {
          "../moved.txt", "a/b.txt", "a\\b.txt", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b",
          ".", ".."
        }) {
      assertEquals(name, R.string.error_file_name_invalid, DialogHelper.fileNameError(name));
    }
  }

  @Test
  public void invalidNameMessage_listsAllCharacters() {
    Context context = ApplicationProvider.getApplicationContext();
    assertEquals(
        "Names must not contain / \\ : * ? \" < > | and must not be . or ..",
        context.getString(R.string.error_file_name_invalid));
  }
}
