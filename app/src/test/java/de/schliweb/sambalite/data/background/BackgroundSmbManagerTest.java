package de.schliweb.sambalite.data.background;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import de.schliweb.sambalite.service.SmbBackgroundService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BackgroundSmbManagerTest {

    @Mock
    private Context mockAppContext;

    private AutoCloseable mocks;

    @Before
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
    }

    @After
    public void tearDown() throws Exception {
        if (mocks != null) mocks.close();
    }

    /**
     * Verifies that when starting the foreground service is not allowed (throws),
     * BackgroundSmbManager still attempts to bind and, once connected, can execute a queued operation.
     */
    @Test
    public void ensureServiceStartedAndBound_startNotAllowed_bindsAndExecutesQueuedOp() throws Exception {
        // Arrange: startForegroundService throws, bindService returns true and captures the connection
        doThrow(new RuntimeException("ForegroundServiceStartNotAllowed"))
                .when(mockAppContext).startForegroundService(any(Intent.class));
        // For pre-O devices path (not used here but safe)
        doThrow(new RuntimeException("StartNotAllowed"))
                .when(mockAppContext).startService(any(Intent.class));

        final ArgumentCaptor<ServiceConnection> connCaptor = ArgumentCaptor.forClass(ServiceConnection.class);
        when(mockAppContext.bindService(any(Intent.class), connCaptor.capture(), eq(Context.BIND_AUTO_CREATE)))
                .thenReturn(true);

        // Provide a main executor that runs tasks inline
        java.util.concurrent.Executor inline = command -> command.run();
        when(mockAppContext.getMainExecutor()).thenReturn(inline);

        when(mockAppContext.getApplicationContext()).thenReturn(mockAppContext);

        // Create manager (will attempt to start + bind immediately)
        BackgroundSmbManager mgr = new BackgroundSmbManager(mockAppContext);

        // Prepare a fake SmbBackgroundService that runs work synchronously
        class TestService extends SmbBackgroundService {
            @Override
            public void executeSmbOperation(String operationName, java.util.concurrent.Callable<Boolean> work) {
                try {
                    work.call();
                } catch (Exception ignored) {
                }
            }
            @Override public void startOperation(String name) { /* no-op */ }
            @Override public void updateOperationProgress(String operationName, String progressInfo) { /* no-op */ }
            @Override public void updateFileProgress(String operationName, int currentFile, int totalFiles, String currentFileName) { /* no-op */ }
            @Override public void updateBytesProgress(String operationName, long currentBytes, long totalBytes, String fileName) { /* no-op */ }
            @Override public void finishOperation(String name, boolean success) { /* no-op */ }
        }
        TestService testService = new TestService();
        IBinder binder = testService.new LocalBinder();

        // Act: queue an operation before the service is connected
        AtomicBoolean executed = new AtomicBoolean(false);
        CompletableFuture<Integer> fut = mgr.executeBackgroundOperation(
                "op1",
                "DOWNLOAD",
                callback -> {
                    executed.set(true);
                    return 123;
                }
        );

        // Simulate that the system connects the service
        ServiceConnection capturedConn = connCaptor.getValue();
        assertNotNull("ServiceConnection should have been captured", capturedConn);
        capturedConn.onServiceConnected(new ComponentName("de.schliweb.sambalite", "SmbBackgroundService"), binder);

        // Assert: the future completes successfully after the manager's delay window
        // The manager schedules a 2000ms delay before delegating when not yet connected at call time.
        Integer result = fut.get(3500, TimeUnit.MILLISECONDS);
        assertEquals(Integer.valueOf(123), result);
        assertTrue(executed.get());

        // Verify bindService was attempted at least once even when start failed
        verify(mockAppContext, times(1)).bindService(any(Intent.class), any(ServiceConnection.class), eq(Context.BIND_AUTO_CREATE));
    }

    /**
     * Verifies that requestCancelAllOperations falls back to bindService when starting is not allowed.
     */
    @Test
    public void requestCancelAllOperations_startNotAllowed_fallsBackToBind() {
        // Arrange
        doThrow(new RuntimeException("ForegroundServiceStartNotAllowed"))
                .when(mockAppContext).startForegroundService(any(Intent.class));
        doThrow(new RuntimeException("StartNotAllowed"))
                .when(mockAppContext).startService(any(Intent.class));

        when(mockAppContext.bindService(any(Intent.class), any(ServiceConnection.class), eq(Context.BIND_AUTO_CREATE)))
                .thenReturn(true);
        when(mockAppContext.getApplicationContext()).thenReturn(mockAppContext);

        BackgroundSmbManager mgr = new BackgroundSmbManager(mockAppContext);

        // Act
        mgr.requestCancelAllOperations();

        // Assert: bindService fallback attempted with ACTION_CANCEL among the intents
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mockAppContext, org.mockito.Mockito.atLeastOnce()).bindService(intentCaptor.capture(), any(ServiceConnection.class), eq(Context.BIND_AUTO_CREATE));
        boolean hasCancel = intentCaptor.getAllValues().stream().anyMatch(i -> SmbBackgroundService.ACTION_CANCEL.equals(i.getAction()));
        assertTrue("Expected a bindService call carrying ACTION_CANCEL intent", hasCancel);
    }
}
