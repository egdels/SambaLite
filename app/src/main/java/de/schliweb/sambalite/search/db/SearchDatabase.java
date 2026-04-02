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

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/** Room database for search results. Separate from the transfer database. */
@Database(
    entities = {SearchResult.class},
    version = 1,
    exportSchema = false)
public abstract class SearchDatabase extends RoomDatabase {

  private static final String DATABASE_NAME = "sambalite_search.db";
  private static volatile SearchDatabase instance;

  /** Returns the DAO for search result operations. */
  @NonNull
  public abstract SearchResultDao searchResultDao();

  /** Returns the singleton database instance. */
  @NonNull
  public static SearchDatabase getInstance(@NonNull Context context) {
    if (instance == null) {
      synchronized (SearchDatabase.class) {
        if (instance == null) {
          instance =
              Room.databaseBuilder(
                      context.getApplicationContext(), SearchDatabase.class, DATABASE_NAME)
                  .fallbackToDestructiveMigration(true)
                  .build();
        }
      }
    }
    return instance;
  }
}
