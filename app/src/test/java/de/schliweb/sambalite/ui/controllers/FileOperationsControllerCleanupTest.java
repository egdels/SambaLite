package de.schliweb.sambalite.ui.controllers;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.*;

import android.content.Context;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests for the cleanupSharedTextSourceFile method in FileOperationsController. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = de.schliweb.sambalite.SambaLiteApp.class)
public class FileOperationsControllerCleanupTest {

  private Context context;
  private FileOperationsController controller;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    // Create controller with minimal dependencies; only context is needed for cleanup
    controller = new FileOperationsController(context, null, null, null, null);
  }

  private Method getCleanupMethod() throws NoSuchMethodException {
    Method method =
        FileOperationsController.class.getDeclaredMethod("cleanupSharedTextSourceFile", Uri.class);
    method.setAccessible(true);
    return method;
  }

  @Test
  public void cleanupSharedTextSourceFile_deletesFileInSharedTextDir() throws Exception {
    File sharedTextDir = new File(context.getCacheDir(), "shared_text");
    sharedTextDir.mkdirs();
    File testFile = new File(sharedTextDir, "test_20260307_091900.txt");
    writeFile(testFile, "test content");
    assertTrue("File should exist before cleanup", testFile.exists());

    getCleanupMethod().invoke(controller, Uri.fromFile(testFile));

    assertFalse("File should be deleted after cleanup", testFile.exists());
  }

  @Test
  public void cleanupSharedTextSourceFile_doesNotDeleteFileOutsideSharedTextDir() throws Exception {
    File otherDir = new File(context.getCacheDir(), "other");
    otherDir.mkdirs();
    File testFile = new File(otherDir, "keep_me.txt");
    writeFile(testFile, "keep");
    assertTrue("File should exist before cleanup", testFile.exists());

    getCleanupMethod().invoke(controller, Uri.fromFile(testFile));

    assertTrue("File outside shared_text dir should NOT be deleted", testFile.exists());
  }

  @Test
  public void cleanupSharedTextSourceFile_ignoresContentUri() throws Exception {
    // content:// URIs should be ignored (no crash)
    Uri contentUri = Uri.parse("content://com.example.provider/file.txt");
    getCleanupMethod().invoke(controller, contentUri);
    // No exception = pass
  }

  @Test
  public void cleanupSharedTextSourceFile_ignoresNullUri() throws Exception {
    getCleanupMethod().invoke(controller, (Uri) null);
    // No exception = pass
  }

  private void writeFile(File file, String content) throws IOException {
    try (FileOutputStream fos = new FileOutputStream(file)) {
      fos.write(content.getBytes(UTF_8));
    }
  }
}
