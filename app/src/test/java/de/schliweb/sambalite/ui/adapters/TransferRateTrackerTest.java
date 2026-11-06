/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui.adapters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.junit.Test;

/** Speed of running transfers from their saved progress (issue #7). */
public class TransferRateTrackerTest {

  private static final long MB = 1_000_000L;

  @Test
  public void firstSave_hasNoSpeedYet() {
    TransferRateTracker t = new TransferRateTracker();
    t.record(1, 5 * MB, 10_000);
    assertEquals(TransferRateTracker.UNKNOWN, t.bytesPerSecond(1, 10_500));
  }

  @Test
  public void unknownTransfer_hasNoSpeed() {
    assertEquals(TransferRateTracker.UNKNOWN, new TransferRateTracker().bytesPerSecond(7, 0));
  }

  @Test
  public void twoSaves_giveBytesPerSecond() {
    TransferRateTracker t = new TransferRateTracker();
    t.record(1, 0, 10_000);
    t.record(1, 4 * MB, 12_000);
    assertEquals(2 * MB, t.bytesPerSecond(1, 12_100));
  }

  @Test
  public void savesInBursts_giveTheRealSpeed() {
    // Saves as logged on the emulator (09:44, upload of 800 MB): several within milliseconds,
    // then none for most of a second. The server file grew by about 12 MB/s.
    long[][] saves = {
      {87_031_808L, 674_566},
      {91_226_112L, 674_580},
      {99_614_720L, 675_221},
      {103_809_024L, 675_833},
      {108_003_328L, 675_841},
      {116_391_936L, 676_454},
      {124_780_544L, 677_096},
      {128_974_848L, 677_817},
      {133_169_152L, 677_823},
      {135_266_304L, 678_505},
      {137_363_456L, 678_524},
      {141_557_760L, 678_547},
      {145_752_064L, 679_567},
      {149_946_368L, 679_573},
      {158_334_976L, 680_250},
    };
    TransferRateTracker t = new TransferRateTracker();
    for (long[] s : saves) {
      t.record(1, s[0], s[1]);
    }
    long rate = t.bytesPerSecond(1, 680_260);
    assertTrue("rate " + rate, rate > 10 * MB && rate < 15 * MB);
  }

  @Test
  public void savesCloserThanASecond_giveNoSpeedYet() {
    TransferRateTracker t = new TransferRateTracker();
    t.record(1, 0, 0);
    t.record(1, 4 * MB, 14); // a burst: 4 MB in 14 ms would read as 285 MB/s
    assertEquals(TransferRateTracker.UNKNOWN, t.bytesPerSecond(1, 20));
  }

  @Test
  public void onlyTheLastSecondsCount() {
    TransferRateTracker t = new TransferRateTracker();
    // 10 MB/s for 5 s, then 1 MB/s for 4 s
    long bytes = 0;
    for (int s = 1; s <= 5; s++) {
      t.record(1, bytes += 10 * MB, s * 1_000L);
    }
    for (int s = 6; s <= 9; s++) {
      t.record(1, bytes += 1 * MB, s * 1_000L);
    }
    assertEquals(1 * MB, t.bytesPerSecond(1, 9_000));
  }

  @Test
  public void repeatedSave_changesNothing() {
    TransferRateTracker t = new TransferRateTracker();
    t.record(1, 0, 0);
    t.record(1, 3 * MB, 1_000);
    t.record(1, 3 * MB, 1_000);
    t.record(1, 3 * MB, 1_500); // same bytes, e.g. a status update
    assertEquals(3 * MB, t.bytesPerSecond(1, 1_600));
  }

  @Test
  public void noBytesFor10Seconds_isStalled() {
    TransferRateTracker t = new TransferRateTracker();
    t.record(1, 0, 0);
    t.record(1, 3 * MB, 1_000);
    assertEquals(3 * MB, t.bytesPerSecond(1, 1_000 + TransferRateTracker.STALL_AFTER_MS - 1));
    assertEquals(
        TransferRateTracker.STALLED,
        t.bytesPerSecond(1, 1_000 + TransferRateTracker.STALL_AFTER_MS));
  }

  @Test
  public void stalledTransfer_recoversWithNewBytes() {
    TransferRateTracker t = new TransferRateTracker();
    t.record(1, 0, 0);
    t.record(1, 1 * MB, 1_000);
    assertEquals(TransferRateTracker.STALLED, t.bytesPerSecond(1, 20_000));
    t.record(1, 2 * MB, 21_000);
    assertTrue(t.bytesPerSecond(1, 21_000) > 0);
  }

  @Test
  public void transferSeenFirstLongAfterItsLastSave_isStalled() {
    // The queue opens while a transfer hangs: its last save is already old
    TransferRateTracker t = new TransferRateTracker();
    t.record(1, 5 * MB, 1_000);
    assertEquals(TransferRateTracker.STALLED, t.bytesPerSecond(1, 60_000));
  }

  @Test
  public void fewerBytes_startOver() {
    // A retry begins again from 0
    TransferRateTracker t = new TransferRateTracker();
    t.record(1, 0, 0);
    t.record(1, 8 * MB, 1_000);
    t.record(1, 0, 2_000);
    assertEquals(TransferRateTracker.UNKNOWN, t.bytesPerSecond(1, 2_100));
    t.record(1, 1 * MB, 3_000);
    assertEquals(1 * MB, t.bytesPerSecond(1, 3_000));
  }

  @Test
  public void transfersAreMeasuredSeparately() {
    TransferRateTracker t = new TransferRateTracker();
    t.record(1, 0, 0);
    t.record(2, 0, 0);
    t.record(1, 1 * MB, 1_000);
    t.record(2, 5 * MB, 1_000);
    assertEquals(1 * MB, t.bytesPerSecond(1, 1_000));
    assertEquals(5 * MB, t.bytesPerSecond(2, 1_000));
  }

  @Test
  public void retainOnly_forgetsFinishedTransfers() {
    TransferRateTracker t = new TransferRateTracker();
    t.record(1, 0, 0);
    t.record(1, 1 * MB, 1_000);
    t.record(2, 0, 0);
    t.retainOnly(Collections.singleton(2L));
    assertEquals(TransferRateTracker.UNKNOWN, t.bytesPerSecond(1, 1_000));
  }
}
