package de.schliweb.sambalite.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Tests the state of a sync as the System Monitor shows it (#50). */
public class SyncHealthTest {

  private static final long HOUR = 60L * 60 * 1000;
  private static final long NOW = 1_000L * HOUR;

  private static SyncConfig config(int intervalMinutes, long lastSync) {
    SyncConfig c = new SyncConfig();
    c.setId("c1");
    c.setIntervalMinutes(intervalMinutes);
    c.setLastSyncTimestamp(lastSync);
    return c;
  }

  private static SyncRunResult result(SyncRunResult.Status status) {
    return SyncRunResult.notStarted("c1", status, null, null, NOW - HOUR);
  }

  @Test
  public void overdue_afterTwiceTheIntervalPlusGrace() {
    // hourly: overdue after 3 hours
    assertFalse(SyncHealth.isOverdue(config(60, NOW - 3 * HOUR + 1000), NOW));
    assertTrue(SyncHealth.isOverdue(config(60, NOW - 3 * HOUR - 1000), NOW));
  }

  @Test
  public void neverOverdue_whenManualPausedOrNeverSynced() {
    assertFalse(SyncHealth.isOverdue(config(0, NOW - 100 * HOUR), NOW));
    assertFalse(SyncHealth.isOverdue(config(60, 0), NOW));
    SyncConfig paused = config(60, NOW - 100 * HOUR);
    paused.setEnabled(false);
    assertFalse(SyncHealth.isOverdue(paused, NOW));
  }

  @Test
  public void evaluate_kinds() {
    SyncConfig fresh = config(60, NOW - HOUR);
    assertEquals(
        SyncHealth.Kind.NEVER_RUN, SyncHealth.evaluate(config(60, 0), null, null, false, NOW).getKind());
    assertEquals(
        SyncHealth.Kind.COMPLETED_EARLIER,
        SyncHealth.evaluate(fresh, null, null, false, NOW).getKind());

    SyncRunResult ok = result(SyncRunResult.Status.COMPLETED);
    assertEquals(SyncHealth.Level.OK, SyncHealth.evaluate(fresh, ok, ok, false, NOW).getLevel());

    SyncRunResult errors = result(SyncRunResult.Status.COMPLETED_WITH_ERRORS);
    SyncHealth h = SyncHealth.evaluate(fresh, errors, errors, false, NOW);
    assertEquals(SyncHealth.Level.WARNING, h.getLevel());
    assertEquals(SyncHealth.Kind.COMPLETED_WITH_ERRORS, h.getKind());

    SyncRunResult failed = result(SyncRunResult.Status.FAILED);
    assertEquals(SyncHealth.Level.ERROR, SyncHealth.evaluate(fresh, failed, failed, false, NOW).getLevel());

    SyncRunResult lowSpace = result(SyncRunResult.Status.ABORTED_LOW_SPACE);
    assertEquals(
        SyncHealth.Kind.LOW_SPACE, SyncHealth.evaluate(fresh, lowSpace, lowSpace, false, NOW).getKind());

    SyncRunResult stopped = result(SyncRunResult.Status.STOPPED);
    assertEquals(SyncHealth.Level.OK, SyncHealth.evaluate(fresh, stopped, stopped, false, NOW).getLevel());
  }

  @Test
  public void overdue_waitingForWifi_whenTheLatestResultIsAWifiSkip() {
    SyncConfig late = config(60, NOW - 10 * HOUR);
    SyncRunResult ok = result(SyncRunResult.Status.COMPLETED);
    SyncRunResult skip = result(SyncRunResult.Status.SKIPPED_NO_WIFI);
    SyncHealth waiting = SyncHealth.evaluate(late, skip, ok, false, NOW);
    assertEquals(SyncHealth.Kind.WAITING_FOR_WIFI, waiting.getKind());
    assertEquals(SyncHealth.Level.WARNING, waiting.getLevel());
    assertTrue(waiting.isOverdue());

    assertEquals(SyncHealth.Kind.OVERDUE, SyncHealth.evaluate(late, ok, ok, false, NOW).getKind());
  }

  @Test
  public void failure_winsOverOverdue_andRunningWinsOverAll() {
    SyncConfig late = config(60, NOW - 10 * HOUR);
    SyncRunResult failed = result(SyncRunResult.Status.FAILED);
    assertEquals(SyncHealth.Kind.FAILED, SyncHealth.evaluate(late, failed, failed, false, NOW).getKind());
    assertEquals(SyncHealth.Kind.RUNNING, SyncHealth.evaluate(late, failed, failed, true, NOW).getKind());
  }

  @Test
  public void paused_isPaused() {
    SyncConfig paused = config(60, NOW - 10 * HOUR);
    paused.setEnabled(false);
    SyncRunResult failed = result(SyncRunResult.Status.FAILED);
    assertEquals(SyncHealth.Level.PAUSED, SyncHealth.evaluate(paused, failed, failed, true, NOW).getLevel());
  }
}
