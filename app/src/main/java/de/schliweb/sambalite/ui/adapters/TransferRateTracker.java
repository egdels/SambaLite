/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui.adapters;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Derives the transfer speed of running transfers from their saved progress (issue #7). The worker
 * saves {@code bytesTransferred} together with {@code updatedAt}, but in bursts: several saves can
 * follow within a few milliseconds and then none for most of a second. The speed is therefore the
 * bytes moved across a window of about {@link #WINDOW_MS}, not the rate between two saves. A
 * transfer whose bytes have not grown for {@link #STALL_AFTER_MS} counts as stalled.
 *
 * <p>Pure Java so it can be unit tested on the JVM; not thread-safe, meant to be used on the main
 * thread by {@link TransferItemAdapter}.
 */
final class TransferRateTracker {

  /** No speed known yet: too few saves, or too close together. */
  static final long UNKNOWN = -1;

  /** The transfer has not moved any bytes for {@link #STALL_AFTER_MS}. */
  static final long STALLED = -2;

  /**
   * How long a transfer may move no bytes before it counts as stalled. Long enough for opening the
   * remote file on a slow server, short enough to notice a hanging transfer.
   */
  static final long STALL_AFTER_MS = 10_000;

  /** The span the speed is measured over. */
  static final long WINDOW_MS = 3_000;

  /** The shortest span a speed is computed from; shorter ones are dominated by bursts. */
  static final long MIN_SPAN_MS = 1_000;

  /** One saved progress: bytes at a time. */
  private static final class Point {
    final long bytes;
    final long at;

    Point(long bytes, long at) {
      this.bytes = bytes;
      this.at = at;
    }
  }

  /** The saves within the window, oldest first, plus the newest one before it. */
  private final Map<Long, ArrayDeque<Point>> points = new HashMap<>();

  /**
   * Records the saved progress of a running transfer. Repeating a save, or a save without new
   * bytes, changes nothing.
   *
   * @param id the transfer
   * @param bytes the bytes transferred so far
   * @param at when the progress was saved, in milliseconds since the epoch
   */
  void record(long id, long bytes, long at) {
    ArrayDeque<Point> q = points.get(id);
    if (q == null || bytes < q.getLast().bytes) {
      // First sight, or the transfer started over (retry from the beginning)
      q = new ArrayDeque<>();
      q.add(new Point(bytes, at));
      points.put(id, q);
      return;
    }
    Point last = q.getLast();
    if (bytes == last.bytes || at <= last.at) {
      return;
    }
    q.add(new Point(bytes, at));
    // Keep one point at or before the window start, so the window is covered completely
    while (q.size() > 2) {
      Iterator<Point> it = q.iterator();
      it.next();
      if (it.next().at <= at - WINDOW_MS) {
        q.removeFirst();
      } else {
        break;
      }
    }
  }

  /**
   * The speed of a transfer.
   *
   * @param id the transfer
   * @param now the current time in milliseconds since the epoch
   * @return bytes per second, {@link #STALLED} or {@link #UNKNOWN}
   */
  long bytesPerSecond(long id, long now) {
    ArrayDeque<Point> q = points.get(id);
    if (q == null) {
      return UNKNOWN;
    }
    Point last = q.getLast();
    if (now - last.at >= STALL_AFTER_MS) {
      return STALLED;
    }
    Point first = q.getFirst();
    long span = last.at - first.at;
    if (span < MIN_SPAN_MS) {
      return UNKNOWN;
    }
    return (last.bytes - first.bytes) * 1000 / span;
  }

  /** Forgets every transfer that is not in {@code ids}, e.g. finished ones. */
  void retainOnly(Set<Long> ids) {
    points.keySet().removeIf(id -> !ids.contains(id));
  }
}
