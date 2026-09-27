/*
 * SambaLite - A lightweight Android SMB client
 * Copyright (C) 2025 Christian Kierdorf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package de.schliweb.sambalite.ui.operations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.net.Uri;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests conflict detection for batch uploads: one listing per remote directory. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class UploadConflictFinderTest {

  private static UploadRequest request(String remotePath) {
    return new UploadRequest(
        Uri.parse("content://test/" + remotePath), remotePath, remotePath, 1);
  }

  @Test
  public void uploadRequest_splitsRemotePathIntoDirectoryAndName() {
    assertEquals("Photos/2024", request("Photos/2024/a.jpg").remoteDirectory());
    assertEquals("a.jpg", request("Photos/2024/a.jpg").remoteFileName());
    assertEquals("", request("root.txt").remoteDirectory());
    assertEquals("root.txt", request("root.txt").remoteFileName());
  }

  @Test
  public void find_listsEachRemoteDirectoryOnce() {
    List<UploadRequest> requests =
        Arrays.asList(
            request("Photos/a.jpg"),
            request("Photos/b.jpg"),
            request("Photos/sub/c.jpg"),
            request("Photos/d.jpg"),
            request("root.txt"));
    List<String> listed = new ArrayList<>();

    UploadConflictFinder.find(
        requests,
        Collections.emptyList(),
        dir -> {
          listed.add(dir);
          return Collections.emptySet();
        },
        null);

    assertEquals(Arrays.asList("Photos", "Photos/sub", ""), listed);
  }

  @Test
  public void find_reportsExistingFilesPerDirectoryListing() {
    List<UploadRequest> requests =
        Arrays.asList(request("Photos/a.jpg"), request("Photos/b.jpg"), request("Photos/sub/c.jpg"));

    UploadConflictFinder.Result result =
        UploadConflictFinder.find(
            requests,
            Collections.emptyList(),
            dir -> {
              if (dir.equals("Photos")) return new HashSet<>(Arrays.asList("b.jpg", "other.jpg"));
              if (dir.equals("Photos/sub")) return new HashSet<>(Collections.singletonList("c.jpg"));
              return Collections.emptySet();
            },
            null);

    assertEquals(2, result.existing.size());
    assertEquals("Photos/b.jpg", result.existing.get(0).remotePath);
    assertEquals("Photos/sub/c.jpg", result.existing.get(1).remotePath);
    assertTrue(result.queued.isEmpty());
  }

  @Test
  public void find_treatsUnlistableDirectoryAsNoConflict() {
    List<UploadRequest> requests = Arrays.asList(request("New/a.jpg"), request("New/b.jpg"));

    UploadConflictFinder.Result result =
        UploadConflictFinder.find(requests, Collections.emptyList(), dir -> null, null);

    assertTrue(result.existing.isEmpty());
  }

  @Test
  public void find_reportsAlreadyQueuedUploads() {
    List<UploadRequest> requests = Arrays.asList(request("Photos/a.jpg"), request("Photos/b.jpg"));
    Set<String> active = new HashSet<>(Arrays.asList("Photos/b.jpg", "Other/x.jpg"));

    UploadConflictFinder.Result result =
        UploadConflictFinder.find(requests, active, dir -> Collections.emptySet(), null);

    assertEquals(1, result.queued.size());
    assertEquals("Photos/b.jpg", result.queued.get(0).remotePath);
  }

  @Test
  public void find_reportsProgressPerDirectory() {
    List<UploadRequest> requests = Arrays.asList(request("A/a"), request("B/b"), request("A/c"));
    List<String> progress = new ArrayList<>();

    UploadConflictFinder.find(
        requests,
        Collections.emptyList(),
        dir -> Collections.emptySet(),
        (done, total) -> progress.add(done + "/" + total));

    assertEquals(Arrays.asList("1/2", "2/2"), progress);
  }
}
