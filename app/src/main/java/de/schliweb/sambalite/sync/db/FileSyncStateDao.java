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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

/** Data Access Object for {@link FileSyncState} entities. */
@Dao
public interface FileSyncStateDao {

  /** Inserts or replaces a sync state entry (upsert by unique index). */
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  long upsert(@NonNull FileSyncState state);

  /** Finds a sync state by root URI and relative path. */
  @Query(
      "SELECT * FROM file_sync_state WHERE root_uri = :rootUri AND relative_path = :relativePath LIMIT 1")
  @Nullable
  FileSyncState findByPath(@NonNull String rootUri, @NonNull String relativePath);

  /** Returns all sync states for a given root URI. */
  @Query("SELECT * FROM file_sync_state WHERE root_uri = :rootUri")
  @NonNull
  List<FileSyncState> findByRootUri(@NonNull String rootUri);

  /** Deletes a sync state by root URI and relative path. */
  @Query("DELETE FROM file_sync_state WHERE root_uri = :rootUri AND relative_path = :relativePath")
  int deleteByPath(@NonNull String rootUri, @NonNull String relativePath);

  /** Deletes all sync states for a given root URI. */
  @Query("DELETE FROM file_sync_state WHERE root_uri = :rootUri")
  int deleteByRootUri(@NonNull String rootUri);

  /** Returns the count of entries where timestamp was preserved. */
  @Query("SELECT COUNT(*) FROM file_sync_state WHERE timestamp_preserved = 1")
  int countTimestampPreserved();

  /** Returns the total count of sync state entries. */
  @Query("SELECT COUNT(*) FROM file_sync_state")
  int countAll();
}
