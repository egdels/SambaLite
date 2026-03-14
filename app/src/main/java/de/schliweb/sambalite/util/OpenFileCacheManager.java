package de.schliweb.sambalite.util;

import android.content.Context;
import androidx.annotation.NonNull;
import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Manages the open-file cache directory ({@code getCacheDir()/open_files/}).
 *
 * <p>Strategy: Lazy cleanup on app start (files older than 1 hour) combined with a size limit (100
 * MB, evicts oldest first down to 50 MB).
 */
public final class OpenFileCacheManager {

  private static final String TAG = "OpenFileCacheManager";
  private static final String CACHE_DIR_NAME = "open_files";
  private static final long MAX_AGE_MS = 60 * 60 * 1000; // 1 hour
  private static final long MAX_CACHE_SIZE_BYTES = 100 * 1024 * 1024; // 100 MB
  private static final long TARGET_CACHE_SIZE_BYTES = 50 * 1024 * 1024; // 50 MB

  private OpenFileCacheManager() {}

  /**
   * Deletes all cache files older than 1 hour. Should be called from {@code
   * SambaLiteApp.onCreate()}.
   */
  public static void cleanupOnAppStart(@NonNull Context context) {
    File cacheDir = new File(context.getCacheDir(), CACHE_DIR_NAME);
    if (!cacheDir.exists()) return;

    long now = System.currentTimeMillis();
    File[] files = cacheDir.listFiles();
    if (files == null) return;

    int deleted = 0;
    for (File file : files) {
      if (now - file.lastModified() > MAX_AGE_MS) {
        if (file.delete()) {
          deleted++;
        }
      }
    }
    if (deleted > 0) {
      LogUtils.d(TAG, "Cleaned up " + deleted + " expired cache file(s)");
    }
  }

  /**
   * Ensures the cache does not exceed 100 MB. Deletes oldest files first until under 50 MB. Should
   * be called before each new download into the cache.
   */
  public static void enforceMaxSize(@NonNull Context context) {
    File cacheDir = new File(context.getCacheDir(), CACHE_DIR_NAME);
    if (!cacheDir.exists()) return;

    File[] files = cacheDir.listFiles();
    if (files == null) return;

    long totalSize = 0;
    for (File f : files) totalSize += f.length();

    if (totalSize <= MAX_CACHE_SIZE_BYTES) return;

    // Sort by last modified, oldest first
    Arrays.sort(files, Comparator.comparingLong(File::lastModified));
    int deleted = 0;
    for (File f : files) {
      if (totalSize <= TARGET_CACHE_SIZE_BYTES) break;
      totalSize -= f.length();
      if (f.delete()) {
        deleted++;
      }
    }
    if (deleted > 0) {
      LogUtils.d(TAG, "Evicted " + deleted + " cache file(s) to enforce size limit");
    }
  }

  /** Returns the cache directory for open files, creating it if necessary. */
  @NonNull
  public static File getCacheDir(@NonNull Context context) {
    File cacheDir = new File(context.getCacheDir(), CACHE_DIR_NAME);
    if (!cacheDir.exists()) {
      cacheDir.mkdirs();
    }
    return cacheDir;
  }
}
