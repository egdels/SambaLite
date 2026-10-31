/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.color.MaterialColors;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows a display path such as {@code share/photos/2026} as tappable segments ({@code share ›
 * photos › 2026}). Tapping a segment other than the last reports its depth: 0 is the share root, 1
 * the first folder below it, and so on. The row scrolls horizontally and keeps the current folder
 * in view.
 */
public class BreadcrumbView extends HorizontalScrollView {

  /** Receives taps on a parent segment. */
  public interface OnSegmentClickListener {
    /**
     * Called when a parent segment is tapped.
     *
     * @param depth 0 for the share root, n for the n-th folder below it
     */
    void onSegmentClick(int depth);
  }

  /** Widest a single segment gets before its name is shortened in the middle. */
  private static final int MAX_SEGMENT_DP = 220;

  private final LinearLayout row;
  @Nullable private OnSegmentClickListener listener;
  @NonNull private List<String> segments = new ArrayList<>();

  public BreadcrumbView(@NonNull Context context) {
    this(context, null);
  }

  public BreadcrumbView(@NonNull Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    setHorizontalScrollBarEnabled(false);
    setFillViewport(true);
    // Fade out the edge where segments are scrolled out of view, so hidden parents are noticed
    setHorizontalFadingEdgeEnabled(true);
    setFadingEdgeLength(Math.round(32 * context.getResources().getDisplayMetrics().density));
    row = new LinearLayout(context);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    // Padding on the row, not on the scroll view, so scrolling to the end shows the last segment
    // completely instead of under the fading edge
    int pad = Math.round(8 * context.getResources().getDisplayMetrics().density);
    row.setPadding(pad, 0, pad, 0);
    addView(row, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
  }

  public void setOnSegmentClickListener(@Nullable OnSegmentClickListener listener) {
    this.listener = listener;
  }

  /** The segments currently shown, share name first. */
  @NonNull
  public List<String> getSegments() {
    return new ArrayList<>(segments);
  }

  /**
   * Shows the given display path.
   *
   * @param displayPath share name followed by the folders below it, separated by '/'
   */
  public void setPath(@Nullable String displayPath) {
    segments = splitPath(displayPath);
    row.removeAllViews();
    for (int i = 0; i < segments.size(); i++) {
      if (i > 0) {
        row.addView(separator());
      }
      row.addView(segment(segments.get(i), i, i == segments.size() - 1));
    }
    // Keep the current folder visible when the path is longer than the screen
    post(() -> fullScroll(FOCUS_RIGHT));
  }

  /** Splits a display path into its non-empty segments. */
  @NonNull
  static List<String> splitPath(@Nullable String displayPath) {
    List<String> result = new ArrayList<>();
    if (displayPath == null) {
      return result;
    }
    for (String part : displayPath.split("/")) {
      if (!part.isEmpty()) {
        result.add(part);
      }
    }
    return result;
  }

  private TextView segment(String name, int depth, boolean current) {
    TextView view = new TextView(getContext());
    view.setSingleLine(true);
    view.setGravity(Gravity.CENTER_VERTICAL);
    view.setMinHeight(dp(40));
    view.setPadding(dp(8), 0, dp(8), 0);
    view.setTextAppearance(
        current
            ? com.google.android.material.R.style.TextAppearance_Material3_TitleSmall
            : com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    // Long names are shortened in the middle to at most MAX_SEGMENT_DP. Shortened here rather
    // than with setEllipsize + setMaxWidth, which keeps the view at its full maximum width and
    // leaves a gap after the shortened text. The full name stays available to screen readers.
    view.setText(
        TextUtils.ellipsize(
            name, view.getPaint(), dp(MAX_SEGMENT_DP), TextUtils.TruncateAt.MIDDLE));
    view.setContentDescription(name);
    view.setTextColor(
        MaterialColors.getColor(
            this,
            current
                ? com.google.android.material.R.attr.colorOnSurface
                : androidx.appcompat.R.attr.colorPrimary));
    if (!current) {
      TypedValue ripple = new TypedValue();
      getContext()
          .getTheme()
          .resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
      view.setBackgroundResource(ripple.resourceId);
      view.setOnClickListener(
          v -> {
            if (listener != null) {
              listener.onSegmentClick(depth);
            }
          });
    }
    return view;
  }

  private TextView separator() {
    TextView view = new TextView(getContext());
    view.setText("›");
    view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    view.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    view.setTextColor(
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant));
    return view;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
