/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.util;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Tests for the TimestampUtils class. */
public class TimestampUtilsTest {

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  @Before
  public void setUp() {
    LogUtils.init(true);
  }

  @Test
  public void testSetLastModified_success() throws IOException {
    File file = tempFolder.newFile("test.txt");
    long timestamp = 1743152400000L; // 2025-03-28 09:00:00 UTC

    boolean result = TimestampUtils.setLastModified(file, timestamp);

    assertTrue("setLastModified should return true", result);
    assertEquals("File timestamp should match", timestamp, file.lastModified());
  }

  @Test
  public void testSetLastModified_invalidTimestamp_zero() throws IOException {
    File file = tempFolder.newFile("test.txt");

    boolean result = TimestampUtils.setLastModified(file, 0);

    assertFalse("setLastModified should return false for zero timestamp", result);
  }

  @Test
  public void testSetLastModified_invalidTimestamp_negative() throws IOException {
    File file = tempFolder.newFile("test.txt");

    boolean result = TimestampUtils.setLastModified(file, -1);

    assertFalse("setLastModified should return false for negative timestamp", result);
  }

  @Test
  public void testFormatTimestamp() {
    // 2025-03-28 09:00:00 UTC - exact output depends on timezone, just check non-null
    String formatted = TimestampUtils.formatTimestamp(1743152400000L);

    assertNotNull("Formatted timestamp should not be null", formatted);
    assertFalse("Formatted timestamp should not be empty", formatted.isEmpty());
    assertTrue("Formatted timestamp should contain 2025", formatted.contains("2025"));
  }

  @Test
  public void testSetLastModified_preservesTimestamp() throws IOException {
    File file = tempFolder.newFile("photo.jpg");
    // Simulate a remote timestamp from the past
    long remoteTimestamp = 1700000000000L; // 2023-11-14

    boolean result = TimestampUtils.setLastModified(file, remoteTimestamp);

    assertTrue("setLastModified should succeed", result);
    assertEquals(
        "File should have the remote timestamp", remoteTimestamp, file.lastModified());
  }
}
