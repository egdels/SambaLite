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

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;
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
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.repository.ConnectionRepositoryImpl;
import de.schliweb.sambalite.util.LogUtils;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WorkManager Worker that performs folder synchronization in the background. Iterates over all
 * enabled SyncConfigs and syncs each folder pair.
 */
public class FolderSyncWorker extends Worker {

  private static final String TAG = "FolderSyncWorker";
  private static final int BUFFER_SIZE = 65536;
  public static final String KEY_SYNC_CONFIG_ID = "sync_config_id";
  private SyncActionLog actionLog;

  public FolderSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
    super(context, params);
    this.actionLog = new SyncActionLog(context);
  }

  @NonNull
  @Override
  public Result doWork() {
    LogUtils.i(TAG, "Starting folder sync work");

    SyncRepository syncRepository = new SyncRepository(getApplicationContext());
    ConnectionRepositoryImpl connectionRepository =
        new ConnectionRepositoryImpl(getApplicationContext());

    // Check if a specific config ID was requested
    String specificConfigId = getInputData().getString(KEY_SYNC_CONFIG_ID);

    List<SyncConfig> configs;
    if (specificConfigId != null) {
      configs = syncRepository.getAllEnabledConfigs();
      configs.removeIf(c -> !c.getId().equals(specificConfigId));
      LogUtils.i(TAG, "Syncing specific config: " + specificConfigId);
    } else {
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
        return Result.success();
      }

      // For periodic sync (no specific config), respect individual intervals
      if (specificConfigId == null) {
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

    try (SMBClient client = new SMBClient();
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

          switch (config.getDirection()) {
            case LOCAL_TO_REMOTE:
              syncLocalToRemote(share, localFolder, remotePath);
              break;
            case REMOTE_TO_LOCAL:
              syncRemoteToLocal(share, localFolder, remotePath);
              break;
            case BIDIRECTIONAL:
              syncLocalToRemote(share, localFolder, remotePath);
              syncRemoteToLocal(share, localFolder, remotePath);
              break;
          }
        }
      }
    }
  }

  /** Syncs local files to remote. Uploads files that are newer locally or don't exist remotely. */
  private void syncLocalToRemote(DiskShare share, DocumentFile localFolder, String remotePath) {
    if (isStopped()) return;

    DocumentFile[] localFiles = localFolder.listFiles();
    for (DocumentFile localFile : localFiles) {
      if (isStopped()) return;

      String name = localFile.getName();
      if (name == null) continue;

      String remoteFilePath = smbJoin(remotePath, name);

      if (localFile.isDirectory()) {
        ensureRemoteDirectoryExists(share, remoteFilePath);
        syncLocalToRemote(share, localFile, remoteFilePath);
      } else {
        try {
          long localModified = localFile.lastModified();
          boolean remoteExists = share.fileExists(remoteFilePath);

          if (!remoteExists) {
            uploadFile(share, localFile, remoteFilePath);
            actionLog.log(SyncActionLog.Action.UPLOADED, name);
          } else {
            long remoteModified = getRemoteFileLastModified(share, remoteFilePath);
            if (localModified > remoteModified) {
              // Also compare file sizes to avoid re-uploading when
              // setRemoteFileLastModified failed (e.g. ACCESS_DENIED)
              long remoteSize = getRemoteFileSize(share, remoteFilePath);
              long localSize = localFile.length();
              if (localSize != remoteSize) {
                uploadFile(share, localFile, remoteFilePath);
                actionLog.log(SyncActionLog.Action.UPLOADED, name);
              } else {
                LogUtils.d(TAG, "Skipping upload (same size): " + name);
                actionLog.log(SyncActionLog.Action.SKIPPED, name, "same size");
              }
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
  private void syncRemoteToLocal(DiskShare share, DocumentFile localFolder, String remotePath) {
    if (isStopped()) return;

    try {
      List<FileIdBothDirectoryInformation> remoteFiles = share.list(remotePath);

      for (FileIdBothDirectoryInformation remoteFile : remoteFiles) {
        if (isStopped()) return;

        String name = remoteFile.getFileName();
        if (".".equals(name) || "..".equals(name)) continue;

        String remoteFilePath = smbJoin(remotePath, name);
        boolean isDirectory =
            (remoteFile.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue())
                != 0;

        if (isDirectory) {
          DocumentFile localSubDir = localFolder.findFile(name);
          if (localSubDir == null) {
            localSubDir = localFolder.createDirectory(name);
            actionLog.log(SyncActionLog.Action.CREATED_DIR, name);
          }
          if (localSubDir != null) {
            syncRemoteToLocal(share, localSubDir, remoteFilePath);
          }
        } else {
          try {
            long remoteModified = remoteFile.getLastWriteTime().toEpochMillis();
            DocumentFile localFile = localFolder.findFile(name);

            if (localFile == null) {
              String mimeType = getMimeType(name);
              DocumentFile newFile = localFolder.createFile(mimeType, name);
              if (newFile != null) {
                downloadFile(share, remoteFilePath, newFile);
                actionLog.log(SyncActionLog.Action.DOWNLOADED, name);
              }
            } else {
              long localModified = localFile.lastModified();
              if (remoteModified > localModified) {
                // Also compare file sizes to avoid re-downloading when
                // setRemoteFileLastModified failed (e.g. ACCESS_DENIED)
                long remoteSize = getRemoteFileSize(share, remoteFilePath);
                long localSize = localFile.length();
                if (localSize != remoteSize) {
                  downloadFile(share, remoteFilePath, localFile);
                  actionLog.log(SyncActionLog.Action.DOWNLOADED, name);
                } else {
                  LogUtils.d(TAG, "Skipping download (same size): " + name);
                  actionLog.log(SyncActionLog.Action.SKIPPED, name, "same size");
                }
              }
            }
          } catch (Exception e) {
            LogUtils.e(TAG, "Error syncing remote file " + name + ": " + e.getMessage());
            actionLog.log(SyncActionLog.Action.ERROR, name, e.getMessage());
          }
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

    // Set remote file's lastWriteTime to match local file's lastModified
    // to prevent re-uploading on next sync cycle
    setRemoteFileLastModified(share, remotePath, localFile.lastModified());

    LogUtils.d(TAG, "Upload completed: " + localFile.getName());
  }

  /** Downloads a remote file to a local DocumentFile. */
  private void downloadFile(DiskShare share, String remotePath, DocumentFile localFile)
      throws Exception {
    LogUtils.d(TAG, "Downloading: " + remotePath + " -> " + localFile.getName());

    try (File remoteFile =
            share.openFile(
                remotePath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null);
        InputStream is = remoteFile.getInputStream();
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

    LogUtils.d(TAG, "Download completed: " + localFile.getName());
  }

  /** Sets the last modified time of a remote file. */
  private void setRemoteFileLastModified(DiskShare share, String remotePath, long timeMillis) {
    try (File remoteFile =
        share.openFile(
            remotePath,
            EnumSet.of(AccessMask.GENERIC_WRITE),
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
