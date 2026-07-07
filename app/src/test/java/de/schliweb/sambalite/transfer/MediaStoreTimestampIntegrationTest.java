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
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import de.schliweb.sambalite.util.LogUtils;
import de.schliweb.sambalite.util.MediaStorePathResolver;
import de.schliweb.sambalite.util.SambaContainer;
import de.schliweb.sambalite.util.TimestampUtils;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for MediaStore-based timestamp preservation on downloads against a real Docker
 * Samba Server.
 *
 * <p>Simulates the full download chain: remote file with historical timestamp → SMB download to a
 * temp file → copy to the final destination resolved via {@link MediaStorePathResolver} (as would
 * happen for MediaStore/FUSE paths on device) → timestamp set via {@link
 * TimestampUtils#setLastModified}. Also verifies the upload direction round-trip.
 */
public class MediaStoreTimestampIntegrationTest {

  /** Fixed historical timestamp: 2020-06-15 12:30:00 UTC. */
  private static final long HISTORICAL_TIMESTAMP = 1592224200000L;

  /** Tolerance for timestamp comparisons (SMB stores 100ns ticks, filesystems may round). */
  private static final long TOLERANCE_MS = 2000L;

  private SambaContainer sambaContainer;
  private SMBClient smbClient;
  private Connection connection;
  private Session session;
  private DiskShare share;
  private Path localTempDir;
  private Path fakeExternalStorageRoot;

  @Before
  public void setUp() throws Exception {
    LogUtils.init(true);

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

    localTempDir = Files.createTempDirectory("mediastore-timestamp-test");
    fakeExternalStorageRoot = Files.createTempDirectory("fake-external-storage");
  }

  @After
  public void tearDown() throws Exception {
    if (share != null) share.close();
    if (session != null) session.close();
    if (connection != null) connection.close();
    if (smbClient != null) smbClient.close();
    if (sambaContainer != null) sambaContainer.stop();
    if (localTempDir != null) deleteDirectory(localTempDir);
    if (fakeExternalStorageRoot != null) deleteDirectory(fakeExternalStorageRoot);
  }

  // ===== DOWNLOAD: timestamp preservation on the local file =====

  @Test
  public void download_preservesHistoricalRemoteTimestamp_onLocalFile() throws Exception {
    writeRemoteFile("photo.jpg", "fake image data");
    setRemoteLastModified("photo.jpg", HISTORICAL_TIMESTAMP);

    long remoteTimestamp = getRemoteLastModified("photo.jpg");
    assertTimestampEquals(
        "Remote timestamp should be set", HISTORICAL_TIMESTAMP, remoteTimestamp, TOLERANCE_MS);

    Path localFile = localTempDir.resolve("photo.jpg");
    downloadFile("photo.jpg", localFile);
    boolean preserved = TimestampUtils.setLastModified(localFile.toFile(), remoteTimestamp);

    assertTrue("Timestamp should be set on local file", preserved);
    assertTimestampEquals(
        "Local file timestamp should match remote timestamp",
        remoteTimestamp,
        localFile.toFile().lastModified(),
        TOLERANCE_MS);
  }

  @Test
  public void download_copyToResolvedMediaStorePath_preservesTimestamp() throws Exception {
    // Simulates the MediaStore download flow: temp download → resolve final path
    // (primary:Pictures/...) → copy → set timestamp on the resolved FUSE-like path.
    writeRemoteFile("vacation.jpg", "vacation image bytes");
    setRemoteLastModified("vacation.jpg", HISTORICAL_TIMESTAMP);
    long remoteTimestamp = getRemoteLastModified("vacation.jpg");

    // Step 1: download to temp file (as SmbRepositoryImpl does)
    Path tempFile = localTempDir.resolve("vacation.jpg.tmp");
    downloadFile("vacation.jpg", tempFile);
    TimestampUtils.setLastModified(tempFile.toFile(), remoteTimestamp);

    // Step 2: resolve the final destination like on device (docId → real path)
    java.io.File finalDestination =
        MediaStorePathResolver.resolveExternalStorageDocId(
            "primary:Pictures/vacation.jpg", fakeExternalStorageRoot.toFile());
    assertNotNull("DocId must resolve to a real path", finalDestination);
    assertTrue(finalDestination.getParentFile().mkdirs() || finalDestination.getParentFile().isDirectory());

    // Step 3: copy temp → final destination (as FileOperations does)
    Files.copy(tempFile, finalDestination.toPath());

    // Step 4: preserve the source timestamp on the resolved path
    long srcTimestamp = tempFile.toFile().lastModified();
    boolean preserved = TimestampUtils.setLastModified(finalDestination, srcTimestamp);

    assertTrue("Timestamp must be settable on the resolved path", preserved);
    assertTimestampEquals(
        "Final destination timestamp should match remote timestamp",
        remoteTimestamp,
        finalDestination.lastModified(),
        TOLERANCE_MS);
    assertEquals("Content must survive the copy chain",
        "vacation image bytes",
        new String(Files.readAllBytes(finalDestination.toPath()), UTF_8));
  }

  @Test
  public void download_multipleFiles_allTimestampsPreservedOnResolvedPaths() throws Exception {
    long[] timestamps = {
      HISTORICAL_TIMESTAMP,
      HISTORICAL_TIMESTAMP - 86_400_000L, // one day earlier
      HISTORICAL_TIMESTAMP - 365L * 86_400_000L // one year earlier
    };
    String[] names = {"a.jpg", "b.jpg", "c.jpg"};

    for (int i = 0; i < names.length; i++) {
      writeRemoteFile(names[i], "content-" + i);
      setRemoteLastModified(names[i], timestamps[i]);
    }

    for (int i = 0; i < names.length; i++) {
      long remoteTimestamp = getRemoteLastModified(names[i]);
      Path tempFile = localTempDir.resolve(names[i]);
      downloadFile(names[i], tempFile);

      java.io.File dest =
          MediaStorePathResolver.resolveExternalStorageDocId(
              "primary:DCIM/" + names[i], fakeExternalStorageRoot.toFile());
      assertNotNull(dest);
      dest.getParentFile().mkdirs();
      Files.copy(tempFile, dest.toPath());
      assertTrue(TimestampUtils.setLastModified(dest, remoteTimestamp));
      assertTimestampEquals(
          "Timestamp of " + names[i] + " should be preserved",
          timestamps[i],
          dest.lastModified(),
          TOLERANCE_MS);
    }
  }

  // ===== ROUND-TRIP: download → upload preserves the original timestamp =====

  @Test
  public void roundTrip_downloadThenUpload_remoteTimestampPreserved() throws Exception {
    writeRemoteFile("roundtrip.txt", "roundtrip content");
    setRemoteLastModified("roundtrip.txt", HISTORICAL_TIMESTAMP);
    long originalRemoteTimestamp = getRemoteLastModified("roundtrip.txt");

    // Download and preserve timestamp locally
    Path localFile = localTempDir.resolve("roundtrip.txt");
    downloadFile("roundtrip.txt", localFile);
    assertTrue(TimestampUtils.setLastModified(localFile.toFile(), originalRemoteTimestamp));

    // Re-upload to a new remote location and preserve local timestamp on the remote file
    uploadFile(localFile, "roundtrip_restored.txt");
    setRemoteLastModified("roundtrip_restored.txt", localFile.toFile().lastModified());

    assertTimestampEquals(
        "Round-trip should preserve the original historical timestamp",
        originalRemoteTimestamp,
        getRemoteLastModified("roundtrip_restored.txt"),
        TOLERANCE_MS);
  }

  @Test
  public void download_invalidRemoteTimestamp_isRejectedWithoutModifyingFile() throws Exception {
    writeRemoteFile("zero.txt", "zero timestamp");
    Path localFile = localTempDir.resolve("zero.txt");
    downloadFile("zero.txt", localFile);
    long before = localFile.toFile().lastModified();

    assertFalse(TimestampUtils.setLastModified(localFile.toFile(), 0));
    assertFalse(TimestampUtils.setLastModified(localFile.toFile(), -1));
    assertEquals("File timestamp must remain unchanged", before, localFile.toFile().lastModified());
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

  private static void assertTimestampEquals(
      String message, long expected, long actual, long toleranceMs) {
    if (Math.abs(expected - actual) > toleranceMs) {
      fail(message + " (expected=" + expected + ", actual=" + actual + ")");
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
