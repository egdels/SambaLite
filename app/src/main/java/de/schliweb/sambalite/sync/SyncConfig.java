/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.sync;

import java.io.Serializable;
import lombok.Getter;
import lombok.Setter;

/** Model class representing a folder synchronization configuration. */
@Setter
@Getter
public class SyncConfig implements Serializable {

  private static final long serialVersionUID = 1L;

  private String id;
  private String connectionId;
  private String localFolderUri;
  private String remotePath;
  private String localFolderDisplayName;
  private boolean enabled = true;
  private long lastSyncTimestamp;
  private SyncDirection direction = SyncDirection.BIDIRECTIONAL;
  private int intervalMinutes = 60;

  /** Default constructor for SyncConfig. */
  public SyncConfig() {}

  @Override
  public String toString() {
    return "SyncConfig{"
        + "id='"
        + id
        + '\''
        + ", connectionId='"
        + connectionId
        + '\''
        + ", localFolderUri='"
        + localFolderUri
        + '\''
        + ", remotePath='"
        + remotePath
        + '\''
        + ", localFolderDisplayName='"
        + localFolderDisplayName
        + '\''
        + ", enabled="
        + enabled
        + ", lastSyncTimestamp="
        + lastSyncTimestamp
        + ", direction="
        + direction
        + ", intervalMinutes="
        + intervalMinutes
        + '}';
  }
}
