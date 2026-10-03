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

import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb2.SMB2MessageCommandCode;
import com.hierynomus.mssmb2.SMBApiException;
import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.mockito.Mockito;
import org.robolectric.annotation.Config;

/**
 * Tests the idle rules of the SMB session cache: a share with a running operation is never closed
 * by the idle cleanup, and a retried download resumes its partial file instead of starting over.
 * A failed operation only costs the session when the error concerns the session, and a session
 * dropped after a failure stays open for the operations still running on it.
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

  // ── leavesSessionUsable ────────────────────────────────────────────────────

  private static SMBApiException smbError(NtStatus status) {
    return new SMBApiException(status.getValue(), SMB2MessageCommandCode.SMB2_CREATE, null);
  }

  @Test
  public void leavesSessionUsable_trueWhenTheServerRejectsTheRequestItself() {
    assertTrue(SmbRepositoryImpl.leavesSessionUsable(smbError(NtStatus.STATUS_ACCESS_DENIED)));
    assertTrue(
        SmbRepositoryImpl.leavesSessionUsable(smbError(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND)));
    assertTrue(SmbRepositoryImpl.leavesSessionUsable(smbError(NtStatus.STATUS_SHARING_VIOLATION)));
  }

  @Test
  public void leavesSessionUsable_findsTheServerAnswerBehindWrappingExceptions() {
    Exception wrapped =
        new IOException("outer", new RuntimeException(smbError(NtStatus.STATUS_ACCESS_DENIED)));
    assertTrue(SmbRepositoryImpl.leavesSessionUsable(wrapped));
  }

  @Test
  public void leavesSessionUsable_falseForSessionAndTransportErrors() {
    assertFalse(
        SmbRepositoryImpl.leavesSessionUsable(smbError(NtStatus.STATUS_NETWORK_SESSION_EXPIRED)));
    assertFalse(
        SmbRepositoryImpl.leavesSessionUsable(smbError(NtStatus.STATUS_USER_SESSION_DELETED)));
    assertFalse(
        SmbRepositoryImpl.leavesSessionUsable(smbError(NtStatus.STATUS_CONNECTION_DISCONNECTED)));
    assertFalse(SmbRepositoryImpl.leavesSessionUsable(new IOException("Broken pipe")));
    assertFalse(
        SmbRepositoryImpl.leavesSessionUsable(
            new IllegalStateException("DiskShare has already been closed")));
    assertFalse(SmbRepositoryImpl.leavesSessionUsable(null));
  }

  // ── retireCachedShare ──────────────────────────────────────────────────────

  private static SmbRepositoryImpl repository() {
    return new SmbRepositoryImpl(Mockito.mock(BackgroundSmbManager.class));
  }

  @Test
  public void retire_closesAnUnusedShareAtOnce() {
    SmbRepositoryImpl.CachedShare cached = cachedShare(0);

    repository().retireCachedShare("c", cached);

    assertTrue(cached.retired);
    assertTrue(cached.closed.get());
  }

  @Test
  public void retire_leavesABusyShareOpenUntilItsLastOperationEnds() {
    SmbRepositoryImpl repository = repository();
    SmbRepositoryImpl.CachedShare cached = cachedShare(0);
    cached.activeOperations.incrementAndGet();
    cached.activeOperations.incrementAndGet();

    repository.retireCachedShare("c", cached);
    assertFalse(cached.closed.get());

    repository.releaseCachedShare(cached);
    assertFalse(cached.closed.get());

    repository.releaseCachedShare(cached);
    assertTrue(cached.closed.get());
  }

  @Test
  public void release_keepsAShareThatWasNotRetired() {
    SmbRepositoryImpl.CachedShare cached = cachedShare(0);
    cached.activeOperations.incrementAndGet();

    repository().releaseCachedShare(cached);

    assertFalse(cached.closed.get());
    assertTrue(cached.lastAccess > 0);
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
