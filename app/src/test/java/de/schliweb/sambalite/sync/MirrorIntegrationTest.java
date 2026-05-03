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
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import de.schliweb.sambalite.sync.db.FileSyncState;
import de.schliweb.sambalite.sync.db.FileSyncStateDao;
import de.schliweb.sambalite.sync.db.SyncStateStore;
import de.schliweb.sambalite.util.SambaContainer;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for mirror mode against a real Docker Samba server.
 *
 * <p>These tests exercise the {@link MirrorSweeper} together with the same SMB delete primitives
 * that {@link FolderSyncWorker} uses for the LOCAL→REMOTE direction (target = remote SMB share).
 *
 * <p>The same logic is also exercised against a local filesystem target to cover the REMOTE→LOCAL
 * direction without requiring Android's SAF.
 *
 * <p>Goal: prove that mirror mode never deletes untracked entries and respects the safeguards
 * (incomplete listing, empty source listing, sanity threshold).
 */
public class MirrorIntegrationTest {

  private static final String ROOT = "test-root";

  private SambaContainer sambaContainer;
  private SMBClient smbClient;
  private Connection connection;
  private Session session;
  private DiskShare share;

  private Path localTempDir;

  private InMemoryDao dao;
  private SyncStateStore stateStore;
  private MirrorSweeper sweeper;

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

    localTempDir = Files.createTempDirectory("mirror-integration-test");

    dao = new InMemoryDao();
    stateStore = new SyncStateStore(dao);
    sweeper = new MirrorSweeper(stateStore);
  }

  @After
  public void tearDown() throws Exception {
    if (share != null) share.close();
    if (session != null) session.close();
    if (connection != null) connection.close();
    if (smbClient != null) smbClient.close();
    if (sambaContainer != null) sambaContainer.stop();
    if (localTempDir != null) deleteDirectoryRecursively(localTempDir);
  }

  // ===========================================================================
  // Mirror LOCAL → REMOTE  (source = local set, target = SMB share)
  // ===========================================================================

  @Test
  public void localToRemote_remoteEntryNoLongerTracked_isDeletedOnRemote() throws Exception {
    // Two files were synced previously; remote still has both.
    writeRemoteFile("a.txt", "A");
    writeRemoteFile("b.txt", "B");
    track("a.txt", "a.txt", 1, 0);
    track("b.txt", "b.txt", 1, 0);

    // User deletes b.txt locally; local source listing now contains only a.txt.
    Set<String> sourcePaths = setOf("a.txt");

    MirrorSweeper.Result result =
        sweeper.sweep(ROOT, sourcePaths, true, rel -> deleteRemoteEntry(rel));

    assertFalse(result.aborted);
    assertFalse(result.skipped);
    assertEquals(1, result.candidates);
    assertEquals(1, result.deleted);
    assertEquals(0, result.failed);

    assertTrue("a.txt should remain", share.fileExists("a.txt"));
    assertFalse("b.txt should be deleted on remote", share.fileExists("b.txt"));
    assertNull("DB row for b.txt should be removed", dao.findByPath(ROOT, "b.txt"));
    assertNotNull("DB row for a.txt should remain", dao.findByPath(ROOT, "a.txt"));
  }

  @Test
  public void localToRemote_untrackedRemoteFileIsNeverTouched() throws Exception {
    // Tracked file
    writeRemoteFile("tracked.txt", "T");
    track("tracked.txt", "tracked.txt", 1, 0);

    // Untracked remote file (e.g. created on the NAS by another user)
    writeRemoteFile("foreign.txt", "F");

    // Local source has neither file → would normally delete BOTH if there were no protection.
    // But mirror only considers tracked entries, so foreign.txt MUST remain.
    Set<String> sourcePaths = new HashSet<>();
    // Empty source + tracked entries triggers the empty-source protection: nothing deleted.
    MirrorSweeper.Result result =
        sweeper.sweep(ROOT, sourcePaths, true, rel -> deleteRemoteEntry(rel));

    assertTrue("Sweep must be skipped on empty source listing", result.skipped);
    assertTrue("foreign.txt must remain", share.fileExists("foreign.txt"));
    assertTrue("tracked.txt must remain", share.fileExists("tracked.txt"));
  }

  @Test
  public void localToRemote_renamedFolder_oldRemoteFolderIsRemoved() throws Exception {
    // Simulate a previously synced album folder
    ensureRemoteDirectory("U2 - Achtung Baby");
    writeRemoteFile("U2 - Achtung Baby/track1.mp3", "track1");
    writeRemoteFile("U2 - Achtung Baby/track2.mp3", "track2");
    track("U2 - Achtung Baby", "U2 - Achtung Baby", 0, 0); // dir
    track("U2 - Achtung Baby/track1.mp3", "U2 - Achtung Baby/track1.mp3", 6, 0);
    track("U2 - Achtung Baby/track2.mp3", "U2 - Achtung Baby/track2.mp3", 6, 0);

    // The user renamed the folder locally. The new folder will be re-uploaded by the regular sync;
    // for the mirror sweep we only care that the OLD folder name is gone from the source listing.
    Set<String> sourcePaths = setOf(
        "U2 (1991) Achtung Baby",
        "U2 (1991) Achtung Baby/track1.mp3",
        "U2 (1991) Achtung Baby/track2.mp3");

    MirrorSweeper.Result result =
        sweeper.sweep(ROOT, sourcePaths, true, rel -> deleteRemoteEntry(rel));

    assertFalse(result.aborted);
    assertFalse(result.skipped);
    assertEquals(3, result.candidates);
    assertEquals(3, result.deleted);

    assertFalse(
        "Old remote folder must be removed", share.folderExists("U2 - Achtung Baby"));
  }

  @Test
  public void localToRemote_incompleteListing_skipsSweep() throws Exception {
    writeRemoteFile("keep.txt", "K");
    track("keep.txt", "keep.txt", 1, 0);

    Set<String> sourcePaths = new HashSet<>(); // pretend nothing was found
    MirrorSweeper.Result result =
        sweeper.sweep(ROOT, sourcePaths, /* sourceListingComplete= */ false,
            rel -> deleteRemoteEntry(rel));

    assertTrue(result.skipped);
    assertEquals("source listing incomplete", result.reason);
    assertTrue("Tracked file must NOT be deleted on incomplete listing",
        share.fileExists("keep.txt"));
  }

  @Test
  public void localToRemote_thresholdExceeded_abortsAndDeletesNothing() throws Exception {
    // 200 tracked remote files
    for (int i = 0; i < 200; i++) {
      String name = "f" + i + ".txt";
      writeRemoteFile(name, "x");
      track(name, name, 1, 0);
    }
    // Source contains only 1 of them → 199 deletes planned → > 100 AND > 50% → abort
    Set<String> sourcePaths = setOf("f0.txt");

    MirrorSweeper.Result result =
        sweeper.sweep(ROOT, sourcePaths, true, rel -> deleteRemoteEntry(rel));

    assertTrue("Sweep must abort over threshold", result.aborted);
    assertEquals(199, result.candidates);
    assertEquals(0, result.deleted);
    // All remote files must still exist
    for (int i = 0; i < 200; i++) {
      assertTrue("f" + i + ".txt must still exist", share.fileExists("f" + i + ".txt"));
    }
    // DB unchanged
    assertEquals(200, dao.findByRootUri(ROOT).size());
  }

  @Test
  public void localToRemote_largeButProportionalDelete_isAllowed() throws Exception {
    // 50 tracked entries, delete 30 → 60% but only 30 absolute → does NOT trigger abort
    // (threshold needs BOTH > 50% AND > 100 absolute).
    for (int i = 0; i < 50; i++) {
      String name = "g" + i + ".txt";
      writeRemoteFile(name, "x");
      track(name, name, 1, 0);
    }
    Set<String> sourcePaths = new HashSet<>();
    for (int i = 30; i < 50; i++) sourcePaths.add("g" + i + ".txt");

    MirrorSweeper.Result result =
        sweeper.sweep(ROOT, sourcePaths, true, rel -> deleteRemoteEntry(rel));

    assertFalse(result.aborted);
    assertEquals(30, result.candidates);
    assertEquals(30, result.deleted);
    for (int i = 0; i < 30; i++) {
      assertFalse("g" + i + ".txt should be deleted", share.fileExists("g" + i + ".txt"));
    }
    for (int i = 30; i < 50; i++) {
      assertTrue("g" + i + ".txt should remain", share.fileExists("g" + i + ".txt"));
    }
  }

  @Test
  public void localToRemote_sweepIsIdempotent() throws Exception {
    writeRemoteFile("once.txt", "O");
    track("once.txt", "once.txt", 1, 0);
    Set<String> sourcePaths = new HashSet<>();
    sourcePaths.add("kept.txt"); // not empty → no empty-source protection
    track("kept.txt", "kept.txt", 1, 0);
    writeRemoteFile("kept.txt", "K");

    MirrorSweeper.Result first =
        sweeper.sweep(ROOT, sourcePaths, true, rel -> deleteRemoteEntry(rel));
    assertEquals(1, first.deleted);
    assertFalse(share.fileExists("once.txt"));

    // Second run with same input: nothing left to delete.
    MirrorSweeper.Result second =
        sweeper.sweep(ROOT, sourcePaths, true, rel -> deleteRemoteEntry(rel));
    assertFalse(second.skipped);
    assertFalse(second.aborted);
    assertEquals(0, second.candidates);
    assertEquals(0, second.deleted);
  }

  // ===========================================================================
  // Mirror REMOTE → LOCAL (source = remote SMB listing, target = local fs)
  // Validates that the same MirrorSweeper drives a different TargetDeleter cleanly.
  // ===========================================================================

  @Test
  public void remoteToLocal_localFileNoLongerOnRemote_isDeletedLocally() throws Exception {
    Path keep = localTempDir.resolve("keep.txt");
    Path gone = localTempDir.resolve("gone.txt");
    Files.write(keep, "K".getBytes(UTF_8));
    Files.write(gone, "G".getBytes(UTF_8));
    track("keep.txt", "keep.txt", 1, 0);
    track("gone.txt", "gone.txt", 1, 0);

    // Remote source listing (after user deleted gone.txt on NAS)
    Set<String> sourcePaths = setOf("keep.txt");

    MirrorSweeper.Result result =
        sweeper.sweep(ROOT, sourcePaths, true, rel -> deleteLocalEntry(rel));

    assertFalse(result.aborted);
    assertFalse(result.skipped);
    assertEquals(1, result.deleted);
    assertTrue("keep.txt should remain locally", Files.exists(keep));
    assertFalse("gone.txt should be deleted locally", Files.exists(gone));
    assertNull(dao.findByPath(ROOT, "gone.txt"));
  }

  @Test
  public void remoteToLocal_localUntrackedFileIsNeverDeleted() throws Exception {
    Path tracked = localTempDir.resolve("tracked.txt");
    Path foreign = localTempDir.resolve("foreign.txt"); // local-only, never tracked
    Files.write(tracked, "T".getBytes(UTF_8));
    Files.write(foreign, "F".getBytes(UTF_8));
    track("tracked.txt", "tracked.txt", 1, 0);

    // Remote listing only contains tracked.txt → foreign.txt is untracked AND not in source.
    Set<String> sourcePaths = setOf("tracked.txt");

    MirrorSweeper.Result result =
        sweeper.sweep(ROOT, sourcePaths, true, rel -> deleteLocalEntry(rel));

    assertEquals(0, result.candidates);
    assertEquals(0, result.deleted);
    assertTrue("foreign.txt must remain (untracked)", Files.exists(foreign));
    assertTrue("tracked.txt must remain (still in source)", Files.exists(tracked));
  }

  // ===========================================================================
  // Helpers
  // ===========================================================================

  private boolean deleteRemoteEntry(String relativePath) {
    String smbPath = relativePath.replace('/', '\\');
    try {
      if (share.folderExists(smbPath)) {
        // recursively empty
        List<FileIdBothDirectoryInformation> children = share.list(smbPath);
        for (FileIdBothDirectoryInformation child : children) {
          String n = child.getFileName();
          if (".".equals(n) || "..".equals(n)) continue;
          deleteRemoteEntry(relativePath + "/" + n);
        }
        share.rmdir(smbPath, false);
        return !share.folderExists(smbPath);
      } else if (share.fileExists(smbPath)) {
        share.rm(smbPath);
        return !share.fileExists(smbPath);
      }
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private boolean deleteLocalEntry(String relativePath) {
    Path p = localTempDir.resolve(relativePath.replace('/', java.io.File.separatorChar));
    try {
      if (!Files.exists(p)) return true;
      if (Files.isDirectory(p)) {
        deleteDirectoryRecursively(p);
        return !Files.exists(p);
      } else {
        Files.delete(p);
        return !Files.exists(p);
      }
    } catch (Exception e) {
      return false;
    }
  }

  private void track(String relativePath, String remotePath, long size, long lastModified) {
    FileSyncState st = new FileSyncState();
    st.rootUri = ROOT;
    st.relativePath = relativePath;
    st.remotePath = remotePath;
    st.remoteSize = size;
    st.remoteLastModified = lastModified;
    st.syncedAt = System.currentTimeMillis();
    dao.upsert(st);
  }

  private static Set<String> setOf(String... values) {
    Set<String> s = new HashSet<>();
    for (String v : values) s.add(v);
    return s;
  }

  private void writeRemoteFile(String relativePath, String content) throws Exception {
    String smbPath = relativePath.replace('/', '\\');
    int slash = smbPath.lastIndexOf('\\');
    if (slash > 0) ensureRemoteDirectory(smbPath.substring(0, slash));
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

  private static void deleteDirectoryRecursively(Path dir) throws Exception {
    if (!Files.exists(dir)) return;
    java.io.File[] files = dir.toFile().listFiles();
    if (files != null) {
      for (java.io.File f : files) {
        if (f.isDirectory()) {
          deleteDirectoryRecursively(f.toPath());
        } else {
          //noinspection ResultOfMethodCallIgnored
          f.delete();
        }
      }
    }
    //noinspection ResultOfMethodCallIgnored
    dir.toFile().delete();
  }

  /** Trivial in-memory implementation of {@link FileSyncStateDao} for tests. */
  private static class InMemoryDao implements FileSyncStateDao {
    private final Map<String, FileSyncState> rows = new LinkedHashMap<>();
    private long nextId = 1;

    private static String key(String rootUri, String relativePath) {
      return rootUri + "\u0000" + relativePath;
    }

    @Override
    public long upsert(FileSyncState state) {
      if (state.id == 0) state.id = nextId++;
      rows.put(key(state.rootUri, state.relativePath), state);
      return state.id;
    }

    @Override
    public FileSyncState findByPath(String rootUri, String relativePath) {
      return rows.get(key(rootUri, relativePath));
    }

    @Override
    public List<FileSyncState> findByRootUri(String rootUri) {
      List<FileSyncState> list = new ArrayList<>();
      for (FileSyncState s : rows.values()) {
        if (rootUri.equals(s.rootUri)) list.add(s);
      }
      return list;
    }

    @Override
    public int deleteByPath(String rootUri, String relativePath) {
      return rows.remove(key(rootUri, relativePath)) != null ? 1 : 0;
    }

    @Override
    public int deleteByRootUri(String rootUri) {
      Collection<FileSyncState> all = new ArrayList<>(rows.values());
      int removed = 0;
      for (FileSyncState s : all) {
        if (rootUri.equals(s.rootUri)) {
          rows.remove(key(s.rootUri, s.relativePath));
          removed++;
        }
      }
      return removed;
    }

    @Override
    public int countTimestampPreserved() {
      int c = 0;
      for (FileSyncState s : rows.values()) if (s.timestampPreserved) c++;
      return c;
    }

    @Override
    public int countAll() {
      return rows.size();
    }
  }
}
