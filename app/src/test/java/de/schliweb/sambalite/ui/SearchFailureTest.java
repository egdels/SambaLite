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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.content.res.Configuration;
import androidx.test.core.app.ApplicationProvider;
import androidx.work.Data;
import androidx.work.WorkInfo;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.search.SearchWorker;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** A failed search is reported as failure, and result counts use plural forms. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SearchFailureTest {

  private static WorkInfo info(UUID id, WorkInfo.State state, Data output) {
    return new WorkInfo(id, state, Collections.emptySet(), output);
  }

  @Test
  public void failureOf_failedCurrentWork_returnsError() {
    UUID id = UUID.randomUUID();
    Data output =
        new Data.Builder().putString(SearchWorker.KEY_ERROR, "Connection refused").build();

    assertEquals(
        "Connection refused",
        SearchViewModel.failureOf(List.of(info(id, WorkInfo.State.FAILED, output)), id));
  }

  @Test
  public void failureOf_failedWithoutError_returnsEmpty() {
    UUID id = UUID.randomUUID();

    assertEquals(
        "", SearchViewModel.failureOf(List.of(info(id, WorkInfo.State.FAILED, Data.EMPTY)), id));
  }

  @Test
  public void failureOf_succeededOrOtherWork_returnsNull() {
    UUID current = UUID.randomUUID();
    UUID older = UUID.randomUUID();

    assertNull(
        SearchViewModel.failureOf(
            List.of(
                info(current, WorkInfo.State.SUCCEEDED, Data.EMPTY),
                info(older, WorkInfo.State.FAILED, Data.EMPTY)),
            current));
    assertNull(
        SearchViewModel.failureOf(
            List.of(info(current, WorkInfo.State.FAILED, Data.EMPTY)), null));
  }

  @Test
  public void resultCount_usesPluralForms() {
    Context context = ApplicationProvider.getApplicationContext();
    assertEquals(
        "1 result found",
        context.getResources().getQuantityString(R.plurals.search_result_count, 1, 1));
    assertEquals(
        "5 results found",
        context.getResources().getQuantityString(R.plurals.search_result_count, 5, 5));

    Configuration config = new Configuration(context.getResources().getConfiguration());
    config.setLocale(new Locale("pl"));
    Context polish = context.createConfigurationContext(config);
    assertEquals(
        "Znaleziono 3 wyniki",
        polish.getResources().getQuantityString(R.plurals.search_result_count, 3, 3));
    assertEquals(
        "Znaleziono 5 wyników",
        polish.getResources().getQuantityString(R.plurals.search_result_count, 5, 5));
  }
}
