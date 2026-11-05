package de.schliweb.sambalite.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.io.IOException;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests the stored run results per sync (#50). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SyncRunHistoryStoreTest {

  private SyncRunHistoryStore store;

  @Before
  public void setUp() {
    Context context = ApplicationProvider.getApplicationContext();
    context.getSharedPreferences("sync_run_history", Context.MODE_PRIVATE).edit().clear().commit();
    store = new SyncRunHistoryStore(context);
  }

  private static SyncRunResult run(String configId, long at, String... errorPaths) {
    SyncRunRecorder recorder = new SyncRunRecorder(null, null, configId, "Photos", null, at);
    recorder.log(SyncActionLog.Action.UPLOADED, "ok.jpg");
    for (String path : errorPaths) {
      recorder.error(path, path, new IOException("STATUS_ACCESS_DENIED"));
    }
    return recorder.finish(recorder.completedStatus(false), null, null, at + 10);
  }

  private static SyncRunResult wifiSkip(String configId, long at) {
    return SyncRunResult.notStarted(
        configId, SyncRunResult.Status.SKIPPED_NO_WIFI, null, null, at);
  }

  @Test
  public void record_storesNewestFirst_andRoundTripsAllFields() {
    store.record(run("c1", 1000));
    store.record(
        SyncRunResult.notStarted(
            "c1",
            SyncRunResult.Status.FAILED,
            "local folder missing",
            SyncErrorCategory.LOCAL_FOLDER_MISSING,
            2000));
    store.record(run("c1", 3000, "a/b.jpg"));

    List<SyncRunResult> history = store.getHistory("c1");
    assertEquals(3, history.size());
    SyncRunResult latest = history.get(0);
    assertEquals(3000, latest.getStartedAt());
    assertEquals(3010, latest.getEndedAt());
    assertEquals(SyncRunResult.Status.COMPLETED_WITH_ERRORS, latest.getStatus());
    assertEquals(1, latest.getCount(SyncActionLog.Action.UPLOADED));
    assertEquals(1, latest.getErrorCount());
    assertEquals("a/b.jpg", latest.getErrors().get(0).getPath());
    assertEquals(SyncErrorCategory.ACCESS_DENIED, latest.getErrors().get(0).getCategory());
    assertEquals(
        Integer.valueOf(1), latest.getErrorCategories().get(SyncErrorCategory.ACCESS_DENIED));

    SyncRunResult failed = history.get(1);
    assertEquals(SyncRunResult.Status.FAILED, failed.getStatus());
    assertEquals("local folder missing", failed.getReason());
    assertEquals(SyncErrorCategory.LOCAL_FOLDER_MISSING, failed.getFailureCategory());
  }

  @Test
  public void record_keepsAtMostMaxRuns() {
    for (int i = 0; i < SyncRunHistoryStore.MAX_RUNS + 5; i++) {
      store.record(run("c1", i * 100L));
    }
    List<SyncRunResult> history = store.getHistory("c1");
    assertEquals(SyncRunHistoryStore.MAX_RUNS, history.size());
    assertEquals((SyncRunHistoryStore.MAX_RUNS + 4) * 100L, history.get(0).getStartedAt());
  }

  @Test
  public void consecutiveWifiSkips_areMergedIntoOne() {
    store.record(run("c1", 1000));
    store.record(wifiSkip("c1", 2000));
    SyncRunHistoryStore.Recorded second = store.record(wifiSkip("c1", 3000));
    store.record(wifiSkip("c1", 4000));

    assertTrue(second.isMerged());
    List<SyncRunResult> history = store.getHistory("c1");
    assertEquals(2, history.size());
    SyncRunResult skip = history.get(0);
    assertEquals(SyncRunResult.Status.SKIPPED_NO_WIFI, skip.getStatus());
    assertEquals(3, skip.getOccurrences());
    assertEquals(2000, skip.getStartedAt());
    assertEquals(4000, skip.getEndedAt());
    // The latest actual run is still the completed one
    assertEquals(SyncRunResult.Status.COMPLETED, store.getLatestRun("c1").getStatus());
  }

  @Test
  public void errorStreak_countsRunsInARowThePathFailed() {
    store.record(run("c1", 1000, "a.jpg", "b.jpg"));
    store.record(wifiSkip("c1", 1500)); // skips do not break the streak
    store.record(run("c1", 2000, "a.jpg"));
    store.record(run("c1", 3000, "a.jpg", "c.jpg"));

    SyncRunResult latest = store.getLatestRun("c1");
    assertEquals(3, latest.getErrors().get(0).getStreak()); // a.jpg
    assertEquals(1, latest.getErrors().get(1).getStreak()); // c.jpg
  }

  @Test
  public void record_returnsThePreviousRun() {
    SyncRunResult first = run("c1", 1000);
    assertNull(store.record(first).getPreviousRun());
    SyncRunHistoryStore.Recorded recorded = store.record(run("c1", 2000));
    assertEquals(1000, recorded.getPreviousRun().getStartedAt());
  }

  @Test
  public void configs_areKeptApart_andDeleteRemovesEverything() {
    store.record(run("c1", 1000));
    store.record(run("c2", 2000));
    store.setMuted("c1", true);

    assertEquals(1, store.getHistory("c1").size());
    assertEquals(2000, store.getLatest("c2").getStartedAt());

    store.delete("c1");
    assertTrue(store.getHistory("c1").isEmpty());
    assertFalse(store.isMuted("c1"));
    assertEquals(1, store.getHistory("c2").size());
  }

  @Test
  public void overdueNotified_isResetByTheNextRun() {
    store.setOverdueNotified("c1", true);
    store.record(wifiSkip("c1", 1000));
    assertTrue(store.isOverdueNotified("c1"));
    store.record(run("c1", 2000));
    assertFalse(store.isOverdueNotified("c1"));
  }

  @Test
  public void unknownConfig_hasNoHistory() {
    assertTrue(store.getHistory("nope").isEmpty());
    assertNull(store.getLatest("nope"));
    assertNull(store.getLatestRun("nope"));
  }

  @Test
  public void corruptJson_isIgnored() {
    Context context = ApplicationProvider.getApplicationContext();
    context
        .getSharedPreferences("sync_run_history", Context.MODE_PRIVATE)
        .edit()
        .putString("runs_c1", "{not json")
        .commit();
    assertTrue(store.getHistory("c1").isEmpty());
    SyncRunResult r = run("c1", 1000);
    store.record(r);
    assertSame(SyncRunResult.Status.COMPLETED, store.getLatest("c1").getStatus());
  }
}
