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

import org.junit.Test;

/** The error snackbar must not show "null" when only a title is given (sync checks). */
public class ProgressErrorTextTest {

  @Test
  public void titleOnly_whenMessageIsNull() {
    assertEquals(
        "Please select a local folder first",
        ProgressController.errorText("Please select a local folder first", null));
  }

  @Test
  public void titleOnly_whenMessageIsBlank() {
    assertEquals("Delete failed", ProgressController.errorText("Delete failed", "  "));
  }

  @Test
  public void titleAndMessage_whenBothGiven() {
    assertEquals(
        "Delete failed: Access denied",
        ProgressController.errorText("Delete failed", "Access denied"));
  }
}
