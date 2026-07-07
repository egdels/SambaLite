/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.util.LogUtils;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Manages thumbnail loading for image files from SMB shares. Downloads image files to a local cache
 * directory, decodes them as scaled-down bitmaps, and caches them in memory using an LruCache.
 */
public class ThumbnailManager {

  private static final String TAG = "ThumbnailManager";
  private static final int THUMBNAIL_SIZE_PX = 96;
  private static final long MAX_THUMBNAIL_FILE_SIZE = 20 * 1024 * 1024; // 20 MB limit
  private static final long MAX_DISK_CACHE_SIZE = 50 * 1024 * 1024; // 50 MB disk cache limit
  private static final long DISK_TRIM_INTERVAL_MS = 60_000; // Trim disk cache at most once/minute
  private static final long NOCOVER_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000; // 7 days
  private static final int THUMBNAIL_COMPRESS_QUALITY = 85;
  private static final Set<String> IMAGE_EXTENSIONS =
      Set.of(
          "jpg", "jpeg", "png", "gif", "bmp", "webp", "heif", "heic", "avif", "wbmp", "ico", "tiff",
          "tif");
  private static final Set<String> THUMBNAIL_EXTENSIONS;

  static {
    java.util.HashSet<String> all = new java.util.HashSet<>(IMAGE_EXTENSIONS);
    all.add("pdf");
    THUMBNAIL_EXTENSIONS = Set.copyOf(all);
  }

  @NonNull private final SmbRepository smbRepository;
  @NonNull private final File cacheDir;
  @NonNull private final LruCache<String, Bitmap> memoryCache;
  @NonNull private final ExecutorService executor;
  @NonNull private final ExecutorService diskExecutor;
  @NonNull private final Handler mainHandler;

  private volatile long lastDiskTrimTime = 0;

  @NonNull
  private final Set<String> pendingKeys = Collections.newSetFromMap(new ConcurrentHashMap<>());

  private SmbConnection connection;

  /**
   * Creates a new ThumbnailManager.
   *
   * @param context The application context for cache directory access
   * @param smbRepository The SMB repository for downloading files
   */
  public ThumbnailManager(@NonNull Context context, @NonNull SmbRepository smbRepository) {
    this.smbRepository = smbRepository;
    this.cacheDir = new File(context.getCacheDir(), "thumbnails");
    if (!this.cacheDir.exists()) {
      this.cacheDir.mkdirs();
    }
    // LIFO-style queue: newest tasks (most recently visible items) are processed first.
    // Capacity limited to ~2 screens worth of items; older tasks are silently dropped.
    LinkedBlockingDeque<Runnable> lifoQueue =
        new LinkedBlockingDeque<Runnable>(30) {
          @Override
          public boolean offer(Runnable r) {
            // Insert at front so newest items are picked up first (LIFO)
            if (!offerFirst(r)) {
              // Queue full — drop oldest (last element = oldest submitted)
              Runnable dropped = pollLast();
              if (dropped instanceof ThumbnailTask) {
                ((ThumbnailTask) dropped).onDropped();
              }
              offerFirst(r);
            }
            return true;
          }
        };
    this.executor = new ThreadPoolExecutor(3, 3, 0L, TimeUnit.MILLISECONDS, lifoQueue);
    // Separate executor for fast disk-cache decodes so cached thumbnails are not
    // queued behind slow network downloads (fast path when scrolling).
    LinkedBlockingDeque<Runnable> diskLifoQueue =
        new LinkedBlockingDeque<Runnable>() {
          @Override
          public boolean offer(Runnable r) {
            // Insert at front so newest items are picked up first (LIFO)
            return offerFirst(r);
          }
        };
    this.diskExecutor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, diskLifoQueue);
    this.mainHandler = new Handler(Looper.getMainLooper());

    // Use 1/8th of available memory for thumbnail cache
    int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
    int cacheSize = maxMemory / 8;
    this.memoryCache =
        new LruCache<String, Bitmap>(cacheSize) {
          @Override
          protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount() / 1024;
          }
        };
  }

  /**
   * Sets the current SMB connection used for downloading thumbnails.
   *
   * @param connection The SMB connection
   */
  public void setConnection(@Nullable SmbConnection connection) {
    this.connection = connection;
  }

  /**
   * Checks whether the given filename is a supported image type for thumbnail generation.
   *
   * @param filename The filename to check
   * @return true if the file is a supported image type
   */
  public static boolean isImageFile(@Nullable String filename) {
    if (filename == null) {
      return false;
    }
    int dotIndex = filename.lastIndexOf('.');
    if (dotIndex < 0 || dotIndex == filename.length() - 1) {
      return false;
    }
    String extension = filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    return IMAGE_EXTENSIONS.contains(extension);
  }

  /**
   * Checks whether the given filename is a supported type for thumbnail generation. This includes
   * image files and PDF documents.
   *
   * @param filename The filename to check
   * @return true if the file is a supported type for thumbnail generation
   */
  public static boolean isThumbnailSupported(@Nullable String filename) {
    if (filename == null) {
      return false;
    }
    int dotIndex = filename.lastIndexOf('.');
    if (dotIndex < 0 || dotIndex == filename.length() - 1) {
      return false;
    }
    String extension = filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    return THUMBNAIL_EXTENSIONS.contains(extension);
  }

  private static boolean isPdfFile(@NonNull String filename) {
    return filename.toLowerCase(Locale.ROOT).endsWith(".pdf");
  }

  /**
   * Loads a thumbnail for the given remote file path into the ImageView. If the thumbnail is
   * already cached in memory, it is set immediately. Otherwise, it is loaded asynchronously from
   * the disk cache or downloaded from the SMB share.
   *
   * @param remotePath The remote SMB file path
   * @param fileSize The file size in bytes (used to skip very large files)
   * @param imageView The ImageView to load the thumbnail into
   * @param fallbackResId The fallback drawable resource ID to use if loading fails
   */
  public void loadThumbnail(
      @NonNull String remotePath, long fileSize, @NonNull ImageView imageView, int fallbackResId) {
    if (connection == null) {
      LogUtils.d(TAG, "No connection set, using fallback icon");
      imageView.setImageResource(fallbackResId);
      return;
    }

    if (fileSize > MAX_THUMBNAIL_FILE_SIZE) {
      LogUtils.d(TAG, "File too large for thumbnail: " + remotePath);
      imageView.setImageResource(fallbackResId);
      return;
    }

    String cacheKey = getCacheKey(remotePath);

    // Check memory cache first
    Bitmap cached = memoryCache.get(cacheKey);
    if (cached != null) {
      imageView.setImageBitmap(cached);
      return;
    }

    // Tag the ImageView with the current path to detect recycled views
    imageView.setTag(cacheKey);
    imageView.setImageResource(fallbackResId);

    // Skip if already queued/in-progress for this key
    if (!pendingKeys.add(cacheKey)) {
      return;
    }

    WeakReference<ImageView> imageViewRef = new WeakReference<>(imageView);
    SmbConnection conn = this.connection;

    // Fast path: check the disk cache on a dedicated executor first, so cached
    // thumbnails appear immediately and are not queued behind slow downloads.
    diskExecutor.execute(
        () -> {
          boolean handedOff = false;
          try {
            if (isViewRecycled(imageViewRef, cacheKey)) {
              return;
            }

            // Check negative cache marker (no thumbnail available for this file)
            if (new File(cacheDir, cacheKey + ".nocover").exists()) {
              LogUtils.d(TAG, "Skipping thumbnail (cached negative result): " + remotePath);
              postResult(imageViewRef, cacheKey, null, fallbackResId);
              return;
            }

            Bitmap diskBitmap = loadFromDiskCache(cacheKey);
            if (diskBitmap != null) {
              postResult(imageViewRef, cacheKey, diskBitmap, fallbackResId);
              return;
            }

            // Slow path: download via SMB on the network executor
            handedOff = true;
            executor.execute(
                new ThumbnailTask(
                    cacheKey,
                    () -> {
                      try {
                        // Check if the ImageView was recycled before the expensive download.
                        // The check is done in a helper method so no strong ImageView
                        // reference stays on this thread's stack during the long download
                        // (would otherwise leak the view and its Activity context).
                        if (isViewRecycled(imageViewRef, cacheKey)) {
                          return;
                        }
                        Bitmap bitmap = downloadThumbnail(conn, remotePath, cacheKey);
                        postResult(imageViewRef, cacheKey, bitmap, fallbackResId);
                      } catch (Exception e) {
                        LogUtils.d(
                            TAG,
                            "Failed to load thumbnail for: " + remotePath + " - " + e.getMessage());
                      } finally {
                        pendingKeys.remove(cacheKey);
                      }
                    }));
          } catch (Exception e) {
            LogUtils.d(TAG, "Failed to load thumbnail for: " + remotePath + " - " + e.getMessage());
          } finally {
            if (!handedOff) {
              pendingKeys.remove(cacheKey);
            }
          }
        });
  }

  /**
   * Checks whether the ImageView referenced by the given WeakReference has been garbage collected
   * or recycled for a different item. Kept in a separate method so the strong ImageView reference
   * only exists within this short-lived stack frame and never lingers as a Java local on a
   * background thread during long-running work (which would leak the view's Activity context).
   */
  private static boolean isViewRecycled(
      @NonNull WeakReference<ImageView> imageViewRef, @NonNull String cacheKey) {
    ImageView iv = imageViewRef.get();
    return iv == null || !cacheKey.equals(iv.getTag());
  }

  /** Posts the loaded bitmap (or the fallback icon) to the ImageView on the main thread. */
  private void postResult(
      @NonNull WeakReference<ImageView> imageViewRef,
      @NonNull String cacheKey,
      @Nullable Bitmap bitmap,
      int fallbackResId) {
    mainHandler.post(
        () -> {
          ImageView iv = imageViewRef.get();
          if (iv != null && cacheKey.equals(iv.getTag())) {
            if (bitmap != null) {
              iv.setImageBitmap(bitmap);
            } else {
              iv.setImageResource(fallbackResId);
            }
          }
        });
  }

  /**
   * Runnable wrapper for the network executor that clears the pending marker when the task is
   * dropped from the bounded LIFO queue, so the thumbnail can be requested again later.
   */
  private final class ThumbnailTask implements Runnable {
    private final String cacheKey;
    private final Runnable delegate;

    ThumbnailTask(String cacheKey, Runnable delegate) {
      this.cacheKey = cacheKey;
      this.delegate = delegate;
    }

    @Override
    public void run() {
      delegate.run();
    }

    void onDropped() {
      pendingKeys.remove(cacheKey);
    }
  }

  /**
   * Updates the thumbnail for a file from a local file copy. Useful when a file was just
   * downloaded.
   *
   * @param remotePath The remote path of the file
   * @param localFile The local copy of the file
   * @param onComplete Optional callback to run on the main thread after update
   */
  public void updateThumbnailFromLocalFile(
      @NonNull String remotePath, @NonNull File localFile, @Nullable Runnable onComplete) {
    if (!isThumbnailSupported(remotePath)) return;

    executor.execute(
        () -> {
          String cacheKey = getCacheKey(remotePath);
          try {
            Bitmap bitmap = decodeThumbnail(remotePath, localFile);
            if (bitmap != null) {
              memoryCache.put(cacheKey, bitmap);
              File thumbFile = new File(cacheDir, cacheKey + ".thumb");
              saveThumbnailToDisk(bitmap, thumbFile);

              // Remove negative cache marker if it exists
              File noCoverMarker = new File(cacheDir, cacheKey + ".nocover");
              if (noCoverMarker.exists()) {
                noCoverMarker.delete();
              }
              LogUtils.d(TAG, "Thumbnail updated from local file: " + remotePath);
            }
          } catch (Exception e) {
            LogUtils.d(TAG, "Failed to update thumbnail from local file: " + e.getMessage());
          } finally {
            if (onComplete != null) {
              mainHandler.post(onComplete);
            }
          }
        });
  }

  /**
   * Loads a thumbnail from the compressed disk cache, if present. Also refreshes the file's
   * last-modified timestamp (LRU) and populates the memory cache on a hit.
   */
  @Nullable
  private Bitmap loadFromDiskCache(@NonNull String cacheKey) {
    File thumbFile = new File(cacheDir, cacheKey + ".thumb");
    if (thumbFile.exists() && thumbFile.length() > 0) {
      Bitmap bitmap = BitmapFactory.decodeFile(thumbFile.getAbsolutePath());
      if (bitmap != null) {
        thumbFile.setLastModified(System.currentTimeMillis());
        memoryCache.put(cacheKey, bitmap);
        return bitmap;
      }
    }
    return null;
  }

  /**
   * Downloads the remote file once (single SMB file handle) and decodes the thumbnail from the
   * in-memory data. Caches the result on disk and in memory, or writes a negative cache marker.
   */
  @Nullable
  private Bitmap downloadThumbnail(
      @NonNull SmbConnection conn, @NonNull String remotePath, @NonNull String cacheKey) {
    File noCoverMarker = new File(cacheDir, cacheKey + ".nocover");
    File thumbFile = new File(cacheDir, cacheKey + ".thumb");

    // Read the file once into memory using a single SMB file handle. Bounds decoding,
    // sampled decoding and EXIF parsing all reuse this buffer (no repeated network reads).
    byte[] data;
    try {
      data = smbRepository.readFileBytes(conn, remotePath, MAX_THUMBNAIL_FILE_SIZE);
    } catch (Exception e) {
      LogUtils.d(TAG, "Download failed for thumbnail: " + remotePath + " - " + e.getMessage());
      return null;
    }

    Bitmap bitmap;
    if (isPdfFile(remotePath)) {
      bitmap = renderPdfThumbnailFromBytes(data, cacheKey);
    } else {
      bitmap = decodeSampledBitmapFromBytes(data);
    }

    if (bitmap != null) {
      saveThumbnailToDisk(bitmap, thumbFile);
      memoryCache.put(cacheKey, bitmap);
      trimDiskCacheIfNeeded();
    } else {
      // Cache negative result to avoid re-downloading
      try {
        noCoverMarker.createNewFile();
      } catch (Exception ignored) {
        // Best effort
      }
      LogUtils.d(TAG, "No thumbnail extracted, cached negative result: " + remotePath);
    }
    return bitmap;
  }

  @Nullable
  private Bitmap decodeSampledBitmapFromBytes(@NonNull byte[] data) {
    try {
      // Step 1: Decode bounds only
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inJustDecodeBounds = true;
      BitmapFactory.decodeByteArray(data, 0, data.length, options);

      // Step 2: Calculate inSampleSize
      options.inSampleSize = calculateInSampleSize(options, THUMBNAIL_SIZE_PX, THUMBNAIL_SIZE_PX);
      options.inJustDecodeBounds = false;
      // RGB_565 halves the per-bitmap memory footprint; transparency is
      // irrelevant for small thumbnails.
      options.inPreferredConfig = Bitmap.Config.RGB_565;

      // Step 3: Decode the actual sampled bitmap
      Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
      if (bitmap == null) return null;

      // Step 4: Apply EXIF rotation from the same in-memory data (no extra I/O)
      int rotation = 0;
      try {
        ExifInterface exif = new ExifInterface(new ByteArrayInputStream(data));
        int orientation =
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        rotation = orientationToRotation(orientation);
      } catch (Exception e) {
        // Ignore EXIF errors
      }
      if (rotation != 0) {
        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        Bitmap rotated =
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) {
          bitmap.recycle();
        }
        return rotated;
      }
      return bitmap;
    } catch (Exception e) {
      LogUtils.d(TAG, "Failed to decode thumbnail from bytes: " + e.getMessage());
      return null;
    }
  }

  @Nullable
  private Bitmap renderPdfThumbnailFromBytes(@NonNull byte[] data, @NonNull String cacheKey) {
    // PdfRenderer requires a seekable file descriptor, so write to a temp file first
    File tempFile = new File(cacheDir, cacheKey + ".tmp");
    try {
      try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile)) {
        fos.write(data);
      }
      return renderPdfThumbnail(tempFile);
    } catch (Exception e) {
      LogUtils.d(TAG, "Failed to write temp PDF for thumbnail: " + e.getMessage());
      return null;
    } finally {
      tempFile.delete();
    }
  }

  @Nullable
  private Bitmap decodeSampledBitmap(@NonNull String filePath) {
    try {
      // First decode bounds only
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(filePath, options);

      // Calculate inSampleSize
      options.inSampleSize = calculateInSampleSize(options, THUMBNAIL_SIZE_PX, THUMBNAIL_SIZE_PX);

      // Decode with inSampleSize
      options.inJustDecodeBounds = false;
      Bitmap bitmap = BitmapFactory.decodeFile(filePath, options);
      if (bitmap == null) return null;

      // Apply EXIF rotation
      int rotation = getExifRotation(filePath);
      if (rotation != 0) {
        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        Bitmap rotated =
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) {
          bitmap.recycle();
        }
        return rotated;
      }
      return bitmap;
    } catch (Exception e) {
      LogUtils.d(TAG, "Failed to decode bitmap: " + filePath + " - " + e.getMessage());
      return null;
    }
  }

  private int getExifRotation(@NonNull String filePath) {
    try {
      ExifInterface exif = new ExifInterface(filePath);
      int orientation =
          exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
      return orientationToRotation(orientation);
    } catch (Exception e) {
      return 0;
    }
  }

  private static int orientationToRotation(int orientation) {
    switch (orientation) {
      case ExifInterface.ORIENTATION_ROTATE_90:
        return 90;
      case ExifInterface.ORIENTATION_ROTATE_180:
        return 180;
      case ExifInterface.ORIENTATION_ROTATE_270:
        return 270;
      default:
        return 0;
    }
  }

  private int calculateInSampleSize(
      @NonNull BitmapFactory.Options options, int reqWidth, int reqHeight) {
    int height = options.outHeight;
    int width = options.outWidth;
    int inSampleSize = 1;

    if (height > reqHeight || width > reqWidth) {
      int halfHeight = height / 2;
      int halfWidth = width / 2;
      while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
        inSampleSize *= 2;
      }
    }
    return inSampleSize;
  }

  @Nullable
  private Bitmap decodeThumbnail(@NonNull String remotePath, @NonNull File file) {
    if (isPdfFile(remotePath)) {
      return renderPdfThumbnail(file);
    } else {
      return decodeSampledBitmap(file.getAbsolutePath());
    }
  }

  @Nullable
  private Bitmap renderPdfThumbnail(@NonNull File pdfFile) {
    try (ParcelFileDescriptor fd =
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer renderer = new PdfRenderer(fd)) {
      if (renderer.getPageCount() < 1) {
        return null;
      }
      try (PdfRenderer.Page page = renderer.openPage(0)) {
        int width = THUMBNAIL_SIZE_PX;
        int height =
            Math.max(1, (int) ((float) page.getHeight() / page.getWidth() * THUMBNAIL_SIZE_PX));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(android.graphics.Color.WHITE);
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        return bitmap;
      }
    } catch (Exception e) {
      LogUtils.d(
          TAG, "Failed to render PDF thumbnail: " + pdfFile.getName() + " - " + e.getMessage());
      return null;
    }
  }

  private void saveThumbnailToDisk(@NonNull Bitmap bitmap, @NonNull File thumbFile) {
    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(thumbFile)) {
      // Lossy compression is much faster and smaller than PNG for small thumbnails
      Bitmap.CompressFormat format =
          android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
              ? Bitmap.CompressFormat.WEBP_LOSSY
              : Bitmap.CompressFormat.JPEG;
      bitmap.compress(format, THUMBNAIL_COMPRESS_QUALITY, fos);
    } catch (Exception e) {
      LogUtils.d(
          TAG, "Failed to save thumbnail to disk: " + thumbFile.getName() + " - " + e.getMessage());
    }
  }

  /** Trims the disk cache, but at most once per {@link #DISK_TRIM_INTERVAL_MS}. */
  private void trimDiskCacheIfNeeded() {
    long now = System.currentTimeMillis();
    if (now - lastDiskTrimTime < DISK_TRIM_INTERVAL_MS) {
      return;
    }
    lastDiskTrimTime = now;
    trimDiskCache();
  }

  private void trimDiskCache() {
    try {
      // Clean up stale negative cache markers
      File[] markers = cacheDir.listFiles((dir, name) -> name.endsWith(".nocover"));
      if (markers != null) {
        long cutoff = System.currentTimeMillis() - NOCOVER_MAX_AGE_MS;
        for (File marker : markers) {
          if (marker.lastModified() < cutoff) {
            marker.delete();
          }
        }
      }

      File[] files = cacheDir.listFiles((dir, name) -> name.endsWith(".thumb"));
      if (files == null || files.length == 0) return;

      long totalSize = 0;
      for (File f : files) {
        totalSize += f.length();
      }

      if (totalSize <= MAX_DISK_CACHE_SIZE) return;

      // Sort by lastModified ascending (oldest first)
      java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));

      for (File f : files) {
        if (totalSize <= MAX_DISK_CACHE_SIZE) break;
        long size = f.length();
        if (f.delete()) {
          totalSize -= size;
          LogUtils.d(TAG, "Evicted from disk cache: " + f.getName());
        }
      }
    } catch (Exception e) {
      LogUtils.d(TAG, "Failed to trim disk cache: " + e.getMessage());
    }
  }

  @NonNull
  private String getCacheKey(@NonNull String remotePath) {
    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      byte[] digest = md.digest(remotePath.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : digest) {
        sb.append(String.format(Locale.ROOT, "%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      // Fallback: simple hash
      return String.valueOf(remotePath.hashCode());
    }
  }

  /** Clears the in-memory thumbnail cache. */
  public void clearMemoryCache() {
    memoryCache.evictAll();
  }

  /** Clears both memory and disk thumbnail caches. */
  public void clearAllCaches() {
    clearMemoryCache();
    if (cacheDir.exists()) {
      File[] files = cacheDir.listFiles();
      if (files != null) {
        for (File file : files) {
          file.delete();
        }
      }
    }
  }

  /** Shuts down the background executors. Call when the manager is no longer needed. */
  public void shutdown() {
    executor.shutdownNow();
    diskExecutor.shutdownNow();
  }
}
