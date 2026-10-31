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
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** Tests for the path splitting behind the breadcrumb row of the file browser. */
public class BreadcrumbViewTest {

  @Test
  public void splitPath_shareRootOnly() {
    assertEquals(List.of("Data"), BreadcrumbView.splitPath("Data"));
  }

  @Test
  public void splitPath_nestedFolders() {
    assertEquals(
        List.of("Data", "Photos", "2026"), BreadcrumbView.splitPath("Data/Photos/2026"));
  }

  @Test
  public void splitPath_ignoresEmptySegments() {
    assertEquals(List.of("Data", "Photos"), BreadcrumbView.splitPath("/Data//Photos/"));
  }

  @Test
  public void splitPath_nullAndEmpty() {
    assertTrue(BreadcrumbView.splitPath(null).isEmpty());
    assertTrue(BreadcrumbView.splitPath("").isEmpty());
  }
}
