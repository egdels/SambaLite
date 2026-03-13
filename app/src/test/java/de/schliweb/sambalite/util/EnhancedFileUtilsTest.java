package de.schliweb.sambalite.util;

import static org.junit.Assert.*;

import org.junit.Test;

/** Unit tests for {@link EnhancedFileUtils}. */
public class EnhancedFileUtilsTest {

  // --- formatFileSize ---

  @Test
  public void formatFileSize_negative_returnsUnknown() {
    assertEquals("Unknown", EnhancedFileUtils.formatFileSize(-1));
  }

  @Test
  public void formatFileSize_zero_returnsBytes() {
    assertEquals("0 B", EnhancedFileUtils.formatFileSize(0));
  }

  @Test
  public void formatFileSize_bytes() {
    assertEquals("512 B", EnhancedFileUtils.formatFileSize(512));
  }

  @Test
  public void formatFileSize_kilobytes() {
    String result = EnhancedFileUtils.formatFileSize(2048);
    assertTrue(result.contains("KB"));
    assertTrue(result.contains("2.0"));
  }

  @Test
  public void formatFileSize_megabytes() {
    String result = EnhancedFileUtils.formatFileSize(5 * 1024 * 1024);
    assertTrue(result.contains("MB"));
    assertTrue(result.contains("5.0"));
  }

  @Test
  public void formatFileSize_gigabytes() {
    String result = EnhancedFileUtils.formatFileSize(2L * 1024 * 1024 * 1024);
    assertTrue(result.contains("GB"));
    assertTrue(result.contains("2.0"));
  }

  // --- getFileExtension ---

  @Test
  public void getFileExtension_normalFile() {
    assertEquals("txt", EnhancedFileUtils.getFileExtension("readme.txt"));
  }

  @Test
  public void getFileExtension_multipleDotsReturnsLast() {
    assertEquals("gz", EnhancedFileUtils.getFileExtension("archive.tar.gz"));
  }

  @Test
  public void getFileExtension_noExtension() {
    assertEquals("", EnhancedFileUtils.getFileExtension("Makefile"));
  }

  @Test
  public void getFileExtension_dotAtEnd() {
    assertEquals("", EnhancedFileUtils.getFileExtension("file."));
  }

  @Test
  public void getFileExtension_emptyString() {
    assertEquals("", EnhancedFileUtils.getFileExtension(""));
  }

  @Test
  public void getFileExtension_uppercaseIsLowered() {
    assertEquals("jpg", EnhancedFileUtils.getFileExtension("photo.JPG"));
  }

  // --- getFileType ---

  @Test
  public void getFileType_image() {
    assertEquals(EnhancedFileUtils.FileType.IMAGE, EnhancedFileUtils.getFileType("photo.jpg"));
    assertEquals(EnhancedFileUtils.FileType.IMAGE, EnhancedFileUtils.getFileType("icon.png"));
    assertEquals(EnhancedFileUtils.FileType.IMAGE, EnhancedFileUtils.getFileType("anim.gif"));
  }

  @Test
  public void getFileType_video() {
    assertEquals(EnhancedFileUtils.FileType.VIDEO, EnhancedFileUtils.getFileType("movie.mp4"));
    assertEquals(EnhancedFileUtils.FileType.VIDEO, EnhancedFileUtils.getFileType("clip.mkv"));
  }

  @Test
  public void getFileType_audio() {
    assertEquals(EnhancedFileUtils.FileType.AUDIO, EnhancedFileUtils.getFileType("song.mp3"));
    assertEquals(EnhancedFileUtils.FileType.AUDIO, EnhancedFileUtils.getFileType("track.flac"));
  }

  @Test
  public void getFileType_document() {
    assertEquals(EnhancedFileUtils.FileType.DOCUMENT, EnhancedFileUtils.getFileType("report.pdf"));
    assertEquals(EnhancedFileUtils.FileType.DOCUMENT, EnhancedFileUtils.getFileType("doc.docx"));
  }

  @Test
  public void getFileType_archive() {
    assertEquals(EnhancedFileUtils.FileType.ARCHIVE, EnhancedFileUtils.getFileType("backup.zip"));
    assertEquals(EnhancedFileUtils.FileType.ARCHIVE, EnhancedFileUtils.getFileType("data.tar"));
  }

  @Test
  public void getFileType_unknown_noExtension() {
    assertEquals(EnhancedFileUtils.FileType.UNKNOWN, EnhancedFileUtils.getFileType("Makefile"));
  }

  @Test
  public void getFileType_other_unknownExtension() {
    assertEquals(EnhancedFileUtils.FileType.OTHER, EnhancedFileUtils.getFileType("data.xyz"));
  }
}
