package de.schliweb.sambalite.ui.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import de.schliweb.sambalite.sync.SyncActionLog;
import de.schliweb.sambalite.sync.SyncRunLog;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Tests the "why was file X not synced?" search (#50). */
public class SyncLogSearchTest {

  private static SyncRunLog.Entry e(long t, SyncActionLog.Action a, String path, String detail) {
    return SyncRunLog.parse(t + "\t" + a.name() + "\t" + path + "\t" + detail);
  }

  private final SyncLogSearch.Source photos =
      new SyncLogSearch.Source(
          "Photos",
          Arrays.asList(
              e(1, SyncActionLog.Action.SKIPPED, "2023/11/IMG_0412.jpg", "same (DB state)"),
              e(2, SyncActionLog.Action.ERROR, "2024/03/IMG_0412.jpg", "STATUS_ACCESS_DENIED"),
              e(3, SyncActionLog.Action.UPLOADED, "2024/03/IMG_0500.jpg", ""),
              e(4, SyncActionLog.Action.ABORTED_LOW_SPACE, "", "sync stopped")));

  @Test
  public void query_findsSkippedFilesToo_errorsFirst() {
    List<SyncLogSearch.Result> results =
        SyncLogSearch.search(
            Collections.singletonList(photos), Collections.emptyList(), "img_0412", false);
    assertEquals(2, results.size());
    assertEquals(SyncActionLog.Action.ERROR, results.get(0).action);
    assertEquals("2023/11/IMG_0412.jpg", results.get(1).path);
  }

  @Test
  public void noQuery_hidesSkippedFilesUnlessAsked_andRunEntries() {
    List<SyncLogSearch.Result> hidden =
        SyncLogSearch.search(Collections.singletonList(photos), Collections.emptyList(), "", false);
    assertEquals(2, hidden.size());
    List<SyncLogSearch.Result> shown =
        SyncLogSearch.search(Collections.singletonList(photos), Collections.emptyList(), null, true);
    assertEquals(3, shown.size());
  }

  @Test
  public void trackedFiles_notInTheLog_areAdded() {
    List<SyncLogSearch.Tracked> tracked =
        Arrays.asList(
            new SyncLogSearch.Tracked("Photos", "2024/03/IMG_0412.jpg", 9),
            new SyncLogSearch.Tracked("Photos", "2019/IMG_0412.jpg", 7));
    List<SyncLogSearch.Result> results =
        SyncLogSearch.search(Collections.singletonList(photos), tracked, "IMG_0412", false);
    // The error, then the newest others: the tracked file (7) before the skipped one (1)
    assertEquals(3, results.size());
    assertEquals(SyncActionLog.Action.ERROR, results.get(0).action);
    assertEquals("2019/IMG_0412.jpg", results.get(1).path);
    assertNull(results.get(1).action);
    assertEquals("2023/11/IMG_0412.jpg", results.get(2).path);
  }
}
