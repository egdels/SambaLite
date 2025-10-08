# Changelog

All notable changes to SambaLite will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
