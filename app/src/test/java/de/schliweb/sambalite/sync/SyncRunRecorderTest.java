package de.schliweb.sambalite.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests that a run is counted and logged as it happens (#50). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SyncRunRecorderTest {

  private Context context;
  private SyncActionLog sharedLog;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    sharedLog = new SyncActionLog(context);
    sharedLog.clear();
  }

  @Test
  public void countsActions_andSkipsSkippedFilesInTheSharedLog() {
    SyncRunRecorder recorder = new SyncRunRecorder(sharedLog, null, "c1", "Photos", null, 1000);
    recorder.log(SyncActionLog.Action.UPLOADED, "2024/a.jpg");
    recorder.log(SyncActionLog.Action.SKIPPED, "2024/b.jpg", "same (DB state)");
    recorder.log(SyncActionLog.Action.SKIPPED, "2024/c.jpg", "same (DB state)");
    recorder.log(SyncActionLog.Action.CREATED_DIR, "2024");

    SyncRunResult result = recorder.finish(recorder.completedStatus(false), null, null, 2000);

    assertEquals(SyncRunResult.Status.COMPLETED, result.getStatus());
    assertEquals(1, result.getCount(SyncActionLog.Action.UPLOADED));
    assertEquals(2, result.getCount(SyncActionLog.Action.SKIPPED));
    assertEquals(1, result.getCount(SyncActionLog.Action.CREATED_DIR));
    assertEquals(3, result.getProcessedFiles());
    assertEquals(1000, result.getStartedAt());
    assertEquals(2000, result.getEndedAt());

    List<String> shared = sharedLog.getEntries();
    assertEquals(shared.toString(), 2, shared.size());
    assertTrue(shared.get(0), shared.get(0).contains("Photos › 2024/a.jpg"));
  }

  @Test
  public void errors_areCountedWithPathAndCategory() {
    SyncRunRecorder recorder = new SyncRunRecorder(sharedLog, null, "c1", "Photos", null, 0);
    recorder.error("2024/03/IMG_0412.jpg", "IMG_0412.jpg", new IOException("STATUS_ACCESS_DENIED"));
    recorder.error("Notes/a:b.txt", "a:b.txt", new IOException("odd"));

    SyncRunResult result =
        recorder.finish(recorder.completedStatus(false), null, null, System.currentTimeMillis());

    assertEquals(SyncRunResult.Status.COMPLETED_WITH_ERRORS, result.getStatus());
    assertEquals(2, result.getErrorCount());
    assertEquals(2, result.getErrors().size());
    assertEquals("2024/03/IMG_0412.jpg", result.getErrors().get(0).getPath());
    assertEquals(SyncErrorCategory.ACCESS_DENIED, result.getErrors().get(0).getCategory());
    assertEquals(SyncErrorCategory.INVALID_NAME, result.getErrors().get(1).getCategory());
    assertEquals(
        Integer.valueOf(1), result.getErrorCategories().get(SyncErrorCategory.ACCESS_DENIED));
  }

  @Test
  public void skippedFolder_makesTheRunCompleteWithErrors() {
    SyncRunRecorder recorder = new SyncRunRecorder(sharedLog, null, "c1", "Photos", null, 0);
    recorder.folderSkipped("Archive/2019", new IOException("STATUS_ACCESS_DENIED"));

    SyncRunResult result = recorder.finish(recorder.completedStatus(false), null, null, 1);

    assertEquals(SyncRunResult.Status.COMPLETED_WITH_ERRORS, result.getStatus());
    assertEquals(1, result.getErrorCount());
    assertEquals("Archive/2019/", result.getErrors().get(0).getPath());
    assertEquals(SyncErrorCategory.FOLDER_SKIPPED, result.getErrors().get(0).getCategory());
    assertEquals(0, result.getProcessedFiles());
  }

  @Test
  public void stopped_winsOverErrors() {
    SyncRunRecorder recorder = new SyncRunRecorder(sharedLog, null, "c1", "Photos", null, 0);
    recorder.error("a.txt", "a.txt", new IOException("x"));
    assertEquals(SyncRunResult.Status.STOPPED, recorder.completedStatus(true));
  }

  @Test
  public void onlyTheFirstErrorsAreStored_butAllAreCounted() {
    SyncRunRecorder recorder = new SyncRunRecorder(null, null, "c1", "Photos", null, 0);
    for (int i = 0; i < SyncRunResult.MAX_STORED_ERRORS + 25; i++) {
      recorder.error("f" + i, "f" + i, new IOException("STATUS_ACCESS_DENIED"));
    }
    SyncRunResult result = recorder.finish(recorder.completedStatus(false), null, null, 1);
    assertEquals(SyncRunResult.MAX_STORED_ERRORS + 25, result.getErrorCount());
    assertEquals(SyncRunResult.MAX_STORED_ERRORS, result.getErrors().size());
    assertEquals(
        Integer.valueOf(SyncRunResult.MAX_STORED_ERRORS + 25),
        result.getErrorCategories().get(SyncErrorCategory.ACCESS_DENIED));
  }

  @Test
  public void progress_isReportedThrottled() {
    List<Integer> reported = new ArrayList<>();
    SyncRunRecorder recorder =
        new SyncRunRecorder(null, null, "c1", "Photos", (n, path) -> reported.add(n), 0);
    for (int i = 0; i < 100; i++) {
      recorder.log(SyncActionLog.Action.SKIPPED, "f" + i);
    }
    // All within one throttle interval: only the first file is reported
    assertEquals(1, reported.size());
    assertEquals(Integer.valueOf(1), reported.get(0));
    assertEquals(100, recorder.getProcessedFiles());
  }

  @Test
  public void runLevelEntries_useTheConfigNameInTheSharedLog() {
    SyncRunRecorder recorder = new SyncRunRecorder(sharedLog, null, "c1", "Music", null, 0);
    recorder.logRun(SyncActionLog.Action.ABORTED_LOW_SPACE, "sync not started");
    String entry = sharedLog.getEntries().get(0);
    assertTrue(entry, entry.contains("⛔ Aborted, low disk space: Music - sync not started"));
    assertFalse(entry, entry.contains("›"));
  }

  @Test
  public void runLog_getsEveryEntryWithItsPath() {
    SyncRunLog runLog = new SyncRunLog(context, "c-log");
    runLog.begin();
    SyncRunRecorder recorder = new SyncRunRecorder(sharedLog, runLog, "c-log", "Photos", null, 0);
    recorder.log(SyncActionLog.Action.SKIPPED, "2024/b.jpg", "same (DB state)");
    recorder.error("2024/c.jpg", "c.jpg", new IOException("STATUS_ACCESS_DENIED"));
    runLog.close();

    List<SyncRunLog.Entry> entries = SyncRunLog.read(context, "c-log");
    assertEquals(2, entries.size());
    assertEquals(SyncActionLog.Action.SKIPPED, entries.get(0).getAction());
    assertEquals("2024/b.jpg", entries.get(0).getPath());
    assertEquals("same (DB state)", entries.get(0).getDetail());
    assertEquals(SyncActionLog.Action.ERROR, entries.get(1).getAction());
  }
}
