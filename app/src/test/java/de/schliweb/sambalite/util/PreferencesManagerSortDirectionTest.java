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

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import de.schliweb.sambalite.ui.FileSortOption;
import de.schliweb.sambalite.ui.SortDirection;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests for the persisted sort direction (issue #36). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PreferencesManagerSortDirectionTest {

  private PreferencesManager preferences;

  @Before
  public void setUp() {
    Context context = ApplicationProvider.getApplicationContext();
    context.getSharedPreferences("de.schliweb.sambalite.preferences", Context.MODE_PRIVATE)
        .edit()
        .clear()
        .commit();
    preferences = new PreferencesManager(context);
  }

  @Test
  public void withoutSavedDirection_existingUsersKeepTheirOrder() {
    preferences.saveSortOption(FileSortOption.DATE);
    assertEquals(SortDirection.DESCENDING, preferences.getSortDirection());

    preferences.saveSortOption(FileSortOption.NAME);
    assertEquals(SortDirection.ASCENDING, preferences.getSortDirection());
  }

  @Test
  public void savedDirection_survivesARestart() {
    preferences.saveSortOption(FileSortOption.DATE);
    preferences.saveSortDirection(SortDirection.ASCENDING);

    PreferencesManager reopened =
        new PreferencesManager(ApplicationProvider.getApplicationContext());
    assertEquals(FileSortOption.DATE, reopened.getSortOption());
    assertEquals(SortDirection.ASCENDING, reopened.getSortDirection());
  }
}
