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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.text.format.Formatter;
import androidx.test.core.app.ApplicationProvider;
import de.schliweb.sambalite.transfer.db.PendingTransfer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The detail line of a running transfer shows its speed, or "stalled" (issue #7). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = de.schliweb.sambalite.SambaLiteApp.class)
public class TransferItemSpeedTest {

  private final Context context = ApplicationProvider.getApplicationContext();

  private static PendingTransfer transfer(String status) {
    PendingTransfer t = new PendingTransfer();
    t.status = status;
    t.fileSize = 400_000_000L;
    t.bytesTransferred = 120_000_000L;
    t.remotePath = "Music/big.bin";
    return t;
  }

  private String speed(long bytesPerSecond) {
    return Formatter.formatFileSize(context, bytesPerSecond).replace(' ', '\u00A0') + "/s";
  }

  @Test
  public void runningTransfer_showsSpeedBeforeTheFolder() {
    String line =
        TransferItemAdapter.ViewHolder.detailLine(
            context, transfer("ACTIVE"), "Transferring", 11_200_000L);
    String progress =
        Formatter.formatFileSize(context, 120_000_000L)
            + " / "
            + Formatter.formatFileSize(context, 400_000_000L);
    assertEquals("Transferring · " + progress + " · " + speed(11_200_000L) + " · Music", line);
  }

  @Test
  public void speed_doesNotBreakBetweenNumberAndUnit() {
    String text = TransferItemAdapter.ViewHolder.speedText(context, 11_200_000L);
    assertFalse(text, text.contains(" "));
  }

  @Test
  public void stalledTransfer_saysStalled() {
    String line =
        TransferItemAdapter.ViewHolder.detailLine(
            context, transfer("ACTIVE"), "Transferring", TransferRateTracker.STALLED);
    assertTrue(line, line.contains(" · stalled · Music"));
  }

  @Test
  public void unknownSpeed_isLeftOut() {
    String line =
        TransferItemAdapter.ViewHolder.detailLine(
            context, transfer("ACTIVE"), "Transferring", TransferRateTracker.UNKNOWN);
    assertFalse(line, line.contains("/s"));
    assertFalse(line, line.contains("stalled"));
  }

  @Test
  public void waitingTransfer_showsNoSpeed() {
    String line =
        TransferItemAdapter.ViewHolder.detailLine(
            context, transfer("PENDING"), "Waiting", 11_200_000L);
    assertFalse(line, line.contains("/s"));
  }

  @Test
  public void runningTransferOfUnknownSize_stillShowsSpeed() {
    PendingTransfer t = transfer("ACTIVE");
    t.fileSize = 0;
    String line = TransferItemAdapter.ViewHolder.detailLine(context, t, "Transferring", 500_000L);
    assertEquals("Transferring · " + speed(500_000L) + " · Music", line);
  }
}
