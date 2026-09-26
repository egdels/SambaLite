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
package de.schliweb.sambalite.sync.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SyncDatabaseMigrationTest {

  private static final String DATABASE_NAME = "sync-database-migration-test";
  private Context context;
  private SyncDatabase database;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    context.deleteDatabase(DATABASE_NAME);
  }

  @After
  public void tearDown() {
    if (database != null) {
      database.close();
    }
    context.deleteDatabase(DATABASE_NAME);
  }

  @Test
  public void migrationFromVersion1_preservesExistingRemoteState() {
    SQLiteDatabase version1 = context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null);
    version1.execSQL(
        "CREATE TABLE IF NOT EXISTS file_sync_state ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
            + "root_uri TEXT NOT NULL, "
            + "relative_path TEXT NOT NULL, "
            + "remote_path TEXT NOT NULL, "
            + "remote_size INTEGER NOT NULL, "
            + "remote_last_modified INTEGER NOT NULL, "
            + "synced_at INTEGER NOT NULL, "
            + "timestamp_preserved INTEGER NOT NULL)");
    version1.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS index_file_sync_state_root_uri_relative_path "
            + "ON file_sync_state (root_uri, relative_path)");
    version1.execSQL(
        "INSERT INTO file_sync_state "
            + "(root_uri, relative_path, remote_path, remote_size, remote_last_modified, "
            + "synced_at, timestamp_preserved) VALUES (?, ?, ?, ?, ?, ?, ?)",
        new Object[] {
          "root://uri", "photos/image.jpg", "/share/photos/image.jpg", 1024, 2000, 3000, 1
        });
    version1.setVersion(1);
    version1.close();

    database =
        Room.databaseBuilder(context, SyncDatabase.class, DATABASE_NAME)
            .addMigrations(SyncDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build();

    FileSyncState state =
        database.fileSyncStateDao().findByPath("root://uri", "photos/image.jpg");
    assertNotNull(state);
    assertEquals(1024, state.remoteSize);
    assertEquals(2000, state.remoteLastModified);
    assertEquals(-1, state.localSize);
    assertEquals(-1, state.localLastModified);
  }
}
