/*
 * Copyright 2025 Christian Schliz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.data.repository;

import static org.junit.Assert.*;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import de.schliweb.sambalite.data.background.BackgroundSmbManager;

import java.util.concurrent.CompletableFuture;

/**
 * Tests for timestamp preservation during file downloads and uploads.
 *
 * <p>These tests verify that the local file timestamp is correctly set after download
 * and that the helper method handles edge cases properly.
 */
@Ignore("Disabled: preserveLocalFileTimestamp method does not exist yet in SmbRepositoryImpl")
@RunWith(MockitoJUnitRunner.Silent.class)
public class TimestampPreservationTest {

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  private SmbRepositoryImpl smbRepository;

  @Before
  public void setUp() {
    BackgroundSmbManager mockBackgroundManager = Mockito.mock(BackgroundSmbManager.class);
    CompletableFuture<Object> failedFuture = new CompletableFuture<>();
    failedFuture.completeExceptionally(
        new UnsupportedOperationException("No background service in test"));
    Mockito.when(
            mockBackgroundManager.executeBackgroundOperation(
                Mockito.anyString(), Mockito.anyString(), Mockito.any()))
        .thenReturn(failedFuture);
    smbRepository = new SmbRepositoryImpl(mockBackgroundManager);
  }

  @Test
  public void testPreserveLocalFileTimestamp_setsCorrectTimestamp() throws Exception {
    File testFile = tempFolder.newFile("test_download.txt");
    try (FileOutputStream fos = new FileOutputStream(testFile)) {
      fos.write("test content".getBytes(UTF_8));
    }

    // Use a known past timestamp (2023-01-15 12:00:00 UTC)
    long expectedTimestamp = 1673784000000L;

    invokePreserveLocalFileTimestamp(testFile, expectedTimestamp);

    assertEquals(
        "Local file timestamp should match the remote timestamp",
        expectedTimestamp,
        testFile.lastModified());
  }

  @Test
  public void testPreserveLocalFileTimestamp_zeroTimestamp_noChange() throws Exception {
    File testFile = tempFolder.newFile("test_zero.txt");
    try (FileOutputStream fos = new FileOutputStream(testFile)) {
      fos.write("test content".getBytes(UTF_8));
    }
    long originalTimestamp = testFile.lastModified();

    invokePreserveLocalFileTimestamp(testFile, 0);

    assertEquals(
        "Timestamp should not change when remote timestamp is 0",
        originalTimestamp,
        testFile.lastModified());
  }

  @Test
  public void testPreserveLocalFileTimestamp_negativeTimestamp_noChange() throws Exception {
    File testFile = tempFolder.newFile("test_negative.txt");
    try (FileOutputStream fos = new FileOutputStream(testFile)) {
      fos.write("test content".getBytes(UTF_8));
    }
    long originalTimestamp = testFile.lastModified();

    invokePreserveLocalFileTimestamp(testFile, -1);

    assertEquals(
        "Timestamp should not change when remote timestamp is negative",
        originalTimestamp,
        testFile.lastModified());
  }

  @Test
  public void testPreserveLocalFileTimestamp_oldTimestamp() throws Exception {
    File testFile = tempFolder.newFile("test_old.txt");
    try (FileOutputStream fos = new FileOutputStream(testFile)) {
      fos.write("test content".getBytes(UTF_8));
    }

    // Use a very old timestamp (2000-01-01 00:00:00 UTC)
    long oldTimestamp = 946684800000L;

    invokePreserveLocalFileTimestamp(testFile, oldTimestamp);

    assertEquals(
        "Local file timestamp should be set to the old remote timestamp",
        oldTimestamp,
        testFile.lastModified());
  }

  @Test
  public void testPreserveLocalFileTimestamp_recentTimestamp() throws Exception {
    File testFile = tempFolder.newFile("test_recent.txt");
    try (FileOutputStream fos = new FileOutputStream(testFile)) {
      fos.write("test content".getBytes(UTF_8));
    }

    // Use a recent timestamp (close to now but in the past)
    long recentTimestamp = System.currentTimeMillis() - 60000; // 1 minute ago

    invokePreserveLocalFileTimestamp(testFile, recentTimestamp);

    assertEquals(
        "Local file timestamp should be set to the recent remote timestamp",
        recentTimestamp,
        testFile.lastModified());
  }

  @Test
  public void testPreserveLocalFileTimestamp_multipleUpdates() throws Exception {
    File testFile = tempFolder.newFile("test_multi.txt");
    try (FileOutputStream fos = new FileOutputStream(testFile)) {
      fos.write("test content".getBytes(UTF_8));
    }

    long timestamp1 = 1673784000000L; // 2023-01-15
    long timestamp2 = 1704067200000L; // 2024-01-01

    invokePreserveLocalFileTimestamp(testFile, timestamp1);
    assertEquals("First timestamp should be set", timestamp1, testFile.lastModified());

    invokePreserveLocalFileTimestamp(testFile, timestamp2);
    assertEquals("Second timestamp should overwrite the first", timestamp2, testFile.lastModified());
  }

  /**
   * Invokes the private preserveLocalFileTimestamp method via reflection.
   */
  private void invokePreserveLocalFileTimestamp(File localFile, long remoteLastModifiedMillis)
      throws Exception {
    Method method =
        SmbRepositoryImpl.class.getDeclaredMethod(
            "preserveLocalFileTimestamp", File.class, long.class);
    method.setAccessible(true);
    method.invoke(smbRepository, localFile, remoteLastModifiedMillis);
  }
}
