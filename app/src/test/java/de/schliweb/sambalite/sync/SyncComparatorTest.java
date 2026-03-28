/*
 * Copyright (C) 2024-2025 Christian Nagel - All Rights Reserved
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package de.schliweb.sambalite.sync;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

public class SyncComparatorTest {

  private SyncComparator comparator;

  @Before
  public void setUp() {
    comparator = new SyncComparator();
  }

  @Test
  public void isSame_sameSize_sameTimestamp_returnsTrue() {
    assertTrue(comparator.isSame(1024, 1000000, 1024, 1000000));
  }

  @Test
  public void isSame_sameSize_timestampWithinTolerance_returnsTrue() {
    // 2 seconds difference, within 3s tolerance
    assertTrue(comparator.isSame(1024, 1000000, 1024, 1002000));
  }

  @Test
  public void isSame_sameSize_timestampExactlyAtTolerance_returnsTrue() {
    // Exactly 3 seconds = within tolerance (<=)
    assertTrue(comparator.isSame(1024, 1000000, 1024, 1003000));
  }

  @Test
  public void isSame_sameSize_timestampBeyondTolerance_returnsFalse() {
    // 4 seconds difference, beyond 3s tolerance
    assertFalse(comparator.isSame(1024, 1000000, 1024, 1004000));
  }

  @Test
  public void isSame_differentSize_returnsFalse() {
    assertFalse(comparator.isSame(1024, 1000000, 2048, 1000000));
  }

  @Test
  public void isSame_differentSize_sameTimestamp_returnsFalse() {
    assertFalse(comparator.isSame(100, 1000000, 200, 1000000));
  }

  @Test
  public void isRemoteNewer_remoteSignificantlyNewer_returnsTrue() {
    assertTrue(comparator.isRemoteNewer(1000000, 1005000));
  }

  @Test
  public void isRemoteNewer_withinTolerance_returnsFalse() {
    assertFalse(comparator.isRemoteNewer(1000000, 1002000));
  }

  @Test
  public void isRemoteNewer_localNewer_returnsFalse() {
    assertFalse(comparator.isRemoteNewer(1005000, 1000000));
  }

  @Test
  public void isLocalNewer_localSignificantlyNewer_returnsTrue() {
    assertTrue(comparator.isLocalNewer(1005000, 1000000));
  }

  @Test
  public void isLocalNewer_withinTolerance_returnsFalse() {
    assertFalse(comparator.isLocalNewer(1002000, 1000000));
  }

  @Test
  public void isLocalNewer_remoteNewer_returnsFalse() {
    assertFalse(comparator.isLocalNewer(1000000, 1005000));
  }

  @Test
  public void customTolerance_respected() {
    SyncComparator strict = new SyncComparator(1000); // 1 second
    assertFalse(strict.isSame(1024, 1000000, 1024, 1002000)); // 2s diff > 1s tolerance
    assertTrue(strict.isSame(1024, 1000000, 1024, 1000500));  // 0.5s diff < 1s tolerance
  }

  @Test
  public void defaultTolerance_is3Seconds() {
    assertEquals(3000, SyncComparator.DEFAULT_TIMESTAMP_TOLERANCE_MS);
  }
}
