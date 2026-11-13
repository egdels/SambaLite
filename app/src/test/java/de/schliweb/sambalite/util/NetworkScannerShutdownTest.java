package de.schliweb.sambalite.util;

import static org.junit.Assert.*;

import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The scan thread forgets its executor when the scan ends. When that happened while {@code
 * shutdown()} ran in {@code MainActivity.onDestroy}, shutdown dereferenced the cleared field and
 * crashed with a NullPointerException (Play Console, 3.2.0).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NetworkScannerShutdownTest {

  private static Field executorField() throws Exception {
    Field f = NetworkScanner.class.getDeclaredField("currentExecutorService");
    f.setAccessible(true);
    return f;
  }

  /**
   * An executor that behaves like one whose scan ends during shutdown: every call on it clears the
   * scanner's field, as the scan thread does.
   */
  private static ExecutorService executorClearedByScanThread(
      NetworkScanner scanner, List<String> calls) {
    return (ExecutorService)
        Proxy.newProxyInstance(
            ExecutorService.class.getClassLoader(),
            new Class<?>[] {ExecutorService.class},
            (proxy, method, args) -> {
              calls.add(method.getName());
              executorField().set(scanner, null);
              return switch (method.getName()) {
                case "isShutdown", "isTerminated", "awaitTermination" -> false;
                case "shutdownNow" -> new ArrayList<Runnable>();
                default -> null;
              };
            });
  }

  @Test
  public void shutdown_survivesScanThreadClearingTheExecutor() throws Exception {
    NetworkScanner scanner = new NetworkScanner(ApplicationProvider.getApplicationContext());
    List<String> calls = new ArrayList<>();
    executorField().set(scanner, executorClearedByScanThread(scanner, calls));

    scanner.shutdown();

    assertTrue("running probes must be stopped: " + calls, calls.contains("shutdownNow"));
    assertFalse("onDestroy must not wait for the probes", calls.contains("awaitTermination"));
    assertNull(executorField().get(scanner));
  }

  @Test
  public void shutdown_withoutScanAndTwice_doesNothing() throws Exception {
    NetworkScanner scanner = new NetworkScanner(ApplicationProvider.getApplicationContext());

    scanner.shutdown();
    scanner.shutdown();

    assertNull(executorField().get(scanner));
  }

  @Test
  public void cancelAndShutdown_duringScan_doNotThrow() throws Exception {
    NetworkScanner scanner = new NetworkScanner(ApplicationProvider.getApplicationContext());
    scanner.scanLocalNetwork(
        new NetworkScanner.ScanProgressListener() {
          @Override
          public void onProgressUpdate(int scannedHosts, int totalHosts, String currentHost) {}

          @Override
          public void onServerFound(NetworkScanner.DiscoveredServer server) {}

          @Override
          public void onScanComplete(List<NetworkScanner.DiscoveredServer> servers) {}

          @Override
          public void onScanError(String error) {}
        });

    scanner.cancelScan();
    scanner.shutdown();

    assertNull(executorField().get(scanner));
  }
}
