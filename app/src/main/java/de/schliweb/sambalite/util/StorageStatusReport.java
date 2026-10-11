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
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the disk space section of the System Monitor: the internal app storage plus every mounted
 * storage volume, including SD cards and USB drives, each with the sync targets that write to it.
 * The free space shown is the one the sync and transfer workers check before writing.
 */
public final class StorageStatusReport {

  /** A local folder the app writes to, e.g. the local side of a folder sync. */
  public static final class Target {
    final String label;
    final Uri uri;

    public Target(@NonNull String label, @Nullable Uri uri) {
      this.label = label;
      this.uri = uri;
    }
  }

  private static final class Volume {
    final File root;
    final String label;
    final List<String> targets = new ArrayList<>();

    Volume(File root, String label) {
      this.root = root;
      this.label = label;
    }
  }

  private StorageStatusReport() {}

  public static @NonNull String build(@NonNull Context context, @NonNull List<Target> targets) {
    List<File> appDirs = new ArrayList<>();
    for (File dir : context.getExternalFilesDirs(null)) {
      if (dir != null) appDirs.add(dir); // null while a volume is unmounted
    }
    return build(
        context.getFilesDir(),
        Environment.getExternalStorageDirectory(),
        new File("/storage"),
        appDirs,
        targets);
  }

  /**
   * Testable variant of {@link #build(Context, List)}.
   *
   * @param appDirs the app's directories on the external volumes, one per mounted volume
   */
  static @NonNull String build(
      @NonNull File internalDir,
      @NonNull File primaryRoot,
      @NonNull File storageRoot,
      @NonNull List<File> appDirs,
      @NonNull List<Target> targets) {
    Map<String, Volume> volumes = new LinkedHashMap<>();
    volume(volumes, primaryRoot, primaryRoot);
    for (File dir : appDirs) {
      File root = volumeRootOf(dir, primaryRoot, storageRoot);
      if (root != null) volume(volumes, root, primaryRoot);
    }
    List<String> unresolved = new ArrayList<>();
    for (Target target : targets) {
      File root =
          target.uri != null
              ? EnhancedFileUtils.resolveVolumeRoot(target.uri, primaryRoot, storageRoot)
              : null;
      if (root == null) {
        unresolved.add(target.label);
      } else {
        volume(volumes, root, primaryRoot).targets.add(target.label);
      }
    }

    StringBuilder sb = new StringBuilder("=== Disk Space ===\n");
    List<String> low = new ArrayList<>();
    appendVolume(sb, low, "Internal app storage", internalDir, new ArrayList<>());
    for (Volume v : volumes.values()) {
      appendVolume(sb, low, v.label, v.root, v.targets);
    }
    if (!unresolved.isEmpty()) {
      sb.append("Sync targets on other providers (space not checked):\n");
      for (String label : unresolved) sb.append("- ").append(label).append("\n");
    }
    if (!low.isEmpty()) {
      sb.append("\n⚠ WARNING: Low disk space on ")
          .append(String.join(", ", low))
          .append(" (below ")
          .append(EnhancedFileUtils.formatFileSize(EnhancedFileUtils.MIN_DISK_SPACE_BYTES))
          .append(", syncs and downloads to it are stopped)\n");
    }
    return sb.toString();
  }

  private static Volume volume(Map<String, Volume> volumes, File root, File primaryRoot) {
    String key = root.getAbsolutePath();
    Volume v = volumes.get(key);
    if (v == null) {
      String kind = key.equals(primaryRoot.getAbsolutePath()) ? "Shared storage" : "SD card / USB";
      v = new Volume(root, kind + " (" + key + ")");
      volumes.put(key, v);
    }
    return v;
  }

  /**
   * Maps a directory on an external volume, e.g. {@code /storage/427D-0786/Android/data/<pkg>}, to
   * the volume's mount point. Secondary volumes are mounted directly below {@code storageRoot}.
   */
  @Nullable
  static File volumeRootOf(
      @NonNull File dir, @NonNull File primaryRoot, @NonNull File storageRoot) {
    String path = dir.getAbsolutePath();
    String primary = primaryRoot.getAbsolutePath();
    if (path.equals(primary) || path.startsWith(primary + "/")) return primaryRoot;
    String storage = storageRoot.getAbsolutePath() + "/";
    if (!path.startsWith(storage)) return null;
    String rest = path.substring(storage.length());
    int slash = rest.indexOf('/');
    String volume = slash < 0 ? rest : rest.substring(0, slash);
    return volume.isEmpty() ? null : new File(storageRoot, volume);
  }

  private static void appendVolume(
      StringBuilder sb, List<String> low, String label, File dir, List<String> targets) {
    sb.append(label).append(":\n");
    try {
      StatFs stat = new StatFs(dir.getPath());
      long total = stat.getTotalBytes();
      long available = stat.getAvailableBytes();
      sb.append("- Used: ")
          .append(EnhancedFileUtils.formatFileSize(total - available))
          .append(" / ")
          .append(EnhancedFileUtils.formatFileSize(total))
          .append("\n- Free: ")
          .append(EnhancedFileUtils.formatFileSize(available));
      if (available < EnhancedFileUtils.MIN_DISK_SPACE_BYTES) {
        sb.append(" ⚠");
        low.add(label);
      }
      sb.append("\n");
    } catch (Exception e) {
      sb.append("- Not accessible: ").append(e.getMessage()).append("\n");
    }
    for (String target : targets) sb.append("- Sync target: ").append(target).append("\n");
  }
}
