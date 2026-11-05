package de.schliweb.sambalite.sync;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Tests when a sync notifies (#50): only when its state changes. */
public class SyncProblemNotifierTest {

  private static SyncRunResult r(SyncRunResult.Status status, SyncErrorCategory category) {
    return SyncRunResult.notStarted("c1", status, null, category, 1);
  }

  private static SyncRunResult r(SyncRunResult.Status status) {
    return r(status, null);
  }

  @Test
  public void firstProblem_notifies() {
    assertEquals(
        SyncProblemNotifier.Decision.PROBLEM,
        SyncProblemNotifier.decide(r(SyncRunResult.Status.COMPLETED_WITH_ERRORS), null, false, false));
    assertEquals(
        SyncProblemNotifier.Decision.PROBLEM,
        SyncProblemNotifier.decide(
            r(SyncRunResult.Status.FAILED), r(SyncRunResult.Status.COMPLETED), false, false));
  }

  @Test
  public void sameProblemAgain_doesNotNotify() {
    assertEquals(
        SyncProblemNotifier.Decision.NONE,
        SyncProblemNotifier.decide(
            r(SyncRunResult.Status.COMPLETED_WITH_ERRORS),
            r(SyncRunResult.Status.COMPLETED_WITH_ERRORS),
            false,
            false));
    assertEquals(
        SyncProblemNotifier.Decision.NONE,
        SyncProblemNotifier.decide(
            r(SyncRunResult.Status.FAILED, SyncErrorCategory.NETWORK),
            r(SyncRunResult.Status.FAILED, SyncErrorCategory.NETWORK),
            false,
            false));
  }

  @Test
  public void differentProblem_notifies() {
    assertEquals(
        SyncProblemNotifier.Decision.PROBLEM,
        SyncProblemNotifier.decide(
            r(SyncRunResult.Status.FAILED, SyncErrorCategory.AUTH),
            r(SyncRunResult.Status.FAILED, SyncErrorCategory.NETWORK),
            false,
            false));
    assertEquals(
        SyncProblemNotifier.Decision.PROBLEM,
        SyncProblemNotifier.decide(
            r(SyncRunResult.Status.FAILED),
            r(SyncRunResult.Status.COMPLETED_WITH_ERRORS),
            false,
            false));
  }

  @Test
  public void recovery_notifiesOnce() {
    assertEquals(
        SyncProblemNotifier.Decision.RECOVERED,
        SyncProblemNotifier.decide(
            r(SyncRunResult.Status.COMPLETED), r(SyncRunResult.Status.FAILED), false, false));
    assertEquals(
        SyncProblemNotifier.Decision.NONE,
        SyncProblemNotifier.decide(
            r(SyncRunResult.Status.COMPLETED), r(SyncRunResult.Status.COMPLETED), false, false));
    assertEquals(
        SyncProblemNotifier.Decision.NONE,
        SyncProblemNotifier.decide(r(SyncRunResult.Status.COMPLETED), null, false, false));
  }

  @Test
  public void stopped_neverNotifies() {
    assertEquals(
        SyncProblemNotifier.Decision.NONE,
        SyncProblemNotifier.decide(
            r(SyncRunResult.Status.STOPPED), r(SyncRunResult.Status.FAILED), false, false));
  }

  @Test
  public void wifiSkip_notifiesOnceWhenOverdue() {
    SyncRunResult skip = r(SyncRunResult.Status.SKIPPED_NO_WIFI);
    assertEquals(SyncProblemNotifier.Decision.NONE, SyncProblemNotifier.decide(skip, null, false, false));
    assertEquals(SyncProblemNotifier.Decision.OVERDUE, SyncProblemNotifier.decide(skip, null, true, false));
    assertEquals(SyncProblemNotifier.Decision.NONE, SyncProblemNotifier.decide(skip, null, true, true));
  }
}
