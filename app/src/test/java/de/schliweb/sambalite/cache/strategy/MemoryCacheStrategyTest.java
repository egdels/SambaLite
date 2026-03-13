package de.schliweb.sambalite.cache.strategy;

import static org.junit.Assert.*;

import de.schliweb.sambalite.cache.entry.CacheEntry;
import de.schliweb.sambalite.cache.statistics.CacheStatistics;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link MemoryCacheStrategy}. */
public class MemoryCacheStrategyTest {

  private CacheStatistics statistics;
  private MemoryCacheStrategy<String, String> cache;

  @Before
  public void setUp() {
    statistics = new CacheStatistics();
    cache = new MemoryCacheStrategy<>(10, statistics);
  }

  private CacheEntry<String> validEntry(String data) {
    return new CacheEntry<>(data, System.currentTimeMillis() + 60_000);
  }

  private CacheEntry<String> expiredEntry(String data) {
    return new CacheEntry<>(data, System.currentTimeMillis() - 1);
  }

  // ── put / get ──

  @Test
  public void put_and_get_returnsEntry() {
    cache.put("key1", validEntry("value1"));
    CacheEntry<String> result = cache.get("key1");
    assertNotNull(result);
    assertEquals("value1", result.getData());
  }

  @Test
  public void get_missingKey_returnsNull() {
    CacheEntry<String> result = cache.get("nonexistent");
    assertNull(result);
  }

  @Test
  public void get_expiredEntry_returnsNull() {
    cache.put("expired", expiredEntry("old"));
    CacheEntry<String> result = cache.get("expired");
    assertNull(result);
  }

  @Test
  public void put_overwritesExistingKey() {
    cache.put("key", validEntry("first"));
    cache.put("key", validEntry("second"));
    assertEquals("second", cache.get("key").getData());
  }

  // ── size ──

  @Test
  public void size_emptyCache_returnsZero() {
    assertEquals(0, cache.size());
  }

  @Test
  public void size_afterPut_returnsCorrectCount() {
    cache.put("a", validEntry("1"));
    cache.put("b", validEntry("2"));
    assertEquals(2, cache.size());
  }

  // ── remove ──

  @Test
  public void remove_existingKey_returnsEntry() {
    cache.put("key", validEntry("value"));
    CacheEntry<String> removed = cache.remove("key");
    assertNotNull(removed);
    assertEquals("value", removed.getData());
    assertEquals(0, cache.size());
  }

  @Test
  public void remove_missingKey_returnsNull() {
    CacheEntry<String> removed = cache.remove("nonexistent");
    assertNull(removed);
  }

  // ── removePattern ──

  @Test
  public void removePattern_removesMatchingKeys() {
    cache.put("search_query1", validEntry("r1"));
    cache.put("search_query2", validEntry("r2"));
    cache.put("files_list", validEntry("r3"));
    int removed = cache.removePattern("search");
    assertEquals(2, removed);
    assertEquals(1, cache.size());
  }

  @Test
  public void removePattern_noMatch_removesNothing() {
    cache.put("key1", validEntry("v1"));
    int removed = cache.removePattern("zzz");
    assertEquals(0, removed);
    assertEquals(1, cache.size());
  }

  // ── clear ──

  @Test
  public void clear_removesAllEntries() {
    cache.put("a", validEntry("1"));
    cache.put("b", validEntry("2"));
    cache.clear();
    assertEquals(0, cache.size());
  }

  // ── LRU eviction ──

  @Test
  public void eviction_removesLeastRecentlyUsed() {
    MemoryCacheStrategy<String, String> smallCache = new MemoryCacheStrategy<>(3, statistics);
    smallCache.put("a", validEntry("1"));
    smallCache.put("b", validEntry("2"));
    smallCache.put("c", validEntry("3"));

    // Access "a" to make it recently used
    smallCache.get("a");

    // Adding a 4th entry should evict "b" (least recently used)
    smallCache.put("d", validEntry("4"));

    assertTrue(smallCache.size() <= 3);
    assertNotNull(smallCache.get("a")); // was accessed, should survive
    assertNotNull(smallCache.get("d")); // just added
  }

  // ── maintenance ──

  @Test
  public void performMaintenance_removesExpiredEntries() {
    cache.put("valid", validEntry("ok"));
    cache.put("expired", expiredEntry("old"));
    cache.performMaintenance();
    assertNotNull(cache.get("valid"));
    assertNull(cache.get("expired"));
  }

  // ── statistics tracking ──

  @Test
  public void statistics_putIncrementsCounter() {
    cache.put("key", validEntry("val"));
    assertEquals(1L, statistics.getPutOperations());
  }

  @Test
  public void statistics_getIncrementsCounter() {
    cache.get("key");
    assertEquals(1L, statistics.getGetOperations());
  }

  @Test
  public void statistics_hitAndMissTracked() {
    cache.put("key", validEntry("val"));
    cache.get("key"); // hit
    cache.get("missing"); // miss
    assertEquals(1L, statistics.getCacheHits());
    assertEquals(1L, statistics.getCacheMisses());
  }

  @Test
  public void statistics_memoryEntriesUpdated() {
    cache.put("a", validEntry("1"));
    cache.put("b", validEntry("2"));
    assertEquals(2, statistics.getMemoryEntries());
  }

  // ── shutdown ──

  @Test
  public void shutdown_clearsCache() {
    cache.put("key", validEntry("val"));
    cache.shutdown();
    assertEquals(0, cache.size());
  }
}
