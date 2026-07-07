package de.schliweb.sambalite.sync;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.Test;

/**
 * Unit tests for the per-config sync locks in {@link FolderSyncWorker}, which prevent two workers
 * (e.g. a manual per-config sync and the periodic sync) from syncing the same folder pair
 * concurrently.
 */
public class FolderSyncWorkerConfigLockTest {

  @Test
  public void lockForConfig_returnsSameLockForSameConfigId() {
    String configId = UUID.randomUUID().toString();
    assertSame(
        FolderSyncWorker.lockForConfig(configId), FolderSyncWorker.lockForConfig(configId));
  }

  @Test
  public void lockForConfig_returnsDifferentLocksForDifferentConfigIds() {
    assertNotSame(
        FolderSyncWorker.lockForConfig(UUID.randomUUID().toString()),
        FolderSyncWorker.lockForConfig(UUID.randomUUID().toString()));
  }

  @Test
  public void tryLock_failsWhileAnotherThreadHoldsTheLock() throws Exception {
    String configId = UUID.randomUUID().toString();
    ReentrantLock lock = FolderSyncWorker.lockForConfig(configId);

    CountDownLatch acquired = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean holderLocked = new AtomicBoolean(false);

    Thread holder =
        new Thread(
            () -> {
              lock.lock();
              try {
                holderLocked.set(true);
                acquired.countDown();
                release.await(5, TimeUnit.SECONDS);
              } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
              } finally {
                lock.unlock();
              }
            });
    holder.start();

    assertTrue(acquired.await(5, TimeUnit.SECONDS));
    assertTrue(holderLocked.get());

    // Simulates a second sync worker: it must not acquire the lock for the same config
    assertFalse(FolderSyncWorker.lockForConfig(configId).tryLock());

    release.countDown();
    holder.join(5000);

    // After the first worker finished, the lock is available again
    ReentrantLock reacquired = FolderSyncWorker.lockForConfig(configId);
    assertTrue(reacquired.tryLock());
    reacquired.unlock();
  }
}
