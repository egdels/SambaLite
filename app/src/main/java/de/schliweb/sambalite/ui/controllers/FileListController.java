package de.schliweb.sambalite.ui.controllers;

import android.view.View;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.ui.FileAdapter;
import de.schliweb.sambalite.ui.FileListViewModel;
import de.schliweb.sambalite.ui.FileSortOption;
import de.schliweb.sambalite.util.LogUtils;
import lombok.Setter;

import java.util.List;

/**
 * Controller for managing the file list display in the FileBrowserActivity.
 * Handles the RecyclerView, adapter, and file list interactions.
 */
public class FileListController implements FileAdapter.OnFileClickListener, FileAdapter.OnFileOptionsClickListener {

    private final RecyclerView recyclerView;
    private final SwipeRefreshLayout swipeRefreshLayout;
    private final View emptyView;
    private final TextView currentPathView;
    private final FileAdapter adapter;
    private final FileListViewModel viewModel;
    private final FileBrowserUIState uiState;

    // Callback interfaces
    @Setter
    private FileClickCallback fileClickCallback;

    @Setter
    private FileOptionsCallback fileOptionsCallback;

    @Setter
    private FileStatisticsCallback fileStatisticsCallback;

    /**
     * Creates a new FileListController.
     *
     * @param recyclerView       The RecyclerView for displaying files
     * @param swipeRefreshLayout The SwipeRefreshLayout for pull-to-refresh
     * @param emptyView          The view to show when the file list is empty
     * @param currentPathView    The TextView for displaying the current path
     * @param viewModel          The FileListViewModel for business logic
     * @param uiState            The shared UI state
     */
    public FileListController(RecyclerView recyclerView, SwipeRefreshLayout swipeRefreshLayout, View emptyView, TextView currentPathView, FileListViewModel viewModel, FileBrowserUIState uiState) {
        this.recyclerView = recyclerView;
        this.swipeRefreshLayout = swipeRefreshLayout;
        this.emptyView = emptyView;
        this.currentPathView = currentPathView;
        this.viewModel = viewModel;
        this.uiState = uiState;

        // Initialize the adapter
        this.adapter = new FileAdapter();
        this.adapter.setOnFileClickListener(this);
        this.adapter.setOnFileOptionsClickListener(this);

        // Set up the RecyclerView
        setupRecyclerView();

        // Set up the SwipeRefreshLayout
        setupSwipeRefreshLayout();

        // Observe the ViewModel
        observeViewModel();
    }

    /**
     * Sets up the RecyclerView.
     */
    private void setupRecyclerView() {
        recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
        recyclerView.setAdapter(adapter);
    }

    /**
     * Sets up the SwipeRefreshLayout.
     */
    private void setupSwipeRefreshLayout() {
        swipeRefreshLayout.setOnRefreshListener(() -> {
            LogUtils.d("FileListController", "Refreshing file list");
            viewModel.refreshCurrentDirectory();
        });
    }

    /**
     * Observes the ViewModel for changes.
     */
    private void observeViewModel() {
        // Observe file list changes
        viewModel.getFiles().observe(getLifecycleOwner(), files -> {
            LogUtils.d("FileListController", "File list updated: " + files.size() + " files");
            adapter.setFiles(files);
            updateEmptyView(files);
            swipeRefreshLayout.setRefreshing(false);

            // Update file statistics if callback is set
            if (fileStatisticsCallback != null) {
                fileStatisticsCallback.onFileStatisticsUpdated(files);
            }
        });

        // Observe current path changes
        viewModel.getCurrentPath().observe(getLifecycleOwner(), path -> {
            LogUtils.d("FileListController", "Current path updated: " + path);
            currentPathView.setText(path);
        });

        // Observe loading state
        viewModel.isLoading().observe(getLifecycleOwner(), isLoading -> {
            swipeRefreshLayout.setRefreshing(isLoading);
        });
    }

    /**
     * Updates the empty view based on the file list.
     *
     * @param files The list of files
     */
    private void updateEmptyView(List<SmbFileItem> files) {
        if (files.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Gets the lifecycle owner for observing LiveData.
     */
    private androidx.lifecycle.LifecycleOwner getLifecycleOwner() {
        return (androidx.lifecycle.LifecycleOwner) recyclerView.getContext();
    }

    /**
     * Called when a file is clicked.
     *
     * @param file The file that was clicked
     */
    @Override
    public void onFileClick(SmbFileItem file) {
        LogUtils.d("FileListController", "File clicked: " + file.getName());

        // Store the selected file in the UI state
        uiState.setSelectedFile(file);

        // If it's a directory, navigate to it
        if (file.isDirectory()) {
            viewModel.navigateToDirectory(file);
        } else {
            // Otherwise, notify the callback
            if (fileClickCallback != null) {
                fileClickCallback.onFileClick(file);
            }
        }
    }

    /**
     * Called when the file options button is clicked.
     *
     * @param file The file for which options were requested
     */
    @Override
    public void onFileOptionsClick(SmbFileItem file) {
        LogUtils.d("FileListController", "File options clicked: " + file.getName());

        // Store the selected file in the UI state
        uiState.setSelectedFile(file);

        // Notify the callback
        if (fileOptionsCallback != null) {
            fileOptionsCallback.onFileOptionsClick(file);
        }
    }

    /**
     * Called when the parent directory item is clicked.
     */
    @Override
    public void onParentDirectoryClick() {
        LogUtils.d("FileListController", "Parent directory clicked");
        navigateUp();
    }

    /**
     * Navigates to the parent directory.
     *
     * @return true if navigation was successful, false if already at the root
     */
    public boolean navigateUp() {
        LogUtils.d("FileListController", "Navigating up");
        return viewModel.navigateUp();
    }

    /**
     * Sets the sort option.
     *
     * @param option The sort option to set
     */
    public void setSortOption(FileSortOption option) {
        LogUtils.d("FileListController", "Setting sort option: " + option);
        viewModel.setSortOption(option);
    }

    /**
     * Gets the current sort option.
     *
     * @return The current sort option
     */
    public FileSortOption getCurrentSortOption() {
        return viewModel.getSortOption().getValue();
    }

    /**
     * Gets whether directories are shown first.
     *
     * @return Whether directories are shown first
     */
    public boolean isDirectoriesFirst() {
        return viewModel.getDirectoriesFirst().getValue();
    }

    /**
     * Sets whether directories should be shown first.
     *
     * @param directoriesFirst Whether directories should be shown first
     */
    public void setDirectoriesFirst(boolean directoriesFirst) {
        LogUtils.d("FileListController", "Setting directories first: " + directoriesFirst);
        viewModel.setDirectoriesFirst(directoriesFirst);
    }

    /**
     * Updates the adapter with the given list of files.
     * This method can be used to display search results or other file lists
     * that are not directly observed from the ViewModel.
     *
     * @param files The list of files to display
     */
    public void updateAdapter(List<SmbFileItem> files) {
        LogUtils.d("FileListController", "Updating adapter with " + files.size() + " files");
        adapter.setFiles(files);
        updateEmptyView(files);
    }

    /**
     * Callback for file clicks.
     */
    public interface FileClickCallback {
        /**
         * Called when a file is clicked.
         *
         * @param file The file that was clicked
         */
        void onFileClick(SmbFileItem file);
    }

    /**
     * Callback for file options clicks.
     */
    public interface FileOptionsCallback {
        /**
         * Called when the file options button is clicked.
         *
         * @param file The file for which options were requested
         */
        void onFileOptionsClick(SmbFileItem file);
    }

    /**
     * Callback for file statistics updates.
     */
    public interface FileStatisticsCallback {
        /**
         * Called when the file statistics are updated.
         *
         * @param files The list of files
         */
        void onFileStatisticsUpdated(List<SmbFileItem> files);
    }
}