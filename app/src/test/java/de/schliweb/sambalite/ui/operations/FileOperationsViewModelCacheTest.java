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
      deleteRecursive(cacheDir);
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
    File cachedFile = cacheFileFor("/share/test.txt", "test.txt");
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
    File cachedFile = cacheFileFor("/share/test.txt", "test.txt");
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
    File cachedFile = cacheFileFor("/share/test.txt", "test.txt");
    writeBytes(cachedFile, remoteSize);
    cachedFile.setLastModified(remoteDate.getTime() - 60000); // 1 minute older

    AtomicReference<String> error = new AtomicReference<>();

    viewModel.downloadToCache(file, f -> {}, error::set);
    ShadowLooper.idleMainLooper();

    // Verify that a download was attempted (cache was stale)
    verify(smbRepository, timeout(2000))
        .downloadFileWithProgress(eq(connection), eq("/share/test.txt"), any(File.class), any());
  }

  /**
   * A file with the same name, size and age in another folder must not be served from the cache
   * entry of the first one.
   */
  @Test
  public void downloadToCache_sameNameInOtherFolder_isNotACacheHit() throws Exception {
    long remoteSize = 612L;
    Date remoteDate = new Date(1000000L);
    File docsCopy = cacheFileFor("/share/Docs/report.pdf", "report.pdf");
    writeBytes(docsCopy, remoteSize);
    docsCopy.setLastModified(remoteDate.getTime() + 1000);

    SmbFileItem other =
        new SmbFileItem(
            "report.pdf", "/share/Other/report.pdf", SmbFileItem.Type.FILE, remoteSize, remoteDate);
    viewModel.downloadToCache(other, f -> {}, e -> {});
    ShadowLooper.idleMainLooper();

    org.mockito.ArgumentCaptor<File> target = org.mockito.ArgumentCaptor.forClass(File.class);
    verify(smbRepository, timeout(2000))
        .downloadFileWithProgress(
            eq(connection), eq("/share/Other/report.pdf"), target.capture(), any());
    assertEquals("report.pdf", target.getValue().getName());
    assertNotEquals(docsCopy.getAbsolutePath(), target.getValue().getAbsolutePath());
    assertTrue("The other folder's copy stays cached", docsCopy.exists());
  }

  /** The same path on another connection is another file. */
  @Test
  public void downloadToCache_samePathOnOtherConnection_isNotACacheHit() throws Exception {
    connection.setId("connection-a");
    File copyA = cacheFileFor("/share/report.pdf", "report.pdf");
    writeBytes(copyA, 612L);
    copyA.setLastModified(1000000L + 1000);

    SmbConnection other = new SmbConnection();
    other.setId("connection-b");
    other.setShare("share");
    when(state.getConnection()).thenReturn(other);

    SmbFileItem file =
        new SmbFileItem(
            "report.pdf", "/share/report.pdf", SmbFileItem.Type.FILE, 612L, new Date(1000000L));
    viewModel.downloadToCache(file, f -> {}, e -> {});
    ShadowLooper.idleMainLooper();

    verify(smbRepository, timeout(2000))
        .downloadFileWithProgress(eq(other), eq("/share/report.pdf"), any(File.class), any());
  }

  /**
   * The download keeps the remote time on the file (here one day old); the cache copy must still
   * survive the age-based cleanup that runs when the app returns to the foreground, and be a cache
   * hit when opened again.
   */
  @Test
  public void downloadToCache_oldRemoteFile_survivesCleanupAndHitsNextTime() throws Exception {
    long remoteSize = 300L;
    Date remoteDate = new Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000);
    SmbFileItem file =
        new SmbFileItem(
            "old.txt", "/share/Docs/old.txt", SmbFileItem.Type.FILE, remoteSize, remoteDate);
    doAnswer(
            invocation -> {
              File target = invocation.getArgument(2);
              writeBytes(target, remoteSize);
              target.setLastModified(remoteDate.getTime()); // like SmbRepositoryImpl
              return null;
            })
        .when(smbRepository)
        .downloadFileWithProgress(
            eq(connection), eq("/share/Docs/old.txt"), any(File.class), any());

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<File> result = new AtomicReference<>();
    viewModel.downloadToCache(
        file,
        f -> {
          result.set(f);
          latch.countDown();
        },
        e -> latch.countDown());
    latch.await(5, TimeUnit.SECONDS);
    ShadowLooper.idleMainLooper();
    assertNotNull(result.get());

    OpenFileCacheManager.cleanupOnAppStart(context);
    assertTrue("Fresh cache copy must survive the cleanup", result.get().exists());

    AtomicReference<File> second = new AtomicReference<>();
    viewModel.downloadToCache(file, second::set, e -> {});
    ShadowLooper.idleMainLooper();
    assertNotNull(second.get());
    verify(smbRepository, times(1))
        .downloadFileWithProgress(eq(connection), eq("/share/Docs/old.txt"), any(), any());
  }

  private File cacheFileFor(String remotePath, String name) {
    return OpenFileCacheManager.getCacheFile(
        context, FileOperationsViewModel.connectionCacheKey(connection), remotePath, name);
  }

  private static void deleteRecursive(File file) {
    File[] children = file.listFiles();
    if (children != null) {
      for (File child : children) {
        deleteRecursive(child);
      }
    }
    file.delete();
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
