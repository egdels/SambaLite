package de.schliweb.sambalite.ui;

import android.content.Context;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import de.schliweb.sambalite.cache.IntelligentCacheManager;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.data.repository.SmbRepository;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link FileListViewModel}.
 */
@RunWith(MockitoJUnitRunner.class)
public class FileListViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    @Mock
    private SmbRepository smbRepository;

    @Mock
    private Context context;

    @Mock
    private FileBrowserState state;

    @Mock
    private IntelligentCacheManager cacheManager;

    private FileListViewModel viewModel;
    private SmbConnection testConnection;
    private List<SmbFileItem> testFiles;

    @Before
    public void setup() {
        // Create test data
        testConnection = new SmbConnection();
        testConnection.setId("test-connection-id");
        testConnection.setName("Test Connection");
        testConnection.setShare("test-share");

        testFiles = Arrays.asList(createTestFile("file1.txt", false), createTestFile("file2.txt", false), createTestFile("folder1", true));

        // Mock state behavior
        when(state.getConnection()).thenReturn(testConnection);
        when(state.getCurrentPathString()).thenReturn("");

        // Initialize the view model
        viewModel = new FileListViewModel(smbRepository, context, state);

        // Replace the executor with a direct executor that runs tasks immediately on the same thread
        // This ensures that our mocking of IntelligentCacheManager works correctly
        java.lang.reflect.Field executorField;
        try {
            executorField = FileListViewModel.class.getDeclaredField("executor");
            executorField.setAccessible(true);
            executorField.set(viewModel, new DirectExecutor());
        } catch (Exception e) {
            throw new RuntimeException("Failed to replace executor", e);
        }
    }

    @Test
    public void testLoadFiles_fromRepository() throws Exception {
        // Arrange
        when(smbRepository.listFiles(any(SmbConnection.class), anyString())).thenReturn(testFiles);

        try (MockedStatic<IntelligentCacheManager> mockedStatic = mockStatic(IntelligentCacheManager.class)) {
            // Ensure the mock always returns our cacheManager instance without checking initialization
            mockedStatic.when(IntelligentCacheManager::getInstance).thenReturn(cacheManager);

            // Mock the behavior of our cache manager
            when(cacheManager.getCachedFileList(any(SmbConnection.class), anyString())).thenReturn(null);

            // Act
            viewModel.loadFiles();

            // Wait for background tasks to complete
            Thread.sleep(100);

            // Assert
            verify(state).setLoading(true);
            verify(smbRepository).listFiles(eq(testConnection), eq(""));
            verify(state).setFiles(eq(testFiles));
            verify(state).setLoading(false);
        }
    }

    @Test
    public void testLoadFiles_fromCache() throws Exception {
        // Arrange
        try (MockedStatic<IntelligentCacheManager> mockedStatic = mockStatic(IntelligentCacheManager.class)) {
            // Ensure the mock always returns our cacheManager instance without checking initialization
            mockedStatic.when(IntelligentCacheManager::getInstance).thenReturn(cacheManager);

            // Mock the behavior of our cache manager
            when(cacheManager.getCachedFileList(any(SmbConnection.class), anyString())).thenReturn(testFiles);

            // Act
            viewModel.loadFiles();

            // Wait for background tasks to complete
            Thread.sleep(100);

            // Assert
            verify(state).setLoading(true);
            verify(smbRepository, times(0)).listFiles(any(SmbConnection.class), anyString());
            verify(state).setFiles(eq(testFiles));
            verify(state).setLoading(false);
        }
    }

    @Test
    public void testNavigateToDirectory() throws Exception {
        // Arrange
        SmbFileItem directory = createTestFile("folder1", true);
        directory.setPath("folder1");

        // Mock repository to return files for the directory
        List<SmbFileItem> directoryFiles = Arrays.asList(createTestFile("file3.txt", false), createTestFile("file4.txt", false));
        when(smbRepository.listFiles(any(SmbConnection.class), eq("folder1"))).thenReturn(directoryFiles);

        try (MockedStatic<IntelligentCacheManager> mockedStatic = mockStatic(IntelligentCacheManager.class)) {
            // Ensure the mock always returns our cacheManager instance without checking initialization
            mockedStatic.when(IntelligentCacheManager::getInstance).thenReturn(cacheManager);

            // Mock the behavior of our cache manager - explicitly return null to force repository call
            doReturn(null).when(cacheManager).getCachedFileList(any(SmbConnection.class), eq("folder1"));

            // Important: Update the mock to return "folder1" after setCurrentPath is called
            // This is crucial because the loadFiles method uses getCurrentPathString to determine the path
            doAnswer(invocation -> {
                // After setCurrentPath("folder1") is called, getCurrentPathString should return "folder1"
                when(state.getCurrentPathString()).thenReturn("folder1");
                return null; // setCurrentPath returns void
            }).when(state).setCurrentPath(eq("folder1"));

            // Add debug verification to check if our mock is working
            System.out.println("[DEBUG_LOG] Before navigateToDirectory - cacheManager mock setup");

            // Act
            viewModel.navigateToDirectory(directory);

            // Debug verification
            System.out.println("[DEBUG_LOG] After navigateToDirectory - verifying interactions");
            System.out.println("[DEBUG_LOG] Current path: " + state.getCurrentPathString());

            // Verify state changes first
            verify(state).pushPath(eq(""));
            verify(state).setCurrentPath(eq("folder1"));
            verify(state).setLoading(true);

            // Verify cache manager was called with the correct path
            verify(cacheManager).getCachedFileList(any(SmbConnection.class), eq("folder1"));

            // Now verify repository was called
            verify(smbRepository).listFiles(eq(testConnection), eq("folder1"));
            verify(state).setFiles(eq(directoryFiles));
            verify(state).setLoading(false);
        }
    }

    @Test
    public void testNavigateUp() throws Exception {
        // Arrange
        when(state.hasParentDirectory()).thenReturn(true);
        when(state.popPath()).thenReturn("parent-path");

        // Mock repository to return files for the parent directory
        List<SmbFileItem> parentFiles = Arrays.asList(createTestFile("file5.txt", false), createTestFile("file6.txt", false));
        when(smbRepository.listFiles(any(SmbConnection.class), eq("parent-path"))).thenReturn(parentFiles);

        try (MockedStatic<IntelligentCacheManager> mockedStatic = mockStatic(IntelligentCacheManager.class)) {
            // Ensure the mock always returns our cacheManager instance without checking initialization
            mockedStatic.when(IntelligentCacheManager::getInstance).thenReturn(cacheManager);

            // Mock the behavior of our cache manager
            when(cacheManager.getCachedFileList(any(SmbConnection.class), eq("parent-path"))).thenReturn(null);

            // Important: Update the mock to return "parent-path" after setCurrentPath is called
            // This is crucial because the loadFiles method uses getCurrentPathString to determine the path
            doAnswer(invocation -> {
                // After setCurrentPath("parent-path") is called, getCurrentPathString should return "parent-path"
                when(state.getCurrentPathString()).thenReturn("parent-path");
                return null; // setCurrentPath returns void
            }).when(state).setCurrentPath(eq("parent-path"));

            // Add debug verification
            System.out.println("[DEBUG_LOG] Before navigateUp - mocks setup");

            // Act
            boolean result = viewModel.navigateUp();

            // Debug verification
            System.out.println("[DEBUG_LOG] After navigateUp - verifying interactions");
            System.out.println("[DEBUG_LOG] Current path: " + state.getCurrentPathString());

            // No need to wait for background tasks since we're using a direct executor

            // Assert
            assertTrue(result);
            verify(state).hasParentDirectory();
            verify(state).popPath();
            verify(state).setCurrentPath(eq("parent-path"));
            verify(state).setLoading(true);
            verify(smbRepository).listFiles(eq(testConnection), eq("parent-path"));
            verify(state).setFiles(eq(parentFiles));
            verify(state).setLoading(false);
        }
    }

    @Test
    public void testNavigateUp_alreadyAtRoot() {
        // Arrange
        when(state.hasParentDirectory()).thenReturn(false);

        // Act
        boolean result = viewModel.navigateUp();

        // Assert
        assertFalse(result);
        verify(state).hasParentDirectory();
    }

    @Test
    public void testSetSortOption() throws Exception {
        // Arrange
        try (MockedStatic<IntelligentCacheManager> mockedStatic = mockStatic(IntelligentCacheManager.class)) {
            // Ensure the mock always returns our cacheManager instance without checking initialization
            mockedStatic.when(IntelligentCacheManager::getInstance).thenReturn(cacheManager);

            // Act
            viewModel.setSortOption(FileSortOption.DATE);

            // Wait for background tasks to complete
            Thread.sleep(100);

            // Assert
            verify(state).setSortOption(eq(FileSortOption.DATE));
            verify(cacheManager).invalidateConnection(eq(testConnection));
        }
    }

    @Test
    public void testSetDirectoriesFirst() throws Exception {
        // Arrange
        try (MockedStatic<IntelligentCacheManager> mockedStatic = mockStatic(IntelligentCacheManager.class)) {
            // Ensure the mock always returns our cacheManager instance without checking initialization
            mockedStatic.when(IntelligentCacheManager::getInstance).thenReturn(cacheManager);

            // Act
            viewModel.setDirectoriesFirst(false);

            // Wait for background tasks to complete
            Thread.sleep(100);

            // Assert
            verify(state).setDirectoriesFirst(eq(false));
            verify(cacheManager).invalidateConnection(eq(testConnection));
        }
    }

    @Test
    public void testSortFiles() {
        // Arrange
        List<SmbFileItem> unsortedFiles = new ArrayList<>(Arrays.asList(createTestFile("c.txt", false), createTestFile("a.txt", false), createTestFile("folder2", true), createTestFile("b.txt", false), createTestFile("folder1", true)));

        when(state.getCurrentSortOption()).thenReturn(FileSortOption.NAME);
        when(state.isDirectoriesFirst()).thenReturn(true);

        // Act
        viewModel.sortFiles(unsortedFiles);

        // Assert
        assertEquals("folder1", unsortedFiles.get(0).getName());
        assertEquals("folder2", unsortedFiles.get(1).getName());
        assertEquals("a.txt", unsortedFiles.get(2).getName());
        assertEquals("b.txt", unsortedFiles.get(3).getName());
        assertEquals("c.txt", unsortedFiles.get(4).getName());
    }

    /**
     * Helper method to create a test file.
     */
    private SmbFileItem createTestFile(String name, boolean isDirectory) {
        String path = name;
        SmbFileItem.Type type = isDirectory ? SmbFileItem.Type.DIRECTORY : SmbFileItem.Type.FILE;
        long size = isDirectory ? 0 : 1024; // Default size for files
        Date lastModified = new Date(); // Current date

        return new SmbFileItem(name, path, type, size, lastModified);
    }

    /**
     * A direct executor that runs tasks immediately on the same thread.
     * This is used to make tests synchronous and predictable.
     */
    private static class DirectExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }
}