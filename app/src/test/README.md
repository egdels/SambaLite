1.# SambaLite Test Suite

This directory contains test classes and utilities for testing the SambaLite application.

## Overview

The test suite includes:

1. A hybrid implementation of a Samba server for testing (`SambaContainer`), which automatically chooses between Docker (Testcontainers) and an in-memory mock.
2. Comprehensive tests for the `SmbRepository` implementation (`SmbRepositoryTest`).
3. Advanced edge case and error handling tests (`SmbRepositoryAdvancedTest`).
4. Transactional operation tests (`SmbTransactionalTest`).
5. File integrity tests (`FileIntegrityTest`).
6. A central `SmbTestHelper` for abstracting server provisioning.

## Samba Server Testing Framework

The `SambaContainer` class provides a test infrastructure that is:

1. **Docker-based:** Uses `Testcontainers` (Image: `dperson/samba`) to start a real Samba server (recommended for MacOS/OrbStack and CI).
2. **In-Memory Mock:** Automatically falls back to a fast Java simulation if Docker is not available.

This allows for realistic protocol tests (SMB2/3) without manual setup steps.

Further details can be found in the central documentation at [docs/SAMBA_TESTING_FRAMEWORK.md](../../../docs/SAMBA_TESTING_FRAMEWORK.md).

## Test Classes

The tests use the `SmbTestHelper` with `AUTO_DETECT` mode.

### SmbRepositoryTest
CRUD operations: connect, list, upload, download, delete, rename, createDirectory, fileExists, downloadFolder.

### SmbRepositoryAdvancedTest
Edge cases and error handling: special characters in filenames, deep nested directories, empty files, large files, concurrent operations, invalid credentials, invalid share names, file name collisions, very long file names.

### SmbTransactionalTest
Transactional operations: single file upload/download, batch upload/download, folder upload/download, network break retry.

### FileIntegrityTest
File integrity verification: MD5 hash comparison after upload/download, rename, folder operations, and multi-step operation sequences.

## Usage

To run the tests, use the following command:

```bash
./gradlew test
```

Or run individual test classes:

```bash
./gradlew test --tests "de.schliweb.sambalite.SmbRepositoryTest"
./gradlew test --tests "de.schliweb.sambalite.SmbRepositoryAdvancedTest"
```

## Implementation Notes

The `SambaContainer` class manages dynamic port assignment when using Docker. Tests should always use the `SmbTestHelper` to abstract between mock and container modes.

### Docker on MacOS (OrbStack)
Ensure that OrbStack is running. The framework automatically detects the Docker socket.

### GitHub Actions
The CI is configured to use Docker for all integration tests.

## Adding New Tests

When adding new tests:

1. Follow the pattern established in `SmbTransactionalTest` (no catch-all blocks, synchronous BackgroundSmbManager mock)
2. Use the `SambaContainer` to set up the test environment
3. Create test files and directories as needed
4. Include appropriate assertions to verify the expected behavior
5. Declare exceptions in the test method signature (`throws Exception`) instead of catching them
6. Configure the `BackgroundSmbManager` mock to execute operations synchronously

## Troubleshooting

If you encounter issues with the in-memory Samba server:

1. Check the debug logs for any error messages
2. Ensure that your test is properly setting up the server and shares
3. Be aware that the current implementation is a mock and may not support all SMB features
4. Consider adding more detailed logging to help diagnose issues
