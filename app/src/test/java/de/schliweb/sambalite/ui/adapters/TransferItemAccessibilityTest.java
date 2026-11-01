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

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.transfer.db.PendingTransfer;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Screen readers hear the direction of a transfer from its icon, and the status badge, whose text
 * is already spoken, is not announced a second time as "Connection Status".
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TransferItemAccessibilityTest {

  private static PendingTransfer transfer(long id, String type) {
    PendingTransfer t = new PendingTransfer();
    t.id = id;
    t.transferType = type;
    t.status = "COMPLETED";
    t.displayName = "x" + id;
    t.remotePath = "Q/x" + id;
    return t;
  }

  @Test
  public void iconNamesTheDirection_statusBadgeIsNotAnnounced() {
    Context context =
        new ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_SambaLite);
    TransferItemAdapter adapter =
        new TransferItemAdapter(
            new TransferItemAdapter.TransferActionCallback() {
              @Override
              public void onItemClick(@NonNull PendingTransfer transfer) {}

              @Override
              public void onItemLongClick(@NonNull PendingTransfer transfer) {}
            });
    adapter.submitList(
        Arrays.asList(
            transfer(1, "UPLOAD"), transfer(2, "DOWNLOAD"), transfer(3, "DOWNLOAD_DIRECTORY")));
    FrameLayout parent = new FrameLayout(context);

    String[] expected = {
      context.getString(R.string.transfer_type_upload),
      context.getString(R.string.transfer_type_download),
      context.getString(R.string.transfer_type_download)
    };
    for (int i = 0; i < 3; i++) {
      TransferItemAdapter.ViewHolder holder = adapter.onCreateViewHolder(parent, 0);
      adapter.onBindViewHolder(holder, i);
      assertEquals(
          expected[i], holder.itemView.findViewById(R.id.transfer_icon).getContentDescription());
      assertEquals(
          View.IMPORTANT_FOR_ACCESSIBILITY_NO,
          holder.itemView.findViewById(R.id.transfer_status_icon).getImportantForAccessibility());
    }
  }
}
