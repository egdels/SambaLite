package de.schliweb.sambalite.util;

import static org.junit.Assert.*;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Unit tests for {@link SimplePerformanceMonitor}. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SimplePerformanceMonitorTest {

  @Test
  public void getMemoryInfo_returnsNonEmptyString() {
    String info = SimplePerformanceMonitor.getMemoryInfo();
    assertNotNull(info);
    assertFalse(info.isEmpty());
    assertTrue(info.contains("Memory:"));
  }

  @Test
  public void getMemoryInfo_containsUsagePercentage() {
    String info = SimplePerformanceMonitor.getMemoryInfo();
    assertTrue(info.contains("%"));
    assertTrue(info.contains("used"));
    assertTrue(info.contains("max"));
  }

  @Test
  public void getDeviceInfo_returnsNonEmptyString() {
    String info = SimplePerformanceMonitor.getDeviceInfo();
    assertNotNull(info);
    assertFalse(info.isEmpty());
    assertTrue(info.contains("Device:"));
  }

  @Test
  public void getDeviceInfo_containsAndroidInfo() {
    String info = SimplePerformanceMonitor.getDeviceInfo();
    assertTrue(info.contains("Android"));
    assertTrue(info.contains("API"));
  }

  @Test
  public void getPerformanceStats_returnsNonEmptyString() {
    String stats = SimplePerformanceMonitor.getPerformanceStats();
    assertNotNull(stats);
    assertFalse(stats.isEmpty());
    assertTrue(stats.contains("Performance:"));
    assertTrue(stats.contains("operations"));
  }

  @Test
  public void startAndEndOperation_uiOperation_completesWithoutError() {
    SimplePerformanceMonitor.startOperation("testActivity_onCreate");
    SimplePerformanceMonitor.endOperation("testActivity_onCreate");
    // No exception means success
  }

  @Test
  public void startAndEndOperation_cacheOperation_completesWithoutError() {
    SimplePerformanceMonitor.startOperation("cacheLoad");
    SimplePerformanceMonitor.endOperation("cacheLoad");
    // No exception means success
  }

  @Test
  public void endOperation_withoutStart_doesNotThrow() {
    // Should handle gracefully when no start was recorded
    SimplePerformanceMonitor.endOperation("nonExistentOperation");
  }

  @Test
  public void startOperation_multipleOperations_doesNotThrow() {
    SimplePerformanceMonitor.startOperation("op1_Activity");
    SimplePerformanceMonitor.startOperation("op2_cache");
    SimplePerformanceMonitor.endOperation("op2_cache");
    SimplePerformanceMonitor.endOperation("op1_Activity");
  }
}
