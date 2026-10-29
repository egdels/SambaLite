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

import static org.junit.Assert.*;

import com.hierynomus.mssmb2.SMB2Dialect;
import com.hierynomus.mssmb2.SMB3EncryptionCipher;
import com.hierynomus.smbj.SmbConfig;
import org.junit.Test;

public class SmbClientConfigsTest {

  @Test
  public void builderDisablesDirectoryLeasing() {
    SmbConfig config = SmbClientConfigs.builder().build();
    assertFalse(config.isDirectoryLeasingEnabled());
  }

  @Test
  public void builderOffersAes256Ciphers() {
    SmbConfig config = SmbClientConfigs.builder().withEncryptData(true).build();
    assertTrue(config.getEncryptionCiphers().contains(SMB3EncryptionCipher.AES_256_GCM));
    assertTrue(config.getEncryptionCiphers().contains(SMB3EncryptionCipher.AES_256_CCM));
  }

  @Test
  public void describesEncryptedConnection() {
    assertEquals(
        "SMB 3.1.1, AES-256-GCM",
        SmbClientConfigs.describeSecurity(
            SMB2Dialect.SMB_3_1_1, SMB3EncryptionCipher.AES_256_GCM, true));
  }

  @Test
  public void describesUnencryptedConnection() {
    assertEquals(
        "SMB 3.1.1, unencrypted",
        SmbClientConfigs.describeSecurity(
            SMB2Dialect.SMB_3_1_1, SMB3EncryptionCipher.AES_128_GCM, false));
    assertEquals(
        "SMB 2.0.2, unencrypted",
        SmbClientConfigs.describeSecurity(SMB2Dialect.SMB_2_0_2, null, false));
  }
}
