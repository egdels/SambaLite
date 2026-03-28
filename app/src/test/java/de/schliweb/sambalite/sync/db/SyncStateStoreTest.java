/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.sync.db;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link SyncStateStore}. Uses a mocked DAO to test store logic in isolation. */
public class SyncStateStoreTest {

  private FileSyncStateDao mockDao;
  private SyncStateStore store;

  @Before
  public void setUp() {
    mockDao = mock(FileSyncStateDao.class);
    store = new SyncStateStore(mockDao);
  }

  @Test
  public void saveRemoteState_insertsNewEntry() {
    when(mockDao.findByPath("root://uri", "docs/file.txt")).thenReturn(null);
    when(mockDao.upsert(any())).thenReturn(1L);

    store.saveRemoteState("root://uri", "docs/file.txt", "/share/docs/file.txt", 1024, 1700000000000L, true);

    verify(mockDao).findByPath("root://uri", "docs/file.txt");
    verify(mockDao).upsert(argThat(state ->
        "root://uri".equals(state.rootUri)
            && "docs/file.txt".equals(state.relativePath)
            && "/share/docs/file.txt".equals(state.remotePath)
            && state.remoteSize == 1024
            && state.remoteLastModified == 1700000000000L
            && state.timestampPreserved
            && state.syncedAt > 0
            && state.id == 0
    ));
  }

  @Test
  public void saveRemoteState_updatesExistingEntry() {
    FileSyncState existing = new FileSyncState();
    existing.id = 42;
    existing.rootUri = "root://uri";
    existing.relativePath = "docs/file.txt";
    when(mockDao.findByPath("root://uri", "docs/file.txt")).thenReturn(existing);
    when(mockDao.upsert(any())).thenReturn(42L);

    store.saveRemoteState("root://uri", "docs/file.txt", "/share/docs/file.txt", 2048, 1700000001000L, false);

    verify(mockDao).upsert(argThat(state -> state.id == 42 && state.remoteSize == 2048));
  }

  @Test
  public void saveRemoteState_handlesException() {
    when(mockDao.findByPath(anyString(), anyString())).thenThrow(new RuntimeException("DB error"));

    // Should not throw
    store.saveRemoteState("root://uri", "file.txt", "/share/file.txt", 100, 100L, false);
  }

  @Test
  public void getRemoteState_returnsStoredState() {
    FileSyncState state = new FileSyncState();
    state.rootUri = "root://uri";
    state.relativePath = "file.txt";
    state.remoteSize = 512;
    when(mockDao.findByPath("root://uri", "file.txt")).thenReturn(state);

    FileSyncState result = store.getRemoteState("root://uri", "file.txt");

    assertNotNull(result);
    assertEquals(512, result.remoteSize);
  }

  @Test
  public void getRemoteState_returnsNullWhenNotFound() {
    when(mockDao.findByPath("root://uri", "missing.txt")).thenReturn(null);

    assertNull(store.getRemoteState("root://uri", "missing.txt"));
  }

  @Test
  public void getRemoteState_returnsNullOnException() {
    when(mockDao.findByPath(anyString(), anyString())).thenThrow(new RuntimeException("DB error"));

    assertNull(store.getRemoteState("root://uri", "file.txt"));
  }

  @Test
  public void getAllForRoot_returnsList() {
    FileSyncState s1 = new FileSyncState();
    s1.relativePath = "a.txt";
    FileSyncState s2 = new FileSyncState();
    s2.relativePath = "b.txt";
    when(mockDao.findByRootUri("root://uri")).thenReturn(Arrays.asList(s1, s2));

    List<FileSyncState> result = store.getAllForRoot("root://uri");

    assertEquals(2, result.size());
  }

  @Test
  public void getAllForRoot_returnsEmptyOnException() {
    when(mockDao.findByRootUri(anyString())).thenThrow(new RuntimeException("DB error"));

    List<FileSyncState> result = store.getAllForRoot("root://uri");

    assertTrue(result.isEmpty());
  }

  @Test
  public void deleteState_callsDao() {
    store.deleteState("root://uri", "file.txt");

    verify(mockDao).deleteByPath("root://uri", "file.txt");
  }

  @Test
  public void deleteAllForRoot_callsDao() {
    when(mockDao.deleteByRootUri("root://uri")).thenReturn(5);

    store.deleteAllForRoot("root://uri");

    verify(mockDao).deleteByRootUri("root://uri");
  }

  @Test
  public void getTimestampPreservedCount_returnsCount() {
    when(mockDao.countTimestampPreserved()).thenReturn(7);

    assertEquals(7, store.getTimestampPreservedCount());
  }

  @Test
  public void getTimestampPreservedCount_returnsZeroOnException() {
    when(mockDao.countTimestampPreserved()).thenThrow(new RuntimeException("DB error"));

    assertEquals(0, store.getTimestampPreservedCount());
  }

  @Test
  public void getTotalCount_returnsCount() {
    when(mockDao.countAll()).thenReturn(15);

    assertEquals(15, store.getTotalCount());
  }

  @Test
  public void getTotalCount_returnsZeroOnException() {
    when(mockDao.countAll()).thenThrow(new RuntimeException("DB error"));

    assertEquals(0, store.getTotalCount());
  }
}
