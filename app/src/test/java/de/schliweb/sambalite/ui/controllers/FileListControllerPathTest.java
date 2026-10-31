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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Tests for the path helpers that decide where a folder listing starts after going up. */
public class FileListControllerPathTest {

  @Test
  public void isAncestorPath_parentAndDeeperAncestors() {
    assertTrue(FileListController.isAncestorPath("Data", "Data/Photos"));
    assertTrue(FileListController.isAncestorPath("Data", "Data/Photos/2026"));
    assertTrue(FileListController.isAncestorPath("Data/Photos", "Data/Photos/2026/Oktober"));
  }

  @Test
  public void isAncestorPath_sameOrSiblingOrDescendant() {
    assertFalse(FileListController.isAncestorPath("Data/Photos", "Data/Photos"));
    assertFalse(FileListController.isAncestorPath("Data/Photos", "Data/Documents"));
    assertFalse(FileListController.isAncestorPath("Data/Photos/2026", "Data/Photos"));
  }

  @Test
  public void isAncestorPath_nameThatOnlySharesAPrefix() {
    // "Data/Photo" is not a parent of "Data/Photos/2026"
    assertFalse(FileListController.isAncestorPath("Data/Photo", "Data/Photos/2026"));
  }

  @Test
  public void childBelow_directChild() {
    assertEquals("Photos", FileListController.childBelow("Data", "Data/Photos"));
  }

  @Test
  public void childBelow_returnsFirstSegmentOnTheWayDown() {
    // Jumping from Data/Photos/2026/Oktober to Data via the breadcrumb shows Photos
    assertEquals("Photos", FileListController.childBelow("Data", "Data/Photos/2026/Oktober"));
    assertEquals("2026", FileListController.childBelow("Data/Photos", "Data/Photos/2026/Oktober"));
  }
}
