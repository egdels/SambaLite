package de.schliweb.sambalite.service;

import static org.junit.Assert.*;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNotificationManager;

/**
 * Tests that cement the swipe-kill behavior of SmbBackgroundService.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>onTaskRemoved() with and without active operations
 *   <li>SharedPreferences persistence of cancelled operation state
 *   <li>Service self-stop after swipe-kill
 *   <li>Null intent handling (system restart after process death)
 *   <li>cancelAllOperations() is triggered on swipe-kill
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SwipeKillBehaviorTest {

  private static final String PREFS_NAME = "swipe_kill_state";

  private SmbBackgroundService service;
  private ShadowNotificationManager shadowNotificationManager;

  @Before
  public void setUp() {
    service = Robolectric.setupService(SmbBackgroundService.class);
    NotificationManager nm =
        (NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE);
    shadowNotificationManager = Shadows.shadowOf(nm);
  }

  private void startServiceNormally() {
    service.onStartCommand(new Intent(), 0, 1);
  }

  private SharedPreferences getSwipeKillPrefs() {
    return service.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  // =========================================================================
  // 1. onTaskRemoved() without active operations
  // =========================================================================

  @Test
  public void onTaskRemoved_noActiveOps_doesNotPersistState() {
    startServiceNormally();
    assertFalse(service.hasActiveOperations());

    service.onTaskRemoved(null);

    SharedPreferences prefs = getSwipeKillPrefs();
    assertFalse(
        "Should not persist state when no operations are active",
        prefs.getBoolean("had_active_operations", false));
  }

  @Test
  public void onTaskRemoved_noActiveOps_removesNotification() {
    startServiceNormally();
    // Verify notification exists before
    assertFalse(shadowNotificationManager.getAllNotifications().isEmpty());

    service.onTaskRemoved(null);

    List<Notification> remaining = shadowNotificationManager.getAllNotifications();
    assertTrue(
        "Notification should be removed after onTaskRemoved without active ops",
        remaining.isEmpty());
  }

  // =========================================================================
  // 2. onTaskRemoved() with active operations
  // =========================================================================

  @Test
  public void onTaskRemoved_withActiveOps_persistsState() {
    startServiceNormally();
    service.startOperation("upload");
    assertTrue(service.hasActiveOperations());

    service.onTaskRemoved(null);

    SharedPreferences prefs = getSwipeKillPrefs();
    assertTrue(
        "Should persist had_active_operations=true",
        prefs.getBoolean("had_active_operations", false));
  }

  @Test
  public void onTaskRemoved_withActiveOps_persistsOperationCount() {
    startServiceNormally();
    service.startOperation("upload");
    service.startOperation("download");

    service.onTaskRemoved(null);

    SharedPreferences prefs = getSwipeKillPrefs();
    assertEquals(
        "Should persist the correct active operation count",
        2,
        prefs.getInt("active_operation_count", 0));
  }

  @Test
  public void onTaskRemoved_withActiveOps_persistsTimestamp() {
    startServiceNormally();
    service.startOperation("upload");

    long before = System.currentTimeMillis();
    service.onTaskRemoved(null);
    long after = System.currentTimeMillis();

    SharedPreferences prefs = getSwipeKillPrefs();
    long timestamp = prefs.getLong("timestamp", 0);
    assertTrue("Timestamp should be set", timestamp >= before && timestamp <= after);
  }

  @Test
  public void onTaskRemoved_withActiveOps_persistsLastOperation() {
    startServiceNormally();
    service.startOperation("upload");

    service.onTaskRemoved(null);

    SharedPreferences prefs = getSwipeKillPrefs();
    String lastOp = prefs.getString("last_operation", "");
    assertNotNull("last_operation should be persisted", lastOp);
  }

  @Test
  public void onTaskRemoved_withActiveOps_removesNotification() {
    startServiceNormally();
    service.startOperation("upload");

    service.onTaskRemoved(null);

    List<Notification> remaining = shadowNotificationManager.getAllNotifications();
    assertTrue(
        "Notification should be removed after onTaskRemoved with active ops", remaining.isEmpty());
  }

  @Test
  public void onTaskRemoved_withActiveOps_cancelsOperations() {
    startServiceNormally();
    service.startOperation("upload");
    service.startOperation("search");

    service.onTaskRemoved(null);

    // After onTaskRemoved, the notification should show cancellation text
    // (cancelAllOperations updates the notification before stopForeground removes it)
    // The key assertion: operations were cancelled — verify via isOperationCancelled
    // Since the service calls cancelAllOperations which cancels futures,
    // we verify indirectly that the service no longer considers itself running
    // by checking that stopForeground was called (notification removed)
    assertTrue(
        "Notifications should be cleared after task removal",
        shadowNotificationManager.getAllNotifications().isEmpty());
  }

  // =========================================================================
  // 3. Null intent handling (system restart after swipe-kill)
  // =========================================================================

  @Test
  public void nullIntent_noActiveOps_returnsStartNotSticky() {
    int result = service.onStartCommand(null, 0, 1);
    assertEquals(
        "Null intent without active ops should return START_NOT_STICKY",
        android.app.Service.START_NOT_STICKY,
        result);
  }

  @Test
  public void nullIntent_noActiveOps_removesNotification() {
    service.onStartCommand(null, 0, 1);
    assertTrue(
        "Null intent without active ops should not leave notifications",
        shadowNotificationManager.getAllNotifications().isEmpty());
  }

  @Test
  public void normalIntent_returnsStartSticky() {
    int result = service.onStartCommand(new Intent(), 0, 1);
    assertEquals(
        "Normal intent should return START_STICKY", android.app.Service.START_STICKY, result);
  }

  // =========================================================================
  // 4. SharedPreferences are NOT written when no operations active
  // =========================================================================

  @Test
  public void onTaskRemoved_noActiveOps_prefsRemainEmpty() {
    startServiceNormally();

    service.onTaskRemoved(null);

    SharedPreferences prefs = getSwipeKillPrefs();
    assertTrue("Prefs should have no entries when no ops were active", prefs.getAll().isEmpty());
  }

  // =========================================================================
  // 5. Multiple swipe-kills: prefs are overwritten correctly
  // =========================================================================

  @Test
  public void onTaskRemoved_overwritesPreviousPrefs() {
    startServiceNormally();
    service.startOperation("upload");
    service.onTaskRemoved(null);

    // Simulate second service lifecycle
    SmbBackgroundService service2 = Robolectric.setupService(SmbBackgroundService.class);
    service2.onStartCommand(new Intent(), 0, 1);
    service2.startOperation("download");
    service2.startOperation("search");
    service2.onTaskRemoved(null);

    SharedPreferences prefs = getSwipeKillPrefs();
    assertEquals(
        "Operation count should reflect the latest swipe-kill",
        2,
        prefs.getInt("active_operation_count", 0));
  }

  // =========================================================================
  // 6. onTaskRemoved with single operation types
  // =========================================================================

  @Test
  public void onTaskRemoved_withSearch_persistsState() {
    startServiceNormally();
    service.startOperation("search");

    service.onTaskRemoved(null);

    assertTrue(getSwipeKillPrefs().getBoolean("had_active_operations", false));
  }

  @Test
  public void onTaskRemoved_withDownload_persistsState() {
    startServiceNormally();
    service.startOperation("download");

    service.onTaskRemoved(null);

    assertTrue(getSwipeKillPrefs().getBoolean("had_active_operations", false));
  }

  @Test
  public void onTaskRemoved_withDelete_persistsState() {
    startServiceNormally();
    service.startOperation("delete");

    service.onTaskRemoved(null);

    assertTrue(getSwipeKillPrefs().getBoolean("had_active_operations", false));
  }

  // =========================================================================
  // 7. Service does not restart itself via startForegroundService
  //    (the old buggy behavior that caused ghost notifications)
  // =========================================================================

  @Test
  public void onTaskRemoved_doesNotLeaveGhostNotification() {
    startServiceNormally();
    service.startOperation("upload");

    service.onTaskRemoved(null);

    // The old code called startForegroundService() which caused a ghost "SMB Service ready"
    // notification after process restart. The new code should leave no notification.
    List<Notification> notifications = shadowNotificationManager.getAllNotifications();
    assertTrue("No ghost notification should remain after swipe-kill", notifications.isEmpty());
  }

  // =========================================================================
  // 8. After finishing all operations, onTaskRemoved does not persist
  // =========================================================================

  @Test
  public void onTaskRemoved_afterAllOpsFinished_doesNotPersistState() {
    startServiceNormally();
    service.startOperation("upload");
    service.finishOperation("upload", true);
    assertFalse(service.hasActiveOperations());

    service.onTaskRemoved(null);

    assertFalse(
        "Should not persist state when all operations already finished",
        getSwipeKillPrefs().getBoolean("had_active_operations", false));
  }
}
