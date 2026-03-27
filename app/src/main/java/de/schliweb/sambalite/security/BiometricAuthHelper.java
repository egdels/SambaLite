/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.security;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import de.schliweb.sambalite.util.LogUtils;

/**
 * Helper class for biometric and device credential authentication. Provides methods to check
 * availability and trigger authentication prompts using biometrics or device PIN/pattern.
 */
public class BiometricAuthHelper {

  private static final String TAG = "BiometricAuthHelper";
  private static final int ALLOWED_AUTHENTICATORS =
      BiometricManager.Authenticators.BIOMETRIC_STRONG
          | BiometricManager.Authenticators.BIOMETRIC_WEAK
          | BiometricManager.Authenticators.DEVICE_CREDENTIAL;

  /** Callback interface for authentication results. */
  public interface AuthCallback {
    void onAuthSuccess();

    void onAuthFailure(String errorMessage);

    void onAuthCancelled();
  }

  private BiometricAuthHelper() {}

  /**
   * Checks whether device authentication (biometrics or PIN/pattern) is available.
   *
   * @param context The application context
   * @return true if device authentication is available
   */
  public static boolean isDeviceAuthAvailable(@NonNull Context context) {
    BiometricManager biometricManager = BiometricManager.from(context);
    int result = biometricManager.canAuthenticate(ALLOWED_AUTHENTICATORS);
    boolean available = result == BiometricManager.BIOMETRIC_SUCCESS;
    LogUtils.d(TAG, "Device auth available: " + available + " (result code: " + result + ")");
    return available;
  }

  /**
   * Shows a biometric/device credential authentication prompt.
   *
   * @param activity The FragmentActivity to attach the prompt to
   * @param title The title shown in the authentication dialog
   * @param subtitle The subtitle shown in the authentication dialog
   * @param callback The callback for authentication results
   */
  public static void authenticate(
      @NonNull FragmentActivity activity,
      @NonNull String title,
      @NonNull String subtitle,
      @NonNull AuthCallback callback) {
    LogUtils.d(TAG, "Starting authentication prompt");

    if (!isDeviceAuthAvailable(activity)) {
      LogUtils.w(TAG, "Device authentication not available");
      callback.onAuthFailure("No device authentication configured");
      return;
    }

    BiometricPrompt.AuthenticationCallback authCallback =
        new BiometricPrompt.AuthenticationCallback() {
          @Override
          public void onAuthenticationSucceeded(
              @NonNull BiometricPrompt.AuthenticationResult result) {
            super.onAuthenticationSucceeded(result);
            LogUtils.i(TAG, "Authentication succeeded");
            callback.onAuthSuccess();
          }

          @Override
          public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
            super.onAuthenticationError(errorCode, errString);
            LogUtils.w(TAG, "Authentication error: " + errorCode + " - " + errString);
            if (errorCode == BiometricPrompt.ERROR_USER_CANCELED
                || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                || errorCode == BiometricPrompt.ERROR_CANCELED) {
              callback.onAuthCancelled();
            } else {
              callback.onAuthFailure(errString.toString());
            }
          }

          @Override
          public void onAuthenticationFailed() {
            super.onAuthenticationFailed();
            LogUtils.d(TAG, "Authentication attempt failed (biometric not recognized)");
          }
        };

    BiometricPrompt biometricPrompt =
        new BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), authCallback);

    BiometricPrompt.PromptInfo promptInfo =
        new BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
            .build();

    biometricPrompt.authenticate(promptInfo);
  }
}
