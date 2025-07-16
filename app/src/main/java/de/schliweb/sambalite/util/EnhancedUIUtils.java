package de.schliweb.sambalite.util;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Toast;

/**
 * Enhanced UI utilities for SambaLite.
 * Provides better user experience with animations and feedback.
 */
public class EnhancedUIUtils {

    private static final String TAG = "EnhancedUIUtils";
    private static final int ANIMATION_DURATION_MEDIUM = 400;

    /**
     * Shows a toast with enhanced styling and positioning.
     */
    public static void showEnhancedToast(Context context, String message, int duration) {
        if (context == null || message == null) return;

        try {
            Toast toast = Toast.makeText(context, message, duration);
            toast.show();
            LogUtils.d(TAG, "Showed toast: " + message);
        } catch (Exception e) {
            LogUtils.e(TAG, "Failed to show toast: " + e.getMessage());
        }
    }

    /**
     * Shows error message to user.
     */
    public static void showError(Context context, String message) {
        showEnhancedToast(context, "✗ " + message, Toast.LENGTH_LONG);
    }

    /**
     * Shows info message to user.
     */
    public static void showInfo(Context context, String message) {
        showEnhancedToast(context, "ℹ " + message, Toast.LENGTH_SHORT);
    }

    /**
     * Animates view scale up (pop-in effect).
     */
    public static void scaleUp(View view) {
        if (view == null) return;

        view.setScaleX(0.8f);
        view.setScaleY(0.8f);
        view.setAlpha(0.7f);
        view.setVisibility(View.VISIBLE);

        AnimatorSet animatorSet = new AnimatorSet();
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(view, "scaleX", 0.8f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(view, "scaleY", 0.8f, 1f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(view, "alpha", 0.7f, 1f);

        animatorSet.playTogether(scaleX, scaleY, alpha);
        animatorSet.setDuration(ANIMATION_DURATION_MEDIUM);
        animatorSet.setInterpolator(new AccelerateDecelerateInterpolator());
        animatorSet.start();

        LogUtils.d(TAG, "Started scale up animation for view");
    }


    /**
     * Creates ripple effect on view touch.
     */
    public static void addRippleEffect(View view) {
        if (view == null) return;

        // Simple scale animation for ripple-like effect
        view.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    ObjectAnimator scaleDownX = ObjectAnimator.ofFloat(v, "scaleX", 1f, 0.95f);
                    ObjectAnimator scaleDownY = ObjectAnimator.ofFloat(v, "scaleY", 1f, 0.95f);
                    scaleDownX.setDuration(100);
                    scaleDownY.setDuration(100);
                    scaleDownX.start();
                    scaleDownY.start();
                    break;

                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    ObjectAnimator scaleUpX = ObjectAnimator.ofFloat(v, "scaleX", 0.95f, 1f);
                    ObjectAnimator scaleUpY = ObjectAnimator.ofFloat(v, "scaleY", 0.95f, 1f);
                    scaleUpX.setDuration(100);
                    scaleUpY.setDuration(100);
                    scaleUpX.start();
                    scaleUpY.start();
                    break;
            }
            return false; // Don't consume the event
        });

        LogUtils.d(TAG, "Added ripple effect to view");
    }

    /**
     * Safely gets view by ID with null check.
     */
    public static <T extends View> T findViewById(View parent, int id, Class<T> type) {
        if (parent == null) return null;

        try {
            View view = parent.findViewById(id);
            if (view != null && type.isInstance(view)) {
                return type.cast(view);
            }
        } catch (Exception e) {
            LogUtils.e(TAG, "Failed to find view: " + e.getMessage());
        }

        return null;
    }
}
