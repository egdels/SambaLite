package de.schliweb.sambalite.util;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LogUtilsReleaseTreeTest {

    @Before
    public void setUp() {
        // Ensure Robolectric captures android.util.Log outputs
        ShadowLog.clear();
        // Initialize LogUtils with release tree
        LogUtils.init(false);
    }

    @After
    public void tearDown() {
        ShadowLog.clear();
    }

    @Test
    public void longMessage_doesNotCrash_andProducesMultipleChunks() {
        // Build a long message significantly exceeding 4k
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            sb.append('A');
        }
        String longMsg = sb.toString();

        // Should not throw
        LogUtils.i("TestTag", longMsg);

        // Verify logs were emitted and chunked (at least > 1 entry)
        // Note: We cannot guarantee exact chunk count here, but should be multiple
        int entriesForTag = (int) ShadowLog.getLogs().stream()
                .filter(e -> "TestTag".equals(e.tag))
                .count();
        assertTrue("Expected multiple log chunks for long message", entriesForTag > 1);
    }

    @Test
    public void messageWithThrowable_doesNotCrash() {
        Exception ex = new Exception("Test exception");
        // Should not throw
        LogUtils.e("TestTag2", "Error occurred", ex);

        // At least one log entry should be present
        boolean any = ShadowLog.getLogs().stream().anyMatch(e -> "TestTag2".equals(e.tag));
        assertTrue(any);
    }
}
