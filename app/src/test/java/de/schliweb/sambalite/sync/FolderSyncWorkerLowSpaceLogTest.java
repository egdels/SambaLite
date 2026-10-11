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

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import androidx.test.core.app.ApplicationProvider;
import androidx.work.Data;
import androidx.work.ListenableWorker;
import androidx.work.testing.TestWorkerBuilder;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.repository.ConnectionRepositoryImpl;
import java.io.File;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowStatFs;

/**
 * A sync stopped by a full target volume must show up in the sync activity log, and must not hold
 * back syncs onto other volumes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FolderSyncWorkerLowSpaceLogTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private Context context;
  private SyncActionLog actionLog;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    context.getSharedPreferences("sambalite_sync_configs", Context.MODE_PRIVATE).edit().clear().commit();
    context.getSharedPreferences("sambalite_connections", Context.MODE_PRIVATE).edit().clear().commit();
    actionLog = new SyncActionLog(context);
    actionLog.clear();
  }

  @Test
  public void doWork_fullTargetVolume_logsAbortWithVolumeAndFreeSpace() throws Exception {
    File card = fullCard();
    saveConnection();
    saveConfig("config1", "Musik", Uri.fromFile(card), 0);

    ListenableWorker.Result result = runWorker("config1");

    assertNotEquals(ListenableWorker.Result.success(), result);
    List<String> entries = actionLog.getEntries();
    assertEquals(entries.toString(), 1, entries.size());
    String entry = entries.get(0);
    assertTrue(entry, entry.contains("⛔ Aborted, low disk space: Musik - sync not started"));
    assertTrue(entry, entry.contains(card.getPath() + ": 3.0 MB free, app storage: 512.0 MB free"));
  }

  @Test
  public void doWork_fullSdCard_doesNotHoldBackSyncsOntoOtherVolumes() throws Exception {
    File card = fullCard();
    saveConnection();
    saveConfig("config1", "Musik", Uri.fromFile(card), 60);
    Uri shared =
        DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents", "primary:Hoerspiele");
    saveConfig("config2", "Hoerspiele", shared, 60);

    runWorker(null); // periodic run over all configs

    List<String> entries = actionLog.getEntries();
    assertEquals(entries.toString(), 2, entries.size());
    assertTrue(entries.get(0), entries.get(0).contains("⛔ Aborted, low disk space: Musik"));
    // The second config is attempted; it fails only because the test has no SAF provider
    assertTrue(entries.get(1), entries.get(1).contains("✗ Error: Hoerspiele - sync failed: "));
  }

  /** Registers app and shared storage with 512 MB free and returns a card with 3 MB free. */
  private File fullCard() throws Exception {
    int blocks = (int) (512L * 1024 * 1024 / 4096);
    ShadowStatFs.registerStats(context.getFilesDir().getPath(), blocks + 10, blocks, blocks);
    ShadowStatFs.registerStats(
        Environment.getExternalStorageDirectory().getPath(), blocks + 10, blocks, blocks);
    File card = tmp.newFolder("427D-0786");
    int cardBlocks = (int) (3L * 1024 * 1024 / 4096); // below the 10 MB limit
    ShadowStatFs.registerStats(card.getPath(), cardBlocks + 10, cardBlocks, cardBlocks);
    return card;
  }

  private void saveConnection() {
    SmbConnection connection = new SmbConnection();
    connection.setId("conn1");
    connection.setName("NAS");
    connection.setServer("127.0.0.1"); // never contacted, the local checks fail first
    connection.setShare("music");
    connection.setUsername("user");
    connection.setPassword("secret");
    connection.setDomain("");
    ConnectionRepositoryImpl connections = new ConnectionRepositoryImpl(context);
    connections.saveConnection(connection);
    assertEquals(1, connections.getAllConnections().size());
  }

  private void saveConfig(String id, String name, Uri localFolder, int intervalMinutes) {
    SyncConfig config = new SyncConfig();
    config.setId(id);
    config.setConnectionId("conn1");
    config.setEnabled(true);
    config.setIntervalMinutes(intervalMinutes);
    config.setLocalFolderUri(localFolder.toString());
    config.setLocalFolderDisplayName(name);
    config.setRemotePath("/" + name.toLowerCase());
    new SyncRepository(context).saveSyncConfig(config);
  }

  private ListenableWorker.Result runWorker(String configId) {
    Data.Builder input = new Data.Builder();
    if (configId != null) input.putString(FolderSyncWorker.KEY_SYNC_CONFIG_ID, configId);
    FolderSyncWorker worker =
        TestWorkerBuilder.from(context, FolderSyncWorker.class, Executors.newSingleThreadExecutor())
            .setInputData(input.build())
            .build();
    return worker.doWork();
  }
}
