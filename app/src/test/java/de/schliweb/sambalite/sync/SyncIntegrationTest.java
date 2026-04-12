/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.sync;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.*;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msdtyp.FileTime;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileBasicInformation;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import de.schliweb.sambalite.util.SambaContainer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for sync operations against a real Docker Samba Server.
 *
 * <p>Tests the core sync logic that FolderSyncWorker uses: uploading, downloading,
 * timestamp comparison, directory creation, conflict handling, and bidirectional sync.
 * Uses the same smbj API as FolderSyncWorker to validate real SMB behavior.
 */
public class SyncIntegrationTest {

  private SambaContainer sambaContainer;
  private SMBClient smbClient;
  private Connection connection;
  private Session session;
  private DiskShare share;
  private Path localTempDir;
  private final SyncComparator syncComparator = new SyncComparator();

  @Before
  public void setUp() throws Exception {
    sambaContainer =
        new SambaContainer()
            .withUsername("testuser")
            .withPassword("testpassword")
            .withDomain("WORKGROUP")
            .withShare("testshare", "/testshare");
    sambaContainer.start();

    Thread.sleep(2000);
    sambaContainer.execInContainer("chmod", "0777", "/testshare");

    smbClient = new SMBClient();
    connection = smbClient.connect(sambaContainer.getHost(), sambaContainer.getPort());
    AuthenticationContext auth =
        new AuthenticationContext("testuser", "testpassword".toCharArray(), "WORKGROUP");
    session = connection.authenticate(auth);
    share = (DiskShare) session.connectShare("testshare");

    localTempDir = Files.createTempDirectory("sync-integration-test");
  }

  @After
  public void tearDown() throws Exception {
    if (share != null) share.close();
    if (session != null) session.close();
    if (connection != null) connection.close();
    if (smbClient != null) smbClient.close();
    if (sambaContainer != null) sambaContainer.stop();
    if (localTempDir != null) deleteDirectory(localTempDir);
  }

  // ===== LOCAL → REMOTE: Upload new file =====

  @Test
  public void localToRemote_newFile_uploadsSuccessfully() throws Exception {
    Path localFile = localTempDir.resolve("newfile.txt");
    Files.write(localFile, "Hello from local".getBytes(UTF_8));

    uploadFile(localFile, "newfile.txt");

    assertTrue("Remote file should exist", share.fileExists("newfile.txt"));
    String remoteContent = readRemoteFile("newfile.txt");
    assertEquals("Hello from local", remoteContent);
  }

  @Test
  public void localToRemote_localNewer_uploadsFile() throws Exception {
    writeRemoteFile("update.txt", "old remote content");
    setRemoteLastModified("update.txt", System.currentTimeMillis() - 60_000);

    Path localFile = localTempDir.resolve("update.txt");
    Files.write(localFile, "new local content".getBytes(UTF_8));
    localFile.toFile().setLastModified(System.currentTimeMillis());

    long localMod = localFile.toFile().lastModified();
    long remoteMod = getRemoteLastModified("update.txt");
    assertTrue("Local should be newer", syncComparator.isLocalNewer(localMod, remoteMod));

    uploadFile(localFile, "update.txt");

    String remoteContent = readRemoteFile("update.txt");
    assertEquals("new local content", remoteContent);
  }

  @Test
  public void localToRemote_remoteNewer_skipsUpload() throws Exception {
    writeRemoteFile("keep.txt", "remote is newer");
    setRemoteLastModified("keep.txt", System.currentTimeMillis());

    Path localFile = localTempDir.resolve("keep.txt");
    Files.write(localFile, "old local content".getBytes(UTF_8));
    localFile.toFile().setLastModified(System.currentTimeMillis() - 60_000);

    long localMod = localFile.toFile().lastModified();
    long remoteMod = getRemoteLastModified("keep.txt");
    assertFalse("Local should NOT be newer", syncComparator.isLocalNewer(localMod, remoteMod));

    // Sync logic would skip upload — verify remote unchanged
    String remoteContent = readRemoteFile("keep.txt");
    assertEquals("remote is newer", remoteContent);
  }

  @Test
  public void localToRemote_sameFile_skipsUpload() throws Exception {
    long now = System.currentTimeMillis();
    Path localFile = localTempDir.resolve("same.txt");
    Files.write(localFile, "identical".getBytes(UTF_8));
    localFile.toFile().setLastModified(now);

    writeRemoteFile("same.txt", "identical");
    setRemoteLastModified("same.txt", now);

    long localSize = Files.size(localFile);
    long remoteSize = getRemoteFileSize("same.txt");
    long remoteMod = getRemoteLastModified("same.txt");

    assertTrue(
        "Files should be considered same",
        syncComparator.isSame(localSize, now, remoteSize, remoteMod));
  }

  @Test
  public void localToRemote_nestedDirectory_createsAndUploads() throws Exception {
    Path subDir = localTempDir.resolve("subdir");
    Files.createDirectories(subDir);
    Path nestedFile = subDir.resolve("nested.txt");
    Files.write(nestedFile, "nested content".getBytes(UTF_8));

    ensureRemoteDirectoryExists("subdir");
    uploadFile(nestedFile, "subdir\\nested.txt");

    assertTrue("Remote dir should exist", share.folderExists("subdir"));
    assertTrue("Remote nested file should exist", share.fileExists("subdir\\nested.txt"));
    assertEquals("nested content", readRemoteFile("subdir\\nested.txt"));
  }

  @Test
  public void localToRemote_deepNestedDirectories_createsRecursively() throws Exception {
    Path deep = localTempDir.resolve("a/b/c");
    Files.createDirectories(deep);
    Files.write(deep.resolve("deep.txt"), "deep content".getBytes(UTF_8));

    ensureRemoteDirectoryExists("a\\b\\c");
    uploadFile(deep.resolve("deep.txt"), "a\\b\\c\\deep.txt");

    assertTrue(share.folderExists("a"));
    assertTrue(share.folderExists("a\\b"));
    assertTrue(share.folderExists("a\\b\\c"));
    assertEquals("deep content", readRemoteFile("a\\b\\c\\deep.txt"));
  }

  // ===== REMOTE → LOCAL: Download new file =====

  @Test
  public void remoteToLocal_newFile_downloadsSuccessfully() throws Exception {
    writeRemoteFile("remote-new.txt", "Hello from remote");

    Path localFile = localTempDir.resolve("remote-new.txt");
    downloadFile("remote-new.txt", localFile);

    assertTrue("Local file should exist", Files.exists(localFile));
    assertEquals("Hello from remote", new String(Files.readAllBytes(localFile), UTF_8));
  }

  @Test
  public void remoteToLocal_remoteNewer_downloadsFile() throws Exception {
    Path localFile = localTempDir.resolve("update.txt");
    Files.write(localFile, "old local content".getBytes(UTF_8));
    localFile.toFile().setLastModified(System.currentTimeMillis() - 60_000);

    writeRemoteFile("update.txt", "new remote content");
    setRemoteLastModified("update.txt", System.currentTimeMillis());

    long localMod = localFile.toFile().lastModified();
    long remoteMod = getRemoteLastModified("update.txt");
    assertTrue("Remote should be newer", syncComparator.isRemoteNewer(localMod, remoteMod));

    downloadFile("update.txt", localFile);

    assertEquals("new remote content", new String(Files.readAllBytes(localFile), UTF_8));
  }

  @Test
  public void remoteToLocal_localNewer_skipsDownload() throws Exception {
    Path localFile = localTempDir.resolve("keep-local.txt");
    Files.write(localFile, "local is newer".getBytes(UTF_8));
    localFile.toFile().setLastModified(System.currentTimeMillis());

    writeRemoteFile("keep-local.txt", "old remote content");
    setRemoteLastModified("keep-local.txt", System.currentTimeMillis() - 60_000);

    long localMod = localFile.toFile().lastModified();
    long remoteMod = getRemoteLastModified("keep-local.txt");
    assertFalse("Remote should NOT be newer", syncComparator.isRemoteNewer(localMod, remoteMod));

    // Sync logic would skip download — verify local unchanged
    assertEquals("local is newer", new String(Files.readAllBytes(localFile), UTF_8));
  }

  @Test
  public void remoteToLocal_nestedDirectory_createsAndDownloads() throws Exception {
    ensureRemoteDirectoryExists("remotedir");
    writeRemoteFile("remotedir\\file.txt", "remote nested");

    Path localSubDir = localTempDir.resolve("remotedir");
    Files.createDirectories(localSubDir);
    downloadFile("remotedir\\file.txt", localSubDir.resolve("file.txt"));

    assertTrue(Files.exists(localSubDir.resolve("file.txt")));
    assertEquals("remote nested", new String(Files.readAllBytes(localSubDir.resolve("file.txt")), UTF_8));
  }

  // ===== BIDIRECTIONAL SYNC =====

  @Test
  public void bidirectional_localNewAndRemoteNew_bothSynced() throws Exception {
    // Local-only file
    Path localOnly = localTempDir.resolve("local-only.txt");
    Files.write(localOnly, "from local".getBytes(UTF_8));

    // Remote-only file
    writeRemoteFile("remote-only.txt", "from remote");

    // Simulate bidirectional: upload local-only, download remote-only
    uploadFile(localOnly, "local-only.txt");
    downloadFile("remote-only.txt", localTempDir.resolve("remote-only.txt"));

    assertTrue(share.fileExists("local-only.txt"));
    assertEquals("from local", readRemoteFile("local-only.txt"));
    assertTrue(Files.exists(localTempDir.resolve("remote-only.txt")));
    assertEquals("from remote", new String(Files.readAllBytes(localTempDir.resolve("remote-only.txt")), UTF_8));
  }

  @Test
  public void bidirectional_conflictLocalNewer_localWins() throws Exception {
    writeRemoteFile("conflict.txt", "old remote");
    setRemoteLastModified("conflict.txt", System.currentTimeMillis() - 60_000);

    Path localFile = localTempDir.resolve("conflict.txt");
    Files.write(localFile, "new local".getBytes(UTF_8));
    localFile.toFile().setLastModified(System.currentTimeMillis());

    long localMod = localFile.toFile().lastModified();
    long remoteMod = getRemoteLastModified("conflict.txt");

    // Local→Remote phase: local is newer → upload
    assertTrue(syncComparator.isLocalNewer(localMod, remoteMod));
    uploadFile(localFile, "conflict.txt");

    assertEquals("new local", readRemoteFile("conflict.txt"));
  }

  @Test
  public void bidirectional_conflictRemoteNewer_remoteWins() throws Exception {
    Path localFile = localTempDir.resolve("conflict2.txt");
    Files.write(localFile, "old local".getBytes(UTF_8));
    localFile.toFile().setLastModified(System.currentTimeMillis() - 60_000);

    writeRemoteFile("conflict2.txt", "new remote");
    setRemoteLastModified("conflict2.txt", System.currentTimeMillis());

    long localMod = localFile.toFile().lastModified();
    long remoteMod = getRemoteLastModified("conflict2.txt");

    // Remote→Local phase: remote is newer → download
    assertTrue(syncComparator.isRemoteNewer(localMod, remoteMod));
    downloadFile("conflict2.txt", localFile);

    assertEquals("new remote", new String(Files.readAllBytes(localFile), UTF_8));
  }

  // ===== INTEGRITY =====

  @Test
  public void uploadIntegrity_fileSizeMatchesAfterUpload() throws Exception {
    byte[] content = new byte[10_000];
    for (int i = 0; i < content.length; i++) content[i] = (byte) (i % 256);

    Path localFile = localTempDir.resolve("integrity.bin");
    Files.write(localFile, content);

    uploadFile(localFile, "integrity.bin");

    long remoteSize = getRemoteFileSize("integrity.bin");
    assertEquals("Remote size should match local", content.length, remoteSize);
  }

  @Test
  public void downloadIntegrity_contentMatchesAfterDownload() throws Exception {
    String content = "Integrity test content with special chars: äöü ñ 日本語";
    writeRemoteFile("integrity-dl.txt", content);

    Path localFile = localTempDir.resolve("integrity-dl.txt");
    downloadFile("integrity-dl.txt", localFile);

    assertEquals(content, new String(Files.readAllBytes(localFile), UTF_8));
  }

  @Test
  public void roundTrip_uploadThenDownload_contentPreserved() throws Exception {
    String original = "Round trip test: 1234567890 abcdefghij ABCDEFGHIJ !@#$%^&*()";
    Path uploadFile = localTempDir.resolve("roundtrip.txt");
    Files.write(uploadFile, original.getBytes(UTF_8));

    uploadFile(uploadFile, "roundtrip.txt");

    Path downloadedFile = localTempDir.resolve("roundtrip-downloaded.txt");
    downloadFile("roundtrip.txt", downloadedFile);

    assertEquals(original, new String(Files.readAllBytes(downloadedFile), UTF_8));
  }

  // ===== TIMESTAMP HANDLING =====

  @Test
  public void setRemoteTimestamp_preservesTimestamp() throws Exception {
    writeRemoteFile("ts-test.txt", "timestamp test");
    long targetTime = 1700000000000L;

    setRemoteLastModified("ts-test.txt", targetTime);

    long actual = getRemoteLastModified("ts-test.txt");
    assertTrue(
        "Timestamp should be within tolerance",
        Math.abs(actual - targetTime) <= SyncComparator.DEFAULT_TIMESTAMP_TOLERANCE_MS);
  }

  @Test
  public void timestampTolerance_withinTolerance_consideredSame() {
    long now = System.currentTimeMillis();
    assertTrue(syncComparator.isSame(100, now, 100, now + 2000));
    assertTrue(syncComparator.isSame(100, now, 100, now - 2000));
  }

  @Test
  public void timestampTolerance_outsideTolerance_consideredDifferent() {
    long now = System.currentTimeMillis();
    assertFalse(syncComparator.isSame(100, now, 100, now + 5000));
    assertFalse(syncComparator.isSame(100, now, 100, now - 5000));
  }

  // ===== DIRECTORY OPERATIONS =====

  @Test
  public void ensureRemoteDirectory_createsNonExistent() throws Exception {
    assertFalse(share.folderExists("newdir"));
    ensureRemoteDirectoryExists("newdir");
    assertTrue(share.folderExists("newdir"));
  }

  @Test
  public void ensureRemoteDirectory_existingIsNoop() throws Exception {
    ensureRemoteDirectoryExists("existdir");
    assertTrue(share.folderExists("existdir"));
    // Should not throw
    ensureRemoteDirectoryExists("existdir");
    assertTrue(share.folderExists("existdir"));
  }

  @Test
  public void listRemoteFiles_returnsUploadedFiles() throws Exception {
    writeRemoteFile("list1.txt", "a");
    writeRemoteFile("list2.txt", "b");
    ensureRemoteDirectoryExists("listdir");

    List<FileIdBothDirectoryInformation> files = share.list("");
    Set<String> names = new HashSet<>();
    for (FileIdBothDirectoryInformation f : files) {
      String name = f.getFileName();
      if (!".".equals(name) && !"..".equals(name)) names.add(name);
    }

    assertTrue(names.contains("list1.txt"));
    assertTrue(names.contains("list2.txt"));
    assertTrue(names.contains("listdir"));
  }

  // ===== EDGE CASES =====

  @Test
  public void emptyFile_syncCorrectly() throws Exception {
    Path emptyFile = localTempDir.resolve("empty.txt");
    Files.write(emptyFile, new byte[0]);

    uploadFile(emptyFile, "empty.txt");
    assertTrue(share.fileExists("empty.txt"));
    assertEquals(0, getRemoteFileSize("empty.txt"));

    Path downloaded = localTempDir.resolve("empty-dl.txt");
    downloadFile("empty.txt", downloaded);
    assertEquals(0, Files.size(downloaded));
  }

  @Test
  public void largeFile_syncCorrectly() throws Exception {
    byte[] largeContent = new byte[1_000_000]; // 1 MB
    for (int i = 0; i < largeContent.length; i++) largeContent[i] = (byte) (i % 256);

    Path largeFile = localTempDir.resolve("large.bin");
    Files.write(largeFile, largeContent);

    uploadFile(largeFile, "large.bin");
    assertEquals(largeContent.length, getRemoteFileSize("large.bin"));

    Path downloaded = localTempDir.resolve("large-dl.bin");
    downloadFile("large.bin", downloaded);
    assertArrayEquals(largeContent, Files.readAllBytes(downloaded));
  }

  @Test
  public void overwriteExistingFile_replacesContent() throws Exception {
    writeRemoteFile("overwrite.txt", "original");
    assertEquals("original", readRemoteFile("overwrite.txt"));

    Path localFile = localTempDir.resolve("overwrite.txt");
    Files.write(localFile, "replaced".getBytes(UTF_8));
    uploadFile(localFile, "overwrite.txt");

    assertEquals("replaced", readRemoteFile("overwrite.txt"));
  }

  @Test
  public void multipleFilesInDirectory_allSynced() throws Exception {
    ensureRemoteDirectoryExists("multi");
    for (int i = 0; i < 5; i++) {
      Path f = localTempDir.resolve("file" + i + ".txt");
      Files.write(f, ("content " + i).getBytes(UTF_8));
      uploadFile(f, "multi\\file" + i + ".txt");
    }

    for (int i = 0; i < 5; i++) {
      assertTrue(share.fileExists("multi\\file" + i + ".txt"));
      assertEquals("content " + i, readRemoteFile("multi\\file" + i + ".txt"));
    }
  }

  // ===== COMPLEX / DEEP FOLDER STRUCTURES =====

  @Test
  public void localToRemote_complexTree_allFilesAndFoldersCreated() throws Exception {
    // Build a complex local tree:
    // project/
    //   README.md
    //   src/
    //     main/
    //       App.java
    //       util/
    //         Helper.java
    //     test/
    //       AppTest.java
    //   docs/
    //     guide.txt
    //     images/
    //       logo.png
    String[][] structure = {
      {"project/README.md", "# Project"},
      {"project/src/main/App.java", "class App {}"},
      {"project/src/main/util/Helper.java", "class Helper {}"},
      {"project/src/test/AppTest.java", "class AppTest {}"},
      {"project/docs/guide.txt", "User Guide"},
      {"project/docs/images/logo.png", "fake-png-data"},
    };

    for (String[] entry : structure) {
      Path localFile = localTempDir.resolve(entry[0].replace('/', java.io.File.separatorChar));
      Files.createDirectories(localFile.getParent());
      Files.write(localFile, entry[1].getBytes(UTF_8));
      String remotePath = entry[0].replace('/', '\\');
      String remoteDir = remotePath.substring(0, remotePath.lastIndexOf('\\'));
      ensureRemoteDirectoryExists(remoteDir);
      uploadFile(localFile, remotePath);
    }

    // Verify all directories exist
    assertTrue(share.folderExists("project"));
    assertTrue(share.folderExists("project\\src"));
    assertTrue(share.folderExists("project\\src\\main"));
    assertTrue(share.folderExists("project\\src\\main\\util"));
    assertTrue(share.folderExists("project\\src\\test"));
    assertTrue(share.folderExists("project\\docs"));
    assertTrue(share.folderExists("project\\docs\\images"));

    // Verify all files exist with correct content
    for (String[] entry : structure) {
      String remotePath = entry[0].replace('/', '\\');
      assertTrue("File should exist: " + remotePath, share.fileExists(remotePath));
      assertEquals("Content mismatch: " + remotePath, entry[1], readRemoteFile(remotePath));
    }
  }

  @Test
  public void remoteToLocal_complexTree_allFilesDownloaded() throws Exception {
    // Create complex remote tree
    String[][] structure = {
      {"data/config.json", "{\"key\":\"value\"}"},
      {"data/logs/app.log", "log entry 1"},
      {"data/logs/archive/old.log", "old log"},
      {"data/cache/temp/session.dat", "session-data"},
      {"data/cache/index.db", "index-content"},
    };

    for (String[] entry : structure) {
      String remotePath = entry[0].replace('/', '\\');
      String remoteDir = remotePath.substring(0, remotePath.lastIndexOf('\\'));
      ensureRemoteDirectoryExists(remoteDir);
      writeRemoteFile(remotePath, entry[1]);
    }

    // Download all files
    for (String[] entry : structure) {
      String remotePath = entry[0].replace('/', '\\');
      Path localFile = localTempDir.resolve(entry[0].replace('/', java.io.File.separatorChar));
      Files.createDirectories(localFile.getParent());
      downloadFile(remotePath, localFile);
    }

    // Verify all files downloaded with correct content
    for (String[] entry : structure) {
      Path localFile = localTempDir.resolve(entry[0].replace('/', java.io.File.separatorChar));
      assertTrue("Local file should exist: " + entry[0], Files.exists(localFile));
      assertEquals("Content mismatch: " + entry[0],
          entry[1], new String(Files.readAllBytes(localFile), UTF_8));
    }
  }

  @Test
  public void bidirectional_complexTree_mergesBothSides() throws Exception {
    // Local-only subtree
    String[][] localOnly = {
      {"shared/local/report.txt", "local report"},
      {"shared/local/data/stats.csv", "a,b,c"},
    };
    for (String[] entry : localOnly) {
      Path localFile = localTempDir.resolve(entry[0].replace('/', java.io.File.separatorChar));
      Files.createDirectories(localFile.getParent());
      Files.write(localFile, entry[1].getBytes(UTF_8));
    }

    // Remote-only subtree
    String[][] remoteOnly = {
      {"shared/remote/backup.zip", "fake-zip"},
      {"shared/remote/meta/info.xml", "<info/>"},
    };
    for (String[] entry : remoteOnly) {
      String remotePath = entry[0].replace('/', '\\');
      String remoteDir = remotePath.substring(0, remotePath.lastIndexOf('\\'));
      ensureRemoteDirectoryExists(remoteDir);
      writeRemoteFile(remotePath, entry[1]);
    }

    // Sync local→remote
    for (String[] entry : localOnly) {
      Path localFile = localTempDir.resolve(entry[0].replace('/', java.io.File.separatorChar));
      String remotePath = entry[0].replace('/', '\\');
      String remoteDir = remotePath.substring(0, remotePath.lastIndexOf('\\'));
      ensureRemoteDirectoryExists(remoteDir);
      uploadFile(localFile, remotePath);
    }

    // Sync remote→local
    for (String[] entry : remoteOnly) {
      String remotePath = entry[0].replace('/', '\\');
      Path localFile = localTempDir.resolve(entry[0].replace('/', java.io.File.separatorChar));
      Files.createDirectories(localFile.getParent());
      downloadFile(remotePath, localFile);
    }

    // Verify all local-only files are now on remote
    for (String[] entry : localOnly) {
      String remotePath = entry[0].replace('/', '\\');
      assertTrue("Remote should have: " + remotePath, share.fileExists(remotePath));
      assertEquals(entry[1], readRemoteFile(remotePath));
    }

    // Verify all remote-only files are now local
    for (String[] entry : remoteOnly) {
      Path localFile = localTempDir.resolve(entry[0].replace('/', java.io.File.separatorChar));
      assertTrue("Local should have: " + entry[0], Files.exists(localFile));
      assertEquals(entry[1], new String(Files.readAllBytes(localFile), UTF_8));
    }
  }

  @Test
  public void deepNesting_7levels_createsAndSyncsCorrectly() throws Exception {
    // 7 levels deep: l1/l2/l3/l4/l5/l6/l7/deep.txt
    String deepPath = "l1/l2/l3/l4/l5/l6/l7";
    Path localDeep = localTempDir.resolve(deepPath.replace('/', java.io.File.separatorChar));
    Files.createDirectories(localDeep);
    Path deepFile = localDeep.resolve("deep.txt");
    Files.write(deepFile, "7 levels deep".getBytes(UTF_8));

    String remoteDir = deepPath.replace('/', '\\');
    ensureRemoteDirectoryExists(remoteDir);
    uploadFile(deepFile, remoteDir + "\\deep.txt");

    // Verify each level exists
    String[] levels = {"l1", "l1\\l2", "l1\\l2\\l3", "l1\\l2\\l3\\l4",
        "l1\\l2\\l3\\l4\\l5", "l1\\l2\\l3\\l4\\l5\\l6", "l1\\l2\\l3\\l4\\l5\\l6\\l7"};
    for (String level : levels) {
      assertTrue("Folder should exist: " + level, share.folderExists(level));
    }
    assertEquals("7 levels deep", readRemoteFile(remoteDir + "\\deep.txt"));

    // Download back and verify round-trip
    Path downloadedFile = localTempDir.resolve("downloaded-deep.txt");
    downloadFile(remoteDir + "\\deep.txt", downloadedFile);
    assertEquals("7 levels deep", new String(Files.readAllBytes(downloadedFile), UTF_8));
  }

  @Test
  public void parallelSubdirectories_multipleFilesPerLevel() throws Exception {
    // Tree with parallel branches and multiple files per directory:
    // root/
    //   alpha/
    //     a1.txt, a2.txt
    //     sub/
    //       as1.txt
    //   beta/
    //     b1.txt
    //     sub/
    //       bs1.txt, bs2.txt
    //   gamma/
    //     g1.txt
    String[][] structure = {
      {"root/alpha/a1.txt", "alpha-1"},
      {"root/alpha/a2.txt", "alpha-2"},
      {"root/alpha/sub/as1.txt", "alpha-sub-1"},
      {"root/beta/b1.txt", "beta-1"},
      {"root/beta/sub/bs1.txt", "beta-sub-1"},
      {"root/beta/sub/bs2.txt", "beta-sub-2"},
      {"root/gamma/g1.txt", "gamma-1"},
    };

    // Upload all
    for (String[] entry : structure) {
      Path localFile = localTempDir.resolve(entry[0].replace('/', java.io.File.separatorChar));
      Files.createDirectories(localFile.getParent());
      Files.write(localFile, entry[1].getBytes(UTF_8));
      String remotePath = entry[0].replace('/', '\\');
      String remoteDir = remotePath.substring(0, remotePath.lastIndexOf('\\'));
      ensureRemoteDirectoryExists(remoteDir);
      uploadFile(localFile, remotePath);
    }

    // Verify parallel branches
    assertTrue(share.folderExists("root\\alpha"));
    assertTrue(share.folderExists("root\\beta"));
    assertTrue(share.folderExists("root\\gamma"));
    assertTrue(share.folderExists("root\\alpha\\sub"));
    assertTrue(share.folderExists("root\\beta\\sub"));

    // Verify all files
    for (String[] entry : structure) {
      String remotePath = entry[0].replace('/', '\\');
      assertEquals("Content mismatch: " + remotePath, entry[1], readRemoteFile(remotePath));
    }

    // Verify file counts per directory via listing
    List<FileIdBothDirectoryInformation> alphaFiles = share.list("root\\alpha");
    Set<String> alphaNames = new HashSet<>();
    for (FileIdBothDirectoryInformation f : alphaFiles) {
      String name = f.getFileName();
      if (!".".equals(name) && !"..".equals(name)) alphaNames.add(name);
    }
    assertTrue(alphaNames.contains("a1.txt"));
    assertTrue(alphaNames.contains("a2.txt"));
    assertTrue(alphaNames.contains("sub"));
    assertEquals(3, alphaNames.size());
  }

  @Test
  public void mixedContentTree_filesAndEmptyDirsCoexist() throws Exception {
    // Directory with both files and subdirectories at each level
    ensureRemoteDirectoryExists("mix");
    ensureRemoteDirectoryExists("mix\\emptydir");
    ensureRemoteDirectoryExists("mix\\subdir");
    writeRemoteFile("mix\\file1.txt", "content1");
    writeRemoteFile("mix\\subdir\\file2.txt", "content2");

    // Verify structure
    assertTrue(share.folderExists("mix\\emptydir"));
    assertTrue(share.folderExists("mix\\subdir"));
    assertTrue(share.fileExists("mix\\file1.txt"));
    assertTrue(share.fileExists("mix\\subdir\\file2.txt"));

    // List root of mix — should contain emptydir, subdir, file1.txt
    List<FileIdBothDirectoryInformation> mixFiles = share.list("mix");
    Set<String> names = new HashSet<>();
    for (FileIdBothDirectoryInformation f : mixFiles) {
      String name = f.getFileName();
      if (!".".equals(name) && !"..".equals(name)) names.add(name);
    }
    assertEquals(3, names.size());
    assertTrue(names.contains("emptydir"));
    assertTrue(names.contains("subdir"));
    assertTrue(names.contains("file1.txt"));

    // Download all files to local
    Path localMix = localTempDir.resolve("mix");
    Files.createDirectories(localMix.resolve("subdir"));
    downloadFile("mix\\file1.txt", localMix.resolve("file1.txt"));
    downloadFile("mix\\subdir\\file2.txt", localMix.resolve("subdir").resolve("file2.txt"));

    assertEquals("content1", new String(Files.readAllBytes(localMix.resolve("file1.txt")), UTF_8));
    assertEquals("content2",
        new String(Files.readAllBytes(localMix.resolve("subdir").resolve("file2.txt")), UTF_8));
  }

  @Test
  public void syncTreeWithTimestampComparison_onlyNewerFilesSync() throws Exception {
    // Create a tree where some files are newer locally, some remotely
    long now = System.currentTimeMillis();
    long old = now - 120_000;

    // Local files: fileA is new, fileB is old
    Path dirLocal = localTempDir.resolve("tsync");
    Files.createDirectories(dirLocal);
    Path fileA = dirLocal.resolve("fileA.txt");
    Path fileB = dirLocal.resolve("fileB.txt");
    Files.write(fileA, "local-A-new".getBytes(UTF_8));
    fileA.toFile().setLastModified(now);
    Files.write(fileB, "local-B-old".getBytes(UTF_8));
    fileB.toFile().setLastModified(old);

    // Remote files: fileA is old, fileB is new
    ensureRemoteDirectoryExists("tsync");
    writeRemoteFile("tsync\\fileA.txt", "remote-A-old");
    setRemoteLastModified("tsync\\fileA.txt", old);
    writeRemoteFile("tsync\\fileB.txt", "remote-B-new");
    setRemoteLastModified("tsync\\fileB.txt", now);

    // Sync logic: upload fileA (local newer), download fileB (remote newer)
    long localModA = fileA.toFile().lastModified();
    long remoteModA = getRemoteLastModified("tsync\\fileA.txt");
    assertTrue("Local A should be newer", syncComparator.isLocalNewer(localModA, remoteModA));

    long localModB = fileB.toFile().lastModified();
    long remoteModB = getRemoteLastModified("tsync\\fileB.txt");
    assertTrue("Remote B should be newer", syncComparator.isRemoteNewer(localModB, remoteModB));

    // Execute sync
    uploadFile(fileA, "tsync\\fileA.txt");
    downloadFile("tsync\\fileB.txt", fileB);

    // Verify
    assertEquals("local-A-new", readRemoteFile("tsync\\fileA.txt"));
    assertEquals("remote-B-new", new String(Files.readAllBytes(fileB), UTF_8));
  }

  // ===== Helper methods (mirror FolderSyncWorker logic) =====

  private void uploadFile(Path localFile, String remotePath) throws Exception {
    try (File remoteFile =
            share.openFile(
                remotePath,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null);
        InputStream is = Files.newInputStream(localFile);
        OutputStream os = remoteFile.getOutputStream()) {
      byte[] buffer = new byte[8192];
      int bytesRead;
      while ((bytesRead = is.read(buffer)) != -1) {
        os.write(buffer, 0, bytesRead);
      }
    }

    // Integrity check (same as FolderSyncWorker)
    long remoteSize = getRemoteFileSize(remotePath);
    long localSize = Files.size(localFile);
    if (remoteSize >= 0 && localSize >= 0 && remoteSize != localSize) {
      throw new Exception(
          "Upload integrity check failed: remoteSize=" + remoteSize + " localSize=" + localSize);
    }

    // Set remote timestamp to match local (same as FolderSyncWorker)
    setRemoteLastModified(remotePath, localFile.toFile().lastModified());
  }

  private void downloadFile(String remotePath, Path localFile) throws Exception {
    try (File remoteFile =
            share.openFile(
                remotePath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null);
        InputStream is = remoteFile.getInputStream();
        OutputStream os = Files.newOutputStream(localFile)) {
      byte[] buffer = new byte[8192];
      int bytesRead;
      while ((bytesRead = is.read(buffer)) != -1) {
        os.write(buffer, 0, bytesRead);
      }
    }
  }

  private void writeRemoteFile(String remotePath, String content) throws Exception {
    try (File remoteFile =
            share.openFile(
                remotePath,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null);
        OutputStream os = remoteFile.getOutputStream()) {
      os.write(content.getBytes(UTF_8));
    }
  }

  private String readRemoteFile(String remotePath) throws Exception {
    try (File remoteFile =
            share.openFile(
                remotePath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null);
        InputStream is = remoteFile.getInputStream()) {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int bytesRead;
      while ((bytesRead = is.read(buffer)) != -1) {
        baos.write(buffer, 0, bytesRead);
      }
      return baos.toString(UTF_8);
    }
  }

  private void setRemoteLastModified(String remotePath, long timeMillis) throws Exception {
    try (File remoteFile =
        share.openFile(
            remotePath,
            EnumSet.of(AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_WRITE_ATTRIBUTES),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null)) {
      FileBasicInformation currentInfo = remoteFile.getFileInformation().getBasicInformation();
      FileTime newTime = FileTime.ofEpochMillis(timeMillis);
      FileBasicInformation newInfo =
          new FileBasicInformation(
              currentInfo.getCreationTime(),
              currentInfo.getLastAccessTime(),
              newTime,
              currentInfo.getChangeTime(),
              currentInfo.getFileAttributes());
      remoteFile.setFileInformation(newInfo);
    }
  }

  private long getRemoteLastModified(String remotePath) throws Exception {
    try (File remoteFile =
        share.openFile(
            remotePath,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null)) {
      return remoteFile
          .getFileInformation()
          .getBasicInformation()
          .getLastWriteTime()
          .toEpochMillis();
    }
  }

  private long getRemoteFileSize(String remotePath) throws Exception {
    try (File remoteFile =
        share.openFile(
            remotePath,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null)) {
      return remoteFile.getFileInformation().getStandardInformation().getEndOfFile();
    }
  }

  private void ensureRemoteDirectoryExists(String path) {
    String smbPath = path.replace('/', '\\');
    String[] parts = smbPath.split("\\\\");
    StringBuilder current = new StringBuilder();
    for (String part : parts) {
      if (part.isEmpty()) continue;
      if (current.length() > 0) current.append("\\");
      current.append(part);
      String dirPath = current.toString();
      if (!share.folderExists(dirPath)) {
        share.mkdir(dirPath);
      }
    }
  }

  private void deleteDirectory(Path dir) throws Exception {
    if (Files.exists(dir)) {
      java.io.File[] files = dir.toFile().listFiles();
      if (files != null) {
        for (java.io.File f : files) {
          if (f.isDirectory()) {
            deleteDirectory(f.toPath());
          } else {
            f.delete();
          }
        }
      }
      dir.toFile().delete();
    }
  }
}
