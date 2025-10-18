package de.schliweb.sambalite.ui.controllers;

import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;
import androidx.lifecycle.MutableLiveData;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.service.SmbBackgroundService;
import lombok.Getter;
import lombok.Setter;

import java.io.File;

/**
 * Shared UI state object for the file browser controllers.
 * This class holds the state that needs to be shared between the specialized controllers:
 * - FileListController
 * - DialogController
 * - ProgressController
 * - ActivityResultController
 * - ServiceController
 */
@Getter
public class FileBrowserUIState {

    // Progress state
    private final MutableLiveData<Integer> progressPercentage = new MutableLiveData<>(0);
    private final MutableLiveData<String> progressMessage = new MutableLiveData<>("");
    private final MutableLiveData<String> progressDetails = new MutableLiveData<>("");
    // File operation state
    @Setter
    private SmbFileItem selectedFile;
    @Setter
    private File tempFile;
    @Setter
    private Uri selectedUri;
    @Setter
    private DocumentFile selectedDocumentFile;
    // Multi-download pending state
    @Setter
    private java.util.List<de.schliweb.sambalite.data.model.SmbFileItem> pendingMultiDownloadItems;
    @Setter
    private boolean multiDownloadPending;
    // Service state
    @Setter
    private SmbBackgroundService backgroundService;
    @Setter
    private boolean isServiceBound;
    // Dialog state
    @Setter
    private boolean isProgressDialogShowing;
    @Setter
    private boolean isSearchDialogShowing;
    @Setter
    private String currentOperation;

    /**
     * Updates the progress information.
     *
     * @param percentage The progress percentage (0-100)
     * @param message    The progress message
     * @param details    The progress details
     */
    public void updateProgress(int percentage, String message, String details) {
        progressPercentage.postValue(percentage);
        progressMessage.postValue(message);
        progressDetails.postValue(details);
    }

    /**
     * Resets the progress information.
     */
    public void resetProgress() {
        progressPercentage.postValue(0);
        progressMessage.postValue("");
        progressDetails.postValue("");
    }

    /**
     * Resets all state.
     */
    public void reset() {
        selectedFile = null;
        tempFile = null;
        selectedUri = null;
        selectedDocumentFile = null;
        isProgressDialogShowing = false;
        isSearchDialogShowing = false;
        currentOperation = null;
        resetProgress();
    }
}