package de.schliweb.sambalite.sync;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the SyncActionLog class.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SyncActionLogTest {

    private SyncActionLog actionLog;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        actionLog = new SyncActionLog(context);
        actionLog.clear();
    }

    @Test
    public void testEmptyLog() {
        List<String> entries = actionLog.getEntries();
        assertTrue(entries.isEmpty());
    }

    @Test
    public void testLogUpload() {
        actionLog.log(SyncActionLog.Action.UPLOADED, "song.mp3");
        List<String> entries = actionLog.getEntries();
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).contains("↑ Uploaded: song.mp3"));
    }

    @Test
    public void testLogDownload() {
        actionLog.log(SyncActionLog.Action.DOWNLOADED, "report.pdf");
        List<String> entries = actionLog.getEntries();
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).contains("↓ Downloaded: report.pdf"));
    }

    @Test
    public void testLogSkipped() {
        actionLog.log(SyncActionLog.Action.SKIPPED, "image.jpg", "same size");
        List<String> entries = actionLog.getEntries();
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).contains("⊘ Skipped: image.jpg - same size"));
    }

    @Test
    public void testLogError() {
        actionLog.log(SyncActionLog.Action.ERROR, "data.tqd", "Failed to create file");
        List<String> entries = actionLog.getEntries();
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).contains("✗ Error: data.tqd - Failed to create file"));
    }

    @Test
    public void testLogCreatedDir() {
        actionLog.log(SyncActionLog.Action.CREATED_DIR, "backup");
        List<String> entries = actionLog.getEntries();
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).contains("Created dir: backup"));
    }

    @Test
    public void testLogWithoutDetail() {
        actionLog.log(SyncActionLog.Action.UPLOADED, "file.txt");
        List<String> entries = actionLog.getEntries();
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).contains("↑ Uploaded: file.txt"));
        // Should not contain " - " detail separator at the end
        assertTrue(entries.get(0).endsWith("file.txt"));
    }

    @Test
    public void testMultipleEntries() {
        actionLog.log(SyncActionLog.Action.UPLOADED, "a.txt");
        actionLog.log(SyncActionLog.Action.DOWNLOADED, "b.txt");
        actionLog.log(SyncActionLog.Action.SKIPPED, "c.txt", "same size");
        List<String> entries = actionLog.getEntries();
        assertEquals(3, entries.size());
    }

    @Test
    public void testRecentEntriesNewestFirst() {
        actionLog.log(SyncActionLog.Action.UPLOADED, "first.txt");
        actionLog.log(SyncActionLog.Action.DOWNLOADED, "second.txt");
        actionLog.log(SyncActionLog.Action.ERROR, "third.txt", "fail");

        List<String> recent = actionLog.getRecentEntries(2);
        assertEquals(2, recent.size());
        assertTrue(recent.get(0).contains("third.txt"));
        assertTrue(recent.get(1).contains("second.txt"));
    }

    @Test
    public void testRecentEntriesMoreThanAvailable() {
        actionLog.log(SyncActionLog.Action.UPLOADED, "only.txt");
        List<String> recent = actionLog.getRecentEntries(10);
        assertEquals(1, recent.size());
    }

    @Test
    public void testClear() {
        actionLog.log(SyncActionLog.Action.UPLOADED, "file.txt");
        actionLog.log(SyncActionLog.Action.DOWNLOADED, "file2.txt");
        actionLog.clear();
        assertTrue(actionLog.getEntries().isEmpty());
    }

    @Test
    public void testFormattedLogEmpty() {
        String formatted = actionLog.getFormattedLog(10);
        assertTrue(formatted.contains("No sync actions recorded yet"));
    }

    @Test
    public void testFormattedLogWithEntries() {
        actionLog.log(SyncActionLog.Action.UPLOADED, "a.mp3");
        actionLog.log(SyncActionLog.Action.DOWNLOADED, "b.pdf");
        actionLog.log(SyncActionLog.Action.SKIPPED, "c.jpg", "same size");
        actionLog.log(SyncActionLog.Action.ERROR, "d.tqd", "fail");
        actionLog.log(SyncActionLog.Action.CREATED_DIR, "subdir");

        String formatted = actionLog.getFormattedLog(10);
        assertTrue(formatted.contains("=== Sync Activity Log ==="));
        assertTrue(formatted.contains("1 uploaded"));
        assertTrue(formatted.contains("1 downloaded"));
        assertTrue(formatted.contains("1 skipped"));
        assertTrue(formatted.contains("1 errors"));
        assertTrue(formatted.contains("1 dirs created"));
    }

    @Test
    public void testTimestampFormat() {
        actionLog.log(SyncActionLog.Action.UPLOADED, "test.txt");
        String entry = actionLog.getEntries().get(0);
        // Should start with [YYYY-MM-DD HH:MM:SS]
        assertTrue(entry.matches("^\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\].*"));
    }

    @Test
    public void testEntriesOrderOldestFirst() {
        actionLog.log(SyncActionLog.Action.UPLOADED, "first.txt");
        actionLog.log(SyncActionLog.Action.UPLOADED, "second.txt");
        actionLog.log(SyncActionLog.Action.UPLOADED, "third.txt");

        List<String> entries = actionLog.getEntries();
        assertTrue(entries.get(0).contains("first.txt"));
        assertTrue(entries.get(1).contains("second.txt"));
        assertTrue(entries.get(2).contains("third.txt"));
    }
}
