/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import java.util.ArrayList;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests the remaining budget of persisted SAF grants under Android's per-app cap. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PersistedGrantBudgetTest {

  private Context context;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    releaseAllGrants();
  }

  @After
  public void tearDown() {
    releaseAllGrants();
  }

  @Test
  public void capacity_isCapMinusReserveMinusUsed_neverNegative() {
    int free = PersistedGrantBudget.ANDROID_MAX_PERSISTED_GRANTS - PersistedGrantBudget.RESERVE;
    assertEquals(free, PersistedGrantBudget.capacity(0));
    assertEquals(free - 100, PersistedGrantBudget.capacity(100));
    assertEquals(0, PersistedGrantBudget.capacity(free));
    assertEquals(0, PersistedGrantBudget.capacity(PersistedGrantBudget.ANDROID_MAX_PERSISTED_GRANTS));
    assertEquals(0, PersistedGrantBudget.capacity(5000));
  }

  @Test
  public void remaining_countsPersistedGrantsOfTheApp() {
    int before = PersistedGrantBudget.remaining(context);

    context
        .getContentResolver()
        .takePersistableUriPermission(
            Uri.parse("content://com.android.externalstorage.documents/document/primary%3Aa.pdf"),
            Intent.FLAG_GRANT_READ_URI_PERMISSION);

    assertEquals(before - 1, PersistedGrantBudget.remaining(context));
    assertTrue(PersistedGrantBudget.fits(context, before - 1));
    assertFalse(PersistedGrantBudget.fits(context, before));
    assertFalse(PersistedGrantBudget.fits(context, 2143));
  }

  private void releaseAllGrants() {
    for (UriPermission p :
        new ArrayList<>(context.getContentResolver().getPersistedUriPermissions())) {
      context
          .getContentResolver()
          .releasePersistableUriPermission(
              p.getUri(),
              Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }
  }
}
