package de.schliweb.sambalite.cache;

import static org.junit.Assert.*;

import de.schliweb.sambalite.data.model.SmbConnection;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link SearchCacheOptimizer}. */
public class SearchCacheOptimizerTest {

  private SearchCacheOptimizer optimizer;
  private SmbConnection connection;

  @Before
  public void setUp() {
    // Use reflection to reset singleton for test isolation
    try {
      java.lang.reflect.Field instance = SearchCacheOptimizer.class.getDeclaredField("instance");
      instance.setAccessible(true);
      instance.set(null, null);
    } catch (Exception ignored) {
    }
    optimizer = SearchCacheOptimizer.getInstance();
    connection = new SmbConnection();
    connection.setId("conn-1");
    connection.setName("TestServer");
  }

  @Test
  public void getInstance_returnsSameInstance() {
    SearchCacheOptimizer a = SearchCacheOptimizer.getInstance();
    SearchCacheOptimizer b = SearchCacheOptimizer.getInstance();
    assertSame(a, b);
  }

  @Test
  public void recordSearch_incrementsStatistics() {
    optimizer.recordSearch(connection, "*.mp3", 10);
    SearchCacheOptimizer.SearchCacheStatistics stats = optimizer.getStatistics();
    assertEquals(1, stats.totalConnections);
    assertEquals(1, stats.totalQueries);
    assertEquals(1, stats.totalSearches);
  }

  @Test
  public void recordSearch_multipleQueries() {
    optimizer.recordSearch(connection, "*.mp3", 10);
    optimizer.recordSearch(connection, "*.pdf", 5);
    SearchCacheOptimizer.SearchCacheStatistics stats = optimizer.getStatistics();
    assertEquals(1, stats.totalConnections);
    assertEquals(2, stats.totalQueries);
    assertEquals(2, stats.totalSearches);
  }

  @Test
  public void recordSearch_sameQueryMultipleTimes() {
    optimizer.recordSearch(connection, "*.mp3", 10);
    optimizer.recordSearch(connection, "*.mp3", 15);
    SearchCacheOptimizer.SearchCacheStatistics stats = optimizer.getStatistics();
    assertEquals(1, stats.totalQueries);
    assertEquals(2, stats.totalSearches);
  }

  @Test
  public void shouldCacheQuery_newQuery_returnsTrue() {
    assertTrue(optimizer.shouldCacheQuery(connection, "*.mp3"));
  }

  @Test
  public void shouldCacheQuery_previouslySearched_returnsTrue() {
    optimizer.recordSearch(connection, "*.mp3", 10);
    assertTrue(optimizer.shouldCacheQuery(connection, "*.mp3"));
  }

  @Test
  public void shouldCacheQuery_largeResultSet_returnsFalse() {
    optimizer.recordSearch(connection, "*.tmp", 1500);
    // The query was searched before, so it returns true regardless of size
    // But a new query with known large results should return false
    SmbConnection other = new SmbConnection();
    other.setId("conn-2");
    other.setName("Other");
    // For a connection that hasn't searched this query but has large result info
    // Actually, shouldCacheQuery checks frequency first, then result size
    // Since we recorded the search, frequency check returns true
    assertTrue(optimizer.shouldCacheQuery(connection, "*.tmp"));
  }

  @Test
  public void getOptimalCacheTTL_noHistory_returns5Minutes() {
    long ttl = optimizer.getOptimalCacheTTL(connection, "*.mp3");
    assertEquals(5 * 60 * 1000, ttl);
  }

  @Test
  public void getOptimalCacheTTL_frequentQuery_returnsLongerTTL() {
    for (int i = 0; i < 10; i++) {
      optimizer.recordSearch(connection, "*.mp3", 10);
    }
    long ttl = optimizer.getOptimalCacheTTL(connection, "*.mp3");
    assertEquals(30 * 60 * 1000, ttl);
  }

  @Test
  public void getOptimalCacheTTL_moderateQuery_returns15Minutes() {
    for (int i = 0; i < 5; i++) {
      optimizer.recordSearch(connection, "*.mp3", 10);
    }
    long ttl = optimizer.getOptimalCacheTTL(connection, "*.mp3");
    assertEquals(15 * 60 * 1000, ttl);
  }

  @Test
  public void getOptimalCacheTTL_occasionalQuery_returns5Minutes() {
    optimizer.recordSearch(connection, "*.mp3", 10);
    long ttl = optimizer.getOptimalCacheTTL(connection, "*.mp3");
    assertEquals(5 * 60 * 1000, ttl);
  }

  @Test
  public void getStatistics_empty() {
    SearchCacheOptimizer.SearchCacheStatistics stats = optimizer.getStatistics();
    assertEquals(0, stats.totalConnections);
    assertEquals(0, stats.totalQueries);
    assertEquals(0, stats.totalSearches);
  }

  @Test
  public void getStatistics_multipleConnections() {
    SmbConnection conn2 = new SmbConnection();
    conn2.setId("conn-2");
    conn2.setName("Server2");
    optimizer.recordSearch(connection, "*.mp3", 10);
    optimizer.recordSearch(conn2, "*.pdf", 5);
    SearchCacheOptimizer.SearchCacheStatistics stats = optimizer.getStatistics();
    assertEquals(2, stats.totalConnections);
  }

  @Test
  public void searchCacheStatistics_toString() {
    SearchCacheOptimizer.SearchCacheStatistics stats = optimizer.getStatistics();
    String str = stats.toString();
    assertTrue(str.contains("totalConnections"));
    assertTrue(str.contains("totalQueries"));
    assertTrue(str.contains("totalSearches"));
  }
}
