package de.schliweb.sambalite.cache.statistics;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link CacheStatistics}. */
public class CacheStatisticsTest {

  private CacheStatistics stats;

  @Before
  public void setUp() {
    stats = new CacheStatistics();
  }

  // ── Memory / Disk metrics ──

  @Test
  public void memoryEntries_defaultZero() {
    assertEquals(0, stats.getMemoryEntries());
  }

  @Test
  public void setMemoryEntries_updatesValue() {
    stats.setMemoryEntries(42);
    assertEquals(42, stats.getMemoryEntries());
  }

  @Test
  public void diskSizeBytes_defaultZero() {
    assertEquals(0L, stats.getDiskSizeBytes());
  }

  @Test
  public void setDiskSizeBytes_updatesValue() {
    stats.setDiskSizeBytes(1024L);
    assertEquals(1024L, stats.getDiskSizeBytes());
  }

  // ── Valid / Expired entries ──

  @Test
  public void validEntries_defaultZero() {
    assertEquals(0, stats.getValidEntries());
  }

  @Test
  public void setValidEntries_updatesValue() {
    stats.setValidEntries(10);
    assertEquals(10, stats.getValidEntries());
  }

  @Test
  public void expiredEntries_defaultZero() {
    assertEquals(0, stats.getExpiredEntries());
  }

  @Test
  public void setExpiredEntries_updatesValue() {
    stats.setExpiredEntries(5);
    assertEquals(5, stats.getExpiredEntries());
  }

  // ── Cache performance counters ──

  @Test
  public void incrementCacheRequests_increments() {
    stats.incrementCacheRequests();
    stats.incrementCacheRequests();
    assertEquals(2L, stats.getCacheRequests());
  }

  @Test
  public void incrementCacheHits_increments() {
    stats.incrementCacheHits();
    assertEquals(1L, stats.getCacheHits());
  }

  @Test
  public void incrementCacheMisses_increments() {
    stats.incrementCacheMisses();
    stats.incrementCacheMisses();
    stats.incrementCacheMisses();
    assertEquals(3L, stats.getCacheMisses());
  }

  // ── Operation counters ──

  @Test
  public void incrementPutOperations_increments() {
    stats.incrementPutOperations();
    assertEquals(1L, stats.getPutOperations());
  }

  @Test
  public void incrementGetOperations_increments() {
    stats.incrementGetOperations();
    stats.incrementGetOperations();
    assertEquals(2L, stats.getGetOperations());
  }

  @Test
  public void incrementRemoveOperations_increments() {
    stats.incrementRemoveOperations();
    assertEquals(1L, stats.getRemoveOperations());
  }

  // ── Error counters ──

  @Test
  public void incrementSerializationErrors_increments() {
    stats.incrementSerializationErrors();
    assertEquals(1, stats.getSerializationErrors());
  }

  @Test
  public void incrementDeserializationErrors_increments() {
    stats.incrementDeserializationErrors();
    stats.incrementDeserializationErrors();
    assertEquals(2, stats.getDeserializationErrors());
  }

  @Test
  public void incrementDiskWriteErrors_increments() {
    stats.incrementDiskWriteErrors();
    assertEquals(1, stats.getDiskWriteErrors());
  }

  @Test
  public void incrementDiskReadErrors_increments() {
    stats.incrementDiskReadErrors();
    assertEquals(1, stats.getDiskReadErrors());
  }

  // ── Hit rate ──

  @Test
  public void getHitRate_noRequests_returnsZero() {
    assertEquals(0.0, stats.getHitRate(), 0.001);
  }

  @Test
  public void getHitRate_allHits_returns100() {
    stats.incrementCacheRequests();
    stats.incrementCacheRequests();
    stats.incrementCacheHits();
    stats.incrementCacheHits();
    assertEquals(1.0, stats.getHitRate(), 0.001);
  }

  @Test
  public void getHitRate_halfHits_returns50() {
    stats.incrementCacheRequests();
    stats.incrementCacheRequests();
    stats.incrementCacheHits();
    assertEquals(0.5, stats.getHitRate(), 0.001);
  }

  // ── Reset ──

  @Test
  public void reset_clearsAllCounters() {
    stats.incrementCacheRequests();
    stats.incrementCacheHits();
    stats.incrementCacheMisses();
    stats.incrementPutOperations();
    stats.incrementGetOperations();
    stats.incrementRemoveOperations();
    stats.incrementSerializationErrors();
    stats.incrementDeserializationErrors();
    stats.incrementDiskWriteErrors();
    stats.incrementDiskReadErrors();
    stats.setMemoryEntries(10);
    stats.setDiskSizeBytes(1024);
    stats.setValidEntries(5);
    stats.setExpiredEntries(3);

    stats.reset();

    assertEquals(0L, stats.getCacheRequests());
    assertEquals(0L, stats.getCacheHits());
    assertEquals(0L, stats.getCacheMisses());
    assertEquals(0L, stats.getPutOperations());
    assertEquals(0L, stats.getGetOperations());
    assertEquals(0L, stats.getRemoveOperations());
    assertEquals(0, stats.getSerializationErrors());
    assertEquals(0, stats.getDeserializationErrors());
    assertEquals(0, stats.getDiskWriteErrors());
    assertEquals(0, stats.getDiskReadErrors());
    assertEquals(0, stats.getMemoryEntries());
    assertEquals(0L, stats.getDiskSizeBytes());
    assertEquals(0, stats.getValidEntries());
    assertEquals(0, stats.getExpiredEntries());
  }

  // ── Snapshot ──

  @Test
  public void createSnapshot_capturesCurrentState() {
    stats.setMemoryEntries(5);
    stats.setDiskSizeBytes(2048);
    stats.incrementCacheRequests();
    stats.incrementCacheHits();
    stats.incrementSerializationErrors();
    stats.incrementDiskReadErrors();

    CacheStatistics.CacheStatisticsSnapshot snapshot = stats.createSnapshot();

    assertEquals(1.0, snapshot.getHitRate(), 0.001);
    assertEquals(2, snapshot.getTotalErrors());
  }
}
