# User Guide: Folder Synchronization in SambaLite

Last updated: 2026-05-03

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
| **Mirror Mode** | Optional, off by default. When enabled, files and folders that were previously synced but are no longer present on the source side are also deleted on the target side. See *Mirror Mode* below. Only takes effect for one-way directions (Local→Remote or Remote→Local); ignored for Bidirectional. |

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

- Manual only (sync runs only when you tap "Sync Now")
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
- Only **new or modified** files are transferred — unchanged files (same size and modification time) are skipped.
- **Timestamp preservation**: After uploading a file, SambaLite sets the remote file's modification time to match the local file. After downloading, it attempts to set the local file's modification time to match the remote file (best-effort via Android's Storage Access Framework — may not succeed on all storage types).
- By default, files are **never deleted** by the sync. If you delete a file on one side, it will be re-synced from the other side on the next run. To propagate deletions and renames from the source to the target, enable **Mirror Mode** (see below).

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

SambaLite uses a **"Newer Wins"** (last-writer-wins) strategy with a small timestamp tolerance (~3 seconds) to account for network jitter and filesystem differences:

- When syncing in either direction, the file with the more recent modification timestamp overwrites the older one.
- A file is only considered "newer" if its modification time differs by more than the 3-second tolerance.
- In bidirectional mode, both directions are checked — the newer file always takes precedence.
- There is no file merging. If the same file is modified on both sides between syncs, the newer version wins and the older changes are lost.

## Mirror Mode

Mirror Mode is an opt-in setting that turns a one-way sync into a true mirror: after each successful sync run, files and directories that previously existed on the **source** side but are no longer present there are also removed on the **target** side.

This solves the most common complaint about the default sync: when you rename or delete a folder on the source (for example renaming `U2 - Achtung Baby` to `U2 (1991) Achtung Baby` on your NAS), the old entry is no longer recreated on the target on every run.

### When to use it

- **Remote → Local + Mirror**: keep a local copy that exactly matches the SMB share. Renames and deletions on the share are propagated to the device.
- **Local → Remote + Mirror**: keep a remote backup that exactly matches a local folder (e.g. an export folder). Files removed locally are also removed on the share.
- **Bidirectional**: Mirror Mode is **ignored** in bidirectional mode. Mirroring in both directions at the same time is not safe and is intentionally not supported.

### What Mirror Mode does and does not do

Mirror Mode only deletes entries that **SambaLite itself previously synced** — it tracks every transferred file and directory in an internal database (the *sync state*). Files that exist on the target but were never synced by SambaLite (for example a file you manually copied to the local folder, or a file another user placed on the share) are **never** deleted by Mirror Mode.

The sweep runs **after** the regular file transfer phase, so Mirror Mode never deletes anything that is still being downloaded or uploaded in the same run.

### Safeguards

To minimise the risk of accidental data loss, Mirror Mode applies several protections automatically:

| Safeguard | Behaviour |
|-----------|-----------|
| **Incomplete source listing** | If SambaLite cannot fully enumerate the source side (e.g. due to a network error during listing), the sweep is **skipped** for that run. Nothing is deleted on the target. |
| **Empty source listing protection** | If the source listing comes back completely empty but SambaLite previously tracked at least one file there, the sweep is **skipped**. This prevents wiping the target when the source is temporarily inaccessible or misconfigured. |
| **Sanity threshold** | If the planned number of deletions exceeds **both** 50% of the previously tracked entries **and** 100 entries in absolute terms, the sweep is **aborted** for that run and an error entry is added to the Sync Activity Log. The target is left untouched. |
| **Untracked files** | Entries on the target that SambaLite never synced itself are never considered for deletion, even if Mirror Mode is on. |

When a sweep is skipped or aborted, the regular add/update transfer still happens — only the deletion phase is suppressed. The next run will re-evaluate the situation.

### Trash folder

By default, Mirror Mode does **not** delete entries permanently. Instead, it **moves** them into a hidden, timestamped trash folder at the root of the sync target (the same root the sync runs against):

```
<targetRoot>/.sambalite-trash/<unix-timestamp>/<original-relative-path>
```

This applies to both directions:

- **Local → Remote + Mirror**: removed entries on the SMB share are moved into `<remotePath>/.sambalite-trash/<timestamp>/…` via SMB rename.
- **Remote → Local + Mirror**: removed entries in the local SAF folder are moved into `<localFolder>/.sambalite-trash/<timestamp>/…` via the Storage Access Framework's `moveDocument`.

A new timestamped subfolder is created for every sweep run, so multiple removed versions never overwrite each other. You can inspect or restore entries by browsing into `.sambalite-trash` on the corresponding side. Cleaning up the trash is a manual operation; SambaLite never empties it on its own. The trash folder itself is excluded from sync traversal, so it is neither uploaded to the remote nor mirrored back into the source side.

If the move into the trash folder fails for any reason (for example a permission error on SMB, or a SAF provider that does not support `moveDocument` for that location), Mirror Mode falls back to a regular delete and logs a warning, so the sweep itself does not get stuck.

#### Disabling the trash folder

The setup and edit dialogs expose a **Move to trash folder** switch directly underneath the Mirror Mode warning. The switch is only visible while Mirror Mode is enabled and a one-way direction is selected. With the switch turned off, Mirror Mode deletes entries immediately on both sides — there is no recovery beyond what the underlying storage offers (NAS snapshots, SAF provider's own trash, etc.). The default is **on**.

### Recommended workflow

1. Run the sync at least once **without** Mirror Mode so the sync state database is populated and you can verify the basic sync behaviour.
2. Open the sync configuration and enable the **Mirror mode** switch in the setup dialog. A red warning text is shown while the switch is active to remind you of the deletion semantics.
3. Save the configuration. The sync list shows a small `Mirror` badge on every configuration that has Mirror Mode enabled.
4. Run the sync (manually or wait for the next scheduled run) and inspect the **Sync Activity Log**. Mirror activity is reported under the dedicated actions `🪞 Mirror trashed`, `🪞 Mirror deleted`, and `🪞 Mirror aborted` (see *Sync Activity Log* below).
5. Always keep a backup of important data before enabling Mirror Mode for the first time. Once Mirror Mode deletes a file (or moves it to the trash folder, depending on direction), recovery depends on the underlying storage (e.g. NAS snapshots, recycle bins, version history) or on the `.sambalite-trash` folder for remote targets.

## Deleting a Connection

When you delete an SMB connection from the main screen, **all sync configurations associated with that connection are automatically removed** and the background sync is cancelled if no other configurations remain.

## Supported File Types

The sync processes **all file types** — there is no whitelist or blacklist. However, when downloading files from the SMB share to your device (Remote → Local), SambaLite needs to determine the correct MIME type for each file. This is done using Android's built-in `MimeTypeMap`, which recognizes most common file types:

| Category | Examples |
|----------|----------|
| **Images** | `.jpg`, `.jpeg`, `.png`, `.gif`, `.webp`, `.bmp` |
| **Documents** | `.pdf`, `.txt`, `.docx`, `.xlsx`, `.pptx`, `.odt` |
| **Video** | `.mp4`, `.mkv`, `.avi`, `.mov`, `.webm` |
| **Audio** | `.mp3`, `.flac`, `.wav`, `.ogg`, `.aac` |
| **Archives** | `.zip`, `.gz`, `.tar`, `.7z` |
| **Web/Data** | `.html`, `.json`, `.xml`, `.csv` |

For file extensions **not recognized** by Android (e.g. `.tqd` or other proprietary formats), the generic MIME type `application/octet-stream` is used. This can cause Android's Storage Access Framework to **alter or remove the file extension** when creating the local file. As a result, the file may not be found on the next sync cycle and could be downloaded again, potentially leading to duplicates.

> **Tip:** If you sync folders containing files with uncommon extensions, verify that the downloaded files retain their correct names and extensions.

## Sync Activity Log

SambaLite records all sync actions with timestamps. You can view the log in the **System Monitor** (accessible from the toolbar menu → System Monitor). The log shows:

- **↑ Uploaded**: Files sent from your device to the SMB share.
- **↓ Downloaded**: Files received from the SMB share to your device.
- **⊘ Skipped**: Files that were unchanged (same size and modification time) and not transferred.
- **📁 Created dir**: New directories created during sync.
- **🗑 Deleted**: Internal database entries for files or directories that no longer exist on the remote share — with the default add/update sync, SambaLite does **not** delete your actual files.
- **🪞 Mirror trashed**: A previously synced entry was moved into the `.sambalite-trash/<timestamp>/` folder by Mirror Mode. This applies to both directions when the trash option is enabled (the default): on the SMB share for `Local → Remote + Mirror`, and inside the local SAF folder for `Remote → Local + Mirror`. The original path is shown in the detail column.
- **🪞 Mirror deleted**: A previously synced entry was permanently deleted by Mirror Mode. This happens when the trash option is disabled, or as a fallback when the move into `.sambalite-trash/` fails (for example because the SAF provider does not support `moveDocument`).
- **🪞 Mirror aborted**: A mirror sweep was aborted by the sanity threshold or another safeguard. No entries were deleted in that run; the detail column shows how many would have been deleted out of how many tracked.
- **🕐 Timestamp set**: File modification timestamps successfully synchronized.
- **⚠ Timestamp failed**: File modification timestamps could not be set (e.g. due to filesystem limitations).
- **✗ Error**: Files that failed to sync, with an error description.

The log displays the 25 most recent entries (newest first) along with a summary of total actions. Up to 100 entries are stored persistently across app restarts.

> **Tip:** Use the Sync Activity Log to verify that your sync is working as expected, especially when setting up a new sync configuration. Start with a small folder to confirm correct behavior before syncing larger datasets.

## Limitations

- **Clock differences** between your device and the SMB server may lead to incorrect sync decisions. Ensure both devices have accurate time settings (NTP recommended).
- **Large files** may take longer than the WorkManager execution window. Very large files might not complete in a single sync cycle.
- **Deleted files reappear (without Mirror Mode)**: With the default add/update sync, deletions are not propagated, so a file deleted on one side will be restored from the other side on the next sync. Enable **Mirror Mode** if you want one-way deletions to be propagated to the target.
- **No file merging**: Simultaneous edits to the same file on both sides will result in the newer version overwriting the older one.
- **Uncommon file extensions**: Files with extensions not recognized by Android may have their file names altered during download (see *Supported File Types* above).

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Sync doesn't start | Ensure you have a network connection and the sync configuration is set up. |
| Files not syncing | Check that the remote path exists and your SMB credentials are correct. |
| Sync stops after a while | Android may restrict background work for battery optimization. Exclude SambaLite from battery optimization in your device settings. |
| Sync doesn't resume after reboot | Some manufacturers block background jobs. Exclude SambaLite from battery optimization and check that "Auto-start" is enabled (if your device has this setting). |
| Sync keeps running after quitting the app | This is expected — WorkManager runs independently. Remove your sync configurations if you want to stop sync completely. |
| Old files overwrite newer ones | Check that the clocks on your device and SMB server are synchronized. |
