package de.schliweb.sambalite.ui.operations;

import static org.junit.Assert.*;

import org.junit.Test;

/** Unit tests for {@link FileSkippedException}. */
public class FileSkippedExceptionTest {

  @Test
  public void constructor_setsMessage() {
    FileSkippedException exception = new FileSkippedException("file was skipped");
    assertEquals("file was skipped", exception.getMessage());
  }

  @Test
  public void isException_extendsException() {
    FileSkippedException exception = new FileSkippedException("test");
    assertTrue(exception instanceof Exception);
  }
}
