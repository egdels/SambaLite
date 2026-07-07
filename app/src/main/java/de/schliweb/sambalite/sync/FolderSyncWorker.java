/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.sync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msdtyp.FileTime;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileBasicInformation;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import com.hierynomus.smbj.transport.tcp.async.AsyncDirectTcpTransportFactory;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.repository.ConnectionRepositoryImpl;
import de.schliweb.sambalite.sync.db.FileSyncState;
import de.schliweb.sambalite.sync.db.SyncStateStore;
import de.schliweb.sambalite.util.EnhancedFileUtils;
import de.schliweb.sambalite.util.LogUtils;
import de.schliweb.sambalite.util.StorageCapabilityResolver;
import de.schliweb.sambalite.util.TimestampCapability;
import de.schliweb.sambalite.util.TimestampUtils;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

/**
 * WorkManager Worker that performs folder synchronization in the background. Iterates over all
 * enabled SyncConfigs and syncs each folder pair.
 */
public class FolderSyncWorker extends Worker {

  private static final String TAG = "FolderSyncWorker";
  private static final int BUFFER_SIZE = 256 * 1024;

  /**
   * Process-wide locks per sync config ID. WorkManager's unique work names do not prevent a
   * manual per-config sync ("sambalite_folder_sync_&lt;configId&gt;") from running concurrently
   * with the periodic or "sync all" worker ("sambalite_folder_sync"), so two workers could sync
   * the same folder pair at the same time and race (e.g. creating duplicate "B (1)" entries via
   * SAF). These locks serialize sync runs per config within the app process.
   */
  private static final ConcurrentHashMap<String, ReentrantLock> CONFIG_LOCKS =
      new ConcurrentHashMap<>();

  /** Returns the process-wide lock for the given sync config ID. */
  static ReentrantLock lockForConfig(String configId) {
    return CONFIG_LOCKS.computeIfAbsent(configId, id -> new ReentrantLock());
  }

  /**
   * Requested SMB2 read/write/transact buffer size (8 MB). Larger buffers reduce the number of SMB2
   * packets per transfer; the effective size is capped by server negotiation.
   */
  private static final int TRANSFER_BUFFER_SIZE = 8 * 1024 * 1024;

  /**
   * Files at or above this size are downloaded with multiple concurrently outstanding SMB READ
   * requests (pipelined). Smaller files use the simple streamed path.
   */
  private static final long PARALLEL_DOWNLOAD_THRESHOLD = 16L * 1024 * 1024;

  /**
   * Chunk size for parallel downloads. Each chunk is fetched with a single SMB READ request
   * (must stay at or below the negotiated maxReadSize, typically 8 MB on SMB 3.x servers).
   */
  private static final int PARALLEL_CHUNK_SIZE = 4 * 1024 * 1024;

  /**
   * Number of concurrently outstanding SMB READ requests during parallel downloads. The SMB
   * connection is multiplexed, so multiple requests are pipelined over the same TCP connection,
   * hiding network round-trip latency.
   */
  private static final int PARALLEL_READERS = 6;

  /**
   * Maximum number of chunk writes that may be queued behind the dedicated writer thread during
   * parallel downloads. Decouples slow SAF/disk writes from the SMB read pipeline while keeping
   * memory usage bounded (PARALLEL_WRITE_QUEUE * PARALLEL_CHUNK_SIZE additional bytes at most).
   */
  private static final int PARALLEL_WRITE_QUEUE = 2;

  private static final long DISK_CHECK_INTERVAL = 10L * 1024 * 1024;
  public static final String KEY_SYNC_CONFIG_ID = "sync_config_id";
  private static final String SYNC_CHANNEL_ID = "FOLDER_SYNC_OPERATIONS";
  private static final int SYNC_NOTIFICATION_ID = 2001;
  private SyncActionLog actionLog;
  private final SyncComparator syncComparator = new SyncComparator();
  private SyncStateStore syncStateStore;

  public FolderSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
    super(context, params);
    this.actionLog = new SyncActionLog(context);
    this.syncStateStore = new SyncStateStore(context);
  }

  @NonNull
  @Override
  public ForegroundInfo getForegroundInfo() {
    return createForegroundInfo();
  }

  private ForegroundInfo createForegroundInfo() {
    Context context = getApplicationContext();

    NotificationChannel channel =
        new NotificationChannel(
            SYNC_CHANNEL_ID,
            context.getString(de.schliweb.sambalite.R.string.your_folder_syncs),
            NotificationManager.IMPORTANCE_LOW);
    channel.setDescription(context.getString(de.schliweb.sambalite.R.string.sync_setup_subtitle));
    channel.setShowBadge(false);
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (manager != null) {
      manager.createNotificationChannel(channel);
    }

    Notification notification =
        new NotificationCompat.Builder(context, SYNC_CHANNEL_ID)
            .setContentTitle(context.getString(de.schliweb.sambalite.R.string.sync_running))
            .setSmallIcon(de.schliweb.sambalite.R.drawable.ic_notification)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      return new ForegroundInfo(
          SYNC_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    }
    return new ForegroundInfo(SYNC_NOTIFICATION_ID, notification);
  }

  @NonNull
  @Override
  public Result doWork() {
    LogUtils.i(TAG, "Starting folder sync work");

    // Check if a specific config ID was requested
    String specificConfigId = getInputData().getString(KEY_SYNC_CONFIG_ID);
    boolean isManualSync = specificConfigId != null;
    LogUtils.i(
        TAG,
        "Sync type: " + (isManualSync ? "manual (config=" + specificConfigId + ")" : "periodic"));

    // Promote to foreground to show notification and survive app kill
    try {
      setForegroundAsync(createForegroundInfo());
      LogUtils.i(TAG, "Successfully promoted to foreground service");
    } catch (Exception e) {
      LogUtils.w(TAG, "Could not promote to foreground: " + e.getMessage());
    }

    SyncRepository syncRepository = new SyncRepository(getApplicationContext());
    ConnectionRepositoryImpl connectionRepository =
        new ConnectionRepositoryImpl(getApplicationContext());

    List<SyncConfig> configs;
    if (specificConfigId != null) {
      LogUtils.i(TAG, "Starting sync for specific config: " + specificConfigId);
      configs = syncRepository.getAllEnabledConfigs();
      configs.removeIf(c -> !c.getId().equals(specificConfigId));
    } else {
      LogUtils.i(TAG, "Starting periodic sync for all enabled configs");
      configs = syncRepository.getAllEnabledConfigs();
    }

    if (configs.isEmpty()) {
      LogUtils.i(TAG, "No enabled sync configs found, finishing");
      return Result.success();
    }

    List<SmbConnection> connections = connectionRepository.getAllConnections();
    Map<String, SmbConnection> connectionMap = new HashMap<>();
    for (SmbConnection conn : connections) {
      connectionMap.put(conn.getId(), conn);
    }

    boolean anyFailure = false;

    for (SyncConfig config : configs) {
      if (isStopped()) {
        LogUtils.i(TAG, "Worker stopped, aborting sync");
        // For manual/immediate sync: retry so the job resumes after kill
        // For periodic sync: success, next periodic run will catch up
        if (specificConfigId != null) {
          LogUtils.i(TAG, "Manual sync interrupted, requesting retry");
          return Result.retry();
        }
        return Result.success();
      }

      // For periodic sync (no specific config), respect individual intervals
      if (specificConfigId == null) {
        // Skip manual-only configs during periodic sync
        if (config.getIntervalMinutes() <= 0) {
          LogUtils.d(
              TAG, "Skipping manual-only config " + config.getId() + " during periodic sync");
          continue;
        }
        long lastSync = config.getLastSyncTimestamp();
        long intervalMs = config.getIntervalMinutes() * 60L * 1000L;
        long elapsed = System.currentTimeMillis() - lastSync;
        if (lastSync > 0 && elapsed < intervalMs) {
          LogUtils.d(
              TAG,
              "Skipping config "
                  + config.getId()
                  + " (next sync in "
                  + ((intervalMs - elapsed) / 60000)
                  + " min)");
          continue;
        }
      }

      SmbConnection connection = connectionMap.get(config.getConnectionId());
      if (connection == null) {
        LogUtils.w(TAG, "Connection not found for config: " + config.getId());
        continue;
      }

      // Check if sync is restricted to WiFi only
      if (config.isWifiOnly() && !isConnectedToWifi()) {
        LogUtils.i(
            TAG,
            "Skipping config "
                + config.getId()
                + " because WiFi is not connected and wifiOnly is enabled");
        continue;
      }

      // Check disk space before starting sync for this config
      if (!hasEnoughDiskSpace()) {
        LogUtils.e(
            TAG, "Insufficient disk space \u2013 aborting sync for config: " + config.getId());
        anyFailure = true;
        break;
      }

      // Guard against parallel sync runs on the same config: manual per-config syncs, the
      // "sync all" trigger and the periodic worker use different WorkManager unique names and
      // can therefore run concurrently. Two workers syncing the same folder pair at the same
      // time can race and create duplicate entries (e.g. "B (1)" via SAF).
      ReentrantLock lock = lockForConfig(config.getId());
      if (!lock.tryLock()) {
        LogUtils.w(
            TAG,
            "Skipping config " + config.getId() + ": another sync is already running for it");
        if (specificConfigId != null) {
          // Manual sync: request a retry so the user's request is not silently dropped
          anyFailure = true;
        }
        continue;
      }
      try {
        syncFolder(config, connection);
        syncRepository.updateLastSyncTimestamp(config.getId(), System.currentTimeMillis());
        LogUtils.i(TAG, "Sync completed for config: " + config.getId());
      } catch (InsufficientDiskSpaceException e) {
        LogUtils.e(TAG, "Sync aborted for config " + config.getId() + ": insufficient disk space");
        anyFailure = true;
        break;
      } catch (Exception e) {
        LogUtils.e(TAG, "Sync failed for config " + config.getId() + ": " + e.getMessage());
        anyFailure = true;
      } finally {
        lock.unlock();
      }

      // Check disk space between configs to avoid futile attempts
      if (!hasEnoughDiskSpace()) {
        LogUtils.e(TAG, "Insufficient disk space \u2013 aborting remaining sync configs");
        anyFailure = true;
        break;
      }
    }

    if (anyFailure) {
      LogUtils.w(TAG, "Some sync operations failed, requesting retry");
      return Result.retry();
    }

    LogUtils.i(TAG, "All sync operations completed successfully");
    return Result.success();
  }

  /** Synchronizes a single folder pair based on the given config and connection. */
  private void syncFolder(SyncConfig config, SmbConnection connection) throws Exception {
    LogUtils.d(TAG, "Syncing folder for config: " + config.getId());

    DocumentFile localFolder =
        DocumentFile.fromTreeUri(getApplicationContext(), Uri.parse(config.getLocalFolderUri()));
    if (localFolder == null || !localFolder.exists()) {
      throw new Exception("Local folder not accessible: " + config.getLocalFolderUri());
    }

    try (SMBClient client = createSmbClient(connection);
        Connection conn = client.connect(connection.getServer())) {

      AuthenticationContext authContext = createAuthContext(connection);
      try (Session session = conn.authenticate(authContext)) {
        String shareName = getShareName(connection.getShare());
        try (DiskShare share = (DiskShare) session.connectShare(shareName)) {

          // Ensure remote directory exists
          String remotePath = config.getRemotePath();
          if (remotePath != null && !remotePath.isEmpty()) {
            ensureRemoteDirectoryExists(share, remotePath);
          }

          String rootUri = config.getLocalFolderUri();

          // Mirror mode is only meaningful for one-way directions; it is ignored for BIDIRECTIONAL.
          boolean mirror =
              config.isMirror() && config.getDirection() != SyncDirection.BIDIRECTIONAL;
          boolean useTrash = config.isMirrorUseTrash();

          switch (config.getDirection()) {
            case LOCAL_TO_REMOTE:
              syncLocalToRemote(share, localFolder, remotePath, rootUri, "");
              if (mirror && !isStopped()) {
                runMirrorSweepLocalSource(share, localFolder, remotePath, rootUri, useTrash);
              }
              break;
            case REMOTE_TO_LOCAL:
              syncRemoteToLocal(share, localFolder, remotePath, rootUri, "");
              if (mirror && !isStopped()) {
                runMirrorSweepRemoteSource(share, localFolder, remotePath, rootUri, useTrash);
              }
              break;
            case BIDIRECTIONAL:
              syncLocalToRemote(share, localFolder, remotePath, rootUri, "");
              syncRemoteToLocal(share, localFolder, remotePath, rootUri, "");
              break;
          }
        }
      }
    }
  }

  /** Syncs local files to remote. Uploads files that are newer locally or don't exist remotely. */
  private void syncLocalToRemote(
      DiskShare share,
      DocumentFile localFolder,
      String remotePath,
      String rootUri,
      String relPath) {
    if (isStopped()) return;

    DocumentFile[] localFilesArray = localFolder.listFiles();

    // Performance (issue #21): fetch all remote metadata for this directory in a single
    // share.list() round-trip instead of opening every remote file multiple times
    // (fileExists + getRemoteFileLastModified + getRemoteFileSize) per file. This mirrors the
    // approach already used by syncRemoteToLocal and dramatically reduces SMB round-trips,
    // which dominate sync time on high-latency connections.
    Map<String, FileIdBothDirectoryInformation> remoteMetadata =
        listRemoteMetadata(share, remotePath);

    for (DocumentFile localFile : localFilesArray) {
      if (isStopped()) return;

      String name = localFile.getName();
      if (name == null) continue;
      if (isTrashAtRoot(name, relPath)) continue;

      String remoteFilePath = smbJoin(remotePath, name);

      if (localFile.isDirectory()) {
        ensureRemoteDirectoryExists(share, remoteFilePath);
        String childRelPath = relPath.isEmpty() ? name : relPath + "/" + name;
        syncLocalToRemote(share, localFile, remoteFilePath, rootUri, childRelPath);
      } else {
        try {
          long localModified = localFile.lastModified();
          FileIdBothDirectoryInformation remoteInfo = remoteMetadata.get(name);
          boolean remoteExists = remoteInfo != null;

          String fileRelPath = relPath.isEmpty() ? name : relPath + "/" + name;

          if (!remoteExists) {
            long remoteSize = uploadFile(share, localFile, remoteFilePath);
            actionLog.log(SyncActionLog.Action.UPLOADED, name);
            // After upload the remote lastWriteTime is set to the local file's lastModified
            // (see uploadFile), so no extra round-trips are needed to read it back.
            syncStateStore.saveRemoteState(
                rootUri, fileRelPath, remoteFilePath, remoteSize, localFile.lastModified(), false);
          } else {
            long remoteModified = remoteInfo.getLastWriteTime().toEpochMillis();
            long remoteSize = remoteInfo.getEndOfFile();
            long localSize = localFile.length();

            // Check stored DB state first – SAF timestamps are unreliable
            var storedState = syncStateStore.getRemoteState(rootUri, fileRelPath);
            if (storedState != null
                && storedState.remoteSize == remoteSize
                && Math.abs(storedState.remoteLastModified - remoteModified)
                    < SyncComparator.DEFAULT_TIMESTAMP_TOLERANCE_MS
                && localSize == remoteSize) {
              LogUtils.d(
                  TAG, "Skipping upload (DB state matches remote, local size same): " + name);
              actionLog.log(SyncActionLog.Action.SKIPPED, name, "same (DB state)");
            } else if (syncComparator.isSame(
                localSize, localModified, remoteSize, remoteModified)) {
              LogUtils.d(TAG, "Skipping upload (same): " + name);
              actionLog.log(SyncActionLog.Action.SKIPPED, name, "same (size+timestamp)");
            } else if (syncComparator.isLocalNewer(localModified, remoteModified)) {
              long newRemoteSize = uploadFile(share, localFile, remoteFilePath);
              actionLog.log(SyncActionLog.Action.UPLOADED, name);
              syncStateStore.saveRemoteState(
                  rootUri,
                  fileRelPath,
                  remoteFilePath,
                  newRemoteSize,
                  localFile.lastModified(),
                  false);
            } else {
              LogUtils.d(TAG, "Skipping upload (remote is newer or within tolerance): " + name);
              actionLog.log(SyncActionLog.Action.SKIPPED, name, "remote newer or within tolerance");
            }
          }
        } catch (Exception e) {
          LogUtils.e(TAG, "Error syncing local file " + name + ": " + e.getMessage());
          actionLog.log(SyncActionLog.Action.ERROR, name, e.getMessage());
        }
      }
    }
  }

  /**
   * Lists a remote directory once and returns a map of file name to its metadata ({@link
   * FileIdBothDirectoryInformation}), which already contains existence, size ({@code
   * getEndOfFile()}) and timestamp ({@code getLastWriteTime()}). Returns an empty map if the
   * directory does not exist yet or cannot be listed.
   */
  private Map<String, FileIdBothDirectoryInformation> listRemoteMetadata(
      DiskShare share, String remotePath) {
    Map<String, FileIdBothDirectoryInformation> result = new HashMap<>();
    try {
      for (FileIdBothDirectoryInformation info : share.list(remotePath)) {
        String name = info.getFileName();
        if (".".equals(name) || "..".equals(name)) continue;
        result.put(name, info);
      }
    } catch (Exception e) {
      LogUtils.d(TAG, "Could not list remote directory " + remotePath + ": " + e.getMessage());
    }
    return result;
  }

  /**
   * Syncs remote files to local. Downloads files that are newer remotely or don't exist locally.
   */
  private void syncRemoteToLocal(
      DiskShare share,
      DocumentFile localFolder,
      String remotePath,
      String rootUri,
      String relPath) {
    if (isStopped()) return;

    try {
      List<FileIdBothDirectoryInformation> remoteFiles = share.list(remotePath);

      // Cache local files to avoid expensive findFile calls which can cause duplicates in SAF.
      // Keys are Unicode-normalized (NFC); a lowercase fallback map covers case-insensitive
      // matches, since SMB is case-insensitive but HashMap lookups are not. Without this,
      // a missed lookup would trigger createDirectory/createFile and the SAF provider would
      // silently create a duplicate like "B (1)".
      DocumentFile[] localFilesArray = localFolder.listFiles();
      Map<String, DocumentFile> localFilesMap = new HashMap<>();
      Map<String, DocumentFile> localFilesMapLower = new HashMap<>();
      for (DocumentFile f : localFilesArray) {
        String n = f.getName();
        if (n != null) {
          String key = normalizeName(n);
          localFilesMap.put(key, f);
          localFilesMapLower.put(key.toLowerCase(Locale.ROOT), f);
        }
      }

      for (FileIdBothDirectoryInformation remoteFile : remoteFiles) {
        if (isStopped()) return;

        String name = remoteFile.getFileName();
        if (".".equals(name) || "..".equals(name)) continue;
        if (isTrashAtRoot(name, relPath)) continue;

        String remoteFilePath = smbJoin(remotePath, name);
        boolean isDirectory =
            (remoteFile.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue())
                != 0;

        if (isDirectory) {
          DocumentFile localSubDir = lookupLocal(localFilesMap, localFilesMapLower, name);
          if (localSubDir == null) {
            localSubDir = createDirectorySafe(localFolder, name);
            actionLog.log(SyncActionLog.Action.CREATED_DIR, name);
          }
          if (localSubDir != null) {
            String childRelPath = relPath.isEmpty() ? name : relPath + "/" + name;
            syncRemoteToLocal(share, localSubDir, remoteFilePath, rootUri, childRelPath);
          }
        } else {
          // Check disk space before each file download
          if (!hasEnoughDiskSpace()) {
            throw new InsufficientDiskSpaceException(
                "Insufficient disk space before downloading: " + name);
          }
          try {
            long remoteModified = remoteFile.getLastWriteTime().toEpochMillis();
            long remoteSize = getRemoteFileSize(share, remoteFilePath);
            String fileRelPath = relPath.isEmpty() ? name : relPath + "/" + name;
            DocumentFile localFile = lookupLocal(localFilesMap, localFilesMapLower, name);

            if (localFile == null) {
              String mimeType = getMimeType(name);
              DocumentFile newFile = localFolder.createFile(mimeType, name);
              if (newFile != null) {
                downloadFile(share, remoteFilePath, newFile);
                actionLog.log(SyncActionLog.Action.DOWNLOADED, name);
                syncStateStore.saveRemoteState(
                    rootUri, fileRelPath, remoteFilePath, remoteSize, remoteModified, false);
              }
            } else {
              // Use stored metadata as fallback for SAF timestamp comparison
              long localModified = localFile.lastModified();
              long localSize = localFile.length();

              FileSyncState storedState = syncStateStore.getRemoteState(rootUri, fileRelPath);
              if (storedState != null
                  && storedState.remoteSize == remoteSize
                  && storedState.remoteLastModified == remoteModified) {
                LogUtils.d(TAG, "Skipping download (unchanged per stored metadata): " + name);
                actionLog.log(SyncActionLog.Action.SKIPPED, name, "unchanged (stored metadata)");
              } else if (syncComparator.isSame(
                  localSize, localModified, remoteSize, remoteModified)) {
                LogUtils.d(TAG, "Skipping download (same): " + name);
                actionLog.log(SyncActionLog.Action.SKIPPED, name, "same (size+timestamp)");
              } else if (syncComparator.isRemoteNewer(localModified, remoteModified)) {
                downloadFile(share, remoteFilePath, localFile);
                actionLog.log(SyncActionLog.Action.DOWNLOADED, name);
                syncStateStore.saveRemoteState(
                    rootUri, fileRelPath, remoteFilePath, remoteSize, remoteModified, false);
              } else {
                LogUtils.d(TAG, "Skipping download (local is newer or within tolerance): " + name);
                actionLog.log(
                    SyncActionLog.Action.SKIPPED, name, "local newer or within tolerance");
              }
            }
          } catch (InsufficientDiskSpaceException e) {
            throw e;
          } catch (Exception e) {
            LogUtils.e(TAG, "Error syncing remote file " + name + ": " + e.getMessage());
            actionLog.log(SyncActionLog.Action.ERROR, name, e.getMessage());
            // Check if the failure was caused by low disk space
            if (!hasEnoughDiskSpace()) {
              throw new InsufficientDiskSpaceException(
                  "Insufficient disk space after failed download: " + name);
            }
          }
        }
      }

      // Note: DB cleanup for remotely deleted files is handled by MirrorSweeper
      // (runMirrorSweepRemoteSource), which is responsible for moving the corresponding local
      // file to the trash (or deleting it) and removing the DB entry afterwards. Performing the
      // DB cleanup here would race with the sweeper and prevent it from finding any candidates,
      // so the local file would never be moved to the trash.
    } catch (Exception e) {
      LogUtils.e(TAG, "Error listing remote directory " + remotePath + ": " + e.getMessage());
    }
  }

  /** Normalizes a file name to Unicode NFC so SMB (often NFD) and SAF names compare equal. */
  static String normalizeName(String name) {
    return Normalizer.normalize(name, Normalizer.Form.NFC);
  }

  /**
   * Looks up a local DocumentFile by name using NFC normalization, falling back to a
   * case-insensitive match (SMB is case-insensitive, HashMap lookups are not).
   */
  static DocumentFile lookupLocal(
      Map<String, DocumentFile> byName, Map<String, DocumentFile> byLowerName, String name) {
    String key = normalizeName(name);
    DocumentFile f = byName.get(key);
    if (f == null) {
      f = byLowerName.get(key.toLowerCase(Locale.ROOT));
    }
    return f;
  }

  /**
   * Creates a subdirectory and guards against the SAF provider silently deduplicating the name
   * (e.g. creating "B (1)" because an entry named "B" already exists). In that case the duplicate
   * is removed and the existing directory is reused.
   */
  private DocumentFile createDirectorySafe(DocumentFile parent, String name) {
    DocumentFile created = parent.createDirectory(name);
    if (created != null && !name.equals(created.getName())) {
      LogUtils.w(
          TAG,
          "Provider renamed new directory '"
              + name
              + "' to '"
              + created.getName()
              + "'; looking for existing entry to reuse");
      DocumentFile existing = findDirectoryByNameInsensitive(parent, name, created);
      if (existing != null) {
        created.delete();
        return existing;
      }
    }
    return created;
  }

  /**
   * Finds an existing directory in {@code parent} whose name matches {@code name} after NFC
   * normalization and ignoring case, excluding {@code exclude}.
   */
  private static DocumentFile findDirectoryByNameInsensitive(
      DocumentFile parent, String name, DocumentFile exclude) {
    String key = normalizeName(name).toLowerCase(Locale.ROOT);
    for (DocumentFile f : parent.listFiles()) {
      if (!f.isDirectory()) continue;
      if (exclude != null && f.getUri().equals(exclude.getUri())) continue;
      String n = f.getName();
      if (n != null && normalizeName(n).toLowerCase(Locale.ROOT).equals(key)) {
        return f;
      }
    }
    return null;
  }

  /**
   * Uploads a local DocumentFile to the remote SMB share.
   *
   * @return the verified remote file size in bytes (from the integrity check)
   */
  private long uploadFile(DiskShare share, DocumentFile localFile, String remotePath)
      throws Exception {
    LogUtils.d(TAG, "Uploading: " + localFile.getName() + " -> " + remotePath);

    long remoteSize = -1;
    // Performance (issue #21): use a single file handle for writing, the post-upload size check
    // and setting the lastWriteTime instead of re-opening the remote file two more times.
    // FILE_READ_ATTRIBUTES/FILE_WRITE_ATTRIBUTES are requested in addition to GENERIC_WRITE so
    // that getFileInformation()/setFileInformation() work on the same handle.
    try (File remoteFile =
        share.openFile(
            remotePath,
            EnumSet.of(
                AccessMask.GENERIC_WRITE,
                AccessMask.FILE_READ_ATTRIBUTES,
                AccessMask.FILE_WRITE_ATTRIBUTES),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            null)) {

      try (InputStream is =
              getApplicationContext().getContentResolver().openInputStream(localFile.getUri());
          OutputStream os = remoteFile.getOutputStream()) {

        if (is == null) {
          throw new Exception("Could not open input stream for: " + localFile.getUri());
        }

        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        while ((bytesRead = is.read(buffer)) != -1) {
          os.write(buffer, 0, bytesRead);
        }
      }

      // Integrity check on the same handle: read the remote size without an extra file open
      try {
        remoteSize = remoteFile.getFileInformation().getStandardInformation().getEndOfFile();
      } catch (Exception e) {
        LogUtils.w(TAG, "Could not get file size for: " + remotePath + ": " + e.getMessage());
      }

      // Set remote file's lastWriteTime to match local file's lastModified
      // to prevent re-uploading on next sync cycle (same handle, no extra open)
      setLastModifiedOnHandle(remoteFile, remotePath, localFile.lastModified());
    }

    long localSize = localFile.length();
    LogUtils.i(
        TAG,
        "Sync upload integrity check: remoteSize="
            + remoteSize
            + ", localSize="
            + localSize
            + ", file="
            + localFile.getName());
    if (remoteSize >= 0 && localSize >= 0 && remoteSize != localSize) {
      throw new Exception(
          "Sync upload integrity check failed: remoteSize="
              + remoteSize
              + " localSize="
              + localSize);
    }
    if (remoteSize < 0 || localSize < 0) {
      LogUtils.w(
          TAG,
          "Sync upload integrity check skipped: remoteSize="
              + remoteSize
              + ", localSize="
              + localSize
              + ", file="
              + localFile.getName());
    }

    LogUtils.d(TAG, "Upload completed: " + localFile.getName());
    return remoteSize;
  }

  /** Downloads a remote file to a local DocumentFile (SAF). */
  private void downloadFile(DiskShare share, String remotePath, DocumentFile localFile)
      throws Exception {
    LogUtils.d(TAG, "Downloading: " + remotePath + " -> " + localFile.getName());

    long remoteTimestamp = 0;
    try (File remoteFile =
        share.openFile(
            remotePath,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null)) {

      // Read remote timestamp before downloading
      remoteTimestamp =
          remoteFile.getFileInformation().getBasicInformation().getLastWriteTime().toEpochMillis();
      LogUtils.d(
          TAG,
          "[TIMESTAMP] Remote lastWriteTime: "
              + localFile.getName()
              + " = "
              + TimestampUtils.formatTimestamp(remoteTimestamp)
              + " ("
              + remoteTimestamp
              + "ms)");

      long fileSize =
          remoteFile.getFileInformation().getStandardInformation().getEndOfFile();

      OutputStream rawOut =
          getApplicationContext().getContentResolver().openOutputStream(localFile.getUri());
      if (rawOut == null) {
        throw new Exception("Could not open output stream for: " + localFile.getUri());
      }

      try (OutputStream out = new BufferedOutputStream(rawOut, BUFFER_SIZE)) {
        if (fileSize >= PARALLEL_DOWNLOAD_THRESHOLD) {
          LogUtils.i(
              TAG,
              "Using parallel download ("
                  + PARALLEL_READERS
                  + " readers, "
                  + PARALLEL_CHUNK_SIZE
                  + " byte chunks) for: "
                  + localFile.getName());
          downloadParallel(remoteFile, out, fileSize, localFile.getName());
        } else {
          downloadStreamed(remoteFile, out, localFile.getName());
        }
        out.flush();
      }
    }

    // Integrity check: compare actual local file size against remote file size
    long localSize = localFile.length();
    long remoteSize = getRemoteFileSize(share, remotePath);
    LogUtils.i(
        TAG,
        "Sync download integrity check: localSize="
            + localSize
            + ", remoteSize="
            + remoteSize
            + ", file="
            + localFile.getName());
    if (localSize >= 0 && remoteSize >= 0 && localSize != remoteSize) {
      throw new Exception(
          "Sync download integrity check failed: localSize="
              + localSize
              + " remoteSize="
              + remoteSize);
    }
    if (localSize < 0 || remoteSize < 0) {
      LogUtils.w(
          TAG,
          "Sync download integrity check skipped: localSize="
              + localSize
              + ", remoteSize="
              + remoteSize
              + ", file="
              + localFile.getName());
    }

    // SAF/DocumentFile: attempt best-effort timestamp preservation via utimensat()
    TimestampCapability capability = StorageCapabilityResolver.resolve(localFile.getUri());

    if (remoteTimestamp > 0) {
      boolean timestampSet =
          TimestampUtils.trySetLastModified(
              getApplicationContext(), localFile.getUri(), remoteTimestamp);
      if (timestampSet) {
        actionLog.log(
            SyncActionLog.Action.TIMESTAMP_SET,
            localFile.getName(),
            "SAF best-effort succeeded (capability="
                + capability
                + ", remote="
                + TimestampUtils.formatTimestamp(remoteTimestamp)
                + ")");
      } else {
        actionLog.log(
            SyncActionLog.Action.TIMESTAMP_FAILED,
            localFile.getName(),
            "SAF best-effort failed (capability="
                + capability
                + ", remote="
                + TimestampUtils.formatTimestamp(remoteTimestamp)
                + ")");
      }
    } else {
      LogUtils.w(
          TAG,
          "[TIMESTAMP] SAF download completed without valid remote timestamp: "
              + localFile.getName());
      actionLog.log(
          SyncActionLog.Action.TIMESTAMP_FAILED,
          localFile.getName(),
          "No valid remote timestamp available");
    }
  }

  /**
   * Downloads a file using multiple concurrently outstanding SMB READ requests. The SMB connection
   * multiplexes the requests over the same TCP connection, hiding network round-trip latency and
   * keeping the pipe full. Chunks are written to the output stream strictly in order.
   */
  private void downloadParallel(File remoteFile, OutputStream out, long fileSize, String name)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_READERS);
    ExecutorService writer = Executors.newSingleThreadExecutor();
    try {
      ArrayDeque<Future<byte[]>> inFlight = new ArrayDeque<>(PARALLEL_READERS);
      ArrayDeque<Future<?>> pendingWrites = new ArrayDeque<>(PARALLEL_WRITE_QUEUE + 1);
      long submitOffset = 0;
      long bytesSinceLastDiskCheck = 0;

      while (submitOffset < fileSize || !inFlight.isEmpty()) {
        // Keep the read pipeline full
        while (inFlight.size() < PARALLEL_READERS && submitOffset < fileSize) {
          final long offset = submitOffset;
          final int length = (int) Math.min(PARALLEL_CHUNK_SIZE, fileSize - offset);
          submitOffset += length;
          inFlight.add(pool.submit(() -> readChunk(remoteFile, offset, length)));
        }

        final byte[] chunk = inFlight.remove().get();

        // Hand the chunk to the dedicated writer thread so slow SAF/disk writes never stall
        // the SMB read pipeline. Writes stay strictly in order (single writer thread).
        pendingWrites.add(
            writer.submit(
                () -> {
                  out.write(chunk);
                  return null;
                }));
        // Bound the write queue to cap memory usage and surface write errors early
        while (pendingWrites.size() > PARALLEL_WRITE_QUEUE) {
          pendingWrites.remove().get();
        }

        bytesSinceLastDiskCheck += chunk.length;
        if (bytesSinceLastDiskCheck >= DISK_CHECK_INTERVAL) {
          if (!hasEnoughDiskSpace()) {
            for (Future<byte[]> f : inFlight) {
              f.cancel(true);
            }
            LogUtils.e(TAG, "Download aborted \u2013 disk space low: " + name);
            throw new InsufficientDiskSpaceException("Insufficient disk space");
          }
          bytesSinceLastDiskCheck = 0;
        }
      }

      // Wait for all outstanding writes to hit the output stream before returning
      while (!pendingWrites.isEmpty()) {
        pendingWrites.remove().get();
      }
    } finally {
      pool.shutdownNow();
      writer.shutdownNow();
    }
  }

  /** Reads exactly {@code length} bytes starting at {@code offset} from the remote file. */
  private static byte[] readChunk(File remoteFile, long offset, int length) throws IOException {
    byte[] buf = new byte[length];
    int pos = 0;
    while (pos < length) {
      int read = remoteFile.read(buf, offset + pos, pos, length - pos);
      if (read < 0) {
        throw new IOException("Unexpected EOF at offset " + (offset + pos));
      }
      pos += read;
    }
    return buf;
  }

  /**
   * Downloads a file via a sequential input stream with a single-buffer prefetch (used for small
   * files or when the remote size is unknown).
   */
  private void downloadStreamed(File remoteFile, OutputStream out, String name) throws Exception {
    try (InputStream in = remoteFile.getInputStream()) {
      byte[] bufferA = new byte[BUFFER_SIZE];
      byte[] bufferB = new byte[BUFFER_SIZE];
      long bytesSinceLastDiskCheck = 0;

      ExecutorService prefetchExecutor = Executors.newSingleThreadExecutor();
      try {
        int read = in.read(bufferA);
        while (read != -1) {
          final byte[] nextBuf = bufferB;
          Future<Integer> prefetchFuture = prefetchExecutor.submit(() -> in.read(nextBuf));

          out.write(bufferA, 0, read);

          bytesSinceLastDiskCheck += read;
          if (bytesSinceLastDiskCheck >= DISK_CHECK_INTERVAL) {
            if (!hasEnoughDiskSpace()) {
              prefetchFuture.cancel(true);
              LogUtils.e(TAG, "Download aborted \u2013 disk space low: " + name);
              throw new InsufficientDiskSpaceException("Insufficient disk space");
            }
            bytesSinceLastDiskCheck = 0;
          }

          read = prefetchFuture.get();

          byte[] tmp = bufferA;
          bufferA = bufferB;
          bufferB = tmp;
        }
      } finally {
        prefetchExecutor.shutdownNow();
      }
    }
  }

  /**
   * Sets the last modified time on an already open remote file handle. Avoids an extra SMB file
   * open when a handle is already available (e.g. right after an upload).
   */
  private void setLastModifiedOnHandle(File remoteFile, String remotePath, long timeMillis) {
    try {
      FileBasicInformation currentInfo = remoteFile.getFileInformation().getBasicInformation();
      FileTime newTime = FileTime.ofEpochMillis(timeMillis);
      FileBasicInformation newInfo =
          new FileBasicInformation(
              currentInfo.getCreationTime(),
              currentInfo.getLastAccessTime(),
              newTime,
              currentInfo.getChangeTime(),
              currentInfo.getFileAttributes());
      remoteFile.setFileInformation(newInfo);
      LogUtils.d(TAG, "Set remote lastWriteTime for: " + remotePath);
    } catch (Exception e) {
      LogUtils.w(
          TAG, "Could not set last modified time for: " + remotePath + ": " + e.getMessage());
    }
  }

  /** Gets the size of a remote file in bytes. */
  private long getRemoteFileSize(DiskShare share, String remotePath) {
    try (File remoteFile =
        share.openFile(
            remotePath,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null)) {
      return remoteFile.getFileInformation().getStandardInformation().getEndOfFile();
    } catch (Exception e) {
      LogUtils.w(TAG, "Could not get file size for: " + remotePath);
      return -1;
    }
  }

  /** Ensures a remote directory exists, creating it recursively if needed. */
  private void ensureRemoteDirectoryExists(DiskShare share, String path) {
    if (path == null || path.isEmpty()) return;

    String smbPath = path.replace('/', '\\');
    String[] parts = smbPath.split("\\\\");
    StringBuilder current = new StringBuilder();

    for (String part : parts) {
      if (part.isEmpty()) continue;
      if (current.length() > 0) current.append("\\");
      current.append(part);

      String dirPath = current.toString();
      try {
        if (!share.folderExists(dirPath)) {
          share.mkdir(dirPath);
          LogUtils.d(TAG, "Created remote directory: " + dirPath);
        }
      } catch (Exception e) {
        LogUtils.w(TAG, "Could not create remote directory " + dirPath + ": " + e.getMessage());
      }
    }
  }

  /**
   * Creates an SMBClient configured based on the connection's encryption and signing settings.
   * Mirrors the configuration logic from {@code SmbRepositoryImpl.getClientFor()}.
   */
  private SMBClient createSmbClient(SmbConnection connection) {
    boolean encrypt = false;
    boolean sign = false;
    boolean async = false;
    try {
      encrypt = connection.isEncryptData();
      sign = connection.isSigningRequired();
      async = connection.isAsyncTransport();
    } catch (Throwable ignored) {
    }

    SmbConfig.Builder builder =
        SmbConfig.builder().withEncryptData(encrypt).withSigningRequired(sign);

    // Performance (issue #21): larger read/write/transact buffers reduce the number of SMB2
    // READ/WRITE packets per transfer, which lowers per-packet overhead (especially with SMB3
    // encryption). The effective size is still capped by what the server negotiates.
    try {
      builder
          .withReadBufferSize(TRANSFER_BUFFER_SIZE)
          .withWriteBufferSize(TRANSFER_BUFFER_SIZE)
          .withTransactBufferSize(TRANSFER_BUFFER_SIZE);
    } catch (Throwable ignored) {
      /* keep SMBJ defaults if the running SMBJ version rejects these values */
    }

    if (async) {
      builder.withTransportLayerFactory(new AsyncDirectTcpTransportFactory<>());
      LogUtils.i(TAG, "Using AsyncDirectTcpTransport for improved sync performance");
    }

    try {
      builder.withDialects(
          com.hierynomus.mssmb2.SMB2Dialect.SMB_3_1_1,
          com.hierynomus.mssmb2.SMB2Dialect.SMB_3_0_2,
          com.hierynomus.mssmb2.SMB2Dialect.SMB_3_0,
          com.hierynomus.mssmb2.SMB2Dialect.SMB_2_1,
          com.hierynomus.mssmb2.SMB2Dialect.SMB_2_0_2);
    } catch (Throwable ignored) {
      /* older SMBJ versions do not support these dialects */
    }

    LogUtils.d(
        TAG, "SMB client config: encrypt=" + encrypt + ", sign=" + sign + ", async=" + async);
    return new SMBClient(builder.build());
  }

  /** Creates an AuthenticationContext from the connection details. */
  private AuthenticationContext createAuthContext(SmbConnection connection) {
    String domain = connection.getDomain() != null ? connection.getDomain() : "";
    String username = connection.getUsername() != null ? connection.getUsername() : "";
    String password = connection.getPassword() != null ? connection.getPassword() : "";

    if (username.isEmpty() && password.isEmpty()) {
      return AuthenticationContext.guest();
    }

    return new AuthenticationContext(username, password.toCharArray(), domain);
  }

  /** Extracts the share name from the full share path. */
  private String getShareName(String sharePath) {
    if (sharePath == null || sharePath.isEmpty()) return "";

    String path = sharePath;
    while (path.startsWith("/") || path.startsWith("\\")) {
      path = path.substring(1);
    }

    int slashIndex = path.indexOf('/');
    if (slashIndex == -1) slashIndex = path.indexOf('\\');

    return slashIndex == -1 ? path : path.substring(0, slashIndex);
  }

  /** Name of the per-task trash folder created at the sync root. Excluded from sync traversal. */
  static final String TRASH_DIR_NAME = ".sambalite-trash";

  /**
   * Returns {@code true} if a top-level entry with the given name should be skipped during sync /
   * sweep traversal because it is the trash folder maintained by mirror mode at the root.
   */
  static boolean isTrashAtRoot(String name, String relPath) {
    return TRASH_DIR_NAME.equals(name) && (relPath == null || relPath.isEmpty());
  }

  /** Joins two SMB path segments using backslash separator. */
  private static String smbJoin(String base, String child) {
    String b = (base == null ? "" : base.replace('/', '\\').trim());
    String c = (child == null ? "" : child.replace('/', '\\').trim());
    if (b.isEmpty() || b.equals("\\")) b = "";
    if (b.endsWith("\\")) b = b.substring(0, b.length() - 1);
    if (c.startsWith("\\")) c = c.substring(1);
    return b.isEmpty() ? c : (b + "\\" + c);
  }

  /** Returns a MIME type for a file based on its extension. */
  String getMimeType(String fileName) {
    return de.schliweb.sambalite.util.MimeTypeUtils.getMimeType(fileName);
  }

  /**
   * Checks whether the device has enough free disk space to continue sync operations.
   *
   * @return true if available space >= MIN_DISK_SPACE_BYTES, false otherwise
   */
  private boolean hasEnoughDiskSpace() {
    boolean internalOk =
        EnhancedFileUtils.hasEnoughDiskSpace(getApplicationContext().getFilesDir());
    boolean externalOk =
        EnhancedFileUtils.hasEnoughDiskSpace(android.os.Environment.getExternalStorageDirectory());
    return internalOk && externalOk;
  }

  /**
   * Exception thrown when disk space is insufficient, used to propagate the abort through recursive
   * sync calls.
   */
  private static class InsufficientDiskSpaceException extends IOException {
    InsufficientDiskSpaceException(String message) {
      super(message);
    }
  }

  /** Checks if the device is currently connected to a WiFi network. */
  boolean isConnectedToWifi() {
    ConnectivityManager cm =
        (ConnectivityManager)
            getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
    if (cm == null) return false;

    Network activeNetwork = cm.getActiveNetwork();
    if (activeNetwork == null) return false;

    NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
    if (caps == null) return false;

    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
  }

  // ---------------------------------------------------------------------------
  // Mirror mode
  // ---------------------------------------------------------------------------

  /**
   * Mirror sweep for {@link SyncDirection#LOCAL_TO_REMOTE}: removes remote entries that are tracked
   * in the DB but no longer present in the local source tree. When {@code useTrash} is {@code
   * true}, entries are moved into a per-run trash folder ({@code .sambalite-trash/<ts>/<relPath>})
   * at the root of the remote sync target instead of being deleted.
   */
  private void runMirrorSweepLocalSource(
      DiskShare share,
      DocumentFile localFolder,
      String remotePath,
      String rootUri,
      boolean useTrash) {
    Set<String> localPaths = new HashSet<>();
    boolean complete;
    try {
      collectLocalPaths(localFolder, "", localPaths);
      complete = true;
    } catch (Exception e) {
      LogUtils.w(TAG, "[MIRROR] Local listing failed: " + e.getMessage());
      complete = false;
    }

    // Lazy: only compute the trash base path on first actual deletion to avoid creating
    // empty timestamp folders on every sync run.
    final String[] trashBaseHolder = new String[1];

    MirrorSweeper sweeper = new MirrorSweeper(syncStateStore);
    MirrorSweeper.Result result =
        sweeper.sweep(
            rootUri,
            localPaths,
            complete,
            relativePath -> {
              String src = smbJoin(remotePath, relativePath);
              if (useTrash) {
                if (trashBaseHolder[0] == null) {
                  trashBaseHolder[0] =
                      smbJoin(remotePath, TRASH_DIR_NAME + "/" + System.currentTimeMillis());
                }
                String trashBase = trashBaseHolder[0];
                boolean trashed = trashRemoteEntry(share, src, trashBase, relativePath);
                if (trashed) {
                  actionLog.log(SyncActionLog.Action.MIRROR_TRASHED, relativePath);
                  return true;
                }
                // Fall through to delete if rename to trash failed
                LogUtils.w(
                    TAG, "[MIRROR] Trash move failed, falling back to delete for: " + relativePath);
              }
              boolean ok = deleteRemoteEntry(share, src);
              if (ok) actionLog.log(SyncActionLog.Action.MIRROR_DELETED, relativePath);
              return ok;
            });
    logMirrorResult("LOCAL→REMOTE", result);

    // Additional cleanup: untracked empty remote directories that no longer exist in the local
    // source. The DB-driven sweep above only knows about files (saveRemoteState is called per
    // file), so directories created remotely during sync (e.g. via ensureRemoteDirectoryExists)
    // are never tracked. After a local rename of a parent folder, the file deletions empty out
    // the old remote folder tree but leave the directory shells behind. We remove them here,
    // guarded by the same safeguards (complete listing, non-empty source) as MirrorSweeper.
    if (complete && !localPaths.isEmpty() && !isStopped()) {
      String trashBaseForDirs = trashBaseHolder[0];
      pruneUntrackedEmptyRemoteDirs(share, remotePath, "", localPaths, useTrash, trashBaseForDirs);
    }
  }

  /**
   * Recursively prunes remote directories that (a) are not present in {@code localPaths} and (b)
   * are empty after their (already swept) children have been removed. Directories that are still
   * present on the local source or that contain non-mirrored content are left alone, so manually
   * created files inside an otherwise-tracked directory are never lost.
   *
   * <p>When {@code useTrash} is {@code true} and a {@code trashBase} is available, the empty
   * directory is moved into the trash; otherwise it is deleted. The {@code .sambalite-trash}
   * directory itself is never pruned.
   */
  private void pruneUntrackedEmptyRemoteDirs(
      DiskShare share,
      String remoteBase,
      String prefix,
      Set<String> localPaths,
      boolean useTrash,
      String trashBase) {
    if (isStopped()) return;
    List<FileIdBothDirectoryInformation> entries;
    try {
      entries = share.list(remoteBase);
    } catch (Exception e) {
      LogUtils.w(
          TAG,
          "[MIRROR] Could not list remote dir for empty-dir prune: "
              + remoteBase
              + ": "
              + e.getMessage());
      return;
    }
    for (FileIdBothDirectoryInformation entry : entries) {
      if (isStopped()) return;
      String name = entry.getFileName();
      if (name == null || ".".equals(name) || "..".equals(name)) continue;
      boolean isDir =
          (entry.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
      if (!isDir) continue;
      if (isTrashAtRoot(name, prefix)) continue;
      String rel = prefix.isEmpty() ? name : prefix + "/" + name;
      String childAbs = smbJoin(remoteBase, name);
      // Recurse first so deepest empty dirs are cleaned before their parents.
      pruneUntrackedEmptyRemoteDirs(share, childAbs, rel, localPaths, useTrash, trashBase);
      if (localPaths.contains(rel)) continue; // still exists on source
      // Check if the directory is empty after child cleanup.
      try {
        List<FileIdBothDirectoryInformation> remaining = share.list(childAbs);
        boolean hasContent = false;
        for (FileIdBothDirectoryInformation r : remaining) {
          String rn = r.getFileName();
          if (rn == null || ".".equals(rn) || "..".equals(rn)) continue;
          hasContent = true;
          break;
        }
        if (hasContent) continue;
      } catch (Exception e) {
        LogUtils.w(
            TAG,
            "[MIRROR] Could not list remote dir for empty check: "
                + childAbs
                + ": "
                + e.getMessage());
        continue;
      }
      boolean removed = false;
      // If the per-run trash already contains a directory at this relative path, the
      // file-level sweep already created it while moving children there. Trying to move
      // the now-empty source directory on top would fail. Skip the trash attempt in that
      // case and fall through to a plain delete of the empty shell.
      boolean trashTargetExists = false;
      if (useTrash && trashBase != null) {
        trashTargetExists = remoteTrashTargetDirExists(share, trashBase, rel);
      }
      if (useTrash && trashBase != null && !trashTargetExists) {
        if (trashRemoteEntry(share, childAbs, trashBase, rel)) {
          removed = true;
          actionLog.log(SyncActionLog.Action.MIRROR_TRASHED, rel);
          LogUtils.d(TAG, "[MIRROR] Trashed empty remote dir: " + rel);
        }
      }
      if (!removed) {
        try {
          share.rmdir(childAbs, false);
          if (!share.folderExists(childAbs)) {
            actionLog.log(SyncActionLog.Action.MIRROR_DELETED, rel);
            LogUtils.d(TAG, "[MIRROR] Pruned empty remote dir: " + rel);
          }
        } catch (Exception e) {
          LogUtils.w(
              TAG, "[MIRROR] Failed to prune empty remote dir " + rel + ": " + e.getMessage());
        }
      }
    }
  }

  /**
   * Returns {@code true} iff the remote per-run trash base already contains a directory at the
   * given relative path. Used to avoid attempting a SMB rename whose destination is guaranteed to
   * fail (e.g. when the file-level sweep already populated that target dir).
   */
  private boolean remoteTrashTargetDirExists(
      DiskShare share, String trashBase, String relativePath) {
    if (trashBase == null || relativePath == null || relativePath.isEmpty()) return false;
    try {
      String dest = smbJoin(trashBase, relativePath);
      return share.folderExists(dest);
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Mirror sweep for {@link SyncDirection#REMOTE_TO_LOCAL}: removes local entries that are tracked
   * in the DB but no longer present on the remote source. When {@code useTrash} is {@code true},
   * entries are moved into a per-run local trash folder ({@code .sambalite-trash/<ts>/<relPath>})
   * directly under the local sync root via SAF; if the move is not supported by the SAF provider,
   * the code falls back to a regular delete.
   */
  private void runMirrorSweepRemoteSource(
      DiskShare share,
      DocumentFile localFolder,
      String remotePath,
      String rootUri,
      boolean useTrash) {
    Set<String> remotePaths = new HashSet<>();
    boolean complete;
    try {
      collectRemotePaths(share, remotePath, "", remotePaths);
      complete = true;
    } catch (Exception e) {
      LogUtils.w(TAG, "[MIRROR] Remote listing failed: " + e.getMessage());
      complete = false;
    }

    // Lazy: only create the local trash base directory on first actual deletion to avoid
    // littering the sync root with empty timestamp folders on every sync run.
    final DocumentFile[] trashBaseHolder = new DocumentFile[1];
    final boolean[] trashBaseInitialized = new boolean[1];

    MirrorSweeper sweeper = new MirrorSweeper(syncStateStore);
    MirrorSweeper.Result result =
        sweeper.sweep(
            rootUri,
            remotePaths,
            complete,
            relativePath -> {
              if (useTrash) {
                if (!trashBaseInitialized[0]) {
                  trashBaseHolder[0] =
                      ensureLocalTrashBase(localFolder, System.currentTimeMillis());
                  trashBaseInitialized[0] = true;
                }
                DocumentFile trashBase = trashBaseHolder[0];
                if (trashBase != null) {
                  boolean trashed = trashLocalEntry(localFolder, trashBase, relativePath);
                  if (trashed) {
                    actionLog.log(SyncActionLog.Action.MIRROR_TRASHED, relativePath);
                    return true;
                  }
                  LogUtils.w(
                      TAG,
                      "[MIRROR] Local trash move failed, falling back to delete for: "
                          + relativePath);
                }
              }
              boolean ok = deleteLocalEntry(localFolder, relativePath);
              if (ok) actionLog.log(SyncActionLog.Action.MIRROR_DELETED, relativePath);
              return ok;
            });
    logMirrorResult("REMOTE→LOCAL", result);

    // Additional cleanup: untracked empty directories that no longer exist on the remote source.
    // The DB-driven sweep above only knows about files (saveRemoteState is called per file),
    // so directories created locally during sync (e.g. via createDirectory) are never tracked.
    // After a remote rename of a parent folder, the file deletions empty out the old folder tree
    // but leave the directory shells behind. We remove them here, guarded by the same safeguards
    // (complete listing, non-empty source) as MirrorSweeper.
    if (complete && !remotePaths.isEmpty() && !isStopped()) {
      DocumentFile trashBaseForDirs = trashBaseHolder[0];
      pruneUntrackedEmptyLocalDirs(
          localFolder, localFolder, "", remotePaths, useTrash, trashBaseForDirs);
    }
  }

  /**
   * Recursively prunes local directories that (a) are not present in {@code remotePaths} and (b)
   * are empty after their (already swept) children have been removed. Directories that are still
   * present on the remote source or that contain non-mirrored content are left alone, so manually
   * created files inside an otherwise-tracked directory are never lost.
   *
   * <p>When {@code useTrash} is {@code true} and a {@code trashBase} is available, the empty
   * directory is moved into the trash; otherwise it is deleted. The {@code .sambalite-trash}
   * directory itself is never pruned.
   */
  private void pruneUntrackedEmptyLocalDirs(
      DocumentFile root,
      DocumentFile folder,
      String prefix,
      Set<String> remotePaths,
      boolean useTrash,
      DocumentFile trashBase) {
    if (folder == null || isStopped()) return;
    DocumentFile[] children = folder.listFiles();
    if (children == null) return;
    for (DocumentFile child : children) {
      if (isStopped()) return;
      String name = child.getName();
      if (name == null || name.isEmpty()) continue;
      if (!child.isDirectory()) continue;
      if (isTrashAtRoot(name, prefix)) continue;
      String rel = prefix.isEmpty() ? name : prefix + "/" + name;
      // Recurse first so deepest empty dirs are cleaned before their parents.
      pruneUntrackedEmptyLocalDirs(root, child, rel, remotePaths, useTrash, trashBase);
      if (remotePaths.contains(rel)) continue; // still exists on source
      DocumentFile[] remaining = child.listFiles();
      if (remaining != null && remaining.length > 0) continue; // not empty (untouched content)
      boolean removed = false;
      // If the per-run trash already contains a directory at this relative path, the
      // file-level sweep already created it while moving children there. Trying to move
      // the now-empty source directory on top would fail with "Already exists". Skip the
      // trash attempt in that case and fall through to a plain delete of the empty shell.
      boolean trashTargetExists = false;
      if (useTrash && trashBase != null) {
        trashTargetExists = trashTargetDirExists(trashBase, rel);
      }
      if (useTrash && trashBase != null && !trashTargetExists) {
        if (trashLocalEntry(root, trashBase, rel)) {
          removed = true;
          actionLog.log(SyncActionLog.Action.MIRROR_TRASHED, rel);
          LogUtils.d(TAG, "[MIRROR] Trashed empty local dir: " + rel);
        }
      }
      if (!removed) {
        try {
          if (child.delete()) {
            actionLog.log(SyncActionLog.Action.MIRROR_DELETED, rel);
            LogUtils.d(TAG, "[MIRROR] Pruned empty local dir: " + rel);
          }
        } catch (Exception e) {
          LogUtils.w(
              TAG, "[MIRROR] Failed to prune empty local dir " + rel + ": " + e.getMessage());
        }
      }
    }
  }

  /**
   * Creates {@code .sambalite-trash/<timestamp>/} under the local root via SAF. Returns the
   * timestamp directory (the per-run trash base) or {@code null} on failure.
   */
  private DocumentFile ensureLocalTrashBase(DocumentFile root, long timestamp) {
    try {
      DocumentFile trashRoot = root.findFile(TRASH_DIR_NAME);
      if (trashRoot == null) {
        trashRoot = root.createDirectory(TRASH_DIR_NAME);
      }
      if (trashRoot == null || !trashRoot.isDirectory()) return null;
      String tsName = String.valueOf(timestamp);
      DocumentFile tsDir = trashRoot.findFile(tsName);
      if (tsDir == null) {
        tsDir = trashRoot.createDirectory(tsName);
      }
      return (tsDir != null && tsDir.isDirectory()) ? tsDir : null;
    } catch (Exception e) {
      LogUtils.w(TAG, "[MIRROR] Failed to create local trash base: " + e.getMessage());
      return null;
    }
  }

  /**
   * Moves a local SAF entry into the per-run trash folder via {@code DocumentsContract
   * .moveDocument}. Returns {@code true} only if the source was actually moved (post-condition:
   * source path no longer resolves). Any failure (unsupported move, security exception, missing
   * parent) is reported as {@code false} so the caller can fall back to a regular delete.
   */
  private boolean trashLocalEntry(DocumentFile root, DocumentFile trashBase, String relativePath) {
    if (trashBase == null) return false;
    DocumentFile entry = resolveLocal(root, relativePath);
    if (entry == null || !entry.exists()) return true; // already gone
    DocumentFile sourceParent = resolveLocalParent(root, relativePath);
    if (sourceParent == null) return false;
    try {
      // Mirror the relative directory structure inside the trash base so multiple entries with
      // the same name from different folders don't collide.
      DocumentFile destParent = trashBase;
      String[] segments = relativePath.replace('\\', '/').split("/");
      for (int i = 0; i < segments.length - 1; i++) {
        String seg = segments[i];
        if (seg.isEmpty()) continue;
        DocumentFile next = destParent.findFile(seg);
        if (next == null) {
          next = destParent.createDirectory(seg);
        }
        if (next == null || !next.isDirectory()) return false;
        destParent = next;
      }
      android.net.Uri movedUri =
          android.provider.DocumentsContract.moveDocument(
              getApplicationContext().getContentResolver(),
              entry.getUri(),
              sourceParent.getUri(),
              destParent.getUri());
      if (movedUri == null) return false;
      // Verify the source is gone — some providers return non-null even on partial failure.
      DocumentFile check = resolveLocal(root, relativePath);
      return check == null || !check.exists();
    } catch (UnsupportedOperationException | SecurityException e) {
      LogUtils.w(
          TAG, "[MIRROR] SAF move not supported for " + relativePath + ": " + e.getMessage());
      return false;
    } catch (Exception e) {
      LogUtils.e(
          TAG, "[MIRROR] Failed to trash local entry " + relativePath + ": " + e.getMessage());
      return false;
    }
  }

  /**
   * Returns {@code true} iff the per-run trash base already contains a directory at the given
   * relative path. Used to avoid attempting a SAF move whose destination is guaranteed to fail with
   * "Already exists" (e.g. when the file-level sweep already populated that target dir).
   */
  private boolean trashTargetDirExists(DocumentFile trashBase, String relativePath) {
    if (trashBase == null || relativePath == null || relativePath.isEmpty()) return false;
    DocumentFile current = trashBase;
    for (String seg : relativePath.replace('\\', '/').split("/")) {
      if (seg.isEmpty()) continue;
      if (current == null) return false;
      current = current.findFile(seg);
      if (current == null) return false;
    }
    return current != null && current.isDirectory();
  }

  private DocumentFile resolveLocalParent(DocumentFile root, String relativePath) {
    if (root == null || relativePath == null || relativePath.isEmpty()) return null;
    String[] segments = relativePath.replace('\\', '/').split("/");
    DocumentFile current = root;
    for (int i = 0; i < segments.length - 1; i++) {
      String seg = segments[i];
      if (seg.isEmpty()) continue;
      if (current == null) return null;
      current = current.findFile(seg);
    }
    return current;
  }

  private void logMirrorResult(String label, MirrorSweeper.Result result) {
    if (result.skipped) {
      LogUtils.i(TAG, "[MIRROR] " + label + " sweep skipped: " + result.reason);
      actionLog.log(SyncActionLog.Action.SKIPPED, "(mirror " + label + ")", result.reason);
    } else if (result.aborted) {
      LogUtils.e(
          TAG,
          "[MIRROR] "
              + label
              + " sweep aborted: "
              + result.candidates
              + " of "
              + result.tracked
              + " tracked entries would be deleted (threshold)");
      actionLog.log(
          SyncActionLog.Action.MIRROR_ABORTED,
          "(mirror " + label + ")",
          "would delete "
              + result.candidates
              + " of "
              + result.tracked
              + " tracked entries (threshold)");
    } else {
      LogUtils.i(
          TAG,
          "[MIRROR] "
              + label
              + " sweep done: tracked="
              + result.tracked
              + " candidates="
              + result.candidates
              + " deleted="
              + result.deleted
              + " failed="
              + result.failed);
    }
  }

  /**
   * Moves a remote SMB entry into a per-run trash folder. Returns {@code true} if the move
   * succeeded and the source path no longer exists.
   */
  private boolean trashRemoteEntry(
      DiskShare share, String srcAbsPath, String trashBaseAbs, String relativePath) {
    try {
      // Ensure parent directory inside trash exists. smbJoin produces backslash-separated
      // paths, so search for backslash (with forward-slash as a defensive fallback).
      String destAbs = smbJoin(trashBaseAbs, relativePath);
      String destParent = destAbs;
      int slash = destAbs.lastIndexOf('\\');
      if (slash < 0) slash = destAbs.lastIndexOf('/');
      if (slash > 0) {
        destParent = destAbs.substring(0, slash);
      } else {
        // No separator found: the entry sits directly under the trash base; nothing to create.
        destParent = trashBaseAbs;
      }
      ensureRemoteDirectoryExists(share, destParent);

      // SMBJ rename works like a move within the same share
      if (share.fileExists(srcAbsPath)) {
        try (File f =
            share.openFile(
                srcAbsPath,
                EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null)) {
          f.rename(destAbs);
        }
        return !share.fileExists(srcAbsPath);
      } else if (share.folderExists(srcAbsPath)) {
        try (com.hierynomus.smbj.share.Directory d =
            share.openDirectory(
                srcAbsPath,
                EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null)) {
          d.rename(destAbs);
        }
        return !share.folderExists(srcAbsPath);
      } else {
        return true; // already gone
      }
    } catch (Exception e) {
      LogUtils.e(
          TAG, "[MIRROR] Failed to trash remote entry: " + srcAbsPath + ": " + e.getMessage());
      return false;
    }
  }

  /**
   * Recursively collects relative paths (forward-slash separated) of all files and directories
   * under {@code folder}. Directory paths are included so that empty directories can also be
   * mirrored away on the target side.
   */
  private void collectLocalPaths(DocumentFile folder, String prefix, Set<String> out) {
    DocumentFile[] children = folder.listFiles();
    if (children == null) return;
    for (DocumentFile child : children) {
      if (isStopped()) return;
      String name = child.getName();
      if (name == null || name.isEmpty()) continue;
      if (isTrashAtRoot(name, prefix)) continue;
      String rel = prefix.isEmpty() ? name : prefix + "/" + name;
      out.add(rel);
      if (child.isDirectory()) {
        collectLocalPaths(child, rel, out);
      }
    }
  }

  /**
   * Recursively collects relative paths (forward-slash separated) of all files and directories
   * under the given remote SMB path.
   */
  private void collectRemotePaths(
      DiskShare share, String remoteBase, String prefix, Set<String> out) {
    List<FileIdBothDirectoryInformation> entries = share.list(remoteBase);
    for (FileIdBothDirectoryInformation entry : entries) {
      if (isStopped()) return;
      String name = entry.getFileName();
      if (".".equals(name) || "..".equals(name)) continue;
      if (isTrashAtRoot(name, prefix)) continue;
      boolean isDir =
          (entry.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
      String rel = prefix.isEmpty() ? name : prefix + "/" + name;
      out.add(rel);
      if (isDir) {
        collectRemotePaths(share, smbJoin(remoteBase, name), rel, out);
      }
    }
  }

  /**
   * Deletes a remote SMB entry (file or directory) for the given absolute remote path. Returns
   * {@code true} if the entry is gone after the call.
   */
  private boolean deleteRemoteEntry(DiskShare share, String remoteAbsPath) {
    try {
      if (share.folderExists(remoteAbsPath)) {
        // SMBJ deletes empty directories only — clean recursively first.
        try {
          List<FileIdBothDirectoryInformation> children = share.list(remoteAbsPath);
          for (FileIdBothDirectoryInformation child : children) {
            String n = child.getFileName();
            if (".".equals(n) || "..".equals(n)) continue;
            deleteRemoteEntry(share, smbJoin(remoteAbsPath, n));
          }
        } catch (Exception listEx) {
          LogUtils.w(
              TAG,
              "[MIRROR] Could not list remote dir for recursive delete: "
                  + remoteAbsPath
                  + ": "
                  + listEx.getMessage());
        }
        share.rmdir(remoteAbsPath, false);
        return !share.folderExists(remoteAbsPath);
      } else if (share.fileExists(remoteAbsPath)) {
        share.rm(remoteAbsPath);
        return !share.fileExists(remoteAbsPath);
      } else {
        // Already gone — desired post-condition holds.
        return true;
      }
    } catch (Exception e) {
      LogUtils.e(
          TAG, "[MIRROR] Failed to delete remote entry: " + remoteAbsPath + ": " + e.getMessage());
      return false;
    }
  }

  /**
   * Deletes a local SAF entry (file or directory) at the given relative path under {@code root}.
   * Returns {@code true} if the entry is gone after the call.
   */
  private boolean deleteLocalEntry(DocumentFile root, String relativePath) {
    DocumentFile entry = resolveLocal(root, relativePath);
    if (entry == null || !entry.exists()) {
      return true; // already gone
    }
    try {
      return entry.delete();
    } catch (Exception e) {
      LogUtils.e(
          TAG, "[MIRROR] Failed to delete local entry: " + relativePath + ": " + e.getMessage());
      return false;
    }
  }

  private DocumentFile resolveLocal(DocumentFile root, String relativePath) {
    if (root == null || relativePath == null || relativePath.isEmpty()) return null;
    String[] segments = relativePath.replace('\\', '/').split("/");
    DocumentFile current = root;
    for (String seg : segments) {
      if (seg.isEmpty()) continue;
      if (current == null) return null;
      current = current.findFile(seg);
    }
    return current;
  }
}
