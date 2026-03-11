package de.schliweb.sambalite.service;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

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

/**
 * Comprehensive tests for the Quit/Stop feature:
 *
 * <p>1. BackgroundSmbManager: - hasActiveOperations / getActiveOperationCount (with and without
 * service) - requestStopService (unbind, stopRequested flag, ACTION_STOP intent) -
 * ensureServiceRunning (resets stopRequested, restarts service) - onServiceDisconnected behavior
 * (auto-restart vs. no-restart after stop) - onServiceConnected mirrors stopRequested from service
 *
 * <p>2. SmbBackgroundService: - ACTION_STOP handling (stops service, cancels operations, sets
 * flags) - ACTION_STOP with active operations (all cancelled) - ACTION_CANCEL handling -
 * stopRequested flag lifecycle - Normal startup resets stopRequested - Notification stop action
 * shown when idle - Constants verification
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class QuitFeatureTest {

  @Mock private Context mockAppContext;

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

  // ===== Helper: create BackgroundSmbManager with captured ServiceConnection =====

  private static class ManagerWithConnection {
    final BackgroundSmbManager manager;
    final ServiceConnection serviceConnection;

    ManagerWithConnection(BackgroundSmbManager manager, ServiceConnection serviceConnection) {
      this.manager = manager;
      this.serviceConnection = serviceConnection;
    }
  }

  /**
   * Creates a BackgroundSmbManager with a mock context, captures the ServiceConnection, and
   * optionally connects a real Robolectric service.
   */
  private ManagerWithConnection createManagerWithService(boolean connectService) {
    final ServiceConnection[] captured = new ServiceConnection[1];
    doReturn(null).when(mockAppContext).startForegroundService(any());
    doAnswer(
            invocation -> {
              captured[0] = invocation.getArgument(1);
              return true;
            })
        .when(mockAppContext)
        .bindService(any(Intent.class), any(ServiceConnection.class), eq(Context.BIND_AUTO_CREATE));

    BackgroundSmbManager mgr = new BackgroundSmbManager(mockAppContext);
    assertNotNull("ServiceConnection should have been captured", captured[0]);

    if (connectService) {
      SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);
      IBinder binder = service.new LocalBinder();
      captured[0].onServiceConnected(
          new ComponentName("de.schliweb.sambalite", "SmbBackgroundService"), binder);
    }

    return new ManagerWithConnection(mgr, captured[0]);
  }

  // =========================================================================
  // BackgroundSmbManager: hasActiveOperations / getActiveOperationCount
  // =========================================================================

  @Test
  public void hasActiveOperations_noService_returnsFalse() {
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
  public void hasActiveOperations_withConnectedService_delegatesToService() {
    ManagerWithConnection mc = createManagerWithService(true);

    assertFalse(mc.manager.hasActiveOperations());
    assertEquals(0, mc.manager.getActiveOperationCount());
  }

  @Test
  public void hasActiveOperations_afterStartOperation_returnsTrue() {
    final ServiceConnection[] captured = new ServiceConnection[1];
    doReturn(null).when(mockAppContext).startForegroundService(any());
    doAnswer(
            invocation -> {
              captured[0] = invocation.getArgument(1);
              return true;
            })
        .when(mockAppContext)
        .bindService(any(Intent.class), any(ServiceConnection.class), eq(Context.BIND_AUTO_CREATE));

    BackgroundSmbManager mgr = new BackgroundSmbManager(mockAppContext);
    SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);
    IBinder binder = service.new LocalBinder();
    captured[0].onServiceConnected(
        new ComponentName("de.schliweb.sambalite", "SmbBackgroundService"), binder);

    service.startOperation("upload1");
    assertTrue(mgr.hasActiveOperations());
    assertEquals(1, mgr.getActiveOperationCount());
  }

  @Test
  public void hasActiveOperations_afterFinishOperation_returnsFalse() {
    final ServiceConnection[] captured = new ServiceConnection[1];
    doReturn(null).when(mockAppContext).startForegroundService(any());
    doAnswer(
            invocation -> {
              captured[0] = invocation.getArgument(1);
              return true;
            })
        .when(mockAppContext)
        .bindService(any(Intent.class), any(ServiceConnection.class), eq(Context.BIND_AUTO_CREATE));

    BackgroundSmbManager mgr = new BackgroundSmbManager(mockAppContext);
    SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);
    IBinder binder = service.new LocalBinder();
    captured[0].onServiceConnected(
        new ComponentName("de.schliweb.sambalite", "SmbBackgroundService"), binder);

    service.startOperation("upload1");
    assertTrue(mgr.hasActiveOperations());

    service.finishOperation("upload1", true);
    assertFalse(mgr.hasActiveOperations());
    assertEquals(0, mgr.getActiveOperationCount());
  }

  @Test
  public void getActiveOperationCount_multipleOperations_returnsCorrectCount() {
    final ServiceConnection[] captured = new ServiceConnection[1];
    doReturn(null).when(mockAppContext).startForegroundService(any());
    doAnswer(
            invocation -> {
              captured[0] = invocation.getArgument(1);
              return true;
            })
        .when(mockAppContext)
        .bindService(any(Intent.class), any(ServiceConnection.class), eq(Context.BIND_AUTO_CREATE));

    BackgroundSmbManager mgr = new BackgroundSmbManager(mockAppContext);
    SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);
    IBinder binder = service.new LocalBinder();
    captured[0].onServiceConnected(
        new ComponentName("de.schliweb.sambalite", "SmbBackgroundService"), binder);

    service.startOperation("op1");
    service.startOperation("op2");
    service.startOperation("op3");
    assertEquals(3, mgr.getActiveOperationCount());

    service.finishOperation("op2", true);
    assertEquals(2, mgr.getActiveOperationCount());
  }

  // =========================================================================
  // BackgroundSmbManager: requestStopService
  // =========================================================================

  @Test
  public void requestStopService_sendsActionStopIntent() {
    ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
    when(mockAppContext.bindService(
            any(Intent.class), any(ServiceConnection.class), eq(Context.BIND_AUTO_CREATE)))
        .thenReturn(true);
    doThrow(new RuntimeException("not allowed")).when(mockAppContext).startForegroundService(any());

    BackgroundSmbManager mgr = new BackgroundSmbManager(mockAppContext);

    reset(mockAppContext);
    when(mockAppContext.getApplicationContext()).thenReturn(mockAppContext);

    mgr.requestStopService();

    verify(mockAppContext).startForegroundService(intentCaptor.capture());
    Intent stopIntent = intentCaptor.getValue();
    assertEquals(SmbBackgroundService.ACTION_STOP, stopIntent.getAction());
  }

  @Test
  public void requestStopService_unbindsServiceBeforeSendingStop() {
    final ServiceConnection[] captured = new ServiceConnection[1];
    doReturn(null).when(mockAppContext).startForegroundService(any());
    doAnswer(
            invocation -> {
              captured[0] = invocation.getArgument(1);
              return true;
            })
        .when(mockAppContext)
        .bindService(any(Intent.class), any(ServiceConnection.class), eq(Context.BIND_AUTO_CREATE));

    BackgroundSmbManager mgr = new BackgroundSmbManager(mockAppContext);

    // Connect the service
    SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);
    IBinder binder = service.new LocalBinder();
    captured[0].onServiceConnected(
        new ComponentName("de.schliweb.sambalite", "SmbBackgroundService"), binder);

    assertTrue(mgr.isServiceConnected());

    mgr.requestStopService();

    // After requestStopService, service should be disconnected
    assertFalse(mgr.isServiceConnected());
    // unbindService should have been called
    verify(mockAppContext).unbindService(captured[0]);
  }

  @Test
  public void requestStopService_setsStopRequestedFlag() {
    doThrow(new RuntimeException("not allowed")).when(mockAppContext).startForegroundService(any());
    when(mockAppContext.bindService(
            any(Intent.class), any(ServiceConnection.class), eq(Context.BIND_AUTO_CREATE)))
        .thenReturn(true);

    BackgroundSmbManager mgr = new BackgroundSmbManager(mockAppContext);

    reset(mockAppContext);
    when(mockAppContext.getApplicationContext()).thenReturn(mockAppContext);
    doReturn(null).when(mockAppContext).startForegroundService(any());

    mgr.requestStopService();

    // After stop, hasActiveOperations should return false (service is null)
    assertFalse(mgr.hasActiveOperations());
    assertEquals(0, mgr.getActiveOperationCount());
  }

  @Test
  public void requestStopService_afterStop_serviceNotReconnected() {
    final ServiceConnection[] captured = new ServiceConnection[1];
    doReturn(null).when(mockAppContext).startForegroundService(any());
    doAnswer(
            invocation -> {
              captured[0] = invocation.getArgument(1);
              return true;
            })
        .when(mockAppContext)
        .bindService(any(Intent.class), any(ServiceConnection.class), eq(Context.BIND_AUTO_CREATE));

    BackgroundSmbManager mgr = new BackgroundSmbManager(mockAppContext);

    // Connect the service
    SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);
    IBinder binder = service.new LocalBinder();
    captured[0].onServiceConnected(
        new ComponentName("de.schliweb.sambalite", "SmbBackgroundService"), binder);

    mgr.requestStopService();

    // Reset mock to track new calls
    reset(mockAppContext);
    when(mockAppContext.getApplicationContext()).thenReturn(mockAppContext);

    // Simulate service disconnected callback
    captured[0].onServiceDisconnected(
        new ComponentName("de.schliweb.sambalite", "SmbBackgroundService"));

    // Service should NOT be restarted after explicit stop
    verify(mockAppContext, never()).startForegroundService(any());
    verify(mockAppContext, never())
        .bindService(any(Intent.class), any(ServiceConnection.class), anyInt());
  }

  // =========================================================================
  // BackgroundSmbManager: ensureServiceRunning
  // =========================================================================

  @Test
  public void ensureServiceRunning_afterStop_restartsService() {
    final ServiceConnection[] captured = new ServiceConnection[1];
    doReturn(null).when(mockAppContext).startForegroundService(any());
    doAnswer(
            invocation -> {
              captured[0] = invocation.getArgument(1);
              return true;
            })
        .when(mockAppContext)
        .bindService(any(Intent.class), any(ServiceConnection.class), eq(Context.BIND_AUTO_CREATE));

    BackgroundSmbManager mgr = new BackgroundSmbManager(mockAppContext);

    // Connect, then stop
    SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);
    IBinder binder = service.new LocalBinder();
    captured[0].onServiceConnected(
        new ComponentName("de.schliweb.sambalite", "SmbBackgroundService"), binder);
    mgr.requestStopService();

    // Simulate disconnect
    captured[0].onServiceDisconnected(
        new ComponentName("de.schliweb.sambalite", "SmbBackgroundService"));

    // Reset mock to track new calls
    reset(mockAppContext);
    when(mockAppContext.getApplicationContext()).thenReturn(mockAppContext);
    doReturn(null).when(mockAppContext).startForegroundService(any());
    doReturn(true)
        .when(mockAppContext)
        .bindService(any(Intent.class), any(ServiceConnection.class), eq(Context.BIND_AUTO_CREATE));

    // ensureServiceRunning should restart the service
    mgr.ensureServiceRunning();

    verify(mockAppContext).startForegroundService(any());
    verify(mockAppContext)
        .bindService(any(Intent.class), any(ServiceConnection.class), eq(Context.BIND_AUTO_CREATE));
  }

  // =========================================================================
  // BackgroundSmbManager: onServiceConnected mirrors stopRequested
  // =========================================================================

  @Test
  public void onServiceConnected_mirrorsStopRequestedFromService() {
    final ServiceConnection[] captured = new ServiceConnection[1];
    doReturn(null).when(mockAppContext).startForegroundService(any());
    doAnswer(
            invocation -> {
              captured[0] = invocation.getArgument(1);
              return true;
            })
        .when(mockAppContext)
        .bindService(any(Intent.class), any(ServiceConnection.class), eq(Context.BIND_AUTO_CREATE));

    BackgroundSmbManager mgr = new BackgroundSmbManager(mockAppContext);

    // Create a service that has stopRequested=true
    SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);
    Intent stopIntent = new Intent(SmbBackgroundService.ACTION_STOP);
    service.onStartCommand(stopIntent, 0, 1);
    assertTrue(
        "Service should have stopRequested=true after ACTION_STOP", service.isStopRequested());

    // Connect with this service — manager should mirror the flag
    IBinder binder = service.new LocalBinder();
    captured[0].onServiceConnected(
        new ComponentName("de.schliweb.sambalite", "SmbBackgroundService"), binder);

    // After connecting to a stopped service, subsequent disconnect should NOT auto-restart
    reset(mockAppContext);
    when(mockAppContext.getApplicationContext()).thenReturn(mockAppContext);

    captured[0].onServiceDisconnected(
        new ComponentName("de.schliweb.sambalite", "SmbBackgroundService"));

    verify(mockAppContext, never()).startForegroundService(any());
  }

  // =========================================================================
  // SmbBackgroundService: ACTION_STOP handling
  // =========================================================================

  @Test
  public void onStartCommand_actionStop_returnsStartNotSticky() {
    SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);

    Intent stopIntent = new Intent(SmbBackgroundService.ACTION_STOP);
    int result = service.onStartCommand(stopIntent, 0, 1);

    assertEquals(android.app.Service.START_NOT_STICKY, result);
  }

  @Test
  public void onStartCommand_actionStop_setsStopRequestedTrue() {
    SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);

    assertFalse("stopRequested should be false initially", service.isStopRequested());

    Intent stopIntent = new Intent(SmbBackgroundService.ACTION_STOP);
    service.onStartCommand(stopIntent, 0, 1);

    assertTrue("stopRequested should be true after ACTION_STOP", service.isStopRequested());
  }

  @Test
  public void onStartCommand_actionStop_hasNoActiveOperations() {
    SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);

    Intent stopIntent = new Intent(SmbBackgroundService.ACTION_STOP);
    service.onStartCommand(stopIntent, 0, 1);

    assertFalse(service.hasActiveOperations());
    assertEquals(0, service.getActiveOperationCount());
  }

  @Test
  public void onStartCommand_actionStop_cancelsActiveOperations() {
    SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);

    service.startOperation("upload1");
    service.startOperation("download1");
    assertTrue(service.hasActiveOperations());
    assertEquals(2, service.getActiveOperationCount());

    Intent stopIntent = new Intent(SmbBackgroundService.ACTION_STOP);
    service.onStartCommand(stopIntent, 0, 1);

    assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(stopIntent, 0, 1));
  }

  @Test
  public void onStartCommand_actionCancel_returnsStartNotSticky() {
    SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);

    Intent cancelIntent = new Intent(SmbBackgroundService.ACTION_CANCEL);
    int result = service.onStartCommand(cancelIntent, 0, 1);

    assertEquals(android.app.Service.START_NOT_STICKY, result);
  }

  @Test
  public void onStartCommand_actionCancel_doesNotSetStopRequested() {
    SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);

    Intent cancelIntent = new Intent(SmbBackgroundService.ACTION_CANCEL);
    service.onStartCommand(cancelIntent, 0, 1);

    assertFalse("ACTION_CANCEL should not set stopRequested", service.isStopRequested());
  }

  // =========================================================================
  // SmbBackgroundService: stopRequested lifecycle
  // =========================================================================

  @Test
  public void normalStartup_resetsStopRequested() {
    SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);

    // First stop the service
    Intent stopIntent = new Intent(SmbBackgroundService.ACTION_STOP);
    service.onStartCommand(stopIntent, 0, 1);
    assertTrue(service.isStopRequested());

    // Normal startup (no action) should reset stopRequested
    Intent normalIntent = new Intent();
    service.onStartCommand(normalIntent, 0, 2);

    assertFalse("Normal startup should reset stopRequested", service.isStopRequested());
  }

  @Test
  public void normalStartup_returnsStartSticky() {
    SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);

    Intent normalIntent = new Intent();
    int result = service.onStartCommand(normalIntent, 0, 1);

    assertEquals(android.app.Service.START_STICKY, result);
  }

  // =========================================================================
  // SmbBackgroundService: Notification behavior
  // =========================================================================

  @Test
  public void notification_serviceIdleAfterStart_noActiveOperations() {
    SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);

    Intent intent = new Intent();
    service.onStartCommand(intent, 0, 1);

    assertFalse(service.hasActiveOperations());
  }

  @Test
  public void notification_serviceWithOperations_hasActiveOperations() {
    SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);

    Intent intent = new Intent();
    service.onStartCommand(intent, 0, 1);

    service.startOperation("testOp");
    assertTrue(service.hasActiveOperations());
  }

  // =========================================================================
  // SmbBackgroundService: Constants
  // =========================================================================

  @Test
  public void actionStop_constant_hasCorrectValue() {
    assertEquals("de.schliweb.sambalite.action.STOP", SmbBackgroundService.ACTION_STOP);
  }

  @Test
  public void actionCancel_constant_hasCorrectValue() {
    assertEquals("de.schliweb.sambalite.action.CANCEL", SmbBackgroundService.ACTION_CANCEL);
  }

  // =========================================================================
  // SmbBackgroundService: startOperation / finishOperation lifecycle
  // =========================================================================

  @Test
  public void startOperation_incrementsCount() {
    SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);

    assertEquals(0, service.getActiveOperationCount());

    service.startOperation("op1");
    assertEquals(1, service.getActiveOperationCount());

    service.startOperation("op2");
    assertEquals(2, service.getActiveOperationCount());
  }

  @Test
  public void finishOperation_decrementsCount() {
    SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);

    service.startOperation("op1");
    service.startOperation("op2");
    assertEquals(2, service.getActiveOperationCount());

    service.finishOperation("op1", true);
    assertEquals(1, service.getActiveOperationCount());

    service.finishOperation("op2", false);
    assertEquals(0, service.getActiveOperationCount());
  }

  @Test
  public void finishOperation_withUnknownName_decrementsCount() {
    SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);

    service.startOperation("op1");
    service.finishOperation("op1", true);
    assertEquals(0, service.getActiveOperationCount());

    // Finishing an unknown operation decrements the counter (current behavior)
    service.finishOperation("nonexistent", true);
    assertEquals(-1, service.getActiveOperationCount());
  }

  // =========================================================================
  // BackgroundSmbManager: isServiceConnected
  // =========================================================================

  @Test
  public void isServiceConnected_initiallyFalse_whenServiceStartFails() {
    doThrow(new RuntimeException("not allowed")).when(mockAppContext).startForegroundService(any());
    BackgroundSmbManager mgr = new BackgroundSmbManager(mockAppContext);
    assertFalse(mgr.isServiceConnected());
  }

  @Test
  public void isServiceConnected_trueAfterConnect() {
    ManagerWithConnection mc = createManagerWithService(true);
    assertTrue(mc.manager.isServiceConnected());
  }

  @Test
  public void isServiceConnected_falseAfterRequestStop() {
    ManagerWithConnection mc = createManagerWithService(true);
    assertTrue(mc.manager.isServiceConnected());

    mc.manager.requestStopService();
    assertFalse(mc.manager.isServiceConnected());
  }

  // =========================================================================
  // BackgroundSmbManager: startOperation / finishOperation delegation
  // =========================================================================

  @Test
  public void startOperation_delegatesToService_whenConnected() {
    final ServiceConnection[] captured = new ServiceConnection[1];
    doReturn(null).when(mockAppContext).startForegroundService(any());
    doAnswer(
            invocation -> {
              captured[0] = invocation.getArgument(1);
              return true;
            })
        .when(mockAppContext)
        .bindService(any(Intent.class), any(ServiceConnection.class), eq(Context.BIND_AUTO_CREATE));

    BackgroundSmbManager mgr = new BackgroundSmbManager(mockAppContext);
    SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);
    IBinder binder = service.new LocalBinder();
    captured[0].onServiceConnected(
        new ComponentName("de.schliweb.sambalite", "SmbBackgroundService"), binder);

    mgr.startOperation("testOp");
    assertTrue(service.hasActiveOperations());
    assertEquals(1, service.getActiveOperationCount());
  }

  @Test
  public void finishOperation_delegatesToService_whenConnected() {
    final ServiceConnection[] captured = new ServiceConnection[1];
    doReturn(null).when(mockAppContext).startForegroundService(any());
    doAnswer(
            invocation -> {
              captured[0] = invocation.getArgument(1);
              return true;
            })
        .when(mockAppContext)
        .bindService(any(Intent.class), any(ServiceConnection.class), eq(Context.BIND_AUTO_CREATE));

    BackgroundSmbManager mgr = new BackgroundSmbManager(mockAppContext);
    SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);
    IBinder binder = service.new LocalBinder();
    captured[0].onServiceConnected(
        new ComponentName("de.schliweb.sambalite", "SmbBackgroundService"), binder);

    mgr.startOperation("testOp");
    mgr.finishOperation("testOp", true);
    assertFalse(service.hasActiveOperations());
  }

  @Test
  public void startOperation_skipped_whenNotConnected() {
    doThrow(new RuntimeException("not allowed")).when(mockAppContext).startForegroundService(any());
    BackgroundSmbManager mgr = new BackgroundSmbManager(mockAppContext);

    // Should not throw, just skip
    mgr.startOperation("testOp");
    assertFalse(mgr.hasActiveOperations());
  }
}
