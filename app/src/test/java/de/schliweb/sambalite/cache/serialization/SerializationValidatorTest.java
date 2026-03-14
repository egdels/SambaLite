package de.schliweb.sambalite.cache.serialization;

import static org.junit.Assert.*;

import de.schliweb.sambalite.cache.exception.CacheExceptionHandler;
import de.schliweb.sambalite.cache.statistics.CacheStatistics;
import de.schliweb.sambalite.data.model.SmbFileItem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Unit tests for {@link SerializationValidator}. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28})
public class SerializationValidatorTest {

  private SerializationValidator validator;

  @Before
  public void setUp() {
    CacheStatistics statistics = new CacheStatistics();
    CacheExceptionHandler handler = new CacheExceptionHandler(statistics);
    validator = new SerializationValidator(handler);
  }

  @Test
  public void validateSerializable_string_returnsTrue() {
    assertTrue(validator.validateSerializable("hello", "key"));
  }

  @Test
  public void validateSerializable_integer_returnsTrue() {
    assertTrue(validator.validateSerializable(42, "key"));
  }

  @Test
  public void validateSerializable_nonSerializable_returnsFalse() {
    Object obj = new Object();
    assertFalse(validator.validateSerializable(obj, "key"));
  }

  @Test
  public void validateSerializable_serializableList_returnsTrue() {
    List<String> list = Arrays.asList("a", "b", "c");
    assertTrue(validator.validateSerializable(list, "key"));
  }

  @Test
  public void validateSerializable_listWithSmbFileItem_returnsTrue() {
    List<SmbFileItem> list = new ArrayList<>();
    list.add(new SmbFileItem("file.txt", "/path/file.txt", SmbFileItem.Type.FILE, 100, new Date()));
    assertTrue(validator.validateSerializable(list, "key"));
  }

  @Test
  public void validateAndSerialize_validObject_returnsBytes() {
    byte[] result = validator.validateAndSerialize("hello", "key");
    assertNotNull(result);
    assertTrue(result.length > 0);
  }

  @Test
  public void validateAndSerialize_nonSerializable_returnsNull() {
    byte[] result = validator.validateAndSerialize(new Object(), "key");
    assertNull(result);
  }

  @Test
  public void validateSerializable_emptyList_returnsTrue() {
    List<String> list = new ArrayList<>();
    assertTrue(validator.validateSerializable(list, "key"));
  }

  @Test
  public void validateSerializable_listWithNullItems_returnsTrue() {
    List<String> list = new ArrayList<>();
    list.add(null);
    list.add("valid");
    assertTrue(validator.validateSerializable(list, "key"));
  }
}
