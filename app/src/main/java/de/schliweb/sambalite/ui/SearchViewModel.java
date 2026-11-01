/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.search.SearchWorker;
import de.schliweb.sambalite.search.db.SearchDatabase;
import de.schliweb.sambalite.search.db.SearchResult;
import de.schliweb.sambalite.search.db.SearchResultDao;
import de.schliweb.sambalite.util.LogUtils;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.inject.Inject;

/**
 * ViewModel for handling search functionality via WorkManager. Search results are stored in a Room
 * database and observed via LiveData for live UI updates as results are found.
 */
public class SearchViewModel extends ViewModel {

  private static final String TAG = "SearchViewModel";
  private static final String UNIQUE_SEARCH_WORK = "smb_search";

  private final Application application;
  private final FileBrowserState state;
  private final FileListViewModel fileListViewModel;
  private final SearchResultDao searchResultDao;

  private String currentSearchId = "";

  // All results of the current search as stored by the worker, before hiding and sorting
  @Nullable private List<SmbFileItem> lastDbItems;

  // Work request of the current search, to tell its failure apart from older searches
  @Nullable private UUID currentWorkId;

  // Technical error of the current search if its worker failed, otherwise null
  @Nullable private String searchFailure;

  /** LiveData that maps SearchResult rows to SmbFileItem list for the UI. */
  private final MediatorLiveData<List<SmbFileItem>> searchResults = new MediatorLiveData<>();

  private LiveData<List<SearchResult>> currentDbSource;

  /** LiveData derived from WorkManager state — true while the search worker is running. */
  private final LiveData<Boolean> searching;

  /** LiveData for the result count from the DB. */
  private LiveData<Integer> resultCount;

  private LiveData<Integer> currentCountSource;

  @Inject
  public SearchViewModel(
      @NonNull Application application,
      @NonNull FileBrowserState state,
      @NonNull FileListViewModel fileListViewModel) {
    this.application = application;
    this.state = state;
    this.fileListViewModel = fileListViewModel;
    this.searchResultDao = SearchDatabase.getInstance(application).searchResultDao();

    // Derive isSearching from WorkManager work info
    LiveData<List<WorkInfo>> workInfos =
        WorkManager.getInstance(application).getWorkInfosForUniqueWorkLiveData(UNIQUE_SEARCH_WORK);
    searching =
        Transformations.map(
            workInfos,
            infos -> {
              if (infos == null || infos.isEmpty()) return false;
              searchFailure = failureOf(infos, currentWorkId);
              for (WorkInfo info : infos) {
                if (info.getState() == WorkInfo.State.RUNNING
                    || info.getState() == WorkInfo.State.ENQUEUED) {
                  return true;
                }
              }
              return false;
            });

    searchResults.setValue(new ArrayList<>());

    // Observe state.searchResults so that re-sorting from FileListViewModel is reflected in the UI
    searchResults.addSource(
        state.getSearchResults(),
        stateResults -> {
          if (state.getSearchMode() && stateResults != null) {
            List<SmbFileItem> current = searchResults.getValue();
            // Only update when the list content actually changed (e.g. after re-sort)
            if (current != stateResults) {
              searchResults.setValue(stateResults);
            }
          }
        });

    // Turning "Show hidden files/folders" on or off applies to the results shown
    searchResults.addSource(
        state.getShowHiddenFiles(),
        show -> {
          if (state.getSearchMode()) {
            publishResults();
          }
        });

    LogUtils.d(TAG, "SearchViewModel initialized");
  }

  /** Gets the search results as LiveData (mapped from DB). */
  public @NonNull LiveData<List<SmbFileItem>> getSearchResults() {
    return searchResults;
  }

  /**
   * Returns the error of the current search if its worker failed, e.g. because the server could not
   * be reached. Updated before {@link #isSearching()} reports the end of the search.
   *
   * @return the technical error, an empty string if the worker gave none, or null if the search did
   *     not fail
   */
  public @Nullable String getSearchFailure() {
    return searchFailure;
  }

  /**
   * Finds the failure of the given search work among the work infos.
   *
   * @param infos the infos of the unique search work
   * @param workId the id of the current search request, or null if none was started
   * @return the error stored by the worker (empty if none), or null if the work did not fail
   */
  static @Nullable String failureOf(@NonNull List<WorkInfo> infos, @Nullable UUID workId) {
    if (workId == null) return null;
    for (WorkInfo info : infos) {
      if (workId.equals(info.getId()) && info.getState() == WorkInfo.State.FAILED) {
        String error = info.getOutputData().getString(SearchWorker.KEY_ERROR);
        return error != null ? error : "";
      }
    }
    return null;
  }

  /** Gets the searching state as LiveData (derived from WorkManager). */
  public @NonNull LiveData<Boolean> isSearching() {
    return searching;
  }

  /** Gets the result count as LiveData. */
  public @NonNull LiveData<Integer> getResultCount() {
    if (resultCount == null) {
      resultCount = new MediatorLiveData<>();
    }
    return resultCount;
  }

  /** Checks if the view model is in search mode. */
  public boolean isInSearchMode() {
    return state.getSearchMode();
  }

  /**
   * Searches for files matching the query with specified options. Starts a SearchWorker via
   * WorkManager and observes results from the Room database.
   */
  public void searchFiles(@NonNull String query, int searchType, boolean includeSubfolders) {
    if (state.getConnection() == null) {
      LogUtils.w(TAG, "Cannot search: connection is null");
      return;
    }

    if (query.trim().isEmpty()) {
      clearSearch();
      return;
    }

    LogUtils.d(
        TAG,
        "Starting search: query='"
            + query
            + "', searchType="
            + searchType
            + ", includeSubfolders="
            + includeSubfolders);

    state.setCurrentSearchQuery(query.trim());
    state.setSearchMode(true);
    state.setSearching(true);
    state.setSearchStartPath(state.getCurrentPathString());

    // Generate a new search ID
    currentSearchId = UUID.randomUUID().toString();

    // Switch DB LiveData source to the new search ID
    lastDbItems = null;
    switchDbSource(currentSearchId);

    // Build and enqueue the SearchWorker
    Data inputData =
        new Data.Builder()
            .putString(SearchWorker.KEY_SEARCH_ID, currentSearchId)
            .putString(SearchWorker.KEY_CONNECTION_ID, state.getConnection().getId())
            .putString(SearchWorker.KEY_SEARCH_PATH, state.getCurrentPathString())
            .putString(SearchWorker.KEY_QUERY, query.trim())
            .putInt(SearchWorker.KEY_SEARCH_TYPE, searchType)
            .putBoolean(SearchWorker.KEY_INCLUDE_SUBFOLDERS, includeSubfolders)
            .build();

    OneTimeWorkRequest request =
        new OneTimeWorkRequest.Builder(SearchWorker.class).setInputData(inputData).build();

    currentWorkId = request.getId();
    searchFailure = null;
    WorkManager.getInstance(application)
        .enqueueUniqueWork(UNIQUE_SEARCH_WORK, ExistingWorkPolicy.REPLACE, request);

    LogUtils.i(TAG, "SearchWorker enqueued: searchId=" + currentSearchId);
  }

  /** Cancels any ongoing search operation. */
  public void cancelSearch() {
    boolean currentlySearching = Boolean.TRUE.equals(searching.getValue());
    boolean inSearchMode = state.getSearchMode();

    if (!currentlySearching && !inSearchMode) {
      return;
    }

    LogUtils.d(TAG, "Cancelling search");

    if (currentlySearching) {
      WorkManager.getInstance(application).cancelUniqueWork(UNIQUE_SEARCH_WORK);
      state.setSearching(false);
      clearSearch();
    } else if (inSearchMode) {
      clearSearch();
    }
  }

  /**
   * Ends search mode without returning to the folder the search started in, e.g. because a folder
   * from the results is opened. A running search is cancelled.
   */
  public void leaveSearchMode() {
    if (Boolean.TRUE.equals(searching.getValue())) {
      WorkManager.getInstance(application).cancelUniqueWork(UNIQUE_SEARCH_WORK);
      state.setSearching(false);
    }
    state.setSearchMode(false);
    state.setCurrentSearchQuery("");
    state.setSearchResults(new ArrayList<>());
    state.setSearchStartPath("");
    searchResults.setValue(new ArrayList<>());
    lastDbItems = null;
  }

  /** Clears the search results and returns to normal browsing. */
  public void clearSearch() {
    LogUtils.d(TAG, "Clearing search results");
    state.setSearchMode(false);
    state.setCurrentSearchQuery("");
    state.setSearchResults(new ArrayList<>());
    searchResults.setValue(new ArrayList<>());
    lastDbItems = null;

    // Navigate back to the folder where the search started
    String startPath = state.getSearchStartPath();
    if (startPath != null && !startPath.isEmpty()) {
      LogUtils.d(TAG, "Returning to search start folder: " + startPath);
      fileListViewModel.navigateToPathWithHierarchy(startPath);
      state.setSearchStartPath("");
    } else {
      fileListViewModel.loadFiles();
    }
  }

  /** Gets the current search query. */
  public @NonNull String getCurrentSearchQuery() {
    return state.getCurrentSearchQuery();
  }

  /** Gets the connection ID. */
  public @NonNull String getConnectionId() {
    if (state.getConnection() != null) {
      return state.getConnection().getId();
    }
    return "";
  }

  /** Gets the current search type. */
  public int getCurrentSearchType() {
    return 0;
  }

  /** Gets whether subfolders are included in the search. */
  public boolean isIncludeSubfolders() {
    return true;
  }

  /** Switches the LiveData source to observe results for the given search ID. */
  private void switchDbSource(String searchId) {
    // Remove old source
    if (currentDbSource != null) {
      searchResults.removeSource(currentDbSource);
    }
    if (currentCountSource != null && resultCount instanceof MediatorLiveData) {
      ((MediatorLiveData<Integer>) resultCount).removeSource(currentCountSource);
    }

    // Add new source for results
    currentDbSource = searchResultDao.observeResults(searchId);
    searchResults.addSource(
        currentDbSource,
        dbResults -> {
          if (dbResults == null) {
            searchResults.setValue(new ArrayList<>());
            return;
          }
          List<SmbFileItem> items = new ArrayList<>(dbResults.size());
          for (SearchResult r : dbResults) {
            items.add(toSmbFileItem(r));
          }
          lastDbItems = items;
          publishResults();
        });

    // Add new source for count
    currentCountSource = searchResultDao.observeResultCount(searchId);
    if (resultCount instanceof MediatorLiveData) {
      ((MediatorLiveData<Integer>) resultCount)
          .addSource(
              currentCountSource,
              count -> {
                ((MediatorLiveData<Integer>) resultCount).setValue(count != null ? count : 0);
              });
    }
  }

  /**
   * Shows the results of the current search, without hidden entries unless "Show hidden
   * files/folders" is on, in the current sort order.
   */
  private void publishResults() {
    if (lastDbItems == null) return;
    List<SmbFileItem> items =
        new ArrayList<>(
            fileListViewModel.filterHiddenSearchResults(lastDbItems, state.getSearchStartPath()));
    // Apply current sort option to search results
    fileListViewModel.sortFiles(items);
    searchResults.setValue(items);
    // Also update the state so other observers (e.g. FileBrowserActivity) see it
    state.setSearchResults(items);
  }

  /** Maps a SearchResult DB entity to an SmbFileItem for the UI. */
  private SmbFileItem toSmbFileItem(SearchResult r) {
    return new SmbFileItem(
        r.name,
        r.path,
        "DIRECTORY".equals(r.type) ? SmbFileItem.Type.DIRECTORY : SmbFileItem.Type.FILE,
        r.size,
        new Date(r.lastModified));
  }
}
