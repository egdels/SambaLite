package de.schliweb.sambalite;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.test.helper.SmbTestHelper;
import de.schliweb.sambalite.test.mock.MockSmbServer;

/**
 * Comprehensive SMB integration tests using the mock SMB server.
 * Tests all major SMB operations including file listing, upload, download, and error scenarios.
 */
public class SmbIntegrationTest {
    
    private SmbTestHelper testHelper;
    private MockSmbServer mockServer;
    
    @Before
    public void setUp() {
        System.out.println("[DEBUG_LOG] Setting up SMB integration tests");
        
        // Initialize test helper with mock-only mode for reliable testing
        testHelper = new SmbTestHelper.Builder()
                .withMockOnly()
                .build();
        
        mockServer = new MockSmbServer();
        testHelper.setupTestData();
        
        System.out.println("[DEBUG_LOG] SMB test environment ready");
    }
    
    @After
    public void tearDown() {
        if (testHelper != null) {
            testHelper.cleanup();
        }
        System.out.println("[DEBUG_LOG] SMB integration test cleanup completed");
    }
    
    @Test
    public void testCreateTestConnection() {
        SmbConnection connection = testHelper.createTestConnection();
        
        assertNotNull("Test connection should not be null", connection);
        assertNotNull("Connection host should not be null", connection.getServer());
        assertNotNull("Connection username should not be null", connection.getUsername());
        assertNotNull("Connection share should not be null", connection.getShare());
        
        System.out.println("[DEBUG_LOG] Test connection created: " + connection.getName());
    }
    
    @Test
    public void testListRootDirectory() {
        List<SmbFileItem> items = testHelper.listFiles("/");
        
        assertNotNull("File list should not be null", items);
        assertTrue("Root directory should contain items", items.size() > 0);
        
        // Check for expected default directories
        boolean hasDocuments = false;
        boolean hasImages = false;
        boolean hasTestFile = false;
        
        for (SmbFileItem item : items) {
            if ("documents".equals(item.getName()) && item.isDirectory()) {
                hasDocuments = true;
            } else if ("images".equals(item.getName()) && item.isDirectory()) {
                hasImages = true;
            } else if ("test_file.txt".equals(item.getName()) && !item.isDirectory()) {
                hasTestFile = true;
            }
        }
        
        assertTrue("Should contain 'documents' directory", hasDocuments);
        assertTrue("Should contain 'images' directory", hasImages);
        assertTrue("Should contain 'test_file.txt'", hasTestFile);
        
        System.out.println("[DEBUG_LOG] Root directory listing successful: " + items.size() + " items");
    }
    
    @Test
    public void testListSubdirectory() {
        List<SmbFileItem> items = testHelper.listFiles("/documents");
        
        assertNotNull("Documents list should not be null", items);
        assertTrue("Documents directory should contain files", items.size() > 0);
        
        // Check for expected test files
        boolean hasTestTxt = false;
        boolean hasReadmeMd = false;
        
        for (SmbFileItem item : items) {
            assertFalse("Items in documents should be files, not directories", item.isDirectory());
            
            if ("test.txt".equals(item.getName())) {
                hasTestTxt = true;
                assertTrue("test.txt should have positive size", item.getSize() > 0);
            } else if ("readme.md".equals(item.getName())) {
                hasReadmeMd = true;
                assertTrue("readme.md should have positive size", item.getSize() > 0);
            }
        }
        
        assertTrue("Should contain 'test.txt'", hasTestTxt);
        assertTrue("Should contain 'readme.md'", hasReadmeMd);
        
        System.out.println("[DEBUG_LOG] Documents directory listing successful: " + items.size() + " items");
    }
    
    @Test
    public void testDownloadFile() {
        try {
            InputStream inputStream = testHelper.downloadFile("/documents/test.txt");
            
            assertNotNull("Download stream should not be null", inputStream);
            
            // Read content and verify
            byte[] buffer = new byte[1024];
            int bytesRead = inputStream.read(buffer);
            
            assertTrue("Should read some bytes", bytesRead > 0);
            
            String content = new String(buffer, 0, bytesRead);
            assertTrue("Content should contain expected text", content.contains("test document"));
            
            inputStream.close();
            
            System.out.println("[DEBUG_LOG] File download successful: " + bytesRead + " bytes");
            
        } catch (Exception e) {
            fail("File download should not throw exception: " + e.getMessage());
        }
    }
    
    @Test
    public void testUploadFile() {
        try {
            String testContent = "This is a test upload file content";
            ByteArrayInputStream uploadStream = new ByteArrayInputStream(testContent.getBytes());
            
            // Upload the file
            testHelper.uploadFile("/uploaded_test.txt", uploadStream);
            
            // Verify the file was uploaded by listing root directory
            List<SmbFileItem> items = testHelper.listFiles("/");
            
            boolean fileFound = false;
            for (SmbFileItem item : items) {
                if ("uploaded_test.txt".equals(item.getName())) {
                    fileFound = true;
                    assertFalse("Uploaded file should not be a directory", item.isDirectory());
                    assertEquals("Uploaded file should have correct size", 
                               testContent.length(), item.getSize());
                    break;
                }
            }
            
            assertTrue("Uploaded file should be found in directory listing", fileFound);
            
            // Verify content by downloading
            InputStream downloadStream = testHelper.downloadFile("/uploaded_test.txt");
            byte[] buffer = new byte[1024];
            int bytesRead = downloadStream.read(buffer);
            String downloadedContent = new String(buffer, 0, bytesRead);
            
            assertEquals("Downloaded content should match uploaded content", 
                        testContent, downloadedContent);
            
            downloadStream.close();
            
            System.out.println("[DEBUG_LOG] File upload and verification successful");
            
        } catch (Exception e) {
            fail("File upload should not throw exception: " + e.getMessage());
        }
    }
    
    @Test
    public void testDeleteFile() {
        try {
            // First upload a file to delete
            String testContent = "File to be deleted";
            ByteArrayInputStream uploadStream = new ByteArrayInputStream(testContent.getBytes());
            testHelper.uploadFile("/delete_test.txt", uploadStream);
            
            // Verify file exists
            List<SmbFileItem> itemsBefore = testHelper.listFiles("/");
            boolean fileExistsBefore = itemsBefore.stream()
                    .anyMatch(item -> "delete_test.txt".equals(item.getName()));
            assertTrue("File should exist before deletion", fileExistsBefore);
            
            // Delete the file
            testHelper.deleteFile("/delete_test.txt");
            
            // Verify file is deleted
            List<SmbFileItem> itemsAfter = testHelper.listFiles("/");
            boolean fileExistsAfter = itemsAfter.stream()
                    .anyMatch(item -> "delete_test.txt".equals(item.getName()));
            assertFalse("File should not exist after deletion", fileExistsAfter);
            
            System.out.println("[DEBUG_LOG] File deletion successful");
            
        } catch (Exception e) {
            fail("File deletion should not throw exception: " + e.getMessage());
        }
    }
    
    @Test
    public void testCreateDirectory() {
        try {
            // Create a new directory
            testHelper.createDirectory("/new_test_directory");
            
            // Verify directory was created
            List<SmbFileItem> items = testHelper.listFiles("/");
            
            boolean directoryFound = false;
            for (SmbFileItem item : items) {
                if ("new_test_directory".equals(item.getName())) {
                    directoryFound = true;
                    assertTrue("Created item should be a directory", item.isDirectory());
                    break;
                }
            }
            
            assertTrue("Created directory should be found in listing", directoryFound);
            
            // Verify we can list the empty directory
            List<SmbFileItem> emptyDirItems = testHelper.listFiles("/new_test_directory");
            assertNotNull("Empty directory listing should not be null", emptyDirItems);
            assertEquals("New directory should be empty", 0, emptyDirItems.size());
            
            System.out.println("[DEBUG_LOG] Directory creation successful");
            
        } catch (Exception e) {
            fail("Directory creation should not throw exception: " + e.getMessage());
        }
    }
    
    @Test
    public void testErrorScenarios() {
        // Test connection failure
        testHelper.setConnectionFailure(true);
        
        try {
            testHelper.listFiles("/");
            fail("Should throw exception when connection fails");
        } catch (Exception e) {
            assertTrue("Should get connection failure exception", 
                      e.getMessage().contains("connection failure"));
        }
        
        // Reset connection failure
        testHelper.setConnectionFailure(false);
        
        // Test file operation failure
        testHelper.setFileOperationFailure(true);
        
        try {
            testHelper.listFiles("/");
            fail("Should throw exception when file operations fail");
        } catch (Exception e) {
            assertTrue("Should get file operation failure exception", 
                      e.getMessage().contains("file operation failure"));
        }
        
        // Reset file operation failure
        testHelper.setFileOperationFailure(false);
        
        System.out.println("[DEBUG_LOG] Error scenarios testing successful");
    }
    
    @Test
    public void testNetworkDelaySimulation() {
        // Set network delay
        testHelper.setNetworkDelay(100); // 100ms delay
        
        long startTime = System.currentTimeMillis();
        testHelper.listFiles("/");
        long endTime = System.currentTimeMillis();
        
        long duration = endTime - startTime;
        assertTrue("Operation should take at least 100ms with delay", duration >= 90);
        
        // Reset network delay
        testHelper.setNetworkDelay(0);
        
        System.out.println("[DEBUG_LOG] Network delay simulation successful: " + duration + "ms");
    }
    
    @Test
    public void testFileNotFound() {
        try {
            testHelper.downloadFile("/nonexistent_file.txt");
            fail("Should throw exception for non-existent file");
        } catch (Exception e) {
            assertTrue("Should get file not found exception", 
                      e.getMessage().contains("not found"));
        }
        
        System.out.println("[DEBUG_LOG] File not found error handling successful");
    }
    
    @Test
    public void testLargeFileOperations() {
        try {
            // Create a larger test file (1KB)
            byte[] largeContent = new byte[1024];
            for (int i = 0; i < largeContent.length; i++) {
                largeContent[i] = (byte) (i % 256);
            }
            
            ByteArrayInputStream uploadStream = new ByteArrayInputStream(largeContent);
            testHelper.uploadFile("/large_test_file.bin", uploadStream);
            
            // Verify file was uploaded with correct size
            List<SmbFileItem> items = testHelper.listFiles("/");
            boolean fileFound = false;
            for (SmbFileItem item : items) {
                if ("large_test_file.bin".equals(item.getName())) {
                    fileFound = true;
                    assertEquals("Large file should have correct size", 
                               largeContent.length, item.getSize());
                    break;
                }
            }
            
            assertTrue("Large file should be found", fileFound);
            
            // Download and verify content
            InputStream downloadStream = testHelper.downloadFile("/large_test_file.bin");
            byte[] downloadedContent = new byte[2048]; // Buffer larger than file
            int totalBytesRead = 0;
            int bytesRead;
            
            while ((bytesRead = downloadStream.read(downloadedContent, totalBytesRead, 
                                                   downloadedContent.length - totalBytesRead)) != -1) {
                totalBytesRead += bytesRead;
            }
            
            assertEquals("Downloaded size should match uploaded size", 
                        largeContent.length, totalBytesRead);
            
            // Verify content integrity
            for (int i = 0; i < largeContent.length; i++) {
                assertEquals("Byte at position " + i + " should match", 
                           largeContent[i], downloadedContent[i]);
            }
            
            downloadStream.close();
            
            System.out.println("[DEBUG_LOG] Large file operations successful: " + totalBytesRead + " bytes");
            
        } catch (Exception e) {
            fail("Large file operations should not throw exception: " + e.getMessage());
        }
    }
    
    @Test
    public void testServerStatistics() {
        String stats = testHelper.getServerStats();
        
        assertNotNull("Server stats should not be null", stats);
        assertTrue("Stats should contain information", stats.length() > 0);
        assertTrue("Stats should mention directories", stats.contains("dirs="));
        assertTrue("Stats should mention files", stats.contains("files="));
        
        System.out.println("[DEBUG_LOG] Server statistics: " + stats);
    }
}
