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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.MutableLiveData;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.ui.BreadcrumbView;
import de.schliweb.sambalite.ui.FileListViewModel;
import de.schliweb.sambalite.ui.FileSortOption;
import de.schliweb.sambalite.ui.SortDirection;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The multi-selection survives a recreation of the activity in the same folder. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FileListControllerSelectionRestoreTest {

  @Mock private FileListViewModel viewModel;
  private final MutableLiveData<String> currentPath = new MutableLiveData<>("Second/Many");
  private final MutableLiveData<List<SmbFileItem>> files =
      new MutableLiveData<>(Collections.emptyList());
  private FileListController controller;
  private final AtomicInteger lastCount = new AtomicInteger(-1);

  @Before
  public void setUp() {
    MockitoAnnotations.openMocks(this);
    when(viewModel.getFiles()).thenReturn(files);
    when(viewModel.getCurrentPath()).thenReturn(currentPath);
    when(viewModel.getLoadError()).thenReturn(new MutableLiveData<>(null));
    when(viewModel.isLoading()).thenReturn(new MutableLiveData<>(false));
    when(viewModel.getSortOption()).thenReturn(new MutableLiveData<>(FileSortOption.NAME));
    when(viewModel.getDirectoriesFirst()).thenReturn(new MutableLiveData<>(true));
    when(viewModel.getSortDirection()).thenReturn(new MutableLiveData<>(SortDirection.ASCENDING));

    AppCompatActivity activity =
        Robolectric.buildActivity(AppCompatActivity.class).create().start().resume().get();
    controller =
        new FileListController(
            new RecyclerView(activity),
            new SwipeRefreshLayout(activity),
            new View(activity),
            new BreadcrumbView(activity),
            viewModel,
            new FileBrowserUIState());
    controller.setSelectionChangedCallback((count, items) -> lastCount.set(count));
  }

  @Test
  public void restoredSelection_staysInSameFolder_andIsClearedOnNavigation() {
    controller.restoreSelection(Arrays.asList("Many/file_001.txt", "Many/file_002.txt"));
    files.setValue(
        Arrays.asList(
            new SmbFileItem(
                "file_001.txt", "Many/file_001.txt", SmbFileItem.Type.FILE, 6, new Date()),
            new SmbFileItem(
                "file_002.txt", "Many/file_002.txt", SmbFileItem.Type.FILE, 6, new Date())));

    // The folder is published again after the recreation: the selection stays
    currentPath.setValue("Second/Many");
    assertTrue(controller.isSelectionMode());
    assertEquals(2, lastCount.get());
    assertEquals(2, controller.getSelectionForSave().size());

    // Another folder ends the selection
    currentPath.setValue("Second");
    assertFalse(controller.isSelectionMode());
    assertNull(controller.getSelectionForSave());
  }

  @Test
  public void noSelection_savesNothing() {
    assertNull(controller.getSelectionForSave());
    controller.restoreSelection(null);
    assertFalse(controller.isSelectionMode());
  }

  @Test
  public void folderInSearchResults_leavesSearchAndOpensWithHierarchy() {
    java.util.concurrent.atomic.AtomicBoolean left =
        new java.util.concurrent.atomic.AtomicBoolean();
    controller.setLeaveSearchCallback(() -> left.set(true));
    controller.setSearchMode(true);
    SmbFileItem folder =
        new SmbFileItem("Ordner_12", "Archiv/Ordner_12", SmbFileItem.Type.DIRECTORY, 0, new Date());

    controller.onFileClick(folder);

    assertTrue(left.get());
    org.mockito.Mockito.verify(viewModel).navigateToPathWithHierarchy("Archiv/Ordner_12");
    org.mockito.Mockito.verify(viewModel, org.mockito.Mockito.never())
        .navigateToDirectory(org.mockito.ArgumentMatchers.any());
  }

  @Test
  public void folderOutsideSearch_isOpenedNormally() {
    controller.setLeaveSearchCallback(() -> {});
    SmbFileItem folder = new SmbFileItem("Docs", "Docs", SmbFileItem.Type.DIRECTORY, 0, new Date());

    controller.onFileClick(folder);

    org.mockito.Mockito.verify(viewModel).navigateToDirectory(folder);
  }
}
