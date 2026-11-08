# Changelog: SambaLite for Mac

All notable changes to the Mac app in `macos/` are documented in this file.
The Android app has its own changelog in the repository root. Mac releases
are tagged `mac-v<version>`, e.g. `mac-v0.1.0`.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-11-07

First release of the native Mac app. It is the same product as the Android app, without shared code, and talks to SMB 2/3 servers directly, without mounting the share.

### Added
- **File browser**: A Finder-like file list with sortable columns, multiple selection, double-click to open, ⌘↑, ⌘[ and ⌘], drag and drop in both directions, and a context menu.
- **Transfer queue**: Transfers survive a restart of the app and resume at the byte where they stopped. They retry by themselves, wait for the network, and show speed and remaining time.
- **Folder sync**: Three directions, schedules from 15 minutes to 24 hours, and an optional mirror mode with the Android app's safeguards. When a file changed on both sides, the other version is kept as a conflict copy.
- **Sync monitor**: Shows per sync whether it works: up to date, with errors, failed, overdue, local folder missing or paused, the last 20 runs, errors grouped by cause with advice, and a searchable log of the last run. "Copy Diagnostics" produces a report for bug reports. Notifications are sent only when the state of a sync changes.
- **Background running**: A menu bar item keeps syncs running with the window closed. "Open at Login" is in the app menu (macOS 13 and later).
- **Connections**: Imports and exports the Android app's connection files. Passwords are stored in the keychain.

### Known limits
- **Not signed with a Developer ID**: The app is ad-hoc signed. macOS blocks it on the first start; see "Installation" in the release notes. After each update the keychain asks once for the stored passwords.
- **Throughput**: libsmb2 handles one request at a time per session; a fast NAS on gigabit Ethernet may not reach line speed.
- **Colons in names**: A file whose name contains `:` (shown as `/` in Finder) is reported as an invalid name.
- **Not in this version**: Server discovery (mDNS, subnet scan) and search across a share.
