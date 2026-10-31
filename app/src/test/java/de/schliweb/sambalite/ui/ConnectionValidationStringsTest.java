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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import de.schliweb.sambalite.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Checks that the validation messages of the connection dialog list the forbidden characters
 * correctly in every language. Unescaped {@code \} and {@code "} in the string resources are
 * silently dropped by aapt, so the messages showed "/  : * ?  < > |".
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ConnectionValidationStringsTest {

  private static final String[] LOCALES = {"en", "de", "es", "fr", "nl", "pl", "zh"};

  @Test
  public void shareNameError_listsBackslashAndQuote() {
    for (String locale : LOCALES) {
      RuntimeEnvironment.setQualifiers(locale);
      Context context = ApplicationProvider.getApplicationContext();
      String message = context.getString(R.string.error_share_invalid);
      assertTrue(locale + ": " + message, message.contains("/ \\ : * ? \" < > |"));
    }
  }

  @Test
  public void defaultFolderError_listsQuoteButNotBackslash() {
    for (String locale : LOCALES) {
      RuntimeEnvironment.setQualifiers(locale);
      Context context = ApplicationProvider.getApplicationContext();
      String message = context.getString(R.string.error_default_folder_invalid);
      assertTrue(locale + ": " + message, message.contains(": * ? \" < > |"));
      // Backslashes are allowed in the default folder (they are converted to '/')
      assertFalse(locale + ": " + message, message.contains("\\"));
    }
  }
}
