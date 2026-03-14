package de.schliweb.sambalite.cache.exception;

import static org.junit.Assert.*;

import de.schliweb.sambalite.cache.statistics.CacheStatistics;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.NotSerializableException;
import java.io.StreamCorruptedException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Unit tests for {@link CacheExceptionHandler}. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28})
public class CacheExceptionHandlerTest {

  private CacheStatistics statistics;
  private CacheExceptionHandler handler;

  @Before
  public void setUp() {
    statistics = new CacheStatistics();
    handler = new CacheExceptionHandler(statistics);
  }

  // =========================================================================
  // handleException — error classification
  // =========================================================================

  @Test
  public void handleException_notSerializable_incrementsSerializationErrors() {
    handler.handleException(new NotSerializableException("test"), "write error");
    assertEquals(1, statistics.getSerializationErrors());
  }

  @Test
  public void handleException_invalidClass_incrementsSerializationErrors() {
    handler.handleException(new InvalidClassException("test"), "write error");
    assertEquals(1, statistics.getSerializationErrors());
  }

  @Test
  public void handleException_streamCorrupted_incrementsDeserializationErrors() {
    handler.handleException(new StreamCorruptedException("test"), "read error");
    assertEquals(1, statistics.getDeserializationErrors());
  }

  @Test
  public void handleException_classCast_incrementsDeserializationErrors() {
    handler.handleException(new ClassCastException("test"), "read error");
    assertEquals(1, statistics.getDeserializationErrors());
  }

  @Test
  public void handleException_ioRead_incrementsDiskReadErrors() {
    handler.handleException(new IOException("test"), "read data failed");
    assertEquals(1, statistics.getDiskReadErrors());
  }

  @Test
  public void handleException_ioWrite_incrementsDiskWriteErrors() {
    handler.handleException(new IOException("test"), "write data failed");
    assertEquals(1, statistics.getDiskWriteErrors());
  }

  @Test
  public void handleException_ioLoad_incrementsDiskReadErrors() {
    handler.handleException(new IOException("test"), "load cache failed");
    assertEquals(1, statistics.getDiskReadErrors());
  }

  @Test
  public void handleException_ioSave_incrementsDiskWriteErrors() {
    handler.handleException(new IOException("test"), "save cache failed");
    assertEquals(1, statistics.getDiskWriteErrors());
  }

  @Test
  public void handleException_genericException_noStatIncrement() {
    handler.handleException(new RuntimeException("test"), "generic error");
    assertEquals(0, statistics.getSerializationErrors());
    assertEquals(0, statistics.getDeserializationErrors());
    assertEquals(0, statistics.getDiskReadErrors());
    assertEquals(0, statistics.getDiskWriteErrors());
  }

  // =========================================================================
  // executeCacheOperation
  // =========================================================================

  @Test
  public void executeCacheOperation_success_returnsResult() {
    String result = handler.executeCacheOperation("error", () -> "hello");
    assertEquals("hello", result);
  }

  @Test
  public void executeCacheOperation_failure_returnsNull() {
    String result =
        handler.executeCacheOperation(
            "error",
            () -> {
              throw new RuntimeException("fail");
            });
    assertNull(result);
  }

  @Test
  public void executeCacheOperation_withFallback_success() {
    String result = handler.executeCacheOperation("default", "error", () -> "value");
    assertEquals("value", result);
  }

  @Test
  public void executeCacheOperation_withFallback_failure_returnsFallback() {
    String result =
        handler.executeCacheOperation(
            "default",
            "error",
            () -> {
              throw new RuntimeException("fail");
            });
    assertEquals("default", result);
  }

  // =========================================================================
  // executeVoidCacheOperation
  // =========================================================================

  @Test
  public void executeVoidCacheOperation_success_runs() {
    final boolean[] ran = {false};
    handler.executeVoidCacheOperation("error", () -> ran[0] = true);
    assertTrue(ran[0]);
  }

  @Test
  public void executeVoidCacheOperation_failure_doesNotThrow() {
    handler.executeVoidCacheOperation(
        "error",
        () -> {
          throw new RuntimeException("fail");
        });
    // no exception propagated
  }

  // =========================================================================
  // validateSerializable
  // =========================================================================

  @Test
  public void validateSerializable_serializableObject_returnsTrue() {
    assertTrue(handler.validateSerializable("hello", "test"));
  }

  @Test
  public void validateSerializable_nonSerializable_returnsFalse() {
    Object nonSerializable = new Object();
    assertFalse(handler.validateSerializable(nonSerializable, "test"));
    assertEquals(1, statistics.getSerializationErrors());
  }

  // =========================================================================
  // validateCast
  // =========================================================================

  @Test
  public void validateCast_validCast_returnsObject() {
    Object obj = "hello";
    String result = handler.validateCast(obj, String.class, "cast error");
    assertEquals("hello", result);
  }

  @Test
  public void validateCast_invalidCast_returnsNull() {
    Object obj = Integer.valueOf(42);
    String result = handler.validateCast(obj, String.class, "cast error");
    assertNull(result);
    assertEquals(1, statistics.getDeserializationErrors());
  }
}
