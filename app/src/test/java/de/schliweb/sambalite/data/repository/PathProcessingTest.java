package de.schliweb.sambalite.data.repository;

import static org.junit.Assert.assertEquals;

import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import java.lang.reflect.Method;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Test for path processing in SmbRepositoryImpl */
public class PathProcessingTest {

  @Mock private BackgroundSmbManager mockBackgroundManager;

  private SmbRepositoryImpl smbRepository;
  private AutoCloseable mocks;

  @Before
  public void setUp() {
    mocks = MockitoAnnotations.openMocks(this);
    smbRepository = new SmbRepositoryImpl(mockBackgroundManager);
  }

  @After
  public void tearDown() throws Exception {
    if (mocks != null) {
      mocks.close();
    }
  }

  /**
   * Test that the getPathWithoutShare method correctly handles paths with folders This test
   * simulates the issue described where a folder name was incorrectly identified as a share name
   * and removed from the path.
   */
  @Test
  public void testGetPathWithoutShare() throws Exception {
    // Use reflection to access the private method
    Method getPathWithoutShareMethod =
        SmbRepositoryImpl.class.getDeclaredMethod("getPathWithoutShare", String.class);
    getPathWithoutShareMethod.setAccessible(true);

    // Test case from the issue description
    String path = "Test/ebook shacket gloria komplett 2020.pdf.zip";
    String result = (String) getPathWithoutShareMethod.invoke(smbRepository, path);

    // The method should NOT remove "Test" from the path
    assertEquals("The folder name should not be removed from the path", path, result);

    // Test with a known share name — set currentShareName via reflection so the method can strip it
    java.lang.reflect.Field shareField =
        SmbRepositoryImpl.class.getDeclaredField("currentShareName");
    shareField.setAccessible(true);
    @SuppressWarnings("unchecked")
    ThreadLocal<String> currentShareName = (ThreadLocal<String>) shareField.get(smbRepository);
    currentShareName.set("christian");

    String pathWithShare = "christian/Test/ebook shacket gloria komplett 2020.pdf.zip";
    String resultWithShare =
        (String) getPathWithoutShareMethod.invoke(smbRepository, pathWithShare);

    // The method should remove "christian" from the path
    assertEquals(
        "The share name should be removed from the path",
        "Test/ebook shacket gloria komplett 2020.pdf.zip",
        resultWithShare);

    // Clean up
    currentShareName.remove();
  }
}
