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
import static org.mockito.Mockito.when;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.MutableLiveData;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.ui.BreadcrumbView;
import de.schliweb.sambalite.ui.FileListViewModel;
import de.schliweb.sambalite.ui.FileSortOption;
import de.schliweb.sambalite.ui.SortDirection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** A folder that could not be listed must not be shown as an empty folder. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FileListControllerLoadErrorTest {

  @Mock private FileListViewModel viewModel;
  private final MutableLiveData<List<SmbFileItem>> files =
      new MutableLiveData<>(Collections.emptyList());
  private final MutableLiveData<String> loadError = new MutableLiveData<>(null);
  private View emptyView;

  @Before
  public void setUp() {
    MockitoAnnotations.openMocks(this);
    when(viewModel.getFiles()).thenReturn(files);
    when(viewModel.getLoadError()).thenReturn(loadError);
    when(viewModel.getCurrentPath()).thenReturn(new MutableLiveData<>("Data"));
    when(viewModel.isLoading()).thenReturn(new MutableLiveData<>(false));
    when(viewModel.getSortOption()).thenReturn(new MutableLiveData<>(FileSortOption.NAME));
    when(viewModel.getDirectoriesFirst()).thenReturn(new MutableLiveData<>(true));
    when(viewModel.getSortDirection()).thenReturn(new MutableLiveData<>(SortDirection.ASCENDING));

    AppCompatActivity activity =
        Robolectric.buildActivity(AppCompatActivity.class).create().start().resume().get();
    activity.setTheme(R.style.Theme_SambaLite);
    View root = LayoutInflater.from(activity).inflate(R.layout.activity_file_browser, null);
    emptyView = root.findViewById(R.id.empty_state);

    new FileListController(
        root.findViewById(R.id.files_recycler_view),
        root.findViewById(R.id.swipe_refresh),
        emptyView,
        root.findViewById(R.id.current_path),
        viewModel,
        new FileBrowserUIState());
  }

  private String title() {
    return ((TextView) emptyView.findViewById(R.id.empty_state_title)).getText().toString();
  }

  private String message() {
    return ((TextView) emptyView.findViewById(R.id.empty_state_message)).getText().toString();
  }

  @Test
  public void failedListing_showsLoadErrorWithReason() {
    loadError.setValue("Failed to execute share operation after 3 attempts: Connection refused");
    files.setValue(new ArrayList<>());

    assertEquals(View.VISIBLE, emptyView.getVisibility());
    assertEquals("Could not load this folder", title());
    assertEquals("Connection to the server failed. Pull down to try again.", message());
  }

  @Test
  public void accessDenied_isNamedAsReason() {
    loadError.setValue("STATUS_ACCESS_DENIED (0xc0000022): Create failed");
    files.setValue(new ArrayList<>());

    assertEquals("No permission on the server. Pull down to try again.", message());
  }

  @Test
  public void successfulEmptyListingAfterError_showsEmptyFolderAgain() {
    loadError.setValue("Connection refused");
    files.setValue(new ArrayList<>());

    loadError.setValue(null);
    files.setValue(new ArrayList<>());

    assertEquals("This directory is empty", title());
    assertEquals(
        "This folder is empty. Use the + button to upload files or create folders.", message());
  }

  @Test
  public void nonEmptyListing_hidesEmptyState() {
    files.setValue(
        List.of(new SmbFileItem("a.txt", "a.txt", SmbFileItem.Type.FILE, 1, new Date())));

    assertEquals(View.GONE, emptyView.getVisibility());
  }
}
