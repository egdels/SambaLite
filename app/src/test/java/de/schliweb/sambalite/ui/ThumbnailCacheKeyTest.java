/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/** A thumbnail is cached per connection and per version of the remote file. */
public class ThumbnailCacheKeyTest {

  private static final String PATH = "Photos/2026/img0.jpg";

  @Test
  public void sameFile_sameKey() {
    assertEquals(
        ThumbnailManager.cacheKeyOf("c1", PATH, 8175, 1000L),
        ThumbnailManager.cacheKeyOf("c1", PATH, 8175, 1000L));
  }

  @Test
  public void fileChangedOnServer_newKey() {
    String before = ThumbnailManager.cacheKeyOf("c1", PATH, 8175, 1000L);
    assertNotEquals(before, ThumbnailManager.cacheKeyOf("c1", PATH, 7926, 1000L));
    assertNotEquals(before, ThumbnailManager.cacheKeyOf("c1", PATH, 8175, 2000L));
  }

  @Test
  public void samePathOnOtherConnection_otherKey() {
    assertNotEquals(
        ThumbnailManager.cacheKeyOf("c1", PATH, 8175, 1000L),
        ThumbnailManager.cacheKeyOf("c2", PATH, 8175, 1000L));
  }
}
