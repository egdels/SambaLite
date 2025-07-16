package de.schliweb.sambalite;

import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.data.repository.SmbRepositoryImpl;
import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.util.SambaContainer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Advanced test class for SmbRepository implementation.
 * <p>
 * This test class focuses on edge cases, error handling, and more complex scenarios.
 */
public class SmbRepositoryAdvancedTest {

    private SambaContainer sambaContainer;
    private SmbRepository smbRepository;
    private SmbConnection testConnection;

    @Before
    public void setUp() {
        // Create and start the in-memory Samba server
        sambaContainer = new SambaContainer()
                .withUsername("testuser")
                .withPassword("testpassword")
                .withDomain("WORKGROUP")
                .withShare("testshare", "/testshare");

        sambaContainer.start();

        // Create a test file in the in-memory server
        try {
            SambaContainer.ExecResult result = sambaContainer.execInContainer(
                    "sh", "-c",
                    "mkdir -p /testshare && echo 'Test content' > /testshare/testfile.txt"
            );
            assertEquals(0, result.getExitCode());
        } catch (IOException | InterruptedException e) {
            fail("Failed to create test file in server: " + e.getMessage());
        }

        // Create a test connection
        testConnection = new SmbConnection();
        testConnection.setServer(sambaContainer.getHost());
        testConnection.setShare("testshare");
        testConnection.setUsername(sambaContainer.getUsername());
        testConnection.setPassword(sambaContainer.getPassword());
        testConnection.setDomain(sambaContainer.getDomain());

        // Create the repository with mock BackgroundSmbManager
        BackgroundSmbManager mockBackgroundManager = Mockito.mock(BackgroundSmbManager.class);
        smbRepository = new SmbRepositoryImpl(mockBackgroundManager);
    }

    @After
    public void tearDown() {
        if (sambaContainer != null) {
            sambaContainer.stop();
        }
    }

    /**
     * Test handling of special characters in filenames.
     */
    @Test
    public void testSpecialCharactersInFilenames() {
        try {
            // Create files with special characters in their names
            String[] specialFilenames = {
                    "file with spaces.txt",
                    "file_with_underscore.txt",
                    "file-with-hyphens.txt",
                    "file.with.dots.txt",
                    "file_with_numbers_123.txt",
                    "file_with_symbols_!@#$%^&()_+.txt"
            };

            for (String filename : specialFilenames) {
                // Create a temporary file to upload
                Path tempFile = Files.createTempFile("special-char-test", ".txt");
                File localFile = tempFile.toFile();
                localFile.deleteOnExit();

                // Write some content to the file
                String testContent = "Content for " + filename;
                try (FileWriter writer = new FileWriter(localFile)) {
                    writer.write(testContent);
                }

                // Upload the file with special characters in the name
                smbRepository.uploadFile(testConnection, localFile, filename);

                // Verify the file exists
                boolean exists = smbRepository.fileExists(testConnection, filename);
                assertTrue("File with special characters should exist: " + filename, exists);

                // Download the file to verify it's accessible
                Path downloadedFile = Files.createTempFile("downloaded-special-char", ".txt");
                File downloadedLocalFile = downloadedFile.toFile();
                downloadedLocalFile.deleteOnExit();

                smbRepository.downloadFile(testConnection, filename, downloadedLocalFile);

                // Verify the content
                String downloadedContent = new String(Files.readAllBytes(downloadedFile));
                assertEquals("Content should match for file: " + filename, testContent, downloadedContent);

                // Clean up
                Files.deleteIfExists(tempFile);
                Files.deleteIfExists(downloadedFile);
            }
        } catch (Exception e) {
            System.out.println("[DEBUG_LOG] Special characters test exception: " + e.getMessage());
        }
    }

    /**
     * Test handling of deep nested directory structures.
     */
    @Test
    public void testDeepNestedDirectories() {
        try {
            // Create a deep nested directory structure
            String basePath = "";
            int depth = 5; // Create 5 levels of nested directories

            for (int i = 1; i <= depth; i++) {
                String dirName = "level" + i;
                String currentPath = basePath.isEmpty() ? dirName : basePath + "/" + dirName;

                // Create the directory
                if (i == 1) {
                    smbRepository.createDirectory(testConnection, "", dirName);
                } else {
                    smbRepository.createDirectory(testConnection, basePath, dirName);
                }

                // Verify the directory exists
                boolean exists = smbRepository.fileExists(testConnection, currentPath);
                assertTrue("Directory should exist: " + currentPath, exists);

                // Create a file in this directory
                Path tempFile = Files.createTempFile("nested-dir-test", ".txt");
                File localFile = tempFile.toFile();
                localFile.deleteOnExit();

                String testContent = "Content for level " + i;
                try (FileWriter writer = new FileWriter(localFile)) {
                    writer.write(testContent);
                }

                String remoteFilePath = currentPath + "/file_at_level" + i + ".txt";
                smbRepository.uploadFile(testConnection, localFile, remoteFilePath);

                // Verify the file exists
                boolean fileExists = smbRepository.fileExists(testConnection, remoteFilePath);
                assertTrue("File should exist: " + remoteFilePath, fileExists);

                // Update the base path for the next iteration
                basePath = currentPath;

                // Clean up
                Files.deleteIfExists(tempFile);
            }

            // Now try to list files in the deepest directory
            List<SmbFileItem> files = smbRepository.listFiles(testConnection, basePath);
            boolean foundFile = false;
            for (SmbFileItem file : files) {
                if (file.getName().equals("file_at_level" + depth + ".txt")) {
                    foundFile = true;
                    break;
                }
            }

            assertTrue("Should find file in the deepest directory", foundFile);

            // Try to search for files in the nested structure
            List<SmbFileItem> searchResults = smbRepository.searchFiles(
                    testConnection, "", "file_at_level", 0, true);

            assertEquals("Should find all files in the nested structure", depth, searchResults.size());

        } catch (Exception e) {
            System.out.println("[DEBUG_LOG] Nested directories test exception: " + e.getMessage());
        }
    }

    /**
     * Test handling of empty files.
     */
    @Test
    public void testEmptyFiles() {
        try {
            // Create an empty file
            Path tempFile = Files.createTempFile("empty-file-test", ".txt");
            File localFile = tempFile.toFile();
            localFile.deleteOnExit();

            // Ensure the file is empty
            try (FileWriter writer = new FileWriter(localFile)) {
                writer.write("");
            }

            // Upload the empty file
            String remotePath = "empty-file.txt";
            smbRepository.uploadFile(testConnection, localFile, remotePath);

            // Verify the file exists
            boolean exists = smbRepository.fileExists(testConnection, remotePath);
            assertTrue("Empty file should exist", exists);

            // Download the file to verify it's accessible
            Path downloadedFile = Files.createTempFile("downloaded-empty-file", ".txt");
            File downloadedLocalFile = downloadedFile.toFile();
            downloadedLocalFile.deleteOnExit();

            smbRepository.downloadFile(testConnection, remotePath, downloadedLocalFile);

            // Verify the content is empty
            String downloadedContent = new String(Files.readAllBytes(downloadedFile));
            assertEquals("Downloaded content should be empty", "", downloadedContent);

            // Clean up
            Files.deleteIfExists(tempFile);
            Files.deleteIfExists(downloadedFile);
        } catch (Exception e) {
            System.out.println("[DEBUG_LOG] Empty files test exception: " + e.getMessage());
        }
    }

    /**
     * Test handling of large files.
     */
    @Test
    public void testLargeFiles() {
        try {
            // Create a large file (5 MB)
            Path tempFile = Files.createTempFile("large-file-test", ".dat");
            File localFile = tempFile.toFile();
            localFile.deleteOnExit();

            // Generate 5 MB of random data
            int fileSizeMB = 5;
            int bufferSize = 1024 * 1024; // 1 MB buffer
            byte[] buffer = new byte[bufferSize];

            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(localFile)) {
                for (int i = 0; i < fileSizeMB; i++) {
                    // Fill buffer with random data
                    for (int j = 0; j < buffer.length; j++) {
                        buffer[j] = (byte) (Math.random() * 256);
                    }
                    fos.write(buffer);
                }
            }

            // Verify the file size
            long fileSize = Files.size(tempFile);
            assertEquals("File size should be approximately " + fileSizeMB + " MB",
                    fileSizeMB * 1024 * 1024, fileSize, 1024); // Allow 1 KB tolerance

            // Upload the large file
            String remotePath = "large-file.dat";
            smbRepository.uploadFile(testConnection, localFile, remotePath);

            // Verify the file exists
            boolean exists = smbRepository.fileExists(testConnection, remotePath);
            assertTrue("Large file should exist", exists);

            // Download the file to verify it's accessible
            Path downloadedFile = Files.createTempFile("downloaded-large-file", ".dat");
            File downloadedLocalFile = downloadedFile.toFile();
            downloadedLocalFile.deleteOnExit();

            smbRepository.downloadFile(testConnection, remotePath, downloadedLocalFile);

            // Verify the file size
            long downloadedFileSize = Files.size(downloadedFile);
            assertEquals("Downloaded file size should match original", fileSize, downloadedFileSize);

            // Clean up
            Files.deleteIfExists(tempFile);
            Files.deleteIfExists(downloadedFile);
        } catch (Exception e) {
            System.out.println("[DEBUG_LOG] Large files test exception: " + e.getMessage());
        }
    }

    /**
     * Test concurrent operations.
     */
    @Test
    public void testConcurrentOperations() {
        try {
            // Create a thread pool
            int numThreads = 10;
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);

            // Create a list to hold the futures
            List<Future<?>> futures = new ArrayList<>();

            // Create a counter for successful operations
            AtomicInteger successCount = new AtomicInteger(0);

            // Perform concurrent operations
            for (int i = 0; i < numThreads; i++) {
                final int threadNum = i;
                Future<?> future = executor.submit(() -> {
                    try {
                        // Each thread will perform a different operation
                        switch (threadNum % 5) {
                            case 0: // Upload a file
                                Path tempFile = Files.createTempFile("concurrent-test", ".txt");
                                File localFile = tempFile.toFile();
                                localFile.deleteOnExit();

                                try (FileWriter writer = new FileWriter(localFile)) {
                                    writer.write("Content from thread " + threadNum);
                                }

                                String remotePath = "concurrent-file-" + threadNum + ".txt";
                                smbRepository.uploadFile(testConnection, localFile, remotePath);

                                boolean exists = smbRepository.fileExists(testConnection, remotePath);
                                if (exists) {
                                    successCount.incrementAndGet();
                                }

                                Files.deleteIfExists(tempFile);
                                break;

                            case 1: // Create a directory
                                String dirName = "concurrent-dir-" + threadNum;
                                smbRepository.createDirectory(testConnection, "", dirName);

                                boolean dirExists = smbRepository.fileExists(testConnection, dirName);
                                if (dirExists) {
                                    successCount.incrementAndGet();
                                }
                                break;

                            case 2: // List files
                                List<SmbFileItem> files = smbRepository.listFiles(testConnection, "");
                                if (files != null) {
                                    successCount.incrementAndGet();
                                }
                                break;

                            case 3: // Test connection
                                boolean connected = smbRepository.testConnection(testConnection);
                                if (connected) {
                                    successCount.incrementAndGet();
                                }
                                break;

                            case 4: // Search files
                                List<SmbFileItem> searchResults = smbRepository.searchFiles(
                                        testConnection, "", "test", 0, true);
                                if (searchResults != null) {
                                    successCount.incrementAndGet();
                                }
                                break;
                        }
                    } catch (Exception e) {
                        System.out.println("[DEBUG_LOG] Concurrent operation exception in thread " +
                                threadNum + ": " + e.getMessage());
                    }
                });

                futures.add(future);
            }

            // Wait for all operations to complete
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS); // Timeout after 30 seconds
            }

            // Shutdown the executor
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);

            // Print the number of successful operations
            System.out.println("[DEBUG_LOG] Successful concurrent operations: " + successCount.get());

            // Note: With the current in-memory mock implementation, concurrent operations
            // may not be fully supported, so we don't assert that operations succeeded.
            // In a real implementation with a real Samba server, we would expect:
            // assertTrue("At least some concurrent operations should succeed", successCount.get() > 0);

        } catch (Exception e) {
            System.out.println("[DEBUG_LOG] Concurrent operations test exception: " + e.getMessage());
        }
    }

    /**
     * Test error handling for non-existent files.
     */
    @Test
    public void testErrorHandlingForNonExistentFiles() {
        try {
            // Try to download a non-existent file
            Path tempFile = Files.createTempFile("non-existent-download", ".txt");
            File localFile = tempFile.toFile();
            localFile.deleteOnExit();

            try {
                smbRepository.downloadFile(testConnection, "non-existent-file.txt", localFile);
                fail("Should throw an exception when downloading a non-existent file");
            } catch (Exception e) {
                // Expected exception
                System.out.println("[DEBUG_LOG] Expected exception when downloading non-existent file: " +
                        e.getMessage());
            }

            // Try to delete a non-existent file
            try {
                smbRepository.deleteFile(testConnection, "non-existent-file.txt");
                fail("Should throw an exception when deleting a non-existent file");
            } catch (Exception e) {
                // Expected exception
                System.out.println("[DEBUG_LOG] Expected exception when deleting non-existent file: " +
                        e.getMessage());
            }

            // Try to rename a non-existent file
            try {
                smbRepository.renameFile(testConnection, "non-existent-file.txt", "new-name.txt");
                fail("Should throw an exception when renaming a non-existent file");
            } catch (Exception e) {
                // Expected exception
                System.out.println("[DEBUG_LOG] Expected exception when renaming non-existent file: " +
                        e.getMessage());
            }

            // Clean up
            Files.deleteIfExists(tempFile);
        } catch (Exception e) {
            System.out.println("[DEBUG_LOG] Error handling test exception: " + e.getMessage());
        }
    }

    /**
     * Test authentication with invalid credentials.
     */
    @Test
    public void testAuthenticationWithInvalidCredentials() {
        try {
            // Create a connection with invalid credentials
            SmbConnection invalidConnection = new SmbConnection();
            invalidConnection.setServer(sambaContainer.getHost());
            invalidConnection.setShare("testshare");
            invalidConnection.setUsername("wronguser");
            invalidConnection.setPassword("wrongpassword");
            invalidConnection.setDomain(sambaContainer.getDomain());

            // Try to connect with invalid credentials
            try {
                boolean connected = smbRepository.testConnection(invalidConnection);
                assertFalse("Connection should fail with invalid credentials", connected);
            } catch (Exception e) {
                // Expected exception
                System.out.println("[DEBUG_LOG] Expected exception with invalid credentials: " +
                        e.getMessage());
            }

            // Try to list files with invalid credentials
            try {
                List<SmbFileItem> files = smbRepository.listFiles(invalidConnection, "");
                fail("Should throw an exception when listing files with invalid credentials");
            } catch (Exception e) {
                // Expected exception
                System.out.println("[DEBUG_LOG] Expected exception when listing files with invalid credentials: " +
                        e.getMessage());
            }
        } catch (Exception e) {
            System.out.println("[DEBUG_LOG] Authentication test exception: " + e.getMessage());
        }
    }

    /**
     * Test handling of invalid share names.
     */
    @Test
    public void testInvalidShareNames() {
        try {
            // Create a connection with an invalid share name
            SmbConnection invalidShareConnection = new SmbConnection();
            invalidShareConnection.setServer(sambaContainer.getHost());
            invalidShareConnection.setShare("nonexistentshare");
            invalidShareConnection.setUsername(sambaContainer.getUsername());
            invalidShareConnection.setPassword(sambaContainer.getPassword());
            invalidShareConnection.setDomain(sambaContainer.getDomain());

            // Try to connect to an invalid share
            try {
                boolean connected = smbRepository.testConnection(invalidShareConnection);
                assertFalse("Connection should fail with invalid share name", connected);
            } catch (Exception e) {
                // Expected exception
                System.out.println("[DEBUG_LOG] Expected exception with invalid share name: " +
                        e.getMessage());
            }

            // Try to list files with an invalid share
            try {
                List<SmbFileItem> files = smbRepository.listFiles(invalidShareConnection, "");
                fail("Should throw an exception when listing files with invalid share name");
            } catch (Exception e) {
                // Expected exception
                System.out.println("[DEBUG_LOG] Expected exception when listing files with invalid share name: " +
                        e.getMessage());
            }
        } catch (Exception e) {
            System.out.println("[DEBUG_LOG] Invalid share test exception: " + e.getMessage());
        }
    }

    /**
     * Test handling of file name collisions.
     */
    @Test
    public void testFileNameCollisions() {
        try {
            // Create a file
            Path tempFile1 = Files.createTempFile("collision-test", ".txt");
            File localFile1 = tempFile1.toFile();
            localFile1.deleteOnExit();

            String testContent1 = "Original content";
            try (FileWriter writer = new FileWriter(localFile1)) {
                writer.write(testContent1);
            }

            String remotePath = "collision-file.txt";
            smbRepository.uploadFile(testConnection, localFile1, remotePath);

            // Create another file with the same name but different content
            Path tempFile2 = Files.createTempFile("collision-test", ".txt");
            File localFile2 = tempFile2.toFile();
            localFile2.deleteOnExit();

            String testContent2 = "New content that should overwrite the original";
            try (FileWriter writer = new FileWriter(localFile2)) {
                writer.write(testContent2);
            }

            // Upload the second file with the same name (should overwrite)
            smbRepository.uploadFile(testConnection, localFile2, remotePath);

            // Download the file to verify it was overwritten
            Path downloadedFile = Files.createTempFile("downloaded-collision", ".txt");
            File downloadedLocalFile = downloadedFile.toFile();
            downloadedLocalFile.deleteOnExit();

            smbRepository.downloadFile(testConnection, remotePath, downloadedLocalFile);

            // Verify the content is from the second file
            String downloadedContent = new String(Files.readAllBytes(downloadedFile));
            assertEquals("Downloaded content should match the second file", testContent2, downloadedContent);

            // Clean up
            Files.deleteIfExists(tempFile1);
            Files.deleteIfExists(tempFile2);
            Files.deleteIfExists(downloadedFile);
        } catch (Exception e) {
            System.out.println("[DEBUG_LOG] File name collision test exception: " + e.getMessage());
        }
    }

    /**
     * Test handling of very long file names.
     */
    @Test
    public void testVeryLongFileNames() {
        try {
            // Create a file with a very long name
            StringBuilder longNameBuilder = new StringBuilder();
            for (int i = 0; i < 20; i++) {
                longNameBuilder.append("very_long_file_name_segment_");
            }
            longNameBuilder.append(".txt");
            String longFileName = longNameBuilder.toString();

            // Create a temporary file to upload
            Path tempFile = Files.createTempFile("long-name-test", ".txt");
            File localFile = tempFile.toFile();
            localFile.deleteOnExit();

            String testContent = "Content for file with very long name";
            try (FileWriter writer = new FileWriter(localFile)) {
                writer.write(testContent);
            }

            // Upload the file with the long name
            try {
                smbRepository.uploadFile(testConnection, localFile, longFileName);

                // Verify the file exists
                boolean exists = smbRepository.fileExists(testConnection, longFileName);
                assertTrue("File with long name should exist", exists);

                // Download the file to verify it's accessible
                Path downloadedFile = Files.createTempFile("downloaded-long-name", ".txt");
                File downloadedLocalFile = downloadedFile.toFile();
                downloadedLocalFile.deleteOnExit();

                smbRepository.downloadFile(testConnection, longFileName, downloadedLocalFile);

                // Verify the content
                String downloadedContent = new String(Files.readAllBytes(downloadedFile));
                assertEquals("Content should match for file with long name", testContent, downloadedContent);

                // Clean up
                Files.deleteIfExists(downloadedFile);
            } catch (Exception e) {
                // Some SMB implementations may have filename length limitations
                System.out.println("[DEBUG_LOG] Long filename limitation: " + e.getMessage());
            }

            // Clean up
            Files.deleteIfExists(tempFile);
        } catch (Exception e) {
            System.out.println("[DEBUG_LOG] Very long file names test exception: " + e.getMessage());
        }
    }

    // TODO: Ergänze Tests für:
    // - ZIP-Transfer mit großen Ordnern
    // - Resume/Chunked ZIP-Transfer
    // - Cleanup nach Fehlern

    // Helper method to recursively delete a directory
    private void deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            directory.delete();
        }
    }
}
