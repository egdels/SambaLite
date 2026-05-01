package de.schliweb.sambalite.data.repository;

import static org.junit.Assert.assertEquals;

import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for path processing in {@link SmbRepositoryImpl}. */
public class PathProcessingTest {

  @Mock private BackgroundSmbManager mockBackgroundManager;

  private SmbRepositoryImpl smbRepository;
  private AutoCloseable mocks;
  private Method getPathWithoutShareMethod;

  @Before
  public void setUp() throws Exception {
    mocks = MockitoAnnotations.openMocks(this);
    smbRepository = new SmbRepositoryImpl(mockBackgroundManager);
    getPathWithoutShareMethod =
        SmbRepositoryImpl.class.getDeclaredMethod("getPathWithoutShare", String.class);
    getPathWithoutShareMethod.setAccessible(true);
  }

  @After
  public void tearDown() throws Exception {
    clearActiveShare();
    if (mocks != null) {
      mocks.close();
    }
  }

  private String invoke(String path) throws Exception {
    return (String) getPathWithoutShareMethod.invoke(smbRepository, path);
  }

  @SuppressWarnings("unchecked")
  private void setActiveShare(String share) throws Exception {
    Field shareField = SmbRepositoryImpl.class.getDeclaredField("currentShareName");
    shareField.setAccessible(true);
    ((ThreadLocal<String>) shareField.get(smbRepository)).set(share);
  }

  @SuppressWarnings("unchecked")
  private void clearActiveShare() throws Exception {
    Field shareField = SmbRepositoryImpl.class.getDeclaredField("currentShareName");
    shareField.setAccessible(true);
    ((ThreadLocal<String>) shareField.get(smbRepository)).remove();
  }

  /** Folder names that look like share names must NOT be stripped (original regression). */
  @Test
  public void testGetPathWithoutShare_doesNotStripFolderLookingLikeShare() throws Exception {
    String path = "Test/ebook shacket gloria komplett 2020.pdf.zip";
    assertEquals(path, invoke(path));
  }

  /**
   * Regression for delete-bug: when share = "christian" and the path points to a sub-sub-folder
   * "christian/christian", the path must be returned share-relative as-is. Previously the heuristic
   * stripped the first "christian" segment, causing delete/rename/create to target the wrong
   * folder.
   */
  @Test
  public void testGetPathWithoutShare_doesNotStripSegmentMatchingActiveShare() throws Exception {
    setActiveShare("christian");
    assertEquals("christian/christian", invoke("christian/christian"));
    assertEquals("christian", invoke("christian"));
    assertEquals("christian/sub/file.txt", invoke("christian/sub/file.txt"));
  }

  /** Even with active share set, share-relative paths are returned unchanged. */
  @Test
  public void testGetPathWithoutShare_keepsSharePrefixedLookingPath() throws Exception {
    setActiveShare("christian");
    // Caller already passes a share-relative path; we must not strip a prefix that happens to
    // match the share name.
    String pathWithLookalikePrefix = "christian/Test/ebook.zip";
    assertEquals(pathWithLookalikePrefix, invoke(pathWithLookalikePrefix));
  }

  /** Leading slashes are normalized away. */
  @Test
  public void testGetPathWithoutShare_normalizesLeadingSlashes() throws Exception {
    assertEquals("foo/bar.txt", invoke("/foo/bar.txt"));
    assertEquals("foo/bar.txt", invoke("\\foo/bar.txt"));
    assertEquals("foo/bar.txt", invoke("//\\foo/bar.txt"));
  }

  /** Null and empty paths are handled gracefully. */
  @Test
  public void testGetPathWithoutShare_nullAndEmpty() throws Exception {
    assertEquals("", invoke(null));
    assertEquals("", invoke(""));
  }

  /** Single-segment paths are passed through unchanged. */
  @Test
  public void testGetPathWithoutShare_singleSegment() throws Exception {
    assertEquals("file.txt", invoke("file.txt"));
    setActiveShare("christian");
    assertEquals("file.txt", invoke("file.txt"));
  }
}
