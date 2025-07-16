package de.schliweb.sambalite.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import de.schliweb.sambalite.R;

/**
 * Utility class for managing battery optimizations.
 * Helps to exclude the app from battery optimizations
 * to enable reliable background operations.
 */
public class BatteryOptimizationUtils {

    private static final String TAG = "BatteryOptimizationUtils";

    /**
     * Checks if the app is exempt from battery optimizations
     */
    public static boolean isIgnoringBatteryOptimizations(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
        }
        return true; // Not relevant on older Android versions
    }

    /**
     * Zeigt einen Dialog zur Anfrage der Battery Optimization Ausnahme
     */
    public static void requestBatteryOptimizationExemption(FragmentActivity activity) {
        if (isIgnoringBatteryOptimizations(activity)) {
            LogUtils.d(TAG, "App is already ignoring battery optimizations");
            return;
        }

        LogUtils.d(TAG, "Requesting battery optimization exemption");

        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.battery_optimization_title))
                .setMessage(activity.getString(R.string.battery_optimization_message))
                .setPositiveButton(activity.getString(R.string.open_settings), (dialog, which) -> {
                    openBatteryOptimizationSettings(activity);
                })
                .setNegativeButton(activity.getString(R.string.later), (dialog, which) -> {
                    LogUtils.d(TAG, "Battery optimization exemption declined by user");
                })
                .setCancelable(true)
                .show();
    }

    /**
     * Opens the Battery Optimization settings
     */
    private static void openBatteryOptimizationSettings(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + context.getPackageName()));
                context.startActivity(intent);
                LogUtils.d(TAG, "Opened battery optimization settings");
            }
        } catch (Exception e) {
            LogUtils.e(TAG, "Failed to open battery optimization settings: " + e.getMessage());

            // Fallback: Open general battery settings
            try {
                Intent intent = new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS);
                context.startActivity(intent);
                LogUtils.d(TAG, "Opened general battery settings as fallback");
            } catch (Exception fallbackException) {
                LogUtils.e(TAG, "Failed to open any battery settings: " + fallbackException.getMessage());
            }
        }
    }

    /**
     * Checks if Battery Optimization dialog should be shown
     */
    public static boolean shouldShowBatteryOptimizationDialog(Context context) {
        // Only relevant on newer Android versions
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }

        // Only show if app is not yet exempted
        return !isIgnoringBatteryOptimizations(context);
    }
}
