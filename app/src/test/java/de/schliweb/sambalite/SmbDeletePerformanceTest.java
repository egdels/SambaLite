/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.data.repository.SmbRepositoryImpl;
import de.schliweb.sambalite.test.helper.LatencyProxy;
import de.schliweb.sambalite.util.SambaContainer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Measures folder deletion over a link with Wi-Fi-like latency (3 ms each way) and compares the
 * repository against the previous implementation, SMBJ's sequential {@code rmdir(path, true)} and
 * the old per-file {@code fileExists}/{@code rm}/{@code fileExists} loop of the batch delete.
 */
public class SmbDeletePerformanceTest {

  private static final String SHARE = "testshare";
  private static final String ROOT = "/testshare";
  private static final long ONE_WAY_DELAY_MS = 3;

  private SambaContainer samba;
  private LatencyProxy proxy;
  private SmbRepository repository;
  private SmbConnection connection;

  @Before
  public void setUp() throws Exception {
    assumeTrue("Docker is required", dockerAvailable());
    samba =
        new SambaContainer()
            .withUsername("testuser")
            .withPassword("testpassword")
            .withDomain("WORKGROUP")
            .withShare(SHARE, ROOT);
    samba.start();
    proxy = new LatencyProxy(samba.getHost(), samba.getPort(), ONE_WAY_DELAY_MS);

    connection = new SmbConnection();
    connection.setServer(proxy.getHost());
    connection.setPort(proxy.getPort());
    connection.setShare(SHARE);
    connection.setUsername(samba.getUsername());
    connection.setPassword(samba.getPassword());
    connection.setDomain(samba.getDomain());
    repository = new SmbRepositoryImpl(synchronousBackgroundManager());
  }

  @After
  public void tearDown() throws Exception {
    if (repository != null) repository.closeConnections();
    if (proxy != null) proxy.close();
    if (samba != null) samba.stop();
  }

  @Test
  public void folderDelete_isMuchFasterThanSequentialRmdir() throws Exception {
    createTree("alt", 8, 25); // 200 files, like an audiobook in parts
    createTree("neu", 8, 25);

    long oldMs;
    try (SMBClient client = new SMBClient();
        Connection conn = client.connect(proxy.getHost(), proxy.getPort());
        Session session =
            conn.authenticate(
                new AuthenticationContext(
                    samba.getUsername(), samba.getPassword().toCharArray(), samba.getDomain()));
        DiskShare share = (DiskShare) session.connectShare(SHARE)) {
      long start = System.nanoTime();
      share.rmdir("alt", true);
      oldMs = (System.nanoTime() - start) / 1_000_000;
    }

    // warm up the session cache so both sides start from an open session
    repository.listFiles(connection, "");
    long start = System.nanoTime();
    repository.deleteFile(connection, "neu");
    long newMs = (System.nanoTime() - start) / 1_000_000;

    System.out.println(
        "[DEBUG_LOG] delete 200 files in 8 folders at "
            + (2 * ONE_WAY_DELAY_MS)
            + " ms RTT: sequential rmdir "
            + oldMs
            + " ms, repository "
            + newMs
            + " ms");
    assertFalse(exists("alt"));
    assertFalse(exists("neu"));
    assertTrue(
        "expected at least 2x speed-up, old=" + oldMs + " ms new=" + newMs + " ms",
        newMs * 2 < oldMs);
  }

  @Test
  public void batchDelete_ofSingleFiles_isMuchFasterThanTheOldLoop() throws Exception {
    sh("mkdir -p " + ROOT + "/a " + ROOT + "/b && for i in $(seq 1 60); do echo $i > " + ROOT
        + "/a/f$i.mp3; echo $i > " + ROOT + "/b/f$i.mp3; done");

    long oldMs;
    try (SMBClient client = new SMBClient();
        Connection conn = client.connect(proxy.getHost(), proxy.getPort());
        Session session =
            conn.authenticate(
                new AuthenticationContext(
                    samba.getUsername(), samba.getPassword().toCharArray(), samba.getDomain()));
        DiskShare share = (DiskShare) session.connectShare(SHARE)) {
      long start = System.nanoTime();
      for (int i = 1; i <= 60; i++) {
        String p = "a\\f" + i + ".mp3";
        if (share.fileExists(p)) { // the previous implementation, per selected entry
          share.rm(p);
          share.fileExists(p);
        }
      }
      oldMs = (System.nanoTime() - start) / 1_000_000;
    }

    List<String> paths = new ArrayList<>();
    for (int i = 1; i <= 60; i++) paths.add("b/f" + i + ".mp3");
    repository.listFiles(connection, "");
    long start = System.nanoTime();
    List<String> failed = repository.deleteFiles(connection, paths);
    long newMs = (System.nanoTime() - start) / 1_000_000;

    System.out.println(
        "[DEBUG_LOG] batch delete of 60 files at "
            + (2 * ONE_WAY_DELAY_MS)
            + " ms RTT: old loop "
            + oldMs
            + " ms, repository "
            + newMs
            + " ms");
    assertTrue(failed.isEmpty());
    assertEquals("", sh("ls -A " + ROOT + "/a " + ROOT + "/b | grep -v ':$' | grep -v '^$' || true").trim());
    assertTrue(
        "expected at least 2x speed-up, old=" + oldMs + " ms new=" + newMs + " ms",
        newMs * 2 < oldMs);
  }

  private void createTree(String relPath, int folders, int files) throws Exception {
    String base = ROOT + "/" + relPath;
    sh(
        "for d in $(seq 1 "
            + folders
            + "); do mkdir -p \""
            + base
            + "/Teil $d\"; for f in $(seq 1 "
            + files
            + "); do echo $d-$f > \""
            + base
            + "/Teil $d/Kapitel $f.mp3\"; done; done");
  }

  private boolean exists(String relPath) throws Exception {
    return samba.execInContainer("test", "-e", ROOT + "/" + relPath).getExitCode() == 0;
  }

  private String sh(String command) throws Exception {
    SambaContainer.ExecResult r =
        samba.execInContainer("sh", "-c", command + " ; chmod -R a+rwX " + ROOT);
    assertEquals("command failed: " + command + "\n" + r.getStderr(), 0, r.getExitCode());
    return r.getStdout();
  }

  private static boolean dockerAvailable() {
    try {
      return org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
    } catch (Throwable t) {
      return false;
    }
  }

  private static BackgroundSmbManager synchronousBackgroundManager() {
    BackgroundSmbManager manager = Mockito.mock(BackgroundSmbManager.class);
    Mockito.when(
            manager.executeBackgroundOperation(
                Mockito.anyString(), Mockito.anyString(), Mockito.any()))
        .thenAnswer(
            invocation -> {
              BackgroundSmbManager.BackgroundOperation<?> operation = invocation.getArgument(2);
              try {
                return CompletableFuture.completedFuture(operation.execute(info -> {}));
              } catch (Exception e) {
                CompletableFuture<Object> future = new CompletableFuture<>();
                future.completeExceptionally(e);
                return future;
              }
            });
    return manager;
  }
}
