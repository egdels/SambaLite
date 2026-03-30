/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.search.db;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

/** Data Access Object for {@link SearchResult} entities. */
@Dao
public interface SearchResultDao {

  /** Observes all results for a given search (live UI updates on each INSERT). */
  @Query("SELECT * FROM search_result WHERE search_id = :searchId ORDER BY path")
  @NonNull
  LiveData<List<SearchResult>> observeResults(@NonNull String searchId);

  /** Observes the hit count for a given search (for toolbar subtitle). */
  @Query("SELECT COUNT(*) FROM search_result WHERE search_id = :searchId")
  @NonNull
  LiveData<Integer> observeResultCount(@NonNull String searchId);

  /** Inserts a single search result (called by SearchWorker for each hit). */
  @Insert
  long insert(@NonNull SearchResult result);

  /** Inserts multiple search results at once (batch optimisation). */
  @Insert
  void insertAll(@NonNull List<SearchResult> results);

  /** Deletes all results for a given search (before starting a new search). */
  @Query("DELETE FROM search_result WHERE search_id = :searchId")
  void deleteBySearchId(@NonNull String searchId);

  /** Deletes all search results (cleanup). */
  @Query("DELETE FROM search_result")
  void deleteAll();

  /** Returns all results synchronously (for mapping/export). */
  @Query("SELECT * FROM search_result WHERE search_id = :searchId ORDER BY path")
  @NonNull
  List<SearchResult> getResultsSync(@NonNull String searchId);
}
