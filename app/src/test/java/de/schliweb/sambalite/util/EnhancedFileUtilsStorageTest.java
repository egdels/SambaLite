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

import android.os.StatFs;
import java.io.File;
import org.junit.Test;
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
}
