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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.data.repository.SmbRepositoryImpl;
import de.schliweb.sambalite.util.SambaContainer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Pins down the behaviour of {@link SmbRepository#deleteFile} and {@link SmbRepository#deleteFiles}
 * against a real Samba server: what gets deleted, what survives, and which paths are reported as
 * failed. Written before the folder delete was reworked for speed, so the rework has to keep all
 * of it.
 */
public class SmbDeleteIntegrationTest {

  private static final String SHARE = "testshare";
  private static final String ROOT = "/testshare";

  private SambaContainer samba;
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

    connection = new SmbConnection();
    connection.setServer(samba.getHost());
    connection.setPort(samba.getPort());
    connection.setShare(SHARE);
    connection.setUsername(samba.getUsername());
    connection.setPassword(samba.getPassword());
    connection.setDomain(samba.getDomain());

    repository = new SmbRepositoryImpl(synchronousBackgroundManager());
  }

  @After
  public void tearDown() {
    if (repository != null) repository.closeConnections();
    if (samba != null) samba.stop();
  }

  // ---------------------------------------------------------------------------------------------
  // deleteFile
  // ---------------------------------------------------------------------------------------------

  @Test
  public void deleteFile_singleFile_isRemoved() throws Exception {
    sh("mkdir -p " + ROOT + "/dir && echo x > " + ROOT + "/dir/a.txt && echo y > " + ROOT
        + "/dir/b.txt");

    repository.deleteFile(connection, "dir/a.txt");

    assertFalse(exists("dir/a.txt"));
    assertTrue("sibling must survive", exists("dir/b.txt"));
  }

  @Test
  public void deleteFile_missingPath_throws() {
    assertThrows(Exception.class, () -> repository.deleteFile(connection, "does/not/exist.txt"));
  }

  @Test
  public void deleteFile_emptyFolder_isRemoved() throws Exception {
    sh("mkdir -p " + ROOT + "/empty");

    repository.deleteFile(connection, "empty");

    assertFalse(exists("empty"));
  }

  @Test
  public void deleteFile_nestedFolder_removesWholeTreeButNotSiblings() throws Exception {
    createAudiobookTree("Hoerspiele/Autor", 3, 4);
    sh("mkdir -p '" + ROOT + "/Hoerspiele/Autor 2' && echo keep > '" + ROOT
        + "/Hoerspiele/Autor 2/keep.mp3'");

    repository.deleteFile(connection, "Hoerspiele/Autor");

    assertFalse(exists("Hoerspiele/Autor"));
    assertTrue("folder with a shared name prefix must survive", exists("Hoerspiele/Autor 2/keep.mp3"));
    assertTrue(exists("Hoerspiele"));
  }

  @Test
  public void deleteFile_folderWithSpecialNamesHiddenFilesAndEmptySubfolders() throws Exception {
    String base = ROOT + "/Spezial";
    sh(
        "mkdir -p '"
            + base
            + "/Ostseehölle Band 21/leer' '"
            + base
            + "/Ostseehölle Band 21/CD 1' && "
            + "echo a > '"
            + base
            + "/Ostseehölle Band 21/CD 1/01 Kapitel 1.mp3' && "
            + "echo b > '"
            + base
            + "/Ostseehölle Band 21/.hidden' && "
            + "echo c > '"
            + base
            + "/Ostseehölle Band 21/Ünïcödé, (Teil 1) & mehr.mp3'");

    repository.deleteFile(connection, "Spezial/Ostseehölle Band 21");

    assertFalse(exists("Spezial/Ostseehölle Band 21"));
    assertTrue(exists("Spezial"));
  }

  @Test
  public void deleteFile_acceptsBackslashAndLeadingSlashPaths() throws Exception {
    createAudiobookTree("a/b", 1, 2);
    createAudiobookTree("c/d", 1, 2);

    repository.deleteFile(connection, "/a/b");
    repository.deleteFile(connection, "c\\d");

    assertFalse(exists("a/b"));
    assertFalse(exists("c/d"));
  }

  @Test
  public void deleteFile_largeFolder_isRemovedCompletely() throws Exception {
    createAudiobookTree("Gross", 10, 30); // 300 files in 10 sub folders

    long start = System.nanoTime();
    repository.deleteFile(connection, "Gross");
    long millis = (System.nanoTime() - start) / 1_000_000;
    System.out.println("[DEBUG_LOG] deleteFile of 300 files in 10 folders took " + millis + " ms");

    assertFalse(exists("Gross"));
  }

  // ---------------------------------------------------------------------------------------------
  // deleteFiles (multi-selection)
  // ---------------------------------------------------------------------------------------------

  @Test
  public void deleteFiles_mixedSelection_deletesAllAndReportsOnlyMissing() throws Exception {
    sh("mkdir -p " + ROOT + "/sel && echo x > " + ROOT + "/sel/file.txt");
    createAudiobookTree("sel/folder", 2, 3);
    sh("echo keep > " + ROOT + "/sel/untouched.txt");

    List<String> failed =
        repository.deleteFiles(
            connection, Arrays.asList("sel/file.txt", "sel/folder", "sel/missing.txt"));

    assertEquals(Collections.singletonList("sel/missing.txt"), failed);
    assertFalse(exists("sel/file.txt"));
    assertFalse(exists("sel/folder"));
    assertTrue(exists("sel/untouched.txt"));
  }

  @Test
  public void deleteFiles_failedPathsKeepSelectionOrder() throws Exception {
    sh("mkdir -p " + ROOT + "/ord && echo x > " + ROOT + "/ord/ok.txt");

    List<String> failed =
        repository.deleteFiles(
            connection, Arrays.asList("ord/missing-b", "ord/ok.txt", "ord/missing-a"));

    assertEquals(Arrays.asList("ord/missing-b", "ord/missing-a"), failed);
    assertFalse(exists("ord/ok.txt"));
  }

  @Test
  public void deleteFiles_manyFoldersAndFiles_areAllRemoved() throws Exception {
    createAudiobookTree("viele/A", 3, 20);
    createAudiobookTree("viele/B", 3, 20);
    sh("for i in $(seq 1 40); do echo $i > " + ROOT + "/viele/f$i.mp3; done");
    String[] paths = new String[42];
    paths[0] = "viele/A";
    paths[1] = "viele/B";
    for (int i = 1; i <= 40; i++) paths[i + 1] = "viele/f" + i + ".mp3";

    long start = System.nanoTime();
    List<String> failed = repository.deleteFiles(connection, Arrays.asList(paths));
    long millis = (System.nanoTime() - start) / 1_000_000;
    System.out.println("[DEBUG_LOG] deleteFiles of 2 folders (120 files) + 40 files took "
        + millis + " ms");

    assertTrue("nothing should fail: " + failed, failed.isEmpty());
    assertEquals("", sh("ls -A " + ROOT + "/viele").trim());
  }

  @Test
  public void undeletableContent_isReportedAndOtherSelectionsAreStillDeleted() throws Exception {
    createAudiobookTree("perm/locked", 2, 3);
    sh("mkdir -p " + ROOT + "/perm/ok && echo x > " + ROOT + "/perm/ok/a.txt");
    // The Samba user may not remove entries from "Teil 1"; chmod runs after the helper's chmod
    SambaContainer.ExecResult r =
        samba.execInContainer("chmod", "555", ROOT + "/perm/locked/Teil 1");
    assertEquals(0, r.getExitCode());

    assertThrows(Exception.class, () -> repository.deleteFile(connection, "perm/locked"));
    assertTrue("locked content must still be there", exists("perm/locked/Teil 1/Kapitel 1.mp3"));

    List<String> failed =
        repository.deleteFiles(connection, Arrays.asList("perm/locked", "perm/ok"));

    assertEquals(Collections.singletonList("perm/locked"), failed);
    assertFalse(exists("perm/ok"));
    assertTrue(exists("perm/locked/Teil 1/Kapitel 1.mp3"));
  }

  // ---------------------------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------------------------

  /** Creates {@code folders} sub folders below {@code relPath}, each with {@code files} files. */
  private void createAudiobookTree(String relPath, int folders, int files) throws Exception {
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

  /** Runs a shell command in the container and makes the share writable for the Samba user. */
  private String sh(String command) throws Exception {
    SambaContainer.ExecResult r =
        samba.execInContainer("sh", "-c", command + " && chmod -R a+rwX " + ROOT);
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
