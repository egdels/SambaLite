package de.schliweb.sambalite.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests the detailed log of the last run per sync (#50). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SyncRunLogTest {

  private final Context context = ApplicationProvider.getApplicationContext();

  @Test
  public void begin_replacesThePreviousRun() {
    SyncRunLog log = new SyncRunLog(context, "c1");
    log.begin();
    log.append(1, SyncActionLog.Action.UPLOADED, "old.txt", null);
    log.close();
    log.begin();
    log.append(2, SyncActionLog.Action.DOWNLOADED, "new.txt", "detail");
    log.close();

    List<SyncRunLog.Entry> entries = SyncRunLog.read(context, "c1");
    assertEquals(1, entries.size());
    assertEquals("new.txt", entries.get(0).getPath());
    assertEquals("detail", entries.get(0).getDetail());
    assertEquals(2, entries.get(0).getTime());
  }

  @Test
  public void runFailingBeforeAnyFile_keepsThePreviousLog() {
    SyncRunLog log = new SyncRunLog(context, "c5");
    log.begin();
    log.append(1, SyncActionLog.Action.UPLOADED, "kept.txt", null);
    log.close();

    // Next run: only a run-level entry (no path), e.g. the local folder is missing
    log.begin();
    log.append(2, SyncActionLog.Action.ERROR, "", "local folder missing");
    log.close();

    List<SyncRunLog.Entry> entries = SyncRunLog.read(context, "c5");
    assertEquals(1, entries.size());
    assertEquals("kept.txt", entries.get(0).getPath());
  }

  @Test
  public void tabsAndNewlines_doNotBreakTheFormat() {
    SyncRunLog log = new SyncRunLog(context, "c2");
    log.begin();
    log.append(5, SyncActionLog.Action.ERROR, "a\tb.txt", "line1\nline2\tx");
    log.close();
    SyncRunLog.Entry e = SyncRunLog.read(context, "c2").get(0);
    assertEquals("a b.txt", e.getPath());
    assertEquals("line1 line2 x", e.getDetail());
  }

  @Test
  public void afterMaxLines_onlyErrorsAreWritten() {
    SyncRunLog log = new SyncRunLog(context, "c3");
    log.begin();
    for (int i = 0; i < SyncRunLog.MAX_LINES; i++) {
      log.append(i, SyncActionLog.Action.SKIPPED, "f" + i, null);
    }
    assertFalse(log.isTruncated());
    log.append(1, SyncActionLog.Action.SKIPPED, "dropped", null);
    log.append(2, SyncActionLog.Action.ERROR, "kept", "boom");
    log.close();
    assertTrue(log.isTruncated());
    List<SyncRunLog.Entry> entries = SyncRunLog.read(context, "c3");
    assertEquals(SyncRunLog.MAX_LINES + 1, entries.size());
    assertEquals("kept", entries.get(entries.size() - 1).getPath());
  }

  @Test
  public void appendWithoutBegin_isIgnored_andDeleteRemovesTheFile() {
    SyncRunLog log = new SyncRunLog(context, "c4");
    log.append(1, SyncActionLog.Action.UPLOADED, "x", null);
    assertTrue(SyncRunLog.read(context, "c4").isEmpty());
    log.begin();
    log.append(1, SyncActionLog.Action.UPLOADED, "x", null);
    log.close();
    assertEquals(1, SyncRunLog.read(context, "c4").size());
    SyncRunLog.delete(context, "c4");
    assertTrue(SyncRunLog.read(context, "c4").isEmpty());
  }

  @Test
  public void parse_rejectsBrokenLines() {
    assertNull(SyncRunLog.parse("garbage"));
    assertNull(SyncRunLog.parse("x\tUPLOADED\tpath"));
    assertNull(SyncRunLog.parse("1\tNO_SUCH_ACTION\tpath"));
    assertEquals("path", SyncRunLog.parse("1\tUPLOADED\tpath").getPath());
  }

  @Test
  public void configIdWithSlash_staysInsideTheLogFolder() {
    assertEquals("sync_logs", SyncRunLog.fileFor(context, "../evil").getParentFile().getName());
  }
}
