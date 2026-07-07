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
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import java.io.File;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** Tests for the MediaStorePathResolver class. */
@RunWith(RobolectricTestRunner.class)
public class MediaStorePathResolverTest {

  private Context context;

  @Before
  public void setUp() {
    LogUtils.init(true);
    context = ApplicationProvider.getApplicationContext();
  }

  // ===== resolveToFile =====

  @Test
  public void fileUri_resolvesToDirectPath() {
    Uri uri = Uri.parse("file:///storage/emulated/0/Download/test.txt");
    File resolved = MediaStorePathResolver.resolveToFile(context, uri);
    assertEquals("/storage/emulated/0/Download/test.txt", resolved.getAbsolutePath());
  }

  @Test
  public void unknownScheme_returnsNull() {
    Uri uri = Uri.parse("ftp://server/file.txt");
    assertNull(MediaStorePathResolver.resolveToFile(context, uri));
  }

  @Test
  public void contentUri_unknownAuthority_returnsNull() {
    Uri uri = Uri.parse("content://com.example.provider/files/1");
    assertNull(MediaStorePathResolver.resolveToFile(context, uri));
  }

  @Test
  public void mediaStoreUri_withoutProvider_returnsNullGracefully() {
    Uri uri = Uri.parse("content://media/external/images/media/42");
    assertNull(MediaStorePathResolver.resolveToFile(context, uri));
  }

  @Test
  public void externalStorageDocumentUri_primaryVolume_resolvesUnderExternalStorage() {
    Uri uri =
        Uri.parse(
            "content://com.android.externalstorage.documents/document/primary%3ADownload%2Ftest.txt");
    File resolved = MediaStorePathResolver.resolveToFile(context, uri);
    assertEquals("Download/test.txt should be resolved relative to external storage",
        true, resolved.getAbsolutePath().endsWith("/Download/test.txt"));
  }

  // ===== resolveExternalStorageDocId =====

  @Test
  public void docId_primaryVolume_resolvesRelativeToPrimaryRoot() {
    File root = new File("/storage/emulated/0");
    File resolved = MediaStorePathResolver.resolveExternalStorageDocId("primary:Pictures/photo.jpg", root);
    assertEquals("/storage/emulated/0/Pictures/photo.jpg", resolved.getAbsolutePath());
  }

  @Test
  public void docId_secondaryVolume_resolvesUnderStorage() {
    File root = new File("/storage/emulated/0");
    File resolved =
        MediaStorePathResolver.resolveExternalStorageDocId("1234-5678:DCIM/img.jpg", root);
    assertEquals("/storage/1234-5678/DCIM/img.jpg", resolved.getAbsolutePath());
  }

  @Test
  public void docId_null_returnsNull() {
    assertNull(MediaStorePathResolver.resolveExternalStorageDocId(null, new File("/tmp")));
  }

  @Test
  public void docId_withoutColon_returnsNull() {
    assertNull(MediaStorePathResolver.resolveExternalStorageDocId("invalid", new File("/tmp")));
  }

  @Test
  public void docId_emptyRelativePath_returnsNull() {
    assertNull(MediaStorePathResolver.resolveExternalStorageDocId("primary:", new File("/tmp")));
  }

  @Test
  public void docId_pathTraversal_returnsNull() {
    assertNull(
        MediaStorePathResolver.resolveExternalStorageDocId(
            "primary:../../../etc/passwd", new File("/tmp")));
  }

  @Test
  public void docId_emptyVolume_returnsNull() {
    assertNull(
        MediaStorePathResolver.resolveExternalStorageDocId(":Download/test.txt", new File("/tmp")));
  }
}
