package de.schliweb.sambalite.sync;

import android.content.Context;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.work.Configuration;
import androidx.work.WorkManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for SyncManager coordination logic.
 * Uses a real SyncRepository with Robolectric SharedPreferences.
 * WorkManager calls are tested indirectly (no exceptions thrown).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SyncManagerTest {

    private SyncRepository syncRepository;
    private SyncManager syncManager;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();

        // Initialize WorkManager for testing
        try {
            Configuration config = new Configuration.Builder()
                    .setMinimumLoggingLevel(android.util.Log.DEBUG)
                    .build();
            WorkManager.initialize(context, config);
        } catch (IllegalStateException e) {
            // Already initialized
        }

        syncRepository = new SyncRepository(context);
        syncManager = new SyncManager(context, syncRepository);
    }

    @Test
    public void addSyncConfig_createsConfigWithCorrectValues() {
        Uri uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APhotos");

        SyncConfig result = syncManager.addSyncConfig(
                "conn-1", uri, "/Photos/Backup", "Photos",
                SyncDirection.LOCAL_TO_REMOTE, 30);

        assertNotNull(result);
        assertNotNull(result.getId());
        assertEquals("conn-1", result.getConnectionId());
        assertEquals(uri.toString(), result.getLocalFolderUri());
        assertEquals("/Photos/Backup", result.getRemotePath());
        assertEquals("Photos", result.getLocalFolderDisplayName());
        assertEquals(SyncDirection.LOCAL_TO_REMOTE, result.getDirection());
        assertEquals(30, result.getIntervalMinutes());
        assertTrue(result.isEnabled());
    }

    @Test
    public void addSyncConfig_enforcesMinimumInterval() {
        Uri uri = Uri.parse("content://test/tree/folder");

        SyncConfig result = syncManager.addSyncConfig(
                "conn-1", uri, "/path", "Folder",
                SyncDirection.BIDIRECTIONAL, 5);

        assertEquals(15, result.getIntervalMinutes());
    }

    @Test
    public void addSyncConfig_persistsConfig() {
        Uri uri = Uri.parse("content://test/tree/folder");

        syncManager.addSyncConfig("conn-1", uri, "/path", "Folder",
                SyncDirection.BIDIRECTIONAL, 60);

        List<SyncConfig> all = syncManager.getAllSyncConfigs();
        assertEquals(1, all.size());
        assertEquals("conn-1", all.get(0).getConnectionId());
    }

    @Test
    public void removeSyncConfig_removesExistingConfig() {
        Uri uri = Uri.parse("content://test/tree/folder");
        SyncConfig config = syncManager.addSyncConfig("conn-1", uri, "/path", "Folder",
                SyncDirection.BIDIRECTIONAL, 60);

        boolean removed = syncManager.removeSyncConfig(config.getId());

        assertTrue(removed);
        assertTrue(syncManager.getAllSyncConfigs().isEmpty());
    }

    @Test
    public void removeSyncConfig_returnsFalseForNonExistent() {
        boolean removed = syncManager.removeSyncConfig("non-existent-id");
        assertFalse(removed);
    }

    @Test
    public void setConfigEnabled_disablesConfig() {
        Uri uri = Uri.parse("content://test/tree/folder");
        SyncConfig config = syncManager.addSyncConfig("conn-1", uri, "/path", "Folder",
                SyncDirection.BIDIRECTIONAL, 60);

        syncManager.setConfigEnabled(config.getId(), false);

        List<SyncConfig> all = syncManager.getAllSyncConfigs();
        assertEquals(1, all.size());
        assertFalse(all.get(0).isEnabled());
    }

    @Test
    public void setConfigEnabled_enablesConfig() {
        Uri uri = Uri.parse("content://test/tree/folder");
        SyncConfig config = syncManager.addSyncConfig("conn-1", uri, "/path", "Folder",
                SyncDirection.BIDIRECTIONAL, 60);
        syncManager.setConfigEnabled(config.getId(), false);

        syncManager.setConfigEnabled(config.getId(), true);

        List<SyncConfig> all = syncManager.getAllSyncConfigs();
        assertTrue(all.get(0).isEnabled());
    }

    @Test
    public void getAllSyncConfigs_returnsEmptyListInitially() {
        List<SyncConfig> configs = syncManager.getAllSyncConfigs();
        assertNotNull(configs);
        assertTrue(configs.isEmpty());
    }

    @Test
    public void getAllSyncConfigs_returnsMultipleConfigs() {
        Uri uri1 = Uri.parse("content://test/tree/folder1");
        Uri uri2 = Uri.parse("content://test/tree/folder2");

        syncManager.addSyncConfig("conn-1", uri1, "/path1", "Folder1",
                SyncDirection.LOCAL_TO_REMOTE, 30);
        syncManager.addSyncConfig("conn-1", uri2, "/path2", "Folder2",
                SyncDirection.REMOTE_TO_LOCAL, 60);

        List<SyncConfig> configs = syncManager.getAllSyncConfigs();
        assertEquals(2, configs.size());
    }

    @Test
    public void removeConfigsForConnection_removesAllConfigsForConnection() {
        Uri uri1 = Uri.parse("content://test/tree/folder1");
        Uri uri2 = Uri.parse("content://test/tree/folder2");
        Uri uri3 = Uri.parse("content://test/tree/folder3");

        syncManager.addSyncConfig("conn-1", uri1, "/path1", "Folder1",
                SyncDirection.LOCAL_TO_REMOTE, 30);
        syncManager.addSyncConfig("conn-1", uri2, "/path2", "Folder2",
                SyncDirection.REMOTE_TO_LOCAL, 60);
        syncManager.addSyncConfig("conn-2", uri3, "/path3", "Folder3",
                SyncDirection.BIDIRECTIONAL, 15);

        int removed = syncManager.removeConfigsForConnection("conn-1");

        assertEquals(2, removed);
        List<SyncConfig> remaining = syncManager.getAllSyncConfigs();
        assertEquals(1, remaining.size());
        assertEquals("conn-2", remaining.get(0).getConnectionId());
    }

    @Test
    public void removeConfigsForConnection_returnsZeroForUnknownConnection() {
        int removed = syncManager.removeConfigsForConnection("unknown");
        assertEquals(0, removed);
    }

    @Test
    public void addSyncConfig_multipleConfigsDifferentConnections() {
        Uri uri1 = Uri.parse("content://test/tree/folder1");
        Uri uri2 = Uri.parse("content://test/tree/folder2");

        syncManager.addSyncConfig("conn-1", uri1, "/path1", "Folder1",
                SyncDirection.LOCAL_TO_REMOTE, 30);
        syncManager.addSyncConfig("conn-2", uri2, "/path2", "Folder2",
                SyncDirection.REMOTE_TO_LOCAL, 60);

        assertEquals(2, syncManager.getAllSyncConfigs().size());
        assertEquals(1, syncManager.removeConfigsForConnection("conn-1"));
        assertEquals(1, syncManager.getAllSyncConfigs().size());
    }

    @Test
    public void addSyncConfig_defaultEnabledTrue() {
        Uri uri = Uri.parse("content://test/tree/folder");
        SyncConfig config = syncManager.addSyncConfig("conn-1", uri, "/path", "Folder",
                SyncDirection.BIDIRECTIONAL, 60);

        assertTrue(config.isEnabled());
    }

    @Test
    public void setConfigEnabled_toggleMultipleTimes() {
        Uri uri = Uri.parse("content://test/tree/folder");
        SyncConfig config = syncManager.addSyncConfig("conn-1", uri, "/path", "Folder",
                SyncDirection.BIDIRECTIONAL, 60);

        syncManager.setConfigEnabled(config.getId(), false);
        assertFalse(syncManager.getAllSyncConfigs().get(0).isEnabled());

        syncManager.setConfigEnabled(config.getId(), true);
        assertTrue(syncManager.getAllSyncConfigs().get(0).isEnabled());

        syncManager.setConfigEnabled(config.getId(), false);
        assertFalse(syncManager.getAllSyncConfigs().get(0).isEnabled());
    }
}
