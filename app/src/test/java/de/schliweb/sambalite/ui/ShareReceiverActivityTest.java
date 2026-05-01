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

import static org.junit.Assert.*;

import android.content.Intent;
import android.net.Uri;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Unit tests for ShareReceiverActivity helper methods. Uses reflection to test private methods
 * since the Activity has heavy DI dependencies.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = de.schliweb.sambalite.SambaLiteApp.class)
public class ShareReceiverActivityTest {

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  private ShareReceiverActivity activity;

  @Before
  public void setUp() {
    Intent intent = new Intent(Intent.ACTION_SEND);
    intent.setType("text/plain");
    // Build the activity but don't call onCreate (avoids DI issues)
    activity = Robolectric.buildActivity(ShareReceiverActivity.class, intent).get();
  }

  // --- buildDisplayFolder tests (Issue #27) ---
  // The persisted folder is the *internal* path (without the share-name prefix). The display
  // string for the confirmation dialog is rebuilt at runtime from share + path, so the share
  // name is no longer accidentally treated as a connection name.

  @Test
  public void buildDisplayFolder_unknownConnectionId_returnsPath() throws Exception {
    Method method =
        ShareReceiverActivity.class.getDeclaredMethod(
            "buildDisplayFolder", String.class, String.class);
    method.setAccessible(true);

    // Without DI the connection repository is null/empty -> share resolves to "" and we just
    // return the (possibly empty) internal path.
    assertEquals("docs", method.invoke(activity, "missing-id", "docs"));
    assertEquals("a/b/c", method.invoke(activity, "missing-id", "a/b/c"));
    assertEquals("", method.invoke(activity, "missing-id", ""));
    assertEquals("", method.invoke(activity, "missing-id", null));
  }

  @Test
  public void buildDisplayFolder_stripsLeadingSlash() throws Exception {
    Method method =
        ShareReceiverActivity.class.getDeclaredMethod(
            "buildDisplayFolder", String.class, String.class);
    method.setAccessible(true);

    assertEquals("docs/sub", method.invoke(activity, "missing-id", "/docs/sub"));
  }

  @Test
  public void buildDisplayFolder_emptyConnectionId_returnsPath() throws Exception {
    Method method =
        ShareReceiverActivity.class.getDeclaredMethod(
            "buildDisplayFolder", String.class, String.class);
    method.setAccessible(true);

    assertEquals("docs", method.invoke(activity, "", "docs"));
    assertEquals("docs", method.invoke(activity, (String) null, "docs"));
  }

  // --- createTempTextFile tests ---

  @Test
  public void createTempTextFile_withTextAndSubject_createsFile() throws Exception {
    // Need to create the activity properly so getCacheDir() works
    ShareReceiverActivity act =
        Robolectric.buildActivity(
                ShareReceiverActivity.class, new Intent(Intent.ACTION_SEND).setType("text/plain"))
            .get();

    Method method =
        ShareReceiverActivity.class.getDeclaredMethod(
            "createTempTextFile", String.class, String.class);
    method.setAccessible(true);

    Uri result = (Uri) method.invoke(act, "Hello World", "My Subject");

    assertNotNull("Should return a URI", result);
    assertEquals("file", result.getScheme());
    assertTrue(
        "Filename should contain sanitized subject", result.getPath().contains("My_Subject"));
    assertTrue("Filename should end with .txt", result.getPath().endsWith(".txt"));

    // Verify file content
    File file = new File(result.getPath());
    assertTrue("File should exist", file.exists());
    String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    assertEquals("Hello World", content);
  }

  @Test
  public void createTempTextFile_withoutSubject_usesDefaultName() throws Exception {
    ShareReceiverActivity act =
        Robolectric.buildActivity(
                ShareReceiverActivity.class, new Intent(Intent.ACTION_SEND).setType("text/plain"))
            .get();

    Method method =
        ShareReceiverActivity.class.getDeclaredMethod(
            "createTempTextFile", String.class, String.class);
    method.setAccessible(true);

    Uri result = (Uri) method.invoke(act, "Some text", null);

    assertNotNull(result);
    assertTrue("Filename should contain 'shared_text'", result.getPath().contains("shared_text"));
  }

  @Test
  public void createTempTextFile_withEmptySubject_usesDefaultName() throws Exception {
    ShareReceiverActivity act =
        Robolectric.buildActivity(
                ShareReceiverActivity.class, new Intent(Intent.ACTION_SEND).setType("text/plain"))
            .get();

    Method method =
        ShareReceiverActivity.class.getDeclaredMethod(
            "createTempTextFile", String.class, String.class);
    method.setAccessible(true);

    Uri result = (Uri) method.invoke(act, "Some text", "");

    assertNotNull(result);
    assertTrue("Filename should contain 'shared_text'", result.getPath().contains("shared_text"));
  }

  @Test
  public void createTempTextFile_sanitizesSpecialCharacters() throws Exception {
    ShareReceiverActivity act =
        Robolectric.buildActivity(
                ShareReceiverActivity.class, new Intent(Intent.ACTION_SEND).setType("text/plain"))
            .get();

    Method method =
        ShareReceiverActivity.class.getDeclaredMethod(
            "createTempTextFile", String.class, String.class);
    method.setAccessible(true);

    Uri result = (Uri) method.invoke(act, "text", "Mein Rezept (lecker!)");

    assertNotNull(result);
    String fileName = new File(result.getPath()).getName();
    // Special characters should be replaced with underscores
    assertFalse("Should not contain spaces", fileName.contains(" "));
    assertFalse("Should not contain parentheses", fileName.contains("("));
    assertFalse("Should not contain exclamation mark", fileName.contains("!"));
    assertTrue("Should contain sanitized name", fileName.startsWith("Mein_Rezept_"));
  }

  @Test
  public void createTempTextFile_preservesUTF8Content() throws Exception {
    ShareReceiverActivity act =
        Robolectric.buildActivity(
                ShareReceiverActivity.class, new Intent(Intent.ACTION_SEND).setType("text/plain"))
            .get();

    Method method =
        ShareReceiverActivity.class.getDeclaredMethod(
            "createTempTextFile", String.class, String.class);
    method.setAccessible(true);

    String unicodeText = "Ünïcödé Tëxt mit Ümläuten: äöüß 日本語";
    Uri result = (Uri) method.invoke(act, unicodeText, "test");

    assertNotNull(result);
    File file = new File(result.getPath());
    String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    assertEquals(unicodeText, content);
  }

  @Test
  public void createTempTextFile_createsFileInSharedTextCacheDir() throws Exception {
    ShareReceiverActivity act =
        Robolectric.buildActivity(
                ShareReceiverActivity.class, new Intent(Intent.ACTION_SEND).setType("text/plain"))
            .get();

    Method method =
        ShareReceiverActivity.class.getDeclaredMethod(
            "createTempTextFile", String.class, String.class);
    method.setAccessible(true);

    Uri result = (Uri) method.invoke(act, "text", "test");

    assertNotNull(result);
    File file = new File(result.getPath());
    assertEquals("shared_text", file.getParentFile().getName());
  }

  @Test
  public void createTempTextFile_filenameContainsTimestamp() throws Exception {
    ShareReceiverActivity act =
        Robolectric.buildActivity(
                ShareReceiverActivity.class, new Intent(Intent.ACTION_SEND).setType("text/plain"))
            .get();

    Method method =
        ShareReceiverActivity.class.getDeclaredMethod(
            "createTempTextFile", String.class, String.class);
    method.setAccessible(true);

    Uri result = (Uri) method.invoke(act, "text", "test");

    assertNotNull(result);
    String fileName = new File(result.getPath()).getName();
    // Should match pattern: test_YYYYMMDD_HHmmss.txt
    assertTrue(
        "Filename should match timestamp pattern", fileName.matches("test_\\d{8}_\\d{6}\\.txt"));
  }
}
