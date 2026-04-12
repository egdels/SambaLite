package de.schliweb.sambalite.sync;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests for SyncRepository persistence and retrieval of sync configurations. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SyncRepositoryTest {

  private SyncRepository repository;

  @Before
  public void setUp() {
    Context context = ApplicationProvider.getApplicationContext();
    repository = new SyncRepository(context);
  }

  // =========================================================================
  // saveSyncConfig
  // =========================================================================

  @Test
  public void saveSyncConfig_newConfig_generatesId() {
    SyncConfig config = createConfig(null, "conn1", "/remote");
    SyncConfig saved = repository.saveSyncConfig(config);

    assertNotNull(saved.getId());
    assertFalse(saved.getId().isEmpty());
  }

  @Test
  public void saveSyncConfig_newConfig_persistsConfig() {
    SyncConfig config = createConfig(null, "conn1", "/remote");
    repository.saveSyncConfig(config);

    List<SyncConfig> all = repository.getAllSyncConfigs();
    assertEquals(1, all.size());
    assertEquals("conn1", all.get(0).getConnectionId());
  }

  @Test
  public void saveSyncConfig_existingConfig_updatesConfig() {
    SyncConfig config = createConfig(null, "conn1", "/remote");
    SyncConfig saved = repository.saveSyncConfig(config);

    saved.setRemotePath("/updated");
    repository.saveSyncConfig(saved);

    List<SyncConfig> all = repository.getAllSyncConfigs();
    assertEquals(1, all.size());
    assertEquals("/updated", all.get(0).getRemotePath());
  }

  @Test
  public void saveSyncConfig_multipleConfigs_allPersisted() {
    repository.saveSyncConfig(createConfig(null, "conn1", "/path1"));
    repository.saveSyncConfig(createConfig(null, "conn1", "/path2"));
    repository.saveSyncConfig(createConfig(null, "conn2", "/path3"));

    assertEquals(3, repository.getAllSyncConfigs().size());
  }

  // =========================================================================
  // getAllSyncConfigs
  // =========================================================================

  @Test
  public void getAllSyncConfigs_empty_returnsEmptyList() {
    List<SyncConfig> configs = repository.getAllSyncConfigs();
    assertNotNull(configs);
    assertTrue(configs.isEmpty());
  }

  @Test
  public void getAllSyncConfigs_preservesAllFields() {
    SyncConfig config = new SyncConfig();
    config.setConnectionId("conn1");
    config.setLocalFolderUri("content://folder");
    config.setRemotePath("/Photos/Backup");
    config.setLocalFolderDisplayName("Camera");
    config.setEnabled(false);
    config.setLastSyncTimestamp(1234567890L);
    config.setDirection(SyncDirection.LOCAL_TO_REMOTE);
    config.setIntervalMinutes(30);
    config.setWifiOnly(true);

    repository.saveSyncConfig(config);

    SyncConfig loaded = repository.getAllSyncConfigs().get(0);
    assertEquals("conn1", loaded.getConnectionId());
    assertEquals("content://folder", loaded.getLocalFolderUri());
    assertEquals("/Photos/Backup", loaded.getRemotePath());
    assertEquals("Camera", loaded.getLocalFolderDisplayName());
    assertFalse(loaded.isEnabled());
    assertEquals(1234567890L, loaded.getLastSyncTimestamp());
    assertEquals(SyncDirection.LOCAL_TO_REMOTE, loaded.getDirection());
    assertEquals(30, loaded.getIntervalMinutes());
    assertTrue(loaded.isWifiOnly());
  }

  // =========================================================================
  // getAllEnabledConfigs
  // =========================================================================

  @Test
  public void getAllEnabledConfigs_filtersDisabled() {
    SyncConfig enabled = createConfig(null, "conn1", "/path1");
    enabled.setEnabled(true);
    repository.saveSyncConfig(enabled);

    SyncConfig disabled = createConfig(null, "conn1", "/path2");
    disabled.setEnabled(false);
    repository.saveSyncConfig(disabled);

    List<SyncConfig> result = repository.getAllEnabledConfigs();
    assertEquals(1, result.size());
    assertEquals("/path1", result.get(0).getRemotePath());
  }

  // =========================================================================
  // getEnabledConfigsForConnection
  // =========================================================================

  @Test
  public void getEnabledConfigsForConnection_filtersCorrectly() {
    SyncConfig c1 = createConfig(null, "conn1", "/path1");
    c1.setEnabled(true);
    repository.saveSyncConfig(c1);

    SyncConfig c2 = createConfig(null, "conn2", "/path2");
    c2.setEnabled(true);
    repository.saveSyncConfig(c2);

    SyncConfig c3 = createConfig(null, "conn1", "/path3");
    c3.setEnabled(false);
    repository.saveSyncConfig(c3);

    List<SyncConfig> result = repository.getEnabledConfigsForConnection("conn1");
    assertEquals(1, result.size());
    assertEquals("/path1", result.get(0).getRemotePath());
  }

  // =========================================================================
  // deleteSyncConfig
  // =========================================================================

  @Test
  public void deleteSyncConfig_existing_returnsTrue() {
    SyncConfig saved = repository.saveSyncConfig(createConfig(null, "conn1", "/path"));
    assertTrue(repository.deleteSyncConfig(saved.getId()));
  }

  @Test
  public void deleteSyncConfig_existing_removesConfig() {
    SyncConfig saved = repository.saveSyncConfig(createConfig(null, "conn1", "/path"));
    repository.deleteSyncConfig(saved.getId());
    assertTrue(repository.getAllSyncConfigs().isEmpty());
  }

  @Test
  public void deleteSyncConfig_nonExisting_returnsFalse() {
    assertFalse(repository.deleteSyncConfig("non-existing-id"));
  }

  // =========================================================================
  // deleteConfigsForConnection
  // =========================================================================

  @Test
  public void deleteConfigsForConnection_removesMatchingConfigs() {
    repository.saveSyncConfig(createConfig(null, "conn1", "/path1"));
    repository.saveSyncConfig(createConfig(null, "conn1", "/path2"));
    repository.saveSyncConfig(createConfig(null, "conn2", "/path3"));

    int removed = repository.deleteConfigsForConnection("conn1");
    assertEquals(2, removed);
    assertEquals(1, repository.getAllSyncConfigs().size());
    assertEquals("conn2", repository.getAllSyncConfigs().get(0).getConnectionId());
  }

  @Test
  public void deleteConfigsForConnection_noMatch_returnsZero() {
    repository.saveSyncConfig(createConfig(null, "conn1", "/path"));
    assertEquals(0, repository.deleteConfigsForConnection("conn99"));
  }

  // =========================================================================
  // updateLastSyncTimestamp
  // =========================================================================

  @Test
  public void updateLastSyncTimestamp_updatesCorrectConfig() {
    SyncConfig saved = repository.saveSyncConfig(createConfig(null, "conn1", "/path"));
    repository.updateLastSyncTimestamp(saved.getId(), 9999L);

    SyncConfig loaded = repository.getAllSyncConfigs().get(0);
    assertEquals(9999L, loaded.getLastSyncTimestamp());
  }

  @Test
  public void updateLastSyncTimestamp_nonExisting_doesNotThrow() {
    repository.updateLastSyncTimestamp("non-existing", 9999L);
    // Should not throw
  }

  // =========================================================================
  // Default values
  // =========================================================================

  @Test
  public void syncConfig_defaultValues() {
    SyncConfig config = new SyncConfig();
    assertTrue(config.isEnabled());
    assertEquals(SyncDirection.BIDIRECTIONAL, config.getDirection());
    assertEquals(60, config.getIntervalMinutes());
    assertEquals(0, config.getLastSyncTimestamp());
  }

  // =========================================================================
  // Helper
  // =========================================================================

  private SyncConfig createConfig(String id, String connectionId, String remotePath) {
    SyncConfig config = new SyncConfig();
    config.setId(id);
    config.setConnectionId(connectionId);
    config.setRemotePath(remotePath);
    config.setLocalFolderUri("content://test");
    config.setLocalFolderDisplayName("Test Folder");
    return config;
  }
}
