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
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Room entity representing a single search result found by the SearchWorker. Each row is one file
 * or directory matching the search query. Results are grouped by search_id so multiple searches
 * don't interfere.
 */
@Entity(
    tableName = "search_result",
    indices = {@Index("search_id")})
public class SearchResult {

  @PrimaryKey(autoGenerate = true)
  public long id;

  /** Groups results belonging to one search run (UUID). */
  @ColumnInfo(name = "search_id")
  @NonNull
  public String searchId = "";

  /** File or directory name (e.g. "report.pdf"). */
  @ColumnInfo(name = "name")
  @NonNull
  public String name = "";

  /** Full SMB path (e.g. "Documents/Reports/report.pdf"). */
  @ColumnInfo(name = "path")
  @NonNull
  public String path = "";

  /** "FILE" or "DIRECTORY". */
  @ColumnInfo(name = "type")
  @NonNull
  public String type = "";

  /** File size in bytes. */
  @ColumnInfo(name = "size")
  public long size;

  /** Last modified timestamp (epoch millis). */
  @ColumnInfo(name = "last_modified")
  public long lastModified;

  /** Connection ID referencing the stored SmbConnection. */
  @ColumnInfo(name = "connection_id")
  @NonNull
  public String connectionId = "";

  /** Timestamp when this result was found (epoch millis). */
  @ColumnInfo(name = "found_at")
  public long foundAt;
}
