1.# SambaLite Test Suite

This directory contains test classes and utilities for testing the SambaLite application.

## Overview

The test suite includes:

1. A hybrid implementation of a Samba server for testing (`SambaContainer`), which automatically chooses between Docker (Testcontainers) and an in-memory mock.
2. Basic tests for server connectivity (`SambaServerTest`).
3. Comprehensive tests for the `SmbRepository` implementation (`SmbRepositoryTest`).
4. Advanced integrity and performance tests in the package `de.schliweb.sambalite.data.repository`.
5. A central `SmbTestHelper` for abstracting server provisioning.

## Samba Server Testing Framework

The `SambaContainer` class provides a test infrastructure that is:

1. **Docker-based:** Uses `Testcontainers` (Image: `dperson/samba`) to start a real Samba server (recommended for MacOS/OrbStack and CI).
2. **In-Memory Mock:** Automatically falls back to a fast Java simulation if Docker is not available.

This allows for realistic protocol tests (SMB2/3) without manual setup steps.

Further details can be found in the central documentation at [docs/SAMBA_TESTING_FRAMEWORK.md](../../../docs/SAMBA_TESTING_FRAMEWORK.md).

## Test Classes

The tests use the `SmbTestHelper` with `AUTO_DETECT` mode.

### SambaServerTest
Validates the basic reachability of the server (Docker or Mock).

### SmbRepository Tests
The project contains extensive test suites to ensure data integrity:
- `SmbRepositoryTest`: CRUD operations.
- `SmbRepositoryAdvancedIntegrityTest`: Concurrency, ZIP handling, and deep structures.
- `SmbRepositoryNetworkIntegrityTest`: Error injection and timeout handling.
- `SmbRepositoryPerformanceTest`: Throughput measurements.

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

1. Follow the pattern established in the existing test classes
2. Use the `SambaContainer` to set up the test environment
3. Create test files and directories as needed
4. Include appropriate assertions to verify the expected behavior
5. Add debug logging to help diagnose issues
6. Handle exceptions appropriately

## Troubleshooting

If you encounter issues with the in-memory Samba server:

1. Check the debug logs for any error messages
2. Ensure that your test is properly setting up the server and shares
3. Be aware that the current implementation is a mock and may not support all SMB features
4. Consider adding more detailed logging to help diagnose issues
