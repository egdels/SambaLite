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
import de.schliweb.sambalite.data.repository.SmbInputStream;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.util.LogUtils;
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
  @NonNull private final Handler mainHandler;

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
              pollLast();
              offerFirst(r);
            }
            return true;
          }
        };
    this.executor = new ThreadPoolExecutor(3, 3, 0L, TimeUnit.MILLISECONDS, lifoQueue);
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

    executor.execute(
        () -> {
          try {
            // Check if the ImageView was recycled before starting the expensive download
            ImageView iv = imageViewRef.get();
            if (iv == null || !cacheKey.equals(iv.getTag())) {
              return;
            }
            Bitmap bitmap = loadOrDownloadThumbnail(conn, remotePath, cacheKey, fileSize);
            mainHandler.post(
                () -> {
                  ImageView iv2 = imageViewRef.get();
                  if (iv2 != null && cacheKey.equals(iv2.getTag())) {
                    if (bitmap != null) {
                      iv2.setImageBitmap(bitmap);
                    } else {
                      iv2.setImageResource(fallbackResId);
                    }
                  }
                });
          } catch (Exception e) {
            LogUtils.d(TAG, "Failed to load thumbnail for: " + remotePath + " - " + e.getMessage());
          } finally {
            pendingKeys.remove(cacheKey);
          }
        });
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

  @Nullable
  private Bitmap loadOrDownloadThumbnail(
      @NonNull SmbConnection conn,
      @NonNull String remotePath,
      @NonNull String cacheKey,
      long fileSize) {
    File noCoverMarker = new File(cacheDir, cacheKey + ".nocover");

    // Check negative cache marker (no thumbnail available for this file)
    if (noCoverMarker.exists()) {
      LogUtils.d(TAG, "Skipping thumbnail (cached negative result): " + remotePath);
      return null;
    }

    // Check disk cache (.thumb file with compressed thumbnail)
    File thumbFile = new File(cacheDir, cacheKey + ".thumb");
    if (thumbFile.exists() && thumbFile.length() > 0) {
      Bitmap bitmap = BitmapFactory.decodeFile(thumbFile.getAbsolutePath());
      if (bitmap != null) {
        thumbFile.setLastModified(System.currentTimeMillis());
        memoryCache.put(cacheKey, bitmap);
        return bitmap;
      }
    }

    // For image files, use stream-based decoding to avoid downloading the whole file
    if (isImageFile(remotePath)) {
      Bitmap bitmap = decodeSampledBitmapFromStream(conn, remotePath, fileSize);
      if (bitmap != null) {
        saveThumbnailToDisk(bitmap, thumbFile);
        memoryCache.put(cacheKey, bitmap);
        trimDiskCache();
        return bitmap;
      } else {
        try {
          noCoverMarker.createNewFile();
        } catch (Exception ignored) {
        }
        LogUtils.d(TAG, "No thumbnail extracted from stream: " + remotePath);
        return null;
      }
    }

    // Legacy/Fallback: Download from SMB (still needed for PDF)
    File cachedFile = new File(cacheDir, cacheKey);
    try {
      smbRepository.downloadFile(conn, remotePath, cachedFile);
      Bitmap bitmap = decodeThumbnail(remotePath, cachedFile);

      // Always clean up the downloaded original file
      cachedFile.delete();

      if (bitmap != null) {
        // Save compressed thumbnail to disk cache
        saveThumbnailToDisk(bitmap, thumbFile);
        memoryCache.put(cacheKey, bitmap);
        trimDiskCache();
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
    } catch (Exception e) {
      LogUtils.d(TAG, "Download failed for thumbnail: " + remotePath + " - " + e.getMessage());
      return null;
    }
  }

  @Nullable
  private Bitmap decodeSampledBitmapFromStream(
      @NonNull SmbConnection conn, @NonNull String remotePath, long fileSize) {
    try {
      BitmapFactory.Options options = new BitmapFactory.Options();

      // Step 1: Decode bounds only
      // We need a fresh stream for this
      try (SmbInputStream stream = new SmbInputStream(smbRepository, conn, remotePath, fileSize)) {
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(stream, null, options);
      }

      // Step 2: Calculate inSampleSize
      options.inSampleSize = calculateInSampleSize(options, THUMBNAIL_SIZE_PX, THUMBNAIL_SIZE_PX);
      options.inJustDecodeBounds = false;

      // Step 3: Decode the actual sampled bitmap
      // We need another fresh stream
      try (SmbInputStream stream = new SmbInputStream(smbRepository, conn, remotePath, fileSize)) {
        Bitmap bitmap = BitmapFactory.decodeStream(stream, null, options);
        if (bitmap == null) return null;

        // Note: For stream-based decoding, we can't easily get EXIF rotation
        // because ExifInterface requires a file path or a specialized stream.
        // On Android 7.0 (API 24)+, ExifInterface supports InputStream.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
          try (SmbInputStream exifStream =
              new SmbInputStream(smbRepository, conn, remotePath, fileSize)) {
            ExifInterface exif = new ExifInterface(exifStream);
            int orientation =
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int rotation = 0;
            switch (orientation) {
              case ExifInterface.ORIENTATION_ROTATE_90:
                rotation = 90;
                break;
              case ExifInterface.ORIENTATION_ROTATE_180:
                rotation = 180;
                break;
              case ExifInterface.ORIENTATION_ROTATE_270:
                rotation = 270;
                break;
            }
            if (rotation != 0) {
              Matrix matrix = new Matrix();
              matrix.postRotate(rotation);
              Bitmap rotated =
                  Bitmap.createBitmap(
                      bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
              if (rotated != bitmap) {
                bitmap.recycle();
              }
              return rotated;
            }
          } catch (Exception e) {
            // Ignore EXIF errors
          }
        }

        return bitmap;
      }
    } catch (Exception e) {
      LogUtils.d(TAG, "Stream decoding failed: " + e.getMessage());
      return null;
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
    } catch (Exception e) {
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
      bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos);
    } catch (Exception e) {
      LogUtils.d(
          TAG, "Failed to save thumbnail to disk: " + thumbFile.getName() + " - " + e.getMessage());
    }
  }

  private void trimDiskCache() {
    try {
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

  /** Shuts down the background executor. Call when the manager is no longer needed. */
  public void shutdown() {
    executor.shutdownNow();
  }
}
