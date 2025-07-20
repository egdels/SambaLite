package de.schliweb.sambalite.data.repository;

import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;

/**
 * Test for path processing in SmbRepositoryImpl
 */
public class PathProcessingTest {

    @Mock
    private BackgroundSmbManager mockBackgroundManager;

    private SmbRepositoryImpl smbRepository;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        smbRepository = new SmbRepositoryImpl(mockBackgroundManager);
    }

    /**
     * Test that the getPathWithoutShare method correctly handles paths with folders
     * This test simulates the issue described where a folder name was incorrectly
     * identified as a share name and removed from the path.
     */
    @Test
    public void testGetPathWithoutShare() throws Exception {
        // Use reflection to access the private method
        Method getPathWithoutShareMethod = SmbRepositoryImpl.class.getDeclaredMethod("getPathWithoutShare", String.class);
        getPathWithoutShareMethod.setAccessible(true);

        // Test case from the issue description
        String path = "Test/ebook shacket gloria komplett 2020.pdf.zip";
        String result = (String) getPathWithoutShareMethod.invoke(smbRepository, path);

        // The method should NOT remove "Test" from the path
        assertEquals("The folder name should not be removed from the path", path, result);

        // Test with a known share name
        String pathWithShare = "christian/Test/ebook shacket gloria komplett 2020.pdf.zip";
        String resultWithShare = (String) getPathWithoutShareMethod.invoke(smbRepository, pathWithShare);

        // The method should remove "christian" from the path
        assertEquals("The share name should be removed from the path", "Test/ebook shacket gloria komplett 2020.pdf.zip", resultWithShare);
    }
}