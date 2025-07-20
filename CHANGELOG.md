# Changelog

All notable changes to SambaLite will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
