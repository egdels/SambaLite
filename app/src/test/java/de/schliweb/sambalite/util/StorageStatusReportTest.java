/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowStatFs;

/** Tests for the disk space section of the System Monitor. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class StorageStatusReportTest {

  private static final long BLOCK = 4096;

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private File internal;
  private File storage;
  private File primary;
  private File sdCard;

  @Before
  public void setUp() throws Exception {
    internal = tmp.newFolder("data");
    storage = tmp.newFolder("storage");
    primary = new File(storage, "emulated/0");
    sdCard = new File(storage, "427D-0786");
    assertTrue(primary.mkdirs());
    assertTrue(new File(sdCard, "Music").mkdirs());
    register(internal, 2048, 1024);
    register(primary, 4096, 2048);
  }

  @Test
  public void build_listsSdCardWithItsSyncTargetAndWarnsWhenItIsFull() {
    register(sdCard, 1024, 5); // 5 MB free, below the 10 MB limit
    List<File> appDirs =
        Arrays.asList(
            new File(primary, "Android/data/de.schliweb.sambalite/files"),
            new File(sdCard, "Android/data/de.schliweb.sambalite/files"));

    String report =
        StorageStatusReport.build(
            internal,
            primary,
            storage,
            appDirs,
            Collections.singletonList(
                new StorageStatusReport.Target("Music ← /musik_neu", sdTree("Music"))));

    String sdHeader = "SD card / USB (" + sdCard.getAbsolutePath() + "):";
    assertTrue(report, report.contains("Internal app storage:\n- Used: 1.0 GB / 2.0 GB\n- Free: 1.0 GB\n"));
    assertTrue(report, report.contains("Shared storage (" + primary.getAbsolutePath() + "):"));
    assertTrue(
        report,
        report.contains(
            sdHeader + "\n- Used: 1019.0 MB / 1.0 GB\n- Free: 5.0 MB ⚠\n- Sync target: Music ← /musik_neu\n"));
    assertTrue(report, report.contains("⚠ WARNING: Low disk space on SD card / USB ("));
    assertFalse("only the SD card is low", report.contains("on Internal"));
  }

  @Test
  public void build_showsSdCardOfASyncTargetEvenWithoutAppDirectoryOnIt() {
    register(sdCard, 1024, 512);

    String report =
        StorageStatusReport.build(
            internal,
            primary,
            storage,
            Collections.emptyList(),
            Collections.singletonList(new StorageStatusReport.Target("Music", sdTree("Music"))));

    assertTrue(report, report.contains("SD card / USB (" + sdCard.getAbsolutePath() + "):"));
    assertTrue(report, report.contains("- Sync target: Music\n"));
    assertFalse(report, report.contains("WARNING"));
  }

  @Test
  public void build_mapsPrimaryTargetsToSharedStorageAndListsForeignProvidersSeparately() {
    Uri primaryTree = DocumentsContract.buildTreeDocumentUri(
        EnhancedFileUtils.EXTERNAL_STORAGE_AUTHORITY, "primary:Download");
    Uri cloud = DocumentsContract.buildTreeDocumentUri("com.example.cloud", "root");

    String report =
        StorageStatusReport.build(
            internal,
            primary,
            storage,
            Collections.emptyList(),
            Arrays.asList(
                new StorageStatusReport.Target("Downloads", primaryTree),
                new StorageStatusReport.Target("Cloud", cloud),
                new StorageStatusReport.Target("No folder", null)));

    assertTrue(
        report,
        report.contains(
            "Shared storage ("
                + primary.getAbsolutePath()
                + "):\n- Used: 2.0 GB / 4.0 GB\n- Free: 2.0 GB\n- Sync target: Downloads\n"));
    assertTrue(
        report,
        report.contains("Sync targets on other providers (space not checked):\n- Cloud\n- No folder\n"));
    assertEquals("shared storage is listed once", 1, count(report, "Shared storage ("));
  }

  @Test
  public void volumeRootOf_mapsAppDirectoriesToTheirMountPoint() {
    assertEquals(
        primary,
        StorageStatusReport.volumeRootOf(new File(primary, "Android/data/x"), primary, storage));
    assertEquals(
        sdCard,
        StorageStatusReport.volumeRootOf(new File(sdCard, "Android/data/x"), primary, storage));
    assertEquals(sdCard, StorageStatusReport.volumeRootOf(sdCard, primary, storage));
    assertNull(StorageStatusReport.volumeRootOf(new File("/data/user/0/x"), primary, storage));
  }

  @Test
  public void describeFreeSpace_namesTheTargetVolume() {
    register(sdCard, 1024, 5);

    assertEquals(
        sdCard.getPath() + ": 5.0 MB free, app storage: 1.0 GB free",
        EnhancedFileUtils.describeFreeSpace(internal, sdCard));
  }

  private Uri sdTree(String path) {
    return DocumentsContract.buildTreeDocumentUri(
        EnhancedFileUtils.EXTERNAL_STORAGE_AUTHORITY, sdCard.getName() + ":" + path);
  }

  /** Registers a volume of {@code totalMb} with {@code freeMb} available. */
  private static void register(File dir, long totalMb, long freeMb) {
    int total = (int) (totalMb * 1024 * 1024 / BLOCK);
    int free = (int) (freeMb * 1024 * 1024 / BLOCK);
    ShadowStatFs.registerStats(dir.getPath(), total, free, free);
  }

  private static int count(String s, String part) {
    int n = 0;
    for (int i = s.indexOf(part); i >= 0; i = s.indexOf(part, i + 1)) n++;
    return n;
  }
}
