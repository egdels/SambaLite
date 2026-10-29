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

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;

import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.data.model.SmbConnection;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration tests for SMB 3.1.1 encryption ciphers against a real Samba server (issue #45,
 * "[Feature] Support AES-256-GCM encryption").
 *
 * <p>Reproduces the reporter's hardened setup: Samba with {@code server smb encrypt = required} and
 * {@code server smb3 encryption algorithms} restricted to the 256-bit ciphers. With SMBJ 0.14.0,
 * which only offered AES-128, the connection failed because the client and server had no cipher in
 * common.
 */
public class EncryptionCipherIntegrationTest {

  private static final String IMAGE_NAME = "dockurr/samba:4.23.8";
  private static final int SMB_PORT = 445;
  private static final String SHARE_NAME = "Data";
  private static final String USERNAME = "smbuser";
  private static final String PASSWORD = "secret123";

  private GenericContainer<?> container;
  private SmbRepositoryImpl repository;

  @Before
  public void setUp() {
    boolean dockerAvailable;
    try {
      DockerClientFactory.instance().client();
      dockerAvailable = true;
    } catch (Throwable t) {
      dockerAvailable = false;
    }
    assumeTrue("Docker is required for this integration test", dockerAvailable);
    repository = new SmbRepositoryImpl(mock(BackgroundSmbManager.class));
  }

  @After
  public void tearDown() {
    if (repository != null) repository.closeConnections();
    if (container != null) container.stop();
  }

  private void startServer(String globalOptions) throws Exception {
    String smbConf =
        "[global]\n"
            + "security = user\n"
            + "server min protocol = SMB2\n"
            + globalOptions
            + "\n"
            + "[" + SHARE_NAME + "]\n"
            + "path = /storage\n"
            + "read only = no\n"
            + "valid users = " + USERNAME + "\n";
    container =
        new GenericContainer<>(DockerImageName.parse(IMAGE_NAME))
            .withExposedPorts(SMB_PORT)
            .withEnv("USER", USERNAME)
            .withEnv("PASS", PASSWORD)
            .withCopyToContainer(Transferable.of(smbConf), "/etc/samba/smb.conf")
            .waitingFor(Wait.forListeningPort());
    container.start();
    Thread.sleep(2000);
  }

  private SmbConnection connection(boolean encrypt) {
    SmbConnection connection = new SmbConnection();
    connection.setName("cipher-test");
    connection.setServer(container.getHost());
    connection.setPort(container.getMappedPort(SMB_PORT));
    connection.setShare(SHARE_NAME);
    connection.setUsername(USERNAME);
    connection.setPassword(PASSWORD);
    connection.setDomain("WORKGROUP");
    connection.setEncryptData(encrypt);
    return connection;
  }

  @Test
  public void aes256GcmOnlyServerNegotiatesAes256Gcm() throws Exception {
    startServer(
        "server smb encrypt = required\nserver smb3 encryption algorithms = aes-256-gcm\n");
    SmbConnection connection = connection(true);

    assertTrue(repository.testConnection(connection));
    assertEquals(
        "SMB 3.1.1, AES-256-GCM", repository.getConnectionSecurityInfo(connection));
    assertNotNull(repository.listFiles(connection, ""));
  }

  @Test
  public void aes256CcmOnlyServerNegotiatesAes256Ccm() throws Exception {
    startServer(
        "server smb encrypt = required\nserver smb3 encryption algorithms = aes-256-ccm\n");
    SmbConnection connection = connection(true);

    assertTrue(repository.testConnection(connection));
    assertEquals(
        "SMB 3.1.1, AES-256-CCM", repository.getConnectionSecurityInfo(connection));
  }

  @Test
  public void defaultServerKeepsAes128Gcm() throws Exception {
    startServer("server smb encrypt = required\n");
    SmbConnection connection = connection(true);

    assertTrue(repository.testConnection(connection));
    assertEquals(
        "SMB 3.1.1, AES-128-GCM", repository.getConnectionSecurityInfo(connection));
  }

  @Test
  public void unencryptedConnectionIsReported() throws Exception {
    startServer("");
    SmbConnection connection = connection(false);

    assertTrue(repository.testConnection(connection));
    assertEquals(
        "SMB 3.1.1, unencrypted", repository.getConnectionSecurityInfo(connection));
  }
}
