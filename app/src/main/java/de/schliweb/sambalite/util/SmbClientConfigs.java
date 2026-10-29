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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.hierynomus.mssmb2.SMB2Dialect;
import com.hierynomus.mssmb2.SMB3EncryptionCipher;
import com.hierynomus.smbj.SmbConfig;

/** App-wide SMBJ client settings shared by the repository and the background workers. */
public final class SmbClientConfigs {

  private SmbClientConfigs() {}

  /**
   * Returns an SMBJ config builder with the app-wide defaults. SMBJ 0.15 opens directories with
   * SMB3 directory leases by default; SambaLite does not use them, so they stay off as in 0.14. The
   * encryption ciphers keep the SMBJ default (AES-128 before AES-256, like Windows 11): the server
   * still gets AES-256 if it requires it, without slowing down everyone else.
   */
  @NonNull
  public static SmbConfig.Builder builder() {
    return SmbConfig.builder().withDirectoryLeasingEnabled(false);
  }

  /**
   * Describes the negotiated dialect and encryption for display, e.g. "SMB 3.1.1, AES-256-GCM" or
   * "SMB 2.1, unencrypted".
   *
   * @param dialect the negotiated SMB dialect
   * @param cipher the negotiated cipher, or null if none was negotiated
   * @param encrypted whether the session encrypts its messages
   */
  @NonNull
  public static String describeSecurity(
      @NonNull SMB2Dialect dialect, @Nullable SMB3EncryptionCipher cipher, boolean encrypted) {
    String protocol = "SMB " + dialect.name().substring("SMB_".length()).replace('_', '.');
    if (!encrypted || cipher == null) {
      return protocol + ", unencrypted";
    }
    return protocol + ", " + cipher.name().replace('_', '-');
  }
}
