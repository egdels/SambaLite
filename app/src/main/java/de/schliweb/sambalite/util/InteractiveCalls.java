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

import androidx.annotation.NonNull;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs SMB requests the user is waiting for (opening a folder, the upload conflict check, the
 * connection test) with a time limit.
 *
 * <p>SMBJ waits 60 seconds per request, and several requests of one operation add up: against a
 * server that stopped answering (sleeping NAS, Wi-Fi gone without a reset) a folder showed its
 * error only after about four minutes. A shorter SMBJ timeout would also cut off slow but working
 * file transfers, so instead the caller stops waiting after {@link #TIMEOUT_MS} and reports a
 * timeout; the request itself ends with SMBJ's own timeout in the background.
 */
public final class InteractiveCalls {

  /** How long the user waits for a server answer before the operation fails. */
  public static final long TIMEOUT_MS = 20_000;

  private static final ExecutorService EXECUTOR =
      Executors.newCachedThreadPool(
          r -> {
            Thread t = new Thread(r, "SmbInteractiveCall");
            t.setDaemon(true);
            return t;
          });

  private InteractiveCalls() {}

  /** Runs the call with {@link #TIMEOUT_MS}. */
  public static <T> T withTimeout(@NonNull Callable<T> call) throws Exception {
    return withTimeout(call, TIMEOUT_MS);
  }

  /**
   * Runs the call and waits at most {@code timeoutMs} for its result.
   *
   * @throws TimeoutException if the call did not finish in time; its message contains "timed out",
   *     which the error texts show as a connection problem
   * @throws Exception whatever the call threw
   */
  public static <T> T withTimeout(@NonNull Callable<T> call, long timeoutMs) throws Exception {
    Future<T> future = EXECUTOR.submit(call);
    try {
      return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      // Not interrupted: interrupting SMBJ mid-request can break the shared session
      future.cancel(false);
      throw new TimeoutException(
          "The server did not answer within " + (timeoutMs / 1000) + " s (timed out)");
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      throw e;
    }
  }
}
