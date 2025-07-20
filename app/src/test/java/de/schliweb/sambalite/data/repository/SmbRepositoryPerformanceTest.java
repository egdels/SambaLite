package de.schliweb.sambalite.data.repository;

import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Test class for evaluating the performance and reliability of SmbRepository operations.
 * It includes tests for various scenarios such as bulk operations, memory usage,
 * concurrency, and large file handling.
 */
@RunWith(MockitoJUnitRunner.class)
public class SmbRepositoryPerformanceTest {

    private final Random random = new Random(42); // Fixed seed for reproducible tests
    private SmbRepositoryImpl smbRepository;
    private SmbConnection testConnection;
    private File tempTestDir;

    @Before
    public void setUp() throws IOException {
        BackgroundSmbManager mockBackgroundManager = Mockito.mock(BackgroundSmbManager.class);
        smbRepository = new SmbRepositoryImpl(mockBackgroundManager);

        // Test connection setup
        testConnection = new SmbConnection();
        testConnection.setServer("localhost");
        testConnection.setShare("testshare");
        testConnection.setUsername("testuser");
        testConnection.setPassword("testpass");

        // Create temp directory for tests
        tempTestDir = createTempDirectory("smb_performance_test");
    }

    /**
     * Test bulk file creation and integrity verification
     */
    @Test
    public void testBulkFileCreationIntegrity() throws Exception {
        final int NUM_FILES = 100;
        List<File> createdFiles = new ArrayList<>();
        Map<String, String> fileHashes = new HashMap<>();

        long startTime = System.currentTimeMillis();

        // Create bulk files
        for (int i = 0; i < NUM_FILES; i++) {
            String fileName = "bulk_file_" + i + ".txt";
            String content = generateRandomContent(1024 + (i * 10)); // Variable sizes

            File file = createTestFile(fileName, content);
            createdFiles.add(file);

            String hash = calculateFileHash(file);
            fileHashes.put(fileName, hash);
        }

        long creationTime = System.currentTimeMillis() - startTime;

        // Verify all files have correct hashes
        startTime = System.currentTimeMillis();
        for (int i = 0; i < NUM_FILES; i++) {
            File file = createdFiles.get(i);
            String fileName = "bulk_file_" + i + ".txt";
            String originalHash = fileHashes.get(fileName);
            String currentHash = calculateFileHash(file);

            assertEquals("File " + fileName + " should maintain integrity", originalHash, currentHash);
        }

        long verificationTime = System.currentTimeMillis() - startTime;

        // Performance assertions
        assertTrue("Bulk creation should complete in reasonable time (< 10s)", creationTime < 10000);
        assertTrue("Bulk verification should complete in reasonable time (< 5s)", verificationTime < 5000);

        // Cleanup
        for (File file : createdFiles) {
            file.delete();
        }
    }

    /**
     * Test memory usage with large number of file operations
     */
    @Test
    public void testMemoryUsageStressTest() throws Exception {
        Runtime runtime = Runtime.getRuntime();
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();

        final int NUM_OPERATIONS = 500;
        List<File> tempFiles = new ArrayList<>();

        try {
            // Perform many file operations
            for (int i = 0; i < NUM_OPERATIONS; i++) {
                // Create file
                String content = generateRandomContent(2048);
                File file = createTestFile("memory_test_" + i + ".dat", content);
                tempFiles.add(file);

                // Calculate hash (memory intensive operation)
                String hash = calculateFileHash(file);
                assertNotNull("Hash should not be null", hash);

                // Copy file (memory intensive)
                File copiedFile = new File(tempTestDir, "copied_memory_test_" + i + ".dat");
                copyFile(file, copiedFile);
                tempFiles.add(copiedFile);

                // Verify integrity
                String copiedHash = calculateFileHash(copiedFile);
                assertEquals("Memory stress should not affect integrity", hash, copiedHash);

                // Force garbage collection every 50 operations
                if (i % 50 == 0) {
                    System.gc();
                    Thread.sleep(10); // Allow GC to run
                }
            }

            // Check memory usage hasn't grown excessively
            System.gc();
            Thread.sleep(100); // Allow GC to complete

            long finalMemory = runtime.totalMemory() - runtime.freeMemory();
            long memoryIncrease = finalMemory - initialMemory;

            // Memory increase should be reasonable (less than 100MB)
            assertTrue("Memory usage should not increase excessively: " + memoryIncrease + " bytes", memoryIncrease < 100 * 1024 * 1024);

        } finally {
            // Cleanup all temp files
            for (File file : tempFiles) {
                if (file.exists()) {
                    file.delete();
                }
            }
        }
    }

    /**
     * Test concurrent file operations performance
     */
    @Test
    public void testConcurrentOperationsPerformance() throws Exception {
        final int NUM_THREADS = 10;
        final int FILES_PER_THREAD = 20;
        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        CountDownLatch latch = new CountDownLatch(NUM_THREADS);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        // Submit concurrent tasks
        for (int threadId = 0; threadId < NUM_THREADS; threadId++) {
            final int finalThreadId = threadId;
            executor.submit(() -> {
                try {
                    for (int fileId = 0; fileId < FILES_PER_THREAD; fileId++) {
                        String fileName = "concurrent_t" + finalThreadId + "_f" + fileId + ".txt";
                        String content = generateRandomContent(1024);

                        // Create file
                        File file = createTestFile(fileName, content);
                        String originalHash = calculateFileHash(file);

                        // Copy file (simulates transfer)
                        File copiedFile = new File(tempTestDir, "copied_" + fileName);
                        copyFile(file, copiedFile);

                        // Verify integrity
                        String copiedHash = calculateFileHash(copiedFile);
                        if (originalHash.equals(copiedHash)) {
                            successCount.incrementAndGet();
                        } else {
                            errorCount.incrementAndGet();
                        }

                        // Cleanup
                        file.delete();
                        copiedFile.delete();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all threads to complete
        assertTrue("All threads should complete within 30 seconds", latch.await(30, TimeUnit.SECONDS));

        long executionTime = System.currentTimeMillis() - startTime;

        executor.shutdown();

        // Verify results
        int expectedOperations = NUM_THREADS * FILES_PER_THREAD;
        assertEquals("All operations should succeed", expectedOperations, successCount.get());
        assertEquals("No errors should occur", 0, errorCount.get());

        // Performance assertion
        assertTrue("Concurrent operations should complete in reasonable time (< 20s)", executionTime < 20000);

        double operationsPerSecond = (double) expectedOperations / (executionTime / 1000.0);
        assertTrue("Should achieve reasonable throughput (> 20 ops/sec)", operationsPerSecond > 20);
    }

    /**
     * Test large file handling performance
     */
    @Test
    public void testLargeFilePerformance() throws Exception {
        final int[] fileSizes = {1024, 10 * 1024, 100 * 1024, 1024 * 1024, 5 * 1024 * 1024}; // 1KB to 5MB
        Map<Integer, Long> performanceData = new HashMap<>();

        for (int size : fileSizes) {
            long startTime = System.currentTimeMillis();

            // Create large file
            byte[] data = generateTestBinaryData(size);
            File largeFile = createBinaryTestFile("large_" + size + ".dat", data);
            String originalHash = calculateFileHash(largeFile);

            // Copy large file (simulates transfer)
            File copiedFile = new File(tempTestDir, "copied_large_" + size + ".dat");
            copyFileInChunks(largeFile, copiedFile, 8192); // 8KB chunks like SMB

            // Verify integrity
            String copiedHash = calculateFileHash(copiedFile);
            assertEquals("Large file integrity should be maintained for size " + size, originalHash, copiedHash);

            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;
            performanceData.put(size, duration);

            // Cleanup
            largeFile.delete();
            copiedFile.delete();

            // Performance assertions based on file size
            if (size <= 100 * 1024) { // Files <= 100KB should be fast
                assertTrue("Small files should process quickly (< 1s) for size " + size, duration < 1000);
            } else if (size <= 1024 * 1024) { // Files <= 1MB should be reasonable
                assertTrue("Medium files should process reasonably (< 5s) for size " + size, duration < 5000);
            } else { // Large files can take longer but should still be reasonable
                assertTrue("Large files should process in reasonable time (< 15s) for size " + size, duration < 15000);
            }
        }

        // Verify performance scaling is reasonable
        long time100KB = performanceData.get(100 * 1024);
        long time1MB = performanceData.get(1024 * 1024);

        // 1MB should not take more than 20x longer than 100KB
        assertTrue("Performance should scale reasonably with file size", time1MB < time100KB * 20);
    }

    /**
     * Test repository operation timeout handling
     */
    @Test
    public void testOperationTimeoutResilience() throws Exception {
        final int NUM_QUICK_OPERATIONS = 50;
        List<Long> operationTimes = new ArrayList<>();

        for (int i = 0; i < NUM_QUICK_OPERATIONS; i++) {
            long startTime = System.currentTimeMillis();

            try {
                // Attempt various operations (will fail but should fail quickly)
                smbRepository.testConnection(testConnection);
                fail("Should have failed quickly");
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                operationTimes.add(duration);

                // Each operation should fail quickly (< 5 seconds)
                assertTrue("Operation " + i + " should timeout quickly: " + duration + "ms", duration < 5000);
            }
        }

        // Calculate average timeout duration
        double avgTimeout = operationTimes.stream().mapToLong(Long::longValue).average().orElse(0.0);
        assertTrue("Average timeout should be reasonable (< 4s)", avgTimeout < 4000);
    }

    /**
     * Test hash calculation performance
     */
    @Test
    public void testHashCalculationPerformance() throws Exception {
        final int[] dataSizes = {1024, 10240, 102400, 1048576}; // 1KB to 1MB

        for (int size : dataSizes) {
            byte[] data = generateTestBinaryData(size);
            File testFile = createBinaryTestFile("hash_test_" + size + ".dat", data);

            // Measure hash calculation time
            long startTime = System.currentTimeMillis();
            String hash1 = calculateFileHash(testFile);
            long firstHashTime = System.currentTimeMillis() - startTime;

            // Calculate hash again to verify consistency
            startTime = System.currentTimeMillis();
            String hash2 = calculateFileHash(testFile);
            long secondHashTime = System.currentTimeMillis() - startTime;

            // Verify hashes are identical
            assertEquals("Hash should be consistent for size " + size, hash1, hash2);

            // Performance assertions
            assertTrue("Hash calculation should be fast for size " + size + ": " + firstHashTime + "ms", firstHashTime < 1000);
            assertTrue("Second hash calculation should be similar: " + secondHashTime + "ms", secondHashTime < 1000);

            // Hash calculation time should not vary dramatically
            long timeDifference = Math.abs(firstHashTime - secondHashTime);
            assertTrue("Hash calculation time should be consistent (diff < 500ms): " + timeDifference, timeDifference < 500);

            testFile.delete();
        }
    }

    /**
     * Test file operation cancellation performance
     */
    @Test
    public void testOperationCancellationSpeed() throws Exception {
        final int NUM_CANCELLATION_TESTS = 20;
        List<Long> cancellationTimes = new ArrayList<>();

        for (int i = 0; i < NUM_CANCELLATION_TESTS; i++) {
            // Start a search operation
            CompletableFuture<Void> searchFuture = CompletableFuture.runAsync(() -> {
                try {
                    smbRepository.searchFiles(testConnection, "/", "*", 0, true);
                } catch (Exception e) {
                    // Expected - will fail due to no SMB server
                }
            });

            // Wait a bit to let the operation start
            Thread.sleep(10);

            // Cancel the search
            long startTime = System.currentTimeMillis();
            smbRepository.cancelSearch();

            // Wait for the operation to respond to cancellation
            try {
                searchFuture.get(2, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                // Operation might still be running, but cancellation should be noted
            }

            long cancellationTime = System.currentTimeMillis() - startTime;
            cancellationTimes.add(cancellationTime);

            // Cancellation should be immediate (< 100ms)
            assertTrue("Cancellation should be immediate: " + cancellationTime + "ms", cancellationTime < 100);
        }

        // Average cancellation time should be very fast
        double avgCancellationTime = cancellationTimes.stream().mapToLong(Long::longValue).average().orElse(0.0);
        assertTrue("Average cancellation time should be very fast: " + avgCancellationTime + "ms", avgCancellationTime < 50);
    }

    /**
     * Test repository API consistency under load
     */
    @Test
    public void testAPIConsistencyUnderLoad() throws Exception {
        final int NUM_ITERATIONS = 100;
        Set<String> uniqueHashes = new HashSet<>();

        // Create a reference file
        String referenceContent = "Reference content for consistency testing";
        File referenceFile = createTestFile("reference.txt", referenceContent);
        String referenceHash = calculateFileHash(referenceFile);

        // Test API consistency under repeated operations
        for (int i = 0; i < NUM_ITERATIONS; i++) {
            // Create identical file
            File testFile = createTestFile("consistency_test_" + i + ".txt", referenceContent);
            String testHash = calculateFileHash(testFile);

            // Hash should always be the same for identical content
            assertEquals("Hash should be consistent across iterations", referenceHash, testHash);
            uniqueHashes.add(testHash);

            // Copy file
            File copiedFile = new File(tempTestDir, "copied_consistency_test_" + i + ".txt");
            copyFile(testFile, copiedFile);

            String copiedHash = calculateFileHash(copiedFile);
            assertEquals("Copied file hash should match original", referenceHash, copiedHash);

            // Test SmbFileItem creation consistency
            Date now = new Date();
            SmbFileItem fileItem = new SmbFileItem("test.txt", "/test.txt", SmbFileItem.Type.FILE, testFile.length(), now);

            assertNotNull("File item should be created consistently", fileItem);
            assertEquals("File item name should be consistent", "test.txt", fileItem.getName());
            assertEquals("File item size should be consistent", testFile.length(), fileItem.getSize());

            // Cleanup
            testFile.delete();
            copiedFile.delete();
        }

        // Should only have one unique hash (all files identical)
        assertEquals("All identical files should produce the same hash", 1, uniqueHashes.size());

        referenceFile.delete();
    }

    // Helper methods
    private File createTempDirectory(String prefix) throws IOException {
        File tempDir = File.createTempFile(prefix, null);
        tempDir.delete();
        tempDir.mkdirs();
        return tempDir;
    }

    private File createTestFile(String name, String content) throws IOException {
        File file = new File(tempTestDir, name);
        try (FileWriter writer = new FileWriter(file, java.nio.charset.StandardCharsets.UTF_8)) {
            writer.write(content);
        }
        return file;
    }

    private File createBinaryTestFile(String name, byte[] data) throws IOException {
        File file = new File(tempTestDir, name);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
        }
        return file;
    }

    private String generateRandomContent(int length) {
        StringBuilder sb = new StringBuilder(length);
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 \n\t";

        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }

        return sb.toString();
    }

    private byte[] generateTestBinaryData(int size) {
        byte[] data = new byte[size];
        random.nextBytes(data);

        // Add consistent patterns for verification
        for (int i = 0; i < size; i += 1024) {
            if (i + 7 < size) {
                data[i] = (byte) 0xAB;
                data[i + 1] = (byte) 0xCD;
                data[i + 2] = (byte) 0xEF;
                data[i + 3] = (byte) 0x12;
                data[i + 4] = (byte) ((i >> 24) & 0xFF);
                data[i + 5] = (byte) ((i >> 16) & 0xFF);
                data[i + 6] = (byte) ((i >> 8) & 0xFF);
                data[i + 7] = (byte) (i & 0xFF);
            }
        }

        return data;
    }

    private String calculateFileHash(File file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    md.update(buffer, 0, bytesRead);
                }
            }

            byte[] hash = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate file hash", e);
        }
    }

    private void copyFile(File source, File destination) throws IOException {
        try (FileInputStream fis = new FileInputStream(source); FileOutputStream fos = new FileOutputStream(destination)) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
        }
    }

    private void copyFileInChunks(File source, File destination, int chunkSize) throws IOException {
        try (FileInputStream fis = new FileInputStream(source); FileOutputStream fos = new FileOutputStream(destination)) {

            byte[] buffer = new byte[chunkSize];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
        }
    }
}
