package de.schliweb.sambalite.ui.utils;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.util.LogUtils;

/**
 * Unified loading indicator utility for SambaLite app.
 * Provides consistent, Material Design loading indicators throughout the app.
 * Consolidates all different loading implementations into a single, elegant solution.
 */
public class LoadingIndicator implements DefaultLifecycleObserver {

    private static final String TAG = "LoadingIndicator";
    private final Activity activity;
    private AlertDialog progressDialog;
    private boolean isShowing = false;

    /**
     * Creates a new loading indicator instance for the given activity.
     */
    public LoadingIndicator(Activity activity) {
        this.activity = activity;

        // Register lifecycle observer to handle activity lifecycle events
        if (activity instanceof androidx.lifecycle.LifecycleOwner) {
            ((androidx.lifecycle.LifecycleOwner) activity).getLifecycle().addObserver(this);
        }
    }

    /**
     * Creates a new builder for fluent loading indicator creation.
     */
    public static Builder with(Activity activity) {
        return new Builder(activity);
    }

    /**
     * Quick static method for simple loading dialogs.
     */
    public static LoadingIndicator showSimple(Activity activity, @StringRes int messageRes) {
        return showSimple(activity, activity.getString(messageRes));
    }

    /**
     * Quick static method for simple loading dialogs.
     */
    public static LoadingIndicator showSimple(Activity activity, String message) {
        LoadingIndicator indicator = new LoadingIndicator(activity);
        indicator.show(message);
        return indicator;
    }

    /**
     * Shows an indeterminate loading dialog with a message.
     */
    public void show(@StringRes int messageRes) {
        show(activity.getString(messageRes), false, null);
    }

    /**
     * Shows an indeterminate loading dialog with a message.
     */
    public void show(String message) {
        show(message, false, null);
    }

    /**
     * Shows a loading dialog with optional cancellation.
     */
    public void show(@StringRes int messageRes, boolean cancelable, Runnable onCancel) {
        show(activity.getString(messageRes), cancelable, onCancel);
    }

    /**
     * Shows a loading dialog with optional cancellation.
     */
    public void show(String message, boolean cancelable, Runnable onCancel) {
        LogUtils.d(TAG, "Showing loading dialog: " + message);

        hide(); // Hide any existing dialog

        // Create modern Material Design loading dialog
        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_loading, null);

        TextView messageText = dialogView.findViewById(R.id.loading_message);
        messageText.setText(message);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity).setView(dialogView).setCancelable(false); // Always false - only Cancel button can close dialog

        if (cancelable && onCancel != null) {
            builder.setNegativeButton(R.string.cancel, (dialog, which) -> {
                LogUtils.d(TAG, "Loading dialog cancelled by user");
                onCancel.run();
                hide();
            });
        }

        progressDialog = builder.create();

        // Prevent dialog from being closed by clicking outside
        progressDialog.setCanceledOnTouchOutside(false);

        progressDialog.show();
        isShowing = true;
    }

    /**
     * Updates the message of the currently showing dialog.
     */
    public void updateMessage(String message) {
        if (isShowing && progressDialog != null) {
            LogUtils.d(TAG, "Updating loading message: " + message);
            TextView messageText = progressDialog.findViewById(R.id.loading_message);
            if (messageText != null) {
                messageText.setText(message);
            }
        }
    }

    /**
     * Hides the loading dialog.
     */
    public void hide() {
        if (isShowing && progressDialog != null) {
            LogUtils.d(TAG, "Hiding loading dialog");
            progressDialog.dismiss();
            progressDialog = null;
            isShowing = false;
        }
    }

    /**
     * Checks if the loading dialog is currently showing.
     */
    public boolean isShowing() {
        return isShowing && progressDialog != null && progressDialog.isShowing();
    }

    /**
     * Enables or disables the cancel button if present.
     */
    public void setCancelButtonEnabled(boolean enabled) {
        if (progressDialog != null) {
            LogUtils.d(TAG, "Setting cancel button enabled: " + enabled);
            android.widget.Button cancelButton = progressDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (cancelButton != null) {
                cancelButton.setEnabled(enabled);
                cancelButton.setAlpha(enabled ? 1.0f : 0.5f); // Visual feedback
            }
        }
    }

    /**
     * Modern lifecycle observer method to handle activity destruction.
     */
    @Override
    public void onDestroy(LifecycleOwner owner) {
        LogUtils.d(TAG, "Activity destroyed, cleaning up loading dialog");
        hide();
    }

    /**
     * Static utility methods for quick access.
     */
    public static class Builder {
        private final Activity activity;
        private String message;
        private boolean cancelable = false;
        private Runnable onCancel;

        public Builder(Activity activity) {
            this.activity = activity;
        }

        public Builder message(@StringRes int messageRes) {
            this.message = activity.getString(messageRes);
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder cancelable(boolean cancelable) {
            this.cancelable = cancelable;
            return this;
        }

        public LoadingIndicator show() {
            LoadingIndicator indicator = new LoadingIndicator(activity);
            indicator.show(message, cancelable, onCancel);
            return indicator;
        }
    }
}
