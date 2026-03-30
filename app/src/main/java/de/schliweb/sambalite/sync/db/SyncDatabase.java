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

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/** Room database for sync metadata persistence. */
@Database(
    entities = {FileSyncState.class},
    version = 1,
    exportSchema = false)
public abstract class SyncDatabase extends RoomDatabase {

  private static final String DATABASE_NAME = "sambalite_sync.db";
  private static volatile SyncDatabase instance;

  /** Returns the DAO for file sync state operations. */
  @NonNull
  public abstract FileSyncStateDao fileSyncStateDao();

  /** Returns the singleton database instance. */
  @NonNull
  public static SyncDatabase getInstance(@NonNull Context context) {
    if (instance == null) {
      synchronized (SyncDatabase.class) {
        if (instance == null) {
          instance =
              Room.databaseBuilder(
                      context.getApplicationContext(), SyncDatabase.class, DATABASE_NAME)
                  .build();
        }
      }
    }
    return instance;
  }
}
