package de.schliweb.sambalite.ui.controllers;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.ui.controllers.FileOperationsController.ProgressCallback;
import de.schliweb.sambalite.ui.utils.LoadingIndicator;
import de.schliweb.sambalite.ui.utils.ProgressFormat;
import de.schliweb.sambalite.ui.utils.UIHelper;
import de.schliweb.sambalite.util.LogUtils;
import lombok.Setter;

import java.util.regex.Pattern;

/**
 * Controller for managing progress dialogs, indicators, and progress tracking.
 * This controller handles all UI elements related to progress feedback and provides
 * a unified approach to user feedback across the application.
 * <p>
 * It implements both ProgressCallback for backward compatibility and UserFeedbackProvider
 * for the new consolidated approach to user feedback.
 */
public class ProgressController implements ProgressCallback, UserFeedbackProvider {

    private final Activity activity;
    private final LoadingIndicator loadingIndicator;
    @Setter
    private SearchCancellationCallback searchCancellationCallback;
    // Progress dialog for detailed download/upload progress
    private AlertDialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressMessage;
    private TextView progressPercentage;
    private TextView progressDetails;
    // Search progress dialog
    private AlertDialog searchProgressDialog;

    // If a cancel action is provided before the dialog is visible (lazy-open case),
    // cache it here and apply as soon as the dialog is shown.
    private Runnable pendingCancelAction;

    private int lastOverall = -1;
    private int lastCur = -1;
    private int lastTotal = -1;
    private String lastFile = "";
    private long lastBytesUiTs = 0L;

    private static final Pattern PCT = Pattern.compile("\\b(\\d{1,3})%");

    /**
     * Creates a new ProgressController.
     *
     * @param activity The activity
     */
    public ProgressController(Activity activity) {
        this.activity = activity;
        this.loadingIndicator = new LoadingIndicator(activity);
        LogUtils.d("ProgressController", "ProgressController initialized");
    }

    /**
     * Shows a loading indicator with a message.
     *
     * @param message      The message to display
     * @param cancelable   Whether the loading indicator can be cancelled
     * @param cancelAction The action to take when the loading indicator is cancelled
     */
    @Override
    public void showLoadingIndicator(String message, boolean cancelable, Runnable cancelAction) {
        LogUtils.d("ProgressController", "Showing loading indicator: " + message);
        loadingIndicator.show(message, cancelable, cancelAction);
    }

    /**
     * Updates the loading indicator message.
     *
     * @param message The new message
     */
    @Override
    public void updateLoadingMessage(String message) {
        LogUtils.d("ProgressController", "Updating loading message: " + message);
        loadingIndicator.updateMessage(message);
    }

    /**
     * Sets whether the cancel button is enabled.
     *
     * @param enabled Whether the cancel button is enabled
     */
    @Override
    public void setCancelButtonEnabled(boolean enabled) {
        LogUtils.d("ProgressController", "Setting cancel button enabled: " + enabled);
        loadingIndicator.setCancelButtonEnabled(enabled);
    }

    /**
     * Hides the loading indicator.
     */
    @Override
    public void hideLoadingIndicator() {
        LogUtils.d("ProgressController", "Hiding loading indicator");
        loadingIndicator.hide();
    }

    /**
     * Shows a detailed progress dialog with progress bar and percentage.
     *
     * @param title   The title of the dialog
     * @param message The initial message
     */
    @Override
    public void showDetailedProgressDialog(String title, String message) {
        LogUtils.d("ProgressController", "Showing detailed progress dialog: " + title + " - " + message);

        // Do not show if activity is not in foreground state
        if (!isActivitySafe() || !isLifecycleAtLeastResumed()) {
            LogUtils.w("ProgressController", "Activity not in RESUMED state, skipping progress dialog show");
            return;
        }

        final String finalTitle = title;
        final String finalMessage = message;

        activity.runOnUiThread(() -> {
            try {
                if (!isActivitySafe() || !isLifecycleAtLeastResumed()) {
                    LogUtils.w("ProgressController", "Activity not safe/resumed during dialog show, aborting");
                    return;
                }
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }

                resetUiProgressCache();

                // Inflate custom progress dialog layout
                View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_progress, null);

                TextView titleView = dialogView.findViewById(R.id.progress_title);
                progressMessage = dialogView.findViewById(R.id.progress_message);
                progressPercentage = dialogView.findViewById(R.id.progress_percentage);
                progressDetails = dialogView.findViewById(R.id.progress_details);
                if (progressDetails != null) progressDetails.setSelected(true);
                progressBar = dialogView.findViewById(R.id.progress_bar);

                titleView.setText(finalTitle);
                progressMessage.setText(finalMessage);
                progressPercentage.setText("0%");
                progressDetails.setText("");
                progressBar.setProgress(0);
                progressBar.setMax(100);
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setIndeterminate(false);

                MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity);
                builder.setView(dialogView).setCancelable(false);

                // Add cancel button for download
                builder.setNegativeButton(R.string.cancel, (dialog, which) -> {
                    LogUtils.d("ProgressController", "User requested cancellation from progress dialog");
                    // The actual cancellation will be handled by the callback
                });

                progressDialog = builder.create();
                progressDialog.show();

                // If a cancel action was set before the dialog was shown (lazy-open case),
                // apply it now to ensure the Cancel button works immediately.
                try {
                    if (pendingCancelAction != null) {
                        setDetailedProgressDialogCancelAction(pendingCancelAction);
                    }
                } catch (Throwable ignore) {}

                LogUtils.d("ProgressController", "Detailed progress dialog shown");
            } catch (Exception e) {
                LogUtils.e("ProgressController", "Error showing detailed progress dialog: " + e.getMessage());
            }
        });
    }

    private void resetUiProgressCache() {
        lastOverall = -1;
        lastCur = -1;
        lastTotal = -1;
        lastFile = "";
        lastBytesUiTs = 0L;
    }

    /**
     * Updates the detailed progress dialog.
     *
     * @param percentage The progress percentage (0-100)
     * @param statusText The status text
     * @param fileName   The name of the file being processed
     */
    @Override
    public void updateDetailedProgress(int percentage, String statusText, String fileName) {
        if (!isActivitySafe()) return;

        // Lazily open the dialog if not already showing and the activity is safely RESUMED
        if (progressDialog == null || !progressDialog.isShowing()) {
            if (isLifecycleAtLeastResumed()) {
                String initialTitle = activity.getString(R.string.transfer_title);
                String initialMsg = (statusText != null && !statusText.isEmpty())
                        ? statusText
                        : activity.getString(R.string.preparing_transfer);
                showDetailedProgressDialog(initialTitle, initialMsg);
            } else {
                // If not resumed, skip dialog creation; update will be applied once visible
            }
        }

        final int overallPct = Math.max(0, Math.min(100, percentage));
        final String raw = statusText != null ? statusText : "";
        final String fallbackName = fileName != null ? fileName : "";

        activity.runOnUiThread(() -> {
            if (progressDialog == null || !progressDialog.isShowing()) return;

            ProgressFormat.Result r = ProgressFormat.parse(raw, overallPct, fallbackName);

            int cur = r.cur().orElse(lastCur > 0 ? lastCur : 0);
            int total = r.total().orElse(lastTotal > 0 ? lastTotal : 0);
            int filePct = r.filePct().orElse(overallPct);
            String baseName = r.fileName().orElse("");

            String head = ProgressFormat.toHeadlineNoPercent(r);
            if (head.isEmpty()) {
                if (cur > 0 && total > 0) {
                    head = cur + "/" + total;
                } else {
                    head = "";
                }
            }

            boolean changedOverall = (overallPct != lastOverall);
            boolean changedIndex = (cur != lastCur) || (total != lastTotal);

            if (progressBar != null && changedOverall) {
                progressBar.setProgress(overallPct, true);
            }

            if (progressPercentage != null) {
                String nextPctText = Math.max(0, Math.min(100, filePct)) + "%";
                if (!nextPctText.contentEquals(progressPercentage.getText())) {
                    progressPercentage.setText(nextPctText);
                }
            }

            if (progressMessage != null) {
                if (!head.contentEquals(progressMessage.getText())) {
                    progressMessage.setText(head);
                }
            }

            if (progressDetails != null) {
                long now = System.currentTimeMillis();
                boolean fileChanged = !baseName.equals(lastFile);
                boolean allowUpdate = fileChanged || changedIndex || (now - lastBytesUiTs >= 120);

                if (allowUpdate) {
                    String nameToShow = baseName != null ? baseName : "";
                    if (nameToShow.length() > 80) {
                        nameToShow = nameToShow.substring(0, 77) + "…";
                    }
                    if (!nameToShow.contentEquals(progressDetails.getText())) {
                        progressDetails.setText(nameToShow);
                    }
                    lastBytesUiTs = now;
                }
            }

            lastOverall = overallPct;
            if (r.cur().isPresent()) lastCur = r.cur().get();
            if (r.total().isPresent()) lastTotal = r.total().get();
            lastFile = baseName;

            LogUtils.d("ProgressController",
                    "Progress updated: overall=" + overallPct + "%, idx=" +
                            (cur > 0 ? cur : lastCur) + "/" + (total > 0 ? total : lastTotal) +
                            ", filePct=" + filePct + ", name=" + baseName);
        });
    }


    /**
     * Hides the detailed progress dialog.
     */
    @Override
    public void hideDetailedProgressDialog() {
        if (!isActivitySafe()) return;

        // Ensure this runs on the UI thread
        activity.runOnUiThread(() -> {
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
                progressDialog = null;
                resetUiProgressCache();
                // Clear any pending cancel action to avoid leaking across operations
                pendingCancelAction = null;
                LogUtils.d("ProgressController", "Detailed progress dialog hidden");
            }
        });
    }

    /**
     * Sets a cancel action for the detailed progress dialog.
     * This should be called immediately after showDetailedProgressDialog.
     *
     * @param cancelAction The action to take when the dialog is cancelled
     */
    public void setDetailedProgressDialogCancelAction(Runnable cancelAction) {
        // Always remember the latest cancel action to apply even if the dialog is lazily opened later
        pendingCancelAction = cancelAction;
        if (!isActivitySafe() || progressDialog == null || !progressDialog.isShowing()) return;

        // Ensure this runs on the UI thread
        final Runnable finalCancelAction = cancelAction;
        activity.runOnUiThread(() -> {
            try {
                // Get the cancel button from the dialog
                android.widget.Button cancelButton = progressDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                if (cancelButton != null && finalCancelAction != null) {
                    // Set the click listener for the cancel button
                    cancelButton.setOnClickListener(v -> {
                        LogUtils.d("ProgressController", "User requested cancellation from progress dialog");
                        try {
                            finalCancelAction.run();
                        } finally {
                            // Close the dialog immediately on user cancel to avoid UI hanging
                            try { hideDetailedProgressDialog(); } catch (Throwable ignore) {}
                        }
                    });
                    LogUtils.d("ProgressController", "Cancel action set for detailed progress dialog");
                }
            } catch (Exception e) {
                LogUtils.e("ProgressController", "Error setting cancel action for detailed progress dialog: " + e.getMessage());
            }
        });
    }

    /**
     * Shows progress directly in the UI instead of just hourglass.
     *
     * @param operationName The name of the operation
     * @param progressText  The progress text
     */
    @Override
    public void showProgressInUI(String operationName, String progressText) {
        // Ensure this runs on the UI thread
        final String finalProgressText = progressText;

        activity.runOnUiThread(() -> {
            if (loadingIndicator.isShowing()) {
                // Update the loading message with progress
                loadingIndicator.updateMessage(finalProgressText);
                LogUtils.d("ProgressController", "Progress updated in UI: " + finalProgressText);
            }
        });
    }

    /**
     * Shows a dialog for file existence confirmation.
     *
     * @param fileName      The name of the file that exists
     * @param confirmAction The action to take if the user confirms overwrite
     * @param cancelAction  The action to take if the user cancels
     */
    @Override
    public void showFileExistsDialog(String fileName, Runnable confirmAction, Runnable cancelAction) {
        if (!isActivitySafe()) {
            LogUtils.w("ProgressController", "Activity is finishing/destroyed, cancelling file exists dialog for: " + fileName);
            cancelAction.run();
            return;
        }

        // Ensure this runs on the UI thread
        final String finalFileName = fileName;
        final Runnable finalConfirmAction = confirmAction;
        final Runnable finalCancelAction = cancelAction;

        activity.runOnUiThread(() -> {
            try {
                new MaterialAlertDialogBuilder(activity).setTitle(R.string.file_exists_title).setMessage(activity.getString(R.string.file_exists_message, finalFileName)).setPositiveButton(R.string.overwrite, (dialog, which) -> {
                    // User confirmed overwrite
                    LogUtils.d("ProgressController", "User confirmed overwrite for file: " + finalFileName);
                    finalConfirmAction.run();
                }).setNegativeButton(R.string.cancel, (dialog, which) -> {
                    // User cancelled
                    LogUtils.d("ProgressController", "User cancelled file exists dialog for: " + finalFileName);
                    finalCancelAction.run();
                }).setCancelable(false).show();
            } catch (Exception e) {
                LogUtils.e("ProgressController", "Error showing file exists dialog: " + e.getMessage());
                finalCancelAction.run();
            }
        });
    }

    /**
     * Sets whether the ZIP buttons are enabled.
     *
     * @param enabled Whether the ZIP buttons are enabled
     */
    @Override
    public void setZipButtonsEnabled(boolean enabled) {
        LogUtils.d("ProgressController", "ZIP buttons enabled state: " + enabled);
        // Ensure this runs on the UI thread
        final boolean finalEnabled = enabled;
        activity.runOnUiThread(() -> {
            // This is a placeholder for now, as the actual implementation depends on the UI
        });
    }

    /**
     * Animates a success effect.
     */
    @Override
    public void animateSuccess() {
        LogUtils.d("ProgressController", "Animating success");
        // Ensure this runs on the UI thread
        activity.runOnUiThread(() -> {
            // This is a placeholder for now, as the actual implementation depends on the UI
        });
    }

    /**
     * Shows a success message.
     *
     * @param message The message to show
     */
    @Override
    public void showSuccess(String message) {
        LogUtils.d("ProgressController", "Showing success: " + message);
        // Ensure this runs on the UI thread
        final String finalMessage = message;
        activity.runOnUiThread(() -> {
            UIHelper.with(activity).message(finalMessage).success().show();
        });
    }

    /**
     * Shows an error message.
     *
     * @param title   The title of the error
     * @param message The error message
     */
    @Override
    public void showError(String title, String message) {
        LogUtils.d("ProgressController", "Showing error: " + title + " - " + message);
        // Ensure this runs on the UI thread
        final String finalTitle = title;
        final String finalMessage = message;
        activity.runOnUiThread(() -> {
            UIHelper.with(activity).message(finalTitle + ": " + finalMessage).error().show();
        });
    }

    /**
     * Shows an info message.
     *
     * @param message The message to show
     */
    @Override
    public void showInfo(String message) {
        LogUtils.d("ProgressController", "Showing info: " + message);
        // Ensure this runs on the UI thread
        final String finalMessage = message;
        activity.runOnUiThread(() -> {
            UIHelper.showInfo(activity, finalMessage);
        });
    }

    /**
     * Shows a confirmation dialog.
     *
     * @param title     The title of the dialog
     * @param message   The message to show
     * @param onConfirm The action to take when confirmed
     * @param onCancel  The action to take when canceled
     */
    @Override
    public void showConfirmation(String title, String message, Runnable onConfirm, Runnable onCancel) {
        LogUtils.d("ProgressController", "Showing confirmation dialog: " + title + " - " + message);

        if (!isActivitySafe()) {
            LogUtils.w("ProgressController", "Activity is finishing/destroyed, cancelling confirmation dialog");
            if (onCancel != null) {
                onCancel.run();
            }
            return;
        }

        // Ensure this runs on the UI thread
        final String finalTitle = title;
        final String finalMessage = message;
        final Runnable finalOnConfirm = onConfirm;
        final Runnable finalOnCancel = onCancel;

        activity.runOnUiThread(() -> {
            try {
                UIHelper.showConfirmation(activity, finalTitle, finalMessage, activity.getString(android.R.string.yes), activity.getString(android.R.string.no), finalOnConfirm, finalOnCancel);

                LogUtils.d("ProgressController", "Confirmation dialog shown");
            } catch (Exception e) {
                LogUtils.e("ProgressController", "Error showing confirmation dialog: " + e.getMessage());
                if (finalOnCancel != null) {
                    finalOnCancel.run();
                }
            }
        });
    }

    /**
     * Shows a search progress dialog.
     */
    public void showSearchProgressDialog() {
        LogUtils.d("ProgressController", "Showing search progress dialog");

        if (!isActivitySafe()) return;

        // Ensure this runs on the UI thread
        activity.runOnUiThread(() -> {
            try {
                if (searchProgressDialog != null && searchProgressDialog.isShowing()) {
                    searchProgressDialog.dismiss();
                }

                // Inflate custom progress dialog layout
                View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_progress, null);

                TextView titleView = dialogView.findViewById(R.id.progress_title);
                TextView progressMessage = dialogView.findViewById(R.id.progress_message);
                TextView progressPercentage = dialogView.findViewById(R.id.progress_percentage);
                TextView progressDetails = dialogView.findViewById(R.id.progress_details);
                ProgressBar searchProgressBar = dialogView.findViewById(R.id.progress_bar);

                titleView.setText(activity.getString(R.string.search_title));
                progressMessage.setText(activity.getString(R.string.searching_files));
                progressPercentage.setText("");
                progressDetails.setText("");

                // Set indeterminate progress for search
                searchProgressBar.setIndeterminate(true);

                MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity);
                builder.setView(dialogView).setCancelable(false);

                // Add cancel button for search
                builder.setNegativeButton(R.string.cancel, (dialog, which) -> {
                    LogUtils.d("ProgressController", "User requested search cancellation from progress dialog");
                    // Call the search cancellation callback if it's set
                    if (searchCancellationCallback != null) {
                        searchCancellationCallback.onSearchCancelled();
                        LogUtils.d("ProgressController", "Search cancellation callback invoked");
                    } else {
                        LogUtils.w("ProgressController", "Search cancellation callback not set");
                    }
                });

                searchProgressDialog = builder.create();
                searchProgressDialog.show();

                LogUtils.d("ProgressController", "Search progress dialog shown");
            } catch (Exception e) {
                LogUtils.e("ProgressController", "Error showing search progress dialog: " + e.getMessage());
            }
        });
    }

    /**
     * Hides the search progress dialog.
     */
    public void hideSearchProgressDialog() {
        if (!isActivitySafe()) return;

        // Ensure this runs on the UI thread
        activity.runOnUiThread(() -> {
            if (searchProgressDialog != null && searchProgressDialog.isShowing()) {
                searchProgressDialog.dismiss();
                searchProgressDialog = null;
                LogUtils.d("ProgressController", "Search progress dialog hidden");
            }
        });
    }

    /**
     * Closes all active dialogs to prevent window leaks.
     * This should be called in the activity's onDestroy method.
     */
    public void closeAllDialogs() {
        // Ensure this runs on the UI thread
        activity.runOnUiThread(() -> {
            try {
                if (progressDialog != null && progressDialog.isShowing()) {
                    LogUtils.d("ProgressController", "Closing progress dialog");
                    progressDialog.dismiss();
                    resetUiProgressCache();
                    progressDialog = null;
                }

                if (searchProgressDialog != null && searchProgressDialog.isShowing()) {
                    LogUtils.d("ProgressController", "Closing search progress dialog");
                    searchProgressDialog.dismiss();
                    searchProgressDialog = null;
                }

                // Also hide loading indicator
                hideLoadingIndicator();
            } catch (Exception e) {
                LogUtils.w("ProgressController", "Error closing dialogs: " + e.getMessage());
            }
        });
    }

    public boolean isTransferDialogShowing() {
        return progressDialog != null && progressDialog.isShowing();
    }

    public void showTransferProgressDialog() {
        showDetailedProgressDialog(activity.getString(R.string.transfer_title),
                activity.getString(R.string.preparing_transfer));
    }

    public void showTransferProgressDialog(String messageOrTitle) {
        showDetailedProgressDialog(messageOrTitle != null && !messageOrTitle.isEmpty()
                        ? messageOrTitle
                        : activity.getString(R.string.transfer_title),
                activity.getString(R.string.preparing_transfer));
    }

    public void hideTransferProgressDialog() {
        hideDetailedProgressDialog();
    }


    /**
     * Checks if the activity is safe for UI operations (not finishing or destroyed).
     *
     * @return true if safe for UI operations, false otherwise
     */
    private boolean isActivitySafe() {
        if (activity instanceof AppCompatActivity appCompatActivity) {
            return !appCompatActivity.isFinishing() && !appCompatActivity.isDestroyed();
        }
        return true;
    }

    private boolean isLifecycleAtLeastStarted() {
        if (activity instanceof AppCompatActivity appCompatActivity) {
            try {
                return appCompatActivity.getLifecycle().getCurrentState()
                        .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED);
            } catch (Exception e) {
                LogUtils.w("ProgressController", "Lifecycle check failed: " + e.getMessage());
                return false;
            }
        }
        return true;
    }

    private boolean isLifecycleAtLeastResumed() {
        if (activity instanceof AppCompatActivity appCompatActivity) {
            try {
                return appCompatActivity.getLifecycle().getCurrentState()
                        .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED);
            } catch (Exception e) {
                LogUtils.w("ProgressController", "Lifecycle check failed: " + e.getMessage());
                return false;
            }
        }
        return true;
    }

    /**
     * Callback interface for search cancellation.
     */
    public interface SearchCancellationCallback {
        /**
         * Called when the user cancels a search operation.
         */
        void onSearchCancelled();
    }
}