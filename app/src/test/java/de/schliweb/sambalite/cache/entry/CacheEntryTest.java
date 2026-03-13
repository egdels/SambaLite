package de.schliweb.sambalite.cache.entry;

import static org.junit.Assert.*;

import org.junit.Test;

/** Unit tests for {@link CacheEntry}. */
public class CacheEntryTest {

  @Test
  public void constructor_setsDataAndExpiration() {
    CacheEntry<String> entry = new CacheEntry<>("hello", System.currentTimeMillis() + 60_000);
    assertEquals("hello", entry.getData());
    assertEquals(3, entry.getCacheVersion());
  }

  @Test
  public void isValid_notExpired_returnsTrue() {
    CacheEntry<String> entry = new CacheEntry<>("data", System.currentTimeMillis() + 60_000);
    assertTrue(entry.isValid());
  }

  @Test
  public void isValid_expired_returnsFalse() {
    CacheEntry<String> entry = new CacheEntry<>("data", System.currentTimeMillis() - 1);
    assertFalse(entry.isValid());
  }

  @Test
  public void getTtl_returnsPositiveForValidEntry() {
    long expiration = System.currentTimeMillis() + 60_000;
    CacheEntry<String> entry = new CacheEntry<>("data", expiration);
    assertTrue(entry.getTtl() > 0);
    assertTrue(entry.getTtl() <= 60_000);
  }

  @Test
  public void getTtl_returnsZeroForExpiredEntry() {
    CacheEntry<String> entry = new CacheEntry<>("data", System.currentTimeMillis() - 1000);
    assertEquals(0, entry.getTtl());
  }

  @Test
  public void getAge_returnsNonNegative() {
    CacheEntry<String> entry = new CacheEntry<>("data", System.currentTimeMillis() + 60_000);
    assertTrue(entry.getAge() >= 0);
  }

  @Test
  public void getData_updatesLastAccessTime() {
    CacheEntry<String> entry = new CacheEntry<>("data", System.currentTimeMillis() + 60_000);
    long before = entry.getLastAccessTime();
    entry.getData();
    assertTrue(entry.getLastAccessTime() >= before);
  }

  @Test
  public void updateLastAccessTime_updatesTime() {
    CacheEntry<String> entry = new CacheEntry<>("data", System.currentTimeMillis() + 60_000);
    long before = entry.getLastAccessTime();
    entry.updateLastAccessTime();
    assertTrue(entry.getLastAccessTime() >= before);
  }

  @Test
  public void getTimeSinceLastAccess_returnsNonNegative() {
    CacheEntry<String> entry = new CacheEntry<>("data", System.currentTimeMillis() + 60_000);
    assertTrue(entry.getTimeSinceLastAccess() >= 0);
  }

  @Test
  public void creationTime_isSetOnConstruction() {
    long before = System.currentTimeMillis();
    CacheEntry<String> entry = new CacheEntry<>("data", before + 60_000);
    long after = System.currentTimeMillis();
    assertTrue(entry.getCreationTime() >= before);
    assertTrue(entry.getCreationTime() <= after);
  }

  @Test
  public void getData_returnsNullWhenStoredNull() {
    CacheEntry<String> entry = new CacheEntry<>(null, System.currentTimeMillis() + 60_000);
    assertNull(entry.getData());
  }

  @Test
  public void getData_returnsCorrectType() {
    CacheEntry<Integer> entry = new CacheEntry<>(42, System.currentTimeMillis() + 60_000);
    assertEquals(Integer.valueOf(42), entry.getData());
  }
}
