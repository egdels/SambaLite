/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.transfer;

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
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for transfer operations (upload/download) against a real Docker Samba Server.
 *
 * <p>Tests the core SMB transfer logic used by TransferWorker: upload with resume support,
 * download with resume, directory creation, timestamp preservation, large file handling,
 * and concurrent transfers.
 */
public class TransferIntegrationTest {

  private SambaContainer sambaContainer;
  private SMBClient smbClient;
  private Connection connection;
  private Session session;
  private DiskShare share;
  private Path localTempDir;

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

    localTempDir = Files.createTempDirectory("transfer-integration-test");
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

  // ===== UPLOAD TESTS =====

  @Test
  public void upload_simpleFile_contentMatchesAfterUpload() throws Exception {
    Path localFile = localTempDir.resolve("simple.txt");
    Files.write(localFile, "Hello Transfer".getBytes(UTF_8));

    uploadFile(localFile, "simple.txt");

    assertTrue(share.fileExists("simple.txt"));
    assertEquals("Hello Transfer", readRemoteFile("simple.txt"));
  }

  @Test
  public void upload_intoNestedDirectory_createsParentsAndUploads() throws Exception {
    Path localFile = localTempDir.resolve("deep.txt");
    Files.write(localFile, "deep content".getBytes(UTF_8));

    ensureRemoteDirectoryExists("a/b/c");
    uploadFile(localFile, "a\\b\\c\\deep.txt");

    assertTrue(share.fileExists("a\\b\\c\\deep.txt"));
    assertEquals("deep content", readRemoteFile("a\\b\\c\\deep.txt"));
  }

  @Test
  public void upload_overwriteExistingFile_replacesContent() throws Exception {
    writeRemoteFile("overwrite.txt", "old content");

    Path localFile = localTempDir.resolve("overwrite.txt");
    Files.write(localFile, "new content".getBytes(UTF_8));
    uploadFile(localFile, "overwrite.txt");

    assertEquals("new content", readRemoteFile("overwrite.txt"));
  }

  @Test
  public void upload_emptyFile_createsZeroByteRemoteFile() throws Exception {
    Path localFile = localTempDir.resolve("empty.txt");
    Files.write(localFile, new byte[0]);

    uploadFile(localFile, "empty.txt");

    assertTrue(share.fileExists("empty.txt"));
    assertEquals(0, getRemoteFileSize("empty.txt"));
  }

  @Test
  public void upload_largeFile_1MB_integrityPreserved() throws Exception {
    byte[] data = new byte[1024 * 1024];
    new Random(42).nextBytes(data);
    Path localFile = localTempDir.resolve("large.bin");
    Files.write(localFile, data);

    uploadFile(localFile, "large.bin");

    assertEquals(data.length, getRemoteFileSize("large.bin"));
    byte[] downloaded = readRemoteFileBytes("large.bin");
    assertArrayEquals(data, downloaded);
  }

  @Test
  public void upload_resumeAfterPartialTransfer_completesFile() throws Exception {
    byte[] fullData = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes(UTF_8);

    // Simulate partial upload: write first 10 bytes
    try (File remoteFile =
        share.openFile(
            "resume.txt",
            EnumSet.of(AccessMask.GENERIC_WRITE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            null)) {
      OutputStream os = remoteFile.getOutputStream();
      os.write(fullData, 0, 10);
      os.flush();
    }

    long partialSize = getRemoteFileSize("resume.txt");
    assertEquals(10, partialSize);

    // Now "resume" by appending remaining bytes
    try (File remoteFile =
        share.openFile(
            "resume.txt",
            EnumSet.of(AccessMask.GENERIC_WRITE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN_IF,
            null)) {
      OutputStream os = remoteFile.getOutputStream(true);
      os.write(fullData, 10, fullData.length - 10);
      os.flush();
    }

    assertEquals(fullData.length, getRemoteFileSize("resume.txt"));
    assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZ", readRemoteFile("resume.txt"));
  }

  @Test
  public void upload_preservesTimestamp() throws Exception {
    Path localFile = localTempDir.resolve("timestamped.txt");
    Files.write(localFile, "timestamp test".getBytes(UTF_8));
    long localTime = 1700000000000L;
    localFile.toFile().setLastModified(localTime);

    uploadFile(localFile, "timestamped.txt");
    setRemoteLastModified("timestamped.txt", localTime);

    long remoteTime = getRemoteLastModified("timestamped.txt");
    assertTrue(
        "Timestamp should be within 2s tolerance",
        Math.abs(remoteTime - localTime) < 2000);
  }

  // ===== DOWNLOAD TESTS =====

  @Test
  public void download_simpleFile_contentMatches() throws Exception {
    writeRemoteFile("download.txt", "Hello Download");

    Path localFile = localTempDir.resolve("download.txt");
    downloadFile("download.txt", localFile);

    assertEquals("Hello Download", new String(Files.readAllBytes(localFile), UTF_8));
  }

  @Test
  public void download_fromNestedDirectory_succeeds() throws Exception {
    ensureRemoteDirectoryExists("x/y/z");
    writeRemoteFile("x\\y\\z\\nested.txt", "nested content");

    Path localFile = localTempDir.resolve("nested.txt");
    downloadFile("x\\y\\z\\nested.txt", localFile);

    assertEquals("nested content", new String(Files.readAllBytes(localFile), UTF_8));
  }

  @Test
  public void download_largeFile_1MB_integrityPreserved() throws Exception {
    byte[] data = new byte[1024 * 1024];
    new Random(42).nextBytes(data);
    writeRemoteFileBytes("large_dl.bin", data);

    Path localFile = localTempDir.resolve("large_dl.bin");
    downloadFile("large_dl.bin", localFile);

    assertArrayEquals(data, Files.readAllBytes(localFile));
  }

  @Test
  public void download_resumeAfterPartialTransfer_completesFile() throws Exception {
    byte[] fullData = "0123456789ABCDEFGHIJ".getBytes(UTF_8);
    writeRemoteFileBytes("resume_dl.txt", fullData);

    // Simulate partial download: write first 10 bytes locally
    Path localFile = localTempDir.resolve("resume_dl.txt");
    Files.write(localFile, Arrays.copyOf(fullData, 10));
    assertEquals(10, Files.size(localFile));

    // Resume: read remaining bytes from offset 10
    try (File remoteFile =
            share.openFile(
                "resume_dl.txt",
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null);
        InputStream is = remoteFile.getInputStream()) {
      long skipped = is.skip(10);
      assertEquals(10, skipped);

      try (OutputStream os = Files.newOutputStream(localFile, java.nio.file.StandardOpenOption.APPEND)) {
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = is.read(buffer)) != -1) {
          os.write(buffer, 0, bytesRead);
        }
      }
    }

    assertArrayEquals(fullData, Files.readAllBytes(localFile));
  }

  // ===== DIRECTORY CREATION TESTS =====

  @Test
  public void ensureDirectoryExists_createsDeepHierarchy() throws Exception {
    ensureRemoteDirectoryExists("level1/level2/level3/level4/level5");

    assertTrue(share.folderExists("level1"));
    assertTrue(share.folderExists("level1\\level2"));
    assertTrue(share.folderExists("level1\\level2\\level3"));
    assertTrue(share.folderExists("level1\\level2\\level3\\level4"));
    assertTrue(share.folderExists("level1\\level2\\level3\\level4\\level5"));
  }

  @Test
  public void ensureDirectoryExists_idempotent_noErrorOnExisting() throws Exception {
    ensureRemoteDirectoryExists("idempotent/dir");
    ensureRemoteDirectoryExists("idempotent/dir");

    assertTrue(share.folderExists("idempotent\\dir"));
  }

  // ===== CONCURRENT TRANSFER TESTS =====

  @Test
  public void concurrent_multipleUploads_allSucceed() throws Exception {
    int fileCount = 5;
    java.util.concurrent.ExecutorService executor =
        java.util.concurrent.Executors.newFixedThreadPool(fileCount);
    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(fileCount);
    java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger(0);

    for (int i = 0; i < fileCount; i++) {
      final int idx = i;
      executor.submit(() -> {
        try {
          Path localFile = localTempDir.resolve("concurrent_" + idx + ".txt");
          Files.write(localFile, ("content " + idx).getBytes(UTF_8));

          // Each thread needs its own SMB session
          SMBClient client = new SMBClient();
          Connection conn = client.connect(sambaContainer.getHost(), sambaContainer.getPort());
          AuthenticationContext auth =
              new AuthenticationContext("testuser", "testpassword".toCharArray(), "WORKGROUP");
          Session sess = conn.authenticate(auth);
          DiskShare ds = (DiskShare) sess.connectShare("testshare");

          try (File remoteFile =
              ds.openFile(
                  "concurrent_" + idx + ".txt",
                  EnumSet.of(AccessMask.GENERIC_WRITE),
                  EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                  SMB2ShareAccess.ALL,
                  SMB2CreateDisposition.FILE_OVERWRITE_IF,
                  null);
              OutputStream os = remoteFile.getOutputStream();
              InputStream is = Files.newInputStream(localFile)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
              os.write(buffer, 0, bytesRead);
            }
          } finally {
            ds.close();
            sess.close();
            conn.close();
            client.close();
          }
        } catch (Exception e) {
          failures.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
    }

    latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
    executor.shutdown();

    assertEquals("All concurrent uploads should succeed", 0, failures.get());
    for (int i = 0; i < fileCount; i++) {
      assertTrue("File " + i + " should exist", share.fileExists("concurrent_" + i + ".txt"));
      assertEquals("content " + i, readRemoteFile("concurrent_" + i + ".txt"));
    }
  }

  @Test
  public void concurrent_uploadAndDownloadSameFile_noCorruption() throws Exception {
    writeRemoteFile("shared.txt", "original content");

    // Upload new version while downloading old
    Thread uploader = new Thread(() -> {
      try {
        Path localFile = localTempDir.resolve("shared_upload.txt");
        Files.write(localFile, "updated content".getBytes(UTF_8));

        SMBClient client = new SMBClient();
        Connection conn = client.connect(sambaContainer.getHost(), sambaContainer.getPort());
        Session sess = conn.authenticate(
            new AuthenticationContext("testuser", "testpassword".toCharArray(), "WORKGROUP"));
        DiskShare ds = (DiskShare) sess.connectShare("testshare");
        try (File rf = ds.openFile("shared.txt",
            EnumSet.of(AccessMask.GENERIC_WRITE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF, null);
            OutputStream os = rf.getOutputStream();
            InputStream is = Files.newInputStream(localFile)) {
          byte[] buf = new byte[8192];
          int r;
          while ((r = is.read(buf)) != -1) os.write(buf, 0, r);
        } finally {
          ds.close(); sess.close(); conn.close(); client.close();
        }
      } catch (Exception ignored) {}
    });

    Thread downloader = new Thread(() -> {
      try {
        Path localFile = localTempDir.resolve("shared_download.txt");

        SMBClient client = new SMBClient();
        Connection conn = client.connect(sambaContainer.getHost(), sambaContainer.getPort());
        Session sess = conn.authenticate(
            new AuthenticationContext("testuser", "testpassword".toCharArray(), "WORKGROUP"));
        DiskShare ds = (DiskShare) sess.connectShare("testshare");
        try (File rf = ds.openFile("shared.txt",
            EnumSet.of(AccessMask.GENERIC_READ), null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, null);
            InputStream is = rf.getInputStream();
            OutputStream os = Files.newOutputStream(localFile)) {
          byte[] buf = new byte[8192];
          int r;
          while ((r = is.read(buf)) != -1) os.write(buf, 0, r);
        } finally {
          ds.close(); sess.close(); conn.close(); client.close();
        }
      } catch (Exception ignored) {}
    });

    uploader.start();
    downloader.start();
    uploader.join(10000);
    downloader.join(10000);

    // After both complete, file should be readable and consistent
    String finalContent = readRemoteFile("shared.txt");
    assertNotNull("File should be readable after concurrent access", finalContent);
    assertTrue("Content should be either old or new",
        "original content".equals(finalContent) || "updated content".equals(finalContent));
  }

  // ===== BATCH TRANSFER TESTS =====

  @Test
  public void batch_uploadMultipleFiles_allExistOnServer() throws Exception {
    String[] fileNames = {"batch1.txt", "batch2.txt", "batch3.txt", "batch4.txt", "batch5.txt"};
    for (String name : fileNames) {
      Path localFile = localTempDir.resolve(name);
      Files.write(localFile, ("content of " + name).getBytes(UTF_8));
      uploadFile(localFile, name);
    }

    for (String name : fileNames) {
      assertTrue(name + " should exist", share.fileExists(name));
      assertEquals("content of " + name, readRemoteFile(name));
    }
  }

  @Test
  public void batch_downloadMultipleFiles_allMatchContent() throws Exception {
    String[] fileNames = {"dl1.txt", "dl2.txt", "dl3.txt"};
    for (String name : fileNames) {
      writeRemoteFile(name, "remote " + name);
    }

    for (String name : fileNames) {
      Path localFile = localTempDir.resolve(name);
      downloadFile(name, localFile);
      assertEquals("remote " + name, new String(Files.readAllBytes(localFile), UTF_8));
    }
  }

  // ===== UPLOAD FOLDER STRUCTURE =====

  @Test
  public void upload_entireFolderStructure_allFilesAndDirsCreated() throws Exception {
    // Create local folder structure
    Path projectDir = localTempDir.resolve("project");
    Files.createDirectories(projectDir.resolve("src/main"));
    Files.createDirectories(projectDir.resolve("src/test"));
    Files.createDirectories(projectDir.resolve("docs"));
    Files.write(projectDir.resolve("README.md"), "# Project".getBytes(UTF_8));
    Files.write(projectDir.resolve("src/main/App.java"), "class App {}".getBytes(UTF_8));
    Files.write(projectDir.resolve("src/test/AppTest.java"), "class AppTest {}".getBytes(UTF_8));
    Files.write(projectDir.resolve("docs/guide.md"), "Guide".getBytes(UTF_8));

    // Upload recursively
    uploadDirectoryRecursive(projectDir, "project");

    assertTrue(share.folderExists("project"));
    assertTrue(share.folderExists("project\\src"));
    assertTrue(share.folderExists("project\\src\\main"));
    assertTrue(share.folderExists("project\\src\\test"));
    assertTrue(share.folderExists("project\\docs"));
    assertEquals("# Project", readRemoteFile("project\\README.md"));
    assertEquals("class App {}", readRemoteFile("project\\src\\main\\App.java"));
    assertEquals("class AppTest {}", readRemoteFile("project\\src\\test\\AppTest.java"));
    assertEquals("Guide", readRemoteFile("project\\docs\\guide.md"));
  }

  // ===== DOWNLOAD FOLDER STRUCTURE =====

  @Test
  public void download_entireFolderStructure_allFilesDownloaded() throws Exception {
    ensureRemoteDirectoryExists("remote_project/src");
    ensureRemoteDirectoryExists("remote_project/lib");
    writeRemoteFile("remote_project\\build.gradle", "apply plugin: 'java'");
    writeRemoteFile("remote_project\\src\\Main.java", "class Main {}");
    writeRemoteFile("remote_project\\lib\\utils.jar", "fake-jar-content");

    Path downloadDir = localTempDir.resolve("downloaded");
    Files.createDirectories(downloadDir);
    downloadDirectoryRecursive("remote_project", downloadDir.resolve("remote_project"));

    assertTrue(Files.exists(downloadDir.resolve("remote_project/build.gradle")));
    assertTrue(Files.exists(downloadDir.resolve("remote_project/src/Main.java")));
    assertTrue(Files.exists(downloadDir.resolve("remote_project/lib/utils.jar")));
    assertEquals("apply plugin: 'java'",
        new String(Files.readAllBytes(downloadDir.resolve("remote_project/build.gradle")), UTF_8));
  }

  // ===== Helper methods =====

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

  private void writeRemoteFileBytes(String remotePath, byte[] data) throws Exception {
    try (File remoteFile =
            share.openFile(
                remotePath,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null);
        OutputStream os = remoteFile.getOutputStream()) {
      os.write(data);
    }
  }

  private String readRemoteFile(String remotePath) throws Exception {
    return new String(readRemoteFileBytes(remotePath), UTF_8);
  }

  private byte[] readRemoteFileBytes(String remotePath) throws Exception {
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
      return baos.toByteArray();
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

  private long getRemoteLastModified(String remotePath) throws Exception {
    try (File remoteFile =
        share.openFile(
            remotePath,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null)) {
      return remoteFile.getFileInformation().getBasicInformation().getLastWriteTime().toEpochMillis();
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

  private void uploadDirectoryRecursive(Path localDir, String remotePath) throws Exception {
    ensureRemoteDirectoryExists(remotePath);
    java.io.File[] files = localDir.toFile().listFiles();
    if (files == null) return;
    for (java.io.File f : files) {
      String remoteChild = remotePath + "\\" + f.getName();
      if (f.isDirectory()) {
        uploadDirectoryRecursive(f.toPath(), remoteChild);
      } else {
        uploadFile(f.toPath(), remoteChild);
      }
    }
  }

  private void downloadDirectoryRecursive(String remotePath, Path localDir) throws Exception {
    Files.createDirectories(localDir);
    for (FileIdBothDirectoryInformation info : share.list(remotePath)) {
      String name = info.getFileName();
      if (".".equals(name) || "..".equals(name)) continue;
      String remoteChild = remotePath + "\\" + name;
      boolean isDir =
          (info.getFileAttributes()
                  & com.hierynomus.msfscc.FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue())
              != 0;
      if (isDir) {
        downloadDirectoryRecursive(remoteChild, localDir.resolve(name));
      } else {
        downloadFile(remoteChild, localDir.resolve(name));
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
