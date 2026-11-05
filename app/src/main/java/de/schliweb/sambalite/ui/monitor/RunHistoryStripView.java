/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui.monitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.sync.SyncRunResult;
import java.util.ArrayList;
import java.util.List;

/**
 * A row of dots, one per stored run of a sync, oldest on the left: the color shows how each run
 * ended, so patterns such as "fails every night" stand out (#50).
 */
public class RunHistoryStripView extends View {

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final List<Integer> colors = new ArrayList<>();
  private final float dot;
  private final float gap;
  private final int maxDots;

  public RunHistoryStripView(@NonNull Context context) {
    this(context, null);
  }

  public RunHistoryStripView(@NonNull Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    float density = getResources().getDisplayMetrics().density;
    dot = 10 * density;
    gap = 4 * density;
    maxDots = 20;
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
  }

  /** Shows the given results, newest first as stored. */
  public void setRuns(@NonNull List<SyncRunResult> newestFirst) {
    colors.clear();
    int n = Math.min(maxDots, newestFirst.size());
    for (int i = n - 1; i >= 0; i--) {
      colors.add(
          ContextCompat.getColor(
              getContext(), SyncStatusTexts.dotColor(newestFirst.get(i).getStatus())));
    }
    requestLayout();
    invalidate();
  }

  /** Marks the newest dot as a run in progress. */
  public void addRunning() {
    colors.add(ContextCompat.getColor(getContext(), R.color.sync_status_running_dot));
    while (colors.size() > maxDots) colors.remove(0);
    requestLayout();
    invalidate();
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    int n = Math.max(1, colors.size());
    int width = (int) Math.ceil(n * dot + (n - 1) * gap) + getPaddingLeft() + getPaddingRight();
    int height = (int) Math.ceil(dot) + getPaddingTop() + getPaddingBottom();
    setMeasuredDimension(
        resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec));
  }

  @Override
  protected void onDraw(@NonNull Canvas canvas) {
    super.onDraw(canvas);
    float radius = dot / 2f;
    float cy = getPaddingTop() + radius;
    float x = getPaddingLeft() + radius;
    for (int color : colors) {
      paint.setColor(color);
      canvas.drawCircle(x, cy, radius, paint);
      x += dot + gap;
    }
  }
}
