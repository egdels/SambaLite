package de.schliweb.sambalite.sync;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.work.testing.TestWorkerBuilder;
import java.util.concurrent.Executors;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Tests for the getMimeType method in FolderSyncWorker. Verifies that common file extensions are
 * mapped to the correct MIME types and that unknown extensions fall back to
 * application/octet-stream.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FolderSyncWorkerGetMimeTypeTest {

  private FolderSyncWorker worker;

  @Before
  public void setUp() {
    Context context = ApplicationProvider.getApplicationContext();
    worker =
        TestWorkerBuilder.from(context, FolderSyncWorker.class, Executors.newSingleThreadExecutor())
            .build();
  }

  @Test
  public void testNull() {
    assertEquals("application/octet-stream", worker.getMimeType(null));
  }

  @Test
  public void testNoExtension() {
    assertEquals("application/octet-stream", worker.getMimeType("README"));
  }

  @Test
  public void testEmptyExtension() {
    assertEquals("application/octet-stream", worker.getMimeType("file."));
  }

  @Test
  public void testJpg() {
    assertEquals("image/jpeg", worker.getMimeType("photo.jpg"));
  }

  @Test
  public void testJpeg() {
    assertEquals("image/jpeg", worker.getMimeType("photo.jpeg"));
  }

  @Test
  public void testJpgUpperCase() {
    assertEquals("image/jpeg", worker.getMimeType("PHOTO.JPG"));
  }

  @Test
  public void testPng() {
    assertEquals("image/png", worker.getMimeType("image.png"));
  }

  @Test
  public void testGif() {
    assertEquals("image/gif", worker.getMimeType("animation.gif"));
  }

  @Test
  public void testPdf() {
    assertEquals("application/pdf", worker.getMimeType("document.pdf"));
  }

  @Test
  public void testTxt() {
    assertEquals("text/plain", worker.getMimeType("notes.txt"));
  }

  @Test
  public void testMp4() {
    assertEquals("video/mp4", worker.getMimeType("video.mp4"));
  }

  @Test
  public void testMp3() {
    assertEquals("audio/mpeg", worker.getMimeType("song.mp3"));
  }

  @Test
  public void testDocx() {
    assertEquals(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        worker.getMimeType("document.docx"));
  }

  @Test
  public void testXlsx() {
    assertEquals(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        worker.getMimeType("tabelle.xlsx"));
  }

  @Test
  public void testZip() {
    assertEquals("application/zip", worker.getMimeType("archiv.zip"));
  }

  @Test
  public void testHtml() {
    assertEquals("text/html", worker.getMimeType("page.html"));
  }

  @Test
  public void testJson() {
    assertEquals("application/json", worker.getMimeType("data.json"));
  }

  @Test
  public void testXml() {
    assertEquals("text/xml", worker.getMimeType("config.xml"));
  }

  @Test
  public void testWebp() {
    assertEquals("image/webp", worker.getMimeType("bild.webp"));
  }

  @Test
  public void testFlac() {
    assertEquals("audio/flac", worker.getMimeType("musik.flac"));
  }

  @Test
  public void testMultipleDots() {
    assertEquals("application/gzip", worker.getMimeType("archive.tar.gz"));
  }

  @Test
  public void testTqd_unknownExtension() {
    // .tqd is not a known MIME type — falls back to application/octet-stream
    assertEquals("application/octet-stream", worker.getMimeType("datei.tqd"));
  }
}
