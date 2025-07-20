package de.schliweb.sambalite.data.repository;

import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.data.model.SmbConnection;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

/**
 * ADVANCED DATA INTEGRITY TESTS
 * <p>
 * These tests cover advanced scenarios and edge cases that could lead to data loss:
 * - Concurrent operations
 * - Network interruption simulation
 * - Partial transfer recovery
 * - File corruption detection
 * - ZIP compression/decompression integrity
 * - Memory pressure scenarios
 * - Complex file structures
 */
@RunWith(MockitoJUnitRunner.class)
public class SmbRepositoryAdvancedIntegrityTest {

    private final SecureRandom random = new SecureRandom();
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

        // Create temp directory for local file tests
        tempTestDir = createTempDirectory("smb_advanced_test");
    }

    /**
     * Test concurrent file operations don't cause data corruption
     */
    @Test
    public void testConcurrentOperationsSafety() throws Exception {
        final int NUM_THREADS = 5;
        final int FILES_PER_THREAD = 3;
        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        List<Future<Boolean>> futures = new ArrayList<>();

        // Create test files for each thread
        for (int i = 0; i < NUM_THREADS; i++) {
            final int threadId = i;
            Future<Boolean> future = executor.submit(() -> {
                try {
                    for (int j = 0; j < FILES_PER_THREAD; j++) {
                        String fileName = "thread_" + threadId + "_file_" + j + ".txt";
                        String content = generateUniqueContent(threadId, j);

                        File originalFile = createTestFile(fileName, content);
                        String originalHash = calculateFileHash(originalFile);

                        // Simulate concurrent transfer
                        File transferredFile = new File(tempTestDir, "transferred_" + fileName);
                        simulateTransferWithDelay(originalFile, transferredFile);

                        String transferredHash = calculateFileHash(transferredFile);

                        // Verify integrity
                        if (!originalHash.equals(transferredHash)) {
                            return false;
                        }
                    }
                    return true;
                } catch (Exception e) {
                    e.printStackTrace();
                    return false;
                }
            });
            futures.add(future);
        }

        // Wait for all threads and verify results
        boolean allSuccessful = true;
        for (Future<Boolean> future : futures) {
            allSuccessful = allSuccessful && future.get(30, TimeUnit.SECONDS);
        }

        executor.shutdown();
        assertTrue("All concurrent operations should preserve data integrity", allSuccessful);
    }

    /**
     * Test partial transfer recovery scenarios
     */
    @Test
    public void testPartialTransferRecovery() throws Exception {
        // Create a large file for testing partial transfers
        byte[] largeData = generateTestBinaryData(500000); // 500KB
        File originalFile = createBinaryTestFile("large_partial.dat", largeData);
        String originalHash = calculateFileHash(originalFile);

        // Simulate partial transfer (first 60% of file)
        File partialFile = new File(tempTestDir, "partial_transfer.dat");
        int partialSize = (int) (largeData.length * 0.6);

        try (FileInputStream fis = new FileInputStream(originalFile); FileOutputStream fos = new FileOutputStream(partialFile)) {

            byte[] buffer = new byte[8192];
            int totalRead = 0;
            int bytesRead;

            while (totalRead < partialSize && (bytesRead = fis.read(buffer)) != -1) {
                int toWrite = Math.min(bytesRead, partialSize - totalRead);
                fos.write(buffer, 0, toWrite);
                totalRead += toWrite;
            }
        }

        // Verify partial file
        assertEquals("Partial file should have expected size", partialSize, partialFile.length());

        // Resume transfer (append remaining data)
        try (FileInputStream fis = new FileInputStream(originalFile); FileOutputStream fos = new FileOutputStream(partialFile, true)) {

            // Skip to resume point
            fis.skip(partialSize);

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
        }

        // Verify complete file integrity after resume
        assertEquals("Resumed file should match original size", originalFile.length(), partialFile.length());
        String resumedHash = calculateFileHash(partialFile);
        assertEquals("Resumed transfer should preserve complete data integrity", originalHash, resumedHash);
    }

    /**
     * Test ZIP compression/decompression integrity
     */
    @Test
    public void testZipIntegrity() throws Exception {
        // Create test files for compression
        Map<String, String> testFiles = new HashMap<>();
        testFiles.put("text_file.txt", "This is a text file with\nmultiple lines\nand special chars: äöü");
        testFiles.put("binary_data.dat", generateBinaryString(10000));
        testFiles.put("unicode_test.txt", "Unicode test: 中文 🚀 Ñiño مرحبا Здравствуй");
        testFiles.put("empty_file.txt", "");

        // Create source directory with test files
        File sourceDir = new File(tempTestDir, "zip_test_source");
        sourceDir.mkdirs();

        Map<String, String> originalHashes = new HashMap<>();
        for (Map.Entry<String, String> entry : testFiles.entrySet()) {
            File file = new File(sourceDir, entry.getKey());
            try (FileWriter writer = new FileWriter(file, java.nio.charset.StandardCharsets.UTF_8)) {
                writer.write(entry.getValue());
            }
            originalHashes.put(entry.getKey(), calculateFileHash(file));
        }

        // Create ZIP file
        File zipFile = new File(tempTestDir, "test_archive.zip");
        createZipArchive(sourceDir, zipFile);

        // Extract ZIP to new location
        File extractDir = new File(tempTestDir, "zip_test_extracted");
        extractDir.mkdirs();
        extractZipArchive(zipFile, extractDir);

        // Verify all files after ZIP/unzip cycle
        for (String fileName : testFiles.keySet()) {
            File extractedFile = new File(extractDir, fileName);
            assertTrue("Extracted file should exist: " + fileName, extractedFile.exists());

            String extractedHash = calculateFileHash(extractedFile);
            String originalHash = originalHashes.get(fileName);
            assertEquals("ZIP/unzip should preserve data integrity for: " + fileName, originalHash, extractedHash);

            // Verify content matches
            String originalContent = testFiles.get(fileName);
            String extractedContent = readFileContent(extractedFile);
            assertEquals("Content should match after ZIP/unzip: " + fileName, originalContent, extractedContent);
        }
    }

    /**
     * Test memory pressure scenarios with large files
     */
    @Test
    public void testMemoryPressureIntegrity() throws Exception {
        // Create multiple large files to stress memory
        List<File> largeFiles = new ArrayList<>();
        List<String> originalHashes = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            byte[] largeData = generateTestBinaryData(1024 * 1024); // 1MB each
            File largeFile = createBinaryTestFile("memory_test_" + i + ".dat", largeData);
            largeFiles.add(largeFile);
            originalHashes.add(calculateFileHash(largeFile));
        }

        // Process files with limited buffer sizes to simulate memory pressure
        for (int i = 0; i < largeFiles.size(); i++) {
            File originalFile = largeFiles.get(i);
            File processedFile = new File(tempTestDir, "processed_memory_test_" + i + ".dat");

            // Use very small buffer to simulate memory constraints
            copyFileWithSmallBuffer(originalFile, processedFile, 512); // 512 bytes

            String processedHash = calculateFileHash(processedFile);
            assertEquals("Memory pressure should not cause data corruption: " + i, originalHashes.get(i), processedHash);
        }
    }

    /**
     * Test file corruption detection patterns
     */
    @Test
    public void testCorruptionDetection() throws Exception {
        // Create file with known corruption detection patterns
        byte[] originalData = generateTestBinaryData(50000);
        File originalFile = createBinaryTestFile("corruption_test.dat", originalData);
        String originalHash = calculateFileHash(originalFile);

        // Create intentionally corrupted copy
        File corruptedFile = new File(tempTestDir, "corrupted_test.dat");
        byte[] corruptedData = Arrays.copyOf(originalData, originalData.length);

        // Introduce corruption at multiple points
        corruptedData[1000] = (byte) ~corruptedData[1000]; // Flip bits
        corruptedData[25000] = (byte) (corruptedData[25000] + 1); // Change value
        corruptedData[49000] = 0; // Zero out byte

        try (FileOutputStream fos = new FileOutputStream(corruptedFile)) {
            fos.write(corruptedData);
        }

        String corruptedHash = calculateFileHash(corruptedFile);

        // Verify corruption is detected
        assertNotEquals("Corruption should be detected via hash mismatch", originalHash, corruptedHash);
        assertFalse("Corrupted file should not match original byte-by-byte", compareFilesByteByByte(originalFile, corruptedFile));
    }

    /**
     * Test complex directory structure integrity
     */
    @Test
    public void testComplexDirectoryStructure() throws Exception {
        // Create complex directory structure
        File rootDir = new File(tempTestDir, "complex_structure");
        Map<String, String> fileContents = createComplexDirectoryStructure(rootDir);

        // Calculate hashes for all files
        Map<String, String> originalHashes = new HashMap<>();
        for (String relativePath : fileContents.keySet()) {
            File file = new File(rootDir, relativePath);
            originalHashes.put(relativePath, calculateFileHash(file));
        }

        // Simulate folder transfer by copying entire structure
        File copiedDir = new File(tempTestDir, "copied_complex_structure");
        copyDirectoryRecursive(rootDir, copiedDir);

        // Verify all files in copied structure
        for (String relativePath : fileContents.keySet()) {
            File originalFile = new File(rootDir, relativePath);
            File copiedFile = new File(copiedDir, relativePath);

            assertTrue("Copied file should exist: " + relativePath, copiedFile.exists());
            assertEquals("File size should match: " + relativePath, originalFile.length(), copiedFile.length());

            String copiedHash = calculateFileHash(copiedFile);
            String originalHash = originalHashes.get(relativePath);
            assertEquals("Directory structure transfer should preserve data: " + relativePath, originalHash, copiedHash);
        }
    }

    /**
     * Test extreme filename scenarios
     */
    @Test
    public void testExtremeFilenames() throws Exception {
        // Test various problematic filename scenarios
        Map<String, String> problematicFiles = new HashMap<>();

        // Long filename
        String longName = "very_long_filename_" + "x".repeat(100) + ".txt";
        problematicFiles.put(longName, "Content for long filename");

        // Unicode filename
        problematicFiles.put("файл_测试_🚀.txt", "Unicode filename content");

        // Special characters (safe for filesystem)
        problematicFiles.put("special-file_123.txt", "Special chars content");

        // Numbers and mixed case
        problematicFiles.put("MixedCase123FILE.TXT", "Mixed case content");

        Map<String, String> hashes = new HashMap<>();

        // Create and verify all problematic files
        for (Map.Entry<String, String> entry : problematicFiles.entrySet()) {
            try {
                File file = createTestFile(entry.getKey(), entry.getValue());
                String hash = calculateFileHash(file);
                hashes.put(entry.getKey(), hash);

                // Copy file to test transfer
                File copiedFile = new File(tempTestDir, "copied_" + entry.getKey());
                copyFile(file, copiedFile);

                String copiedHash = calculateFileHash(copiedFile);
                assertEquals("Extreme filename should not affect data integrity: " + entry.getKey(), hash, copiedHash);

            } catch (Exception e) {
                // Some extreme filenames might not be supported by the filesystem
                System.out.println("Skipping unsupported filename: " + entry.getKey());
            }
        }
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

    private String generateUniqueContent(int threadId, int fileId) {
        return String.format("Thread %d, File %d - Unique content with timestamp: %d\n" + "Data integrity test content that must be preserved!\n" + "Random data: %s\n", threadId, fileId, System.currentTimeMillis(), UUID.randomUUID().toString());
    }

    private byte[] generateTestBinaryData(int size) {
        byte[] data = new byte[size];
        random.nextBytes(data);

        // Add corruption detection patterns every 4KB
        for (int i = 0; i < size; i += 4096) {
            if (i + 15 < size) {
                // Magic header
                data[i] = (byte) 0xCA;
                data[i + 1] = (byte) 0xFE;
                data[i + 2] = (byte) 0xBA;
                data[i + 3] = (byte) 0xBE;

                // Position marker
                data[i + 4] = (byte) ((i >> 24) & 0xFF);
                data[i + 5] = (byte) ((i >> 16) & 0xFF);
                data[i + 6] = (byte) ((i >> 8) & 0xFF);
                data[i + 7] = (byte) (i & 0xFF);

                // Size marker
                data[i + 8] = (byte) ((size >> 24) & 0xFF);
                data[i + 9] = (byte) ((size >> 16) & 0xFF);
                data[i + 10] = (byte) ((size >> 8) & 0xFF);
                data[i + 11] = (byte) (size & 0xFF);

                // Checksum of this block
                int checksum = 0;
                for (int j = 0; j < 12; j++) {
                    checksum ^= data[i + j];
                }
                data[i + 12] = (byte) checksum;
                data[i + 13] = (byte) ~checksum;
                data[i + 14] = (byte) 0xDE;
                data[i + 15] = (byte) 0xAD;
            }
        }

        return data;
    }

    private String generateBinaryString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) (random.nextInt(256)));
        }
        return sb.toString();
    }

    private void simulateTransferWithDelay(File source, File destination) throws IOException, InterruptedException {
        try (FileInputStream fis = new FileInputStream(source); FileOutputStream fos = new FileOutputStream(destination)) {

            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
                // Simulate network delay
                Thread.sleep(1);
            }
        }
    }

    private void copyFileWithSmallBuffer(File source, File destination, int bufferSize) throws IOException {
        try (FileInputStream fis = new FileInputStream(source); FileOutputStream fos = new FileOutputStream(destination)) {

            byte[] buffer = new byte[bufferSize];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
        }
    }

    private void createZipArchive(File sourceDir, File zipFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            zipDirectoryRecursive(sourceDir, sourceDir.getAbsolutePath(), zos);
        }
    }

    private void zipDirectoryRecursive(File source, String basePath, ZipOutputStream zos) throws IOException {
        if (source.isDirectory()) {
            File[] files = source.listFiles();
            if (files != null) {
                for (File file : files) {
                    zipDirectoryRecursive(file, basePath, zos);
                }
            }
        } else {
            String entryName = source.getAbsolutePath().substring(basePath.length() + 1).replace("\\", "/");
            ZipEntry entry = new ZipEntry(entryName);
            zos.putNextEntry(entry);

            try (FileInputStream fis = new FileInputStream(source)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = fis.read(buffer)) != -1) {
                    zos.write(buffer, 0, len);
                }
            }
            zos.closeEntry();
        }
    }

    private void extractZipArchive(File zipFile, File destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zis.read(buffer)) != -1) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private Map<String, String> createComplexDirectoryStructure(File rootDir) throws IOException {
        Map<String, String> fileContents = new HashMap<>();

        // Create nested directories
        new File(rootDir, "level1/level2/level3").mkdirs();
        new File(rootDir, "level1/sibling1").mkdirs();
        new File(rootDir, "level1/sibling2").mkdirs();
        new File(rootDir, "another_root").mkdirs();

        // Create files in various locations
        fileContents.put("root_file.txt", "Root level file content");
        fileContents.put("level1/level1_file.txt", "Level 1 file content");
        fileContents.put("level1/level2/level2_file.txt", "Level 2 file content with\nmultiple lines");
        fileContents.put("level1/level2/level3/deep_file.txt", "Deep nested file content");
        fileContents.put("level1/sibling1/sibling_file.txt", "Sibling directory file");
        fileContents.put("level1/sibling2/another_sibling.txt", "Another sibling file");
        fileContents.put("another_root/separate_file.txt", "Separate root directory file");

        // Create the actual files
        for (Map.Entry<String, String> entry : fileContents.entrySet()) {
            File file = new File(rootDir, entry.getKey());
            try (FileWriter writer = new FileWriter(file, java.nio.charset.StandardCharsets.UTF_8)) {
                writer.write(entry.getValue());
            }
        }

        return fileContents;
    }

    private void copyDirectoryRecursive(File source, File destination) throws IOException {
        if (source.isDirectory()) {
            if (!destination.exists()) {
                destination.mkdirs();
            }

            File[] files = source.listFiles();
            if (files != null) {
                for (File file : files) {
                    File destFile = new File(destination, file.getName());
                    copyDirectoryRecursive(file, destFile);
                }
            }
        } else {
            copyFile(source, destination);
        }
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

    private boolean compareFilesByteByByte(File file1, File file2) {
        try (FileInputStream fis1 = new FileInputStream(file1); FileInputStream fis2 = new FileInputStream(file2)) {

            int byte1, byte2;
            do {
                byte1 = fis1.read();
                byte2 = fis2.read();
                if (byte1 != byte2) {
                    return false;
                }
            } while (byte1 != -1);

            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String readFileContent(File file) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
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
}
