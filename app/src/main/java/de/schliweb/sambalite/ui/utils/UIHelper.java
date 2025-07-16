package de.schliweb.sambalite.ui.utils;

import android.app.Activity;
import android.content.Context;
import androidx.annotation.ColorRes;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import de.schliweb.sambalite.R;

/**
 * Beautiful UI utilities for consistent user feedback with Material Design 3.
 * Provides elegant, chainable methods for all UI feedback patterns.
 */
public class UIHelper {

    // Material Design 3 Colors
    private static final int SUCCESS_COLOR = android.R.color.holo_green_dark;
    private static final int ERROR_COLOR = android.R.color.holo_red_dark;
    private static final int INFO_COLOR = android.R.color.holo_blue_dark;

    /**
     * Beautiful success feedback with elegant animation.
     */
    public static void showSuccess(Activity activity, @StringRes int messageRes) {
        createSnackbar(activity, activity.getString(messageRes), SUCCESS_COLOR).setDuration(Snackbar.LENGTH_LONG).show();
    }

    /**
     * Beautiful error feedback with context and elegant styling.
     */
    public static void showError(Activity activity, @StringRes int titleRes, String details) {
        String message = buildErrorMessage(activity, titleRes, details);
        createSnackbar(activity, message, ERROR_COLOR).setDuration(Snackbar.LENGTH_LONG).show();
    }

    public static void showInfo(Activity activity, String message) {
        createSnackbar(activity, message, INFO_COLOR).setDuration(Snackbar.LENGTH_SHORT).show();
    }

    /**
     * Beautiful confirmation dialogs with Material Design.
     */
    public static void showConfirmation(Activity activity, @StringRes int titleRes, String message, Runnable onConfirm) {
        createConfirmationDialog(activity, activity.getString(titleRes), message, onConfirm).show();
    }

    /**
     * Advanced confirmation with custom button texts.
     */
    public static void showConfirmation(Activity activity, String title, String message, String positiveText, String negativeText, Runnable onConfirm, Runnable onCancel) {
        new MaterialAlertDialogBuilder(activity).setTitle(title).setMessage(message).setPositiveButton(positiveText, (dialog, which) -> {
            if (onConfirm != null) onConfirm.run();
        }).setNegativeButton(negativeText, (dialog, which) -> {
            if (onCancel != null) onCancel.run();
        }).setCancelable(false).show();
    }

    /**
     * Elegant utility methods for internal use.
     */
    private static Snackbar createSnackbar(Activity activity, String message, @ColorRes int colorRes) {
        return Snackbar.make(activity.findViewById(android.R.id.content), message, Snackbar.LENGTH_SHORT).setBackgroundTint(ContextCompat.getColor(activity, colorRes)).setTextColor(ContextCompat.getColor(activity, android.R.color.white));
    }

    private static String buildErrorMessage(Context context, @StringRes int titleRes, String details) {
        String title = context.getString(titleRes);
        return details != null && !details.trim().isEmpty() ? title + ": " + details : title;
    }

    private static AlertDialog createConfirmationDialog(Activity activity, String title, String message, Runnable onConfirm) {
        return new MaterialAlertDialogBuilder(activity).setTitle(title).setMessage(message).setPositiveButton(R.string.delete, (dialog, which) -> {
            if (onConfirm != null) onConfirm.run();
        }).setNegativeButton(R.string.cancel, null).setCancelable(true).create();
    }

    /**
     * Creates a new fluent builder for complex UI feedback.
     */
    public static Builder with(Activity activity) {
        return new Builder(activity);
    }

    /**
     * Fluent builder for complex UI feedback scenarios.
     */
    public static class Builder {
        private final Activity activity;
        private String message;
        private @ColorRes int colorRes = INFO_COLOR;
        private int duration = Snackbar.LENGTH_SHORT;
        private String actionText;
        private Runnable actionCallback;

        public Builder(Activity activity) {
            this.activity = activity;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder message(@StringRes int messageRes) {
            this.message = activity.getString(messageRes);
            return this;
        }

        public Builder success() {
            this.colorRes = SUCCESS_COLOR;
            this.duration = Snackbar.LENGTH_LONG;
            return this;
        }

        public Builder error() {
            this.colorRes = ERROR_COLOR;
            this.duration = Snackbar.LENGTH_LONG;
            return this;
        }

        public Builder duration(int duration) {
            this.duration = duration;
            return this;
        }

        public Builder action(String text, Runnable callback) {
            this.actionText = text;
            this.actionCallback = callback;
            return this;
        }

        public Builder action(@StringRes int textRes, Runnable callback) {
            this.actionText = activity.getString(textRes);
            this.actionCallback = callback;
            return this;
        }

        public void show() {
            Snackbar snackbar = createSnackbar(activity, message, colorRes).setDuration(duration);

            if (actionText != null && actionCallback != null) {
                snackbar.setAction(actionText, v -> actionCallback.run()).setActionTextColor(ContextCompat.getColor(activity, android.R.color.white));
            }

            snackbar.show();
        }
    }
}
