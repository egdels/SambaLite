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

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import androidx.documentfile.provider.DocumentFile;
import androidx.test.core.app.ApplicationProvider;
import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.ui.FileBrowserState;
import de.schliweb.sambalite.ui.FileListViewModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests for directory support in multi-file download (getRelativeDownloadPath, flattenDirectory). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FileOperationsViewModelDirectoryDownloadTest {

  private Context context;
  @Mock private SmbRepository smbRepository;
  @Mock private FileBrowserState state;
  @Mock private FileListViewModel fileListViewModel;
  @Mock private BackgroundSmbManager backgroundSmbManager;
  @Mock private DocumentFile destDir;

  private FileOperationsViewModel viewModel;
  private SmbConnection connection;

  @Before
  public void setUp() {
    MockitoAnnotations.openMocks(this);
    context = ApplicationProvider.getApplicationContext();

    connection = new SmbConnection();
    connection.setId("test-conn-1");
    connection.setServer("192.168.1.1");
    connection.setShare("share");

    when(state.getConnection()).thenReturn(connection);

    viewModel =
        new FileOperationsViewModel(
            smbRepository, context, state, fileListViewModel, backgroundSmbManager);
  }

  @Test
  public void getRelativeDownloadPath_returnsNull_forDirectlySelectedFile() {
    SmbFileItem file =
        new SmbFileItem("test.txt", "/share/test.txt", SmbFileItem.Type.FILE, 100, new Date());
    List<SmbFileItem> selection = Collections.singletonList(file);

    String result = viewModel.getRelativeDownloadPath(file, selection, destDir);

    assertNull("Directly selected file should have no relative path", result);
  }

  @Test
  public void getRelativeDownloadPath_returnsDirectoryName_forFileInSelectedDirectory() {
    SmbFileItem dir =
        new SmbFileItem("Photos", "/share/Photos", SmbFileItem.Type.DIRECTORY, 0, new Date());
    SmbFileItem fileInDir =
        new SmbFileItem(
            "photo.jpg", "/share/Photos/photo.jpg", SmbFileItem.Type.FILE, 5000, new Date());
    List<SmbFileItem> selection = Collections.singletonList(dir);

    String result = viewModel.getRelativeDownloadPath(fileInDir, selection, destDir);

    assertEquals("Photos", result);
  }

  @Test
  public void getRelativeDownloadPath_returnsNestedPath_forFileInSubdirectory() {
    SmbFileItem dir =
        new SmbFileItem("Photos", "/share/Photos", SmbFileItem.Type.DIRECTORY, 0, new Date());
    SmbFileItem fileInSubDir =
        new SmbFileItem(
            "img.png",
            "/share/Photos/2024/January/img.png",
            SmbFileItem.Type.FILE,
            3000,
            new Date());
    List<SmbFileItem> selection = Collections.singletonList(dir);

    String result = viewModel.getRelativeDownloadPath(fileInSubDir, selection, destDir);

    assertEquals("Photos/2024/January", result);
  }

  @Test
  public void getRelativeDownloadPath_returnsNull_forFileNotInAnySelectedDirectory() {
    SmbFileItem dir =
        new SmbFileItem("Photos", "/share/Photos", SmbFileItem.Type.DIRECTORY, 0, new Date());
    SmbFileItem otherFile =
        new SmbFileItem(
            "readme.txt", "/share/Documents/readme.txt", SmbFileItem.Type.FILE, 100, new Date());
    List<SmbFileItem> selection = Collections.singletonList(dir);

    String result = viewModel.getRelativeDownloadPath(otherFile, selection, destDir);

    assertNull(result);
  }

  @Test
  public void getRelativeDownloadPath_ignoresFileItemsInSelection() {
    SmbFileItem selectedFile =
        new SmbFileItem("test.txt", "/share/test.txt", SmbFileItem.Type.FILE, 100, new Date());
    SmbFileItem dir =
        new SmbFileItem("Photos", "/share/Photos", SmbFileItem.Type.DIRECTORY, 0, new Date());
    SmbFileItem fileInDir =
        new SmbFileItem(
            "photo.jpg", "/share/Photos/photo.jpg", SmbFileItem.Type.FILE, 5000, new Date());
    List<SmbFileItem> selection = Arrays.asList(selectedFile, dir);

    String result = viewModel.getRelativeDownloadPath(fileInDir, selection, destDir);

    assertEquals("Photos", result);
  }

  @Test
  public void flattenDirectoryForDownload_collectsFilesRecursively() throws Exception {
    SmbFileItem dir =
        new SmbFileItem("Photos", "/share/Photos", SmbFileItem.Type.DIRECTORY, 0, new Date());
    SmbFileItem file1 =
        new SmbFileItem("a.jpg", "/share/Photos/a.jpg", SmbFileItem.Type.FILE, 100, new Date());
    SmbFileItem subDir =
        new SmbFileItem(
            "Sub", "/share/Photos/Sub", SmbFileItem.Type.DIRECTORY, 0, new Date());
    SmbFileItem file2 =
        new SmbFileItem(
            "b.jpg", "/share/Photos/Sub/b.jpg", SmbFileItem.Type.FILE, 200, new Date());

    when(smbRepository.listFiles(connection, "/share/Photos"))
        .thenReturn(Arrays.asList(file1, subDir));
    when(smbRepository.listFiles(connection, "/share/Photos/Sub"))
        .thenReturn(Collections.singletonList(file2));

    List<SmbFileItem> result = new ArrayList<>();
    viewModel.flattenDirectoryForDownload(dir, destDir, result);

    assertEquals(2, result.size());
    assertEquals("a.jpg", result.get(0).getName());
    assertEquals("b.jpg", result.get(1).getName());
  }

  @Test
  public void flattenDirectoryForDownload_handlesEmptyDirectory() throws Exception {
    SmbFileItem dir =
        new SmbFileItem("Empty", "/share/Empty", SmbFileItem.Type.DIRECTORY, 0, new Date());

    when(smbRepository.listFiles(connection, "/share/Empty"))
        .thenReturn(Collections.emptyList());

    List<SmbFileItem> result = new ArrayList<>();
    viewModel.flattenDirectoryForDownload(dir, destDir, result);

    assertTrue(result.isEmpty());
  }

  @Test
  public void flattenDirectoryForDownload_handlesListException() throws Exception {
    SmbFileItem dir =
        new SmbFileItem("Broken", "/share/Broken", SmbFileItem.Type.DIRECTORY, 0, new Date());

    when(smbRepository.listFiles(connection, "/share/Broken"))
        .thenThrow(new RuntimeException("Connection lost"));

    List<SmbFileItem> result = new ArrayList<>();
    viewModel.flattenDirectoryForDownload(dir, destDir, result);

    assertTrue("Should return empty list on error", result.isEmpty());
  }

  @Test
  public void flattenDirectoryForDownload_skipsNullChildren() throws Exception {
    SmbFileItem dir =
        new SmbFileItem("Mixed", "/share/Mixed", SmbFileItem.Type.DIRECTORY, 0, new Date());
    SmbFileItem file1 =
        new SmbFileItem("ok.txt", "/share/Mixed/ok.txt", SmbFileItem.Type.FILE, 50, new Date());

    List<SmbFileItem> children = new ArrayList<>();
    children.add(null);
    children.add(file1);
    when(smbRepository.listFiles(connection, "/share/Mixed")).thenReturn(children);

    List<SmbFileItem> result = new ArrayList<>();
    viewModel.flattenDirectoryForDownload(dir, destDir, result);

    assertEquals(1, result.size());
    assertEquals("ok.txt", result.get(0).getName());
  }
}
