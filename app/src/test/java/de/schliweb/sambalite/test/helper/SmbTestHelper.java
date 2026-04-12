package de.schliweb.sambalite.test.helper;

import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.data.repository.SmbRepositoryImpl;
import de.schliweb.sambalite.test.mock.MockSmbServer;
import de.schliweb.sambalite.util.LogUtils;
import de.schliweb.sambalite.util.SambaContainer;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.mockito.Mockito;

/**
 * Test helper for SMB operations during testing. Provides a unified interface for both mock and
 * container-based testing.
 *
 * <p>This helper automatically chooses the appropriate testing approach: - Mock server for fast
 * unit tests - Container-based server for integration tests - Fallback mechanisms for different
 * test environments
 */
public class SmbTestHelper {

  private static final String TAG = "SmbTestHelper";

  private final MockSmbServer mockServer;
  private final TestMode testMode;
  private SambaContainer container;
  private SmbRepository smbRepository;

  public SmbTestHelper(TestMode mode) {
    this.testMode = mode;
    this.mockServer = new MockSmbServer();

    if (mode == TestMode.CONTAINER_ONLY || (mode == TestMode.AUTO_DETECT && isDockerAvailable())) {
      setupContainer();
    }

    LogUtils.d(TAG, "SmbTestHelper initialized with mode: " + testMode);
  }

  private boolean isDockerAvailable() {
    try {
      org.testcontainers.DockerClientFactory.instance().client();
      return true;
    } catch (Throwable e) {
      return false;
    }
  }

  private void setupContainer() {
    container = new SambaContainer()
        .withUsername("testuser")
        .withPassword("testpassword")
        .withShare("testshare", "/testshare");
    container.start();

    // Wait for Samba service to be fully ready (port listening != service ready)
    try {
      Thread.sleep(3000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    BackgroundSmbManager mockBackgroundManager = Mockito.mock(BackgroundSmbManager.class);
    CompletableFuture<Object> failedFuture = new CompletableFuture<>();
    failedFuture.completeExceptionally(new UnsupportedOperationException("No background service"));
    Mockito.when(mockBackgroundManager.executeBackgroundOperation(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
        .thenReturn(failedFuture);
    smbRepository = new SmbRepositoryImpl(mockBackgroundManager);
  }

  public SmbTestHelper() {
    this(TestMode.AUTO_DETECT);
  }

  /** Creates a test SMB connection with predefined credentials. */
  public SmbConnection createTestConnection() {
    return createTestConnection("testshare");
  }

  /** Creates a test SMB connection for a specific share. */
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
      // Container configuration
      connection.setName("Container Test Connection");
      connection.setServer(container.getHost());
      connection.setPort(container.getPort());
      connection.setUsername(container.getUsername());
      connection.setPassword(container.getPassword());
      connection.setShare(shareName);
      connection.setDomain(container.getDomain());
    }

    LogUtils.d(TAG, "Created test connection: " + connection.getName());
    return connection;
  }

  /** Lists files using the appropriate test server. */
  public List<SmbFileItem> listFiles(String path) {
    if (shouldUseMockServer()) {
      MockSmbServer.MockConnection mockConnection =
          mockServer.connect("mock.test.server", "testuser", "testpass", "testshare");
      return mockServer.listFiles(mockConnection, path);
    } else {
      try {
        return smbRepository.listFiles(createTestConnection(), path);
      } catch (Exception e) {
        throw new RuntimeException("Listing failed", e);
      }
    }
  }

  /** Downloads a file using the appropriate test server. */
  public InputStream downloadFile(String filePath) {
    if (shouldUseMockServer()) {
      MockSmbServer.MockConnection mockConnection =
          mockServer.connect("mock.test.server", "testuser", "testpass", "testshare");
      return mockServer.downloadFile(mockConnection, filePath);
    } else {
      try {
        // Use execInContainer to read file directly (avoids BackgroundSmbManager dependency)
        String containerPath = "/testshare/" + filePath;
        de.schliweb.sambalite.util.SambaContainer.ExecResult result =
            container.execInContainer("cat", containerPath);
        if (result.getExitCode() != 0) {
          throw new RuntimeException(
              "Failed to read file from container: " + result.getStderr());
        }
        return new ByteArrayInputStream(result.getStdout().getBytes(StandardCharsets.UTF_8));
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new RuntimeException("Download failed", e);
      }
    }
  }

  /** Uploads a file using the appropriate test server. */
  public void uploadFile(String filePath, InputStream inputStream) {
    try {
      if (shouldUseMockServer()) {
        MockSmbServer.MockConnection mockConnection =
            mockServer.connect("mock.test.server", "testuser", "testpass", "testshare");
        mockServer.uploadFile(mockConnection, filePath, inputStream);
      } else {
        // Read content from input stream
        byte[] buffer = new byte[8192];
        StringBuilder content = new StringBuilder();
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
          content.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
        // Use execInContainer to write file directly (avoids BackgroundSmbManager dependency)
        String containerPath = "/testshare/" + filePath;
        String parentDir = containerPath.substring(0, containerPath.lastIndexOf('/'));
        container.execInContainer("sh", "-c", "mkdir -p '" + parentDir + "'");
        container.execInContainer("sh", "-c",
            "printf '%s' '" + content.toString().replace("'", "'\\''") + "' > '" + containerPath + "'");
      }
    } catch (Exception e) {
      LogUtils.d(TAG, "Failed to upload file: " + filePath + " - " + e.getMessage());
      throw new RuntimeException("Upload failed", e);
    }
  }

  /** Deletes a file using the appropriate test server. */
  public void deleteFile(String filePath) {
    if (shouldUseMockServer()) {
      MockSmbServer.MockConnection mockConnection =
          mockServer.connect("mock.test.server", "testuser", "testpass", "testshare");
      mockServer.deleteFile(mockConnection, filePath);
    } else {
      try {
        smbRepository.deleteFile(createTestConnection(), filePath);
      } catch (Exception e) {
        throw new RuntimeException("Deletion failed", e);
      }
    }
  }

  /** Creates a directory using the appropriate test server. */
  public void createDirectory(String dirPath) {
    if (shouldUseMockServer()) {
      MockSmbServer.MockConnection mockConnection =
          mockServer.connect("mock.test.server", "testuser", "testpass", "testshare");
      mockServer.createDirectory(mockConnection, dirPath);
    } else {
      try {
        int lastSlash = dirPath.lastIndexOf('/');
        String parent = lastSlash <= 0 ? "" : dirPath.substring(0, lastSlash);
        String name = lastSlash < 0 ? dirPath : dirPath.substring(lastSlash + 1);
        smbRepository.createDirectory(createTestConnection(), parent, name);
      } catch (Exception e) {
        throw new RuntimeException("Directory creation failed for '" + dirPath + "': " + e.getMessage(), e);
      }
    }
  }

  /** Sets up test data in the test server. */
  public void setupTestData() {
    if (shouldUseMockServer()) {
      // Mock server already has default test data
      LogUtils.d(TAG, "Using default mock server test data");
    } else {
      try {
        container.execInContainer("sh", "-c", "mkdir -p /testshare/documents");
        container.execInContainer("sh", "-c", "mkdir -p /testshare/images");
        container.execInContainer("sh", "-c", "echo 'test' > /testshare/documents/test.txt");
        container.execInContainer("sh", "-c", "echo 'readme' > /testshare/documents/readme.md");
        container.execInContainer("sh", "-c", "echo 'root' > /testshare/test_file.txt");
      } catch (Exception e) {
        throw new RuntimeException("Failed to setup test data", e);
      }
    }
  }

  /** Cleans up test data after tests. */
  public void cleanup() {
    if (smbRepository != null) {
      smbRepository.closeConnections();
    }
    if (shouldUseMockServer()) {
      mockServer.reset();
      LogUtils.d(TAG, "Mock server reset");
    } else {
      if (container != null) {
        container.stop();
      }
    }
  }

  /** Enables error injection for testing error scenarios. */
  public void setConnectionFailure(boolean shouldFail) {
    if (shouldUseMockServer()) {
      mockServer.setShouldFailConnection(shouldFail);
    }
    LogUtils.d(TAG, "Connection failure simulation: " + shouldFail);
  }

  /** Enables file operation failures for testing error scenarios. */
  public void setFileOperationFailure(boolean shouldFail) {
    if (shouldUseMockServer()) {
      mockServer.setShouldFailFileOperations(shouldFail);
    }
    LogUtils.d(TAG, "File operation failure simulation: " + shouldFail);
  }

  /** Simulates network latency for testing slow connections. */
  public void setNetworkDelay(long delayMs) {
    if (shouldUseMockServer()) {
      mockServer.setNetworkDelay(delayMs);
    }
    LogUtils.d(TAG, "Network delay simulation: " + delayMs + "ms");
  }

  /** Gets statistics about the test server state. */
  public String getServerStats() {
    if (shouldUseMockServer()) {
      MockSmbServer.MockServerStats stats = mockServer.getStats();
      return stats.toString();
    } else {
      return "Container stats not available";
    }
  }

  /** Determines whether to use the mock server based on test mode and environment. */
  public boolean isMockMode() {
    return shouldUseMockServer();
  }

  /** Determines whether to use the mock server based on test mode and environment. */
  private boolean shouldUseMockServer() {
    switch (testMode) {
      case MOCK_ONLY:
        return true;

      case CONTAINER_ONLY:
        return false;

      case AUTO_DETECT:
      default:
        return container == null;
    }
  }

  public enum TestMode {
    MOCK_ONLY, // Use only in-memory mock server
    CONTAINER_ONLY, // Use only Docker container
    AUTO_DETECT // Automatically choose based on environment
  }

  /** Builder pattern for creating SmbTestHelper with specific configurations. */
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
