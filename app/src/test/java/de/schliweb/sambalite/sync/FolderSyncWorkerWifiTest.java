package de.schliweb.sambalite.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
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
    Context context = ApplicationProvider.getApplicationContext();
    SyncRepository syncRepository = new SyncRepository(context);
    ConnectionRepositoryImpl connectionRepository = new ConnectionRepositoryImpl(context);

    // Setup a connection
    SmbConnection connection = new SmbConnection();
    connection.setId("conn1");
    connection.setName("Test Connection");
    connectionRepository.saveConnection(connection);

    // Setup a config with wifiOnly = true
    SyncConfig config = new SyncConfig();
    config.setId("config1");
    config.setConnectionId("conn1");
    config.setWifiOnly(true);
    config.setEnabled(true);
    config.setIntervalMinutes(0); // MANUAL SYNC
    syncRepository.saveSyncConfig(config);

    // Ensure NO WiFi
    NetworkCapabilities nc = ShadowNetworkCapabilities.newInstance();
    shadowOf(nc).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
    shadowOf(connectivityManager).setNetworkCapabilities(connectivityManager.getActiveNetwork(), nc);

    // Run worker for SPECIFIC config (Manual sync)
    androidx.work.Data inputData = new androidx.work.Data.Builder()
        .putString("sync_config_id", "config1") // KEY_SYNC_CONFIG_ID
        .build();
    
    // We need a new worker with inputData
    FolderSyncWorker manualWorker = TestWorkerBuilder.from(context, FolderSyncWorker.class, Executors.newSingleThreadExecutor())
            .setInputData(inputData)
            .build();

    ListenableWorker.Result result = manualWorker.doWork();

    // Should be success (meaning it finished without error), but skipped due to WiFi check
    assertEquals(ListenableWorker.Result.success(), result);
  }
}
