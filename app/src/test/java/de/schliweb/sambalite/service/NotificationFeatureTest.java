package de.schliweb.sambalite.service;

import static org.junit.Assert.*;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import java.util.List;
import java.util.Locale;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNotificationManager;

/**
 * Comprehensive notification tests for SmbBackgroundService.
 *
 * <p>Tests all combinations of: - Idle vs. active operations - Operation types: Search, Upload,
 * Download, Delete/Rename (generic cancelable) - Notification actions: Stop (idle), Cancel (active
 * cancelable), none (active non-cancelable) - Notification content: title, text - Content intent:
 * Search/Upload/Download deep links vs. generic fallback - Multiple operations and transitions
 * between states - ACTION_STOP removes notification - ACTION_CANCEL updates notification
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NotificationFeatureTest {

  private SmbBackgroundService service;
  private ShadowNotificationManager shadowNotificationManager;

  @Before
  public void setUp() {
    service = Robolectric.setupService(SmbBackgroundService.class);
    NotificationManager nm =
        (NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE);
    shadowNotificationManager = Shadows.shadowOf(nm);
  }

  // =========================================================================
  // Helper
  // =========================================================================

  private Notification getPostedNotification() {
    List<Notification> notifications = shadowNotificationManager.getAllNotifications();
    assertFalse("Expected at least one notification", notifications.isEmpty());
    return notifications.get(notifications.size() - 1);
  }

  private void startServiceNormally() {
    Intent intent = new Intent();
    service.onStartCommand(intent, 0, 1);
  }

  private Notification.Action findAction(Notification notification, String title) {
    if (notification.actions == null) return null;
    for (Notification.Action action : notification.actions) {
      if (title.equals(action.title.toString())) {
        return action;
      }
    }
    return null;
  }

  private int actionCount(Notification notification) {
    return notification.actions != null ? notification.actions.length : 0;
  }

  // =========================================================================
  // 1. Idle state: notification after normal start
  // =========================================================================

  @Test
  public void idle_notificationPosted_afterNormalStart() {
    startServiceNormally();
    Notification n = getPostedNotification();
    assertNotNull(n);
  }

  @Test
  public void idle_notificationTitle_isSmbServiceReady() {
    startServiceNormally();
    Notification n = getPostedNotification();
    assertEquals("SMB Service ready", n.extras.getString(Notification.EXTRA_TITLE));
  }

  @Test
  public void idle_notificationText_isReadyForBackgroundOperations() {
    startServiceNormally();
    Notification n = getPostedNotification();
    assertEquals("Ready for background operations", n.extras.getString(Notification.EXTRA_TEXT));
  }

  @Test
  public void idle_hasStopAction() {
    startServiceNormally();
    Notification n = getPostedNotification();
    Notification.Action stopAction = findAction(n, "Quit");
    assertNotNull("Idle notification should have Stop/Quit action", stopAction);
  }

  @Test
  public void idle_hasNoCancelAction() {
    startServiceNormally();
    Notification n = getPostedNotification();
    Notification.Action cancelAction = findAction(n, "Cancel");
    assertNull("Idle notification should not have Cancel action", cancelAction);
  }

  @Test
  public void idle_hasExactlyOneAction() {
    startServiceNormally();
    Notification n = getPostedNotification();
    assertEquals("Idle notification should have exactly 1 action (Quit)", 1, actionCount(n));
  }

  @Test
  public void idle_notificationIsOngoing() {
    startServiceNormally();
    Notification n = getPostedNotification();
    assertTrue("Notification should be ongoing", (n.flags & Notification.FLAG_ONGOING_EVENT) != 0);
  }

  @Test
  public void idle_notificationIsSilent() {
    startServiceNormally();
    Notification n = getPostedNotification();
    // Check channel importance instead of deprecated Notification.priority
    NotificationManager nm =
        (NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE);
    NotificationChannel channel = nm.getNotificationChannel(n.getChannelId());
    assertNotNull("Notification should have a channel", channel);
    assertTrue(
        "Notification channel should be low importance (silent)",
        channel.getImportance() <= NotificationManager.IMPORTANCE_LOW);
  }

  // =========================================================================
  // 2. Active generic operation (Delete/Rename) — cancelable
  // =========================================================================

  @Test
  public void activeGenericOp_hasCancelAction() {
    startServiceNormally();
    service.startOperation("delete");
    Notification n = getPostedNotification();
    Notification.Action cancelAction = findAction(n, "Cancel");
    assertNotNull("Active generic op should have Cancel action", cancelAction);
  }

  @Test
  public void activeGenericOp_hasNoStopAction() {
    startServiceNormally();
    service.startOperation("delete");
    Notification n = getPostedNotification();
    Notification.Action stopAction = findAction(n, "Quit");
    assertNull("Active op should not have Stop/Quit action", stopAction);
  }

  @Test
  public void activeGenericOp_hasExactlyOneAction() {
    startServiceNormally();
    service.startOperation("delete");
    Notification n = getPostedNotification();
    assertEquals("Active generic op should have exactly 1 action (Cancel)", 1, actionCount(n));
  }

  @Test
  public void activeRenameOp_hasCancelAction() {
    startServiceNormally();
    service.startOperation("rename");
    Notification n = getPostedNotification();
    Notification.Action cancelAction = findAction(n, "Cancel");
    assertNotNull("Active rename op should have Cancel action", cancelAction);
  }

  // =========================================================================
  // 3. Active Search operation — NOT cancelable via notification
  // =========================================================================

  @Test
  public void activeSearchOp_hasNoCancelAction() {
    startServiceNormally();
    service.startOperation("search");
    Notification n = getPostedNotification();
    Notification.Action cancelAction = findAction(n, "Cancel");
    assertNull("Active search op should NOT have Cancel action", cancelAction);
  }

  @Test
  public void activeSearchOp_hasNoStopAction() {
    startServiceNormally();
    service.startOperation("search");
    Notification n = getPostedNotification();
    Notification.Action stopAction = findAction(n, "Quit");
    assertNull("Active search op should NOT have Stop action", stopAction);
  }

  @Test
  public void activeSearchOp_hasNoActions() {
    startServiceNormally();
    service.startOperation("search");
    Notification n = getPostedNotification();
    assertEquals("Active search op should have 0 actions", 0, actionCount(n));
  }

  // =========================================================================
  // 4. Active Upload operation — NOT cancelable via notification
  // =========================================================================

  @Test
  public void activeUploadOp_hasNoCancelAction() {
    startServiceNormally();
    service.startOperation("upload");
    Notification n = getPostedNotification();
    Notification.Action cancelAction = findAction(n, "Cancel");
    assertNull("Active upload op should NOT have Cancel action", cancelAction);
  }

  @Test
  public void activeUploadOp_hasNoStopAction() {
    startServiceNormally();
    service.startOperation("upload");
    Notification n = getPostedNotification();
    Notification.Action stopAction = findAction(n, "Quit");
    assertNull("Active upload op should NOT have Stop action", stopAction);
  }

  @Test
  public void activeUploadOp_hasNoActions() {
    startServiceNormally();
    service.startOperation("upload");
    Notification n = getPostedNotification();
    assertEquals("Active upload op should have 0 actions", 0, actionCount(n));
  }

  // =========================================================================
  // 5. Active Download operation — NOT cancelable via notification
  // =========================================================================

  @Test
  public void activeDownloadOp_hasNoCancelAction() {
    startServiceNormally();
    service.startOperation("download");
    Notification n = getPostedNotification();
    Notification.Action cancelAction = findAction(n, "Cancel");
    assertNull("Active download op should NOT have Cancel action", cancelAction);
  }

  @Test
  public void activeDownloadOp_hasNoStopAction() {
    startServiceNormally();
    service.startOperation("download");
    Notification n = getPostedNotification();
    Notification.Action stopAction = findAction(n, "Quit");
    assertNull("Active download op should NOT have Stop action", stopAction);
  }

  @Test
  public void activeDownloadOp_hasNoActions() {
    startServiceNormally();
    service.startOperation("download");
    Notification n = getPostedNotification();
    assertEquals("Active download op should have 0 actions", 0, actionCount(n));
  }

  // =========================================================================
  // 6. Transition: active → idle (finish operation)
  // =========================================================================

  @Test
  public void afterFinishOperation_serviceBecomesIdle() {
    startServiceNormally();
    service.startOperation("delete");

    // While active: no Stop action
    Notification nActive = getPostedNotification();
    assertNull(findAction(nActive, "Quit"));
    assertTrue(service.hasActiveOperations());

    // Finish operation → idle
    service.finishOperation("delete", true);
    assertFalse(
        "Service should be idle after finishing all operations", service.hasActiveOperations());
  }

  @Test
  public void afterFinishAllOperations_noActiveOperations() {
    startServiceNormally();
    service.startOperation("upload");
    service.startOperation("delete");
    assertEquals(2, service.getActiveOperationCount());

    service.finishOperation("upload", true);
    service.finishOperation("delete", true);
    assertFalse(service.hasActiveOperations());
  }

  // =========================================================================
  // 7. Notification content for different operation types
  // =========================================================================

  @Test
  public void searchOp_notificationTitle_containsSearch() {
    startServiceNormally();
    service.startOperation("search");
    Notification n = getPostedNotification();
    String title = n.extras.getString(Notification.EXTRA_TITLE);
    assertNotNull(title);
    assertTrue("Search op title should contain 'Searching'", title.contains("Searching"));
  }

  @Test
  public void uploadOp_notificationTitle_containsUpload() {
    startServiceNormally();
    service.startOperation("upload");
    Notification n = getPostedNotification();
    String title = n.extras.getString(Notification.EXTRA_TITLE);
    assertNotNull(title);
    assertTrue("Upload op title should contain 'Uploading'", title.contains("Uploading"));
  }

  @Test
  public void downloadOp_notificationTitle_containsDownload() {
    startServiceNormally();
    service.startOperation("download");
    Notification n = getPostedNotification();
    String title = n.extras.getString(Notification.EXTRA_TITLE);
    assertNotNull(title);
    assertTrue("Download op title should contain 'Downloading'", title.contains("Downloading"));
  }

  @Test
  public void deleteOp_notificationTitle_containsDelete() {
    startServiceNormally();
    service.startOperation("delete");
    Notification n = getPostedNotification();
    String title = n.extras.getString(Notification.EXTRA_TITLE);
    assertNotNull(title);
    assertTrue("Delete op title should contain 'Deleting'", title.contains("Deleting"));
  }

  @Test
  public void renameOp_notificationTitle_containsRename() {
    startServiceNormally();
    service.startOperation("rename");
    Notification n = getPostedNotification();
    String title = n.extras.getString(Notification.EXTRA_TITLE);
    assertNotNull(title);
    assertTrue("Rename op title should contain 'Renaming'", title.contains("Renaming"));
  }

  // =========================================================================
  // 8. Content intent deep links via setXxxParameters
  // =========================================================================

  @Test
  public void setUploadParameters_updatesNotification() {
    startServiceNormally();
    service.setUploadParameters("conn1", "/share/docs");
    Notification n = getPostedNotification();
    String title = n.extras.getString(Notification.EXTRA_TITLE);
    assertNotNull(title);
    assertTrue(
        "After setUploadParameters, title should contain 'Uploading'", title.contains("Uploading"));
  }

  @Test
  public void setDownloadParameters_updatesNotification() {
    startServiceNormally();
    service.setDownloadParameters("conn1", "/share/docs");
    Notification n = getPostedNotification();
    String title = n.extras.getString(Notification.EXTRA_TITLE);
    assertNotNull(title);
    assertTrue(
        "After setDownloadParameters, title should contain 'Downloading'",
        title.contains("Downloading"));
  }

  // =========================================================================
  // 9. ACTION_STOP removes notification
  // =========================================================================

  @Test
  public void actionStop_removesNotification() {
    startServiceNormally();
    Notification n = getPostedNotification();
    assertNotNull(n);

    Intent stopIntent = new Intent(SmbBackgroundService.ACTION_STOP);
    service.onStartCommand(stopIntent, 0, 2);

    // After ACTION_STOP, notification should be cancelled
    List<Notification> remaining = shadowNotificationManager.getAllNotifications();
    // ShadowNotificationManager.cancel removes from the list
    assertTrue("Notification should be removed after ACTION_STOP", remaining.isEmpty());
  }

  @Test
  public void actionStop_whileActive_removesNotification() {
    startServiceNormally();
    service.startOperation("upload");
    assertTrue(service.hasActiveOperations());

    Intent stopIntent = new Intent(SmbBackgroundService.ACTION_STOP);
    service.onStartCommand(stopIntent, 0, 2);

    List<Notification> remaining = shadowNotificationManager.getAllNotifications();
    assertTrue(
        "Notification should be removed after ACTION_STOP even with active ops",
        remaining.isEmpty());
  }

  // =========================================================================
  // 10. ACTION_CANCEL updates notification (does not remove)
  // =========================================================================

  @Test
  public void actionCancel_doesNotRemoveNotification() {
    startServiceNormally();
    service.startOperation("delete");

    Intent cancelIntent = new Intent(SmbBackgroundService.ACTION_CANCEL);
    service.onStartCommand(cancelIntent, 0, 2);

    List<Notification> remaining = shadowNotificationManager.getAllNotifications();
    assertFalse("Notification should still exist after ACTION_CANCEL", remaining.isEmpty());
  }

  @Test
  public void actionCancel_notificationShowsCancelledText() {
    startServiceNormally();
    service.startOperation("delete");

    Intent cancelIntent = new Intent(SmbBackgroundService.ACTION_CANCEL);
    service.onStartCommand(cancelIntent, 0, 2);

    Notification n = getPostedNotification();
    String title = n.extras.getString(Notification.EXTRA_TITLE);
    assertNotNull(title);
    assertTrue(
        "After cancel, title should indicate cancellation",
        title.toLowerCase(Locale.ROOT).contains("cancel"));
  }

  // =========================================================================
  // 11. Multiple operations: mixed types
  // =========================================================================

  @Test
  public void multipleOps_searchThenDelete_searchFlagsOverrideCancel() {
    startServiceNormally();
    // Start search first (sets isSearchOperation=true)
    service.startOperation("search");
    // Start delete (sets isSearchOperation=false)
    service.startOperation("delete");

    Notification n = getPostedNotification();
    // After delete starts, isSearchOperation=false, so cancel should appear
    Notification.Action cancelAction = findAction(n, "Cancel");
    assertNotNull(
        "After search→delete, Cancel should appear (delete is cancelable, search flag cleared)",
        cancelAction);
  }

  @Test
  public void multipleOps_deleteThenSearch_noCancelAction() {
    startServiceNormally();
    service.startOperation("delete");
    // Now start search — overrides flags
    service.startOperation("search");

    Notification n = getPostedNotification();
    Notification.Action cancelAction = findAction(n, "Cancel");
    assertNull("After delete→search, Cancel should NOT appear (search flag set)", cancelAction);
  }

  @Test
  public void multipleOps_deleteThenUpload_noCancelAction() {
    startServiceNormally();
    service.startOperation("delete");
    service.startOperation("upload");

    Notification n = getPostedNotification();
    Notification.Action cancelAction = findAction(n, "Cancel");
    assertNull("After delete→upload, Cancel should NOT appear (upload flag set)", cancelAction);
  }

  @Test
  public void multipleOps_deleteThenDownload_noCancelAction() {
    startServiceNormally();
    service.startOperation("delete");
    service.startOperation("download");

    Notification n = getPostedNotification();
    Notification.Action cancelAction = findAction(n, "Cancel");
    assertNull("After delete→download, Cancel should NOT appear (download flag set)", cancelAction);
  }

  // =========================================================================
  // 12. Notification channel
  // =========================================================================

  @Test
  public void notificationChannel_isCreated() {
    startServiceNormally();
    NotificationManager nm =
        (NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE);
    assertNotNull(
        "Notification channel should exist",
        nm.getNotificationChannel("SMB_BACKGROUND_OPERATIONS"));
  }

  @Test
  public void notificationChannel_hasLowImportance() {
    startServiceNormally();
    NotificationManager nm =
        (NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE);
    assertEquals(
        NotificationManager.IMPORTANCE_LOW,
        nm.getNotificationChannel("SMB_BACKGROUND_OPERATIONS").getImportance());
  }

  // =========================================================================
  // 13. Progress updates
  // =========================================================================

  @Test
  public void updateOperationProgress_updatesNotificationContent() {
    startServiceNormally();
    service.startOperation("download");
    service.updateOperationProgress("download", "50% complete");

    Notification n = getPostedNotification();
    String text = n.extras.getString(Notification.EXTRA_TEXT);
    assertNotNull(text);
    assertTrue("Progress text should be reflected", text.contains("50% complete"));
  }

  // =========================================================================
  // 14. Clear parameters reset operation flags
  // =========================================================================

  @Test
  public void clearUploadParameters_afterSet_cancelActionAppearsForGenericOp() {
    startServiceNormally();
    service.setUploadParameters("conn1", "/path");
    service.clearUploadParameters();

    service.startOperation("delete");
    Notification n = getPostedNotification();
    Notification.Action cancelAction = findAction(n, "Cancel");
    assertNotNull("After clearing upload params, generic op should have Cancel", cancelAction);
  }

  @Test
  public void clearDownloadParameters_afterSet_cancelActionAppearsForGenericOp() {
    startServiceNormally();
    service.setDownloadParameters("conn1", "/path");
    service.clearDownloadParameters();

    service.startOperation("delete");
    Notification n = getPostedNotification();
    Notification.Action cancelAction = findAction(n, "Cancel");
    assertNotNull("After clearing download params, generic op should have Cancel", cancelAction);
  }

  // =========================================================================
  // 15. Null intent handling
  // =========================================================================

  @Test
  public void nullIntent_withoutActiveOperations_stopsImmediately() {
    int result = service.onStartCommand(null, 0, 1);
    // After swipe-kill restart with no active operations, service should stop itself
    assertEquals(android.app.Service.START_NOT_STICKY, result);
  }

  @Test
  public void nullIntent_withoutActiveOperations_removesNotification() {
    // After swipe-kill restart with no active operations, service stops itself
    // and removes the foreground notification
    service.onStartCommand(null, 0, 1);
    List<Notification> notifications = shadowNotificationManager.getAllNotifications();
    assertTrue(
        "Null intent without active operations should not leave a notification",
        notifications.isEmpty());
  }

  // =========================================================================
  // 16. Stop action PendingIntent contains ACTION_STOP
  // =========================================================================

  @Test
  public void stopAction_pendingIntent_isNotNull() {
    startServiceNormally();
    Notification n = getPostedNotification();
    Notification.Action stopAction = findAction(n, "Quit");
    assertNotNull(stopAction);
    assertNotNull("Stop action should have a PendingIntent", stopAction.actionIntent);
  }

  // =========================================================================
  // 17. Repeated start commands don't duplicate notifications
  // =========================================================================

  @Test
  public void repeatedStartCommands_sameNotificationId() {
    startServiceNormally();
    int countAfterFirst = shadowNotificationManager.getAllNotifications().size();

    // Second start
    service.onStartCommand(new Intent(), 0, 2);
    int countAfterSecond = shadowNotificationManager.getAllNotifications().size();

    // Should not increase (same NOTIFICATION_ID is reused)
    assertEquals(
        "Repeated starts should not create additional notifications",
        countAfterFirst,
        countAfterSecond);
  }

  // =========================================================================
  // 18. Idle notification has no content intent (SMB Service ready)
  // =========================================================================

  @Test
  public void idle_noContentIntent() {
    startServiceNormally();
    Notification n = getPostedNotification();
    assertNull(
        "Idle notification ('SMB Service ready') should have no content intent", n.contentIntent);
  }

  // =========================================================================
  // 19. Active operation has content intent
  // =========================================================================

  @Test
  public void activeOp_hasContentIntent() {
    startServiceNormally();
    service.startOperation("delete");
    Notification n = getPostedNotification();
    assertNotNull("Active operation notification should have a content intent", n.contentIntent);
  }

  @Test
  public void uploadOp_withParams_hasContentIntent() {
    startServiceNormally();
    service.setUploadParameters("conn1", "/share/docs");
    service.startOperation("upload");
    Notification n = getPostedNotification();
    assertNotNull("Upload operation with params should have a content intent", n.contentIntent);
  }

  @Test
  public void downloadOp_withParams_hasContentIntent() {
    startServiceNormally();
    service.setDownloadParameters("conn1", "/share/docs");
    service.startOperation("download");
    Notification n = getPostedNotification();
    assertNotNull("Download operation with params should have a content intent", n.contentIntent);
  }
}
