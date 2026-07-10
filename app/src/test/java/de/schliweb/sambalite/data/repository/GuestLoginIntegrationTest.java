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

import com.hierynomus.mssmb2.SMB2Dialect;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration tests for guest/anonymous login against a real Samba server (issue #32, "[Bug] Guest
 * login fails").
 *
 * <p>Reproduces the reporter's setup: Samba 4.23.x with a guest-accessible read-only share ({@code
 * guest ok = yes}) and <b>no</b> {@code map to guest} mapping (Samba default is {@code Never}) -
 * exactly like the reporter's smb.conf. Uses the {@code dockurr/samba} image, which ships the same
 * Samba version (4.23.8) as the reporter's server.
 *
 * <p>Expected behavior in this setup:
 *
 * <ul>
 *   <li>{@link AuthenticationContext#guest()} sends the username {@code Guest}, which the server
 *       rejects with {@code NT_STATUS_NO_SUCH_USER} / {@code NT_STATUS_LOGON_FAILURE} (the old,
 *       buggy SambaLite behavior from issue #32).
 *   <li>An anonymous context ({@code new AuthenticationContext("", new char[0], domain)}) succeeds
 *       - this is the fixed behavior used by {@code SmbRepositoryImpl.createAuthContext} and
 *       matches {@code mount.cifs -o guest}.
 *   <li>With the documented workaround {@code map to guest = Bad User}, the old {@code guest()}
 *       login works as well.
 * </ul>
 *
 * <p>Note: like {@code SmbRepositoryImpl.getClientFor}, anonymous logins are performed with an
 * SMB2-restricted client. SMBJ 0.14.0 crashes with a NullPointerException when deriving SMB3
 * signing keys for anonymous sessions (hierynomus/smbj#792), because Samba does not set the
 * IS_NULL/IS_GUEST session flags for them.
 */
public class GuestLoginIntegrationTest {

  private static final String IMAGE_NAME = "dockurr/samba:latest";
  private static final int SMB_PORT = 445;
  private static final String SHARE_NAME = "guestshare";
  private static final String DOMAIN = "WORKGROUP";

  /**
   * Mirrors the reporter's smb.conf from issue #32: guest-accessible read-only share, no "map to
   * guest" in [global] (default: Never).
   */
  private static final String SMB_CONF_WITHOUT_MAP_TO_GUEST =
      "[global]\n"
          + "security = user\n"
          + "server min protocol = SMB2\n"
          + "\n"
          + "[" + SHARE_NAME + "]\n"
          + "browseable = yes\n"
          + "guest ok = yes\n"
          + "path = /storage\n"
          + "read only = yes\n";

  /** Same setup, but with the documented workaround "map to guest = Bad User". */
  private static final String SMB_CONF_WITH_MAP_TO_GUEST =
      "[global]\n"
          + "security = user\n"
          + "server min protocol = SMB2\n"
          + "map to guest = Bad User\n"
          + "\n"
          + "[" + SHARE_NAME + "]\n"
          + "browseable = yes\n"
          + "guest ok = yes\n"
          + "path = /storage\n"
          + "read only = yes\n";

  private GenericContainer<?> container;

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
  }

  @After
  public void tearDown() {
    if (container != null) container.stop();
  }

  private void startServer(String smbConf) throws Exception {
    container =
        new GenericContainer<>(DockerImageName.parse(IMAGE_NAME))
            .withExposedPorts(SMB_PORT)
            .withCopyToContainer(Transferable.of(smbConf), "/etc/samba/smb.conf")
            .waitingFor(Wait.forListeningPort());
    container.start();
    Thread.sleep(2000);
    // Make the share directory readable for the guest account (nobody).
    container.execInContainer("chmod", "0777", "/storage");
  }

  private void connectAndListShare(AuthenticationContext auth) throws Exception {
    try (SMBClient smbClient = createClientFor(auth)) {
      try (Connection connection =
          smbClient.connect(container.getHost(), container.getMappedPort(SMB_PORT))) {
        try (Session session = connection.authenticate(auth)) {
          try (DiskShare share = (DiskShare) session.connectShare(SHARE_NAME)) {
            // Listing the share root proves the session is fully usable.
            assertNotNull(share.list(""));
          }
        }
      }
    }
  }

  /**
   * Mirrors SmbRepositoryImpl.getClientFor: anonymous logins use an SMB2-restricted client as a
   * workaround for hierynomus/smbj#792, everything else uses the default client.
   */
  private static SMBClient createClientFor(AuthenticationContext auth) {
    if (auth.getUsername().isEmpty() && auth.getPassword().length == 0) {
      return new SMBClient(
          SmbConfig.builder().withDialects(SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2).build());
    }
    return new SMBClient();
  }

  /** Builds the same context as SmbRepositoryImpl.createAuthContext for empty credentials. */
  private static AuthenticationContext anonymousAuth() {
    return new AuthenticationContext("", new char[0], DOMAIN);
  }

  // ===== Fixed behavior: anonymous login (SmbRepositoryImpl.createAuthContext) =====

  @Test
  public void anonymousLogin_guestShareWithoutMapToGuest_succeeds() throws Exception {
    startServer(SMB_CONF_WITHOUT_MAP_TO_GUEST);

    connectAndListShare(anonymousAuth());
  }

  // ===== Old (buggy) behavior: AuthenticationContext.guest() =====

  @Test
  public void guestLogin_guestShareWithoutMapToGuest_failsWithLogonFailure() throws Exception {
    startServer(SMB_CONF_WITHOUT_MAP_TO_GUEST);

    // The old implementation used AuthenticationContext.guest(), which sends the
    // username "Guest". Without "map to guest = Bad User" the server rejects it
    // with NT_STATUS_NO_SUCH_USER -> NT_STATUS_LOGON_FAILURE (see issue #32 logs).
    try {
      connectAndListShare(AuthenticationContext.guest());
      fail("Expected guest login to fail without 'map to guest = Bad User'");
    } catch (SMBApiException e) {
      String status = e.getStatus() != null ? e.getStatus().name() : "";
      assertTrue(
          "Expected logon failure, but was: " + status,
          status.contains("LOGON_FAILURE") || status.contains("NO_SUCH_USER"));
    }
  }

  // ===== Workaround: server maps unknown users to guest =====

  @Test
  public void guestLogin_withMapToGuestBadUser_succeeds() throws Exception {
    startServer(SMB_CONF_WITH_MAP_TO_GUEST);

    // With "map to guest = Bad User" even the "Guest" username is mapped to the
    // guest account, so the old behavior works too.
    connectAndListShare(AuthenticationContext.guest());
  }

  @Test
  public void anonymousLogin_withMapToGuestBadUser_succeeds() throws Exception {
    startServer(SMB_CONF_WITH_MAP_TO_GUEST);

    connectAndListShare(anonymousAuth());
  }
}
