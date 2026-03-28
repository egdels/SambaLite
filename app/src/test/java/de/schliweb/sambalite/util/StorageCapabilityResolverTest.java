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

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class StorageCapabilityResolverTest {

  @Test
  public void testFileUri_returnsSupported() {
    Uri uri = Uri.parse("file:///storage/emulated/0/Download/test.txt");
    assertEquals(TimestampCapability.PRESERVE_SUPPORTED, StorageCapabilityResolver.resolve(uri));
  }

  @Test
  public void testMediaStoreUri_returnsBestEffort() {
    Uri uri = Uri.parse("content://media/external/images/media/42");
    assertEquals(TimestampCapability.PRESERVE_BEST_EFFORT, StorageCapabilityResolver.resolve(uri));
  }

  @Test
  public void testExternalStorageDocumentsUri_returnsBestEffort() {
    Uri uri =
        Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3ADownload/document/primary%3ADownload%2Ftest.txt");
    assertEquals(TimestampCapability.PRESERVE_BEST_EFFORT, StorageCapabilityResolver.resolve(uri));
  }

  @Test
  public void testSafTreeUri_otherProvider_returnsUnreliable() {
    Uri uri =
        Uri.parse(
            "content://com.android.providers.downloads.documents/tree/raw%3A%2Fstorage%2Femulated%2F0%2FDownload");
    assertEquals(TimestampCapability.PRESERVE_UNRELIABLE, StorageCapabilityResolver.resolve(uri));
  }

  @Test
  public void testUnknownScheme_returnsUnreliable() {
    Uri uri = Uri.parse("ftp://server/file.txt");
    assertEquals(TimestampCapability.PRESERVE_UNRELIABLE, StorageCapabilityResolver.resolve(uri));
  }

  @Test
  public void testContentUri_unknownAuthority_returnsUnreliable() {
    Uri uri = Uri.parse("content://com.example.provider/files/1");
    assertEquals(TimestampCapability.PRESERVE_UNRELIABLE, StorageCapabilityResolver.resolve(uri));
  }
}
