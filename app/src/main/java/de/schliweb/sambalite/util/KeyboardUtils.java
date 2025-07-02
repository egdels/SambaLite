package de.schliweb.sambalite.util;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

/**
 * Utility class for managing keyboard visibility.
 */
public class KeyboardUtils {

    private KeyboardUtils() {
    }

    /**
     * Hides the keyboard.
     *
     * @param activity The activity where the keyboard is shown
     */
    public static void hideKeyboard(Activity activity) {
        LogUtils.d("KeyboardUtils", "Attempting to hide keyboard");
        if (activity == null) {
            LogUtils.w("KeyboardUtils", "Cannot hide keyboard: activity is null");
            return;
        }

        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        View view = activity.getCurrentFocus();

        if (view != null && imm != null) {
            LogUtils.d("KeyboardUtils", "Hiding keyboard for view: " + view.getClass().getSimpleName());
            // Use InputMethodManager.HIDE_IMPLICIT_ONLY flag to properly handle IME callbacks
            imm.hideSoftInputFromWindow(view.getWindowToken(), InputMethodManager.HIDE_IMPLICIT_ONLY);
        } else {
            LogUtils.d("KeyboardUtils", "Cannot hide keyboard: view or InputMethodManager is null");
        }
    }

    /**
     * Shows the keyboard for a specific view.
     *
     * @param context The context
     * @param view    The view that will receive input
     */
    public static void showKeyboard(Context context, View view) {
        LogUtils.d("KeyboardUtils", "Attempting to show keyboard");
        if (context == null || view == null) {
            LogUtils.w("KeyboardUtils", "Cannot show keyboard: context or view is null");
            return;
        }

        InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
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
