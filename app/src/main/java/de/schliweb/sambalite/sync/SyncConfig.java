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
  private boolean wifiOnly = false;
  private long lastSyncTimestamp;
  private SyncDirection direction = SyncDirection.BIDIRECTIONAL;
  private int intervalMinutes = 60;

  /**
   * If {@code true}, the sync runs in mirror mode: after a successful one-way sync, files and
   * directories that previously existed on the source (tracked in the local sync state DB) but are
   * no longer present in the source's current listing are deleted on the target side as well.
   *
   * <p>Mirror mode is only meaningful for {@link SyncDirection#LOCAL_TO_REMOTE} and {@link
   * SyncDirection#REMOTE_TO_LOCAL}. For {@link SyncDirection#BIDIRECTIONAL} this flag is ignored.
   *
   * <p>Safeguards (see {@link MirrorSweeper}): an empty source listing is treated as suspicious and
   * disables deletions for that run; a sweep is also aborted if the planned number of deletions
   * exceeds a sanity threshold (more than 50% of the previously tracked entries AND more than 100
   * entries).
   */
  private boolean mirror = false;

  /**
   * If {@code true} (default) and {@link #mirror} is enabled, mirror sweep moves removed entries to
   * a trash folder on the target instead of permanently deleting them. The trash folder is named
   * {@code .sambalite-trash/<timestamp>/<relPath>} and lives at the root of the target.
   *
   * <p>Currently this safeguard is implemented for the SMB target (i.e. {@link
   * SyncDirection#LOCAL_TO_REMOTE}). For {@link SyncDirection#REMOTE_TO_LOCAL} entries are deleted
   * directly on local storage; relying on Android's recycle bin / SAF trash semantics is not
   * portable across providers.
   */
  private boolean mirrorUseTrash = true;

  /** Indicates if this sync is currently running. Not persisted. */
  private transient boolean isRunning = false;

  public boolean isRunning() {
    return isRunning;
  }

  public void setRunning(boolean running) {
    isRunning = running;
  }

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
        + ", wifiOnly="
        + wifiOnly
        + ", lastSyncTimestamp="
        + lastSyncTimestamp
        + ", direction="
        + direction
        + ", intervalMinutes="
        + intervalMinutes
        + ", mirror="
        + mirror
        + ", mirrorUseTrash="
        + mirrorUseTrash
        + '}';
  }
}
