/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui.utils;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import de.schliweb.sambalite.R;

/**
 * Beautiful UI utilities for consistent user feedback with Material Design 3. Provides elegant,
 * chainable methods for all UI feedback patterns.
 */
public class UIHelper {

  // Material Design 3 Colors
  private static final int SUCCESS_COLOR = android.R.color.holo_green_dark;
  private static final int ERROR_COLOR = android.R.color.holo_red_dark;
  private static final int INFO_COLOR = android.R.color.holo_blue_dark;

  /**
   * Like {@link #applyHorizontalSystemInsets(View)}, but adds the insets to the left and right
   * margins, for views with their own background such as a card in the app bar.
   *
   * @param view the view to move in; its own horizontal margins are kept
   */
  public static void applyHorizontalSystemInsetsAsMargin(@NonNull View view) {
    if (!(view.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams start)) {
      return;
    }
    final int left = start.leftMargin;
    final int right = start.rightMargin;
    ViewCompat.setOnApplyWindowInsetsListener(
        view,
        (v, windowInsets) -> {
          Insets insets =
              windowInsets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          if (v.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams lp
              && (lp.leftMargin != left + insets.left || lp.rightMargin != right + insets.right)) {
            lp.leftMargin = left + insets.left;
            lp.rightMargin = right + insets.right;
            v.setLayoutParams(lp);
          }
          return windowInsets;
        });
  }

  /**
   * Pads a top bar view (toolbar, breadcrumb row) on the left and right by the system bars and the
   * display cutout, so that in landscape it lines up with the content below instead of reaching
   * under the camera cutout or the navigation bar.
   *
   * @param view the view to pad; its own horizontal padding is kept
   */
  public static void applyHorizontalSystemInsets(@NonNull View view) {
    final int left = view.getPaddingLeft();
    final int right = view.getPaddingRight();
    ViewCompat.setOnApplyWindowInsetsListener(
        view,
        (v, windowInsets) -> {
          Insets insets =
              windowInsets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          v.setPadding(
              left + insets.left, v.getPaddingTop(), right + insets.right, v.getPaddingBottom());
          return windowInsets;
        });
  }

  /** Beautiful success feedback with elegant animation. */
  public static void showSuccess(@NonNull Activity activity, @StringRes int messageRes) {
    createSnackbar(activity, activity.getString(messageRes), SUCCESS_COLOR)
        .setDuration(Snackbar.LENGTH_LONG)
        .show();
  }

  /** Beautiful error feedback with context and elegant styling. */
  public static void showError(
      @NonNull Activity activity, @StringRes int titleRes, @NonNull String details) {
    String message = buildErrorMessage(activity, titleRes, details);
    createSnackbar(activity, message, ERROR_COLOR).setDuration(Snackbar.LENGTH_LONG).show();
  }

  public static void showInfo(@NonNull Activity activity, @NonNull String message) {
    createSnackbar(activity, message, INFO_COLOR).setDuration(Snackbar.LENGTH_SHORT).show();
  }

  /** Beautiful confirmation dialogs with Material Design. */
  public static void showConfirmation(
      @NonNull Activity activity,
      @StringRes int titleRes,
      @NonNull String message,
      @Nullable Runnable onConfirm) {
    createConfirmationDialog(activity, activity.getString(titleRes), message, onConfirm).show();
  }

  /** Advanced confirmation with custom button texts. */
  public static void showConfirmation(
      @NonNull Activity activity,
      @NonNull String title,
      @NonNull String message,
      @NonNull String positiveText,
      @NonNull String negativeText,
      @NonNull Runnable onConfirm,
      @NonNull Runnable onCancel) {
    new MaterialAlertDialogBuilder(activity)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(
            positiveText,
            (dialog, which) -> {
              if (onConfirm != null) onConfirm.run();
            })
        .setNegativeButton(
            negativeText,
            (dialog, which) -> {
              if (onCancel != null) onCancel.run();
            })
        .setCancelable(false)
        .show();
  }

  /** Elegant utility methods for internal use. */
  static Snackbar createSnackbar(Activity activity, String message, @ColorRes int colorRes) {
    Snackbar snackbar =
        Snackbar.make(activity.findViewById(android.R.id.content), message, Snackbar.LENGTH_SHORT)
            .setBackgroundTint(ContextCompat.getColor(activity, colorRes))
            .setTextColor(ContextCompat.getColor(activity, android.R.color.white))
            // Error messages with a hint ("... Edit the sync to choose another folder.") need
            // more than the default two lines
            .setTextMaxLines(4);
    android.view.View fab = visibleBottomFab(activity);
    if (fab != null) {
      // Show the message above the button instead of covering it (and being covered by it)
      snackbar.setAnchorView(fab);
    }
    return snackbar;
  }

  /** The screen's visible floating action button at the bottom edge, or {@code null}. */
  @Nullable
  static android.view.View visibleBottomFab(Activity activity) {
    int[] ids = {R.id.fab, R.id.fab_add_connection, R.id.fab_select_folder};
    for (int id : ids) {
      android.view.View v = activity.findViewById(id);
      if (v != null && v.isShown()) {
        return v;
      }
    }
    return null;
  }

  private static String buildErrorMessage(
      Context context, @StringRes int titleRes, String details) {
    String title = context.getString(titleRes);
    return details != null && !details.trim().isEmpty() ? title + ": " + details : title;
  }

  private static AlertDialog createConfirmationDialog(
      Activity activity, String title, String message, Runnable onConfirm) {
    return new MaterialAlertDialogBuilder(activity)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(
            R.string.delete,
            (dialog, which) -> {
              if (onConfirm != null) onConfirm.run();
            })
        .setNegativeButton(R.string.cancel, null)
        .setCancelable(true)
        .create();
  }

  /** Creates a new fluent builder for complex UI feedback. */
  public static @NonNull Builder with(@NonNull Activity activity) {
    return new Builder(activity);
  }

  /** Fluent builder for complex UI feedback scenarios. */
  public static class Builder {
    private final Activity activity;
    private String message;
    private @ColorRes int colorRes = INFO_COLOR;
    private int duration = Snackbar.LENGTH_SHORT;
    private String actionText;
    private Runnable actionCallback;

    public Builder(@NonNull Activity activity) {
      this.activity = activity;
    }

    public @NonNull Builder message(@NonNull String message) {
      this.message = message;
      return this;
    }

    public @NonNull Builder message(@StringRes int messageRes) {
      this.message = activity.getString(messageRes);
      return this;
    }

    public @NonNull Builder success() {
      this.colorRes = SUCCESS_COLOR;
      this.duration = Snackbar.LENGTH_LONG;
      return this;
    }

    public @NonNull Builder error() {
      this.colorRes = ERROR_COLOR;
      this.duration = Snackbar.LENGTH_LONG;
      return this;
    }

    public @NonNull Builder duration(int duration) {
      this.duration = duration;
      return this;
    }

    public @NonNull Builder action(@NonNull String text, @Nullable Runnable callback) {
      this.actionText = text;
      this.actionCallback = callback;
      return this;
    }

    public @NonNull Builder action(@StringRes int textRes, @Nullable Runnable callback) {
      this.actionText = activity.getString(textRes);
      this.actionCallback = callback;
      return this;
    }

    public void show() {
      Snackbar snackbar = createSnackbar(activity, message, colorRes).setDuration(duration);

      if (actionText != null && actionCallback != null) {
        snackbar
            .setAction(actionText, v -> actionCallback.run())
            .setActionTextColor(ContextCompat.getColor(activity, android.R.color.white));
      }

      snackbar.show();
    }
  }
}
