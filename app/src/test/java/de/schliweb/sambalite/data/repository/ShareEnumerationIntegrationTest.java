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
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * Share discovery against a real Samba server (issue #47, "Connect to a server and browse its SMB
 * shares without specifying a share").
 *
 * <p>Discovery used to probe a fixed list of common share names, so a share with a custom name
 * such as the reporter's TrueNAS shares was never found. It now asks the server via srvsvc {@code
 * NetrShareEnum}. The server configured here publishes:
 *
 * <ul>
 *   <li>{@code Data} and {@code Projekt Ärger 2026}: browseable, the second one with a name no
 *       probing list would contain, spaces and non-ASCII characters
 *   <li>{@code versteckt}: {@code browseable = no}, must not be listed
 *   <li>{@code backup$}: a hidden {@code $} share, must not be listed
 *   <li>{@code fremd}: only for another user, with access-based share enumeration, so the server
 *       does not list it for {@code smbuser}
 *   <li>{@code Gastordner}: guest-accessible, the only share an anonymous user may see listed
 * </ul>
 */
public class ShareEnumerationIntegrationTest {

  private static final String IMAGE_NAME = "dockurr/samba:4.23.8";
  private static final int SMB_PORT = 445;
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
            + "map to guest = Bad User\n"
            + globalOptions
            + "\n[Data]\npath = /storage\nread only = no\nvalid users = " + USERNAME + "\n"
            + "\n[Projekt Ärger 2026]\npath = /storage\nvalid users = " + USERNAME + "\n"
            + "\n[versteckt]\npath = /storage\nbrowseable = no\n"
            + "\n[backup$]\npath = /storage\n"
            + "\n[fremd]\npath = /storage\nvalid users = someoneelse\n"
            + "access based share enum = yes\n"
            + "\n[Gastordner]\npath = /storage\nguest ok = yes\nread only = yes\n";
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

  private SmbConnection connection(String username, String password) {
    SmbConnection connection = new SmbConnection();
    connection.setName("share-enum-test");
    connection.setServer(container.getHost());
    connection.setPort(container.getMappedPort(SMB_PORT));
    connection.setUsername(username);
    connection.setPassword(password);
    connection.setDomain("WORKGROUP");
    return connection;
  }

  @Test
  public void listsPublishedSharesIncludingCustomNames() throws Exception {
    startServer("");

    List<String> shares = repository.listShares(connection(USERNAME, PASSWORD));

    assertEquals(Arrays.asList("Data", "Gastordner", "Projekt Ärger 2026"), shares);
  }

  @Test
  public void listsSharesOverAnEncryptedSession() throws Exception {
    startServer("server smb encrypt = required\n");
    SmbConnection connection = connection(USERNAME, PASSWORD);
    connection.setEncryptData(true);

    List<String> shares = repository.listShares(connection);

    assertEquals(Arrays.asList("Data", "Gastordner", "Projekt Ärger 2026"), shares);
  }

  @Test
  public void anonymousUserSeesTheGuestShare() throws Exception {
    startServer("");

    List<String> shares = repository.listShares(connection("", ""));

    assertTrue(shares.toString(), shares.contains("Gastordner"));
    assertFalse(shares.toString(), shares.contains("versteckt"));
    assertFalse(shares.toString(), shares.contains("backup$"));
  }
}
