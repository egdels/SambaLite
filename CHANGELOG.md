# Changelog

All notable changes to SambaLite will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.0.6] - 2026-04-02

### Added
- **Authentication before security settings**: `FileBrowserActivity` and `MainActivity` now require biometric/device authentication before opening the security settings dialog if any auth setting is currently enabled. This prevents unauthorized users from disabling security protections.
- **Individual transfer cancellation**: `TransferWorker` now checks per-transfer cancellation status during active uploads and downloads. A new `getStatus()` query in `PendingTransferDao` enables real-time cancellation detection, allowing users to cancel individual transfers while the worker continues processing others.
- **Background transfer progress reporting**: `FileOperationsViewModel` now forwards byte-level progress updates to `BackgroundSmbManager` for both upload and download operations, enabling accurate progress display in notifications.
- Translation for `auth_subtitle_security_settings` in all 7 languages (EN, DE, ES, FR, NL, PL, ZH).

### Fixed
- **Database migration crash**: `SearchDatabase` and `SyncDatabase` now use `fallbackToDestructiveMigration(true)`, preventing crashes when the database schema changes between app updates.
- **Cancelled transfer status overwrite**: `TransferWorker` no longer overwrites a `CANCELLED` status with `FAILED` when a transfer error occurs after user cancellation.

If you like this update, support SambaLite here: https://ko-fi.com/egdels • https://www.paypal.com/paypalme/egdels

## [2.0.5] - 2026-04-01

### Added
- **Batch file deletion**: New `deleteFiles()` method in `SmbRepository` / `SmbRepositoryImpl` deletes multiple files in a single SMB session, avoiding repeated session creation and SMB oplock/caching issues. Includes per-file retry logic with 200 ms delay for servers that delay deletion due to oplocks.
- **"Hide completed" filter in Transfer Queue**: `TransferQueueActivity` now offers a toggle to hide completed transfers. The sort dialog has been redesigned as a custom dialog (`dialog_sort_transfers.xml`) with radio buttons and a checkbox.
- **Transfer Worker connection failure tracking**: `TransferWorker` now tracks connections that fail with connectivity errors within a single worker run and skips them for the remainder of that run, avoiding tight retry loops on unreachable servers.
- Translations for `transfer_sort_subtitle` and `transfer_hide_completed` in all 7 languages (EN, DE, ES, FR, NL, PL, ZH).
- New drawable `ic_system_monitor` for the System Monitor menu entry.

### Changed
- **Batch delete in FileOperationsController**: Multi-file deletion now uses `FileOperationsViewModel.deleteFilesBatch()` instead of deleting files one by one. Directory refresh and cache invalidation happen once after the entire batch completes, improving performance for large selections.
- **`deleteFile()` overload with `skipRefresh` parameter**: Allows callers to suppress the automatic directory refresh after a single deletion, enabling efficient batch workflows.
- **Layout improvements**: `dialog_progress.xml`, `item_file.xml`, and `item_transfer.xml` updated — file name text views now use `match_parent` width with `maxLines="2"` and `ellipsize="end"` for better readability of long file names. Removed redundant `layout_constraintBottom` attributes.
- System Monitor menu icon changed from generic `ic_dialog_info` to dedicated `ic_system_monitor` drawable.

### Fixed
- **Single-file delete verification**: `SmbRepositoryImpl.deleteFile()` now verifies that the file was actually removed after `share.rm()` and retries once with a 200 ms delay if the file still exists due to SMB server-side oplock delays.

If you like this update, support SambaLite here: https://ko-fi.com/egdels • https://www.paypal.com/paypalme/egdels

## [2.0.4] - 2026-04-01

### Changed
- **Unified user feedback**: All user-facing messages now consistently use Snackbar (via `UIHelper`) instead of a mix of Toast and Snackbar. Toast is completely removed from the codebase.
- **Consistent Snackbar color strategy**: Success messages (delete, rename, folder created) now correctly show green instead of blue. Color semantics: green = success, red = error, blue = informational.
- **Removed "Opening ..." message**: The redundant "Opening ..." Snackbar after navigating to a share was removed, as the navigation itself provides sufficient feedback.

### Added
- **Transfer status badges in file browser**: Files with active uploads or downloads now display a transfer badge (sync icon) on their file icon, matching the transfer queue visual style.
- **Blocked actions during transfer**: Files currently being uploaded or downloaded cannot be edited (rename, delete, options menu) or multi-selected. A blue info Snackbar explains the restriction.
- Translations for `file_upload_in_progress` and `file_download_in_progress` in all 7 languages (EN, DE, FR, PL, ES, NL, ZH).

### Removed
- **Toast completely removed**: `DialogHelper.showToast()`, `EnhancedUIUtils.showEnhancedToast()`, and the unreachable Toast in `FileBrowserActivity.onDestroy()` have been removed. All user feedback now uses Snackbar exclusively.

If you like this update, support SambaLite here: https://ko-fi.com/egdels • https://www.paypal.com/paypalme/egdels

## [2.0.3] - 2026-03-31

### Added
- **Async transport option**: New per-connection toggle "Use async transport (faster)" in the add/edit connection dialog. When enabled, SMB clients use `AsyncDirectTcpTransportFactory` for improved transfer, sync, and search performance. Persisted via `SmbConnection.asyncTransport` and `ConnectionRepositoryImpl`. Translations added for all 7 languages.
- **Transfer throughput logging**: `TransferWorker` now measures and logs upload and download duration and throughput (MB/s) on completion.

### Fixed
- **Keyboard not dismissed on dialog close**: Added `OnDismissListener` to the rename dialog in `DialogHelper` that clears focus and hides the keyboard, including a delayed secondary hide (100 ms) to handle devices where the keyboard reappears after dismiss.
- **Folder upload ignores per-file overwrite selection**: `FileOperationsController` folder upload now respects the user's individual file selection from the multi-file exists dialog. Previously, confirming the dialog always uploaded all files; now unselected files are excluded via a `Set<String>` filter passed to the enqueue logic.

### Changed
- **File-exists dialogs refactored to custom button views**: `showFileExistsDialog` and `showMultiFileExistsDialog` in `DialogHelper` now use in-layout buttons (`file_exists_overwrite_button`, `file_exists_cancel_button`, `files_exist_overwrite_button`, `files_exist_skip_button`) instead of `MaterialAlertDialogBuilder` positive/negative buttons, improving layout control and consistency.
- `FileOperationsController`: Removed `showMultiFileExistsOrSingle` helper; folder upload now calls `showMultiFileExistsDialog` directly with a `Consumer<Set<String>>` callback for selective file exclusion.
- `SystemMonitorActivity`: Simplified network status section — removed `AdvancedNetworkOptimizer` details, now reports that network monitoring is handled by system `ConnectivityManager`.
- `SmbRepositoryImpl`, `TransferWorker`, `FolderSyncWorker`, `SearchWorker`: SMB client creation now respects the `asyncTransport` flag and includes enhanced debug logging for client configuration.

### Removed
- `AdvancedNetworkOptimizer` class deleted — network optimization is now handled by the system `ConnectivityManager`. References removed from `SambaLiteApp` and `SystemMonitorActivity`.

If you like this update, support SambaLite here: https://ko-fi.com/egdels • https://www.paypal.com/paypalme/egdels

## [2.0.2] - 2026-03-31

### Added
- **Multi-file overwrite dialog**: When uploading multiple files that already exist on the server, a new checkbox dialog (`showMultiFileExistsDialog`) allows the user to individually select which files to overwrite. Includes a "Select all" toggle and "Skip all" option. New layout `dialog_file_exists_multi` and translations for all 7 languages.

### Fixed
- **Transfer integrity check**: Upload and download integrity checks in `TransferWorker` and `FolderSyncWorker` now compare the actual local file size (queried via SAF `ContentResolver`) against the actual remote file size, instead of relying on the potentially stale `fileSize` value stored in the database. This prevents false positives when the DB value is zero or outdated.
- **Thread-safe date formatting**: `TimestampUtils.DATE_FORMAT` changed from a shared `SimpleDateFormat` with `synchronized` block to a `ThreadLocal<SimpleDateFormat>`, eliminating potential contention in multi-threaded transfer scenarios.
- **Locale-independent sorting**: `TransferQueueActivity` sort-by-name now uses `Locale.ROOT` instead of the default locale for consistent case-insensitive ordering.

### Changed
- `FileOperationsController`: Extracted multi-file and single-file overwrite dialog logic into dedicated methods (`showMultiFileExistsOrSingle`, `showMultiFileExistsDialog`). Removed ~350 lines of unused legacy upload/download code and related imports.
- `PendingTransferDao`: Clarified documentation for `resetActiveToRetry()` and `resetFailedToRetry()` — resetting `bytes_transferred` to 0 is intentional; resume after crash/reboot is not yet supported.
- `TransferWorker`: Added documentation note that upload resume does not take effect after crash/reboot due to the `resetActiveToRetry()` reset. Added `getLocalFileSize()` helper method for SAF-based file size queries. Improved logging for download remote file size lookup.

If you like this update, support SambaLite here: https://ko-fi.com/egdels • https://www.paypal.com/paypalme/egdels

## [2.0.1] - 2026-03-30

### Fixed
- Polish locale lint error (`MissingQuantity`): Added missing `few` and `many` plural quantity items to `transfer_queue_count` in `values-pl/strings.xml`. Polish requires four plural forms (`one`, `few`, `many`, `other`); the missing entries caused a lint error that failed the CI build.

If you like this update, support SambaLite here: https://ko-fi.com/egdels • https://www.paypal.com/paypalme/egdels

## [2.0.0] - 2026-03-30

### Added
- **Persistent Transfer Queue**: Uploads and downloads are now queued persistently using Room DB and processed in the background via WorkManager (`TransferWorker`). Transfers survive app kills and device restarts, with automatic resume from the last saved byte offset. Includes foreground notification with live progress, connection reuse (one SMB session per connection), direct SAF→SMB streaming without temp copies, and disk space checks during downloads.
- **Transfer Queue Activity**: New dedicated screen (`TransferQueueActivity`) to view, sort, retry, cancel, and remove queued transfers. Supports batch actions, status filtering (pending, active, completed, failed, cancelled), and sorting by name, date, or status. Accessible from the main menu.
- **Background Search via WorkManager**: File search is now performed in the background using `SearchWorker` with a Room database (`SearchDatabase`). Results appear live in the UI via LiveData as they are found, replacing the previous blocking in-process search. Search survives activity recreation and runs as a foreground service with notification.
- Transfer completion broadcast: `FileBrowserActivity` receives a local broadcast when a transfer finishes, showing a success message and refreshing the file list automatically.
- Duplicate upload detection: When enqueuing a file that is already in the transfer queue, a confirmation dialog is shown before adding it again.
- Translations for all new Transfer Queue and search strings in all 7 languages (EN, DE, ES, FR, NL, PL, ZH).
- New drawable resources: `ic_transfer_queue`, `ic_cancel_red`, `ic_check_circle_green`, `ic_power_off`, `ic_sync_active`.

### Changed
- Uploads and downloads no longer block the UI with progress dialogs. Instead, they are enqueued into the persistent transfer queue and processed in the background.
- `SearchViewModel` fully rewritten: replaced in-memory executor-based search with WorkManager-backed `SearchWorker` and Room-based `SearchResultDao`. Removed search cache optimizer and in-memory throttle logic.
- `FileOperationsController` and `FileOperationsViewModel` significantly refactored: removed inline upload/download logic (~700 lines) in favor of `enqueueUpload()`/`enqueueDownload()` methods that persist transfers to Room and trigger `TransferWorker`.
- `SambaLiteApp` now implements `Configuration.Provider` for custom WorkManager initialization. On app start, pending transfers from a previous session are automatically resumed.
- Search dialog (`dialog_search.xml`) wrapped in `ScrollView` for better keyboard handling; `SOFT_INPUT_ADJUST_RESIZE` applied to search dialog window.
- `SmbRepository` / `SmbRepositoryImpl`: Removed `searchFiles()` and related search methods (now handled by `SearchWorker` directly). Removed unused `BackgroundSmbManager` search context methods.
- `BiometricAuthHelper.AuthCallback.onAuthFailure` parameter annotated with `@NonNull`.

### Removed
- `FileUploadTask` class removed — upload logic is now handled entirely by `TransferWorker`.
- Blocking progress dialogs for uploads and downloads removed from `FileOperationsController`.
- In-memory search execution removed from `SmbRepository` and `BackgroundSmbManager`.
- `FileSkippedException` and its test class removed.
- Removed several obsolete test methods from `SmbRepositoryTest`, `SmbRepositoryNetworkIntegrityTest`, `SmbRepositoryPerformanceTest`, and `NotificationFeatureTest` that tested removed search/upload APIs.

### Developer Notes
- `TransferWorker`: WorkManager `Worker` with foreground service notification, connection pooling (`Map<Integer, Session>`), chunked I/O with 256 KB buffer, periodic progress persistence (every 2 MB), and disk space validation during downloads.
- `PendingTransfer` / `PendingTransferDao` / `TransferDatabase`: Room-based persistence for the transfer queue with status tracking (PENDING, ACTIVE, COMPLETED, FAILED, CANCELLED), byte-level progress, retry count, and error messages.
- `SearchWorker` / `SearchDatabase` / `SearchResult` / `SearchResultDao`: Room-based persistence for background search results with live observation via LiveData.
- `TransferQueueViewModel`: ViewModel exposing LiveData from `PendingTransferDao` for the transfer queue UI.
- `TransferItemAdapter`: RecyclerView adapter for transfer items with status icons, progress bars, and action callbacks (retry, cancel, remove).
- `FileOperationsViewModelEnqueueTest`: New test class covering enqueue logic for uploads and downloads.
- `PendingTransferTest`: New test class for `PendingTransfer` entity validation.

If you like this update, support SambaLite here: https://ko-fi.com/egdels • https://www.paypal.com/paypalme/egdels



## [1.8.3] - 2026-03-28
### Improved
- Transfer performance: Increased I/O buffer size from 8 KB to 64 KB for uploads and downloads.
- Upload progress reporting: Throttled progress updates to reduce UI overhead during large file transfers.

### Fixed
- Keyboard remains visible after renaming: On some devices (e.g., Amazon Fire Tablet), the soft keyboard reappeared after dismissing the rename dialog due to a race condition with pending focus events. Fixed by clearing focus on the edit text and adding a delayed secondary `hideKeyboard()` call (100ms) in the dialog's `OnDismissListener`.
- FAB buttons stuck in multi-selection state: After starting a batch download or delete via the floating action buttons, the selection UI (FAB icons, selection count) was not cleared. Added `clearSelection()` calls to both FAB click handlers to match the existing toolbar menu behavior.
- SAF folder picker missing "Use this folder" option: When reopening the folder picker for downloads, the previously used folder was shown but the "Use this folder" button was absent. Fixed by storing the last used download folder URI and setting `EXTRA_INITIAL_URI` on the picker intent. The stored URI is converted from a tree URI to a document URI via `DocumentsContract.buildDocumentUriUsingTree()`, which is required for the SAF picker to recognize the folder and offer the selection button.
- Duplicate progress dialogs: Prevented duplicate progress dialog creation when multiple operations start in quick succession by introducing a `dialogShowPending` guard flag.
- Upload/download state race condition: Fixed a race condition where the uploading/downloading LiveData could reflect a stale count by reading the AtomicInteger inside the posted Runnable instead of capturing a local snapshot.

If you like this update, support SambaLite here: https://ko-fi.com/egdels • https://www.paypal.com/paypalme/egdels

## [1.8.2] - 2026-03-28
### Added
- Timestamp preservation on download: Remote file timestamps are now preserved when downloading files, both for manual downloads and folder sync. New `TimestampUtils` utility class with `setLastModified()` for `java.io.File` and `trySetLastModified()` as SAF best-effort via `Files.setLastModifiedTime()` over `/proc/self/fd/`.
- Robust sync comparison logic: New `SyncComparator` class compares files using size + timestamp with configurable tolerance (default: 3 seconds), replacing strict timestamp comparison that caused unnecessary re-transfers.
- Sync metadata database: New Room database (`SyncDatabase`) with `FileSyncState` entity and `SyncStateStore` wrapper for tracking remote file metadata (size, timestamp, sync time). Enables reliable sync decisions independent of unreliable SAF filesystem timestamps.
- Storage capability detection: New `StorageCapabilityResolver` and `TimestampCapability` enum classify storage targets as `PRESERVE_SUPPORTED`, `BEST_EFFORT`, or `UNRELIABLE` based on URI scheme.
- Timestamp statistics in System Monitor: New section showing tracked files count, timestamp preservation success rate, and SAF file percentage.
- Sync action logging for timestamp operations: `SyncActionLog` and `TransferActionLog` extended with `TIMESTAMP_SET`, `TIMESTAMP_FAILED`, and `DELETED` actions with summary counting.
- Comprehensive `[TIMESTAMP]`-prefixed logging across all download and sync paths for diagnostics via Logcat filtering.
- DB cleanup: Sync state entries are automatically deleted when sync configurations are removed or when remote files no longer exist.

### Changed
- `FolderSyncWorker`: Both sync directions (`syncLocalToRemote`, `syncRemoteToLocal`) now use `SyncComparator` and `SyncStateStore` for comparison instead of direct timestamp comparison. DB state is checked first as a fast path before falling back to filesystem comparison.
- `FileOperations`: All three copy methods (`copySingleFileWithProgress`, `copyFileToUriAsync`, `copyFileToUri`) now attempt to preserve the source file's timestamp on the destination URI after copying.
- `SmbRepositoryImpl`: All download methods (`downloadFileWithRetry`, `downloadFileDirectly`, `downloadFileWithProgressCallback`) now read the remote timestamp and set it on the local file via `TimestampUtils.setLastModified()`.

### Fixed
- Timestamp lost during file copy: Downloaded files had the correct timestamp on the temp file but lost it when copied to the final destination URI. Now `trySetLastModified()` is called after every copy operation.
- Unnecessary re-uploads in bidirectional sync with SAF: When SAF couldn't preserve timestamps, the local file's timestamp differed from the remote, causing re-uploads on every sync. Fixed by using DB-stored metadata as the primary comparison source.
- Main thread database access crash: `SyncStateStore.deleteAllForRoot()` was called on the main thread when deleting sync configurations, causing `IllegalStateException`. Moved to background thread via `Executors.newSingleThreadExecutor()`.

### Known Limitations
- Setting the file timestamp on the device is not reliably possible for SAF (Storage Access Framework) destinations. Android's SAF API does not provide a `setLastModified()` method, and the best-effort workaround via `Files.setLastModifiedTime()` over `/proc/self/fd/` is blocked by `AccessDeniedException` on most devices (confirmed on GrapheneOS and stock Android). This is a platform limitation, not a bug. The app compensates by storing remote timestamps in a local metadata database (`SyncStateStore`), ensuring correct sync behavior regardless of the local file's timestamp.

### Dependencies
- Updated `androidx.room` from 2.7.1 to 2.8.4 (`room-runtime`, `room-compiler`, `room-testing`).

### Developer Notes
- `TimestampUtils`: New utility class in `util/` with `setLastModified(File, long)` for direct file timestamps and `trySetLastModified(Context, Uri, long)` for SAF best-effort via `/proc/self/fd/`. Includes `SmartErrorHandler` integration for repeated failures.
- `SyncComparator`: New class in `sync/` with `isSame()`, `isRemoteNewer()`, `isLocalNewer()` methods using configurable timestamp tolerance.
- `SyncStateStore`: High-level wrapper around `FileSyncStateDao` with logging and exception handling. Uses `SyncDatabase` Room singleton.
- `FileSyncState`: Room entity with `UNIQUE(root_uri, relative_path)` constraint. Fields: `remote_size`, `remote_last_modified`, `synced_at`, `timestamp_preserved`.
- `StorageCapabilityResolver`: Classifies URIs by storage type (file → SUPPORTED, external storage → BEST_EFFORT, other SAF → UNRELIABLE).
- `SystemMonitorActivity.getTimestampStatus()`: Queries `SyncDatabase` for timestamp statistics displayed in the monitor.

If you like this update, support SambaLite here: https://ko-fi.com/egdels • https://www.paypal.com/paypalme/egdels

## [1.8.1] - 2026-03-28
### Added
- "Manual only" sync interval option: Users can now disable periodic synchronization and trigger sync exclusively via "Sync now". New string resource `sync_interval_manual` added in all 7 supported languages (EN, DE, ES, FR, NL, PL, ZH).

### Fixed
- SMB timestamp preservation on upload: Original file timestamps are now correctly transferred to the remote file after both single-file and folder uploads. Previously, uploaded files showed the upload time instead of the original modification date.
- `STATUS_ACCESS_DENIED` when setting remote file timestamps: Changed AccessMask from `GENERIC_WRITE` to `FILE_READ_ATTRIBUTES + FILE_WRITE_ATTRIBUTES` in both `SmbRepositoryImpl` and `FolderSyncWorker`, resolving the `QueryInfo failed` error on NAS systems that don't grant `FILE_READ_ATTRIBUTES` via `GENERIC_WRITE`.
- SMB client configuration in `FolderSyncWorker`: The sync worker now uses the connection's encryption, signing, and dialect settings (via new `createSmbClient()` method) instead of an unconfigured default `SMBClient`.
- Temporary file timestamp loss during upload: When copying a file from a content URI to a temp file for upload, the original `lastModified` timestamp from `DocumentFile` is now preserved on the temp file, ensuring the correct timestamp is passed to `setRemoteFileLastModified()`.
- Manual sync swipe-kill resilience: "Sync now" operations now use Expedited Work with a Foreground Service notification, preventing Android from killing the sync when the app is swiped away. Added `foregroundServiceType="dataSync"` declaration for WorkManager's `SystemForegroundService` in `AndroidManifest.xml` to fix a `FATAL EXCEPTION` crash (`foregroundServiceType 0x00000001 is not a subset of 0x00000000`).

### Developer Notes
- `FolderSyncWorker.createSmbClient(SmbConnection)`: New method that mirrors `SmbRepositoryImpl.getClientFor()` to apply encryption, signing, and SMB dialect settings from the connection configuration.
- `SmbRepositoryImpl.setRemoteFileLastModified()`: New private method for setting remote file timestamps after upload, using `FILE_READ_ATTRIBUTES + FILE_WRITE_ATTRIBUTES` AccessMask.
- `FileOperations.getLastModifiedFromUri()`: New private method to extract the original last-modified timestamp from content URIs via `DocumentFile` or `DocumentsContract`.
- `SyncManager.addSyncConfig()` now accepts interval 0 (manual); `schedulePeriodicSync()` cancels periodic sync when all configs are manual.
- `FolderSyncWorker`: Skips configs with `intervalMinutes ≤ 0` during periodic sync execution.
- `FolderSyncWorker.getForegroundInfo()`: New method providing a `ForegroundInfo` with notification channel `FOLDER_SYNC_OPERATIONS` and `FOREGROUND_SERVICE_TYPE_DATA_SYNC`. Worker calls `setForegroundAsync()` at start of `doWork()` for both manual and periodic syncs.
- `SyncManager.triggerImmediateSync()`: Both overloads now use `setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)` for swipe-kill protection.
- `FolderSyncWorker.isStopped()` handling: Manual syncs return `Result.retry()` (job resumes after kill), periodic syncs return `Result.success()` (next scheduled run catches up).

If you like this update, support SambaLite here: https://ko-fi.com/egdels • https://www.paypal.com/paypalme/egdels

## [1.8.0] - 2026-03-27
### Added
- Biometric and device credential authentication: New `BiometricAuthHelper` enables optional biometric or PIN/pattern authentication before accessing shares and revealing saved passwords. Configurable via a new Security Settings dialog (`dialog_security_settings.xml`) accessible from the main menu.
- Transfer Action Log (`TransferActionLog`): Persistent log of file transfer actions (cache hits, cache misses, downloads, uploads) stored in SharedPreferences for diagnostics and debugging.
- Swipe-kill recovery: Active file operations are now persisted to SharedPreferences before process termination. On next launch, a Snackbar informs the user that a previous operation was cancelled.
- Stale cache temp file cleanup: `SmbBackgroundService` automatically removes orphaned `download*.tmp` files older than 1 hour from the cache directory on startup.
- Smart cache validation: Downloads now check for existing cache files with matching size and timestamp, skipping redundant re-downloads (cache hit/miss logging via `TransferActionLog`).
- Remote file existence check before upload: Uploads now verify whether the file already exists on the server before starting the transfer, allowing the user to confirm or cancel.
- `getRemoteFileSize()` method in `SmbRepository` for querying remote file sizes.
- Staging progress callback (`StagingProgressCallback`) for URI-to-file copy operations with byte-level progress reporting.
- New unit tests: `FileOperationsViewModelCacheTest`, `TransferActionLogTest`, `TimestampPreservationTest`, `SwipeKillBehaviorTest`.
- Translations for all new security, swipe-kill recovery, and progress strings in all supported languages (DE, ES, FR, NL, PL, ZH).

### Changed
- Progress dialog is now deferred until the activity reaches RESUMED state, preventing crashes when the activity is not yet fully visible. Pending dialog requests are cancelled to avoid duplicates.
- Upload flow redesigned: Progress dialog is shown only after the remote existence check completes and the user confirms (or the file is new), providing clearer feedback.
- Byte-level progress formatting for both download and upload operations using `ProgressFormat`.
- "Finalizing" state added to `FileOperationsViewModel` to indicate post-transfer copy-to-destination phase in the UI.
- Gradle: `versionCode` bumped to 10800, `versionName` updated to 1.8.0.

### Fixed
- `ProgressFormat.Op.DOWNLOAD` label corrected.
- `BatteryOptimizationUtils` annotation fix.
- `DialogHelper` minor layout adjustment.
- Notification feature test updated for compatibility with new service behavior.

### Developer Notes
- `BiometricAuthHelper`: Wraps AndroidX Biometric API; checks availability via `BiometricManager` and triggers `BiometricPrompt` with `BIOMETRIC_WEAK | DEVICE_CREDENTIAL`.
- `TransferActionLog`: SharedPreferences-backed circular log (max 200 entries) with timestamped action records and JSON serialization.
- `PreferencesManager`: New preference keys for security settings (`auth_required_for_access`, `auth_required_for_password_reveal`).
- `SmbBackgroundService.onCreate()`: Added `cleanupStaleCacheTempFiles()` call for orphaned temp file removal.
- `ProgressController`: Lifecycle-aware dialog deferral via `LifecycleEventObserver` to avoid `IllegalStateException` on early dialog show.
- `FileOperationsController`: Refactored upload and download flows with pre-checks, staging progress, and cancel support.

If you like this update, support SambaLite here: https://ko-fi.com/egdels • https://www.paypal.com/paypalme/egdels

## [1.7.0] - 2026-03-14

### Added
- Modernized all dialogs (Rename, Search, Sort, Progress, Sync Setup, Loading, Share) with Material Design 3 styling matching the Connection dialog: header with icon card, title and subtitle, MaterialCardView sections, and consistent 24dp padding.
- New custom layouts for Share dialogs (`dialog_share_upload.xml`, `dialog_share_needs_target.xml`, `dialog_upload_complete.xml`, `dialog_file_exists.xml`) with Material Design 3 header and card sections.
- New "Change folder" dialog (`dialog_change_folder.xml`) for Share workflow, displaying available connections as clickable Material cards (matching the Shares section in the Connection dialog).
- Night theme variant for `Theme.SambaLite.Dialog` to ensure correct text colors in Dark Mode.
- Translations for all new dialog subtitles and labels in all supported languages (DE, ES, FR, NL, PL, ZH).
- Automatic service shutdown: The background SMB service now stops automatically when all activities are closed and no file transfers are active, eliminating the need to manually stop the service.

### Changed
- Replaced CheckBox with MaterialSwitch in Search and Sort dialogs for a more modern appearance.
- Replaced ProgressBar with LinearProgressIndicator (Material Design 3) in the Progress dialog.

### Fixed
- Memory leak: `SmbBackgroundService$LocalBinder` (non-static inner class) held an implicit strong reference to the destroyed service, preventing garbage collection. Converted to a static class with `WeakReference`.

### Developer Notes
- `BackgroundSmbManager`: New `visibleActivityCount` (AtomicInteger) tracks visible activities. New `onActivityStarted()`/`onActivityStopped()` methods auto-stop the service when count reaches zero and no operations are active. Auto-stop check also added in `finishOperation()`.
- `MainActivity` and `FileBrowserActivity`: Added `onStart()`/`onStop()` lifecycle overrides to call the new activity tracking methods.
- `SmbBackgroundService.LocalBinder`: Converted from non-static inner class to `static` class with `WeakReference<SmbBackgroundService>`. Added `clearService()` called in `onDestroy()`.
- `BackgroundSmbManager.onServiceConnected()`: Updated to handle nullable return from `getService()`.

If you like this update, support SambaLite here: https://ko-fi.com/egdels • https://www.paypal.com/paypalme/egdels

## [1.6.0] - 2026-03-14

### Added
- Adaptive launcher icons for improved visual consistency across Android devices and launchers.
- `SharesDiffCallback` for efficient RecyclerView list updates when browsing SMB shares.
- GitHub Actions workflow for automated code quality checks (CI).
- New unit tests for caching, file utilities, diff callbacks, and progress formatting.
- Open files directly from SMB shares: New utility classes for file opening (`FileOpenHelper`), MIME type resolution (`MimeTypeUtils`), cache management (`OpenFileCacheManager`), and a custom `FileProvider` with `_data` column support for broad app compatibility.
- `downloadToCache` method in `FileOperationsViewModel` for managing open-file downloads and cache handling.
- Leave confirmation dialog when navigating away from the file browser during active file transfers.
- New unit tests for `MimeTypeUtils` and `OpenFileCacheManager` covering MIME type resolution, cache cleanup, and size-based eviction logic.
- Translations for leave confirmation and file open strings in all supported languages.
- FileProvider configuration (`file_paths.xml`) for external file sharing via content URIs.

### Changed
- Comprehensive `@NonNull` and `@Nullable` annotations added across the entire codebase (~40 classes) for improved null safety, API clarity, and `NullPointerException` prevention. Affected areas include ViewModels, controllers, adapters, repositories, utilities, cache classes, sync classes, Dagger components, and more.
- Enabled stricter lint checks with a new lint baseline for better code quality enforcement.
- Updated WorkManager dependencies to version 2.11.1.
- Removed unused resources, plurals, and obsolete strings; simplified layout attributes for dialogs.
- Refactored `SambaLiteApp` and utility classes for enhanced readability and formatting consistency.
- Removed redundant API-level checks in `BatteryOptimizationUtils`.
- Replaced `getMimeType` implementation in `FolderSyncWorker` with shared utility method from `MimeTypeUtils`.
- Prevented potential memory leaks in `DialogController` by replacing strong `Context` references with `WeakReference` and adding null checks.
- Made `instance` field volatile in `IntelligentCacheManager` to ensure thread-safe singleton access.
- Improved keyboard handling: New utility method for hiding keyboard in dialogs; dialogs now resize correctly with keyboard visibility.
- Added file transfer activity checks before finishing `FileBrowserActivity` and streamlined FAB visibility logic.
- Added default case handling in `SmartErrorHandler` switch statements for improved code clarity.
- Used UTF-8 charset explicitly in `hashString` method and simplified fallback hash calculation logic.
- Removed redundant code: unused `Locale` import and `getSubnetFromIp` method in `NetworkScanner`, unused `lastByteCount` field in `BandwidthMonitor`, redundant `if` checks in `DiscoveredServerAdapter` and `SharesAdapter`, unused `isLifecycleAtLeastStarted` method in `ProgressController`, redundant fallback cache key logic in `SearchViewModel`, unnecessary type checks in `SerializationValidator`.
- Suppressed lint warnings in `FileListCacheOperations`, `CacheMaintenanceManager`, `SmbBackgroundService`, `SmbRepositoryImpl`, `SearchCacheOperations`, and `ShareReceiverActivity` for future-use variables and non-API types.
- Open-file cache cleanup now runs on app start in `SambaLiteApp` initialization.
- Updated `AndroidManifest.xml`: Added FileProvider for external file sharing and handle configuration changes in `MainActivity`.

### Fixed
- `FileDiffCallback.areContentsTheSame` now compares timestamps correctly.
- Keyboard dismissal logic fixed in `MainActivity` dialogs.

### Developer Notes
- Lint baseline file added to track and gradually resolve pre-existing lint issues.
- `SharesDiffCallback`: New `DiffUtil.ItemCallback` implementation for `SmbFileItem` lists, enabling efficient partial RecyclerView updates.
- Null safety annotations applied systematically to: `FileBrowserUIState`, `FileListController`, `FileListViewModel`, `ConnectionAdapter`, `FileOperationCallbacks`, `FileSkippedException`, `FileUploadTask`, `InputController`, `LogUtils`, `PreferenceUtils`, `ProgressController`, `ProgressFormat`, `UIHelper`, `UserFeedbackProvider`, `ActivityResultController`, `DialogController`, `MemoryCacheStrategy`, `DiscoveredServerAdapter`, `SmbFileItem`, `LoadingIndicator`, `SearchCacheOperations`, `ShareReceiverViewModel`, `ShareReceiverActivity`, `SimplePerformanceMonitor`, `BatteryOptimizationUtils`, `SmartErrorHandler`, `CacheKeyGenerator`, `CacheExceptionHandler`, `SambaLiteApp`, Dagger components, `ViewModelFactory`, `ConnectionRepository`, `SmbRepository`, and related test classes.
- CI workflow: GitHub Actions pipeline for lint, unit tests, and build verification.
- `MimeTypeUtils`: Extracted shared MIME type resolution utility, used by both `FolderSyncWorker` and the new file open feature.
- `OpenFileCacheManager`: Size-based eviction cache for temporarily stored files opened from SMB shares.
- `DialogController`: Refactored to use `WeakReference<Context>` to prevent activity leak on configuration changes.

If you like this update, support SambaLite here: https://ko-fi.com/egdels • https://www.paypal.com/paypalme/egdels

## [1.5.2] - 2026-03-09

### Added
- Sync Activity Log: All sync actions (uploads, downloads, skips, errors, directory creations) are now recorded with timestamps and displayed in the System Monitor. Shows the 25 most recent entries (newest first) with a summary of total actions.

### Changed
- Improved MIME type detection for folder sync: Replaced hardcoded MIME type mapping (only 7 file types) with Android's `MimeTypeMap`, supporting all file types known to the platform. This prevents file extension corruption when downloading files with uncommon extensions (e.g. `.docx`, `.xlsx`, `.webp`, `.flac`) via the Storage Access Framework.

### Fixed
- System Monitor: Bottom content was partially obscured by the navigation bar. Added dynamic WindowInsets handling to ensure full scrollability.

### Developer Notes
- `FolderSyncWorker.getMimeType()`: Replaced manual if-chain with `MimeTypeMap.getSingleton().getMimeTypeFromExtension()`. Unknown extensions still fall back to `application/octet-stream`.
- New test class `FolderSyncWorkerGetMimeTypeTest` with 22 unit tests covering null, no extension, known types, uppercase, multiple dots, and unknown extensions (`.tqd`).
- Updated `docs/sync_user_guide.md` with new "Supported File Types" section and limitation note for uncommon file extensions.
- New class `SyncActionLog`: Persistent sync action log using SharedPreferences (FIFO, max 100 entries). Supports UPLOADED, DOWNLOADED, SKIPPED, CREATED_DIR, and ERROR actions with timestamps.
- `FolderSyncWorker`: Integrated `SyncActionLog` calls at all upload, download, skip, error, and directory creation points.
- `SystemMonitorActivity`: New `getSyncActivityLog()` section displaying the 25 most recent sync actions with summary counts. Added `ViewCompat.setOnApplyWindowInsetsListener` for dynamic bottom padding based on navigation bar height.
- `activity_system_monitor.xml`: Added `id` and `clipToPadding="false"` to `NestedScrollView` for proper WindowInsets handling.
- New test class `SyncActionLogTest` with 15 unit tests covering all action types, ordering, formatting, clear, and timestamp format.

If you like this update, support SambaLite here: https://ko-fi.com/egdels • https://www.paypal.com/paypalme/egdels

## [1.5.1] - 2026-03-08

### Added
- Hide hidden files/folders: New "Show hidden files/folders" toggle in the sort dialog. Files and folders starting with a dot (e.g. `.config`, `.cache`) are now hidden by default, reducing clutter when browsing Linux home directories or other locations with many dotfiles.
- The setting is persisted across app restarts and applies to both normal browsing and search results.
- Translations for the new option in all 7 languages (EN, DE, ES, FR, NL, PL, ZH).

### Fixed
- Sync icon was incorrectly shown on parent folders (e.g. "Podcasts") when only a deeper subfolder (e.g. "Podcasts/BundB") was configured for sync. Now only the actual sync-configured folder displays the sync icon.
- Local folder collision check for sync configurations: Prevents setting up sync on a local folder that is already synced, or is a parent/child of an already-synced folder. Supports Android Content-URIs with encoded path separators (`%2F`). Translations in all 7 languages.
- Memory leak: `FileBrowserUIState` (Dagger singleton) retained a reference to a destroyed `FileBrowserActivity` via `syncFolderDisplay` (TextView), causing a ~2 MB leak on every activity recreation (e.g. rotation).
- Navigation state lost on device rotation: Browsing a subfolder and rotating the device would reset the file browser to the root directory, because `FileBrowserState.setConnection()` cleared the current path on every activity restart.

### Developer Notes
- `PreferencesManager`: New `saveShowHiddenFiles()`/`getShowHiddenFiles()` methods with `show_hidden_files` SharedPreferences key (default: `false`).
- `FileBrowserState`: New `showHiddenFiles` field and `showHiddenFilesLiveData`, loaded from preferences on initialization.
- `FileListViewModel`: New `filterHiddenFiles()` method applied after sorting in all `loadFiles` paths (cache, server, force-refresh) and in search results. New `setShowHiddenFiles()` triggers cache invalidation and reload.
- `DialogController.showSortDialog()`: Wired `show_hidden_files_checkbox` to `FileListViewModel`.
- `dialog_sort.xml` (portrait + landscape): Added CheckBox for the new option.

If you like this update, support SambaLite here: https://ko-fi.com/egdels • https://www.paypal.com/paypalme/egdels

## [1.5.0] - 2026-03-07

### Added
- Continuous folder synchronization: Automatically sync a local folder with a remote SMB folder in the background using WorkManager.
- Sync directions: Local → Remote, Remote → Local, or Bidirectional with configurable intervals (15 min to 24 hours).
- Sync setup dialog accessible from the file browser menu, with folder picker, direction, interval, and remote path configuration.
- Manage sync configurations dialog: view, enable/disable, and delete sync configs.
- "Sync Now" button for triggering immediate one-time sync.
- "Newer Wins" conflict resolution strategy for bidirectional sync.
- Automatic cleanup of sync configurations when a connection is deleted.
- Translations for all sync strings in all 7 languages (EN, DE, ES, FR, NL, PL, ZH).
- Share text from other apps: When sharing text (ACTION_SEND with EXTRA_TEXT) from apps like Notes or Chrome, SambaLite now creates a temporary `.txt` file and uploads it to the last opened SMB folder. The filename is derived from EXTRA_SUBJECT (if available) with a timestamp suffix.
- Automatic cleanup of temporary shared-text files after successful upload staging.
- Quit button: Users can now stop the background service and close the app via a power icon in the toolbar or a "Stop" action in the foreground notification.
- Confirmation dialog when quitting with active operations: Shows the number of running transfers and lets the user choose to cancel them or continue.

### Changed
- Upgraded to JDK 21 (source/target compatibility, GitHub CI workflows).
- Updated Android Gradle Plugin from 8.10.1 to 8.12.0 and Gradle from 8.12 to 8.13.
- Updated dependencies: Kotlin 2.1.0 → 2.3.10, Activity 1.9.2 → 1.12.4, Core-KTX 1.16.0 → 1.17.0, Lifecycle 2.9.4 → 2.10.0, SwipeRefreshLayout 1.1.0 → 1.2.0, Dagger 2.57.2 → 2.59.2, BouncyCastle 1.79 → 1.83, Timber 4.7.1 → 5.0.1, Robolectric 4.16 → 4.16.1, Mockito 5.20.0 → 5.22.0.
- Raised compileSdk and targetSdk from 35 to 36.
- Streamlined welcome screen text: Removed redundant "tap the + button" hint from the welcome subtitle in all 7 languages (EN, DE, ES, FR, NL, PL, ZH). The FAB is self-explanatory.

### Fixed
- Fixed System Monitor button not working when inside a folder (FileBrowserActivity). The menu handler was missing; tapping the button had no effect outside the main screen.
- Fixed `BackgroundSmbManager.ensureServiceStartedAndBound()`: `bindService` now executes even when `startForegroundService` fails (fallback behavior).
- Fixed `BackgroundSmbManager.requestCancelAllOperations()`: Falls back to `bindService` with `ACTION_CANCEL` when `startForegroundService` fails.
- Fixed `SmartErrorHandler.setupGlobalErrorHandler()`: Resolved recursion bug where the previous `UncaughtExceptionHandler` was queried at runtime instead of being captured before replacement, causing a StackOverflow.
- Eliminated Mockito dynamic agent loading warning by attaching `byte-buddy-agent` as a `-javaagent` JVM argument.
- Resolved SLF4J "No providers found" warning by adding `slf4j-simple` test dependency.
- Suppressed Robolectric SDK 36 warning by pinning tests to SDK 34.
- Removed deprecated `Notification.priority` usage in tests; replaced with `NotificationChannel.getImportance()`.
- Suppressed unchecked cast warning in `LiveDataTestUtil`.
- Network scan dialog no longer dismisses when touching outside the dialog, preventing accidental scan resets during scanning or when viewing results.
- Add/Edit connection dialogs no longer dismiss when touching outside the dialog, preventing accidental data loss.
- Users must now explicitly use the Cancel button to dismiss dialogs, matching expected behavior.
- Quit/Stop button in the foreground notification now works correctly. Previously, the background service was automatically restarted after being stopped.
- Downloads and uploads after quit and reopen now work correctly. Previously, operations would queue indefinitely because the service binding was not properly restored.
- Foreground notification ("SMB Service Ready") is now consistently restored whenever any SMB operation is performed (download, upload, rename, delete, create folder, browse files). Previously, the notification would not reappear after being stopped until the app was fully restarted.
- Foreground notification now shows the current operation status (e.g. "Renaming: folder", "Downloading: file.txt", "Uploading: photo.jpg") during all file operations. Previously, the notification text remained static ("SMB Service Ready") while operations were running.

### Developer Notes
- New package `sync`: `SyncConfig`, `SyncDirection`, `SyncRepository` (SharedPreferences/JSON), `SyncManager` (WorkManager scheduling), `FolderSyncWorker` (recursive sync logic).
- DI: `AppModule` provides `SyncRepository` and `SyncManager`; `SyncManager` injected into `FileBrowserActivity` and `MainViewModel`.
- `MainViewModel.deleteConnection()` now calls `syncManager.removeConfigsForConnection()`.
- UI: `dialog_sync_setup.xml`, new menu entry `action_sync`, `DialogController` with `SyncSetupCallback`, `ActivityResultController` with sync folder picker.
- WorkManager: unique periodic work `sambalite_folder_sync`, `NetworkType.CONNECTED` constraint, exponential backoff.
- Tests: `SyncRepositoryTest` (16 tests), `SyncManagerTest` (14 tests).
- `ShareReceiverActivity`: Added `createTempTextFile()` for EXTRA_TEXT handling; files stored in `<cache>/shared_text/` with sanitized filenames.
- `FileOperationsController`: Added `cleanupSharedTextSourceFile()` to delete temp files after staging.
- Tests: `ShareReceiverActivityTest` (16 tests), `FileOperationsControllerCleanupTest` (4 tests).
- `robolectric.properties` created with `sdk=34` to avoid SDK 36/Java 21 mismatch warnings.
- Mockito agent is now attached statically via `doFirst` block in `unitTests.all` (Gradle).
- `SmbBackgroundService`: Added `ACTION_STOP` handling (cancels operations, stops foreground, stops self). Stop action shown in notification when idle. Added `stopRequested` flag to prevent service restart after explicit stop. `ACTION_STOP` now explicitly cancels all running futures, stops the watchdog, and calls `notificationManager.cancel()` to ensure the notification is removed.
- `BackgroundSmbManager`: Exposed `hasActiveOperations()`, `getActiveOperationCount()`, and `requestStopService()`. Added `stopRequested` flag; `onServiceDisconnected` no longer auto-restarts the service when a stop was requested. `requestStopService()` now unbinds the service before sending `ACTION_STOP`, so `stopSelf()` can take effect. Added `ensureServiceRunning()` to allow restoring the service after quit and reopen. The `stopRequested` flag is reset when the service is intentionally restarted via `ensureServiceStartedAndBound()`. On reconnect, the manager mirrors the service's `stopRequested` state. `requestStopService()` now also resets `bindingInProgress` to prevent the next `ensureServiceStartedAndBound()` from being blocked (since `unbindService()` does not trigger `onServiceDisconnected`). `ensureServiceRunning()` now handles the case where the service is still bound but was stopped via the notification: it sends a `startForegroundService` intent without re-binding, avoiding `bindingInProgress` deadlock.
- `FileListViewModel` and `FileOperationsViewModel`: Now receive `BackgroundSmbManager` via dependency injection. All SMB operations (loadFiles, downloadFile, downloadFolder, uploadFile, uploadFolderContents, createFolder, deleteFile, renameFile) call `ensureServiceRunning()` before execution, ensuring the foreground service and notification are always active during operations. All file operations in `FileOperationsViewModel` now call `startOperation()`/`finishOperation()` to update the foreground notification with the current operation status (e.g. "Renaming: folder", "Downloading: file.txt").
- `ShareReceiverViewModel`: Updated constructor to pass `BackgroundSmbManager` through to parent `FileOperationsViewModel`.
- `MainActivity`: Added `handleQuit()` with `MaterialAlertDialog` for active operations, `performQuit()` with `requestStopService()` + `finishAffinity()`. Quit icon (`ic_lock_power_off`) shown directly in toolbar. Calls `ensureServiceRunning()` in `onCreate()` to restore the foreground notification after quit and reopen.
- `menu_main.xml`: New `action_quit` menu item with `showAsAction="ifRoom"`.
- `MainActivity.java`: Added `setCanceledOnTouchOutside(false)` and `setCancelable(false)` to the network scan, add connection, and edit connection dialogs.

If you like this update, support SambaLite here: https://ko-fi.com/egdels • https://www.paypal.com/paypalme/egdels

## [1.3.2] - 2026-01-14

### Changed
- Improved button visibility in Scan and Connection dialogs for large font settings: buttons now use full-width vertical layout.
- Increased minimum height of Scan and Connection dialogs (400dp) for better usability on various display configurations.
- Streamlined welcome screen by removing redundant "Add Connection" button (FAB remains available).

### Fixed
- Buttons in Network Scan dialog were not visible with certain display and font settings.
- Buttons in Add/Edit Connection dialog were not visible with large font configurations.

### Developer Notes
- `dialog_network_scan.xml`: Wrapped in `NestedScrollView` with `fillViewport="true"` and `minHeight="400dp"`; button bar changed to vertical orientation with full-width buttons.
- `dialog_add_connection.xml`: Added `minHeight="400dp"` and `fillViewport="true"`; added custom vertical button bar with Save/Cancel buttons.
- `MainActivity.java`: Updated Add and Edit connection dialogs to use custom buttons instead of AlertDialog default buttons.
- `activity_main.xml`: Removed `welcome_add_button` from welcome card.

## [1.3.1] - 2025-10-19

### Added
- Multi-select for files with batch Download and Delete actions.

### Changed
- Reused existing progress UI and Cancel flow; hardened dialog lifecycle (reliable open/close on success, error, or cancel).
- Unified wording and internationalization: replaced hardcoded strings in controllers/viewmodels with resources; consistent labels and summaries.
- Cleanup of temporary files/folders across success, error, and cancel paths.
- Selection UX: long-press to enter selection mode, tap toggles items, toolbar shows selected count; contextual FAB icons in multi-select mode (Download, Delete).

### Developer Notes
- Controller-driven iteration without repository API changes; improved cancel propagation and progress reporting.
- Key components: FileOperationsController, FileOperationsViewModel, ProgressController, and string resources.

If you like this update, support SambaLite here: https://ko-fi.com/egdels • https://www.paypal.com/paypalme/egdels

## [1.3.0] - 2025-10-08

### Added
- Per-connection security options in Add/Edit Connection: toggles to "Require encryption (SMB3)" and "Require signing".
- Translations for the new security options: de, es, fr, nl, pl, zh.

### Changed
- Use a per-connection SMB client configuration (encryption/signing) when needed instead of relying solely on a single global client.

### Fixed
- Connection failures to Samba shares with `smb encrypt = required` or signing requirements. The app now negotiates encrypted/signed sessions when configured per connection.

### Developer Notes
- SmbConnection: added boolean fields `encryptData` and `signingRequired` (persisted via ConnectionRepositoryImpl; defaults to false for existing entries).
- SmbRepositoryImpl: new `getClientFor(SmbConnection)` builds an `SmbConfig` with `withEncryptData(...)` and `withSigningRequired(...)` (fallback to `withSigningEnabled(...)` if needed) and uses it across search/list/withShare.
- UI: `dialog_add_connection.xml` adds security switches; MainActivity wires them for Add/Edit and Test Connection.

## [1.2.10] - 2025-10-06

### Added
- Chinese translation (zh).

## [1.2.9] - 2025-10-01

### Developer Notes

Code cleanup, no functional changes

## [1.2.8] - 2025-09-17

### Improvements
- Share intent: More reliable multi-file uploads. The previous absolute 60-second timeout for entire batches has been removed. Instead, a per-file inactivity watchdog (reset by progress) is used. Long uploads will no longer be aborted prematurely.
- Google Photos (Share): More robust URI handling. For URIs with the authority `com.google.android.apps.photos` we no longer call `takePersistableUriPermission()`; we rely on the temporary read grant from the intent and still perform best-effort self grants. This reduces log warnings and makes starting uploads more reliable.

### Fixed
- Premature cancellation after ~60s when the first shared file is large in the share workflow.

### Developer Notes
- `SmbBackgroundService.executeSmbOperation`: removed absolute batch timeout; only the inactivity watchdog remains.
- `FileBrowserActivity.checkAndHandleShareUpload`: skip persistable permissions for Google Photos URIs.

## [1.2.7] - 2025-09-13

### Added
- Share handoff to FileBrowserActivity: Share uploads are now executed in the main file browser, showing the regular consolidated transfer dialog with a working Cancel action.
- Batch upload for multiple shared files: New FileOperationsController.handleMultipleFileUploads(...) processes shared URIs sequentially inside one background operation, providing a single, smooth progress surface.
- URI permission handover: Self‑grant and persistable read permissions for shared URIs, and ClipData propagation with FLAG_GRANT_READ_URI_PERMISSION during the handoff to ensure reliable staging.

### Changed
- ShareReceiverActivity simplified: No in‑activity uploads or progress UI. It now only parses the share intent, asks for confirmation, hands off to FileBrowserActivity, and finishes.
- Unified progress lifecycle: ProgressController lazily opens the transfer dialog only when the activity is RESUMED, and FileOperationsViewModel emits a single TransferProgress stream for both uploads and downloads.
- FileBrowserActivity notification deep‑links: Opening from upload notifications now immediately shows the regular transfer dialog and navigates to the target path.

### Fixed
- Background continuity: Share uploads are no longer canceled when the Share activity goes to background/destroys; onDestroy no longer aborts transfers.
- Window leaks prevented: Added robust lifecycle gating (RESUMED checks and re‑checks on the UI thread) to avoid android.view.WindowLeaked when activities background during dialog creation.
- Recents duplication: FileBrowserActivity launchMode set to singleTask and ShareReceiverActivity excluded from Recents; prevents multiple app cards when sharing.
- Multi‑file reliability: All shared items are now uploaded; replaced parallel fire‑and‑forget starts with sequenced batch processing guarded by latches.
- Permission Denial during staging: Mitigated by proactively granting/taking persistable URI permissions and passing ClipData across the handoff.

### Developer Notes
- BackgroundSmbManager/SmbBackgroundService continues uploads in the background; UI dialogs are restored when the app returns to foreground.
- ProgressController auto‑opens the transfer dialog upon receiving progress while the activity is RESUMED; otherwise it defers UI until safe.


## [1.2.6] - 2025-09-05

### Fixed
- **Foreground-Service Timeout (API 35/36):** Implemented `onTimeout(...)` to cleanly stop the foreground service on system timeout (releases wakelock, cancels running jobs, calls `stopForeground(STOP_FOREGROUND_REMOVE)` and `stopSelf()`), preventing `RemoteServiceException$ForegroundServiceDidNotStopInTimeException`.
- **Start Not Allowed (API 31+):** Guarded `startForeground(...)` with runtime-safe handling for `ForegroundServiceStartNotAllowedException` (detected by class name on API ≥ 31). The service now exits with `START_NOT_STICKY` instead of crashing when the `dataSync` budget is exhausted.

### Changed
- **Startup Robustness:** If `startForeground(...)` fails for any reason, the service no longer continues running without foreground state; it stops itself deterministically.
- **Cancellation Path:** `cancelAllOperations(...)` now reliably tears down background work and watchdogs so the service can shut down promptly when idle or on timeout.

### Developer Notes
- No change to `minSdk`. All new behaviors are gated by SDK checks (API 31+, API 35+).

## [1.2.5] - 2025-08-30

### Added
- Unified transfer pipeline (upload/download) with `isUploading`, `isDownloading`, `isAnyOperationActive`, and replayable `TransferProgress`.
- Cancel actions wired to UI (`cancelUpload()`, `cancelDownload()`).
- Search flow runs in background with cancellable progress dialog.
- Folder upload (SAF): recursive scan, structure creation, overwrite prompts, multi-file progress, cleanup.
- Edge-to-edge display via `androidx.activity` **EdgeToEdge** helper: borderless layout under system bars, backward-compatible, no deprecated flags.

### Changed
- Transfer dialog lifecycle now driven by `FileOperationsViewModel` (`isAnyOperationActive` + `getTransferProgress()`).
- Kept `fileOperationsController.setProgressCallback(progressController)` for user feedback (info/success/error & overwrite dialog), not for dialog lifecycle.
- Smoother progress: throttled updates, monotonic percentages, accurate byte-based calc, and a “finalizing” phase for local SAF copy.

### Fixed
- Prevented window leaks by closing dialogs in `onDestroy()`.
- Safe UI access checks in `ProgressController`.
- In empty folders, **Upload** and **Create Folder** FABs are shown again (explicitly visible when a directory has no items).

### Removed
- `ServiceController` (responsibilities moved into `BackgroundSmbManager`).

## [1.2.4] - 2025-08-18

The ServiceController was successfully removed in favor of direct usage of BackgroundSmbManager in FileBrowserActivity, maintaining all necessary functionality such as setting the search context and executing background operations. All changes were implemented, built, and tests verified successfully. The solution has been submitted.
The connection resolution for uploads was improved by storing and using a stable connection ID alongside the folder path, instead of relying on the connection name, which can change. This change resolved the issue of uploads failing when the connection name was changed, and the project now builds successfully without errors. The implementation was finalized and is ready for submission.
The Back functionality in the file browser was updated to navigate up one folder level instead of immediately returning to the Connections view, only leading to Connections when at the topmost folder. The implementation was validated with a successful build. No errors were encountered during the modification process.
Fix: Upload path normalization no longer duplicates the share name. A thread-local active share name is set during SMB operations and getPathWithoutShare strips it reliably (case-insensitive), preventing paths like \\server\share\share\file and fixing STATUS_OBJECT_PATH_NOT_FOUND during uploads. Built successfully.
Search caching was improved to handle server-side file changes by removing cache entries only when necessary and managing search state upon connection changes. A generation token was introduced to prevent stale updates after cancellations. All changes were verified, and there were no errors reported during testing.

### Added
- File browser: Floating action buttons now automatically hide when scrolling down and reappear when scrolling up or at the top of the list, providing more screen space while browsing files.
- Back from search results: Pressing BACK while viewing search results exits search mode and returns to the exact folder where the search was started. Works for both toolbar back and system back; also cancels any in-flight search safely.

### Changed
- Search caching: Introduced stale-while-revalidate with throttled background revalidation (per key, ≥2 min interval). Cached results show immediately; a background refresh updates the cache/UI if the server-side content changed.
- Search robustness: Added a generation token to prevent stale UI updates after cancellations or context switches; isolated searches by connection to avoid cross-connection leakage of results.
- Removed deprecated Android APIs and suppressions across the app:
  - Replaced legacy system UI flags (SYSTEM_UI_FLAG_*) with WindowCompat edge-to-edge configuration.
  - Migrated deprecated network APIs (NetworkInfo, WifiManager.getConnectionInfo) to ConnectivityManager with LinkProperties/NetworkCapabilities in NetworkScanner.
  - Updated parcelable retrieval to type-safe getParcelableExtra(key, Class) on Android 13+ with IntentCompat fallbacks for older versions.
- Modernized Gradle configuration for AGP 8.10.x:
  - Switched from deprecated lintOptions { } to lint { } DSL and consolidated disabled checks.
  - Switched from minSdkVersion to minSdk in defaultConfig.
- General deprecation cleanup: removed remaining @SuppressWarnings("deprecation") and aligned code with current Android SDK recommendations.
- Notifications: Operation notifications for Search, Upload, and Download remain tappable but no longer show a Cancel action button.

### Fixed
- Tapping an operation notification (Search/Upload/Download) now opens the correct screen and dialog, even if the file browser is already running (added proper PendingIntent and onNewIntent handling). Additionally set FileBrowserActivity launchMode to singleTop and added CLEAR_TOP | SINGLE_TOP flags to notification intents to prevent activity recreation that previously canceled running searches.
- Immediate notification refresh on operation context changes prevents stale tap targets (e.g., after a search followed by a large download, tapping now opens the Download dialog instead of the Search dialog).
- After cancelling a search and starting a download, tapping the notification no longer opens the Search dialog. The service now derives and updates the notification deep-link context at operation start (Search/Upload/Download), ensuring the tap target is always correct immediately.
- Tapping a download/upload/search notification no longer cancels the running operation: the generic fallback intent now brings FileBrowserActivity to front instead of launching MainActivity, avoiding Activity finish and unintended cancellation.
- Search robustness during transfers: If a search refresh times out because another operation (upload/download) is running, existing search results are preserved and the UI is not cleared to 0 items.
- Back from cached search results: Pressing BACK now exits search mode and returns to the starting folder even when results came from cache (previously the UI could stay on search results and required repeated presses).
- Prevented stale or post-cancel search results from overwriting the UI by guarding updates with the generation token.
- Ensured cached search results are only applied if still current; background revalidation updates them when server-side changes are detected.

> Developer note: These changes eliminate deprecation warnings on current toolchains (Java 17, AGP 8.10.1) and improve forward compatibility without altering app behavior.

## [1.2.3] - 2025-08-13

### Added
- **Finalization service bridge:** Status updates during the local SAF finalization step (e.g., “Finalizing…”, “scanning files”, “copying …”, “done”) are now mirrored to the foreground service notification.
- **Multi-file progress to service:** The controller propagates per-file and byte progress to the service via `MultiFileProgressCallback` where available (single file upload staging and folder-contents upload).
- **Upload staging feedback:** During single-file uploads, the “staging/preparing” step (URI → temp file) now reports progress both in the UI and in the service notification.

### Changed
- **BackgroundSmbManager API:** Simplified `executeMultiFileOperation(String id, String name, MultiFileOperation<T> op)` (removed the `totalFiles` parameter). All controller call sites updated accordingly.
- **Controller → Notification mirroring:** The controller now forwards progress text from finalization phases to the foreground service (e.g., via `cb.updateProgress(...)` in wrapped callbacks), keeping notifications live while local copies run.
- **Callback wrapping with latches:** The controller wraps operation callbacks with a `CountDownLatch` to keep the service operation alive until the real completion callback fires (prevents premature notification completion).
- **Single-file upload staging:** Improved error and resource handling during temp-file staging; temp files are reliably cleaned up and the service is informed on failure/success.
- **Progress UX smoothing:** SMB phase is clamped to `100 - FINALIZE_WINDOW_PCT`, with the finalization phase using the remaining window for steadier overall percentages across UI and notification.
- **FileOperations throughput & accuracy:** Increased I/O buffer to 64 KB, added a pre-scan (file/byte totals) and consolidated status lines (`"Copying X/Y – name • n% (bytes)"`) for more realistic overall percentages.
- **FileOperationsController:** Moved finalization steps for downloads (both folders and single files) to asynchronous execution.
- **FileOperations:** Introduced `copyFolderAsync(...)` and enhanced `copyFileToUri(...)` with buffered streams; local SAF copies now run non-blocking and report progress.

### Fixed
- **ANR During Large Folder Copies:** Resolved an issue where synchronous copying of folders/files on the main thread could cause the app to freeze.
- **Folder Download Finalization:** The final copy step into the selected SAF destination folder now runs fully in the background, even with deeply nested directory structures.
- **Large File Download Finalization:** Copying of large single files into SAF destinations no longer blocks the user interface.
- **UI Responsiveness:** Significantly improved app responsiveness during and after long-running file operations.
- **Build & API mismatches:** Updated all controller invocations to the new `executeMultiFileOperation` signature.
- **Inner-class captures:** Resolved “local variables must be final or effectively final” issues (e.g., by capturing `finalTempFile` in wrapped callbacks).
- **Robust error propagation:** Staging errors (e.g., `copyUriToFile`) are surfaced to the service and close the operation cleanly with a failure notification.

> **Note:** Local SAF copies now have a cancellation hook in `FileOperations.Callback#isCancelled()`. Wiring a cancel token from the UI into these callbacks (to abort mid-copy) is prepared and can be completed in a follow-up release.

## [1.2.2] - 2025-08-09

### Fixed
- **Folder Download Freeze at 100%**: Resolved an issue where downloading a folder could cause the app to freeze at 100% progress, leaving an empty folder.
- **Upload/Download Finalization**: Ensured proper cleanup and finalization of background file operations to prevent incomplete transfers.
- **Service Binding Reliability**: Improved lifecycle-aware binding/unbinding of the background service to avoid stale connections and missed progress updates.
- **Thread Safety & Callbacks**: Guaranteed main-thread execution for UI callbacks and improved cancellation handling for long-running operations.
- **Notification Permission Flow**: Fixed incorrect ordering of runtime permission checks and settings redirection to ensure proper display of system permission prompts and rationale before opening app settings.

### Changed
- **SmbBackgroundService**: Enhanced operation lifecycle handling with safe cancellation and reliable finalization of transfers.
- **FileOperationsController**: Decoupled from direct service dependencies, improved thread-safety, and ensured completion callbacks for all operations.
- **ServiceController**: Strengthened service connection handling with lifecycle awareness to maintain stability.
- **FileOperationsViewModel**: Improved UI update safety and executor shutdown during cleanup.
- **FileBrowserActivity**: Adjusted integration with `ServiceController` to maintain correct service binding and progress updates.
- **Permission Request Logic**: Reworked notification permission request flow to prioritize system prompts, show rationale on repeated denials, and only offer settings navigation when necessary.

## [1.2.1] - 2025-08-03

### Added
- **Multi-language Support**: Added localization for Polish, Spanish, Dutch, and French
- **Enhanced Sharing**: Improved sharing functionality for devices with Android 11 (API 30) and higher

### Changed
- **Internationalization**: Optimized app resources for international users
- **Sharing Mechanism**: Updated sharing implementation for better compatibility with newer Android versions

## [1.2.0] - 2025-07-20

### Added
- **Enhanced Folder Operations**: Improved handling of folder uploads and downloads with better progress tracking
- **Background Service Integration**: New dedicated service for handling long-running operations
- **Lifecycle-Aware Components**: Better handling of Android lifecycle events to prevent memory leaks
- **Improved Progress Reporting**: More detailed progress information for all file operations

### Changed
- **Major Architecture Refactoring**: Completely restructured codebase following MVVM best practices
  - Split monolithic ViewModels into specialized components (FileListViewModel, FileOperationsViewModel)
  - Extracted UI logic into dedicated controllers (FileOperationsController, ServiceController, ProgressController)
  - Reduced class complexity and improved separation of concerns
- **Enhanced Error Handling**: More robust error recovery mechanisms for network operations
- **Improved Thread Safety**: Better handling of concurrent operations and background tasks
- **UI Responsiveness**: Smoother UI experience during long-running operations

### Fixed
- **Memory Leaks**: Resolved issues with resource management during file operations
- **Cancellation Handling**: Improved cleanup when operations are cancelled
- **Error Reporting**: More descriptive error messages for troubleshooting
- **Edge Cases**: Better handling of special characters in filenames and large folder structures

## [1.1.1] - 2025-07-17

### Fixed
- **Search Hanging**: App no longer hangs when moved to background during search operations
- **Background Operations**: Search operations now automatically cancel when app goes to background
- **Thread Safety**: Added timeout protection to prevent infinite waiting during SMB operations

### Changed
- **Build System**: Centralized version management
- **F-Droid Compatibility**: Maintained compatibility with F-Droid automated builds
- **Developer Experience**: Version updates now require changes in only one file

## [1.1.0] - 2025-07-16

### Added
- **Folder Upload/Download**: Transfer entire folders using ZIP compression
- **Network Discovery**: Automatic scanning for SMB servers on your local network
- **Multi-file Operations**: Select and transfer multiple files with progress tracking
- **Background Transfers**: Large file operations continue in the background
- **Enhanced Search**: Improved search with progress indicator and cancel option

### Changed
- **New Design**: Complete Material Design 3 update with app logo colors
- **Simplified UI**: Removed long-press actions - all options now accessible via menu buttons
- **Better Performance**: Smaller app size and faster file operations
- **Improved Stability**: Better error handling and connection reliability

### Fixed
- Fixed duplicate error messages during network scanning
- Improved keyboard handling in dialogs
- Better progress indication for long-running operations
- Enhanced connection timeout handling

## [1.0.2] - 2025-07-05

### Added
- Support for guest authentication when connecting with empty credentials

## [1.0.1] - 2025-07-04

### Added

Improved reproducibility for F-Droid

## [1.0.0] - 2025-07-02

### Added
- Initial project setup
- Basic MVVM architecture with Dagger 2 for dependency injection
- SMB connection management with secure credential storage
- File browsing functionality
- File operations (download, upload, delete, rename)
- Connection testing
- Wildcard search support (using * and ? characters)
- Ability to cancel ongoing searches

## [0.1.0] - 2025-06-28

### Added
- Initial project structure
- Core architecture and dependencies
- Basic UI components
- SMB client implementation using SMBJ
- Secure credential storage using EncryptedSharedPreferences



---
Note for releases: Please append a short support line at the end of each release entry:
If you like this update, support SambaLite here: https://ko-fi.com/egdels • https://www.paypal.com/paypalme/egdels
