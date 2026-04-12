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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.*;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
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
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for error recovery, edge cases, and robustness against a real Docker Samba
 * Server.
 *
 * <p>Tests authentication failures, invalid paths, long filenames, special characters,
 * reconnection after session close, non-existent file access, and boundary conditions.
 */
public class ErrorRecoveryIntegrationTest {

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

    localTempDir = Files.createTempDirectory("error-recovery-test");
  }

  @After
  public void tearDown() throws Exception {
    if (share != null) {
      try { share.close(); } catch (Exception ignored) {}
    }
    if (session != null) {
      try { session.close(); } catch (Exception ignored) {}
    }
    if (connection != null) {
      try { connection.close(); } catch (Exception ignored) {}
    }
    if (smbClient != null) {
      try { smbClient.close(); } catch (Exception ignored) {}
    }
    if (sambaContainer != null) sambaContainer.stop();
    if (localTempDir != null) deleteDirectory(localTempDir);
  }

  // ===== AUTHENTICATION TESTS =====

  @Test(expected = Exception.class)
  public void auth_wrongPassword_throwsException() throws Exception {
    SMBClient client = new SMBClient();
    try (Connection conn = client.connect(sambaContainer.getHost(), sambaContainer.getPort())) {
      AuthenticationContext badAuth =
          new AuthenticationContext("testuser", "wrongpassword".toCharArray(), "WORKGROUP");
      Session sess = conn.authenticate(badAuth);
      // Attempt to access share — this should fail
      DiskShare ds = (DiskShare) sess.connectShare("testshare");
      ds.list("");
      ds.close();
      sess.close();
    } finally {
      client.close();
    }
  }

  @Test(expected = Exception.class)
  public void auth_wrongUsername_throwsException() throws Exception {
    SMBClient client = new SMBClient();
    try (Connection conn = client.connect(sambaContainer.getHost(), sambaContainer.getPort())) {
      AuthenticationContext badAuth =
          new AuthenticationContext("nonexistent", "testpassword".toCharArray(), "WORKGROUP");
      Session sess = conn.authenticate(badAuth);
      DiskShare ds = (DiskShare) sess.connectShare("testshare");
      ds.list("");
      ds.close();
      sess.close();
    } finally {
      client.close();
    }
  }

  @Test
  public void auth_correctCredentials_succeeds() throws Exception {
    SMBClient client = new SMBClient();
    try (Connection conn = client.connect(sambaContainer.getHost(), sambaContainer.getPort())) {
      AuthenticationContext auth =
          new AuthenticationContext("testuser", "testpassword".toCharArray(), "WORKGROUP");
      Session sess = conn.authenticate(auth);
      DiskShare ds = (DiskShare) sess.connectShare("testshare");
      // Should not throw
      ds.list("");
      ds.close();
      sess.close();
    } finally {
      client.close();
    }
  }

  // ===== NON-EXISTENT FILE/DIRECTORY ACCESS =====

  @Test
  public void access_nonExistentFile_fileExistsReturnsFalse() throws Exception {
    assertFalse(share.fileExists("does_not_exist.txt"));
  }

  @Test
  public void access_nonExistentDirectory_folderExistsReturnsFalse() throws Exception {
    assertFalse(share.folderExists("no_such_dir"));
  }

  @Test(expected = Exception.class)
  public void download_nonExistentFile_throwsException() throws Exception {
    share.openFile(
        "ghost.txt",
        EnumSet.of(AccessMask.GENERIC_READ),
        null,
        SMB2ShareAccess.ALL,
        SMB2CreateDisposition.FILE_OPEN,
        null);
  }

  @Test(expected = Exception.class)
  public void list_nonExistentDirectory_throwsException() throws Exception {
    share.list("nonexistent_dir");
  }

  // ===== RECONNECTION TESTS =====

  @Test
  public void reconnect_afterSessionClose_canAccessShareAgain() throws Exception {
    writeRemoteFile("before_reconnect.txt", "data");

    // Close current session
    share.close();
    session.close();
    connection.close();
    smbClient.close();

    // Reconnect
    smbClient = new SMBClient();
    connection = smbClient.connect(sambaContainer.getHost(), sambaContainer.getPort());
    AuthenticationContext auth =
        new AuthenticationContext("testuser", "testpassword".toCharArray(), "WORKGROUP");
    session = connection.authenticate(auth);
    share = (DiskShare) session.connectShare("testshare");

    // Verify data persists
    assertTrue(share.fileExists("before_reconnect.txt"));
    assertEquals("data", readRemoteFile("before_reconnect.txt"));
  }

  @Test
  public void reconnect_multipleTimesInSequence_allSucceed() throws Exception {
    for (int i = 0; i < 3; i++) {
      writeRemoteFile("reconnect_" + i + ".txt", "iteration " + i);

      share.close();
      session.close();
      connection.close();
      smbClient.close();

      smbClient = new SMBClient();
      connection = smbClient.connect(sambaContainer.getHost(), sambaContainer.getPort());
      AuthenticationContext auth =
          new AuthenticationContext("testuser", "testpassword".toCharArray(), "WORKGROUP");
      session = connection.authenticate(auth);
      share = (DiskShare) session.connectShare("testshare");
    }

    for (int i = 0; i < 3; i++) {
      assertTrue(share.fileExists("reconnect_" + i + ".txt"));
      assertEquals("iteration " + i, readRemoteFile("reconnect_" + i + ".txt"));
    }
  }

  // ===== SPECIAL CHARACTER EDGE CASES =====

  @Test
  public void file_withSpacesInName_uploadAndDownload() throws Exception {
    writeRemoteFile("file with spaces.txt", "spaced content");

    assertTrue(share.fileExists("file with spaces.txt"));
    assertEquals("spaced content", readRemoteFile("file with spaces.txt"));
  }

  @Test
  public void file_withHyphensDotsUnderscores_uploadAndDownload() throws Exception {
    writeRemoteFile("my-file_v2.0.final.txt", "versioned");

    assertTrue(share.fileExists("my-file_v2.0.final.txt"));
    assertEquals("versioned", readRemoteFile("my-file_v2.0.final.txt"));
  }

  @Test
  public void file_withParentheses_uploadAndDownload() throws Exception {
    writeRemoteFile("document (1).txt", "copy");

    assertTrue(share.fileExists("document (1).txt"));
    assertEquals("copy", readRemoteFile("document (1).txt"));
  }

  @Test
  public void file_withMultipleDots_uploadAndDownload() throws Exception {
    writeRemoteFile("archive.tar.gz", "compressed");

    assertTrue(share.fileExists("archive.tar.gz"));
    assertEquals("compressed", readRemoteFile("archive.tar.gz"));
  }

  @Test
  public void file_withUmlauts_uploadAndDownload() throws Exception {
    writeRemoteFile("Übersicht.txt", "Ä Ö Ü ä ö ü ß");

    assertTrue(share.fileExists("Übersicht.txt"));
    assertEquals("Ä Ö Ü ä ö ü ß", readRemoteFile("Übersicht.txt"));
  }

  @Test
  public void directory_withSpaces_createAndList() throws Exception {
    ensureRemoteDirectoryExists("My Documents");
    writeRemoteFile("My Documents\\file.txt", "in spaced dir");

    assertTrue(share.folderExists("My Documents"));
    assertEquals("in spaced dir", readRemoteFile("My Documents\\file.txt"));
  }

  // ===== LONG PATH EDGE CASES =====

  @Test
  public void file_withLongName_200chars_uploadAndDownload() throws Exception {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 196; i++) sb.append('a');
    String longName = sb + ".txt";

    writeRemoteFile(longName, "long name content");

    assertTrue(share.fileExists(longName));
    assertEquals("long name content", readRemoteFile(longName));
  }

  @Test
  public void directory_deepNesting_10levels_createAndAccess() throws Exception {
    StringBuilder pathBuilder = new StringBuilder("d1");
    for (int i = 2; i <= 10; i++) {
      pathBuilder.append("/d").append(i);
    }
    ensureRemoteDirectoryExists(pathBuilder.toString());
    String smbPath = pathBuilder.toString().replace('/', '\\');
    writeRemoteFile(smbPath + "\\deep.txt", "very deep");

    assertEquals("very deep", readRemoteFile(smbPath + "\\deep.txt"));
  }

  // ===== BOUNDARY CONDITIONS =====

  @Test
  public void file_exactlyOneByte_uploadAndDownload() throws Exception {
    writeRemoteFileBytes("onebyte.bin", new byte[]{0x42});

    assertEquals(1, getRemoteFileSize("onebyte.bin"));
    byte[] downloaded = readRemoteFileBytes("onebyte.bin");
    assertEquals(1, downloaded.length);
    assertEquals(0x42, downloaded[0]);
  }

  @Test
  public void file_zeroBytes_uploadAndDownload() throws Exception {
    writeRemoteFileBytes("zero.bin", new byte[0]);

    assertTrue(share.fileExists("zero.bin"));
    assertEquals(0, getRemoteFileSize("zero.bin"));
  }

  @Test
  public void file_binaryContent_preservesAllByteValues() throws Exception {
    byte[] allBytes = new byte[256];
    for (int i = 0; i < 256; i++) allBytes[i] = (byte) i;

    writeRemoteFileBytes("allbytes.bin", allBytes);

    byte[] downloaded = readRemoteFileBytes("allbytes.bin");
    assertArrayEquals(allBytes, downloaded);
  }

  @Test
  public void file_5MB_uploadAndDownloadIntegrity() throws Exception {
    byte[] data = new byte[5 * 1024 * 1024];
    new Random(123).nextBytes(data);
    Path localFile = localTempDir.resolve("5mb.bin");
    Files.write(localFile, data);

    // Upload
    try (File remoteFile =
            share.openFile(
                "5mb.bin",
                EnumSet.of(AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null);
        InputStream is = Files.newInputStream(localFile);
        OutputStream os = remoteFile.getOutputStream()) {
      byte[] buffer = new byte[65536];
      int bytesRead;
      while ((bytesRead = is.read(buffer)) != -1) {
        os.write(buffer, 0, bytesRead);
      }
    }

    assertEquals(data.length, getRemoteFileSize("5mb.bin"));

    // Download and verify
    byte[] downloaded = readRemoteFileBytes("5mb.bin");
    assertArrayEquals(data, downloaded);
  }

  @Test
  public void file_whitespaceOnlyContent_preservedCorrectly() throws Exception {
    String whitespace = "   \t\n\r\n   ";
    writeRemoteFile("whitespace.txt", whitespace);

    assertEquals(whitespace, readRemoteFile("whitespace.txt"));
  }

  // ===== OVERWRITE AND DELETE EDGE CASES =====

  @Test
  public void overwrite_existingFile_withSmallerContent_truncatesCorrectly() throws Exception {
    writeRemoteFile("shrink.txt", "This is a long content string that will be replaced");
    writeRemoteFile("shrink.txt", "short");

    assertEquals("short", readRemoteFile("shrink.txt"));
    assertEquals(5, getRemoteFileSize("shrink.txt"));
  }

  @Test
  public void overwrite_existingFile_withLargerContent_growsCorrectly() throws Exception {
    writeRemoteFile("grow.txt", "small");
    writeRemoteFile("grow.txt", "This is a much larger content string that replaces the small one");

    String content = readRemoteFile("grow.txt");
    assertTrue(content.startsWith("This is a much larger"));
  }

  @Test
  public void delete_file_thenRecreate_succeeds() throws Exception {
    writeRemoteFile("recreate.txt", "version 1");
    share.rm("recreate.txt");
    assertFalse(share.fileExists("recreate.txt"));

    writeRemoteFile("recreate.txt", "version 2");
    assertTrue(share.fileExists("recreate.txt"));
    assertEquals("version 2", readRemoteFile("recreate.txt"));
  }

  @Test
  public void delete_directoryWithContents_afterCleanup() throws Exception {
    ensureRemoteDirectoryExists("to_delete/sub");
    writeRemoteFile("to_delete\\sub\\file.txt", "content");

    // Must delete files first, then directories bottom-up
    share.rm("to_delete\\sub\\file.txt");
    share.rmdir("to_delete\\sub", false);
    share.rmdir("to_delete", false);

    assertFalse(share.folderExists("to_delete"));
  }

  // ===== MULTIPLE SESSIONS =====

  @Test
  public void multipleSessions_readSameFile_bothSucceed() throws Exception {
    writeRemoteFile("shared_read.txt", "shared data");

    SMBClient client2 = new SMBClient();
    Connection conn2 = client2.connect(sambaContainer.getHost(), sambaContainer.getPort());
    AuthenticationContext auth2 =
        new AuthenticationContext("testuser", "testpassword".toCharArray(), "WORKGROUP");
    Session sess2 = conn2.authenticate(auth2);
    DiskShare share2 = (DiskShare) sess2.connectShare("testshare");

    try {
      String content1 = readRemoteFile("shared_read.txt");
      String content2;
      try (File rf = share2.openFile("shared_read.txt",
          EnumSet.of(AccessMask.GENERIC_READ), null,
          SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null);
          InputStream is = rf.getInputStream()) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = is.read(buf)) != -1) baos.write(buf, 0, r);
        content2 = baos.toString(UTF_8);
      }

      assertEquals("shared data", content1);
      assertEquals("shared data", content2);
    } finally {
      share2.close();
      sess2.close();
      conn2.close();
      client2.close();
    }
  }

  @Test
  public void multipleSessions_writeToSameShare_bothSucceed() throws Exception {
    SMBClient client2 = new SMBClient();
    Connection conn2 = client2.connect(sambaContainer.getHost(), sambaContainer.getPort());
    AuthenticationContext auth2 =
        new AuthenticationContext("testuser", "testpassword".toCharArray(), "WORKGROUP");
    Session sess2 = conn2.authenticate(auth2);
    DiskShare share2 = (DiskShare) sess2.connectShare("testshare");

    try {
      writeRemoteFile("session1.txt", "from session 1");
      try (File rf = share2.openFile("session2.txt",
          EnumSet.of(AccessMask.GENERIC_WRITE),
          EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
          SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null);
          OutputStream os = rf.getOutputStream()) {
        os.write("from session 2".getBytes(UTF_8));
      }

      assertEquals("from session 1", readRemoteFile("session1.txt"));
      assertEquals("from session 2", readRemoteFile("session2.txt"));
    } finally {
      share2.close();
      sess2.close();
      conn2.close();
      client2.close();
    }
  }

  // ===== LISTING EDGE CASES =====

  @Test
  public void list_emptyDirectory_returnsOnlyDotEntries() throws Exception {
    ensureRemoteDirectoryExists("empty_dir");

    int realEntries = 0;
    for (FileIdBothDirectoryInformation info : share.list("empty_dir")) {
      String name = info.getFileName();
      if (!".".equals(name) && !"..".equals(name)) {
        realEntries++;
      }
    }
    assertEquals(0, realEntries);
  }

  @Test
  public void list_directoryWithMixedContent_returnsFilesAndDirs() throws Exception {
    ensureRemoteDirectoryExists("mixed/subdir");
    writeRemoteFile("mixed\\file1.txt", "f1");
    writeRemoteFile("mixed\\file2.txt", "f2");

    int fileCount = 0;
    int dirCount = 0;
    for (FileIdBothDirectoryInformation info : share.list("mixed")) {
      String name = info.getFileName();
      if (".".equals(name) || "..".equals(name)) continue;
      boolean isDir =
          (info.getFileAttributes()
                  & com.hierynomus.msfscc.FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue())
              != 0;
      if (isDir) dirCount++;
      else fileCount++;
    }

    assertEquals(2, fileCount);
    assertEquals(1, dirCount);
  }

  // ===== Helper methods =====

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
