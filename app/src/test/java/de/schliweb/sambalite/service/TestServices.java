package de.schliweb.sambalite.service;

import java.util.ArrayList;
import java.util.List;
import org.robolectric.Robolectric;

/**
 * Creates the {@link SmbBackgroundService}s of a test and ends them after it.
 *
 * <p>The service runs a watchdog on a thread of its own. A service that a test leaves behind with
 * an operation that never finishes reports "Operation timed out" every 30 seconds for the rest of
 * the test run, and Robolectric keeps the notifications of all tests in one place: the
 * notification showed up in a test of another class that ran minutes later.
 */
final class TestServices {

  private static final List<SmbBackgroundService> created = new ArrayList<>();

  private TestServices() {}

  /** Creates and starts a service that {@link #destroyAll()} ends. */
  static synchronized SmbBackgroundService setup() {
    SmbBackgroundService service = Robolectric.setupService(SmbBackgroundService.class);
    created.add(service);
    return service;
  }

  /** Ends all services created by {@link #setup()}; call it from the {@code @After} method. */
  static synchronized void destroyAll() {
    for (SmbBackgroundService service : created) {
      try {
        service.onDestroy();
      } catch (RuntimeException e) {
        // already destroyed by the test
      }
    }
    created.clear();
  }
}
