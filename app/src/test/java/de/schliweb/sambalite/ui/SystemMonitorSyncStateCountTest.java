/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.Looper;
import de.schliweb.sambalite.sync.db.FileSyncStateDao;
import de.schliweb.sambalite.sync.db.SyncStateStore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.mockito.stubbing.Answer;

/** The System Monitor showed "Tracked files: 0" because it queried Room on the main thread. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SystemMonitorSyncStateCountTest {

  /** Behaves like Room: queries on the main thread throw. */
  private static Answer<Integer> roomLike(int value) {
    return invocation -> {
      if (Looper.getMainLooper().isCurrentThread()) {
        throw new IllegalStateException("Cannot access database on the main thread");
      }
      return value;
    };
  }

  @Test
  public void countsAreReadOffTheMainThread() throws Exception {
    FileSyncStateDao dao = mock(FileSyncStateDao.class);
    when(dao.countAll()).thenAnswer(roomLike(121));
    when(dao.countTimestampPreserved()).thenAnswer(roomLike(87));

    int[] counts = SystemMonitorActivity.readSyncStateCounts(new SyncStateStore(dao));

    assertArrayEquals(new int[] {121, 87}, counts);
  }
}
