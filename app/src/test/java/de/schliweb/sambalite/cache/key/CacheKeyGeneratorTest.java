package de.schliweb.sambalite.cache.key;

import static org.junit.Assert.*;

import de.schliweb.sambalite.data.model.SmbConnection;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link CacheKeyGenerator}. */
public class CacheKeyGeneratorTest {

  private CacheKeyGenerator generator;
  private SmbConnection connection;

  @Before
  public void setUp() {
    generator = new CacheKeyGenerator(null);
    connection = new SmbConnection();
    connection.setId("test-conn-1");
    connection.setName("TestServer");
  }

  // --- generateFileListKey ---

  @Test
  public void generateFileListKey_containsConnectionIdAndPath() {
    String key = generator.generateFileListKey(connection, "docs/subfolder");
    assertTrue(key.startsWith("files_conn_"));
    assertTrue(key.contains("test-conn-1"));
    assertTrue(key.contains("docs/subfolder"));
  }

  @Test
  public void generateFileListKey_rootPath() {
    String key = generator.generateFileListKey(connection, "");
    assertTrue(key.contains("root"));
  }

  // --- generateSearchKey ---

  @Test
  public void generateSearchKey_containsAllParameters() {
    String key = generator.generateSearchKey(connection, "music/rock", "*.mp3", 1, true);
    assertTrue(key.startsWith("search_conn_"));
    assertTrue(key.contains("test-conn-1"));
    assertTrue(key.contains("music/rock"));
    assertTrue(key.contains("type_1"));
    assertTrue(key.contains("sub_true"));
  }

  @Test
  public void generateSearchKey_subfoldersFlag() {
    String keyTrue = generator.generateSearchKey(connection, "path", "q", 0, true);
    String keyFalse = generator.generateSearchKey(connection, "path", "q", 0, false);
    assertTrue(keyTrue.contains("sub_true"));
    assertTrue(keyFalse.contains("sub_false"));
  }

  // --- generateFileKey ---

  @Test
  public void generateFileKey_containsAllParts() {
    String key = generator.generateFileKey(connection, "docs", "readme.txt");
    assertTrue(key.startsWith("file_conn_"));
    assertTrue(key.contains("test-conn-1"));
    assertTrue(key.contains("docs"));
    assertTrue(key.contains("readme.txt"));
  }

  // --- generateCustomKey ---

  @Test
  public void generateCustomKey_withParams() {
    String key = generator.generateCustomKey("prefix", "param1", "param2");
    assertTrue(key.startsWith("prefix"));
    assertTrue(key.contains("param1"));
    assertTrue(key.contains("param2"));
  }

  @Test
  public void generateCustomKey_emptyParamsIgnored() {
    String key = generator.generateCustomKey("prefix", "", "valid");
    assertTrue(key.startsWith("prefix"));
    assertTrue(key.contains("valid"));
  }

  // --- generateInvalidationPattern ---

  @Test
  public void generateInvalidationPattern_containsConnectionAndPath() {
    String pattern = generator.generateInvalidationPattern(connection, "music");
    assertTrue(pattern.startsWith("conn_"));
    assertTrue(pattern.contains("test-conn-1"));
    assertTrue(pattern.contains("music"));
  }

  // --- sanitizePath ---

  @Test
  public void sanitizePath_emptyReturnsRoot() {
    assertEquals("root", generator.sanitizePath(""));
  }

  @Test
  public void sanitizePath_removesLeadingTrailingSlashes() {
    String result = generator.sanitizePath("/docs/subfolder/");
    assertFalse(result.startsWith("/"));
    assertFalse(result.endsWith("/"));
  }

  @Test
  public void sanitizePath_replacesSpecialChars() {
    String result = generator.sanitizePath("path with spaces/and (parens)");
    assertFalse(result.contains(" "));
    assertFalse(result.contains("("));
  }

  @Test
  public void sanitizePath_longPathIsHashed() {
    StringBuilder longPath = new StringBuilder();
    for (int i = 0; i < 120; i++) {
      longPath.append("a");
    }
    String result = generator.sanitizePath(longPath.toString());
    assertTrue(result.length() <= 100);
  }

  // --- sanitizeSearchQuery ---

  @Test
  public void sanitizeSearchQuery_emptyReturnsEmpty() {
    assertEquals("empty", generator.sanitizeSearchQuery(""));
  }

  @Test
  public void sanitizeSearchQuery_replacesSpecialChars() {
    String result = generator.sanitizeSearchQuery("*.mp3");
    assertFalse(result.contains("*"));
    assertFalse(result.contains("."));
  }

  // --- sanitizeFileName ---

  @Test
  public void sanitizeFileName_emptyReturnsUnnamed() {
    assertEquals("unnamed", generator.sanitizeFileName(""));
  }

  @Test
  public void sanitizeFileName_preservesDotAndAlphanumeric() {
    String result = generator.sanitizeFileName("test-file.txt");
    assertEquals("test-file.txt", result);
  }

  @Test
  public void sanitizeFileName_replacesSpaces() {
    String result = generator.sanitizeFileName("my file.txt");
    assertFalse(result.contains(" "));
  }

  // --- sanitizeParameter ---

  @Test
  public void sanitizeParameter_emptyReturnsEmpty() {
    assertEquals("empty", generator.sanitizeParameter(""));
  }

  @Test
  public void sanitizeParameter_preservesAlphanumeric() {
    assertEquals("abc123", generator.sanitizeParameter("abc123"));
  }

  // --- deterministic keys ---

  @Test
  public void sameInputs_produceSameKey() {
    String key1 = generator.generateFileListKey(connection, "docs");
    String key2 = generator.generateFileListKey(connection, "docs");
    assertEquals(key1, key2);
  }

  @Test
  public void differentPaths_produceDifferentKeys() {
    String key1 = generator.generateFileListKey(connection, "docs");
    String key2 = generator.generateFileListKey(connection, "music");
    assertNotEquals(key1, key2);
  }

  @Test
  public void differentConnections_produceDifferentKeys() {
    SmbConnection other = new SmbConnection();
    other.setId("other-conn");
    String key1 = generator.generateFileListKey(connection, "docs");
    String key2 = generator.generateFileListKey(other, "docs");
    assertNotEquals(key1, key2);
  }
}
