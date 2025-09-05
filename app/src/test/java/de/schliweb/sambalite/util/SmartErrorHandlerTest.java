package de.schliweb.sambalite.util;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class SmartErrorHandlerTest {

    private Thread.UncaughtExceptionHandler original;
    private final AtomicInteger prevCalls = new AtomicInteger(0);

    @Before
    public void setUp() {
        original = Thread.getDefaultUncaughtExceptionHandler();
        // Install a test previous handler that increments a counter
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> prevCalls.incrementAndGet());
    }

    @After
    public void tearDown() {
        // Restore original handler
        Thread.setDefaultUncaughtExceptionHandler(original);
    }

    @Test
    public void setupGlobalErrorHandler_delegatesToPrevious_withoutRecursion() {
        SmartErrorHandler.getInstance().setupGlobalErrorHandler();

        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        assertNotNull(current);

        // Invoke handler manually with a dummy exception
        current.uncaughtException(Thread.currentThread(), new RuntimeException("test"));

        // Ensure the previous handler was called exactly once (no recursion)
        assertEquals(1, prevCalls.get());
    }
}
