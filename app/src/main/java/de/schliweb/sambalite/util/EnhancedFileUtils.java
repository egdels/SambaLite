/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.util;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;
import android.provider.DocumentsContract;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.File;
import java.util.Locale;

/**
 * Enhanced file operation utilities for SambaLite. Provides better file handling, validation, and
 * progress tracking.
 */
public class EnhancedFileUtils {

  /** Minimum free disk space required (10 MB). Matches TransferWorker.MIN_DISK_SPACE_BYTES. */
  public static final long MIN_DISK_SPACE_BYTES = 10 * 1024 * 1024;

  /**
   * Checks whether the device has enough free disk space.
   *
   * @param dir The directory to check space for (usually app files dir)
   * @return true if available space >= MIN_DISK_SPACE_BYTES, false otherwise
   */
  public static boolean hasEnoughDiskSpace(File dir) {
    if (dir == null) return true;
    try {
      StatFs stat = new StatFs(dir.getPath());
      long available = stat.getAvailableBytes();
      if (available < MIN_DISK_SPACE_BYTES) {
        LogUtils.w(
            "EnhancedFileUtils",
            "Low disk space: "
                + available
                + " bytes available (min: "
                + MIN_DISK_SPACE_BYTES
                + ")");
        return false;
      }
    } catch (Exception e) {
      LogUtils.w("EnhancedFileUtils", "Could not check disk space: " + e.getMessage());
    }
    return true;
  }

  /**
   * Checks whether the storage volume that holds the given target has enough free space.
   *
   * <p>Downloads and folder syncs write to a SAF location chosen by the user, which may live on a
   * removable SD card. Checking the primary external storage would miss a full SD card entirely, so
   * this resolves the volume behind the URI first. When the volume cannot be determined (foreign
   * document providers, cloud providers), the internal and primary external storage are checked
   * instead so the result is never more permissive than before.
   *
   * @param context context for resolving the URI
   * @param target the SAF tree or document URI (or file URI) that will be written to
   * @return true if the volume has at least {@link #MIN_DISK_SPACE_BYTES} free or cannot be
   *     resolved
   */
  public static boolean hasEnoughDiskSpaceForTarget(
      @NonNull Context context, @Nullable Uri target) {
    return hasEnoughDiskSpaceForVolume(context, target != null ? resolveVolumeRoot(target) : null);
  }

  /** Checks internal storage plus the given volume, or the primary external one if unknown. */
  static boolean hasEnoughDiskSpaceForVolume(@NonNull Context context, @Nullable File volume) {
    boolean internalOk = hasEnoughDiskSpace(context.getFilesDir());
    if (volume == null) {
      return internalOk && hasEnoughDiskSpace(Environment.getExternalStorageDirectory());
    }
    return internalOk && hasEnoughDiskSpace(volume);
  }

  /**
   * Describes the free space the disk check looked at, for activity log entries, e.g. {@code
   * /storage/427D-0786: 3.2 MB free, app storage: 1.4 GB free}.
   */
  public static @NonNull String describeFreeSpace(@NonNull Context context, @Nullable Uri target) {
    File volume = target != null ? resolveVolumeRoot(target) : null;
    return describeFreeSpace(
        context.getFilesDir(), volume != null ? volume : Environment.getExternalStorageDirectory());
  }

  static @NonNull String describeFreeSpace(@NonNull File internalDir, @NonNull File volume) {
    return volume.getPath()
        + ": "
        + freeSpaceText(volume)
        + " free, app storage: "
        + freeSpaceText(internalDir)
        + " free";
  }

  private static String freeSpaceText(File dir) {
    try {
      return formatFileSize(new StatFs(dir.getPath()).getAvailableBytes());
    } catch (Exception e) {
      return "?";
    }
  }

  /**
   * Resolves the mount point of the storage volume behind a URI, or {@code null} if unknown.
   *
   * <p>Only the external storage documents provider (the SAF picker's "Internal storage" and SD
   * card roots) is understood; its document ids carry the volume id in front of the colon.
   */
  @Nullable
  static File resolveVolumeRoot(@NonNull Uri uri) {
    return resolveVolumeRoot(uri, Environment.getExternalStorageDirectory(), new File("/storage"));
  }

  /**
   * Testable variant of {@link #resolveVolumeRoot(Uri)}.
   *
   * @param primaryRoot mount point of the primary external storage volume
   * @param storageRoot parent of the secondary volume mount points ({@code /storage} on devices)
   */
  @Nullable
  static File resolveVolumeRoot(
      @NonNull Uri uri, @NonNull File primaryRoot, @NonNull File storageRoot) {
    try {
      if ("file".equals(uri.getScheme())) {
        String path = uri.getPath();
        return path != null ? existingAncestor(new File(path)) : null;
      }
      if (!EXTERNAL_STORAGE_AUTHORITY.equals(uri.getAuthority())) {
        return null;
      }
      // Tree URIs look like /tree/<id> or /tree/<id>/document/<id>, plain ones like /document/<id>
      String docId =
          uri.getPathSegments().contains("document")
              ? DocumentsContract.getDocumentId(uri)
              : DocumentsContract.getTreeDocumentId(uri);
      File root = volumeRootForExternalStorageDocId(docId, primaryRoot, storageRoot);
      return root != null && root.isDirectory() ? root : null;
    } catch (Exception e) {
      LogUtils.w("EnhancedFileUtils", "Could not resolve storage volume for " + uri + ": " + e);
      return null;
    }
  }

  /** Authority of the external storage documents provider used by the SAF picker. */
  static final String EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents";

  /**
   * Maps an external storage document id such as {@code primary:Music/x.mp3} or {@code
   * 427D-0786:Music} to the mount point of its volume. Free of framework calls for testability.
   *
   * @param docId document id from {@link DocumentsContract#getDocumentId} or {@link
   *     DocumentsContract#getTreeDocumentId}
   * @param primaryRoot mount point of the primary external storage volume
   * @param storageRoot parent of the secondary volume mount points ({@code /storage} on devices)
   * @return the volume mount point, or {@code null} if the id carries no volume
   */
  @Nullable
  static File volumeRootForExternalStorageDocId(
      @Nullable String docId, @NonNull File primaryRoot, @NonNull File storageRoot) {
    if (docId == null) return null;
    int colon = docId.indexOf(':');
    String volume = colon < 0 ? docId : docId.substring(0, colon);
    if (volume.isEmpty() || volume.contains("/") || volume.contains("..")) return null;
    if ("primary".equalsIgnoreCase(volume)) return primaryRoot;
    // Secondary volumes (SD cards, USB drives) are mounted under /storage/<volume-id>
    return new File(storageRoot, volume);
  }

  @Nullable
  private static File existingAncestor(@NonNull File file) {
    File f = file;
    while (f != null && !f.exists()) f = f.getParentFile();
    return f;
  }

  /** Formats file size in human-readable format. */
  public static @NonNull String formatFileSize(long bytes) {
    if (bytes < 0) return "Unknown";
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
    if (bytes < 1024 * 1024 * 1024)
      return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    return String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
  }

  /** Gets file extension from filename. */
  public static @NonNull String getFileExtension(@NonNull String fileName) {
    if (fileName == null || fileName.isEmpty()) return "";

    int lastDot = fileName.lastIndexOf('.');
    if (lastDot == -1 || lastDot == fileName.length() - 1) {
      return "";
    }

    return fileName.substring(lastDot + 1).toLowerCase(Locale.US);
  }

  /** Determines file type category from extension. */
  public static @NonNull FileType getFileType(@NonNull String fileName) {
    String extension = getFileExtension(fileName);

    if (extension.isEmpty()) return FileType.UNKNOWN;

    // Image files
    if (isImageExtension(extension)) return FileType.IMAGE;

    // Video files
    if (isVideoExtension(extension)) return FileType.VIDEO;

    // Audio files
    if (isAudioExtension(extension)) return FileType.AUDIO;

    // Document files
    if (isDocumentExtension(extension)) return FileType.DOCUMENT;

    // Archive files
    if (isArchiveExtension(extension)) return FileType.ARCHIVE;

    return FileType.OTHER;
  }

  // Helper methods for file type detection

  private static boolean isImageExtension(String ext) {
    String[] imageExts = {"jpg", "jpeg", "png", "gif", "bmp", "webp", "ico", "svg", "tiff", "tif"};
    for (String imageExt : imageExts) {
      if (imageExt.equals(ext)) return true;
    }
    return false;
  }

  private static boolean isVideoExtension(String ext) {
    String[] videoExts = {"mp4", "avi", "mkv", "mov", "wmv", "flv", "webm", "m4v", "3gp", "ogv"};
    for (String videoExt : videoExts) {
      if (videoExt.equals(ext)) return true;
    }
    return false;
  }

  private static boolean isAudioExtension(String ext) {
    String[] audioExts = {"mp3", "wav", "flac", "aac", "ogg", "wma", "m4a", "opus", "aiff"};
    for (String audioExt : audioExts) {
      if (audioExt.equals(ext)) return true;
    }
    return false;
  }

  private static boolean isDocumentExtension(String ext) {
    String[] docExts = {
      "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "ods", "odp"
    };
    for (String docExt : docExts) {
      if (docExt.equals(ext)) return true;
    }
    return false;
  }

  private static boolean isArchiveExtension(String ext) {
    String[] archiveExts = {"zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso"};
    for (String archiveExt : archiveExts) {
      if (archiveExt.equals(ext)) return true;
    }
    return false;
  }

  // Enums and data classes

  public enum FileType {
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    ARCHIVE,
    OTHER,
    UNKNOWN
  }
}
