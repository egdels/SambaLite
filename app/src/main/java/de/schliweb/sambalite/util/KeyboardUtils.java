package de.schliweb.sambalite.util;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import androidx.annotation.NonNull;

/** Utility class for managing keyboard visibility. */
public class KeyboardUtils {

  private KeyboardUtils() {}

  /**
   * Hides the keyboard.
   *
   * @param activity The activity where the keyboard is shown
   */
  public static void hideKeyboard(@NonNull Activity activity) {
    LogUtils.d("KeyboardUtils", "Attempting to hide keyboard");
    if (activity == null) {
      LogUtils.w("KeyboardUtils", "Cannot hide keyboard: activity is null");
      return;
    }

    InputMethodManager imm =
        (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
    View view = activity.getCurrentFocus();

    if (imm != null) {
      if (view != null) {
        LogUtils.d("KeyboardUtils", "Hiding keyboard for view: " + view.getClass().getSimpleName());
        view.clearFocus();
        imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
      } else {
        LogUtils.d("KeyboardUtils", "No focused view, hiding keyboard from decor view");
        View decorView = activity.getWindow().getDecorView();
        imm.hideSoftInputFromWindow(decorView.getWindowToken(), 0);
      }
    } else {
      LogUtils.d("KeyboardUtils", "Cannot hide keyboard: InputMethodManager is null");
    }
  }

  /**
   * Hides the keyboard for a dialog. Uses the dialog's window to find the correct view.
   *
   * @param dialog The dialog where the keyboard is shown
   */
  public static void hideKeyboard(@NonNull Dialog dialog) {
    LogUtils.d("KeyboardUtils", "Attempting to hide keyboard for dialog");
    if (dialog == null) {
      LogUtils.w("KeyboardUtils", "Cannot hide keyboard: dialog is null");
      return;
    }

    Window window = dialog.getWindow();
    if (window == null) {
      LogUtils.w("KeyboardUtils", "Cannot hide keyboard: dialog window is null");
      return;
    }

    View decorView = window.getDecorView();
    InputMethodManager imm =
        (InputMethodManager) decorView.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    if (imm != null) {
      View focusedView = window.getCurrentFocus();
      if (focusedView != null) {
        LogUtils.d(
            "KeyboardUtils",
            "Hiding keyboard for dialog view: " + focusedView.getClass().getSimpleName());
        focusedView.clearFocus();
        imm.hideSoftInputFromWindow(focusedView.getWindowToken(), 0);
      } else {
        LogUtils.d("KeyboardUtils", "Hiding keyboard from dialog decor view");
        imm.hideSoftInputFromWindow(decorView.getWindowToken(), 0);
      }
    }
  }

  /**
   * Shows the keyboard for a specific view.
   *
   * @param context The context
   * @param view The view that will receive input
   */
  public static void showKeyboard(@NonNull Context context, @NonNull View view) {
    LogUtils.d("KeyboardUtils", "Attempting to show keyboard");
    if (context == null || view == null) {
      LogUtils.w("KeyboardUtils", "Cannot show keyboard: context or view is null");
      return;
    }

    InputMethodManager imm =
        (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
    if (imm != null) {
      LogUtils.d("KeyboardUtils", "Showing keyboard for view: " + view.getClass().getSimpleName());
      view.requestFocus();
      // Use InputMethodManager.SHOW_IMPLICIT flag to properly handle IME callbacks
      // This is already correct, but adding a comment for clarity
      imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
    } else {
      LogUtils.d("KeyboardUtils", "Cannot show keyboard: InputMethodManager is null");
    }
  }
}
