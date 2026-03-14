package de.schliweb.sambalite.util;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Unit tests for {@link LogUtils}. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LogUtilsTest {

  @Before
  public void setUp() {
    LogUtils.init(true);
  }

  @Test
  public void init_debug_doesNotThrow() {
    LogUtils.init(true);
  }

  @Test
  public void init_release_doesNotThrow() {
    LogUtils.init(false);
  }

  @Test
  public void v_withMessage_doesNotThrow() {
    LogUtils.v("test verbose message");
  }

  @Test
  public void v_withTagAndMessage_doesNotThrow() {
    LogUtils.v("TestTag", "test verbose message");
  }

  @Test
  public void d_withMessage_doesNotThrow() {
    LogUtils.d("test debug message");
  }

  @Test
  public void d_withTagAndMessage_doesNotThrow() {
    LogUtils.d("TestTag", "test debug message");
  }

  @Test
  public void i_withMessage_doesNotThrow() {
    LogUtils.i("test info message");
  }

  @Test
  public void i_withTagAndMessage_doesNotThrow() {
    LogUtils.i("TestTag", "test info message");
  }

  @Test
  public void w_withMessage_doesNotThrow() {
    LogUtils.w("test warning message");
  }

  @Test
  public void w_withTagAndMessage_doesNotThrow() {
    LogUtils.w("TestTag", "test warning message");
  }

  @Test
  public void w_withThrowableAndMessage_doesNotThrow() {
    LogUtils.w(new RuntimeException("test"), "test warning with throwable");
  }

  @Test
  public void e_withMessage_doesNotThrow() {
    LogUtils.e("test error message");
  }

  @Test
  public void e_withTagAndMessage_doesNotThrow() {
    LogUtils.e("TestTag", "test error message");
  }

  @Test
  public void e_withThrowableAndMessage_doesNotThrow() {
    LogUtils.e(new RuntimeException("test"), "test error with throwable");
  }

  @Test
  public void e_withTagMessageAndException_doesNotThrow() {
    LogUtils.e("TestTag", "error message", new RuntimeException("test"));
  }

  @Test
  public void constructor_isPrivate() throws Exception {
    java.lang.reflect.Constructor<LogUtils> constructor = LogUtils.class.getDeclaredConstructor();
    assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
    constructor.setAccessible(true);
    assertNotNull(constructor.newInstance());
  }
}
