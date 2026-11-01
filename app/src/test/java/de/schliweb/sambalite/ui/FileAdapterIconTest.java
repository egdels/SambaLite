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

import de.schliweb.sambalite.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests the file type icons shown when thumbnails are off or not available. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FileAdapterIconTest {

  private final FileAdapter adapter = new FileAdapter();

  @Test
  public void phoneCameraFormats_getTheImageIcon() {
    assertEquals(R.drawable.ic_image, adapter.getFileIcon("IMG_0001.HEIC"));
    assertEquals(R.drawable.ic_image, adapter.getFileIcon("photo.heif"));
    assertEquals(R.drawable.ic_image, adapter.getFileIcon("raw.dng"));
    assertEquals(R.drawable.ic_image, adapter.getFileIcon("pic.avif"));
  }

  @Test
  public void moreVideoAndAudioFormats_getTheirIcons() {
    assertEquals(R.drawable.ic_movie, adapter.getFileIcon("clip.webm"));
    assertEquals(R.drawable.ic_movie, adapter.getFileIcon("video.3gp"));
    assertEquals(R.drawable.ic_music_note, adapter.getFileIcon("voice.opus"));
    assertEquals(R.drawable.ic_music_note, adapter.getFileIcon("song.aac"));
  }

  @Test
  public void unknownAndMissingNames_getTheGenericFileIcon() {
    assertEquals(R.drawable.ic_file, adapter.getFileIcon("data.xyz"));
    assertEquals(R.drawable.ic_file, adapter.getFileIcon(null));
  }
}
