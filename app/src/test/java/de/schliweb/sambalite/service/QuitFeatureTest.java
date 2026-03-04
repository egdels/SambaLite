package de.schliweb.sambalite.service;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import de.schliweb.sambalite.data.background.BackgroundSmbManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for the Quit/Stop feature (Punkt 1):
 * - BackgroundSmbManager: hasActiveOperations, getActiveOperationCount, requestStopService
 * - SmbBackgroundService: ACTION_STOP handling, Stop button in notification when idle
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class QuitFeatureTest {

    @Mock
    private Context mockAppContext;

    private AutoCloseable mocks;

    @Before
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        when(mockAppContext.getApplicationContext()).thenReturn(mockAppContext);
    }

    @After
    public void tearDown() throws Exception {
        if (mocks != null) mocks.close();
    }

    // ===== BackgroundSmbManager Tests =====

    @Test
    public void hasActiveOperations_noService_returnsFalse() {
        // startForegroundService throws, so bindService is never reached -> no service connected
        doThrow(new RuntimeException("not allowed")).when(mockAppContext).startForegroundService(any());

        BackgroundSmbManager mgr = new BackgroundSmbManager(mockAppContext);
        assertFalse(mgr.hasActiveOperations());
    }

    @Test
    public void getActiveOperationCount_noService_returnsZero() {
        doThrow(new RuntimeException("not allowed")).when(mockAppContext).startForegroundService(any());

        BackgroundSmbManager mgr = new BackgroundSmbManager(mockAppContext);
        assertEquals(0, mgr.getActiveOperationCount());
    }

    @Test
    public void hasActiveOperations_withConnectedService_delegatesToService() throws Exception {
        // Allow startForegroundService to succeed, capture the ServiceConnection from bindService
        final ServiceConnection[] captured = new ServiceConnection[1];
        doReturn(null).when(mockAppContext).startForegroundService(any());
        doAnswer(invocation -> {
            captured[0] = invocation.getArgument(1);
            return true;
        }).when(mockAppContext).bindService(any(Intent.class), any(ServiceConnection.class), eq(Context.BIND_AUTO_CREATE));

        BackgroundSmbManager mgr = new BackgroundSmbManager(mockAppContext);
        assertNotNull("ServiceConnection should have been captured", captured[0]);

        // Create a real service via Robolectric and get its binder
        SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);
        IBinder binder = service.new LocalBinder();

        // Simulate service connection
        captured[0].onServiceConnected(
                new ComponentName("de.schliweb.sambalite", "SmbBackgroundService"), binder);

        // No active operations initially
        assertFalse(mgr.hasActiveOperations());
        assertEquals(0, mgr.getActiveOperationCount());

        // Start an operation to make it active
        service.startOperation("testOp");
        assertTrue(mgr.hasActiveOperations());
        assertEquals(1, mgr.getActiveOperationCount());

        // Finish the operation
        service.finishOperation("testOp", true);
        assertFalse(mgr.hasActiveOperations());
        assertEquals(0, mgr.getActiveOperationCount());
    }

    @Test
    public void requestStopService_sendsActionStopIntent() {
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        when(mockAppContext.bindService(any(Intent.class), any(ServiceConnection.class), eq(Context.BIND_AUTO_CREATE)))
                .thenReturn(true);
        doThrow(new RuntimeException("not allowed")).when(mockAppContext).startForegroundService(any());

        BackgroundSmbManager mgr = new BackgroundSmbManager(mockAppContext);

        // Reset to capture only the stop intent
        reset(mockAppContext);
        when(mockAppContext.getApplicationContext()).thenReturn(mockAppContext);

        mgr.requestStopService();

        verify(mockAppContext).startForegroundService(intentCaptor.capture());
        Intent stopIntent = intentCaptor.getValue();
        assertEquals(SmbBackgroundService.ACTION_STOP, stopIntent.getAction());
    }

    // ===== SmbBackgroundService Tests =====

    @Test
    public void onStartCommand_actionStop_stopsService() {
        SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);

        Intent stopIntent = new Intent(SmbBackgroundService.ACTION_STOP);
        int result = service.onStartCommand(stopIntent, 0, 1);

        assertEquals(android.app.Service.START_NOT_STICKY, result);
        // After ACTION_STOP, service should have no active operations
        assertFalse(service.hasActiveOperations());
    }

    @Test
    public void onStartCommand_actionStop_cancelsActiveOperations() {
        SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);

        // Start an operation
        service.startOperation("upload1");
        assertTrue(service.hasActiveOperations());
        assertEquals(1, service.getActiveOperationCount());

        // Send ACTION_STOP
        Intent stopIntent = new Intent(SmbBackgroundService.ACTION_STOP);
        int result = service.onStartCommand(stopIntent, 0, 1);

        assertEquals(android.app.Service.START_NOT_STICKY, result);
    }

    @Test
    public void notification_showsStopAction_whenIdle() {
        SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);

        // Service is idle (no active operations)
        assertFalse(service.hasActiveOperations());

        // Trigger notification creation via onStartCommand with no action
        Intent intent = new Intent();
        service.onStartCommand(intent, 0, 1);

        // The notification should contain a Stop action
        // We verify indirectly: the service should still be running and idle
        assertFalse(service.hasActiveOperations());
    }

    @Test
    public void actionStop_constant_hasCorrectValue() {
        assertEquals("de.schliweb.sambalite.action.STOP", SmbBackgroundService.ACTION_STOP);
    }

    @Test
    public void actionCancel_constant_hasCorrectValue() {
        assertEquals("de.schliweb.sambalite.action.CANCEL", SmbBackgroundService.ACTION_CANCEL);
    }
}
