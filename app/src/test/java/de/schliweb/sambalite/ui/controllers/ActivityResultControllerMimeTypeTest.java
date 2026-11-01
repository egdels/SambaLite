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
import static org.robolectric.Shadows.shadowOf;

import android.webkit.MimeTypeMap;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The "save as" picker for a single download gets the file's real type, so that the provider keeps
 * the extension when it makes the name unique.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ActivityResultControllerMimeTypeTest {

  @Before
  public void setUp() {
    shadowOf(MimeTypeMap.getSingleton()).addExtensionMimeTypeMapping("txt", "text/plain");
    shadowOf(MimeTypeMap.getSingleton()).addExtensionMimeTypeMapping("jpg", "image/jpeg");
  }

  @Test
  public void mimeType_fromExtension_ignoringCase() {
    assertEquals("text/plain", ActivityResultController.mimeTypeForName("readme.txt"));
    assertEquals("image/jpeg", ActivityResultController.mimeTypeForName("Photo.JPG"));
  }

  @Test
  public void mimeType_unknownOrMissingExtension_isOctetStream() {
    assertEquals("application/octet-stream", ActivityResultController.mimeTypeForName("x.qqq"));
    assertEquals("application/octet-stream", ActivityResultController.mimeTypeForName("Makefile"));
    assertEquals("application/octet-stream", ActivityResultController.mimeTypeForName(".hidden"));
    assertEquals("application/octet-stream", ActivityResultController.mimeTypeForName("name."));
  }
}
