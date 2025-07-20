package de.schliweb.sambalite.test.helper;

import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.test.mock.MockSmbServer;
import de.schliweb.sambalite.util.LogUtils;

import java.io.InputStream;
import java.util.List;

/**
 * Test helper for SMB operations during testing.
 * Provides a unified interface for both mock and container-based testing.
 * <p>
 * This helper automatically chooses the appropriate testing approach:
 * - Mock server for fast unit tests
 * - Container-based server for integration tests
 * - Fallback mechanisms for different test environments
 */
public class SmbTestHelper {

    private static final String TAG = "SmbTestHelper";

    private final MockSmbServer mockServer;
    private final TestMode testMode;

    public SmbTestHelper(TestMode mode) {
        this.testMode = mode;
        this.mockServer = new MockSmbServer();

        LogUtils.d(TAG, "SmbTestHelper initialized with mode: " + mode);
    }

    public SmbTestHelper() {
        this(TestMode.AUTO_DETECT);
    }

    /**
     * Creates a test SMB connection with predefined credentials.
     */
    public SmbConnection createTestConnection() {
        return createTestConnection("testshare");
    }

    /**
     * Creates a test SMB connection for a specific share.
     */
    public SmbConnection createTestConnection(String shareName) {
        SmbConnection connection = new SmbConnection();

        if (shouldUseMockServer()) {
            // Mock server configuration
            connection.setName("Mock Test Connection");
            connection.setServer("mock.test.server");
            connection.setUsername("testuser");
            connection.setPassword("testpass");
            connection.setShare(shareName);
            connection.setDomain("WORKGROUP");
        } else {
            // Container configuration (will be filled when container support is active)
            connection.setName("Container Test Connection");
            connection.setServer("localhost");
            connection.setUsername("testuser");
            connection.setPassword("testpass123");
            connection.setShare(shareName);
            connection.setDomain("WORKGROUP");
        }

        LogUtils.d(TAG, "Created test connection: " + connection.getName());
        return connection;
    }

    /**
     * Lists files using the appropriate test server.
     */
    public List<SmbFileItem> listFiles(String path) {
        if (shouldUseMockServer()) {
            MockSmbServer.MockConnection mockConnection = mockServer.connect("mock.test.server", "testuser", "testpass", "testshare");
            return mockServer.listFiles(mockConnection, path);
        } else {
            // TODO: Implement container-based file listing
            throw new UnsupportedOperationException("Container-based testing not yet implemented");
        }
    }

    /**
     * Downloads a file using the appropriate test server.
     */
    public InputStream downloadFile(String filePath) {
        if (shouldUseMockServer()) {
            MockSmbServer.MockConnection mockConnection = mockServer.connect("mock.test.server", "testuser", "testpass", "testshare");
            return mockServer.downloadFile(mockConnection, filePath);
        } else {
            // TODO: Implement container-based file download
            throw new UnsupportedOperationException("Container-based testing not yet implemented");
        }
    }

    /**
     * Uploads a file using the appropriate test server.
     */
    public void uploadFile(String filePath, InputStream inputStream) {
        try {
            if (shouldUseMockServer()) {
                MockSmbServer.MockConnection mockConnection = mockServer.connect("mock.test.server", "testuser", "testpass", "testshare");
                mockServer.uploadFile(mockConnection, filePath, inputStream);
            } else {
                // TODO: Implement container-based file upload
                throw new UnsupportedOperationException("Container-based testing not yet implemented");
            }
        } catch (Exception e) {
            LogUtils.d(TAG, "Failed to upload file: " + filePath + " - " + e.getMessage());
            throw new RuntimeException("Upload failed", e);
        }
    }

    /**
     * Deletes a file using the appropriate test server.
     */
    public void deleteFile(String filePath) {
        if (shouldUseMockServer()) {
            MockSmbServer.MockConnection mockConnection = mockServer.connect("mock.test.server", "testuser", "testpass", "testshare");
            mockServer.deleteFile(mockConnection, filePath);
        } else {
            // TODO: Implement container-based file deletion
            throw new UnsupportedOperationException("Container-based testing not yet implemented");
        }
    }

    /**
     * Creates a directory using the appropriate test server.
     */
    public void createDirectory(String dirPath) {
        if (shouldUseMockServer()) {
            MockSmbServer.MockConnection mockConnection = mockServer.connect("mock.test.server", "testuser", "testpass", "testshare");
            mockServer.createDirectory(mockConnection, dirPath);
        } else {
            // TODO: Implement container-based directory creation
            throw new UnsupportedOperationException("Container-based testing not yet implemented");
        }
    }

    /**
     * Sets up test data in the test server.
     */
    public void setupTestData() {
        if (shouldUseMockServer()) {
            // Mock server already has default test data
            LogUtils.d(TAG, "Using default mock server test data");
        } else {
            // TODO: Setup container test data
            LogUtils.d(TAG, "Container test data setup not yet implemented");
        }
    }

    /**
     * Cleans up test data after tests.
     */
    public void cleanup() {
        if (shouldUseMockServer()) {
            mockServer.reset();
            LogUtils.d(TAG, "Mock server reset");
        } else {
            // TODO: Cleanup container test data
            LogUtils.d(TAG, "Container cleanup not yet implemented");
        }
    }

    /**
     * Enables error injection for testing error scenarios.
     */
    public void setConnectionFailure(boolean shouldFail) {
        if (shouldUseMockServer()) {
            mockServer.setShouldFailConnection(shouldFail);
        }
        LogUtils.d(TAG, "Connection failure simulation: " + shouldFail);
    }

    /**
     * Enables file operation failures for testing error scenarios.
     */
    public void setFileOperationFailure(boolean shouldFail) {
        if (shouldUseMockServer()) {
            mockServer.setShouldFailFileOperations(shouldFail);
        }
        LogUtils.d(TAG, "File operation failure simulation: " + shouldFail);
    }

    /**
     * Simulates network latency for testing slow connections.
     */
    public void setNetworkDelay(long delayMs) {
        if (shouldUseMockServer()) {
            mockServer.setNetworkDelay(delayMs);
        }
        LogUtils.d(TAG, "Network delay simulation: " + delayMs + "ms");
    }

    /**
     * Gets statistics about the test server state.
     */
    public String getServerStats() {
        if (shouldUseMockServer()) {
            MockSmbServer.MockServerStats stats = mockServer.getStats();
            return stats.toString();
        } else {
            return "Container stats not available";
        }
    }

    /**
     * Determines whether to use the mock server based on test mode and environment.
     */
    private boolean shouldUseMockServer() {
        switch (testMode) {
            case MOCK_ONLY:
                return true;

            case CONTAINER_ONLY:
                return false;

            case AUTO_DETECT:
            default:
                // For now, always use mock server
                // TODO: Add logic to detect if Docker/Testcontainers is available
                return true;
        }
    }

    /**
     * Checks if the test environment supports Docker containers.
     */
    private boolean isContainerEnvironmentAvailable() {
        try {
            // Try to detect if Docker is available
            ProcessBuilder processBuilder = new ProcessBuilder("docker", "--version");
            Process process = processBuilder.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            LogUtils.d(TAG, "Docker not available: " + e.getMessage());
            return false;
        }
    }

    public enum TestMode {
        MOCK_ONLY,          // Use only in-memory mock server
        CONTAINER_ONLY,     // Use only Docker container
        AUTO_DETECT         // Automatically choose based on environment
    }

    /**
     * Builder pattern for creating SmbTestHelper with specific configurations.
     */
    public static class Builder {
        private TestMode testMode = TestMode.AUTO_DETECT;

        public Builder withTestMode(TestMode mode) {
            this.testMode = mode;
            return this;
        }

        public Builder withMockOnly() {
            this.testMode = TestMode.MOCK_ONLY;
            return this;
        }

        public Builder withContainerOnly() {
            this.testMode = TestMode.CONTAINER_ONLY;
            return this;
        }

        public SmbTestHelper build() {
            return new SmbTestHelper(testMode);
        }
    }
}
