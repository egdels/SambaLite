package de.schliweb.sambalite.ui;

import static org.junit.Assert.assertEquals;

import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * A delayed "clear while loading" reached the adapter after the file browser was closed and threw
 * RejectedExecutionException from the shut-down diff executor (Play Console, 3.0.0-rc2).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = de.schliweb.sambalite.SambaLiteApp.class)
public class FileAdapterShutdownTest {

  @Test
  public void setFiles_afterShutdown_isIgnored() {
    FileAdapter adapter = new FileAdapter();
    adapter.shutdown();

    adapter.setFiles(Collections.emptyList());
    adapter.setFiles(Collections.emptyList(), () -> {});

    assertEquals(0, adapter.getItemCount());
  }
}
