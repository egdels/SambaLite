package de.schliweb.sambalite.sync;

import static org.junit.Assert.*;

import org.junit.Test;

/** Unit tests for {@link SyncDirection}. */
public class SyncDirectionTest {

  @Test
  public void enum_hasThreeValues() {
    assertEquals(3, SyncDirection.values().length);
  }

  @Test
  public void valueOf_returnsCorrectValues() {
    assertEquals(SyncDirection.LOCAL_TO_REMOTE, SyncDirection.valueOf("LOCAL_TO_REMOTE"));
    assertEquals(SyncDirection.REMOTE_TO_LOCAL, SyncDirection.valueOf("REMOTE_TO_LOCAL"));
    assertEquals(SyncDirection.BIDIRECTIONAL, SyncDirection.valueOf("BIDIRECTIONAL"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void valueOf_invalidName_throwsException() {
    SyncDirection.valueOf("INVALID");
  }
}
