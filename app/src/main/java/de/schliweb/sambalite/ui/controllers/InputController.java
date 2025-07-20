package de.schliweb.sambalite.ui.controllers;

import android.view.MotionEvent;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import de.schliweb.sambalite.util.KeyboardUtils;
import de.schliweb.sambalite.util.LogUtils;

/**
 * Controller for handling keyboard and focus management.
 * This controller is responsible for hiding the keyboard, clearing focus,
 * and handling touch events outside of input fields.
 */
public class InputController {

    private final AppCompatActivity activity;

    /**
     * Creates a new InputController.
     *
     * @param activity The activity
     */
    public InputController(AppCompatActivity activity) {
        this.activity = activity;
        LogUtils.d("InputController", "InputController initialized");
    }

    /**
     * Hides the keyboard and clears focus from input fields.
     */
    public void hideKeyboardAndClearFocus() {
        View currentFocus = activity.getCurrentFocus();
        if (currentFocus != null) {
            currentFocus.clearFocus();
        }
        KeyboardUtils.hideKeyboard(activity);
        LogUtils.d("InputController", "Keyboard hidden and focus cleared");
    }

    /**
     * Handles touch events to hide the keyboard when touching outside of input fields.
     *
     * @param event The touch event
     * @return true if the event was handled, false otherwise
     */
    public boolean handleTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            View v = activity.getCurrentFocus();
            if (v instanceof com.google.android.material.textfield.TextInputEditText) {
                LogUtils.d("InputController", "Touch event on TextInputEditText");
                // Check if the touch was outside the focused text field
                float x = event.getRawX();
                float y = event.getRawY();
                int[] location = new int[2];
                v.getLocationOnScreen(location);

                if (x < location[0] || x > location[0] + v.getWidth() || y < location[1] || y > location[1] + v.getHeight()) {
                    LogUtils.d("InputController", "Touch outside of text field, hiding keyboard");
                    // Touch was outside the text field, hide keyboard
                    // Clear focus first to ensure proper IME callback handling
                    v.clearFocus();
                    KeyboardUtils.hideKeyboard(activity);
                    return true;
                }
            }
        }
        return false;
    }
}