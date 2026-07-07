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

/**
 * Unit tests for the static helpers in {@link MainActivity}. Covers the normalization of the
 * optional default folder inside a share (Issue #27 follow-up: open a configured subfolder such as
 * {@code AA/AA/AA/AA} directly after connecting).
 */
public class MainActivityTest {

  // --- normalizeInitialPath ---

  @Test
  public void normalizeInitialPath_null_returnsEmpty() {
    assertEquals("", MainActivity.normalizeInitialPath(null));
  }

  @Test
  public void normalizeInitialPath_empty_returnsEmpty() {
    assertEquals("", MainActivity.normalizeInitialPath(""));
    assertEquals("", MainActivity.normalizeInitialPath("   "));
  }

  @Test
  public void normalizeInitialPath_singleSlash_returnsEmpty() {
    assertEquals("", MainActivity.normalizeInitialPath("/"));
  }

  @Test
  public void normalizeInitialPath_stripsLeadingAndTrailingSlashes() {
    assertEquals("docs", MainActivity.normalizeInitialPath("/docs"));
    assertEquals("docs", MainActivity.normalizeInitialPath("docs/"));
    assertEquals("docs/sub", MainActivity.normalizeInitialPath("/docs/sub/"));
  }

  @Test
  public void normalizeInitialPath_convertsBackslashes() {
    assertEquals("docs/sub", MainActivity.normalizeInitialPath("docs\\sub"));
    assertEquals("docs/sub", MainActivity.normalizeInitialPath("\\docs\\sub\\"));
  }

  @Test
  public void normalizeInitialPath_collapsesDuplicateSlashes() {
    assertEquals("docs/sub", MainActivity.normalizeInitialPath("docs//sub"));
    assertEquals("docs/sub", MainActivity.normalizeInitialPath("//docs///sub//"));
  }

  @Test
  public void normalizeInitialPath_trimsWhitespace() {
    assertEquals("docs/sub", MainActivity.normalizeInitialPath("  docs/sub  "));
  }

  @Test
  public void normalizeInitialPath_multiLevelPath_isPreserved() {
    // The exact request from Issue #27: default subfolder "AA/AA/AA/AA" inside the share.
    assertEquals("AA/AA/AA/AA", MainActivity.normalizeInitialPath("AA/AA/AA/AA"));
    assertEquals("AA/AA/AA/AA", MainActivity.normalizeInitialPath("/AA/AA/AA/AA/"));
  }
}
