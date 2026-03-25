/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui.operations;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import de.schliweb.sambalite.ui.utils.ProgressFormat;
import de.schliweb.sambalite.util.LogUtils;
import java.io.*;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileOperations {

  private static final int BUFFER_SIZE = 64 * 1024;
  private static final ExecutorService COPY_EXECUTOR = Executors.newSingleThreadExecutor();

  public interface Callback {
    default void onStart() {}

    default void onFileCopied(@NonNull String name, long bytes) {}

    default void onProgress(int percent, @NonNull String status) {} // NEW

    default void onDone() {}

    default void onError(@NonNull Exception e) {}

    default boolean isCancelled() {
      return false;
    }
  }

  public static class OperationCancelledException extends IOException {
    public OperationCancelledException(@NonNull String msg) {
      super(msg);
    }
  }

  static void throwIfCancelled(Callback cb) throws OperationCancelledException {
    if (cb != null && cb.isCancelled()) throw new OperationCancelledException("Cancelled by user");
  }

  record Count(long files, long bytes) {}

  static Count countFilesAndBytes(File root) {
    long files = 0, bytes = 0;
    File[] list = root.listFiles();
    if (list == null) return new Count(0, 0);
    for (File f : list) {
      if (f.isDirectory()) {
        Count c = countFilesAndBytes(f);
        files += c.files();
        bytes += c.bytes();
      } else {
        files++;
        bytes += Math.max(0L, f.length());
      }
    }
    return new Count(files, bytes);
  }

  public static void copyFolderAsync(
      @NonNull File sourceFolder,
      @NonNull DocumentFile destFolder,
      @NonNull Context context,
      @Nullable Callback cb) {
    if (cb == null) cb = new Callback() {};
    final Callback callback = cb;
    callback.onStart();
    COPY_EXECUTOR.submit(
        () -> {
          try {
            Count total = countFilesAndBytes(sourceFolder);
            final long totalFiles = total.files();
            final long totalBytes = total.bytes() <= 0 ? 1 : total.bytes();

            final java.util.concurrent.atomic.AtomicLong bytesCopied =
                new java.util.concurrent.atomic.AtomicLong(0L);
            final java.util.concurrent.atomic.AtomicLong filesCopied =
                new java.util.concurrent.atomic.AtomicLong(0L);

            copyFolderWithProgress(
                sourceFolder,
                destFolder,
                context,
                totalFiles,
                totalBytes,
                bytesCopied,
                filesCopied,
                callback);

            if (!callback.isCancelled()) callback.onDone();
          } catch (OperationCancelledException cancelled) {
            LogUtils.i("FileOperations", "Folder copy cancelled by user");
            callback.onError(cancelled); // Controller kann das als „Cancelled“ behandeln
          } catch (Exception e) {
            LogUtils.e("FileOperations", "Async folder copy error: " + e.getMessage());
            callback.onError(e);
          }
        });
  }

  static void copyFolderWithProgress(
      File src,
      DocumentFile dst,
      Context ctx,
      long totalFiles,
      long totalBytes,
      java.util.concurrent.atomic.AtomicLong bytesCopied,
      java.util.concurrent.atomic.AtomicLong filesCopied,
      Callback cb)
      throws IOException {

    throwIfCancelled(cb);
    File[] children = src.listFiles();
    if (children == null) return;

    for (File f : children) {
      throwIfCancelled(cb);
      if (f.isDirectory()) {
        DocumentFile sub = findOrCreateDirectory(dst, f.getName());
        if (sub == null) throw new IOException("Failed to create/find directory: " + f.getName());
        copyFolderWithProgress(f, sub, ctx, totalFiles, totalBytes, bytesCopied, filesCopied, cb);
      } else {
        copySingleFileWithProgress(
            f, dst, ctx, totalFiles, totalBytes, bytesCopied, filesCopied, cb);
      }
    }
  }

  static void copySingleFileWithProgress(
      File sourceFile,
      DocumentFile destFolder,
      Context context,
      long totalFiles,
      long totalBytes,
      java.util.concurrent.atomic.AtomicLong bytesCopied,
      java.util.concurrent.atomic.AtomicLong filesCopied,
      Callback cb)
      throws IOException {

    throwIfCancelled(cb);

    DocumentFile target =
        findOrCreateFile(destFolder, sourceFile.getName(), guessMimeType(sourceFile.getName()));
    if (target == null) throw new IOException("Failed to create file: " + sourceFile.getName());

    boolean completedThisFile = false;
    try (BufferedInputStream bis =
            new BufferedInputStream(new FileInputStream(sourceFile), BUFFER_SIZE);
        OutputStream os = context.getContentResolver().openOutputStream(target.getUri());
        BufferedOutputStream bos =
            new BufferedOutputStream(
                require(os, "output stream for file: " + sourceFile.getName()), BUFFER_SIZE)) {

      byte[] buf = new byte[BUFFER_SIZE];
      int n;
      long local = 0L;

      long lastEmit = System.nanoTime();
      final long fileTotal = Math.max(1L, sourceFile.length());

      while ((n = bis.read(buf)) != -1) {
        throwIfCancelled(cb);
        bos.write(buf, 0, n);
        local += n;
        long global = bytesCopied.addAndGet(n);

        long now = System.nanoTime();
        if (now - lastEmit > 80_000_000L) {
          int overallPct = computePercent(global, totalBytes, filesCopied.get(), totalFiles);
          int filePct = pct(local, fileTotal);
          int idx = safeInt(filesCopied.get() + 1);
          int tot = safeInt(totalFiles);
          String base = ProgressFormat.formatIdx("Copying", idx, tot, sourceFile.getName());
          String status =
              base
                  + " • "
                  + filePct
                  + "% ("
                  + ProgressFormat.formatBytesOnly(local, fileTotal)
                  + ")";
          cb.onProgress(overallPct, status);
          lastEmit = now;
        }
      }
      bos.flush();
      completedThisFile = true;

    } catch (OperationCancelledException oce) {
      try {
        target.delete();
      } catch (Throwable ignored) {
      }
      throw oce;
    }

    if (completedThisFile) {
      long fDone = filesCopied.incrementAndGet();
      int overallPct = computePercent(bytesCopied.get(), totalBytes, fDone, totalFiles);
      String baseDone =
          ProgressFormat.formatIdx(
              "Copying", safeInt(fDone), safeInt(totalFiles), sourceFile.getName());
      String statusDone =
          baseDone
              + " • 100% ("
              + ProgressFormat.formatBytesOnly(sourceFile.length(), sourceFile.length())
              + ")";
      cb.onProgress(overallPct, statusDone);
      cb.onFileCopied(sourceFile.getName(), sourceFile.length());
    }
  }

  static int safeInt(long v) {
    if (v < 0) return 0;
    return (v > Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int) v;
  }

  static int computePercent(long bytesCopied, long totalBytes, long filesCopied, long totalFiles) {
    double by = (bytesCopied * 100.0) / Math.max(1L, totalBytes);
    double fi = (filesCopied * 100.0) / Math.max(1L, totalFiles);
    int pct = (int) Math.round(Double.isFinite(by) ? by : fi);
    if (pct < 0) pct = 0;
    if (pct > 100) pct = 100;
    return pct;
  }

  static int pct(long cur, long total) {
    if (total <= 0) return 0;
    if (cur >= total) return 100;
    return (int) Math.round(cur * 100.0 / total);
  }

  public static void copyFileToUri(
      @NonNull File file, @NonNull Uri uri, @NonNull Context context, @NonNull Callback cb)
      throws IOException {
    LogUtils.d(
        "FileOperations", "Copying file to URI: " + uri + ", size: " + file.length() + " bytes");
    try (OutputStream os = context.getContentResolver().openOutputStream(uri);
        BufferedOutputStream bos =
            new BufferedOutputStream(require(os, "output stream for URI: " + uri), BUFFER_SIZE);
        BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file), BUFFER_SIZE)) {

      long bytes = copyStream(bis, bos, cb);
      LogUtils.d("FileOperations", "File copied successfully (" + bytes + " bytes)");
    } catch (IOException e) {
      LogUtils.e("FileOperations", "Error copying file to URI: " + e.getMessage());
      throw e;
    }
  }

  public static void copyFileToUriAsync(
      @NonNull File sourceFile,
      @NonNull Uri destUri,
      @NonNull Context context,
      @Nullable Callback cb) {
    if (cb == null) cb = new Callback() {};
    final Callback callback = cb;
    callback.onStart();

    COPY_EXECUTOR.submit(
        () -> {
          boolean completedThisFile = false;
          try (OutputStream os = context.getContentResolver().openOutputStream(destUri);
              BufferedOutputStream bos =
                  new BufferedOutputStream(
                      require(os, "output stream for URI: " + destUri), BUFFER_SIZE);
              BufferedInputStream bis =
                  new BufferedInputStream(new FileInputStream(sourceFile), BUFFER_SIZE)) {

            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            long local = 0L;
            long lastEmit = System.nanoTime();
            final long fileTotal = Math.max(1L, sourceFile.length());

            while ((n = bis.read(buf)) != -1) {
              throwIfCancelled(callback);
              bos.write(buf, 0, n);
              local += n;

              long now = System.nanoTime();
              if (now - lastEmit > 80_000_000L) {
                int filePct = pct(local, fileTotal);
                String base = ProgressFormat.formatIdx("Copying", 1, 1, sourceFile.getName());
                String status =
                    base
                        + " • "
                        + filePct
                        + "% ("
                        + ProgressFormat.formatBytesOnly(local, fileTotal)
                        + ")";
                callback.onProgress(filePct, status);
                lastEmit = now;
              }
            }
            bos.flush();
            completedThisFile = true;

          } catch (OperationCancelledException oce) {
            try {
              androidx.documentfile.provider.DocumentFile tgt =
                  androidx.documentfile.provider.DocumentFile.fromSingleUri(context, destUri);
              if (tgt != null) tgt.delete();
            } catch (Throwable ignored) {
            }
            callback.onError(oce);
            return;
          } catch (Exception e) {
            callback.onError(e);
            return;
          }

          if (completedThisFile) {
            try {
              callback.onProgress(100, "Copying • 100%");
            } catch (Throwable ignored) {
            }
            try {
              callback.onFileCopied(sourceFile.getName(), sourceFile.length());
            } catch (Throwable ignored) {
            }
            callback.onDone();
          }
        });
  }

  public static boolean deleteRecursive(@NonNull File fileOrDirectory) {
    LogUtils.d("FileOperations", "Deleting: " + fileOrDirectory.getAbsolutePath());
    if (fileOrDirectory.isDirectory()) {
      File[] files = fileOrDirectory.listFiles();
      if (files != null) {
        for (File child : files) deleteRecursive(child);
      }
    }
    boolean deleted = fileOrDirectory.delete();
    if (!deleted)
      LogUtils.w("FileOperations", "Failed to delete: " + fileOrDirectory.getAbsolutePath());
    return deleted;
  }

  public static void copyUriToFile(
      @NonNull Uri uri, @NonNull File targetFile, @NonNull Context context) throws IOException {
    LogUtils.d("FileOperations", "Copying URI content to file: " + targetFile.getAbsolutePath());
    try (InputStream is = context.getContentResolver().openInputStream(uri);
        BufferedInputStream bis =
            new BufferedInputStream(require(is, "input stream from URI: " + uri), BUFFER_SIZE);
        BufferedOutputStream bos =
            new BufferedOutputStream(new FileOutputStream(targetFile), BUFFER_SIZE)) {
      long bytes = copyStream(bis, bos);
      LogUtils.d("FileOperations", "URI content copied successfully (" + bytes + " bytes)");
    } catch (IOException e) {
      LogUtils.e("FileOperations", "Error copying URI to file: " + e.getMessage());
      throw e;
    }
  }

  private static DocumentFile findOrCreateDirectory(DocumentFile parent, String name) {
    DocumentFile existing = parent.findFile(name);
    if (existing != null && existing.isDirectory()) return existing;
    return parent.createDirectory(name);
  }

  private static DocumentFile findOrCreateFile(DocumentFile parent, String name, String mime) {
    DocumentFile existing = parent.findFile(name);
    if (existing != null && existing.isFile()) return existing;
    return parent.createFile(mime != null ? mime : "*/*", name);
  }

  private static <T> T require(T obj, String what) throws IOException {
    if (obj == null) throw new IOException("Failed to open " + what);
    return obj;
  }

  private static long copyStream(InputStream input, OutputStream output) throws IOException {
    return copyStream(input, output, null);
  }

  private static long copyStream(InputStream input, OutputStream output, Callback cb)
      throws IOException {
    byte[] buffer = new byte[BUFFER_SIZE];
    int read;
    long total = 0;
    while ((read = input.read(buffer)) != -1) {
      if (cb != null && cb.isCancelled()) {
        throw new OperationCancelledException("Cancelled by user");
      }
      output.write(buffer, 0, read);
      total += read;
    }
    output.flush();
    LogUtils.d("FileOperations", "Stream copied: " + total + " bytes");
    return total;
  }

  public static @NonNull File createTempFileFromUri(@NonNull Context context, @NonNull Uri uri)
      throws IOException {
    String fileName = getDisplayNameFromUri(context, uri);
    File tempFile = new File(context.getCacheDir(), fileName);
    try (InputStream is = context.getContentResolver().openInputStream(uri);
        BufferedInputStream bis =
            new BufferedInputStream(require(is, "input stream from URI: " + uri), BUFFER_SIZE);
        BufferedOutputStream bos =
            new BufferedOutputStream(new FileOutputStream(tempFile), BUFFER_SIZE)) {
      copyStream(bis, bos);
    }
    return tempFile;
  }

  public static @NonNull String getDisplayNameFromUri(@NonNull Context context, @NonNull Uri uri) {
    String result = "shared_file";
    try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        if (nameIndex >= 0) result = cursor.getString(nameIndex);
      }
    } catch (Exception e) {
      LogUtils.w("FileOperations", "Could not get display name: " + e.getMessage());
    }
    return result;
  }

  private static String guessMimeType(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
    if (lower.endsWith(".png")) return "image/png";
    if (lower.endsWith(".pdf")) return "application/pdf";
    if (lower.endsWith(".txt")) return "text/plain";
    return "*/*";
  }
}
