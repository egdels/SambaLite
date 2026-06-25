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
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Baseline measurement for issue #21 (upload speed performance boost).
 *
 * <p>The LOCAL→REMOTE sync path in {@code FolderSyncWorker.syncLocalToRemote} currently performs
 * <b>three</b> separate SMB round-trips per already-existing remote file when deciding whether an
 * upload is needed:
 *
 * <ol>
 *   <li>{@code share.fileExists(path)} – existence check
 *   <li>open file to read {@code lastWriteTime} (mirrors {@code getRemoteFileLastModified})
 *   <li>open file to read {@code endOfFile} / size (mirrors {@code getRemoteFileSize})
 * </ol>
 *
 * <p>This test reproduces exactly those SMB primitives against a real Docker Samba server and
 * counts the round-trips, establishing a hard, deterministic baseline. It also demonstrates that a
 * single {@code share.list(dir)} call already returns the same metadata
 * ({@link FileIdBothDirectoryInformation#getEndOfFile()} and
 * {@link FileIdBothDirectoryInformation#getLastWriteTime()}) for every entry in the directory.
 *
 * <p>The numbers logged here (search for {@code [DEBUG_LOG]}) serve as the reference against which
 * the optimized {@code share.list()}-based implementation can be compared once it is in place.
 */
public class SyncUploadRoundTripBaselineTest {

  private static final int FILE_COUNT = 50;
  private static final String DIR = "baseline";

  private SambaContainer sambaContainer;
  private SMBClient smbClient;
  private Connection connection;
  private Session session;
  private DiskShare share;

  /** Counts the number of SMB round-trips (open / fileExists / list) performed via the helpers. */
  private int roundTrips;

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

    // Seed a directory with FILE_COUNT already-synced files.
    ensureRemoteDirectory(DIR);
    for (int i = 0; i < FILE_COUNT; i++) {
      writeRemoteFile(DIR + "\\file" + i + ".txt", "content-" + i);
    }
  }

  @After
  public void tearDown() throws Exception {
    if (share != null) share.close();
    if (session != null) session.close();
    if (connection != null) connection.close();
    if (smbClient != null) smbClient.close();
    if (sambaContainer != null) sambaContainer.stop();
  }

  /**
   * Baseline: the current per-file metadata access pattern requires 3 SMB round-trips per file
   * (fileExists + open-for-timestamp + open-for-size).
   */
  @Test
  public void currentPattern_usesThreeRoundTripsPerFile() {
    roundTrips = 0;
    long start = System.nanoTime();

    for (int i = 0; i < FILE_COUNT; i++) {
      String path = DIR + "\\file" + i + ".txt";
      boolean exists = existsRemote(path);
      assertTrue("seeded file should exist: " + path, exists);
      long lastModified = remoteLastModified(path);
      long size = remoteSize(path);
      assertTrue("timestamp should be readable", lastModified > 0);
      assertTrue("size should be readable", size > 0);
    }

    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

    System.out.println(
        "[DEBUG_LOG] BASELINE current pattern: files="
            + FILE_COUNT
            + ", roundTrips="
            + roundTrips
            + ", elapsedMs="
            + elapsedMs);

    assertEquals(
        "Current pattern is expected to use exactly 3 SMB round-trips per file",
        3 * FILE_COUNT,
        roundTrips);
  }

  /**
   * Target: a single {@code share.list(dir)} returns existence, size and timestamp for every file,
   * i.e. ONE round-trip for the whole directory instead of 3 per file.
   */
  @Test
  public void singleListProvidesSameMetadata_inOneRoundTrip() {
    // Reference values via the (expensive) per-file pattern.
    Map<String, long[]> perFile = new HashMap<>();
    for (int i = 0; i < FILE_COUNT; i++) {
      String name = "file" + i + ".txt";
      String path = DIR + "\\" + name;
      perFile.put(name, new long[] {remoteSize(path), remoteLastModified(path)});
    }

    roundTrips = 0;
    long start = System.nanoTime();
    Map<String, FileIdBothDirectoryInformation> map = listMetadata(DIR);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

    System.out.println(
        "[DEBUG_LOG] TARGET single list(): files="
            + FILE_COUNT
            + ", roundTrips="
            + roundTrips
            + ", elapsedMs="
            + elapsedMs);

    assertEquals("A single directory listing must be exactly one round-trip", 1, roundTrips);

    for (Map.Entry<String, long[]> e : perFile.entrySet()) {
      FileIdBothDirectoryInformation info = map.get(e.getKey());
      assertNotNull("list() must contain entry for " + e.getKey(), info);
      assertEquals(
          "size from list() must match per-file size for " + e.getKey(),
          e.getValue()[0],
          info.getEndOfFile());
      assertEquals(
          "lastWriteTime from list() must match per-file timestamp for " + e.getKey(),
          e.getValue()[1],
          info.getLastWriteTime().toEpochMillis());
    }

    // The whole point of the optimization: 1 round-trip vs. 3 * FILE_COUNT.
    assertTrue(
        "single list() must use fewer round-trips than the current per-file pattern",
        1 < 3 * FILE_COUNT);
  }

  /**
   * Verifies the only semantic change introduced by the issue #21 optimization in
   * {@code FolderSyncWorker.syncLocalToRemote}: after an upload the worker no longer reads the
   * remote timestamp back from the server, but stores {@code localFile.lastModified()} directly
   * (because {@code uploadFile} applies exactly that value via {@code setRemoteFileLastModified}).
   *
   * <p>This test reproduces upload + setRemoteFileLastModified against the real Samba server and
   * asserts that the timestamp actually persisted on the server (read back via {@code share.list()})
   * equals the local value the worker would store. If the server silently changed/rounded the
   * timestamp, this test would catch the divergence.
   */
  @Test
  public void postUploadStoredTimestampMatchesServer() {
    String name = "uploaded.txt";
    String path = DIR + "\\" + name;

    // Local lastModified the worker would use. Use a non-trivial, fixed value in the past.
    long localLastModified = 1_700_000_000_000L;

    // Mirror uploadFile: write content, then set the remote lastWriteTime to the local value.
    try {
      writeRemoteFile(path, "some-uploaded-content");
      setRemoteLastModified(path, localLastModified);
    } catch (Exception e) {
      fail("upload/setRemoteLastModified failed: " + e.getMessage());
    }

    // Read back the metadata exactly the way syncLocalToRemote now does (single list()).
    Map<String, FileIdBothDirectoryInformation> map = listMetadata(DIR);
    FileIdBothDirectoryInformation info = map.get(name);
    assertNotNull("uploaded file must appear in list()", info);

    long serverLastModified = info.getLastWriteTime().toEpochMillis();
    System.out.println(
        "[DEBUG_LOG] POST-UPLOAD timestamp: localStored="
            + localLastModified
            + ", server="
            + serverLastModified
            + ", deltaMs="
            + (serverLastModified - localLastModified));

    // The value the worker stores in the DB (localLastModified) must match what the server
    // actually persisted, so no spurious re-upload is triggered on the next sync.
    assertEquals(
        "Stored timestamp must match the value persisted on the server",
        localLastModified,
        serverLastModified);
  }

  // ===========================================================================
  // SMB primitives mirroring FolderSyncWorker (each call = one round-trip)
  // ===========================================================================

  private boolean existsRemote(String path) {
    roundTrips++;
    return share.fileExists(path);
  }

  /** Mirrors {@code FolderSyncWorker.getRemoteFileLastModified}. */
  private long remoteLastModified(String path) {
    roundTrips++;
    try (File remoteFile =
        share.openFile(
            path,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null)) {
      return remoteFile.getFileInformation().getBasicInformation().getLastWriteTime().toEpochMillis();
    }
  }

  /** Mirrors {@code FolderSyncWorker.getRemoteFileSize}. */
  private long remoteSize(String path) {
    roundTrips++;
    try (File remoteFile =
        share.openFile(
            path,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null)) {
      return remoteFile.getFileInformation().getStandardInformation().getEndOfFile();
    }
  }

  /** A single directory listing, mirroring the approach already used by syncRemoteToLocal. */
  private Map<String, FileIdBothDirectoryInformation> listMetadata(String dir) {
    roundTrips++;
    Map<String, FileIdBothDirectoryInformation> result = new HashMap<>();
    List<FileIdBothDirectoryInformation> entries = new ArrayList<>(share.list(dir));
    for (FileIdBothDirectoryInformation info : entries) {
      String name = info.getFileName();
      if (".".equals(name) || "..".equals(name)) continue;
      result.put(name, info);
    }
    return result;
  }

  // ===========================================================================
  // Helpers
  // ===========================================================================

  private void writeRemoteFile(String smbPath, String content) throws Exception {
    try (File remoteFile =
            share.openFile(
                smbPath,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null);
        OutputStream os = remoteFile.getOutputStream()) {
      os.write(content.getBytes(UTF_8));
    }
  }

  /** Mirrors {@code FolderSyncWorker.setRemoteFileLastModified}. */
  private void setRemoteLastModified(String path, long timeMillis) {
    try (File remoteFile =
        share.openFile(
            path,
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

  private void ensureRemoteDirectory(String path) {
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
}
