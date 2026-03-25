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

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import de.schliweb.sambalite.data.model.SmbFileItem;
import java.util.List;
import java.util.Objects;

/**
 * DiffUtil callback for calculating differences between two lists of {@link SmbFileItem}. Used by
 * {@link FileAdapter} to efficiently update the RecyclerView.
 */
public class FileDiffCallback extends DiffUtil.Callback {

  private final List<SmbFileItem> oldList;
  private final List<SmbFileItem> newList;

  public FileDiffCallback(@NonNull List<SmbFileItem> oldList, @NonNull List<SmbFileItem> newList) {
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
    SmbFileItem oldItem = oldList.get(oldItemPosition);
    SmbFileItem newItem = newList.get(newItemPosition);
    // Items are the same if they have the same path
    return Objects.equals(oldItem.getPath(), newItem.getPath());
  }

  @Override
  public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
    SmbFileItem oldItem = oldList.get(oldItemPosition);
    SmbFileItem newItem = newList.get(newItemPosition);
    return Objects.equals(oldItem.getName(), newItem.getName())
        && Objects.equals(oldItem.getPath(), newItem.getPath())
        && oldItem.getType() == newItem.getType()
        && oldItem.getSize() == newItem.getSize()
        && oldItem.getLastModified().getTime() == newItem.getLastModified().getTime();
  }
}
