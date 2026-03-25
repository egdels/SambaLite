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

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import java.util.List;
import java.util.Objects;

/**
 * DiffUtil callback for calculating differences between two lists of share names. Used by {@link
 * SharesAdapter} to efficiently update the RecyclerView.
 */
public class SharesDiffCallback extends DiffUtil.Callback {

  private final List<String> oldList;
  private final List<String> newList;

  public SharesDiffCallback(@NonNull List<String> oldList, @NonNull List<String> newList) {
    this.oldList = oldList;
    this.newList = newList;
  }

  @Override
  public int getOldListSize() {
    return oldList.size();
  }

  @Override
  public int getNewListSize() {
    return newList.size();
  }

  @Override
  public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
    return Objects.equals(oldList.get(oldItemPosition), newList.get(newItemPosition));
  }

  @Override
  public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
    return Objects.equals(oldList.get(oldItemPosition), newList.get(newItemPosition));
  }
}
