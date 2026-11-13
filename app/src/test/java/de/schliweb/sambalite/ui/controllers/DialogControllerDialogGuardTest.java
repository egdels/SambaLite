package de.schliweb.sambalite.ui.controllers;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.appcompat.app.AppCompatActivity;
import androidx.test.core.app.ApplicationProvider;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.ui.operations.FileOperationCallbacks;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

/**
 * The result of a rename, delete or folder creation arrives asynchronously and may find the
 * activity already finished. Showing the error dialog then crashed with BadTokenException (Play
 * Console, 3.2.0).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = de.schliweb.sambalite.SambaLiteApp.class)
public class DialogControllerDialogGuardTest {

  /** Plain AppCompat activity with the app's Material theme, so dialogs can be inflated. */
  public static class HostActivity extends AppCompatActivity {
    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
      setTheme(R.style.Theme_SambaLite);
      super.onCreate(savedInstanceState);
    }
  }

  private static void reportRenameFailure(DialogController controller) throws Exception {
    Method m = DialogController.class.getDeclaredMethod("createRenameCallback");
    m.setAccessible(true);
    ((FileOperationCallbacks.RenameFileCallback) m.invoke(controller))
        .onResult(false, "Connection lost");
  }

  @Test
  public void errorDialog_isShownWhileActivityIsActive() throws Exception {
    ActivityController<HostActivity> ac = Robolectric.buildActivity(HostActivity.class).setup();
    DialogController controller = new DialogController(ac.get(), null, new FileBrowserUIState());
    ShadowDialog.reset();

    reportRenameFailure(controller);

    assertNotNull(ShadowDialog.getLatestDialog());
    assertTrue(ShadowDialog.getLatestDialog().isShowing());
  }

  @Test
  public void errorDialog_isSkippedAfterActivityIsDestroyed() throws Exception {
    ActivityController<HostActivity> ac = Robolectric.buildActivity(HostActivity.class).setup();
    DialogController controller = new DialogController(ac.get(), null, new FileBrowserUIState());
    ac.pause().stop().destroy();
    ShadowDialog.reset();

    reportRenameFailure(controller);

    assertNull(ShadowDialog.getLatestDialog());
  }

  @Test
  public void errorDialog_isSkippedWhileActivityIsFinishing() throws Exception {
    ActivityController<HostActivity> ac = Robolectric.buildActivity(HostActivity.class).setup();
    DialogController controller = new DialogController(ac.get(), null, new FileBrowserUIState());
    ac.get().finish();
    ShadowDialog.reset();

    reportRenameFailure(controller);

    assertNull(ShadowDialog.getLatestDialog());
  }

  @Test
  public void usableContext_keepsNonActivityContext() {
    Context app = ApplicationProvider.getApplicationContext();
    assertSame(app, DialogController.usableContext(app));
    assertNull(DialogController.usableContext(null));
  }
}
