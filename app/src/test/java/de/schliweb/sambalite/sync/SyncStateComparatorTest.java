/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.schliweb.sambalite.sync;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.schliweb.sambalite.sync.db.FileSyncState;
import org.junit.Before;
import org.junit.Test;

public class SyncStateComparatorTest {

  private static final long INITIAL_MODIFIED = 1_700_000_000_000L;
  private FileSyncState state;

  @Before
  public void setUp() {
    state = new FileSyncState();
    state.localSize = 1024;
    state.localLastModified = INITIAL_MODIFIED;
    state.remoteSize = 1024;
    state.remoteLastModified = INITIAL_MODIFIED;
  }

  @Test
  public void bothMatch_whenNeitherSideChanged() {
    assertTrue(
        SyncStateComparator.bothMatch(
            state, 1024, INITIAL_MODIFIED, 1024, INITIAL_MODIFIED));
  }

  @Test
  public void bothMatch_whenSafCannotPreserveRemoteTimestampAfterDownload() {
    state.localLastModified = 5000;
    state.remoteLastModified = 1000;

    assertTrue(SyncStateComparator.bothMatch(state, 1024, 5000, 1024, 1000));
    assertFalse(
        SyncStateComparator.localChangedWhileRemoteMatches(state, 1024, 5000, 1024, 1000));
  }

  @Test
  public void bothMatch_falseWhenSameSizeLocalFileWasModified() {
    long newerLocalModified =
        INITIAL_MODIFIED + SyncComparator.DEFAULT_TIMESTAMP_TOLERANCE_MS + 1;

    assertTrue(SyncStateComparator.remoteMatches(state, 1024, INITIAL_MODIFIED));
    assertFalse(SyncStateComparator.localMatches(state, 1024, newerLocalModified));
    assertFalse(
        SyncStateComparator.bothMatch(
            state, 1024, newerLocalModified, 1024, INITIAL_MODIFIED));
    assertTrue(
        SyncStateComparator.localChangedWhileRemoteMatches(
            state, 1024, newerLocalModified, 1024, INITIAL_MODIFIED));
  }

  @Test
  public void localChangedWhileRemoteMatches_doesNotUploadOlderLocalFile() {
    assertFalse(
        SyncStateComparator.localChangedWhileRemoteMatches(
            state, 512, INITIAL_MODIFIED - 10_000, 1024, INITIAL_MODIFIED));
  }

  @Test
  public void localChangedWhileRemoteMatches_doesNotUploadSameSizeOlderLocalFile() {
    assertFalse(
        SyncStateComparator.localChangedWhileRemoteMatches(
            state, 1024, INITIAL_MODIFIED - 10_000, 1024, INITIAL_MODIFIED));
  }

  @Test
  public void localMatches_acceptsTimestampJitterWithinTolerance() {
    long jitteredLocalModified =
        INITIAL_MODIFIED + SyncComparator.DEFAULT_TIMESTAMP_TOLERANCE_MS - 1;

    assertTrue(SyncStateComparator.localMatches(state, 1024, jitteredLocalModified));
    assertTrue(
        SyncStateComparator.bothMatch(
            state, 1024, jitteredLocalModified, 1024, INITIAL_MODIFIED));
    assertFalse(
        SyncStateComparator.localChangedWhileRemoteMatches(
            state, 1024, jitteredLocalModified, 1024, INITIAL_MODIFIED));
  }

  @Test
  public void bothMatch_acceptsTimestampExactlyAtToleranceAfterSafDownload() {
    state.localLastModified = 5000;
    state.remoteLastModified = 1000;

    assertTrue(SyncStateComparator.bothMatch(state, 1024, 8000, 1024, 1000));
    assertFalse(SyncStateComparator.localChangedWhileRemoteMatches(state, 1024, 8000, 1024, 1000));
    assertTrue(SyncStateComparator.remoteMatches(state, 1024, 4000));
  }

  @Test
  public void bothMatch_falseWhenLocalSizeChanged() {
    assertFalse(
        SyncStateComparator.bothMatch(
            state, 2048, INITIAL_MODIFIED, 1024, INITIAL_MODIFIED));
  }

  @Test
  public void remoteMatches_acceptsTimestampRoundingWithinTolerance() {
    assertTrue(
        SyncStateComparator.remoteMatches(
            state,
            1024,
            INITIAL_MODIFIED + SyncComparator.DEFAULT_TIMESTAMP_TOLERANCE_MS - 1));
  }

  @Test
  public void remoteMatches_falseWhenRemoteChanged() {
    assertFalse(SyncStateComparator.remoteMatches(state, 2048, INITIAL_MODIFIED));
    assertFalse(
        SyncStateComparator.remoteMatches(
            state,
            1024,
            INITIAL_MODIFIED + SyncComparator.DEFAULT_TIMESTAMP_TOLERANCE_MS + 1));
    assertFalse(
        SyncStateComparator.localChangedWhileRemoteMatches(
            state,
            1024,
            INITIAL_MODIFIED + 1,
            1024,
            INITIAL_MODIFIED + SyncComparator.DEFAULT_TIMESTAMP_TOLERANCE_MS + 1));
  }

  @Test
  public void legacyStateWithoutLocalBaseline_doesNotUseFastPath() {
    state.localSize = -1;
    state.localLastModified = -1;

    assertFalse(SyncStateComparator.hasLocalBaseline(state));
    assertFalse(
        SyncStateComparator.bothMatch(
            state, 1024, INITIAL_MODIFIED, 1024, INITIAL_MODIFIED));
  }
}
