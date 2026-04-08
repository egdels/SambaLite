# User Guide: Transfer Queue in SambaLite

Last updated: 2026-04-08

This guide explains how the transfer queue works for manual file uploads and downloads between your Android device and an SMB network share.

## Overview

The Transfer Queue provides a persistent, non-blocking way to upload and download files. Instead of waiting for each transfer to complete in a blocking dialog, files are added to a queue and processed in the background. Typical use cases include:

- **Uploading multiple files**: Select several files and continue browsing while they upload.
- **Downloading large files**: Start a download and use other apps while it completes.
- **Folder uploads**: Upload an entire folder — all files are queued as a batch with folder structure preserved.

## Prerequisites

- At least one SMB connection must be configured in SambaLite.
- You must be connected to the same network as your SMB server (Wi-Fi or VPN).
- The app needs storage permission to access local files and folders.

## Enqueueing Transfers

### Single File Upload

1. Open the file browser and navigate to the remote folder where you want to upload a file.
2. Tap the **Upload** button.
3. Select a file from the system file picker.
4. The file is immediately added to the transfer queue — no blocking dialog appears.

### Folder Upload

1. Open the file browser and navigate to the remote folder where you want to upload.
2. Tap the **Upload Folder** button.
3. Select a folder from the system folder picker.
4. All files in the folder (including subfolders) are scanned and enqueued as a **batch**. The remote folder structure is created automatically by the transfer worker.

### Single File Download

1. Open the file browser and navigate to the file you want to download.
2. Tap the file or select **Download** from the context menu.
3. The file is added to the transfer queue and processed in the background.

### Multi-File Download

1. Select multiple files in the file browser.
2. Tap **Download**.
3. All selected files are enqueued as a batch.

## Transfer Queue UI

The Transfer Queue screen shows all queued, active, and recently completed transfers. You can open it from the file browser toolbar.

### Stats Card

At the top of the screen, a summary card shows the current counts:

| Counter | Description |
|---------|-------------|
| **Pending** | Transfers waiting to be processed. |
| **Active** | The transfer currently in progress. |
| **Completed** | Successfully finished transfers (shown for up to 1 hour after completion). |
| **Failed** | Transfers that encountered an error. |
| **Cancelled** | Transfers that were cancelled by the user. |

### Sorting

You can sort the transfer list by tapping the sort button:

- **By Name**: Alphabetical order by file name.
- **By Date**: Newest first (based on last update time).
- **By Status**: Active → Pending → Failed → Completed → Cancelled.

### Actions per Transfer

Tap on any transfer to open an action dialog:

| Action | Description |
|--------|-------------|
| **Retry** | Resets a failed transfer back to PENDING (retry count and progress are reset) and restarts the worker. |
| **Cancel** | Marks the transfer as CANCELLED. It will no longer be processed. |
| **Remove** | Permanently deletes the transfer entry from the database. |

## Transfer Status Lifecycle

Each transfer goes through the following states:

```
PENDING → ACTIVE → COMPLETED
                 → FAILED (→ retry → PENDING)
         → CANCELLED
```

| Status | Description |
|--------|-------------|
| **PENDING** | Queued and waiting to be processed. |
| **ACTIVE** | Currently being uploaded or downloaded. |
| **COMPLETED** | Transfer finished successfully. |
| **FAILED** | Transfer encountered an error. Will be retried automatically up to 3 times. |
| **CANCELLED** | Transfer was cancelled by the user. |

## How the Transfer Queue Works in the Background

- The queue is powered by Android **WorkManager** with a **OneTimeWorkRequest**. The worker runs as a **foreground service** with a persistent notification showing the current transfer status.
- Transfers only run when a **network connection** is available. If the network is lost, the worker is retried automatically by WorkManager.
- The worker uses **KEEP policy**: if a worker is already running when new transfers are enqueued, the running worker is not replaced. Instead, it loops internally to pick up newly added transfers from the database.
- Transfers for the same SMB connection are processed using a **shared SMB session** (Connection-Reuse), reducing connection overhead.
- Files are streamed **directly between SAF and SMB** — no temporary copies are created on the device.
- **Timestamp preservation**: After uploading a file, SambaLite sets the remote file's modification time to match the local file. After downloading, it attempts to set the local file's modification time to match the remote file (best-effort via Android's Storage Access Framework).
- **Disk space check**: Before starting and periodically during downloads (every 10 MB), the worker checks that at least 10 MB of free disk space is available. If space is insufficient, the queue is aborted.
- **Duplicate detection**: Before enqueueing, the system checks whether a transfer for the same remote path is already pending or active, avoiding duplicate entries.

## Retry and Error Handling

- Each transfer is retried automatically up to **3 times** if it fails.
- After 3 failed attempts, the transfer remains in FAILED status and must be retried manually from the queue UI.
- If any transfers fail, the worker requests a **retry** from WorkManager, which re-schedules it with backoff.
- You can manually retry individual transfers or all failed transfers from the queue UI.

## Crash Recovery and Resume

The transfer queue is fully persistent in a **Room database**. Transfers survive app kills and device restarts, with **automatic resume** from the last transferred byte offset.

- All **ACTIVE** transfers are automatically reset to **PENDING** after an unclean app exit.
- The worker is re-enqueued on the next app start if there are pending transfers.
- Upon restart, the worker checks the already transferred bytes and resumes from that point.

## Transfer Behavior After App Exit and Device Restart

| Action | Transfers continue? | Details |
|--------|:-------------------:|---------|
| **Quit via Power-Off button** in the app | ✅ Yes | The WorkManager transfer job keeps running. |
| **Swipe app from Recents** | ✅ Yes | WorkManager jobs are not affected by removing the app from the recent apps list. |
| **Device restart** | ✅ Yes | Pending transfers are persisted in the database. The worker is re-enqueued on next app start. |
| **Force-Stop** (Android Settings → App Info → Force Stop) | ❌ No | A force-stop cancels the WorkManager job. Transfers resume only after you open SambaLite again. |

## Notifications

While the transfer queue is being processed, a **persistent notification** is displayed showing:

- The name of the file currently being transferred.
- The transfer direction (upload/download).

The notification is silent and low-priority to avoid disturbing the user. It is automatically dismissed when all transfers are completed.

> **Note:** A broadcast is sent after each successful transfer, which can trigger a UI refresh in the file browser.

## Automatic Cleanup

Completed and cancelled transfers are automatically cleaned up after **7 days**. This cleanup runs at the end of each transfer queue processing cycle.

## Limitations

- **Large files** may take longer than the WorkManager execution window. Very large files might not complete in a single worker run, but they will automatically resume in the next run.
- **Battery optimization**: Some device manufacturers (e.g. Xiaomi, Huawei, Samsung) apply aggressive battery restrictions that may delay or stop the transfer worker. Exclude SambaLite from battery optimization in your device settings.
- **Storage Access Framework limitations**: Timestamp preservation after download may not work on all storage types (e.g. SD cards or certain cloud-backed storage providers).

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Transfer stays in PENDING | Ensure you have a network connection. Check that the SMB server is reachable. |
| Transfer fails repeatedly | Check the error message in the queue UI. Verify that the remote path exists and your SMB credentials are correct. |
| Transfers stop after a while | Android may restrict background work for battery optimization. Exclude SambaLite from battery optimization in your device settings. |
| Queue is empty but files weren't transferred | Completed transfers are hidden after 1 hour in the queue UI. Check the file browser to verify the transfer succeeded. |
| Duplicate files after interruption | Interrupted transfers automatically resume from the last byte. If the transfer was interrupted before any data was written, it will start from the beginning. |
