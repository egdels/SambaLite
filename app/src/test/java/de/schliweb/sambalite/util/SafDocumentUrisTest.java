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

import android.net.Uri;
import android.provider.DocumentsContract;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SafDocumentUrisTest {

  private static final String AUTH = "com.android.externalstorage.documents";

  @Test
  public void parentDocumentId_pathShapedIds() {
    assertEquals("427D-0786:Music/a", SafDocumentUris.parentDocumentId("427D-0786:Music/a/b.mp3"));
    assertEquals("primary:", SafDocumentUris.parentDocumentId("primary:b.mp3"));
    assertEquals("raw:/storage/x", SafDocumentUris.parentDocumentId("raw:/storage/x/y.bin"));
    assertNull(SafDocumentUris.parentDocumentId("primary:"));
    assertNull(SafDocumentUris.parentDocumentId("opaque-id-without-path"));
    assertNull(SafDocumentUris.parentDocumentId(null));
  }

  @Test
  public void parentDocumentUri_staysWithinTree() {
    Uri tree = DocumentsContract.buildTreeDocumentUri(AUTH, "427D-0786:Music/Hoerspiele");
    Uri child =
        DocumentsContract.buildDocumentUriUsingTree(tree, "427D-0786:Music/Hoerspiele/ddf/1.mp3");

    Uri parent = SafDocumentUris.parentDocumentUri(child);

    assertEquals(
        DocumentsContract.buildDocumentUriUsingTree(tree, "427D-0786:Music/Hoerspiele/ddf"),
        parent);
    assertEquals("427D-0786:Music/Hoerspiele", DocumentsContract.getTreeDocumentId(parent));
  }

  @Test
  public void parentDocumentUri_treeRootHasNoParent() {
    Uri tree = DocumentsContract.buildTreeDocumentUri(AUTH, "primary:Download");
    Uri rootDoc = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:Download");
    assertNull(SafDocumentUris.parentDocumentUri(rootDoc));
  }

  @Test
  public void parentDocumentUri_plainDocumentUriIsNotSupported() {
    Uri single = DocumentsContract.buildDocumentUri(AUTH, "primary:Download/a.txt");
    assertNull(SafDocumentUris.parentDocumentUri(single));
  }
}
