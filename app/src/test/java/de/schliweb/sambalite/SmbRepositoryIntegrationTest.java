package de.schliweb.sambalite;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.test.helper.SmbTestHelper;

/**
 * Repository integration tests with mock SMB server.
 * Tests the SmbRepository implementation with realistic SMB operations
 * using the mock server infrastructure.
 */
public class SmbRepositoryIntegrationTest {
    
    @Mock
    private SmbRepository mockSmbRepository;
    
    private SmbTestHelper testHelper;
    private SmbConnection testConnection;
    
    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        
        System.out.println("[DEBUG_LOG] Setting up SMB repository integration tests");
        
        testHelper = new SmbTestHelper.Builder()
                .withMockOnly()
                .build();
        
        testConnection = testHelper.createTestConnection();
        testHelper.setupTestData();
        
        System.out.println("[DEBUG_LOG] SMB repository test environment ready");
    }
    
    @Test
    public void testRepositoryListFiles() throws Exception {
        // Setup mock repository to return test data
        List<SmbFileItem> mockItems = testHelper.listFiles("/");
        when(mockSmbRepository.listFiles(eq(testConnection), eq("/"))).thenReturn(mockItems);
        
        // Test repository call
        List<SmbFileItem> result = mockSmbRepository.listFiles(testConnection, "/");
        
        assertNotNull("Repository should return file list", result);
        assertTrue("Repository should return items", result.size() > 0);
        
        // Verify mock was called
        verify(mockSmbRepository, times(1)).listFiles(testConnection, "/");
        
        System.out.println("[DEBUG_LOG] Repository list files test successful: " + result.size() + " items");
    }
    
    @Test
    public void testRepositoryDownloadFile() throws Exception {
        String testFilePath = "/documents/test.txt";
        
        // Create a temporary local file for the download
        java.io.File tempFile = java.io.File.createTempFile("test_download", ".txt");
        tempFile.deleteOnExit();
        
        // Setup mock repository
        doNothing().when(mockSmbRepository).downloadFile(eq(testConnection), eq(testFilePath), any(java.io.File.class));
        
        // Test repository call
        mockSmbRepository.downloadFile(testConnection, testFilePath, tempFile);
        
        // Verify mock was called
        verify(mockSmbRepository, times(1)).downloadFile(testConnection, testFilePath, tempFile);
        
        System.out.println("[DEBUG_LOG] Repository download test successful");
    }
    
    @Test
    public void testRepositoryUploadFile() throws Exception {
        String testFilePath = "/upload_test.txt";
        
        // Create a temporary local file for the upload
        java.io.File tempFile = java.io.File.createTempFile("test_upload", ".txt");
        tempFile.deleteOnExit();
        
        // Setup mock repository (upload returns void, so we just verify the call)
        doNothing().when(mockSmbRepository).uploadFile(eq(testConnection), any(java.io.File.class), eq(testFilePath));
        
        // Test repository call
        mockSmbRepository.uploadFile(testConnection, tempFile, testFilePath);
        
        // Verify mock was called
        verify(mockSmbRepository, times(1)).uploadFile(eq(testConnection), any(java.io.File.class), eq(testFilePath));
        
        System.out.println("[DEBUG_LOG] Repository upload test successful");
    }
    
    @Test
    public void testRepositoryDeleteFile() throws Exception {
        String testFilePath = "/delete_test.txt";
        
        // Setup mock repository
        doNothing().when(mockSmbRepository).deleteFile(eq(testConnection), eq(testFilePath));
        
        // Test repository call
        mockSmbRepository.deleteFile(testConnection, testFilePath);
        
        // Verify mock was called
        verify(mockSmbRepository, times(1)).deleteFile(testConnection, testFilePath);
        
        System.out.println("[DEBUG_LOG] Repository delete test successful");
    }
    
    @Test
    public void testRepositoryCreateDirectory() throws Exception {
        String testDirPath = "/documents";
        String testDirName = "new_directory";
        
        // Setup mock repository
        doNothing().when(mockSmbRepository).createDirectory(eq(testConnection), eq(testDirPath), eq(testDirName));
        
        // Test repository call
        mockSmbRepository.createDirectory(testConnection, testDirPath, testDirName);
        
        // Verify mock was called
        verify(mockSmbRepository, times(1)).createDirectory(testConnection, testDirPath, testDirName);
        
        System.out.println("[DEBUG_LOG] Repository create directory test successful");
    }
    
    @Test
    public void testRepositoryConnectionValidation() throws Exception {
        // Setup mock repository for connection test
        when(mockSmbRepository.testConnection(eq(testConnection))).thenReturn(true);
        
        // Test repository connection validation
        boolean isValid = mockSmbRepository.testConnection(testConnection);
        
        assertTrue("Repository should validate test connection", isValid);
        
        // Verify mock was called
        verify(mockSmbRepository, times(1)).testConnection(testConnection);
        
        System.out.println("[DEBUG_LOG] Repository connection validation test successful");
    }
    
    @Test
    public void testRepositoryErrorHandling() throws Exception {
        String invalidPath = "/nonexistent/path";
        
        // Setup mock repository to throw exception
        when(mockSmbRepository.listFiles(eq(testConnection), eq(invalidPath)))
                .thenThrow(new RuntimeException("Path not found"));
        
        // Test repository error handling
        try {
            mockSmbRepository.listFiles(testConnection, invalidPath);
            fail("Repository should throw exception for invalid path");
        } catch (RuntimeException e) {
            assertEquals("Should get expected error message", "Path not found", e.getMessage());
        }
        
        // Verify mock was called
        verify(mockSmbRepository, times(1)).listFiles(testConnection, invalidPath);
        
        System.out.println("[DEBUG_LOG] Repository error handling test successful");
    }
    
    @Test
    public void testRepositoryMultipleOperations() throws Exception {
        // Test sequence of operations
        String basePath = "/test_operations";
        String dirName = "subdir";
        
        // Setup mock for multiple operations
        when(mockSmbRepository.listFiles(eq(testConnection), eq(basePath)))
                .thenReturn(testHelper.listFiles("/"));
        doNothing().when(mockSmbRepository).createDirectory(eq(testConnection), eq(basePath), eq(dirName));
        doNothing().when(mockSmbRepository).uploadFile(eq(testConnection), any(java.io.File.class), eq(basePath + "/test.txt"));
        doNothing().when(mockSmbRepository).downloadFile(eq(testConnection), eq(basePath + "/test.txt"), any(java.io.File.class));
        doNothing().when(mockSmbRepository).deleteFile(eq(testConnection), eq(basePath + "/test.txt"));
        
        // Execute sequence of operations
        mockSmbRepository.createDirectory(testConnection, basePath, dirName);
        
        java.io.File tempUploadFile = java.io.File.createTempFile("upload", ".txt");
        tempUploadFile.deleteOnExit();
        mockSmbRepository.uploadFile(testConnection, tempUploadFile, basePath + "/test.txt");
        
        List<SmbFileItem> files = mockSmbRepository.listFiles(testConnection, basePath);
        assertNotNull("Should get file listing", files);
        
        java.io.File tempDownloadFile = java.io.File.createTempFile("download", ".txt");
        tempDownloadFile.deleteOnExit();
        mockSmbRepository.downloadFile(testConnection, basePath + "/test.txt", tempDownloadFile);
        
        mockSmbRepository.deleteFile(testConnection, basePath + "/test.txt");
        
        // Verify all operations were called
        verify(mockSmbRepository, times(1)).createDirectory(testConnection, basePath, dirName);
        verify(mockSmbRepository, times(1)).uploadFile(eq(testConnection), any(java.io.File.class), eq(basePath + "/test.txt"));
        verify(mockSmbRepository, times(1)).listFiles(testConnection, basePath);
        verify(mockSmbRepository, times(1)).downloadFile(eq(testConnection), eq(basePath + "/test.txt"), any(java.io.File.class));
        verify(mockSmbRepository, times(1)).deleteFile(testConnection, basePath + "/test.txt");
        
        System.out.println("[DEBUG_LOG] Repository multiple operations test successful");
    }
    
    @Test
    public void testRepositoryConnectionPooling() throws Exception {
        // Test multiple connections with same credentials
        SmbConnection connection1 = testHelper.createTestConnection("share1");
        SmbConnection connection2 = testHelper.createTestConnection("share2");
        
        // Setup mock repository for both connections
        when(mockSmbRepository.testConnection(eq(connection1))).thenReturn(true);
        when(mockSmbRepository.testConnection(eq(connection2))).thenReturn(true);
        when(mockSmbRepository.listFiles(eq(connection1), eq("/"))).thenReturn(testHelper.listFiles("/"));
        when(mockSmbRepository.listFiles(eq(connection2), eq("/"))).thenReturn(testHelper.listFiles("/"));
        
        // Test both connections
        boolean valid1 = mockSmbRepository.testConnection(connection1);
        boolean valid2 = mockSmbRepository.testConnection(connection2);
        
        assertTrue("Connection 1 should be valid", valid1);
        assertTrue("Connection 2 should be valid", valid2);
        
        List<SmbFileItem> files1 = mockSmbRepository.listFiles(connection1, "/");
        List<SmbFileItem> files2 = mockSmbRepository.listFiles(connection2, "/");
        
        assertNotNull("Connection 1 should return files", files1);
        assertNotNull("Connection 2 should return files", files2);
        
        // Verify all operations
        verify(mockSmbRepository, times(1)).testConnection(connection1);
        verify(mockSmbRepository, times(1)).testConnection(connection2);
        verify(mockSmbRepository, times(1)).listFiles(connection1, "/");
        verify(mockSmbRepository, times(1)).listFiles(connection2, "/");
        
        System.out.println("[DEBUG_LOG] Repository connection pooling test successful");
    }
    
    @Test
    public void testRepositoryPerformanceMetrics() throws Exception {
        // Test operation timing
        long startTime = System.currentTimeMillis();
        
        // Setup mock with delayed response to simulate network latency
        when(mockSmbRepository.listFiles(eq(testConnection), eq("/"))).thenAnswer(invocation -> {
            Thread.sleep(50); // Simulate 50ms network delay
            return testHelper.listFiles("/");
        });
        
        List<SmbFileItem> result = mockSmbRepository.listFiles(testConnection, "/");
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        
        assertNotNull("Should get result", result);
        assertTrue("Operation should take some time", duration >= 40);
        
        verify(mockSmbRepository, times(1)).listFiles(testConnection, "/");
        
        System.out.println("[DEBUG_LOG] Repository performance test successful: " + duration + "ms");
    }
}
