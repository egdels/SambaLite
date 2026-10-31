package de.schliweb.sambalite.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Tests for {@link OpenFileCacheManager}. Verifies time-based cleanup, size-based eviction, and
 * cache directory creation.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class OpenFileCacheManagerTest {

  private Context context;
  private File cacheDir;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    cacheDir = new File(context.getCacheDir(), "open_files");
    if (cacheDir.exists()) {
      deleteRecursive(cacheDir);
    }
  }

  @After
  public void tearDown() {
    if (cacheDir.exists()) {
      deleteRecursive(cacheDir);
    }
  }

  @Test
  public void cleanupOnAppStart_noCacheDir_doesNotCrash() {
    assertFalse(cacheDir.exists());
    OpenFileCacheManager.cleanupOnAppStart(context);
    // No exception expected
  }

  @Test
  public void cleanupOnAppStart_deletesOldFiles() throws IOException {
    cacheDir.mkdirs();
    File oldFile = createFileWithSize(cacheDir, "old.pdf", 1024);
    // Set last modified to 2 hours ago
    oldFile.setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000);

    File recentFile = createFileWithSize(cacheDir, "recent.pdf", 1024);
    recentFile.setLastModified(System.currentTimeMillis());

    OpenFileCacheManager.cleanupOnAppStart(context);

    assertFalse("Old file should be deleted", oldFile.exists());
    assertTrue("Recent file should remain", recentFile.exists());
  }

  @Test
  public void cleanupOnAppStart_keepsFilesUnderOneHour() throws IOException {
    cacheDir.mkdirs();
    File file = createFileWithSize(cacheDir, "recent.pdf", 1024);
    file.setLastModified(System.currentTimeMillis() - 30 * 60 * 1000); // 30 min ago

    OpenFileCacheManager.cleanupOnAppStart(context);

    assertTrue("File under 1 hour should remain", file.exists());
  }

  @Test
  public void enforceMaxSize_noCacheDir_doesNotCrash() {
    assertFalse(cacheDir.exists());
    OpenFileCacheManager.enforceMaxSize(context);
  }

  @Test
  public void enforceMaxSize_underLimit_noFilesDeleted() throws IOException {
    cacheDir.mkdirs();
    File file = createFileWithSize(cacheDir, "small.pdf", 1024);

    OpenFileCacheManager.enforceMaxSize(context);

    assertTrue("File should remain when under limit", file.exists());
  }

  @Test
  public void enforceMaxSize_overLimit_deletesOldestFirst() throws IOException {
    cacheDir.mkdirs();
    // Create files totaling > 100 MB
    File oldest = createFileWithSize(cacheDir, "oldest.bin", 40 * 1024 * 1024);
    oldest.setLastModified(System.currentTimeMillis() - 3000);

    File middle = createFileWithSize(cacheDir, "middle.bin", 40 * 1024 * 1024);
    middle.setLastModified(System.currentTimeMillis() - 2000);

    File newest = createFileWithSize(cacheDir, "newest.bin", 40 * 1024 * 1024);
    newest.setLastModified(System.currentTimeMillis() - 1000);

    // Total = 120 MB > 100 MB limit
    OpenFileCacheManager.enforceMaxSize(context);

    // Should delete oldest first until <= 50 MB target
    // After deleting oldest (40MB): 80MB > 50MB, delete middle (40MB): 40MB <= 50MB
    assertFalse("Oldest file should be deleted", oldest.exists());
    assertFalse("Middle file should be deleted", middle.exists());
    assertTrue("Newest file should remain", newest.exists());
  }

  @Test
  public void enforceMaxSize_exactlyAtLimit_noFilesDeleted() throws IOException {
    cacheDir.mkdirs();
    File file = createFileWithSize(cacheDir, "exact.bin", 100 * 1024 * 1024);

    OpenFileCacheManager.enforceMaxSize(context);

    assertTrue("File at exactly 100 MB should remain", file.exists());
  }

  @Test
  public void getCacheFile_samePathAndConnection_givesSameFile() {
    File a = OpenFileCacheManager.getCacheFile(context, "c1", "Docs/report.pdf", "report.pdf");
    File b = OpenFileCacheManager.getCacheFile(context, "c1", "Docs/report.pdf", "report.pdf");

    assertEquals(a.getAbsolutePath(), b.getAbsolutePath());
    assertEquals("report.pdf", a.getName());
    assertEquals(cacheDir.getAbsolutePath(), a.getParentFile().getParentFile().getAbsolutePath());
  }

  @Test
  public void getCacheFile_otherFolderOrConnection_givesOtherFile() {
    File docs = OpenFileCacheManager.getCacheFile(context, "c1", "Docs/report.pdf", "report.pdf");
    File other = OpenFileCacheManager.getCacheFile(context, "c1", "Other/report.pdf", "report.pdf");
    File otherConnection =
        OpenFileCacheManager.getCacheFile(context, "c2", "Docs/report.pdf", "report.pdf");

    assertNotEquals(docs.getAbsolutePath(), other.getAbsolutePath());
    assertNotEquals(docs.getAbsolutePath(), otherConnection.getAbsolutePath());
  }

  @Test
  public void cleanupOnAppStart_deletesOldFilesInEntryFolders() throws IOException {
    File old = OpenFileCacheManager.getCacheFile(context, "c1", "a/old.pdf", "old.pdf");
    createFileWithSize(old.getParentFile(), old.getName(), 1024);
    old.setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000);
    File recent = OpenFileCacheManager.getCacheFile(context, "c1", "a/new.pdf", "new.pdf");
    createFileWithSize(recent.getParentFile(), recent.getName(), 1024);

    OpenFileCacheManager.cleanupOnAppStart(context);

    assertFalse(old.exists());
    assertFalse("The empty entry folder is removed", old.getParentFile().exists());
    assertTrue(recent.exists());
  }

  @Test
  public void enforceMaxSize_countsFilesInEntryFolders() throws IOException {
    File oldest = OpenFileCacheManager.getCacheFile(context, "c1", "x/1.bin", "1.bin");
    createFileWithSize(oldest.getParentFile(), "1.bin", 60 * 1024 * 1024);
    oldest.setLastModified(System.currentTimeMillis() - 3000);
    File newest = OpenFileCacheManager.getCacheFile(context, "c1", "x/2.bin", "2.bin");
    createFileWithSize(newest.getParentFile(), "2.bin", 45 * 1024 * 1024);

    OpenFileCacheManager.enforceMaxSize(context);

    assertFalse(oldest.exists());
    assertTrue(newest.exists());
    assertEquals(1, OpenFileCacheManager.listCacheFiles(context).size());
  }

  @Test
  public void getCacheDir_createsDirectoryIfNotExists() {
    assertFalse(cacheDir.exists());

    File result = OpenFileCacheManager.getCacheDir(context);

    assertTrue("Cache dir should be created", result.exists());
    assertTrue("Should be a directory", result.isDirectory());
    assertEquals("open_files", result.getName());
  }

  @Test
  public void getCacheDir_returnsExistingDirectory() {
    cacheDir.mkdirs();
    assertTrue(cacheDir.exists());

    File result = OpenFileCacheManager.getCacheDir(context);

    assertTrue(result.exists());
    assertEquals(cacheDir.getAbsolutePath(), result.getAbsolutePath());
  }

  private File createFileWithSize(File dir, String name, int sizeBytes) throws IOException {
    File file = new File(dir, name);
    try (FileOutputStream fos = new FileOutputStream(file)) {
      byte[] buffer = new byte[Math.min(sizeBytes, 8192)];
      int remaining = sizeBytes;
      while (remaining > 0) {
        int toWrite = Math.min(remaining, buffer.length);
        fos.write(buffer, 0, toWrite);
        remaining -= toWrite;
      }
    }
    return file;
  }

  private void deleteRecursive(File file) {
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) {
        for (File child : children) {
          deleteRecursive(child);
        }
      }
    }
    file.delete();
  }
}
