package de.schliweb.sambalite.sync;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link SyncConfig}. */
public class SyncConfigTest {

  private SyncConfig config;

  @Before
  public void setUp() {
    config = new SyncConfig();
  }

  @Test
  public void defaultConstructor_hasCorrectDefaults() {
    assertNull(config.getId());
    assertNull(config.getConnectionId());
    assertNull(config.getLocalFolderUri());
    assertNull(config.getRemotePath());
    assertNull(config.getLocalFolderDisplayName());
    assertTrue(config.isEnabled());
    assertEquals(0, config.getLastSyncTimestamp());
    assertEquals(SyncDirection.BIDIRECTIONAL, config.getDirection());
    assertEquals(60, config.getIntervalMinutes());
  }

  @Test
  public void settersAndGetters_workCorrectly() {
    config.setId("sync1");
    config.setConnectionId("conn1");
    config.setLocalFolderUri("content://folder");
    config.setRemotePath("/remote/path");
    config.setLocalFolderDisplayName("My Folder");
    config.setEnabled(false);
    config.setLastSyncTimestamp(123456789L);
    config.setDirection(SyncDirection.LOCAL_TO_REMOTE);
    config.setIntervalMinutes(30);

    assertEquals("sync1", config.getId());
    assertEquals("conn1", config.getConnectionId());
    assertEquals("content://folder", config.getLocalFolderUri());
    assertEquals("/remote/path", config.getRemotePath());
    assertEquals("My Folder", config.getLocalFolderDisplayName());
    assertFalse(config.isEnabled());
    assertEquals(123456789L, config.getLastSyncTimestamp());
    assertEquals(SyncDirection.LOCAL_TO_REMOTE, config.getDirection());
    assertEquals(30, config.getIntervalMinutes());
  }

  @Test
  public void toString_containsFieldValues() {
    config.setId("s1");
    config.setConnectionId("c1");
    config.setRemotePath("/docs");

    String result = config.toString();
    assertTrue(result.contains("s1"));
    assertTrue(result.contains("c1"));
    assertTrue(result.contains("/docs"));
    assertTrue(result.contains("BIDIRECTIONAL"));
  }

  @Test
  public void serializable_implementsInterface() {
    assertTrue(config instanceof java.io.Serializable);
  }
}
