/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.ui.utils.TransferErrorText;
import java.io.IOException;
import java.net.ConnectException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The connection test used to show "Connection failed: Failed to execute share operation after 1
 * attempts" for a wrong password, because the reason is only in the cause of the repository's
 * exception. These tests check that the cause chain is kept so the reason can be shown.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ConnectionTestMessageTest {

  private static final String LOGON_FAILURE =
      "STATUS_LOGON_FAILURE (0xc000006d): Authentication failed for 'smbuser' using"
          + " com.hierynomus.smbj.auth.NtlmSealer@b0bdecd";

  @Test
  public void describeFailure_includesCauses() {
    Exception e =
        new IOException(
            "Failed to execute share operation after 1 attempts",
            new RuntimeException(LOGON_FAILURE));
    assertEquals(
        "Failed to execute share operation after 1 attempts: " + LOGON_FAILURE,
        MainViewModel.describeFailure(e));
  }

  @Test
  public void describeFailure_skipsRepeatedMessagesAndUsesClassNameWithoutMessage() {
    RuntimeException inner = new RuntimeException(LOGON_FAILURE);
    Exception e = new IOException(LOGON_FAILURE, new IllegalStateException(inner));
    // IllegalStateException(Throwable) takes the cause's toString as message
    assertEquals(
        LOGON_FAILURE + ": java.lang.RuntimeException: " + LOGON_FAILURE,
        MainViewModel.describeFailure(e));
    assertEquals(
        "ConnectException", MainViewModel.describeFailure(new ConnectException(null)));
  }

  @Test
  public void wrongPassword_mapsToLoginReason() {
    Context context = ApplicationProvider.getApplicationContext();
    Exception e =
        new IOException(
            "Failed to execute share operation after 1 attempts",
            new RuntimeException(LOGON_FAILURE));
    assertEquals(
        context.getString(R.string.transfer_error_login),
        TransferErrorText.shortReason(context, MainViewModel.describeFailure(e)));
  }

  @Test
  public void serverDown_mapsToConnectionReason() {
    Context context = ApplicationProvider.getApplicationContext();
    Exception e =
        new IOException(
            "Failed to execute share operation after 3 attempts",
            new ConnectException("Connection refused"));
    assertEquals(
        context.getString(R.string.transfer_error_connection),
        TransferErrorText.shortReason(context, MainViewModel.describeFailure(e)));
  }
}
