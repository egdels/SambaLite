/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.data.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Tests the idle rules of the SMB session cache: a share with a running operation is never closed
 * by the idle cleanup, and a retried download resumes its partial file instead of starting over.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SmbRepositorySessionCacheTest {

  private static final long TIMEOUT = 30_000;

  private static SmbRepositoryImpl.CachedShare cachedShare(long lastAccess) {
    SmbRepositoryImpl.CachedShare cached = new SmbRepositoryImpl.CachedShare(null, null, null);
    cached.lastAccess = lastAccess;
    return cached;
  }

  // ── isIdle ─────────────────────────────────────────────────────────────────

  @Test
  public void isIdle_trueOnlyAfterTimeoutWithoutActiveOperation() {
    long now = 1_000_000;
    assertTrue(SmbRepositoryImpl.isIdle(cachedShare(now - TIMEOUT - 1), now, TIMEOUT));
    assertFalse(SmbRepositoryImpl.isIdle(cachedShare(now - TIMEOUT), now, TIMEOUT));
    assertFalse(SmbRepositoryImpl.isIdle(cachedShare(now - 1_000), now, TIMEOUT));
    assertFalse(SmbRepositoryImpl.isIdle(null, now, TIMEOUT));
  }

  @Test
  public void isIdle_falseWhileAnOperationRunsHoweverOldTheLastAccess() {
    long now = 1_000_000;
    SmbRepositoryImpl.CachedShare busy = cachedShare(now - 10 * TIMEOUT);
    busy.activeOperations.incrementAndGet();

    assertFalse(SmbRepositoryImpl.isIdle(busy, now, TIMEOUT));

    busy.activeOperations.decrementAndGet();
    assertTrue(SmbRepositoryImpl.isIdle(busy, now, TIMEOUT));
  }

  @Test
  public void isIdle_nestedOperationsKeepShareBusyUntilTheLastOneEnds() {
    long now = 1_000_000;
    SmbRepositoryImpl.CachedShare busy = cachedShare(now - 10 * TIMEOUT);
    busy.activeOperations.incrementAndGet();
    busy.activeOperations.incrementAndGet();
    busy.activeOperations.decrementAndGet();

    assertFalse(SmbRepositoryImpl.isIdle(busy, now, TIMEOUT));
  }

  // ── resumeOffset ───────────────────────────────────────────────────────────

  @Test
  public void resumeOffset_resumesPartialFileOnRetryOnly() {
    assertEquals(0, SmbRepositoryImpl.resumeOffset(false, 4_000, 10_000));
    assertEquals(4_000, SmbRepositoryImpl.resumeOffset(true, 4_000, 10_000));
    assertEquals(10_000, SmbRepositoryImpl.resumeOffset(true, 10_000, 10_000));
  }

  @Test
  public void resumeOffset_startsOverForEmptyOrOversizedLocalFile() {
    assertEquals(0, SmbRepositoryImpl.resumeOffset(true, 0, 10_000));
    assertEquals(0, SmbRepositoryImpl.resumeOffset(true, 10_001, 10_000));
    assertEquals(0, SmbRepositoryImpl.resumeOffset(true, 5_000, 0));
  }
}
