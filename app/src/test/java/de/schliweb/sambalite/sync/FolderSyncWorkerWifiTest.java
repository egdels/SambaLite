package de.schliweb.sambalite.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Environment;
import androidx.work.ListenableWorker;
import androidx.test.core.app.ApplicationProvider;
import androidx.work.testing.TestWorkerBuilder;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.repository.ConnectionRepositoryImpl;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import org.robolectric.shadows.ShadowStatFs;

/**
 * Tests for the WiFi connection check in FolderSyncWorker.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FolderSyncWorkerWifiTest {

  private FolderSyncWorker worker;
  private ConnectivityManager connectivityManager;

  @Before
  public void setUp() {
    Context context = ApplicationProvider.getApplicationContext();
    // Clear existing data from SharedPreferences to ensure clean state
    context.getSharedPreferences("sambalite_sync_configs", Context.MODE_PRIVATE).edit().clear().commit();
    context.getSharedPreferences("sambalite_connections", Context.MODE_PRIVATE).edit().clear().commit();
    context.getSharedPreferences("sambalite_sync_state", Context.MODE_PRIVATE).edit().clear().commit();

    worker =
        TestWorkerBuilder.from(context, FolderSyncWorker.class, Executors.newSingleThreadExecutor())
            .build();
    connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
  }

  @Test
  public void isConnectedToWifi_noActiveNetwork_returnsFalse() {
    shadowOf(connectivityManager).setActiveNetworkInfo(null);
    assertFalse(worker.isConnectedToWifi());
  }

  @Test
  public void isConnectedToWifi_wifiConnected_returnsTrue() {
    NetworkCapabilities nc = ShadowNetworkCapabilities.newInstance();
    shadowOf(nc).addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
    shadowOf(connectivityManager).setNetworkCapabilities(connectivityManager.getActiveNetwork(), nc);

    assertTrue(worker.isConnectedToWifi());
  }

  @Test
  public void isConnectedToWifi_cellularConnected_returnsFalse() {
    NetworkCapabilities nc = ShadowNetworkCapabilities.newInstance();
    shadowOf(nc).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
    shadowOf(connectivityManager).setNetworkCapabilities(connectivityManager.getActiveNetwork(), nc);

    assertFalse(worker.isConnectedToWifi());
  }

  @Test
  public void doWork_wifiOnly_noWifi_skipsSync() {
    saveConnectionAndConfig(true);
    useCellular();

    // Skipped configs count as done; an attempted sync would fail (no local folder) and retry
    assertEquals(ListenableWorker.Result.success(), runManualSync());
  }

  @Test
  public void doWork_withoutWifiOnly_noWifi_attemptsSync() {
    saveConnectionAndConfig(false);
    useCellular();

    // Control for the test above: the same config without wifiOnly is attempted and fails
    assertEquals(ListenableWorker.Result.retry(), runManualSync());
  }

  private void saveConnectionAndConfig(boolean wifiOnly) {
    Context context = ApplicationProvider.getApplicationContext();
    // Enough free space, so an attempted sync gets past the disk check
    int blocks = (int) (512L * 1024 * 1024 / 4096);
    ShadowStatFs.registerStats(context.getFilesDir().getPath(), blocks + 10, blocks, blocks);
    ShadowStatFs.registerStats(
        Environment.getExternalStorageDirectory().getPath(), blocks + 10, blocks, blocks);
    ConnectionRepositoryImpl connectionRepository = new ConnectionRepositoryImpl(context);
    SmbConnection connection = new SmbConnection();
    connection.setId("conn1");
    connection.setName("Test Connection");
    // All fields must be set, otherwise the stored connection cannot be read back
    connection.setServer("127.0.0.1");
    connection.setShare("share");
    connection.setUsername("user");
    connection.setPassword("secret");
    connection.setDomain("");
    connectionRepository.saveConnection(connection);
    assertEquals(1, connectionRepository.getAllConnections().size());

    SyncConfig config = new SyncConfig();
    config.setId("config1");
    config.setConnectionId("conn1");
    config.setWifiOnly(wifiOnly);
    config.setEnabled(true);
    config.setIntervalMinutes(0); // MANUAL SYNC
    config.setLocalFolderUri("content://com.android.externalstorage.documents/tree/primary%3ASync");
    config.setRemotePath("/sync");
    new SyncRepository(context).saveSyncConfig(config);
  }

  private void useCellular() {
    NetworkCapabilities nc = ShadowNetworkCapabilities.newInstance();
    shadowOf(nc).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
    shadowOf(connectivityManager).setNetworkCapabilities(connectivityManager.getActiveNetwork(), nc);
  }

  private ListenableWorker.Result runManualSync() {
    androidx.work.Data inputData =
        new androidx.work.Data.Builder()
            .putString(FolderSyncWorker.KEY_SYNC_CONFIG_ID, "config1")
            .build();
    FolderSyncWorker manualWorker =
        TestWorkerBuilder.from(
                ApplicationProvider.getApplicationContext(),
                FolderSyncWorker.class,
                Executors.newSingleThreadExecutor())
            .setInputData(inputData)
            .build();
    return manualWorker.doWork();
  }
}
