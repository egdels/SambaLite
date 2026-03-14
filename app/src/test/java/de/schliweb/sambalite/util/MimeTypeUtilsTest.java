package de.schliweb.sambalite.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Tests for {@link MimeTypeUtils#getMimeType(String)}. Verifies correct MIME type resolution for
 * common extensions, edge cases, and unknown types.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MimeTypeUtilsTest {

  private static final String FALLBACK = "application/octet-stream";

  @Test
  public void testNull() {
    assertEquals(FALLBACK, MimeTypeUtils.getMimeType(null));
  }

  @Test
  public void testEmptyString() {
    assertEquals(FALLBACK, MimeTypeUtils.getMimeType(""));
  }

  @Test
  public void testNoExtension() {
    assertEquals(FALLBACK, MimeTypeUtils.getMimeType("README"));
  }

  @Test
  public void testEmptyExtension() {
    assertEquals(FALLBACK, MimeTypeUtils.getMimeType("file."));
  }

  @Test
  public void testPdf() {
    assertEquals("application/pdf", MimeTypeUtils.getMimeType("document.pdf"));
  }

  @Test
  public void testJpg() {
    assertEquals("image/jpeg", MimeTypeUtils.getMimeType("photo.jpg"));
  }

  @Test
  public void testJpgUpperCase() {
    assertEquals("image/jpeg", MimeTypeUtils.getMimeType("PHOTO.JPG"));
  }

  @Test
  public void testJpgMixedCase() {
    assertEquals("image/jpeg", MimeTypeUtils.getMimeType("Photo.JpG"));
  }

  @Test
  public void testPng() {
    assertEquals("image/png", MimeTypeUtils.getMimeType("image.png"));
  }

  @Test
  public void testGif() {
    assertEquals("image/gif", MimeTypeUtils.getMimeType("animation.gif"));
  }

  @Test
  public void testMp4() {
    assertEquals("video/mp4", MimeTypeUtils.getMimeType("video.mp4"));
  }

  @Test
  public void testMp3() {
    assertEquals("audio/mpeg", MimeTypeUtils.getMimeType("song.mp3"));
  }

  @Test
  public void testTxt() {
    assertEquals("text/plain", MimeTypeUtils.getMimeType("notes.txt"));
  }

  @Test
  public void testHtml() {
    assertEquals("text/html", MimeTypeUtils.getMimeType("page.html"));
  }

  @Test
  public void testZip() {
    assertEquals("application/zip", MimeTypeUtils.getMimeType("archiv.zip"));
  }

  @Test
  public void testDocx() {
    assertEquals(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        MimeTypeUtils.getMimeType("document.docx"));
  }

  @Test
  public void testXlsx() {
    assertEquals(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        MimeTypeUtils.getMimeType("tabelle.xlsx"));
  }

  @Test
  public void testMultipleDots() {
    assertEquals("application/gzip", MimeTypeUtils.getMimeType("archive.tar.gz"));
  }

  @Test
  public void testPathWithDirectories() {
    assertEquals("application/pdf", MimeTypeUtils.getMimeType("/some/path/to/file.pdf"));
  }

  @Test
  public void testUnknownExtension() {
    assertEquals(FALLBACK, MimeTypeUtils.getMimeType("datei.tqd"));
  }

  @Test
  public void testWebp() {
    assertEquals("image/webp", MimeTypeUtils.getMimeType("bild.webp"));
  }

  @Test
  public void testJson() {
    assertEquals("application/json", MimeTypeUtils.getMimeType("data.json"));
  }

  @Test
  public void testXml() {
    assertEquals("text/xml", MimeTypeUtils.getMimeType("config.xml"));
  }
}
