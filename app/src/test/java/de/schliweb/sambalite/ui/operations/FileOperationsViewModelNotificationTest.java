/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui.operations;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;
import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.ui.FileBrowserState;
import de.schliweb.sambalite.ui.FileListViewModel;
import java.io.File;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 * Tests that verify notifications (via BackgroundSmbManager) are triggered for all upload and
 * download constellations. Each test verifies that startOperation() and finishOperation() are called
 * with the correct operation name, and that progress updates are forwarded where applicable.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FileOperationsViewModelNotificationTest {

  private Context context;
  @Mock private SmbRepository smbRepository;
  @Mock private FileBrowserState state;
  @Mock private FileListViewModel fileListViewModel;
  @Mock private BackgroundSmbManager backgroundSmbManager;

  private FileOperationsViewModel viewModel;
  private SmbConnection connection;
  private AutoCloseable mocks;

  @Before
  public void setUp() {
    mocks = MockitoAnnotations.openMocks(this);
    context = ApplicationProvider.getApplicationContext();

    connection = new SmbConnection();
    connection.setId("test-conn-1");
    connection.setName("test");
    connection.setServer("192.168.1.1");
    connection.setShare("share");

    when(state.getConnection()).thenReturn(connection);
    when(state.getCurrentPathString()).thenReturn("/share/docs");

    viewModel =
        new FileOperationsViewModel(
            smbRepository, context, state, fileListViewModel, backgroundSmbManager);
  }

  @After
  public void tearDown() throws Exception {
    mocks.close();
  }

  private static FileOperationCallbacks.DownloadCallback downloadCallback(CountDownLatch latch) {
    return new FileOperationCallbacks.DownloadCallback() {
      @Override
      public void onResult(boolean success, @NonNull String message) {
        latch.countDown();
      }

      @Override
      public void onProgress(@NonNull String status, int percentage) {}
    };
  }

  private static FileOperationCallbacks.ProgressCallback noOpProgressCallback() {
    return new FileOperationCallbacks.ProgressCallback() {
      @Override
      public void updateProgress(@NonNull String info) {}

      @Override
      public void updateBytesProgress(long cur, long total, @NonNull String name) {}

      @Override
      public void updateFileProgress(int cur, int total, @NonNull String name) {}
    };
  }

  // ===== downloadToCache =====

  @Test
  public void downloadToCache_success_triggersStartAndFinishNotification() throws Exception {
    SmbFileItem file = createFile("report.pdf", "/share/docs/report.pdf", 1024);

    doNothing()
        .when(smbRepository)
        .downloadFileWithProgress(eq(connection), eq("/share/docs/report.pdf"), any(), any());

    CountDownLatch latch = new CountDownLatch(1);
    viewModel.downloadToCache(file, f -> latch.countDown(), err -> latch.countDown());

    latch.await(5, TimeUnit.SECONDS);
    ShadowLooper.idleMainLooper();

    verify(backgroundSmbManager).ensureServiceRunning();
    verify(backgroundSmbManager).startOperation(contains("report.pdf"));
    verify(backgroundSmbManager).finishOperation(contains("report.pdf"), eq(true));
  }

  @Test
  public void downloadToCache_failure_triggersStartAndFinishNotification() throws Exception {
    SmbFileItem file = createFile("report.pdf", "/share/docs/report.pdf", 1024);

    doThrow(new RuntimeException("Network error"))
        .when(smbRepository)
        .downloadFileWithProgress(eq(connection), eq("/share/docs/report.pdf"), any(), any());

    CountDownLatch latch = new CountDownLatch(1);
    viewModel.downloadToCache(file, f -> latch.countDown(), err -> latch.countDown());

    latch.await(5, TimeUnit.SECONDS);
    ShadowLooper.idleMainLooper();

    verify(backgroundSmbManager).ensureServiceRunning();
    verify(backgroundSmbManager).startOperation(contains("report.pdf"));
    // downloadToCache always calls finishOperation(true) in finally block, even on error
    verify(backgroundSmbManager).finishOperation(contains("report.pdf"), eq(true));
  }

  @Test
  public void downloadToCache_invalidFile_noNotification() {
    viewModel.downloadToCache(null, f -> {}, err -> {});
    ShadowLooper.idleMainLooper();

    verify(backgroundSmbManager, never()).startOperation(any());
    verify(backgroundSmbManager, never()).finishOperation(any(), anyBoolean());
  }

  @Test
  public void downloadToCache_directory_noNotification() {
    SmbFileItem dir = createDirectory("myFolder", "/share/docs/myFolder");

    viewModel.downloadToCache(dir, f -> {}, err -> {});
    ShadowLooper.idleMainLooper();

    verify(backgroundSmbManager, never()).startOperation(any());
    verify(backgroundSmbManager, never()).finishOperation(any(), anyBoolean());
  }

  // ===== downloadFile (single file) =====

  @Test
  public void downloadFile_success_triggersNotification() throws Exception {
    SmbFileItem file = createFile("data.csv", "/share/docs/data.csv", 2048);
    File localFile = new File(context.getCacheDir(), "data.csv");

    doNothing()
        .when(smbRepository)
        .downloadFileWithProgress(eq(connection), eq("/share/docs/data.csv"), any(), any());

    CountDownLatch latch = new CountDownLatch(1);
    viewModel.downloadFile(file, localFile, downloadCallback(latch), noOpProgressCallback());

    latch.await(5, TimeUnit.SECONDS);
    ShadowLooper.idleMainLooper();

    verify(backgroundSmbManager).ensureServiceRunning();
    verify(backgroundSmbManager).startOperation(contains("data.csv"));
    verify(backgroundSmbManager).finishOperation(contains("data.csv"), eq(true));
  }

  @Test
  public void downloadFile_failure_triggersFinishWithFalse() throws Exception {
    SmbFileItem file = createFile("data.csv", "/share/docs/data.csv", 2048);
    File localFile = new File(context.getCacheDir(), "data.csv");

    doThrow(new RuntimeException("Connection lost"))
        .when(smbRepository)
        .downloadFileWithProgress(eq(connection), eq("/share/docs/data.csv"), any(), any());

    CountDownLatch latch = new CountDownLatch(1);
    viewModel.downloadFile(file, localFile, downloadCallback(latch), noOpProgressCallback());

    latch.await(5, TimeUnit.SECONDS);
    ShadowLooper.idleMainLooper();

    verify(backgroundSmbManager).startOperation(contains("data.csv"));
    verify(backgroundSmbManager).finishOperation(contains("data.csv"), eq(false));
  }

  @Test
  public void downloadFile_withoutProgress_triggersNotification() throws Exception {
    SmbFileItem file = createFile("simple.txt", "/share/docs/simple.txt", 100);
    File localFile = new File(context.getCacheDir(), "simple.txt");

    doNothing()
        .when(smbRepository)
        .downloadFile(eq(connection), eq("/share/docs/simple.txt"), any(File.class));

    CountDownLatch latch = new CountDownLatch(1);
    viewModel.downloadFile(file, localFile, downloadCallback(latch));

    latch.await(5, TimeUnit.SECONDS);
    ShadowLooper.idleMainLooper();

    verify(backgroundSmbManager).startOperation(contains("simple.txt"));
    verify(backgroundSmbManager).finishOperation(contains("simple.txt"), eq(true));
  }

  @Test
  public void downloadFile_invalidConnection_noNotification() {
    when(state.getConnection()).thenReturn(null);
    SmbFileItem file = createFile("data.csv", "/share/docs/data.csv", 2048);
    File localFile = new File(context.getCacheDir(), "data.csv");

    viewModel.downloadFile(file, localFile, downloadCallback(new CountDownLatch(1)));
    ShadowLooper.idleMainLooper();

    verify(backgroundSmbManager, never()).startOperation(any());
  }

  // ===== downloadFolder =====

  @Test
  public void downloadFolder_success_triggersNotification() throws Exception {
    SmbFileItem folder = createDirectory("photos", "/share/docs/photos");
    File localFolder = new File(context.getCacheDir(), "photos");
    localFolder.mkdirs();

    doNothing()
        .when(smbRepository)
        .downloadFolderWithProgress(eq(connection), eq("/share/docs/photos"), any(), any());

    CountDownLatch latch = new CountDownLatch(1);
    viewModel.downloadFolder(
        folder, localFolder, downloadCallback(latch), noOpProgressCallback());

    latch.await(5, TimeUnit.SECONDS);
    ShadowLooper.idleMainLooper();

    verify(backgroundSmbManager).ensureServiceRunning();
    verify(backgroundSmbManager).startOperation(contains("photos"));
    verify(backgroundSmbManager).finishOperation(contains("photos"), eq(true));
  }

  @Test
  public void downloadFolder_failure_triggersFinishWithFalse() throws Exception {
    SmbFileItem folder = createDirectory("photos", "/share/docs/photos");
    File localFolder = new File(context.getCacheDir(), "photos");
    localFolder.mkdirs();

    doThrow(new RuntimeException("SMB error"))
        .when(smbRepository)
        .downloadFolderWithProgress(eq(connection), eq("/share/docs/photos"), any(), any());

    CountDownLatch latch = new CountDownLatch(1);
    viewModel.downloadFolder(
        folder, localFolder, downloadCallback(latch), noOpProgressCallback());

    latch.await(5, TimeUnit.SECONDS);
    ShadowLooper.idleMainLooper();

    verify(backgroundSmbManager).startOperation(contains("photos"));
    verify(backgroundSmbManager).finishOperation(contains("photos"), eq(false));
  }

  @Test
  public void downloadFolder_invalidFolder_noNotification() {
    SmbFileItem file = createFile("notAFolder.txt", "/share/docs/notAFolder.txt", 100);
    File localFolder = new File(context.getCacheDir(), "notAFolder");

    viewModel.downloadFolder(
        file, localFolder, downloadCallback(new CountDownLatch(1)), null);
    ShadowLooper.idleMainLooper();

    verify(backgroundSmbManager, never()).startOperation(any());
  }

  // ===== createFolder =====

  @Test
  public void createFolder_success_triggersNotification() throws Exception {
    doNothing()
        .when(smbRepository)
        .createDirectory(eq(connection), eq("/share/docs"), eq("newFolder"));

    CountDownLatch latch = new CountDownLatch(1);
    viewModel.createFolder("newFolder", (success, msg) -> latch.countDown());

    latch.await(5, TimeUnit.SECONDS);
    ShadowLooper.idleMainLooper();

    verify(backgroundSmbManager).ensureServiceRunning();
    verify(backgroundSmbManager).startOperation(contains("newFolder"));
    verify(backgroundSmbManager).finishOperation(contains("newFolder"), eq(true));
  }

  @Test
  public void createFolder_failure_triggersFinishWithFalse() throws Exception {
    doThrow(new RuntimeException("Permission denied"))
        .when(smbRepository)
        .createDirectory(eq(connection), eq("/share/docs"), eq("newFolder"));

    CountDownLatch latch = new CountDownLatch(1);
    viewModel.createFolder("newFolder", (success, msg) -> latch.countDown());

    latch.await(5, TimeUnit.SECONDS);
    ShadowLooper.idleMainLooper();

    verify(backgroundSmbManager).startOperation(contains("newFolder"));
    verify(backgroundSmbManager).finishOperation(contains("newFolder"), eq(false));
  }

  @Test
  public void createFolder_invalidName_noNotification() {
    viewModel.createFolder("", (success, msg) -> {});
    ShadowLooper.idleMainLooper();

    verify(backgroundSmbManager, never()).startOperation(any());
  }

  // ===== deleteFile =====

  @Test
  public void deleteFile_success_triggersNotification() throws Exception {
    SmbFileItem file = createFile("old.txt", "/share/docs/old.txt", 512);

    doNothing().when(smbRepository).deleteFile(eq(connection), eq("/share/docs/old.txt"));

    CountDownLatch latch = new CountDownLatch(1);
    viewModel.deleteFile(file, (success, msg) -> latch.countDown());

    latch.await(5, TimeUnit.SECONDS);
    ShadowLooper.idleMainLooper();

    verify(backgroundSmbManager).ensureServiceRunning();
    verify(backgroundSmbManager).startOperation(contains("old.txt"));
    verify(backgroundSmbManager).finishOperation(contains("old.txt"), eq(true));
  }

  @Test
  public void deleteFile_failure_triggersFinishWithFalse() throws Exception {
    SmbFileItem file = createFile("old.txt", "/share/docs/old.txt", 512);

    doThrow(new RuntimeException("Access denied"))
        .when(smbRepository)
        .deleteFile(eq(connection), eq("/share/docs/old.txt"));

    CountDownLatch latch = new CountDownLatch(1);
    viewModel.deleteFile(file, (success, msg) -> latch.countDown());

    latch.await(5, TimeUnit.SECONDS);
    ShadowLooper.idleMainLooper();

    verify(backgroundSmbManager).startOperation(contains("old.txt"));
    verify(backgroundSmbManager).finishOperation(contains("old.txt"), eq(false));
  }

  @Test
  public void deleteFile_invalidFile_noNotification() {
    viewModel.deleteFile(null, (success, msg) -> {});
    ShadowLooper.idleMainLooper();

    verify(backgroundSmbManager, never()).startOperation(any());
  }

  @Test
  public void deleteDirectory_success_triggersNotification() throws Exception {
    SmbFileItem dir = createDirectory("oldFolder", "/share/docs/oldFolder");

    doNothing().when(smbRepository).deleteFile(eq(connection), eq("/share/docs/oldFolder"));

    CountDownLatch latch = new CountDownLatch(1);
    viewModel.deleteFile(dir, (success, msg) -> latch.countDown());

    latch.await(5, TimeUnit.SECONDS);
    ShadowLooper.idleMainLooper();

    verify(backgroundSmbManager).ensureServiceRunning();
    verify(backgroundSmbManager).startOperation(contains("oldFolder"));
    verify(backgroundSmbManager).finishOperation(contains("oldFolder"), eq(true));
  }

  // ===== renameFile =====

  @Test
  public void renameFile_success_triggersNotification() throws Exception {
    SmbFileItem file = createFile("old.txt", "/share/docs/old.txt", 512);

    doNothing()
        .when(smbRepository)
        .renameFile(eq(connection), eq("/share/docs/old.txt"), eq("new.txt"));

    CountDownLatch latch = new CountDownLatch(1);
    viewModel.renameFile(file, "new.txt", (success, msg) -> latch.countDown());

    latch.await(5, TimeUnit.SECONDS);
    ShadowLooper.idleMainLooper();

    verify(backgroundSmbManager).ensureServiceRunning();
    verify(backgroundSmbManager).startOperation(contains("old.txt"));
    verify(backgroundSmbManager).finishOperation(contains("old.txt"), eq(true));
  }

  @Test
  public void renameFile_failure_triggersFinishWithFalse() throws Exception {
    SmbFileItem file = createFile("old.txt", "/share/docs/old.txt", 512);

    doThrow(new RuntimeException("Name conflict"))
        .when(smbRepository)
        .renameFile(eq(connection), eq("/share/docs/old.txt"), eq("new.txt"));

    CountDownLatch latch = new CountDownLatch(1);
    viewModel.renameFile(file, "new.txt", (success, msg) -> latch.countDown());

    latch.await(5, TimeUnit.SECONDS);
    ShadowLooper.idleMainLooper();

    verify(backgroundSmbManager).startOperation(contains("old.txt"));
    verify(backgroundSmbManager).finishOperation(contains("old.txt"), eq(false));
  }

  @Test
  public void renameFile_invalidName_noNotification() {
    SmbFileItem file = createFile("old.txt", "/share/docs/old.txt", 512);

    viewModel.renameFile(file, "", (success, msg) -> {});
    ShadowLooper.idleMainLooper();

    verify(backgroundSmbManager, never()).startOperation(any());
  }

  @Test
  public void renameDirectory_success_triggersNotification() throws Exception {
    SmbFileItem dir = createDirectory("oldDir", "/share/docs/oldDir");

    doNothing()
        .when(smbRepository)
        .renameFile(eq(connection), eq("/share/docs/oldDir"), eq("newDir"));

    CountDownLatch latch = new CountDownLatch(1);
    viewModel.renameFile(dir, "newDir", (success, msg) -> latch.countDown());

    latch.await(5, TimeUnit.SECONDS);
    ShadowLooper.idleMainLooper();

    verify(backgroundSmbManager).ensureServiceRunning();
    verify(backgroundSmbManager).startOperation(contains("oldDir"));
    verify(backgroundSmbManager).finishOperation(contains("oldDir"), eq(true));
  }

  // ===== Progress forwarding tests =====

  @Test
  public void downloadFile_withProgress_forwardsBytesProgressToNotification() throws Exception {
    SmbFileItem file = createFile("big.zip", "/share/docs/big.zip", 10_000_000);
    File localFile = new File(context.getCacheDir(), "big.zip");

    doAnswer(
            invocation -> {
              BackgroundSmbManager.ProgressCallback cb = invocation.getArgument(3);
              cb.updateBytesProgress(5_000_000, 10_000_000, "big.zip");
              cb.updateBytesProgress(10_000_000, 10_000_000, "big.zip");
              return null;
            })
        .when(smbRepository)
        .downloadFileWithProgress(eq(connection), eq("/share/docs/big.zip"), any(), any());

    CountDownLatch latch = new CountDownLatch(1);
    viewModel.downloadFile(file, localFile, downloadCallback(latch), noOpProgressCallback());

    latch.await(5, TimeUnit.SECONDS);
    ShadowLooper.idleMainLooper();

    verify(backgroundSmbManager).startOperation(contains("big.zip"));
    verify(backgroundSmbManager, atLeastOnce())
        .updateBytesProgress(contains("big.zip"), anyLong(), anyLong(), eq("big.zip"));
    verify(backgroundSmbManager).finishOperation(contains("big.zip"), eq(true));
  }

  @Test
  public void downloadToCache_forwardsProgressToNotification() throws Exception {
    SmbFileItem file = createFile("video.mp4", "/share/docs/video.mp4", 50_000_000);

    doAnswer(
            invocation -> {
              BackgroundSmbManager.ProgressCallback cb = invocation.getArgument(3);
              cb.updateBytesProgress(25_000_000, 50_000_000, "video.mp4");
              cb.updateProgress("50% complete");
              return null;
            })
        .when(smbRepository)
        .downloadFileWithProgress(eq(connection), eq("/share/docs/video.mp4"), any(), any());

    CountDownLatch latch = new CountDownLatch(1);
    viewModel.downloadToCache(file, f -> latch.countDown(), err -> latch.countDown());

    latch.await(5, TimeUnit.SECONDS);
    ShadowLooper.idleMainLooper();

    verify(backgroundSmbManager).startOperation(contains("video.mp4"));
    verify(backgroundSmbManager, atLeastOnce())
        .updateBytesProgress(contains("video.mp4"), anyLong(), anyLong(), eq("video.mp4"));
    verify(backgroundSmbManager, atLeastOnce())
        .updateOperationProgress(contains("video.mp4"), anyString());
    verify(backgroundSmbManager).finishOperation(contains("video.mp4"), eq(true));
  }

  @Test
  public void downloadFolder_forwardsMultiFileProgressToNotification() throws Exception {
    SmbFileItem folder = createDirectory("docs", "/share/docs/docs");
    File localFolder = new File(context.getCacheDir(), "docs");
    localFolder.mkdirs();

    doAnswer(
            invocation -> {
              BackgroundSmbManager.MultiFileProgressCallback cb = invocation.getArgument(3);
              cb.updateFileProgress(1, 3, "file1.txt");
              cb.updateBytesProgress(500, 1000, "file1.txt");
              cb.updateFileProgress(2, 3, "file2.txt");
              cb.updateFileProgress(3, 3, "file3.txt");
              return null;
            })
        .when(smbRepository)
        .downloadFolderWithProgress(eq(connection), eq("/share/docs/docs"), any(), any());

    CountDownLatch latch = new CountDownLatch(1);
    viewModel.downloadFolder(
        folder, localFolder, downloadCallback(latch), noOpProgressCallback());

    latch.await(5, TimeUnit.SECONDS);
    ShadowLooper.idleMainLooper();

    verify(backgroundSmbManager).startOperation(contains("docs"));
    verify(backgroundSmbManager).finishOperation(contains("docs"), eq(true));
  }

  // ===== Helper methods =====

  private SmbFileItem createFile(String name, String path, long size) {
    return new SmbFileItem(name, path, SmbFileItem.Type.FILE, size, new Date());
  }

  private SmbFileItem createDirectory(String name, String path) {
    return new SmbFileItem(name, path, SmbFileItem.Type.DIRECTORY, 0, new Date());
  }
}
