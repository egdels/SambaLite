/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui.utils;

import static org.junit.Assert.*;

import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;
import com.google.android.material.snackbar.Snackbar;
import de.schliweb.sambalite.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Snackbars are placed above a visible "New"/"Add Connection" button instead of covering it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class UIHelperSnackbarAnchorTest {

  private static Activity activityWithFab(int visibility) {
    Activity activity =
        Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity.class).setup().get();
    activity.setTheme(R.style.Theme_SambaLite);
    FrameLayout root = new FrameLayout(activity);
    View fab = new View(activity);
    fab.setId(R.id.fab);
    fab.setVisibility(visibility);
    root.addView(fab);
    activity.setContentView(root);
    return activity;
  }

  @Test
  public void visibleFab_isTheAnchor() {
    Activity activity = activityWithFab(View.VISIBLE);
    Snackbar snackbar = UIHelper.createSnackbar(activity, "Upload complete", android.R.color.white);
    assertSame(activity.findViewById(R.id.fab), snackbar.getAnchorView());
  }

  @Test
  public void hiddenFab_noAnchor() {
    Activity activity = activityWithFab(View.GONE);
    Snackbar snackbar = UIHelper.createSnackbar(activity, "Upload complete", android.R.color.white);
    assertNull(snackbar.getAnchorView());
  }
}
