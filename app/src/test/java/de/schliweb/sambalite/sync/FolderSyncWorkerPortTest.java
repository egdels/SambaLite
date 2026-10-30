/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import androidx.test.core.app.ApplicationProvider;
import androidx.work.Data;
import androidx.work.ListenableWorker;
import androidx.work.testing.TestWorkerBuilder;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.repository.ConnectionRepositoryImpl;
import de.schliweb.sambalite.util.SambaContainer;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowStatFs;

/**
 * Issue #46: the folder sync must connect to the SMB port configured for the connection. The Samba
 * container's port is mapped to a random host port, never 445, so the sync only reaches the server
 * if the worker honors {@link SmbConnection#getPort()}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FolderSyncWorkerPortTest {

  private static final String AUTHORITY = "de.schliweb.sambalite.test.syncdocs";
  private static final Uri LOCAL_TREE = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root");

  private Context context;
  private SambaContainer samba;
  private SyncActionLog actionLog;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    context.getSharedPreferences("sambalite_sync_configs", Context.MODE_PRIVATE).edit().clear().commit();
    context.getSharedPreferences("sambalite_connections", Context.MODE_PRIVATE).edit().clear().commit();
    actionLog = new SyncActionLog(context);
    actionLog.clear();

    // Enough free space, so the sync gets past the disk check
    int blocks = (int) (512L * 1024 * 1024 / 4096);
    ShadowStatFs.registerStats(context.getFilesDir().getPath(), blocks + 10, blocks, blocks);
    ShadowStatFs.registerStats(
        Environment.getExternalStorageDirectory().getPath(), blocks + 10, blocks, blocks);
    Robolectric.buildContentProvider(EmptyFolderProvider.class).create(AUTHORITY);

    samba =
        new SambaContainer()
            .withUsername("testuser")
            .withPassword("testpassword")
            .withDomain("WORKGROUP")
            .withShare("testshare", "/testshare");
    samba.start();
  }

  @After
  public void tearDown() {
    if (samba != null) {
      samba.stop();
    }
  }

  @Test
  public void doWork_connectsToTheConfiguredPort() throws Exception {
    assertNotEquals(SmbConnection.DEFAULT_PORT, samba.getPort());
    saveConnection(samba.getPort());
    saveConfig("/port-sync-target");

    ListenableWorker.Result result = runWorker();

    assertEquals(actionLog.getEntries().toString(), ListenableWorker.Result.success(), result);
    // The worker creates the remote target folder, so it has been on the server
    SambaContainer.ExecResult exists =
        samba.execInContainer("test", "-d", "/testshare/port-sync-target");
    assertEquals("remote target folder must exist", 0, exists.getExitCode());
  }

  @Test
  public void doWork_closedPort_failsInsteadOfFallingBackToTheDefaultPort() throws Exception {
    saveConnection(closedPort());
    saveConfig("/never-created");

    ListenableWorker.Result result = runWorker();

    assertEquals(ListenableWorker.Result.retry(), result);
    List<String> entries = actionLog.getEntries();
    assertEquals(entries.toString(), 1, entries.size());
    assertTrue(entries.get(0), entries.get(0).contains("sync failed"));
  }

  private void saveConnection(int port) {
    SmbConnection connection = new SmbConnection();
    connection.setId("conn1");
    connection.setName("NAS");
    connection.setServer(samba.getHost());
    connection.setPort(port);
    connection.setShare("testshare");
    connection.setUsername(samba.getUsername());
    connection.setPassword(samba.getPassword());
    connection.setDomain(samba.getDomain());
    ConnectionRepositoryImpl connections = new ConnectionRepositoryImpl(context);
    connections.saveConnection(connection);
    assertEquals(port, connections.getAllConnections().get(0).getPort());
  }

  private void saveConfig(String remotePath) {
    SyncConfig config = new SyncConfig();
    config.setId("config1");
    config.setConnectionId("conn1");
    config.setEnabled(true);
    config.setDirection(SyncDirection.LOCAL_TO_REMOTE);
    config.setLocalFolderUri(LOCAL_TREE.toString());
    config.setLocalFolderDisplayName("Local");
    config.setRemotePath(remotePath);
    new SyncRepository(context).saveSyncConfig(config);
  }

  private ListenableWorker.Result runWorker() {
    FolderSyncWorker worker =
        TestWorkerBuilder.from(context, FolderSyncWorker.class, Executors.newSingleThreadExecutor())
            .setInputData(
                new Data.Builder().putString(FolderSyncWorker.KEY_SYNC_CONFIG_ID, "config1").build())
            .build();
    return worker.doWork();
  }

  /** A local port nothing listens on. */
  private static int closedPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  /**
   * An empty local folder: the root document exists and has no children. A plain {@link
   * ContentProvider}, as in {@code SafFolderScannerTest}, since Robolectric rejects queries on a
   * {@code DocumentsProvider}.
   */
  public static class EmptyFolderProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
      return true;
    }

    @Override
    public Cursor query(
        Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
      String[] columns =
          projection != null
              ? projection
              : new String[] {
                Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE
              };
      MatrixCursor cursor = new MatrixCursor(columns);
      if (!uri.getPath().endsWith("/children")) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, "root");
        row.add(Document.COLUMN_DISPLAY_NAME, "Local");
        row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
        row.add(Document.COLUMN_FLAGS, 0);
      }
      return cursor;
    }

    @Override
    public String getType(Uri uri) {
      return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
      return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
      return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
      return 0;
    }
  }
}
