package de.schliweb.sambalite.ui.operations;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.ui.FileBrowserState;
import de.schliweb.sambalite.ui.FileListViewModel;
import de.schliweb.sambalite.util.OpenFileCacheManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * Tests for the cache validation, integrity checks, and cancellation improvements in
 * FileOperationsViewModel (Phases 1, 2, 4 of the robustness improvement plan).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FileOperationsViewModelCacheTest {

  private Context context;
  @Mock private SmbRepository smbRepository;
  @Mock private FileBrowserState state;
  @Mock private FileListViewModel fileListViewModel;
  @Mock private BackgroundSmbManager backgroundSmbManager;

  private FileOperationsViewModel viewModel;
  private SmbConnection connection;
  private File cacheDir;
  private AutoCloseable mocks;

  @Before
  public void setUp() {
    mocks = MockitoAnnotations.openMocks(this);
    context = ApplicationProvider.getApplicationContext();
    connection = new SmbConnection();
    connection.setName("test");
    connection.setServer("192.168.1.1");
    connection.setShare("share");

    when(state.getConnection()).thenReturn(connection);
    when(state.isDownloadCancelled()).thenReturn(false);
    when(state.isUploadCancelled()).thenReturn(false);
    when(state.getCurrentPathString()).thenReturn("/share");

    viewModel =
        new FileOperationsViewModel(
            smbRepository, context, state, fileListViewModel, backgroundSmbManager);

    cacheDir = OpenFileCacheManager.getCacheDir(context);
    cacheDir.mkdirs();
  }

  @After
  public void tearDown() throws Exception {
    // Clean up cache files
    if (cacheDir != null && cacheDir.exists()) {
      File[] files = cacheDir.listFiles();
      if (files != null) {
        for (File f : files) {
          f.delete();
        }
      }
    }
    mocks.close();
  }

  // ========== Phase 1: Cache Validation Tests ==========

  /**
   * UT1.1 – Cache hit when local file matches remote size and timestamp. No download should be
   * triggered.
   */
  @Test
  public void downloadToCache_cacheHit_whenSizeAndTimestampMatch() throws Exception {
    long remoteSize = 1024L;
    Date remoteDate = new Date(1000000L);
    SmbFileItem file =
        new SmbFileItem(
            "test.txt", "/share/test.txt", SmbFileItem.Type.FILE, remoteSize, remoteDate);

    // Create a cached file with matching size and newer timestamp
    File cachedFile = new File(cacheDir, "test.txt");
    writeBytes(cachedFile, remoteSize);
    cachedFile.setLastModified(remoteDate.getTime() + 1000);

    AtomicReference<File> result = new AtomicReference<>();
    AtomicReference<String> error = new AtomicReference<>();

    viewModel.downloadToCache(file, result::set, error::set);
    ShadowLooper.idleMainLooper();

    assertNotNull("onSuccess should be called", result.get());
    assertNull("onError should not be called", error.get());
    assertEquals(cachedFile.getAbsolutePath(), result.get().getAbsolutePath());

    // Verify no download was triggered
    verify(smbRepository, never())
        .downloadFileWithProgress(any(), anyString(), any(File.class), any());
  }

  /** UT1.2 – Cache miss when local file size differs from remote. Download should be triggered. */
  @Test
  public void downloadToCache_cacheMiss_whenSizeDiffers() throws Exception {
    long remoteSize = 2048L;
    Date remoteDate = new Date(1000000L);
    SmbFileItem file =
        new SmbFileItem(
            "test.txt", "/share/test.txt", SmbFileItem.Type.FILE, remoteSize, remoteDate);

    // Create a cached file with WRONG size
    File cachedFile = new File(cacheDir, "test.txt");
    writeBytes(cachedFile, 512L); // different size
    cachedFile.setLastModified(remoteDate.getTime() + 1000);

    AtomicReference<String> error = new AtomicReference<>();

    viewModel.downloadToCache(file, f -> {}, error::set);
    ShadowLooper.idleMainLooper();

    // The stale cached file should have been deleted before re-download
    // Verify that a download was attempted (the file was not served from cache)
    verify(smbRepository, timeout(2000))
        .downloadFileWithProgress(eq(connection), eq("/share/test.txt"), any(File.class), any());
  }

  /**
   * UT1.3 – Cache miss when local file timestamp is older than remote. Download should be
   * triggered.
   */
  @Test
  public void downloadToCache_cacheMiss_whenTimestampStale() throws Exception {
    long remoteSize = 1024L;
    Date remoteDate = new Date(System.currentTimeMillis()); // now
    SmbFileItem file =
        new SmbFileItem(
            "test.txt", "/share/test.txt", SmbFileItem.Type.FILE, remoteSize, remoteDate);

    // Create a cached file with matching size but OLDER timestamp
    File cachedFile = new File(cacheDir, "test.txt");
    writeBytes(cachedFile, remoteSize);
    cachedFile.setLastModified(remoteDate.getTime() - 60000); // 1 minute older

    AtomicReference<String> error = new AtomicReference<>();

    viewModel.downloadToCache(file, f -> {}, error::set);
    ShadowLooper.idleMainLooper();

    // Verify that a download was attempted (cache was stale)
    verify(smbRepository, timeout(2000))
        .downloadFileWithProgress(eq(connection), eq("/share/test.txt"), any(File.class), any());
  }

  // ========== Phase 2: Download Integrity Tests ==========

  /** UT2.1 – Download integrity check passes when file size matches remote size. */
  @Test
  public void downloadToCache_integrityPass_whenSizeMatches() throws Exception {
    long remoteSize = 500L;
    SmbFileItem file =
        new SmbFileItem("doc.pdf", "/share/doc.pdf", SmbFileItem.Type.FILE, remoteSize, new Date());

    // Mock the download to create a file with the correct size
    doAnswer(
            invocation -> {
              File target = invocation.getArgument(2);
              writeBytes(target, remoteSize);
              return null;
            })
        .when(smbRepository)
        .downloadFileWithProgress(eq(connection), eq("/share/doc.pdf"), any(File.class), any());

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<File> result = new AtomicReference<>();
    AtomicReference<String> error = new AtomicReference<>();

    viewModel.downloadToCache(
        file,
        f -> {
          result.set(f);
          latch.countDown();
        },
        e -> {
          error.set(e);
          latch.countDown();
        });

    latch.await(5, TimeUnit.SECONDS);
    ShadowLooper.idleMainLooper();

    assertNotNull("onSuccess should be called", result.get());
    assertNull("onError should not be called", error.get());
  }

  /**
   * UT2.2 – Download integrity check fails when file size does not match remote size. File should
   * be deleted and error reported.
   */
  @Test
  public void downloadToCache_integrityFail_whenSizeMismatch() throws Exception {
    long remoteSize = 1000L;
    SmbFileItem file =
        new SmbFileItem(
            "data.bin", "/share/data.bin", SmbFileItem.Type.FILE, remoteSize, new Date());

    // Mock the download to create a file with WRONG size (incomplete download)
    doAnswer(
            invocation -> {
              File target = invocation.getArgument(2);
              writeBytes(target, 500L); // only half downloaded
              return null;
            })
        .when(smbRepository)
        .downloadFileWithProgress(eq(connection), eq("/share/data.bin"), any(File.class), any());

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<File> result = new AtomicReference<>();
    AtomicReference<String> error = new AtomicReference<>();

    viewModel.downloadToCache(
        file,
        f -> {
          result.set(f);
          latch.countDown();
        },
        e -> {
          error.set(e);
          latch.countDown();
        });

    latch.await(5, TimeUnit.SECONDS);
    ShadowLooper.idleMainLooper();

    assertNull("onSuccess should not be called", result.get());
    assertNotNull("onError should be called", error.get());
    assertTrue(
        "Error should mention incomplete download",
        error.get().contains("Download incomplete") || error.get().contains("expected"));
  }

  // Legacy upload integrity tests removed — upload now handled by TransferWorker

  // ========== Phase 4: Cancellation Tests ==========

  /** UT4.1 – cancelDownload sets the cancellation flag correctly. */
  @Test
  public void cancelDownload_setsCancellationFlag() {
    viewModel.cancelDownload();

    verify(state).setDownloadCancelled(true);
    verify(smbRepository).cancelDownload();
  }

  /** UT4.2 – After cancelDownload, isDownloadCancelled returns true. */
  @Test
  public void isDownloadCancelled_returnsTrueAfterCancel() {
    when(state.isDownloadCancelled()).thenReturn(true);

    assertTrue("isDownloadCancelled should return true", viewModel.isDownloadCancelled());
  }

  // Legacy getRemoteFileSize upload tests removed — upload integrity now handled by TransferWorker

  // ========== Helper Methods ==========

  private void writeBytes(File file, long size) throws IOException {
    file.getParentFile().mkdirs();
    try (FileOutputStream fos = new FileOutputStream(file)) {
      byte[] buffer = new byte[1024];
      long remaining = size;
      while (remaining > 0) {
        int toWrite = (int) Math.min(buffer.length, remaining);
        fos.write(buffer, 0, toWrite);
        remaining -= toWrite;
      }
    }
  }
}
