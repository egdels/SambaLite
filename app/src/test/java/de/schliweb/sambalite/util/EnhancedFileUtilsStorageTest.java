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

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;
import android.provider.DocumentsContract;
import androidx.test.core.app.ApplicationProvider;
import java.io.File;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowStatFs;

/**
 * Tests for EnhancedFileUtils disk space check.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class EnhancedFileUtilsStorageTest {

  @Test
  public void testHasEnoughDiskSpace_Success() {
    File mockDir = new File("/data/test");
    // Register the path in ShadowStatFs to control available bytes
    // Robolectric 4.x: use ShadowStatFs.registerStats
    // If that's also failing, it might be an older/newer version or different ShadowStatFs
    // Let's try registerStats again with correct types or if not available, it might be due to incorrect import
    ShadowStatFs.registerStats(mockDir.getPath(), 100, 50, 50); 
    
    // Set enough space: 20MB
    long twentyMB = 20 * 1024 * 1024;
    long blockSize = 4096;
    long blocks = twentyMB / blockSize;
    ShadowStatFs.registerStats(mockDir.getPath(), (int)blocks + 10, (int)blocks, (int)blocks);

    assertTrue("Should have enough space", EnhancedFileUtils.hasEnoughDiskSpace(mockDir));
  }

  @Test
  public void testHasEnoughDiskSpace_Failure() {
    File mockDir = new File("/data/low_space");
    // Set low space: 5MB (less than 10MB limit)
    long fiveMB = 5 * 1024 * 1024;
    long blockSize = 4096;
    long blocks = fiveMB / blockSize;
    ShadowStatFs.registerStats(mockDir.getPath(), (int)blocks + 10, (int)blocks, (int)blocks);

    assertFalse("Should NOT have enough space", EnhancedFileUtils.hasEnoughDiskSpace(mockDir));
  }

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void volumeRoot_primaryAndSdCardDocIds() {
    File primary = new File("/storage/emulated/0");
    File storage = new File("/storage");
    assertEquals(
        primary,
        EnhancedFileUtils.volumeRootForExternalStorageDocId("primary:Music/a.mp3", primary, storage));
    assertEquals(
        new File("/storage/427D-0786"),
        EnhancedFileUtils.volumeRootForExternalStorageDocId(
            "427D-0786:Music/Hoerspiele", primary, storage));
    assertEquals(
        new File("/storage/427D-0786"),
        EnhancedFileUtils.volumeRootForExternalStorageDocId("427D-0786:", primary, storage));
    assertNull(EnhancedFileUtils.volumeRootForExternalStorageDocId(null, primary, storage));
    assertNull(EnhancedFileUtils.volumeRootForExternalStorageDocId(":Music", primary, storage));
    assertNull(EnhancedFileUtils.volumeRootForExternalStorageDocId("../x:Music", primary, storage));
  }

  @Test
  public void resolveVolumeRoot_readsVolumeFromTreeAndDocumentUris() throws Exception {
    File storage = tmp.newFolder("storage");
    File sd = new File(storage, "427D-0786");
    assertTrue(sd.mkdirs());
    File primary = tmp.newFolder("primary");
    Uri tree =
        DocumentsContract.buildTreeDocumentUri(
            EnhancedFileUtils.EXTERNAL_STORAGE_AUTHORITY, "427D-0786:Music/Hoerspiele_neu");
    assertEquals(sd, EnhancedFileUtils.resolveVolumeRoot(tree, primary, storage));
    Uri doc =
        DocumentsContract.buildDocumentUriUsingTree(tree, "427D-0786:Music/Hoerspiele_neu/x.mp3");
    assertEquals(sd, EnhancedFileUtils.resolveVolumeRoot(doc, primary, storage));
    Uri onPrimary =
        DocumentsContract.buildTreeDocumentUri(
            EnhancedFileUtils.EXTERNAL_STORAGE_AUTHORITY, "primary:Music");
    assertEquals(primary, EnhancedFileUtils.resolveVolumeRoot(onPrimary, primary, storage));
  }

  @Test
  public void resolveVolumeRoot_unmountedVolumeIsNull() throws Exception {
    File storage = tmp.newFolder("storage");
    Uri tree =
        DocumentsContract.buildTreeDocumentUri(
            EnhancedFileUtils.EXTERNAL_STORAGE_AUTHORITY, "GONE-CARD:Music");
    assertNull(EnhancedFileUtils.resolveVolumeRoot(tree, tmp.newFolder("p"), storage));
  }

  @Test
  public void resolveVolumeRoot_unknownProviderIsNull() {
    Uri foreign =
        DocumentsContract.buildTreeDocumentUri("com.example.cloud.documents", "some-opaque-id");
    assertNull(EnhancedFileUtils.resolveVolumeRoot(foreign));
  }

  @Test
  public void hasEnoughDiskSpaceForVolume_fullTargetVolumeFailsEvenIfPrimaryHasSpace()
      throws Exception {
    Context ctx = ApplicationProvider.getApplicationContext();
    long blockSize = 4096;
    int okBlocks = (int) ((50L * 1024 * 1024) / blockSize);
    int fullBlocks = (int) ((5L * 1024 * 1024) / blockSize); // 5 MB: below the 10 MB limit
    ShadowStatFs.registerStats(ctx.getFilesDir().getPath(), okBlocks + 10, okBlocks, okBlocks);
    ShadowStatFs.registerStats(
        Environment.getExternalStorageDirectory().getPath(), okBlocks + 10, okBlocks, okBlocks);
    File sd = tmp.newFolder("FULL-CARD");
    ShadowStatFs.registerStats(sd.getPath(), fullBlocks + 10, fullBlocks, fullBlocks);

    assertFalse(EnhancedFileUtils.hasEnoughDiskSpaceForVolume(ctx, sd));
    assertTrue(EnhancedFileUtils.hasEnoughDiskSpaceForVolume(ctx, null));
  }
}
