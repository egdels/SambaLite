/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.transfer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Tests when the transfer progress is saved: every 2 MiB, or at least once per second. */
public class TransferWorkerProgressSaveTest {

  private static final long MIB = 1024 * 1024;

  @Test
  public void savesAfterTwoMebibytes_evenWithinASecond() {
    assertTrue(TransferWorker.isProgressSaveDue(2 * MIB, 10));
  }

  @Test
  public void savesAfterOneSecond_onSlowConnections() {
    assertTrue(TransferWorker.isProgressSaveDue(64 * 1024, 1000));
  }

  @Test
  public void doesNotSaveTooOften() {
    assertFalse(TransferWorker.isProgressSaveDue(MIB, 500));
  }

  @Test
  public void doesNotSaveWithoutNewBytes() {
    assertFalse(TransferWorker.isProgressSaveDue(0, 5000));
  }
}
