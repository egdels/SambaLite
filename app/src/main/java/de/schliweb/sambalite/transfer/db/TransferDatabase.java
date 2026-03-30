/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.transfer.db;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/** Room database for persistent transfer queue. Separate from the sync database. */
@Database(
    entities = {PendingTransfer.class},
    version = 2,
    exportSchema = false)
public abstract class TransferDatabase extends RoomDatabase {

  private static final String DATABASE_NAME = "sambalite_transfers.db";
  private static volatile TransferDatabase instance;

  /** Returns the DAO for pending transfer operations. */
  @NonNull
  public abstract PendingTransferDao pendingTransferDao();

  /** Returns the singleton database instance. */
  @NonNull
  public static TransferDatabase getInstance(@NonNull Context context) {
    if (instance == null) {
      synchronized (TransferDatabase.class) {
        if (instance == null) {
          instance =
              Room.databaseBuilder(
                      context.getApplicationContext(), TransferDatabase.class, DATABASE_NAME)
                  .fallbackToDestructiveMigration(true)
                  .build();
        }
      }
    }
    return instance;
  }
}
