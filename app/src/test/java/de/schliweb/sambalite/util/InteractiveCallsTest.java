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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.junit.Test;

/** Tests the time limit for SMB requests the user is waiting for. */
public class InteractiveCallsTest {

  @Test
  public void returnsTheResultOfAFastCall() throws Exception {
    assertEquals("ok", InteractiveCalls.withTimeout(() -> "ok", 1000));
  }

  @Test
  public void rethrowsTheCallsOwnException() throws Exception {
    IOException failure = new IOException("STATUS_ACCESS_DENIED");
    try {
      InteractiveCalls.withTimeout(
          () -> {
            throw failure;
          },
          1000);
      fail("expected the call's exception");
    } catch (IOException e) {
      assertSame(failure, e);
    }
  }

  @Test
  public void stopsWaitingForAHangingServer() throws Exception {
    long start = System.currentTimeMillis();
    try {
      InteractiveCalls.withTimeout(
          () -> {
            Thread.sleep(5000);
            return "late";
          },
          200);
      fail("expected a timeout");
    } catch (TimeoutException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("timed out"));
      assertTrue(System.currentTimeMillis() - start < 3000);
    }
  }
}
