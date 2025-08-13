# Changelog

All notable changes to SambaLite will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
