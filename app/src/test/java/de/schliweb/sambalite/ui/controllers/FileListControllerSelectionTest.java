/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui.controllers;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import androidx.appcompat.app.AppCompatActivity;
import android.view.View;
import android.widget.TextView;
import androidx.lifecycle.MutableLiveData;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.ui.FileListViewModel;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests for multi-select directory support in FileListController. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FileListControllerSelectionTest {

  private FileListController controller;
  @Mock private FileListViewModel viewModel;
  private MutableLiveData<List<SmbFileItem>> filesLiveData;

  private SmbFileItem fileItem;
  private SmbFileItem dirItem;
  private SmbFileItem fileItem2;

  @Before
  public void setUp() {
    MockitoAnnotations.openMocks(this);

    // Stub LiveData returned by the ViewModel so observeViewModel() doesn't NPE
    filesLiveData = new MutableLiveData<>(Collections.emptyList());
    when(viewModel.getFiles()).thenReturn(filesLiveData);
    when(viewModel.getCurrentPath()).thenReturn(new MutableLiveData<>(""));
    when(viewModel.isLoading()).thenReturn(new MutableLiveData<>(false));
    when(viewModel.getErrorMessage()).thenReturn(new MutableLiveData<>(null));

    AppCompatActivity activity =
        Robolectric.buildActivity(AppCompatActivity.class).create().start().resume().get();

    RecyclerView recyclerView = new RecyclerView(activity);
    SwipeRefreshLayout swipeRefresh = new SwipeRefreshLayout(activity);
    View emptyView = new View(activity);
    TextView pathView = new TextView(activity);
    FileBrowserUIState uiState = new FileBrowserUIState();

    controller =
        new FileListController(recyclerView, swipeRefresh, emptyView, pathView, viewModel, uiState);

    fileItem =
        new SmbFileItem("test.txt", "/share/test.txt", SmbFileItem.Type.FILE, 1024, new Date());
    dirItem =
        new SmbFileItem("Photos", "/share/Photos", SmbFileItem.Type.DIRECTORY, 0, new Date());
    fileItem2 =
        new SmbFileItem("doc.pdf", "/share/doc.pdf", SmbFileItem.Type.FILE, 2048, new Date());
  }

  @Test
  public void toggleSelection_acceptsFile() {
    controller.enableSelectionMode(true);
    controller.toggleSelection(fileItem);

    Set<String> selected = controller.getSelectedPaths();
    assertTrue(selected.contains("/share/test.txt"));
    assertEquals(1, selected.size());
  }

  @Test
  public void toggleSelection_acceptsDirectory() {
    controller.enableSelectionMode(true);
    controller.toggleSelection(dirItem);

    Set<String> selected = controller.getSelectedPaths();
    assertTrue(selected.contains("/share/Photos"));
    assertEquals(1, selected.size());
  }

  @Test
  public void toggleSelection_togglesOffOnSecondCall() {
    controller.enableSelectionMode(true);
    controller.toggleSelection(dirItem);
    controller.toggleSelection(dirItem);

    // After toggling off the only item, selection mode is exited and paths are cleared
    assertTrue(controller.getSelectedPaths().isEmpty());
  }

  @Test
  public void toggleSelection_mixedFilesAndDirectories() {
    controller.enableSelectionMode(true);
    controller.toggleSelection(fileItem);
    controller.toggleSelection(dirItem);
    controller.toggleSelection(fileItem2);

    Set<String> selected = controller.getSelectedPaths();
    assertEquals(3, selected.size());
    assertTrue(selected.contains("/share/test.txt"));
    assertTrue(selected.contains("/share/Photos"));
    assertTrue(selected.contains("/share/doc.pdf"));
  }

  @Test
  public void toggleSelection_acceptsMultipleDirectories() {
    controller.enableSelectionMode(true);
    SmbFileItem dir2 =
        new SmbFileItem("Music", "/share/Music", SmbFileItem.Type.DIRECTORY, 0, new Date());

    controller.toggleSelection(dirItem);
    controller.toggleSelection(dir2);

    Set<String> selected = controller.getSelectedPaths();
    assertEquals(2, selected.size());
    assertTrue(selected.contains("/share/Photos"));
    assertTrue(selected.contains("/share/Music"));
  }

  @Test
  public void clearSelection_clearsAll() {
    controller.enableSelectionMode(true);
    controller.toggleSelection(fileItem);
    controller.toggleSelection(dirItem);

    controller.clearSelection();

    assertTrue(controller.getSelectedPaths().isEmpty());
  }

  @Test
  public void onFileLongClick_startsSelectionModeForDirectory() {
    assertFalse(controller.getSelectedPaths().contains("/share/Photos"));

    controller.onFileLongClick(dirItem);

    Set<String> selected = controller.getSelectedPaths();
    assertTrue(selected.contains("/share/Photos"));
    assertEquals(1, selected.size());
  }

  @Test
  public void onFileLongClick_startsSelectionModeForFile() {
    controller.onFileLongClick(fileItem);

    Set<String> selected = controller.getSelectedPaths();
    assertTrue(selected.contains("/share/test.txt"));
    assertEquals(1, selected.size());
  }

  @Test
  public void onFileClick_inSelectionMode_togglesDirectory() {
    controller.enableSelectionMode(true);
    controller.onFileClick(dirItem);

    Set<String> selected = controller.getSelectedPaths();
    assertTrue(
        "Directory should be selected on click in selection mode",
        selected.contains("/share/Photos"));
  }

  @Test
  public void onFileClick_inSelectionMode_togglesFile() {
    controller.enableSelectionMode(true);
    controller.onFileClick(fileItem);

    Set<String> selected = controller.getSelectedPaths();
    assertTrue(selected.contains("/share/test.txt"));
  }
}
