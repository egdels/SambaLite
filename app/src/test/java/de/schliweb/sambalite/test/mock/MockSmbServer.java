package de.schliweb.sambalite.test.mock;

import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.util.LogUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock SMB server for unit testing.
 * Simulates SMB file operations in memory without requiring network connectivity.
 * <p>
 * This mock server provides:
 * - In-memory file system simulation
 * - Directory listing functionality
 * - File upload/download operations
 * - Connection management simulation
 * - Error injection for testing edge cases
 */
public class MockSmbServer {

    private static final String TAG = "MockSmbServer";

    // Mock file system structure
    private final Map<String, MockDirectory> directories = new ConcurrentHashMap<>();
    private final Map<String, MockFile> files = new ConcurrentHashMap<>();

    // Connection simulation
    private final Map<String, MockConnection> connections = new ConcurrentHashMap<>();

    // Error injection
    private boolean shouldFailConnection = false;
    private boolean shouldFailFileOperations = false;
    private boolean shouldSimulateSlowNetwork = false;
    private long networkDelayMs = 0;

    public MockSmbServer() {
        LogUtils.d(TAG, "Mock SMB server initialized");
        setupDefaultStructure();
    }

    /**
     * Sets up a default directory structure for testing.
     */
    private void setupDefaultStructure() {
        // Create root directories
        createDirectory("/");
        createDirectory("/documents");
        createDirectory("/images");
        createDirectory("/videos");
        createDirectory("/temp");

        // Create some test files
        createFile("/documents/test.txt", "This is a test document".getBytes());
        createFile("/documents/readme.md", "# Test File\nThis is a test markdown file".getBytes());
        createFile("/images/sample.jpg", generateDummyImageData());
        createFile("/test_file.txt", "Root level test file".getBytes());

        LogUtils.d(TAG, "Default directory structure created");
    }

    /**
     * Creates a mock connection to the server.
     */
    public MockConnection connect(String host, String username, String password, String share) {
        simulateNetworkDelay();

        if (shouldFailConnection) {
            throw new RuntimeException("Mock connection failure");
        }

        String connectionId = host + ":" + username + "@" + share;
        MockConnection connection = new MockConnection(connectionId, host, username, share);
        connections.put(connectionId, connection);

        LogUtils.d(TAG, "Mock connection established: " + connectionId);
        return connection;
    }

    /**
     * Lists files in a directory.
     */
    public List<SmbFileItem> listFiles(MockConnection connection, String path) {
        simulateNetworkDelay();

        if (shouldFailFileOperations) {
            throw new RuntimeException("Mock file operation failure");
        }

        List<SmbFileItem> result = new ArrayList<>();
        String normalizedPath = normalizePath(path);

        LogUtils.d(TAG, "Listing files in: " + normalizedPath);

        // Find subdirectories
        for (String dirPath : directories.keySet()) {
            if (isDirectChild(normalizedPath, dirPath)) {
                String dirName = getFileName(dirPath);
                result.add(createDirectoryItem(dirName, dirPath));
            }
        }

        // Find files
        for (Map.Entry<String, MockFile> entry : files.entrySet()) {
            String filePath = entry.getKey();
            if (isDirectChild(normalizedPath, filePath)) {
                MockFile file = entry.getValue();
                String fileName = getFileName(filePath);
                result.add(createFileItem(fileName, filePath, file.data.length, file.lastModified));
            }
        }

        LogUtils.d(TAG, "Found " + result.size() + " items in " + normalizedPath);
        return result;
    }

    /**
     * Downloads a file from the mock server.
     */
    public InputStream downloadFile(MockConnection connection, String filePath) {
        simulateNetworkDelay();

        if (shouldFailFileOperations) {
            throw new RuntimeException("Mock download failure");
        }

        String normalizedPath = normalizePath(filePath);
        MockFile file = files.get(normalizedPath);

        if (file == null) {
            throw new RuntimeException("File not found: " + normalizedPath);
        }

        LogUtils.d(TAG, "Downloading file: " + normalizedPath + " (" + file.data.length + " bytes)");
        return new ByteArrayInputStream(file.data);
    }

    /**
     * Uploads a file to the mock server.
     */
    public void uploadFile(MockConnection connection, String filePath, InputStream inputStream) throws IOException {
        simulateNetworkDelay();

        if (shouldFailFileOperations) {
            throw new RuntimeException("Mock upload failure");
        }

        String normalizedPath = normalizePath(filePath);

        // Read all data from input stream
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int bytesRead;
        while ((bytesRead = inputStream.read(chunk)) != -1) {
            buffer.write(chunk, 0, bytesRead);
        }

        byte[] data = buffer.toByteArray();
        createFile(normalizedPath, data);

        LogUtils.d(TAG, "Uploaded file: " + normalizedPath + " (" + data.length + " bytes)");
    }

    /**
     * Deletes a file from the mock server.
     */
    public void deleteFile(MockConnection connection, String filePath) {
        simulateNetworkDelay();

        if (shouldFailFileOperations) {
            throw new RuntimeException("Mock delete failure");
        }

        String normalizedPath = normalizePath(filePath);

        if (files.remove(normalizedPath) != null) {
            LogUtils.d(TAG, "Deleted file: " + normalizedPath);
        } else if (directories.remove(normalizedPath) != null) {
            // Also remove all files in this directory
            List<String> filesToRemove = new ArrayList<>();
            for (String path : files.keySet()) {
                if (path.startsWith(normalizedPath + "/")) {
                    filesToRemove.add(path);
                }
            }
            for (String path : filesToRemove) {
                files.remove(path);
            }
            LogUtils.d(TAG, "Deleted directory: " + normalizedPath);
        } else {
            throw new RuntimeException("File or directory not found: " + normalizedPath);
        }
    }

    /**
     * Creates a directory in the mock file system.
     */
    public void createDirectory(MockConnection connection, String dirPath) {
        simulateNetworkDelay();

        if (shouldFailFileOperations) {
            throw new RuntimeException("Mock directory creation failure");
        }

        createDirectory(dirPath);
    }

    // Internal helper methods

    private void createDirectory(String path) {
        String normalizedPath = normalizePath(path);
        directories.put(normalizedPath, new MockDirectory(normalizedPath));
        LogUtils.d(TAG, "Created directory: " + normalizedPath);
    }

    private void createFile(String path, byte[] data) {
        String normalizedPath = normalizePath(path);
        files.put(normalizedPath, new MockFile(data));
        LogUtils.d(TAG, "Created file: " + normalizedPath + " (" + data.length + " bytes)");
    }

    private String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }

        String normalized = path.replace("\\", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }

        // Remove trailing slash unless it's root
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized;
    }

    private boolean isDirectChild(String parentPath, String childPath) {
        if (parentPath.equals("/")) {
            // For root, check if child has no additional slashes
            return childPath.startsWith("/") && childPath.substring(1).indexOf('/') == -1 && !childPath.equals("/");
        } else {
            // For non-root, check if child starts with parent and has exactly one more path segment
            return childPath.startsWith(parentPath + "/") && childPath.substring(parentPath.length() + 1).indexOf('/') == -1;
        }
    }

    private String getFileName(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private SmbFileItem createFileItem(String name, String path, long size, long lastModified) {
        return new SmbFileItem(name, path, SmbFileItem.Type.FILE, size, new java.util.Date(lastModified));
    }

    private SmbFileItem createDirectoryItem(String name, String path) {
        return new SmbFileItem(name, path, SmbFileItem.Type.DIRECTORY, 0, new java.util.Date(System.currentTimeMillis()));
    }

    private byte[] generateDummyImageData() {
        // Generate some dummy binary data that looks like an image
        byte[] data = new byte[1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 256);
        }
        return data;
    }

    private void simulateNetworkDelay() {
        if (shouldSimulateSlowNetwork && networkDelayMs > 0) {
            try {
                Thread.sleep(networkDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // Error injection methods for testing

    public void setShouldFailConnection(boolean shouldFail) {
        this.shouldFailConnection = shouldFail;
        LogUtils.d(TAG, "Connection failure simulation: " + shouldFail);
    }

    public void setShouldFailFileOperations(boolean shouldFail) {
        this.shouldFailFileOperations = shouldFail;
        LogUtils.d(TAG, "File operation failure simulation: " + shouldFail);
    }

    public void setNetworkDelay(long delayMs) {
        this.networkDelayMs = delayMs;
        this.shouldSimulateSlowNetwork = delayMs > 0;
        LogUtils.d(TAG, "Network delay simulation: " + delayMs + "ms");
    }

    /**
     * Resets the mock server to its initial state.
     */
    public void reset() {
        directories.clear();
        files.clear();
        connections.clear();
        shouldFailConnection = false;
        shouldFailFileOperations = false;
        shouldSimulateSlowNetwork = false;
        networkDelayMs = 0;
        setupDefaultStructure();
        LogUtils.d(TAG, "Mock server reset");
    }

    /**
     * Gets statistics about the mock server state.
     */
    public MockServerStats getStats() {
        return new MockServerStats(directories.size(), files.size(), connections.size(), calculateTotalFileSize());
    }

    private long calculateTotalFileSize() {
        return files.values().stream().mapToLong(file -> file.data.length).sum();
    }

    // Inner classes

    public static class MockConnection {
        public final String id;
        public final String host;
        public final String username;
        public final String share;
        public final long connectedAt;

        public MockConnection(String id, String host, String username, String share) {
            this.id = id;
            this.host = host;
            this.username = username;
            this.share = share;
            this.connectedAt = System.currentTimeMillis();
        }

        public boolean isValid() {
            return System.currentTimeMillis() - connectedAt < 300000; // 5 minutes timeout
        }
    }

    private static class MockDirectory {
        public final String path;
        public final long created;

        public MockDirectory(String path) {
            this.path = path;
            this.created = System.currentTimeMillis();
        }
    }

    private static class MockFile {
        public final byte[] data;
        public final long lastModified;

        public MockFile(byte[] data) {
            this.data = data;
            this.lastModified = System.currentTimeMillis();
        }
    }

    public static class MockServerStats {
        public final int directoryCount;
        public final int fileCount;
        public final int connectionCount;
        public final long totalFileSize;

        public MockServerStats(int directoryCount, int fileCount, int connectionCount, long totalFileSize) {
            this.directoryCount = directoryCount;
            this.fileCount = fileCount;
            this.connectionCount = connectionCount;
            this.totalFileSize = totalFileSize;
        }

        @Override
        public String toString() {
            return String.format("MockServerStats{dirs=%d, files=%d, connections=%d, totalSize=%d}", directoryCount, fileCount, connectionCount, totalFileSize);
        }
    }
}
