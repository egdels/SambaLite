# User Guide: Folder Synchronization in SambaLite

Last updated: 2026-03-07

This guide explains how to set up and manage automatic folder synchronization between your Android device and an SMB network share.

## Overview

Folder Sync lets you keep a local folder on your device in sync with a remote folder on your SMB share — automatically and in the background. Typical use cases include:

- **Photo Backup**: Automatically upload your camera folder to your NAS.
- **Signal/WhatsApp Backup**: Push app backup folders to a network share.
- **Bidirectional Sync**: Edit files on your PC and have them available on your phone, and vice versa.

## Prerequisites

- At least one SMB connection must be configured in SambaLite.
- You must be connected to the same network as your SMB server (Wi-Fi or VPN).
- The app needs storage permission to access the local folder you want to sync.

## Setting Up a Sync Configuration

1. Open SambaLite and navigate to a connection's file browser.
2. Browse to the folder you want to sync.
3. **Long-press** the folder to open the context menu.
4. Select **"Setup Sync"**.
5. In the Sync Setup dialog, configure the following:

| Setting | Description |
|---------|-------------|
| **Sync Direction** | Choose one of three options (see below). |
| **Sync Interval** | How often the sync runs in the background (15 min – 24 hours). |
| **Remote Path** | The folder path on the SMB share (e.g. `Photos/Backup`). Automatically set to the selected folder's path and **not editable**. |
| **Local Folder** | Tap "Select Local Folder" to pick a folder on your device using the system folder picker. |

6. Tap **"Save"** to create the sync configuration.

You will see a confirmation toast: *"Sync configuration saved"*.

> **Note:** Folders with an active sync configuration are marked with a sync direction indicator in the file browser.

## Sync Directions

| Direction | Behavior |
|-----------|----------|
| **Local → Remote** | Files from your device are uploaded to the SMB share. New or modified local files overwrite older remote versions. |
| **Remote → Local** | Files from the SMB share are downloaded to your device. New or modified remote files overwrite older local versions. |
| **Bidirectional** | Sync runs in both directions. The newer version of a file always wins ("Newer Wins" strategy). |

## Sync Intervals

You can choose from the following intervals:

- Every 15 minutes
- Every 30 minutes
- Every hour
- Every 6 hours
- Every 12 hours
- Every 24 hours

The sync runs automatically in the background using Android's WorkManager, even when the app is closed. Each sync configuration respects its own interval individually — a folder configured for every 6 hours will not be synced more frequently just because another folder is set to every 15 minutes.

> **Note:** You can configure a maximum of **5 sync folders**. This limit ensures reliable performance within the WorkManager execution window and keeps resource usage (SMB connections, battery, network) manageable.

## Managing Sync Configurations

To manage an existing sync configuration:

1. Open the file browser and navigate to the folder that has a sync configuration.
2. **Long-press** the folder to open the context menu.
3. From the context menu you can:
   - **Sync Now** to trigger an immediate one-time sync.
   - **Edit Sync** to modify the sync configuration.
   - **Remove Sync** to delete the sync configuration.

## How Sync Works in the Background

- Sync is powered by Android **WorkManager**, which handles scheduling, retries, and battery optimization.
- Sync only runs when a **network connection** is available. If the network is lost, the sync is retried automatically with exponential backoff.
- The sync processes files **recursively**, including all subfolders.
- Only **new or modified** files are transferred — unchanged files (same size) are skipped.
- Files are **never deleted** by the sync. If you delete a file on one side, it will be re-synced from the other side on the next run.

## Sync Behavior After App Exit and Device Restart

Because sync is managed by Android's WorkManager, it operates **independently of the app's lifecycle**. The table below summarizes what happens in different scenarios:

| Action | Sync continues? | Details |
|--------|:---------------:|---------|
| **Quit via Power-Off button** in the app | ✅ Yes | The Power-Off button stops the SMB background service and closes the app, but the WorkManager sync job keeps running on schedule. |
| **Swipe app from Recents** | ✅ Yes | WorkManager jobs are not affected by removing the app from the recent apps list. |
| **Device restart** | ✅ Yes | WorkManager persists all scheduled jobs in an internal database. After a reboot, jobs are automatically re-scheduled — no need to open the app. |
| **Force-Stop** (Android Settings → App Info → Force Stop) | ❌ No | A force-stop cancels all WorkManager jobs. Sync resumes only after you open SambaLite again. |
| **Uninstall the app** | ❌ No | All sync configurations and scheduled jobs are permanently removed. |

> **Tip:** If you want the sync to stop completely when you quit the app, remove your sync configurations before pressing the Power-Off button (see *Managing Sync Configurations* above).

> **Note on battery optimization:** Some device manufacturers (e.g. Xiaomi, Huawei, Samsung) apply aggressive battery restrictions that may delay or block WorkManager jobs. If sync does not run reliably after a device restart, exclude SambaLite from battery optimization in your device settings.

## Conflict Resolution

SambaLite uses a **"Newer Wins"** (last-writer-wins) strategy:

- When syncing in either direction, the file with the more recent modification timestamp overwrites the older one.
- In bidirectional mode, both directions are checked — the newer file always takes precedence.
- There is no file merging. If the same file is modified on both sides between syncs, the newer version wins and the older changes are lost.

## Deleting a Connection

When you delete an SMB connection from the main screen, **all sync configurations associated with that connection are automatically removed** and the background sync is cancelled if no other configurations remain.

## Limitations

- **Clock differences** between your device and the SMB server may lead to incorrect sync decisions. Ensure both devices have accurate time settings (NTP recommended).
- **Large files** may take longer than the WorkManager execution window. Very large files might not complete in a single sync cycle.
- **Deleted files reappear**: Since the sync does not track deletions, a file deleted on one side will be restored from the other side on the next sync.
- **No file merging**: Simultaneous edits to the same file on both sides will result in the newer version overwriting the older one.

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Sync doesn't start | Ensure you have a network connection and the sync configuration is set up. |
| Files not syncing | Check that the remote path exists and your SMB credentials are correct. |
| Sync stops after a while | Android may restrict background work for battery optimization. Exclude SambaLite from battery optimization in your device settings. |
| Sync doesn't resume after reboot | Some manufacturers block background jobs. Exclude SambaLite from battery optimization and check that "Auto-start" is enabled (if your device has this setting). |
| Sync keeps running after quitting the app | This is expected — WorkManager runs independently. Remove your sync configurations if you want to stop sync completely. |
| Old files overwrite newer ones | Check that the clocks on your device and SMB server are synchronized. |
