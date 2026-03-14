package de.schliweb.sambalite.ui.utils;

import static org.junit.Assert.*;

import org.junit.Test;

/** Unit tests for {@link ProgressFormat}. */
public class ProgressFormatTest {

  // =========================================================================
  // Op.fromString
  // =========================================================================

  @Test
  public void opFromString_download() {
    assertEquals(ProgressFormat.Op.DOWNLOAD, ProgressFormat.Op.fromString("Downloading file"));
  }

  @Test
  public void opFromString_upload() {
    assertEquals(ProgressFormat.Op.UPLOAD, ProgressFormat.Op.fromString("Uploading data"));
  }

  @Test
  public void opFromString_delete() {
    assertEquals(ProgressFormat.Op.DELETE, ProgressFormat.Op.fromString("Deleting items"));
  }

  @Test
  public void opFromString_rename() {
    assertEquals(ProgressFormat.Op.RENAME, ProgressFormat.Op.fromString("Renaming file"));
  }

  @Test
  public void opFromString_search() {
    assertEquals(ProgressFormat.Op.SEARCH, ProgressFormat.Op.fromString("Searching..."));
  }

  @Test
  public void opFromString_finalizing() {
    assertEquals(ProgressFormat.Op.FINALIZING, ProgressFormat.Op.fromString("Finalizing op"));
  }

  @Test
  public void opFromString_unknown() {
    assertEquals(ProgressFormat.Op.UNKNOWN, ProgressFormat.Op.fromString("something else"));
  }

  @Test
  public void opLabel_returnsExpected() {
    assertEquals("Downloading", ProgressFormat.Op.DOWNLOAD.label());
    assertEquals("Processing", ProgressFormat.Op.UNKNOWN.label());
  }

  // =========================================================================
  // parsePercent
  // =========================================================================

  @Test
  public void parsePercent_bracketFormat() {
    assertEquals(42, ProgressFormat.parsePercent("[PROGRESS:42:3:10]file.txt"));
  }

  @Test
  public void parsePercent_simplePercent() {
    assertEquals(75, ProgressFormat.parsePercent("75% done"));
  }

  @Test
  public void parsePercent_emptyString_returnsZero() {
    assertEquals(0, ProgressFormat.parsePercent(""));
  }

  @Test
  public void parsePercent_noPercent_returnsZero() {
    assertEquals(0, ProgressFormat.parsePercent("no numbers here"));
  }

  @Test
  public void parsePercent_clampsTo100() {
    assertEquals(100, ProgressFormat.parsePercent("150%"));
  }

  // =========================================================================
  // percentOfBytes
  // =========================================================================

  @Test
  public void percentOfBytes_zeroTotal_returnsZero() {
    assertEquals(0, ProgressFormat.percentOfBytes(50, 0));
  }

  @Test
  public void percentOfBytes_negativeTotal_returnsZero() {
    assertEquals(0, ProgressFormat.percentOfBytes(50, -1));
  }

  @Test
  public void percentOfBytes_halfDone_returns50() {
    assertEquals(50, ProgressFormat.percentOfBytes(50000, 100000));
  }

  @Test
  public void percentOfBytes_almostDone_returns100() {
    // within 1024 bytes of total → 100
    assertEquals(100, ProgressFormat.percentOfBytes(9999, 10000));
  }

  @Test
  public void percentOfBytes_exactlyDone_returns100() {
    assertEquals(100, ProgressFormat.percentOfBytes(1000, 1000));
  }

  // =========================================================================
  // buildUnified
  // =========================================================================

  @Test
  public void buildUnified_formatsCorrectly() {
    String result = ProgressFormat.buildUnified(ProgressFormat.Op.DOWNLOAD, 3, 10, "test.txt");
    assertEquals("Downloading: 3/10 - test.txt", result);
  }

  @Test
  public void buildUnified_nullFileName_usesEmpty() {
    String result = ProgressFormat.buildUnified(ProgressFormat.Op.UPLOAD, 1, 5, null);
    assertEquals("Uploading: 1/5 - ", result);
  }

  // =========================================================================
  // formatIdx
  // =========================================================================

  @Test
  public void formatIdx_extractsBaseName() {
    String result = ProgressFormat.formatIdx("Downloading", 2, 5, "/path/to/file.txt");
    assertEquals("Downloading: 2/5 - file.txt", result);
  }

  @Test
  public void formatIdx_nullFileName() {
    String result = ProgressFormat.formatIdx("Uploading", 1, 3, null);
    assertEquals("Uploading: 1/3 - ", result);
  }

  // =========================================================================
  // normalizePercentInStatus
  // =========================================================================

  @Test
  public void normalizePercentInStatus_replacesAllPercents() {
    String result = ProgressFormat.normalizePercentInStatus("Progress: 30% done", 30);
    assertEquals("Progress: 30% done", result);
  }

  @Test
  public void normalizePercentInStatus_noMatchWithoutWordBoundary() {
    // "50% " has no \b after %, so percent not replaced when followed by space
    String result = ProgressFormat.normalizePercentInStatus("50% complete", -5);
    assertEquals("50% complete", result);
  }

  // =========================================================================
  // parse
  // =========================================================================

  @Test
  public void parse_bracketFormat_extractsFields() {
    ProgressFormat.Result r = ProgressFormat.parse("[PROGRESS:50:3:10]myfile.txt", 0, "");
    assertEquals(50, r.overallPct());
    assertTrue(r.cur().isPresent());
    assertEquals(Integer.valueOf(3), r.cur().get());
    assertTrue(r.total().isPresent());
    assertEquals(Integer.valueOf(10), r.total().get());
    assertEquals("myfile.txt", r.fileName().orElse(""));
  }

  @Test
  public void parse_prefixedFormat_detectsOp() {
    ProgressFormat.Result r = ProgressFormat.parse("Downloading: 3/10 - test.txt", 25, "");
    assertEquals(ProgressFormat.Op.DOWNLOAD, r.op());
    assertTrue(r.cur().isPresent());
    assertEquals(Integer.valueOf(3), r.cur().get());
    assertTrue(r.total().isPresent());
    assertEquals(Integer.valueOf(10), r.total().get());
    assertEquals("test.txt", r.fileName().orElse(""));
  }

  @Test
  public void parse_percentIdxName_extractsAll() {
    ProgressFormat.Result r = ProgressFormat.parse("75% (5/20) - document.pdf", 0, "");
    assertEquals(75, r.overallPct());
    assertEquals(Integer.valueOf(5), r.cur().orElse(null));
    assertEquals(Integer.valueOf(20), r.total().orElse(null));
    assertEquals("document.pdf", r.fileName().orElse(""));
  }

  @Test
  public void parse_emptyString_usesFallback() {
    ProgressFormat.Result r = ProgressFormat.parse("", 42, "fallback.txt");
    assertEquals(42, r.overallPct());
    assertEquals("fallback.txt", r.fileName().orElse(""));
  }

  // =========================================================================
  // toHeadlineNoPercent
  // =========================================================================

  @Test
  public void toHeadlineNoPercent_withCurAndTotal() {
    ProgressFormat.Result r = ProgressFormat.parse("[PROGRESS:50:3:10]file.txt", 0, "");
    String headline = ProgressFormat.toHeadlineNoPercent(r);
    assertEquals("Processing: 3/10", headline);
  }

  @Test
  public void toHeadlineNoPercent_finalizingOp() {
    ProgressFormat.Result r = ProgressFormat.parse("Finalizing: done", 100, "");
    assertEquals("Finalizing…", ProgressFormat.toHeadlineNoPercent(r));
  }

  // =========================================================================
  // toDetailsFilename
  // =========================================================================

  @Test
  public void toDetailsFilename_shortName_returnsAsIs() {
    ProgressFormat.Result r = ProgressFormat.parse("[PROGRESS:50:1:1]short.txt", 0, "");
    assertEquals("short.txt", ProgressFormat.toDetailsFilename(r, 50));
  }

  @Test
  public void toDetailsFilename_longName_truncates() {
    ProgressFormat.Result r =
        ProgressFormat.parse("[PROGRESS:50:1:1]very_long_filename_that_exceeds.txt", 0, "");
    String detail = ProgressFormat.toDetailsFilename(r, 15);
    assertTrue(detail.endsWith("…"));
    assertTrue(detail.length() <= 15);
  }
}
