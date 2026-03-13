package de.schliweb.sambalite.ui.utils;

import static org.junit.Assert.*;

import org.junit.Test;

/** Unit tests for {@link ProgressFormat}. */
public class ProgressFormatTest {

  // ── Op.fromString ──

  @Test
  public void opFromString_download() {
    assertEquals(ProgressFormat.Op.DOWNLOAD, ProgressFormat.Op.fromString("Downloading"));
  }

  @Test
  public void opFromString_upload() {
    assertEquals(ProgressFormat.Op.UPLOAD, ProgressFormat.Op.fromString("uploading"));
  }

  @Test
  public void opFromString_delete() {
    assertEquals(ProgressFormat.Op.DELETE, ProgressFormat.Op.fromString("Deleting"));
  }

  @Test
  public void opFromString_rename() {
    assertEquals(ProgressFormat.Op.RENAME, ProgressFormat.Op.fromString("Renaming"));
  }

  @Test
  public void opFromString_search() {
    assertEquals(ProgressFormat.Op.SEARCH, ProgressFormat.Op.fromString("Searching"));
  }

  @Test
  public void opFromString_finalizing() {
    assertEquals(ProgressFormat.Op.FINALIZING, ProgressFormat.Op.fromString("Finalizing"));
  }

  @Test
  public void opFromString_unknown() {
    assertEquals(ProgressFormat.Op.UNKNOWN, ProgressFormat.Op.fromString("something"));
  }

  @Test
  public void opLabel_returnsLabel() {
    assertEquals("Downloading", ProgressFormat.Op.DOWNLOAD.label());
    assertEquals("Processing", ProgressFormat.Op.UNKNOWN.label());
  }

  // ── parsePercent ──

  @Test
  public void parsePercent_simplePercent() {
    assertEquals(42, ProgressFormat.parsePercent("42%"));
  }

  @Test
  public void parsePercent_bracketFormat() {
    assertEquals(75, ProgressFormat.parsePercent("[PROGRESS:75:3:10]file.txt"));
  }

  @Test
  public void parsePercent_emptyString() {
    assertEquals(0, ProgressFormat.parsePercent(""));
  }

  @Test
  public void parsePercent_noPercent() {
    assertEquals(0, ProgressFormat.parsePercent("no percent here"));
  }

  @Test
  public void parsePercent_clampsTo100() {
    assertEquals(100, ProgressFormat.parsePercent("150%"));
  }

  @Test
  public void parsePercent_percentInText() {
    assertEquals(50, ProgressFormat.parsePercent("Downloading: 50% complete"));
  }

  // ── formatIdx ──

  @Test
  public void formatIdx_simpleFileName() {
    assertEquals(
        "Downloading: 1/5 - test.mp3", ProgressFormat.formatIdx("Downloading", 1, 5, "test.mp3"));
  }

  @Test
  public void formatIdx_pathExtractsBaseName() {
    assertEquals(
        "Uploading: 2/10 - file.txt",
        ProgressFormat.formatIdx("Uploading", 2, 10, "/path/to/file.txt"));
  }

  @Test
  public void formatIdx_backslashPath() {
    assertEquals(
        "Deleting: 3/7 - doc.pdf", ProgressFormat.formatIdx("Deleting", 3, 7, "folder\\doc.pdf"));
  }

  @Test
  public void formatIdx_nullFileName() {
    assertEquals("Searching: 1/1 - ", ProgressFormat.formatIdx("Searching", 1, 1, null));
  }

  // ── formatBytes / formatBytesOnly ──

  @Test
  public void formatBytesOnly_formatsCorrectly() {
    String result = ProgressFormat.formatBytesOnly(1024, 2048);
    assertTrue(result.contains("/"));
  }

  @Test
  public void formatBytes_includesVerb() {
    String result = ProgressFormat.formatBytes("Downloading", 512, 1024);
    assertTrue(result.startsWith("Downloading: "));
  }

  // ── normalizePercentInStatus ──

  @Test
  public void normalizePercentInStatus_replacesPercent() {
    String result = ProgressFormat.normalizePercentInStatus("Progress: 50% done", 75);
    assertNotNull(result);
    assertFalse(result.isEmpty());
  }

  @Test
  public void normalizePercentInStatus_nullStatus() {
    assertEquals("", ProgressFormat.normalizePercentInStatus(null, 50));
  }

  @Test
  public void normalizePercentInStatus_clampsPercent() {
    String result = ProgressFormat.normalizePercentInStatus("Status: 50% ok", 200);
    assertNotNull(result);
    assertFalse(result.isEmpty());
  }

  // ── percentOfBytes ──

  @Test
  public void percentOfBytes_halfDone() {
    assertEquals(50, ProgressFormat.percentOfBytes(500_000, 1_000_000));
  }

  @Test
  public void percentOfBytes_zeroDone() {
    assertEquals(0, ProgressFormat.percentOfBytes(0, 1_000_000));
  }

  @Test
  public void percentOfBytes_zeroTotal() {
    assertEquals(0, ProgressFormat.percentOfBytes(0, 0));
  }

  @Test
  public void percentOfBytes_complete() {
    assertEquals(100, ProgressFormat.percentOfBytes(1_000_000, 1_000_000));
  }

  // ── parse ──

  @Test
  public void parse_prefixedDownload() {
    ProgressFormat.Result r = ProgressFormat.parse("Downloading: 50% - file.mp3", 0, "fallback");
    assertEquals(ProgressFormat.Op.DOWNLOAD, r.op());
  }

  @Test
  public void parse_bracketFormat() {
    ProgressFormat.Result r = ProgressFormat.parse("[PROGRESS:80:5:10]song.mp3", 0, "fallback");
    assertEquals(80, r.overallPct());
  }

  @Test
  public void parse_emptyString_usesFallback() {
    ProgressFormat.Result r = ProgressFormat.parse("", 42, "fallback.txt");
    assertEquals(42, r.overallPct());
  }

  @Test
  public void parse_pctIdxName() {
    ProgressFormat.Result r = ProgressFormat.parse("75% (3/10) - myfile.txt", 0, "fallback");
    assertTrue(r.cur().isPresent());
    assertEquals(Integer.valueOf(3), r.cur().get());
    assertTrue(r.total().isPresent());
    assertEquals(Integer.valueOf(10), r.total().get());
  }

  @Test
  public void parse_idxName() {
    ProgressFormat.Result r = ProgressFormat.parse("5/20 - document.pdf", 0, "fallback");
    assertTrue(r.cur().isPresent());
    assertEquals(Integer.valueOf(5), r.cur().get());
  }

  @Test
  public void parse_pctName() {
    ProgressFormat.Result r = ProgressFormat.parse("60% - photo.jpg", 0, "fallback");
    assertTrue(r.fileName().isPresent());
    assertTrue(r.fileName().get().contains("photo"));
  }

  // ── buildUnified ──

  @Test
  public void buildUnified_formatsCorrectly() {
    String result = ProgressFormat.buildUnified(ProgressFormat.Op.DOWNLOAD, 1, 5, "test.mp3");
    assertNotNull(result);
    assertFalse(result.isEmpty());
  }

  // ── toHeadlineNoPercent ──

  @Test
  public void toHeadlineNoPercent_returnsNonEmpty() {
    ProgressFormat.Result r =
        ProgressFormat.parse("Downloading: 50% (1/5) - file.mp3", 50, "file.mp3");
    String headline = ProgressFormat.toHeadlineNoPercent(r);
    assertNotNull(headline);
  }

  // ── toDetailsFilename ──

  @Test
  public void toDetailsFilename_truncatesLongName() {
    ProgressFormat.Result r =
        ProgressFormat.parse("50% - averylongfilenamethatexceedslimit.mp3", 50, "fallback");
    String details = ProgressFormat.toDetailsFilename(r, 15);
    assertTrue(details.length() <= 16); // maxLen + ellipsis char
  }
}
