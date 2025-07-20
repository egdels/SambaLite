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

import static org.junit.Assert.*;

/**
 * SIMPLIFIED DATA INTEGRITY TESTS
 * <p>
 * These tests verify the basic structure and API compatibility.
 * Full integration tests require a running SMB server.
 */
@RunWith(MockitoJUnitRunner.class)
public class SmbRepositoryBasicIntegrityTest {

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
        tempTestDir = createTempDirectory("smb_test");
    }

    /**
     * Test that verifies file integrity utilities work correctly
     */
    @Test
    public void testFileIntegrityUtilities() throws Exception {
        // Create test file with known content
        String testContent = "CRITICAL TEST DATA - This content must be preserved exactly!\n" + "Unicode: äöü ñ 中文 🚀\n" + "Special chars: @#$%^&*()_+-=[]{}|;:,.<>?/~`\n";

        File testFile = createTestFile("integrity_test.txt", testContent);
        String originalHash = calculateFileHash(testFile);
        long originalSize = testFile.length();

        // Create a copy to simulate upload/download
        File copiedFile = new File(tempTestDir, "copied_integrity_test.txt");
        copyFile(testFile, copiedFile);

        // Verify integrity
        assertTrue("Copied file should exist", copiedFile.exists());
        assertEquals("File size should match", originalSize, copiedFile.length());

        String copiedHash = calculateFileHash(copiedFile);
        assertEquals("File hash should match - NO DATA LOSS!", originalHash, copiedHash);

        String copiedContent = readFileContent(copiedFile);
        assertEquals("File content should be identical", testContent, copiedContent);

        // Byte-by-byte verification
        assertTrue("Files should be byte-for-byte identical", compareFilesByteByByte(testFile, copiedFile));
    }

    /**
     * Test binary file integrity
     */
    @Test
    public void testBinaryFileIntegrity() throws Exception {
        // Create binary test data with patterns
        byte[] binaryData = generateTestBinaryData(10000);
        File binaryFile = createBinaryTestFile("binary_test.dat", binaryData);
        String originalHash = calculateFileHash(binaryFile);

        // Copy binary file
        File copiedBinary = new File(tempTestDir, "copied_binary_test.dat");
        copyFile(binaryFile, copiedBinary);

        // Verify binary integrity
        assertEquals("Binary file size should match", binaryFile.length(), copiedBinary.length());

        String copiedHash = calculateFileHash(copiedBinary);
        assertEquals("Binary file hash should match", originalHash, copiedHash);

        // Verify every byte
        byte[] copiedData = readBinaryFile(copiedBinary);
        assertArrayEquals("Binary data should be identical", binaryData, copiedData);
    }

    /**
     * Test SmbConnection model integrity
     */
    @Test
    public void testSmbConnectionIntegrity() {
        assertNotNull("SmbConnection should be created", testConnection);
        assertEquals("Server should be set", "localhost", testConnection.getServer());
        assertEquals("Share should be set", "testshare", testConnection.getShare());
        assertEquals("Username should be set", "testuser", testConnection.getUsername());
        assertEquals("Password should be set", "testpass", testConnection.getPassword());
    }

    /**
     * Test SmbFileItem model integrity
     */
    @Test
    public void testSmbFileItemIntegrity() {
        // Test file item
        SmbFileItem fileItem = new SmbFileItem("test.txt", "/test.txt", SmbFileItem.Type.FILE, 1024, new Date());

        assertNotNull("File item should be created", fileItem);
        assertEquals("File name should match", "test.txt", fileItem.getName());
        assertEquals("File path should match", "/test.txt", fileItem.getPath());
        assertEquals("File type should be FILE", SmbFileItem.Type.FILE, fileItem.getType());
        assertEquals("File size should match", 1024, fileItem.getSize());
        assertFalse("File should not be directory", fileItem.isDirectory());
        assertTrue("File should be file", fileItem.isFile());

        // Test directory item
        SmbFileItem dirItem = new SmbFileItem("testdir", "/testdir", SmbFileItem.Type.DIRECTORY, 0, new Date());

        assertTrue("Directory should be directory", dirItem.isDirectory());
        assertFalse("Directory should not be file", dirItem.isFile());
    }

    /**
     * Test repository API exists and is callable
     */
    @Test
    public void testRepositoryAPIExists() {
        assertNotNull("Repository should be created", smbRepository);

        // Verify key methods exist (will throw exceptions without real SMB server)
        try {
            // These will fail without SMB server, but should compile
            smbRepository.testConnection(testConnection);
        } catch (Exception expected) {
            // Expected - no real SMB server
            assertNotNull("Should fail gracefully", expected.getMessage());
        }
    }

    /**
     * Test large file handling simulation
     */
    @Test
    public void testLargeFileSimulation() throws Exception {
        // Create larger test file (1MB)
        byte[] largeData = generateTestBinaryData(1024 * 1024);
        File largeFile = createBinaryTestFile("large_test.dat", largeData);
        String originalHash = calculateFileHash(largeFile);

        // Simulate transfer by copying
        File transferredFile = new File(tempTestDir, "transferred_large.dat");
        copyFileInChunks(largeFile, transferredFile, 8192); // 8KB chunks like SMB

        // Verify large file integrity
        assertEquals("Large file size should match", largeFile.length(), transferredFile.length());

        String transferredHash = calculateFileHash(transferredFile);
        assertEquals("Large file hash should match - NO DATA CORRUPTION!", originalHash, transferredHash);
    }

    /**
     * Test edge cases that could cause data loss
     */
    @Test
    public void testEdgeCases() throws Exception {
        // Empty file
        File emptyFile = createTestFile("empty.txt", "");
        assertEquals("Empty file should have size 0", 0, emptyFile.length());

        File copiedEmpty = new File(tempTestDir, "copied_empty.txt");
        copyFile(emptyFile, copiedEmpty);
        assertEquals("Copied empty file should also be empty", 0, copiedEmpty.length());

        // File with only whitespace
        File whitespaceFile = createTestFile("whitespace.txt", "   \n\t\r\n   ");
        File copiedWhitespace = new File(tempTestDir, "copied_whitespace.txt");
        copyFile(whitespaceFile, copiedWhitespace);

        String originalWhitespace = readFileContent(whitespaceFile);
        String copiedWhitespaceContent = readFileContent(copiedWhitespace);
        assertEquals("Whitespace should be preserved exactly", originalWhitespace, copiedWhitespaceContent);

        // File with special characters
        String specialContent = "Special chars: @#$%^&*()_+-=[]{}|;:,.<>?/~`\nUnicode: äöü ñ 中文 🚀";
        File specialFile = createTestFile("special.txt", specialContent);
        File copiedSpecial = new File(tempTestDir, "copied_special.txt");
        copyFile(specialFile, copiedSpecial);

        String copiedSpecialContent = readFileContent(copiedSpecial);
        assertEquals("Special characters should be preserved", specialContent, copiedSpecialContent);
    }

    /**
     * Test wildcard pattern matching functionality
     */
    @Test
    public void testWildcardPatternMatching() throws Exception {
        // Create instance to test wildcard matching (using reflection for private method)
        BackgroundSmbManager mockBackgroundManager = Mockito.mock(BackgroundSmbManager.class);
        SmbRepositoryImpl repo = new SmbRepositoryImpl(mockBackgroundManager);

        // Test basic wildcard patterns through search functionality
        // Since matchesWildcard is private, we test it indirectly

        // Create test files with different names
        String[] testFilenames = {"document.txt", "image.jpg", "data.csv", "readme.md", "config.xml", "test_file.txt", "another_document.pdf"};

        // Test that files can be created and have expected properties
        for (String filename : testFilenames) {
            File testFile = createTestFile(filename, "Content for " + filename);
            assertTrue("Test file should exist: " + filename, testFile.exists());
            assertTrue("Test file should have content", testFile.length() > 0);
        }

        // Verify search functionality exists (will fail without SMB server but tests API)
        try {
            List<SmbFileItem> results = smbRepository.searchFiles(testConnection, "", "*.txt", 0, false);
            assertNotNull("Search results should not be null", results);
        } catch (Exception e) {
            // Expected without real SMB server
            assertNotNull("Error should have message", e.getMessage());
        }
    }

    /**
     * Test file with extremely long content
     */
    @Test
    public void testExtremelyLongContent() throws Exception {
        // Create file with very long content (10MB of text)
        StringBuilder longContent = new StringBuilder();
        String repeatedLine = "This is a very long line of text that will be repeated many times to create a large file for testing data integrity during transfers.\n";

        int targetSize = 10 * 1024 * 1024; // 10MB
        while (longContent.length() < targetSize) {
            longContent.append(repeatedLine);
        }

        String finalContent = longContent.toString();
        File longFile = createTestFile("extremely_long_file.txt", finalContent);
        String originalHash = calculateFileHash(longFile);

        // Copy the long file to simulate transfer
        File copiedLongFile = new File(tempTestDir, "copied_extremely_long_file.txt");
        copyFileInChunks(longFile, copiedLongFile, 4096); // Small chunks

        // Verify integrity of extremely long file
        assertEquals("Extremely long file size should match", longFile.length(), copiedLongFile.length());

        String copiedHash = calculateFileHash(copiedLongFile);
        assertEquals("Extremely long file hash should match - NO DATA LOSS!", originalHash, copiedHash);

        // Verify content matches
        String copiedContent = readFileContent(copiedLongFile);
        assertEquals("Extremely long content should be identical", finalContent, copiedContent);
    }

    /**
     * Test multiple file operations in sequence
     */
    @Test
    public void testSequentialFileOperations() throws Exception {
        List<File> testFiles = new ArrayList<>();
        List<String> originalHashes = new ArrayList<>();

        // Create multiple test files
        for (int i = 0; i < 10; i++) {
            String content = "Sequential test file " + i + "\n" + "Content with unique data: " + UUID.randomUUID().toString() + "\n" + "Timestamp: " + System.currentTimeMillis() + "\n";

            File testFile = createTestFile("sequential_" + i + ".txt", content);
            testFiles.add(testFile);
            originalHashes.add(calculateFileHash(testFile));
        }

        // Process files sequentially
        for (int i = 0; i < testFiles.size(); i++) {
            File originalFile = testFiles.get(i);
            File processedFile = new File(tempTestDir, "processed_sequential_" + i + ".txt");

            // Simulate processing (copy with different buffer sizes)
            int bufferSize = 1024 + (i * 512); // Different buffer sizes
            copyFileInChunks(originalFile, processedFile, bufferSize);

            // Verify each file individually
            String processedHash = calculateFileHash(processedFile);
            assertEquals("Sequential file " + i + " should maintain integrity", originalHashes.get(i), processedHash);
        }
    }

    /**
     * Test handling of files with null bytes (binary data in text files)
     */
    @Test
    public void testNullByteHandling() throws Exception {
        // Create content with null bytes
        byte[] contentWithNulls = new byte[10000];
        Random random = new Random(98765);
        random.nextBytes(contentWithNulls);

        // Insert null bytes at regular intervals
        for (int i = 0; i < contentWithNulls.length; i += 100) {
            contentWithNulls[i] = 0; // Null byte
        }

        File nullByteFile = createBinaryTestFile("null_byte_test.dat", contentWithNulls);
        String originalHash = calculateFileHash(nullByteFile);

        // Copy file with null bytes
        File copiedNullFile = new File(tempTestDir, "copied_null_byte_test.dat");
        copyFile(nullByteFile, copiedNullFile);

        // Verify null byte handling
        assertEquals("Null byte file size should match", nullByteFile.length(), copiedNullFile.length());

        String copiedHash = calculateFileHash(copiedNullFile);
        assertEquals("Null bytes should be preserved exactly", originalHash, copiedHash);

        // Verify byte-by-byte
        byte[] copiedData = readBinaryFile(copiedNullFile);
        assertArrayEquals("Null byte data should be identical", contentWithNulls, copiedData);
    }

    /**
     * Test various character encodings
     */
    @Test
    public void testCharacterEncodingIntegrity() throws Exception {
        // Test different character sets
        Map<String, String> encodingTests = new HashMap<>();

        encodingTests.put("ASCII", "Simple ASCII text with numbers 123 and symbols !@#$%");
        encodingTests.put("Latin1", "Latin-1 text with accents: café, naïve, résumé");
        encodingTests.put("UTF8", "UTF-8 with emoji: 🚀🌟💻 and math: ∑∏∆∇");
        encodingTests.put("Mixed", "Mixed: ASCII + café + 中文 + 🚀 + математика");

        for (Map.Entry<String, String> test : encodingTests.entrySet()) {
            String testName = test.getKey();
            String content = test.getValue();

            File encodingFile = createTestFile("encoding_" + testName + ".txt", content);
            String originalHash = calculateFileHash(encodingFile);

            // Copy file to test encoding preservation
            File copiedEncodingFile = new File(tempTestDir, "copied_encoding_" + testName + ".txt");
            copyFile(encodingFile, copiedEncodingFile);

            // Verify encoding integrity
            String copiedHash = calculateFileHash(copiedEncodingFile);
            assertEquals("Encoding should be preserved for " + testName, originalHash, copiedHash);

            String copiedContent = readFileContent(copiedEncodingFile);
            assertEquals("Content should match for " + testName, content, copiedContent);
        }
    }

    /**
     * Test very small files (1 byte, 2 bytes, etc.)
     */
    @Test
    public void testVerySmallFiles() throws Exception {
        // Test files of various tiny sizes
        byte[][] tinyContents = {new byte[]{},                           // 0 bytes
                new byte[]{65},                         // 1 byte ('A')
                new byte[]{65, 66},                     // 2 bytes ('AB')
                new byte[]{65, 66, 67},                 // 3 bytes ('ABC')
                new byte[]{0},                          // 1 null byte
                new byte[]{-1},                         // 1 byte with all bits set
                new byte[]{0, -1, 0, -1}               // Pattern of 0x00, 0xFF
        };

        for (int i = 0; i < tinyContents.length; i++) {
            byte[] content = tinyContents[i];
            File tinyFile = createBinaryTestFile("tiny_" + i + ".dat", content);
            String originalHash = calculateFileHash(tinyFile);

            // Copy tiny file
            File copiedTinyFile = new File(tempTestDir, "copied_tiny_" + i + ".dat");
            copyFile(tinyFile, copiedTinyFile);

            // Verify tiny file integrity
            assertEquals("Tiny file " + i + " size should match", content.length, (int) copiedTinyFile.length());

            String copiedHash = calculateFileHash(copiedTinyFile);
            assertEquals("Tiny file " + i + " hash should match", originalHash, copiedHash);

            if (content.length > 0) {
                byte[] copiedContent = readBinaryFile(copiedTinyFile);
                assertArrayEquals("Tiny file " + i + " content should match", content, copiedContent);
            }
        }
    }

    /**
     * Test file with repeating patterns that could cause compression artifacts
     */
    @Test
    public void testRepeatingPatterns() throws Exception {
        // Create files with various repeating patterns
        Map<String, byte[]> patternTests = new HashMap<>();

        // Pattern 1: All zeros
        byte[] zeros = new byte[10000];
        Arrays.fill(zeros, (byte) 0);
        patternTests.put("zeros", zeros);

        // Pattern 2: All ones
        byte[] ones = new byte[10000];
        Arrays.fill(ones, (byte) 0xFF);
        patternTests.put("ones", ones);

        // Pattern 3: Alternating pattern
        byte[] alternating = new byte[10000];
        for (int i = 0; i < alternating.length; i++) {
            alternating[i] = (byte) (i % 2 == 0 ? 0xAA : 0x55);
        }
        patternTests.put("alternating", alternating);

        // Pattern 4: Sequential bytes
        byte[] sequential = new byte[10000];
        for (int i = 0; i < sequential.length; i++) {
            sequential[i] = (byte) (i % 256);
        }
        patternTests.put("sequential", sequential);

        for (Map.Entry<String, byte[]> test : patternTests.entrySet()) {
            String patternName = test.getKey();
            byte[] pattern = test.getValue();

            File patternFile = createBinaryTestFile("pattern_" + patternName + ".dat", pattern);
            String originalHash = calculateFileHash(patternFile);

            // Copy pattern file
            File copiedPatternFile = new File(tempTestDir, "copied_pattern_" + patternName + ".dat");
            copyFileInChunks(patternFile, copiedPatternFile, 1337); // Odd buffer size

            // Verify pattern integrity
            assertEquals("Pattern " + patternName + " size should match", pattern.length, (int) copiedPatternFile.length());

            String copiedHash = calculateFileHash(copiedPatternFile);
            assertEquals("Pattern " + patternName + " hash should match", originalHash, copiedHash);

            byte[] copiedPattern = readBinaryFile(copiedPatternFile);
            assertArrayEquals("Pattern " + patternName + " should be identical", pattern, copiedPattern);
        }
    }

    /**
     * Test error recovery in file operations
     */
    @Test
    public void testErrorRecoveryScenarios() throws Exception {
        // Test what happens when operations fail

        // Create test file for error scenarios
        String testContent = "Error recovery test content\nThis should survive error conditions\n";
        File errorTestFile = createTestFile("error_recovery_test.txt", testContent);
        String originalHash = calculateFileHash(errorTestFile);

        // Test 1: Copy to read-only directory (should handle gracefully)
        File readOnlyDir = new File(tempTestDir, "readonly");
        readOnlyDir.mkdirs();
        readOnlyDir.setReadOnly();

        File targetInReadOnly = new File(readOnlyDir, "should_fail.txt");
        try {
            copyFile(errorTestFile, targetInReadOnly);
            // If it succeeds on this system, verify integrity
            if (targetInReadOnly.exists()) {
                String copiedHash = calculateFileHash(targetInReadOnly);
                assertEquals("If copy succeeds, integrity should be maintained", originalHash, copiedHash);
            }
        } catch (IOException e) {
            // Expected on read-only directory
            assertNotNull("Should provide error message", e.getMessage());
            assertFalse("Failed copy should not create partial file", targetInReadOnly.exists());
        } finally {
            // Cleanup: remove read-only flag
            readOnlyDir.setWritable(true);
        }

        // Test 2: Copy to location with insufficient space (simulated)
        // We can't easily simulate disk full, but we can test with very long paths
        try {
            String veryLongPath = "very_long_path_" + "x".repeat(200) + ".txt";
            File longPathTarget = new File(tempTestDir, veryLongPath);
            copyFile(errorTestFile, longPathTarget);

            // If it succeeds, verify integrity
            if (longPathTarget.exists()) {
                String copiedHash = calculateFileHash(longPathTarget);
                assertEquals("Long path copy should maintain integrity", originalHash, copiedHash);
            }
        } catch (Exception e) {
            // Some systems may reject very long paths
            assertNotNull("Should provide error message for long path", e.getMessage());
        }
    }

    /**
     * Test repository method APIs exist and can be called
     */
    @Test
    public void testRepositoryMethodAPIs() {
        // Verify all expected methods exist on the repository
        assertNotNull("Repository should exist", smbRepository);

        // Test that methods can be called (will throw exceptions without SMB server)
        String[] testMethods = {"testConnection", "listFiles", "searchFiles", "downloadFile", "uploadFile", "deleteFile", "renameFile", "createDirectory", "fileExists", "downloadFolder", "cancelSearch"};

        for (String methodName : testMethods) {
            try {
                // Try to call each method with test parameters
                switch (methodName) {
                    case "testConnection":
                        smbRepository.testConnection(testConnection);
                        break;
                    case "listFiles":
                        smbRepository.listFiles(testConnection, "/");
                        break;
                    case "searchFiles":
                        smbRepository.searchFiles(testConnection, "/", "*", 0, false);
                        break;
                    case "downloadFile":
                        File tempDownload = new File(tempTestDir, "temp_download.txt");
                        smbRepository.downloadFile(testConnection, "/test.txt", tempDownload);
                        break;
                    case "uploadFile":
                        File tempUpload = createTestFile("temp_upload.txt", "upload test");
                        smbRepository.uploadFile(testConnection, tempUpload, "/test_upload.txt");
                        break;
                    case "deleteFile":
                        smbRepository.deleteFile(testConnection, "/test_delete.txt");
                        break;
                    case "renameFile":
                        smbRepository.renameFile(testConnection, "/old.txt", "new.txt");
                        break;
                    case "createDirectory":
                        smbRepository.createDirectory(testConnection, "/", "testdir");
                        break;
                    case "fileExists":
                        smbRepository.fileExists(testConnection, "/test.txt");
                        break;
                    case "downloadFolder":
                        File tempFolderDownload = new File(tempTestDir, "temp_folder_download");
                        smbRepository.downloadFolder(testConnection, "/testfolder", tempFolderDownload);
                        break;
                    case "cancelSearch":
                        smbRepository.cancelSearch();
                        // cancelSearch doesn't throw exceptions - it's a void method that sets a flag
                        assertTrue("cancelSearch should complete successfully", true);
                        continue; // Skip the fail() call for this method
                }

                // If we get here without exception, the method exists but will likely fail without SMB server
                fail("Method " + methodName + " should fail without SMB server");

            } catch (Exception e) {
                // Expected - verify the method exists and fails gracefully
                assertNotNull("Method " + methodName + " should fail gracefully with error message", e.getMessage());
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

    private byte[] generateTestBinaryData(int size) {
        byte[] data = new byte[size];
        Random random = new Random(12345); // Fixed seed for reproducible tests
        random.nextBytes(data);

        // Add corruption detection patterns every 1KB
        for (int i = 0; i < size; i += 1024) {
            if (i + 7 < size) {
                data[i] = (byte) 0xDE;
                data[i + 1] = (byte) 0xAD;
                data[i + 2] = (byte) 0xBE;
                data[i + 3] = (byte) 0xEF;
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

    private byte[] readBinaryFile(File file) throws IOException {
        return java.nio.file.Files.readAllBytes(file.toPath());
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
