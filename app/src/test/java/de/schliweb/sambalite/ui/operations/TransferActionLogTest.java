package de.schliweb.sambalite.ui.operations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests for the TransferActionLog class. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TransferActionLogTest {

  private TransferActionLog actionLog;

  @Before
  public void setUp() {
    Context context = ApplicationProvider.getApplicationContext();
    actionLog = new TransferActionLog(context);
    actionLog.clear();
  }

  @Test
  public void testEmptyLog() {
    List<String> entries = actionLog.getEntries();
    assertTrue(entries.isEmpty());
  }

  @Test
  public void testLogDownloadStarted() {
    actionLog.log(TransferActionLog.Action.DOWNLOAD_STARTED, "song.mp3");
    List<String> entries = actionLog.getEntries();
    assertEquals(1, entries.size());
    assertTrue(entries.get(0).contains("↓ Download started: song.mp3"));
  }

  @Test
  public void testLogDownloadCompleted() {
    actionLog.log(TransferActionLog.Action.DOWNLOAD_COMPLETED, "report.pdf");
    List<String> entries = actionLog.getEntries();
    assertEquals(1, entries.size());
    assertTrue(entries.get(0).contains("✓ Downloaded: report.pdf"));
  }

  @Test
  public void testLogDownloadFailed() {
    actionLog.log(TransferActionLog.Action.DOWNLOAD_FAILED, "data.bin", "Connection reset");
    List<String> entries = actionLog.getEntries();
    assertEquals(1, entries.size());
    assertTrue(entries.get(0).contains("✗ Download failed: data.bin - Connection reset"));
  }

  @Test
  public void testLogUploadStarted() {
    actionLog.log(TransferActionLog.Action.UPLOAD_STARTED, "photo.jpg");
    List<String> entries = actionLog.getEntries();
    assertEquals(1, entries.size());
    assertTrue(entries.get(0).contains("↑ Upload started: photo.jpg"));
  }

  @Test
  public void testLogUploadCompleted() {
    actionLog.log(TransferActionLog.Action.UPLOAD_COMPLETED, "doc.pdf");
    List<String> entries = actionLog.getEntries();
    assertEquals(1, entries.size());
    assertTrue(entries.get(0).contains("✓ Uploaded: doc.pdf"));
  }

  @Test
  public void testLogUploadFailed() {
    actionLog.log(TransferActionLog.Action.UPLOAD_FAILED, "big.zip", "Disk full");
    List<String> entries = actionLog.getEntries();
    assertEquals(1, entries.size());
    assertTrue(entries.get(0).contains("✗ Upload failed: big.zip - Disk full"));
  }

  @Test
  public void testLogCacheHit() {
    actionLog.log(TransferActionLog.Action.CACHE_HIT, "cached.txt");
    List<String> entries = actionLog.getEntries();
    assertEquals(1, entries.size());
    assertTrue(entries.get(0).contains("⊙ Cache hit: cached.txt"));
  }

  @Test
  public void testLogCacheMiss() {
    actionLog.log(TransferActionLog.Action.CACHE_MISS, "stale.txt", "stale cache entry");
    List<String> entries = actionLog.getEntries();
    assertEquals(1, entries.size());
    assertTrue(entries.get(0).contains("⊘ Cache miss: stale.txt - stale cache entry"));
  }

  @Test
  public void testLogWithoutDetail() {
    actionLog.log(TransferActionLog.Action.UPLOAD_COMPLETED, "file.txt");
    List<String> entries = actionLog.getEntries();
    assertEquals(1, entries.size());
    assertTrue(entries.get(0).endsWith("file.txt"));
  }

  @Test
  public void testMultipleEntries() {
    actionLog.log(TransferActionLog.Action.DOWNLOAD_STARTED, "a.txt");
    actionLog.log(TransferActionLog.Action.DOWNLOAD_COMPLETED, "a.txt");
    actionLog.log(TransferActionLog.Action.UPLOAD_STARTED, "b.txt");
    List<String> entries = actionLog.getEntries();
    assertEquals(3, entries.size());
  }

  @Test
  public void testRecentEntriesNewestFirst() {
    actionLog.log(TransferActionLog.Action.DOWNLOAD_STARTED, "first.txt");
    actionLog.log(TransferActionLog.Action.DOWNLOAD_COMPLETED, "second.txt");
    actionLog.log(TransferActionLog.Action.UPLOAD_FAILED, "third.txt", "fail");

    List<String> recent = actionLog.getRecentEntries(2);
    assertEquals(2, recent.size());
    assertTrue(recent.get(0).contains("third.txt"));
    assertTrue(recent.get(1).contains("second.txt"));
  }

  @Test
  public void testRecentEntriesMoreThanAvailable() {
    actionLog.log(TransferActionLog.Action.CACHE_HIT, "only.txt");
    List<String> recent = actionLog.getRecentEntries(10);
    assertEquals(1, recent.size());
  }

  @Test
  public void testClear() {
    actionLog.log(TransferActionLog.Action.UPLOAD_COMPLETED, "file.txt");
    actionLog.log(TransferActionLog.Action.DOWNLOAD_COMPLETED, "file2.txt");
    actionLog.clear();
    assertTrue(actionLog.getEntries().isEmpty());
  }

  @Test
  public void testFormattedLogEmpty() {
    String formatted = actionLog.getFormattedLog(10);
    assertTrue(formatted.contains("No transfer actions recorded yet"));
  }

  @Test
  public void testFormattedLogWithEntries() {
    actionLog.log(TransferActionLog.Action.UPLOAD_COMPLETED, "a.mp3");
    actionLog.log(TransferActionLog.Action.DOWNLOAD_COMPLETED, "b.pdf");
    actionLog.log(TransferActionLog.Action.CACHE_HIT, "c.jpg");
    actionLog.log(TransferActionLog.Action.DOWNLOAD_FAILED, "d.bin", "timeout");
    actionLog.log(TransferActionLog.Action.UPLOAD_FAILED, "e.zip", "disk full");

    String formatted = actionLog.getFormattedLog(10);
    assertTrue(formatted.contains("=== Transfer Activity Log ==="));
    assertTrue(formatted.contains("1 uploaded"));
    assertTrue(formatted.contains("1 downloaded"));
    assertTrue(formatted.contains("1 cache hits"));
    assertTrue(formatted.contains("2 errors"));
  }

  @Test
  public void testTimestampFormat() {
    actionLog.log(TransferActionLog.Action.UPLOAD_STARTED, "test.txt");
    String entry = actionLog.getEntries().get(0);
    assertTrue(entry.matches("^\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\].*"));
  }

  @Test
  public void testEntriesOrderOldestFirst() {
    actionLog.log(TransferActionLog.Action.DOWNLOAD_STARTED, "first.txt");
    actionLog.log(TransferActionLog.Action.DOWNLOAD_STARTED, "second.txt");
    actionLog.log(TransferActionLog.Action.DOWNLOAD_STARTED, "third.txt");

    List<String> entries = actionLog.getEntries();
    assertTrue(entries.get(0).contains("first.txt"));
    assertTrue(entries.get(1).contains("second.txt"));
    assertTrue(entries.get(2).contains("third.txt"));
  }
}
