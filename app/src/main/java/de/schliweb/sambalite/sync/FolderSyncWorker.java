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
import de.schliweb.sambalite.util.LogUtils;
import de.schliweb.sambalite.util.StorageCapabilityResolver;
import de.schliweb.sambalite.util.TimestampCapability;
import de.schliweb.sambalite.util.TimestampUtils;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WorkManager Worker that performs folder synchronization in the background. Iterates over all
 * enabled SyncConfigs and syncs each folder pair.
 */
public class FolderSyncWorker extends Worker {

  private static final String TAG = "FolderSyncWorker";
  private static final int BUFFER_SIZE = 65536;
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
        new NotificationChannel(SYNC_CHANNEL_ID, "Folder Sync", NotificationManager.IMPORTANCE_LOW);
    channel.setDescription("Shows the status of folder sync operations");
    channel.setShowBadge(false);
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (manager != null) {
      manager.createNotificationChannel(channel);
    }

    Notification notification =
        new NotificationCompat.Builder(context, SYNC_CHANNEL_ID)
            .setContentTitle("Synchronisierung läuft…")
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

      try {
        syncFolder(config, connection);
        syncRepository.updateLastSyncTimestamp(config.getId(), System.currentTimeMillis());
        LogUtils.i(TAG, "Sync completed for config: " + config.getId());
      } catch (Exception e) {
        LogUtils.e(TAG, "Sync failed for config " + config.getId() + ": " + e.getMessage());
        anyFailure = true;
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

          switch (config.getDirection()) {
            case LOCAL_TO_REMOTE:
              syncLocalToRemote(share, localFolder, remotePath, rootUri, "");
              break;
            case REMOTE_TO_LOCAL:
              syncRemoteToLocal(share, localFolder, remotePath, rootUri, "");
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

    for (DocumentFile localFile : localFilesArray) {
      if (isStopped()) return;

      String name = localFile.getName();
      if (name == null) continue;

      String remoteFilePath = smbJoin(remotePath, name);

      if (localFile.isDirectory()) {
        ensureRemoteDirectoryExists(share, remoteFilePath);
        String childRelPath = relPath.isEmpty() ? name : relPath + "/" + name;
        syncLocalToRemote(share, localFile, remoteFilePath, rootUri, childRelPath);
      } else {
        try {
          long localModified = localFile.lastModified();
          boolean remoteExists = share.fileExists(remoteFilePath);

          String fileRelPath = relPath.isEmpty() ? name : relPath + "/" + name;

          if (!remoteExists) {
            uploadFile(share, localFile, remoteFilePath);
            actionLog.log(SyncActionLog.Action.UPLOADED, name);
            long remoteModified = getRemoteFileLastModified(share, remoteFilePath);
            long remoteSize = getRemoteFileSize(share, remoteFilePath);
            syncStateStore.saveRemoteState(
                rootUri, fileRelPath, remoteFilePath, remoteSize, remoteModified, false);
          } else {
            long remoteModified = getRemoteFileLastModified(share, remoteFilePath);
            long remoteSize = getRemoteFileSize(share, remoteFilePath);
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
              uploadFile(share, localFile, remoteFilePath);
              actionLog.log(SyncActionLog.Action.UPLOADED, name);
              remoteModified = getRemoteFileLastModified(share, remoteFilePath);
              remoteSize = getRemoteFileSize(share, remoteFilePath);
              syncStateStore.saveRemoteState(
                  rootUri, fileRelPath, remoteFilePath, remoteSize, remoteModified, false);
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

      // Cache local files to avoid expensive findFile calls which can cause duplicates in SAF
      DocumentFile[] localFilesArray = localFolder.listFiles();
      Map<String, DocumentFile> localFilesMap = new HashMap<>();
      for (DocumentFile f : localFilesArray) {
        String n = f.getName();
        if (n != null) {
          localFilesMap.put(n, f);
        }
      }

      for (FileIdBothDirectoryInformation remoteFile : remoteFiles) {
        if (isStopped()) return;

        String name = remoteFile.getFileName();
        if (".".equals(name) || "..".equals(name)) continue;

        String remoteFilePath = smbJoin(remotePath, name);
        boolean isDirectory =
            (remoteFile.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue())
                != 0;

        if (isDirectory) {
          DocumentFile localSubDir = localFilesMap.get(name);
          if (localSubDir == null) {
            localSubDir = localFolder.createDirectory(name);
            actionLog.log(SyncActionLog.Action.CREATED_DIR, name);
          }
          if (localSubDir != null) {
            String childRelPath = relPath.isEmpty() ? name : relPath + "/" + name;
            syncRemoteToLocal(share, localSubDir, remoteFilePath, rootUri, childRelPath);
          }
        } else {
          try {
            long remoteModified = remoteFile.getLastWriteTime().toEpochMillis();
            long remoteSize = getRemoteFileSize(share, remoteFilePath);
            String fileRelPath = relPath.isEmpty() ? name : relPath + "/" + name;
            DocumentFile localFile = localFilesMap.get(name);

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
          } catch (Exception e) {
            LogUtils.e(TAG, "Error syncing remote file " + name + ": " + e.getMessage());
            actionLog.log(SyncActionLog.Action.ERROR, name, e.getMessage());
          }
        }
      }

      // Cleanup: Remove DB entries for files that no longer exist remotely
      Set<String> remoteFileNames = new HashSet<>();
      for (FileIdBothDirectoryInformation rf : remoteFiles) {
        String rfName = rf.getFileName();
        if (!".".equals(rfName) && !"..".equals(rfName)) {
          remoteFileNames.add(rfName);
        }
      }

      List<FileSyncState> storedStates = syncStateStore.getAllForRoot(rootUri);
      for (FileSyncState stored : storedStates) {
        // Only check entries that belong to the current directory level
        String storedRelPath = stored.relativePath;
        String expectedPrefix = relPath.isEmpty() ? "" : relPath + "/";
        if (!storedRelPath.startsWith(expectedPrefix)) continue;

        String remainder = storedRelPath.substring(expectedPrefix.length());
        // Only direct children (no further '/' in remainder)
        if (remainder.contains("/")) continue;

        if (!remoteFileNames.contains(remainder)) {
          syncStateStore.deleteState(rootUri, storedRelPath);
          LogUtils.i(
              TAG, "[TIMESTAMP] Cleaned up DB entry for remotely deleted file: " + storedRelPath);
          actionLog.log(SyncActionLog.Action.DELETED, remainder, "remote file no longer exists");
        }
      }
    } catch (Exception e) {
      LogUtils.e(TAG, "Error listing remote directory " + remotePath + ": " + e.getMessage());
    }
  }

  /** Uploads a local DocumentFile to the remote SMB share. */
  private void uploadFile(DiskShare share, DocumentFile localFile, String remotePath)
      throws Exception {
    LogUtils.d(TAG, "Uploading: " + localFile.getName() + " -> " + remotePath);

    try (File remoteFile =
            share.openFile(
                remotePath,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null);
        InputStream is =
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

    // Integrity check: compare actual remote file size against local file size
    long remoteSize = getRemoteFileSize(share, remotePath);
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

    // Set remote file's lastWriteTime to match local file's lastModified
    // to prevent re-uploading on next sync cycle
    setRemoteFileLastModified(share, remotePath, localFile.lastModified());

    LogUtils.d(TAG, "Upload completed: " + localFile.getName());
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

      try (InputStream is = remoteFile.getInputStream();
          OutputStream os =
              getApplicationContext().getContentResolver().openOutputStream(localFile.getUri())) {

        if (os == null) {
          throw new Exception("Could not open output stream for: " + localFile.getUri());
        }

        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        while ((bytesRead = is.read(buffer)) != -1) {
          os.write(buffer, 0, bytesRead);
        }
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

  /** Sets the last modified time of a remote file. */
  private void setRemoteFileLastModified(DiskShare share, String remotePath, long timeMillis) {
    try (File remoteFile =
        share.openFile(
            remotePath,
            EnumSet.of(AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_WRITE_ATTRIBUTES),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null)) {
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

  /** Gets the last modified time of a remote file in epoch millis. */
  private long getRemoteFileLastModified(DiskShare share, String remotePath) {
    try (File remoteFile =
        share.openFile(
            remotePath,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null)) {
      return remoteFile
          .getFileInformation()
          .getBasicInformation()
          .getLastWriteTime()
          .toEpochMillis();
    } catch (Exception e) {
      LogUtils.w(TAG, "Could not get last modified time for: " + remotePath);
      return 0;
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

    if (!encrypt && !sign && !async) {
      return new SMBClient();
    }

    SmbConfig.Builder builder =
        SmbConfig.builder().withEncryptData(encrypt).withSigningRequired(sign);

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
}
