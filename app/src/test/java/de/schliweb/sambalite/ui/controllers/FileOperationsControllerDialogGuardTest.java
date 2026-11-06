package de.schliweb.sambalite.ui.controllers;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.appcompat.app.AppCompatActivity;
import androidx.test.core.app.ApplicationProvider;
import de.schliweb.sambalite.R;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

/**
 * A conflict check returns on a background thread and may find the activity already finished.
 * Showing a dialog then crashed with BadTokenException (Play Console, 3.0.0-rc2).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = de.schliweb.sambalite.SambaLiteApp.class)
public class FileOperationsControllerDialogGuardTest {

  /** Plain AppCompat activity with the app's Material theme, so dialogs can be inflated. */
  public static class HostActivity extends AppCompatActivity {
    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
      setTheme(R.style.Theme_SambaLite);
      super.onCreate(savedInstanceState);
    }
  }

  private static void showCheckFailed(FileOperationsController controller, Runnable uploadAnyway)
      throws Exception {
    Method m =
        FileOperationsController.class.getDeclaredMethod(
            "showCheckFailedDialog", int.class, String.class, Runnable.class);
    m.setAccessible(true);
    m.invoke(controller, 3, null, uploadAnyway);
  }

  @Test
  public void checkFailedDialog_isShownWhileActivityIsActive() throws Exception {
    ActivityController<HostActivity> ac = Robolectric.buildActivity(HostActivity.class).setup();
    FileOperationsController controller =
        new FileOperationsController(ac.get(), null, null, null, null);

    assertTrue(controller.canShowDialog());
    showCheckFailed(controller, () -> {});

    assertNotNull(ShadowDialog.getLatestDialog());
    assertTrue(ShadowDialog.getLatestDialog().isShowing());
  }

  @Test
  public void checkFailedDialog_isSkippedAndUploadsNothingAfterActivityIsDestroyed()
      throws Exception {
    ActivityController<HostActivity> ac = Robolectric.buildActivity(HostActivity.class).setup();
    FileOperationsController controller =
        new FileOperationsController(ac.get(), null, null, null, null);
    ac.pause().stop().destroy();
    ShadowDialog.reset();

    AtomicBoolean uploaded = new AtomicBoolean(false);
    assertFalse(controller.canShowDialog());
    showCheckFailed(controller, () -> uploaded.set(true));

    assertNull(ShadowDialog.getLatestDialog());
    assertFalse("must not upload without the user's confirmation", uploaded.get());
  }

  @Test
  public void canShowDialog_isFalseForNonActivityContext() {
    Context app = ApplicationProvider.getApplicationContext();
    assertFalse(new FileOperationsController(app, null, null, null, null).canShowDialog());
  }
}
