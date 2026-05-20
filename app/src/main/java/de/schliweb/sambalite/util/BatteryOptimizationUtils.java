/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.util;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.schliweb.sambalite.R;

/**
 * Utility class for managing battery optimizations. Helps to exclude the app from battery
 * optimizations to enable reliable background operations.
 */
public class BatteryOptimizationUtils {

  private static final String TAG = "BatteryOptimizationUtils";
  private static final String PREFS_NAME = "battery_optimization";
  private static final String KEY_PROMPT_DISABLED = "battery_optimization_prompt_disabled";
  private static final String KEY_PROMPT_SNOOZED_UNTIL =
      "battery_optimization_prompt_snoozed_until";
  private static final long SNOOZE_MILLIS = 14L * 24L * 60L * 60L * 1000L;

  /** Checks if the app is exempt from battery optimizations */
  public static boolean isIgnoringBatteryOptimizations(@NonNull Context context) {
    PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    return powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
  }

  /** Zeigt einen Dialog zur Anfrage der Battery Optimization Ausnahme */
  public static void requestBatteryOptimizationExemption(@NonNull FragmentActivity activity) {
    if (isIgnoringBatteryOptimizations(activity)) {
      LogUtils.d(TAG, "App is already ignoring battery optimizations");
      return;
    }

    LogUtils.d(TAG, "Requesting battery optimization exemption");

    if (activity.isFinishing() || activity.isDestroyed()) {
      LogUtils.w(TAG, "Activity is finishing or destroyed, skipping dialog");
      return;
    }

    View dialogView =
        LayoutInflater.from(activity).inflate(R.layout.dialog_battery_optimization, null, false);

    AlertDialog dialog =
        new MaterialAlertDialogBuilder(activity).setView(dialogView).setCancelable(true).show();

    dialogView
        .findViewById(R.id.button_open_settings)
        .setOnClickListener(
            view -> {
              dialog.dismiss();
              openBatteryOptimizationSettings(activity);
            });
    dialogView
        .findViewById(R.id.button_later)
        .setOnClickListener(
            view -> {
              snoozePrompt(activity);
              LogUtils.d(TAG, "Battery optimization exemption declined by user");
              dialog.dismiss();
            });
    dialogView
        .findViewById(R.id.button_do_not_show_again)
        .setOnClickListener(
            view -> {
              disablePrompt(activity);
              LogUtils.d(TAG, "Battery optimization reminder disabled by user");
              dialog.dismiss();
            });
  }

  private static void snoozePrompt(@NonNull Context context) {
    long snoozedUntil = System.currentTimeMillis() + SNOOZE_MILLIS;
    getPreferences(context).edit().putLong(KEY_PROMPT_SNOOZED_UNTIL, snoozedUntil).apply();
  }

  private static void disablePrompt(@NonNull Context context) {
    getPreferences(context)
        .edit()
        .putBoolean(KEY_PROMPT_DISABLED, true)
        .remove(KEY_PROMPT_SNOOZED_UNTIL)
        .apply();
  }

  private static SharedPreferences getPreferences(@NonNull Context context) {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  /** Opens the Battery Optimization settings */
  @android.annotation.SuppressLint(
      "BatteryLife") // intentional: app needs reliable background SMB transfers
  private static void openBatteryOptimizationSettings(Context context) {
    try {
      Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
      intent.setData(Uri.parse("package:" + context.getPackageName()));
      context.startActivity(intent);
      LogUtils.d(TAG, "Opened battery optimization settings");
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

  /** Checks if Battery Optimization dialog should be shown */
  public static boolean shouldShowBatteryOptimizationDialog(@NonNull Context context) {
    if (isIgnoringBatteryOptimizations(context)) {
      return false;
    }

    SharedPreferences preferences = getPreferences(context);
    if (preferences.getBoolean(KEY_PROMPT_DISABLED, false)) {
      return false;
    }

    long snoozedUntil = preferences.getLong(KEY_PROMPT_SNOOZED_UNTIL, 0L);
    return System.currentTimeMillis() >= snoozedUntil;
  }
}
