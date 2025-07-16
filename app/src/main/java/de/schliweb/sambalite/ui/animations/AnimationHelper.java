package de.schliweb.sambalite.ui.animations;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import de.schliweb.sambalite.util.LogUtils;

/**
 * Elegant animation helper for beautiful UI transitions and micro-interactions.
 * Provides smooth, Material Design-compliant animations for enhanced user experience.
 */
public class AnimationHelper {

    private static final String TAG = "AnimationHelper";

    /**
     * Success pulse animation for positive feedback.
     */
    public static void pulseSuccess(View view) {
        LogUtils.d(TAG, "Pulse success animation for view: " + view.getClass().getSimpleName());

        AnimatorSet animatorSet = new AnimatorSet();

        ObjectAnimator scaleUpX = ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.1f);
        ObjectAnimator scaleUpY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.1f);
        ObjectAnimator scaleDownX = ObjectAnimator.ofFloat(view, "scaleX", 1.1f, 1f);
        ObjectAnimator scaleDownY = ObjectAnimator.ofFloat(view, "scaleY", 1.1f, 1f);

        scaleUpX.setDuration(200);
        scaleUpY.setDuration(200);
        scaleDownX.setDuration(200);
        scaleDownY.setDuration(200);

        animatorSet.play(scaleUpX).with(scaleUpY);
        animatorSet.play(scaleDownX).with(scaleDownY).after(scaleUpX);
        animatorSet.setInterpolator(new AccelerateDecelerateInterpolator());
        animatorSet.start();
    }

}
