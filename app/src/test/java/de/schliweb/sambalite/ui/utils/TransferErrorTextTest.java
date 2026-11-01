/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import de.schliweb.sambalite.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests the readable reasons shown in the transfer queue for failed transfers. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TransferErrorTextTest {

  @Test
  public void smbStatusCodes_mapToReasons() {
    assertEquals(
        R.string.transfer_error_access_denied,
        TransferErrorText.reasonRes(
            "STATUS_ACCESS_DENIED (0xc0000022): Create failed for \\\\10.0.2.2\\Data\\a.jpg"));
    assertEquals(
        R.string.transfer_error_not_found,
        TransferErrorText.reasonRes("STATUS_OBJECT_PATH_NOT_FOUND (0xc000003a): Create failed"));
    assertEquals(
        R.string.transfer_error_disk_full,
        TransferErrorText.reasonRes("STATUS_DISK_FULL (0xc000007f): Write failed"));
    assertEquals(
        R.string.transfer_error_in_use,
        TransferErrorText.reasonRes("STATUS_SHARING_VIOLATION (0xc0000043): Create failed"));
    assertEquals(
        R.string.transfer_error_login,
        TransferErrorText.reasonRes("STATUS_LOGON_FAILURE (0xc000006d): Authentication failed"));
  }

  @Test
  public void connectionProblems_mapToConnectionReason() {
    assertEquals(
        R.string.transfer_error_connection,
        TransferErrorText.reasonRes("Connection error: Connection refused"));
    assertEquals(
        R.string.transfer_error_connection,
        TransferErrorText.reasonRes("java.net.SocketTimeoutException: timeout"));
    assertEquals(
        R.string.transfer_error_connection,
        TransferErrorText.reasonRes("Connection not found: 3f2a-uuid"));
  }

  @Test
  public void unknownTechnicalErrors_mapToGenericServerReason() {
    assertEquals(
        R.string.transfer_error_server,
        TransferErrorText.reasonRes("STATUS_NOT_SUPPORTED (0xc00000bb): Create failed for x"));
    assertEquals(
        R.string.transfer_error_server,
        TransferErrorText.reasonRes("java.lang.IllegalStateException: boom"));
  }

  @Test
  public void readableMessages_areKeptAsTheyAre() {
    Context context = ApplicationProvider.getApplicationContext();
    String readable = context.getString(R.string.transfer_error_source_access_lost);
    assertEquals(0, TransferErrorText.reasonRes(readable));
    assertEquals(readable, TransferErrorText.shortReason(context, readable));
  }

  @Test
  public void noError_givesNoReason() {
    Context context = ApplicationProvider.getApplicationContext();
    assertNull(TransferErrorText.shortReason(context, null));
    assertNull(TransferErrorText.shortReason(context, "  "));
  }

  @Test
  public void reasonFor_usesCauseOfWrappedOperationError() {
    Context context = ApplicationProvider.getApplicationContext();
    Exception exists =
        new java.io.IOException(
            "Failed to execute share operation after 1 attempts",
            new java.io.IOException("Target path already exists: Docs/readme.txt"));
    assertEquals(
        "A file or folder with this name already exists",
        TransferErrorText.reasonFor(context, exists));

    Exception denied =
        new java.io.IOException(
            "Failed to execute share operation after 1 attempts",
            new RuntimeException("STATUS_ACCESS_DENIED (0xc0000022): Create failed"));
    assertEquals("No permission on the server", TransferErrorText.reasonFor(context, denied));
  }

  @Test
  public void reasonFor_unmappedError_showsInnermostMessage() {
    Context context = ApplicationProvider.getApplicationContext();
    Exception wrapped =
        new java.io.IOException(
            "Failed to execute share operation after 1 attempts",
            new IllegalStateException("Name must not be empty"));
    assertEquals("Name must not be empty", TransferErrorText.reasonFor(context, wrapped));
  }
}
