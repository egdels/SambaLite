/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.transfer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.StatFs;
import android.provider.OpenableColumns;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileBasicInformation;
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
import de.schliweb.sambalite.transfer.db.PendingTransfer;
import de.schliweb.sambalite.transfer.db.PendingTransferDao;
import de.schliweb.sambalite.transfer.db.TransferDatabase;
import de.schliweb.sambalite.util.LogUtils;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * WorkManager Worker that processes the persistent transfer queue. Runs as a foreground service
 * with notification, supports Connection-Reuse (one SMB session per connection), direct SAF→SMB
 * streaming (no temp copy), and resume after app kill via persisted progress in Room DB.
 */
public class TransferWorker extends Worker {

  private static final String TAG = "TransferWorker";
  private static final int BUFFER_SIZE = 262144;
  private static final long PROGRESS_SAVE_INTERVAL = 2 * 1024 * 1024;

  /** Interval between disk space checks during downloads (10 MB). */
  private static final long DISK_CHECK_INTERVAL = 10 * 1024 * 1024;

  private static final String CHANNEL_ID = "TRANSFER_QUEUE";
  private static final int NOTIFICATION_ID = 2002;
  private static final long CLEANUP_AGE_DAYS = 7;

  /** Minimum free disk space required to continue transfers (10 MB). */
  private static final long MIN_DISK_SPACE_BYTES = 10 * 1024 * 1024;

  /** Minimum interval between notification updates to avoid Android rate-limiting. */
  private static final long NOTIFICATION_MIN_INTERVAL_MS = 1000;

  /** Broadcast action sent after a transfer completes successfully. */
  public static final String ACTION_TRANSFER_COMPLETED = "de.schliweb.sambalite.TRANSFER_COMPLETED";

  public static final String EXTRA_DISPLAY_NAME = "display_name";
  public static final String EXTRA_REMOTE_PATH = "remote_path";
  public static final String EXTRA_TRANSFER_TYPE = "transfer_type";

  private NotificationManager notificationManager;
  private long lastNotificationUpdateMs;

  public TransferWorker(@NonNull Context context, @NonNull WorkerParameters params) {
    super(context, params);
  }

  @NonNull
  @Override
  public ForegroundInfo getForegroundInfo() {
    return createForegroundInfo("Transfer-Queue wird verarbeitet…", "");
  }

  private ForegroundInfo createForegroundInfo(String title, String content) {
    Context context = getApplicationContext();

    NotificationChannel channel =
        new NotificationChannel(CHANNEL_ID, "Transfer Queue", NotificationManager.IMPORTANCE_LOW);
    channel.setDescription("Shows the status of file transfer operations");
    channel.setShowBadge(false);
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (manager != null) {
      manager.createNotificationChannel(channel);
    }

    Notification notification = buildNotification(title, content);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      return new ForegroundInfo(
          NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    }
    return new ForegroundInfo(NOTIFICATION_ID, notification);
  }

  private Notification buildNotification(String title, String content) {
    return new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(content)
        .setSmallIcon(de.schliweb.sambalite.R.drawable.ic_notification)
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build();
  }

  private void updateNotification(String title, String content) {
    long now = System.currentTimeMillis();
    if (now - lastNotificationUpdateMs < NOTIFICATION_MIN_INTERVAL_MS) {
      return; // Rate-limit to avoid Android shedding notifications
    }
    lastNotificationUpdateMs = now;
    if (notificationManager == null) {
      notificationManager = getApplicationContext().getSystemService(NotificationManager.class);
    }
    if (notificationManager != null) {
      notificationManager.notify(NOTIFICATION_ID, buildNotification(title, content));
    }
  }

  @NonNull
  @Override
  public Result doWork() {
    LogUtils.i(
        TAG,
        "Starting transfer queue processing (workId="
            + getId()
            + ", runAttempt="
            + getRunAttemptCount()
            + ")");

    try {
      setForegroundAsync(createForegroundInfo("Transfer-Queue wird verarbeitet…", ""));
    } catch (Exception e) {
      LogUtils.w(TAG, "Could not promote to foreground: " + e.getMessage());
    }

    // Abort early if disk is (nearly) full to avoid filling up the device
    if (!hasEnoughDiskSpace()) {
      LogUtils.e(TAG, "Insufficient disk space \u2013 aborting transfer queue");
      return Result.failure();
    }

    PendingTransferDao dao =
        TransferDatabase.getInstance(getApplicationContext()).pendingTransferDao();

    // Crash recovery: reset any ACTIVE transfers back to PENDING
    int reset = dao.resetActiveToRetry(System.currentTimeMillis());
    if (reset > 0) {
      LogUtils.i(TAG, "Crash recovery: reset " + reset + " ACTIVE transfers to PENDING");
    }

    ConnectionRepositoryImpl connectionRepository =
        new ConnectionRepositoryImpl(getApplicationContext());

    boolean anyFailure = false;

    // Loop: process all pending transfers, then re-check for newly added ones.
    // This is needed because we use KEEP policy — the worker is NOT replaced when
    // new transfers are enqueued while it's already running.
    // Track connections that failed with a connectivity error so we don't
    // tight-loop retrying them within the same worker run.
    Set<String> failedConnectionIds = new HashSet<>();

    while (true) {
      if (isStopped()) {
        LogUtils.i(TAG, "Worker stopped, will resume on next run");
        return Result.retry();
      }

      // Group pending work by connection for Connection-Reuse
      Map<String, SmbConnection> connectionCache = new HashMap<>();
      List<String> connectionIds = dao.getConnectionsWithPendingWork();

      // Remove connections that already failed with a connectivity error
      // in this worker run — they will be retried on the next WorkManager run.
      connectionIds.removeAll(failedConnectionIds);

      if (connectionIds.isEmpty()) {
        break; // No more work — exit loop
      }

      // Pre-load all needed connections
      for (SmbConnection conn : connectionRepository.getAllConnections()) {
        connectionCache.put(conn.getId(), conn);
      }

      for (String connectionId : connectionIds) {
        if (isStopped()) {
          LogUtils.i(TAG, "Worker stopped, will resume on next run");
          return Result.retry();
        }

        SmbConnection connection = connectionCache.get(connectionId);
        if (connection == null) {
          LogUtils.e(TAG, "Connection not found: " + connectionId);
          List<PendingTransfer> orphaned = dao.getPendingForConnection(connectionId);
          for (PendingTransfer t : orphaned) {
            dao.markFailed(
                t.id, "Connection not found: " + connectionId, System.currentTimeMillis());
          }
          anyFailure = true;
          continue;
        }

        boolean batchOk = processConnectionBatch(dao, connection);
        if (!batchOk) {
          anyFailure = true;
          // Remember this connection so we don't retry it in the same
          // worker run — avoids tight-looping on network errors.
          failedConnectionIds.add(connectionId);
        }
      }
    }

    cleanup(dao);

    if (anyFailure) {
      LogUtils.w(TAG, "Some transfers failed, requesting retry");
      return Result.retry();
    }

    LogUtils.i(TAG, "All transfers completed successfully");
    return Result.success();
  }

  /**
   * Processes all pending transfers for a single connection using one shared SMB session.
   *
   * @return true if all transfers succeeded, false if any failed
   */
  private boolean processConnectionBatch(PendingTransferDao dao, SmbConnection connection) {
    List<PendingTransfer> transfers = dao.getPendingForConnection(connection.getId());
    if (transfers.isEmpty()) return true;

    LogUtils.i(
        TAG, "Processing " + transfers.size() + " transfers for connection: " + connection.getId());

    boolean allSuccess = true;

    try (SMBClient client = createSmbClient(connection);
        Connection conn = client.connect(connection.getServer())) {

      // Log negotiated SMB protocol details
      try {
        com.hierynomus.smbj.connection.NegotiatedProtocol negotiated = conn.getNegotiatedProtocol();
        LogUtils.i(
            TAG,
            "SMB negotiated: dialect="
                + negotiated.getDialect()
                + ", maxReadSize="
                + negotiated.getMaxReadSize()
                + ", maxWriteSize="
                + negotiated.getMaxWriteSize()
                + ", maxTransactSize="
                + negotiated.getMaxTransactSize());
      } catch (Exception e) {
        LogUtils.w(TAG, "Could not log SMB negotiated protocol: " + e.getMessage());
      }

      AuthenticationContext authContext = createAuthContext(connection);
      try (Session session = conn.authenticate(authContext)) {
        String shareName = getShareName(connection.getShare());
        LogUtils.i(
            TAG,
            "SMB connection: server="
                + connection.getServer()
                + ", share="
                + connection.getShare()
                + ", shareName="
                + shareName
                + ", user="
                + connection.getUsername());
        try (DiskShare share = (DiskShare) session.connectShare(shareName)) {

          for (PendingTransfer transfer : transfers) {
            if (isStopped()) {
              LogUtils.i(TAG, "Worker stopped during batch processing");
              return false;
            }

            boolean success = processTransfer(dao, share, transfer);
            if (!success) {
              allSuccess = false;
              // If the share/connection is broken, stop processing this batch
              // so remaining transfers get a fresh connection on retry.
              if (!share.isConnected()) {
                LogUtils.w(
                    TAG,
                    "DiskShare disconnected, aborting batch for connection: " + connection.getId());
                break;
              }
            }
          }
        }
      }
    } catch (Exception e) {
      LogUtils.e(TAG, "Connection error for " + connection.getId() + ": " + e.getMessage());
      for (PendingTransfer t : transfers) {
        if ("ACTIVE".equals(t.status) || "PENDING".equals(t.status)) {
          dao.markFailed(t.id, "Connection error: " + e.getMessage(), System.currentTimeMillis());
        }
      }
      return false;
    }

    return allSuccess;
  }

  /**
   * Processes a single transfer (upload or download).
   *
   * @return true if the transfer completed successfully
   */
  private boolean processTransfer(
      PendingTransferDao dao, DiskShare share, PendingTransfer transfer) {
    dao.updateStatus(transfer.id, "ACTIVE", System.currentTimeMillis());
    updateNotification("Übertragung läuft…", transfer.displayName);

    try {
      if ("UPLOAD".equals(transfer.transferType)) {
        processUpload(dao, share, transfer);
      } else {
        processDownload(dao, share, transfer);
      }

      // If the transfer was cancelled by the user during processing, don't mark as completed
      if (isTransferCancelled(dao, transfer.id)) {
        LogUtils.i(TAG, "Transfer was cancelled by user: " + transfer.displayName);
        return false;
      }

      dao.updateStatus(transfer.id, "COMPLETED", System.currentTimeMillis());
      LogUtils.i(TAG, "Transfer completed: " + transfer.displayName);
      cleanupSharedTextSourceFile(transfer);
      sendTransferCompletedBroadcast(transfer);
      return true;
    } catch (Exception e) {
      LogUtils.e(TAG, "Transfer failed: " + transfer.displayName + " - " + e.getMessage());
      // Don't overwrite CANCELLED status with FAILED
      if (!isTransferCancelled(dao, transfer.id)) {
        dao.markFailed(transfer.id, e.getMessage(), System.currentTimeMillis());
      }
      return false;
    }
  }

  /**
   * Uploads a file directly from SAF URI to SMB share without intermediate temp copy. Supports
   * resume by skipping already-transferred bytes.
   */
  private void processUpload(PendingTransferDao dao, DiskShare share, PendingTransfer transfer)
      throws Exception {
    ContentResolver resolver = getApplicationContext().getContentResolver();
    Uri sourceUri = Uri.parse(transfer.localUri);

    long localSize = getLocalFileSize(resolver, sourceUri);
    LogUtils.i(
        TAG,
        "Upload starting: file="
            + transfer.displayName
            + ", remotePath="
            + transfer.remotePath
            + ", localUri="
            + transfer.localUri
            + ", connectionId="
            + transfer.connectionId
            + ", fileSize="
            + transfer.fileSize
            + ", localFileSize="
            + localSize);

    long uploadStartTime = System.currentTimeMillis();

    try (InputStream rawIn = resolver.openInputStream(sourceUri)) {
      if (rawIn == null) {
        throw new IOException("Cannot open input stream for: " + transfer.displayName);
      }
      InputStream in = new BufferedInputStream(rawIn, BUFFER_SIZE);

      // Ensure parent directories exist
      String parentPath = getParentPath(transfer.remotePath);
      LogUtils.i(TAG, "Upload parentPath=" + parentPath + ", remotePath=" + transfer.remotePath);
      ensureRemoteDirectoryExists(share, parentPath);

      // Determine resume offset from actual remote file size (not DB progress)
      // to avoid gaps when progress was not saved before interruption.
      // NOTE: Resume does not take effect after a crash or device reboot because
      // resetActiveToRetry() in PendingTransferDao resets bytes_transferred to 0.
      // This is intentional for the current phase — uploads always restart from the
      // beginning after an unclean interruption.
      long resumeOffset = 0;
      if (transfer.bytesTransferred > 0) {
        long remoteSize = getRemoteFileSize(share, transfer.remotePath);
        if (remoteSize > 0) {
          resumeOffset = remoteSize;
          LogUtils.i(
              TAG,
              "Resume: DB progress="
                  + transfer.bytesTransferred
                  + ", actual remote size="
                  + remoteSize
                  + ", using remote size as offset");
        }
      }
      boolean resuming = resumeOffset > 0;

      try (File remoteFile =
          share.openFile(
              transfer.remotePath,
              EnumSet.of(AccessMask.GENERIC_WRITE),
              EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
              SMB2ShareAccess.ALL,
              resuming
                  ? SMB2CreateDisposition.FILE_OPEN_IF
                  : SMB2CreateDisposition.FILE_OVERWRITE_IF,
              null)) {

        OutputStream out = remoteFile.getOutputStream(resuming);

        if (resuming) {
          long skipped = skipFully(in, resumeOffset);
          if (skipped != resumeOffset) {
            LogUtils.w(
                TAG,
                "Could not skip to resume position ("
                    + skipped
                    + "/"
                    + resumeOffset
                    + "), restarting upload");
            transfer.bytesTransferred = 0;
            out.close();
            // Re-open for overwrite
            out =
                share
                    .openFile(
                        transfer.remotePath,
                        EnumSet.of(AccessMask.GENERIC_WRITE),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OVERWRITE_IF,
                        null)
                    .getOutputStream();
          } else {
            transfer.bytesTransferred = resumeOffset;
            LogUtils.i(
                TAG, "Resuming upload at byte " + resumeOffset + ": " + transfer.displayName);
          }
        }

        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        long bytesSinceLastSave = 0;

        while ((read = in.read(buffer)) != -1) {
          if (isStopped() || isTransferCancelled(dao, transfer.id)) {
            dao.updateProgress(transfer.id, transfer.bytesTransferred, System.currentTimeMillis());
            if (isTransferCancelled(dao, transfer.id)) {
              LogUtils.i(
                  TAG,
                  "Upload cancelled by user at byte "
                      + transfer.bytesTransferred
                      + ": "
                      + transfer.displayName);
              return;
            }
            // Only reset to PENDING if not already CANCELLED by user
            dao.updateStatusIfActive(transfer.id, "PENDING", System.currentTimeMillis());
            LogUtils.i(
                TAG,
                "Upload paused at byte " + transfer.bytesTransferred + ": " + transfer.displayName);
            return;
          }

          out.write(buffer, 0, read);
          transfer.bytesTransferred += read;
          bytesSinceLastSave += read;

          if (bytesSinceLastSave >= PROGRESS_SAVE_INTERVAL) {
            dao.updateProgress(transfer.id, transfer.bytesTransferred, System.currentTimeMillis());
            updateTransferNotification(transfer);
            bytesSinceLastSave = 0;
          }
        }

        out.flush();
        long uploadDurationMs = System.currentTimeMillis() - uploadStartTime;
        double uploadDurationSec = uploadDurationMs / 1000.0;
        double throughputMBs =
            uploadDurationSec > 0
                ? (transfer.bytesTransferred / (1024.0 * 1024.0)) / uploadDurationSec
                : 0;
        LogUtils.i(
            TAG,
            "Upload stream completed: bytesTransferred="
                + transfer.bytesTransferred
                + ", file="
                + transfer.displayName
                + ", duration="
                + String.format("%.1f", uploadDurationSec)
                + "s, throughput="
                + String.format("%.2f", throughputMBs)
                + " MB/s");
      }
    }

    // Integrity check: compare actual remote file size against expected local file size
    long remoteSize = getRemoteFileSize(share, transfer.remotePath);
    localSize = getLocalFileSize(resolver, sourceUri);
    LogUtils.i(
        TAG,
        "Upload integrity check: remoteSize="
            + remoteSize
            + ", localSize="
            + localSize
            + ", expectedFileSize="
            + transfer.fileSize
            + ", file="
            + transfer.displayName);
    if (remoteSize >= 0 && localSize >= 0 && remoteSize != localSize) {
      throw new IOException(
          "Integrity check failed: remoteSize="
              + remoteSize
              + " localSize="
              + localSize
              + " bytes");
    }

    // Preserve local file timestamp on remote file
    setRemoteFileTimestamp(share, transfer, resolver);

    // Final progress update
    dao.updateProgress(transfer.id, transfer.bytesTransferred, System.currentTimeMillis());
  }

  /**
   * Sets the last modified time of the remote file to match the local source file's timestamp. This
   * preserves the original file timestamp on the server, matching the behavior of the legacy upload
   * and the FolderSyncWorker.
   */
  private void setRemoteFileTimestamp(
      DiskShare share, PendingTransfer transfer, ContentResolver resolver) {
    try {
      Uri sourceUri = Uri.parse(transfer.localUri);
      android.database.Cursor cursor = resolver.query(sourceUri, null, null, null, null);
      long lastModified = 0;
      if (cursor != null) {
        try {
          if (cursor.moveToFirst()) {
            int idx =
                cursor.getColumnIndex(
                    android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED);
            if (idx >= 0) {
              lastModified = cursor.getLong(idx);
            }
          }
        } finally {
          cursor.close();
        }
      }

      if (lastModified > 0) {
        setRemoteFileLastModified(share, transfer.remotePath, lastModified);
        LogUtils.i(
            TAG,
            "Preserved timestamp on remote file: "
                + transfer.displayName
                + " ("
                + lastModified
                + ")");
      } else {
        LogUtils.w(TAG, "Could not read local file timestamp for: " + transfer.displayName);
      }
    } catch (Exception e) {
      LogUtils.w(
          TAG, "Failed to preserve timestamp for: " + transfer.displayName + ": " + e.getMessage());
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
      com.hierynomus.msdtyp.FileTime newTime =
          com.hierynomus.msdtyp.FileTime.ofEpochMillis(timeMillis);
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

  /** Downloads a file from SMB share directly to the local SAF URI. */
  private void processDownload(PendingTransferDao dao, DiskShare share, PendingTransfer transfer)
      throws Exception {
    ContentResolver resolver = getApplicationContext().getContentResolver();
    Uri targetUri = Uri.parse(transfer.localUri);

    LogUtils.i(
        TAG,
        "Download starting: file="
            + transfer.displayName
            + ", remotePath="
            + transfer.remotePath
            + ", localUri="
            + transfer.localUri
            + ", connectionId="
            + transfer.connectionId
            + ", fileSize="
            + transfer.fileSize);

    // Get remote file size if not already known
    long remoteSize = getRemoteFileSize(share, transfer.remotePath);
    LogUtils.i(
        TAG,
        "Remote file size for "
            + transfer.displayName
            + ": "
            + remoteSize
            + " bytes (known fileSize="
            + transfer.fileSize
            + ")");
    if (transfer.fileSize <= 0 && remoteSize > 0) {
      transfer.fileSize = remoteSize;
      dao.updateProgress(transfer.id, transfer.bytesTransferred, System.currentTimeMillis());
    }

    // Always start fresh
    transfer.bytesTransferred = 0;
    long downloadStartTime = System.currentTimeMillis();

    try (File remoteFile =
        share.openFile(
            transfer.remotePath,
            EnumSet.of(AccessMask.GENERIC_READ),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null)) {

      InputStream in = remoteFile.getInputStream();
      OutputStream rawOut = resolver.openOutputStream(targetUri, "w");
      if (rawOut == null) {
        throw new IOException("Cannot open SAF output stream for: " + transfer.displayName);
      }
      OutputStream out = new BufferedOutputStream(rawOut, BUFFER_SIZE);

      try {
        byte[] bufferA = new byte[BUFFER_SIZE];
        byte[] bufferB = new byte[BUFFER_SIZE];
        long bytesSinceLastSave = 0;
        long bytesSinceLastDiskCheck = 0;

        final InputStream finalIn = in;
        ExecutorService prefetchExecutor = Executors.newSingleThreadExecutor();

        try {
          int read = finalIn.read(bufferA);
          if (read == -1) {
            // Empty file
          } else {
            while (read != -1) {
              final byte[] nextBuf = bufferB;
              Future<Integer> prefetchFuture = prefetchExecutor.submit(() -> finalIn.read(nextBuf));

              out.write(bufferA, 0, read);

              transfer.bytesTransferred += read;
              bytesSinceLastSave += read;
              bytesSinceLastDiskCheck += read;

              if (bytesSinceLastSave >= PROGRESS_SAVE_INTERVAL) {
                if (bytesSinceLastDiskCheck >= DISK_CHECK_INTERVAL) {
                  if (!hasEnoughDiskSpace()) {
                    dao.updateProgress(
                        transfer.id, transfer.bytesTransferred, System.currentTimeMillis());
                    dao.updateStatusIfActive(transfer.id, "PENDING", System.currentTimeMillis());
                    LogUtils.e(
                        TAG,
                        "Download aborted \u2013 disk space low at byte "
                            + transfer.bytesTransferred
                            + ": "
                            + transfer.displayName);
                    throw new IOException("Insufficient disk space");
                  }
                  bytesSinceLastDiskCheck = 0;
                }
                dao.updateProgress(
                    transfer.id, transfer.bytesTransferred, System.currentTimeMillis());
                updateTransferNotification(transfer);
                bytesSinceLastSave = 0;
              }

              if (isStopped() || isTransferCancelled(dao, transfer.id)) {
                prefetchFuture.cancel(true);
                dao.updateProgress(
                    transfer.id, transfer.bytesTransferred, System.currentTimeMillis());
                if (isTransferCancelled(dao, transfer.id)) {
                  LogUtils.i(
                      TAG,
                      "Download cancelled by user at byte "
                          + transfer.bytesTransferred
                          + ": "
                          + transfer.displayName);
                  return;
                }
                dao.updateStatusIfActive(transfer.id, "PENDING", System.currentTimeMillis());
                LogUtils.i(
                    TAG,
                    "Download paused at byte "
                        + transfer.bytesTransferred
                        + ": "
                        + transfer.displayName);
                return;
              }

              read = prefetchFuture.get();

              byte[] tmp = bufferA;
              bufferA = bufferB;
              bufferB = tmp;
            }
          }
        } finally {
          prefetchExecutor.shutdownNow();
        }

        out.flush();
      } finally {
        out.close();
        in.close();
      }
    }

    long downloadDurationMs = System.currentTimeMillis() - downloadStartTime;
    double downloadDurationSec = downloadDurationMs / 1000.0;
    double throughputMBs =
        downloadDurationSec > 0
            ? (transfer.bytesTransferred / (1024.0 * 1024.0)) / downloadDurationSec
            : 0;
    LogUtils.i(
        TAG,
        "Download complete: "
            + transfer.bytesTransferred
            + " bytes, file="
            + transfer.displayName
            + ", duration="
            + String.format("%.1f", downloadDurationSec)
            + "s, throughput="
            + String.format("%.2f", throughputMBs)
            + " MB/s");

    // Integrity check: compare actual local file size against remote file size
    long localSize = getLocalFileSize(resolver, targetUri);
    remoteSize = getRemoteFileSize(share, transfer.remotePath);
    LogUtils.i(
        TAG,
        "Download integrity check: localSize="
            + localSize
            + ", remoteSize="
            + remoteSize
            + ", file="
            + transfer.displayName);
    if (localSize >= 0 && remoteSize >= 0 && localSize != remoteSize) {
      throw new IOException(
          "Integrity check failed: localSize="
              + localSize
              + " remoteSize="
              + remoteSize
              + " bytes");
    }
    if (localSize < 0 || remoteSize < 0) {
      LogUtils.w(
          TAG,
          "Download integrity check skipped: localSize="
              + localSize
              + ", remoteSize="
              + remoteSize
              + ", file="
              + transfer.displayName);
    }

    // Final progress update
    dao.updateProgress(transfer.id, transfer.bytesTransferred, System.currentTimeMillis());
  }

  /**
   * Checks whether the given transfer has been cancelled by the user (via queue UI). This allows
   * individual transfer cancellation while the worker continues processing others.
   */
  private boolean isTransferCancelled(PendingTransferDao dao, long transferId) {
    String status = dao.getStatus(transferId);
    return status == null || "CANCELLED".equals(status);
  }

  private void updateTransferNotification(PendingTransfer transfer) {
    String content = transfer.displayName;
    if (transfer.fileSize > 0) {
      int pct = (int) (transfer.bytesTransferred * 100 / transfer.fileSize);
      content = transfer.displayName + " • " + pct + "%";
    }
    updateNotification("Übertragung läuft…", content);
  }

  /** Skips exactly {@code n} bytes from the input stream. Returns the actual number skipped. */
  private long skipFully(InputStream in, long n) throws IOException {
    long remaining = n;
    byte[] skipBuffer = new byte[BUFFER_SIZE];
    while (remaining > 0) {
      int toRead = (int) Math.min(skipBuffer.length, remaining);
      int read = in.read(skipBuffer, 0, toRead);
      if (read < 0) break;
      remaining -= read;
    }
    return n - remaining;
  }

  /** Returns the parent path of a remote file path, or empty string for root-level files. */
  private String getParentPath(String remotePath) {
    if (remotePath == null) return "";
    String normalized = remotePath.replace('/', '\\');
    int lastSep = normalized.lastIndexOf('\\');
    return lastSep > 0 ? normalized.substring(0, lastSep) : "";
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

  /** Returns the local file size via SAF ContentResolver, or -1 on error. */
  private long getLocalFileSize(ContentResolver resolver, Uri uri) {
    try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
        if (idx >= 0 && !cursor.isNull(idx)) {
          return cursor.getLong(idx);
        }
      }
    } catch (Exception e) {
      LogUtils.w(TAG, "Could not query local file size: " + e.getMessage());
    }
    return -1;
  }

  /** Returns the remote file size, or -1 on error. */
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
      LogUtils.w(TAG, "Could not get remote file size for: " + remotePath);
      return -1;
    }
  }

  /**
   * Deletes the source file if it resides in the shared_text cache directory (i.e. it was created
   * by ShareReceiverActivity for a text share). Called after successful upload.
   */
  private void cleanupSharedTextSourceFile(PendingTransfer transfer) {
    if (transfer.localUri == null) return;
    try {
      Uri uri = Uri.parse(transfer.localUri);
      if (!"file".equals(uri.getScheme())) return;
      java.io.File sourceFile = new java.io.File(uri.getPath());
      java.io.File sharedTextDir =
          new java.io.File(getApplicationContext().getCacheDir(), "shared_text");
      if (sourceFile.exists()
          && sourceFile.getParentFile() != null
          && sourceFile.getParentFile().equals(sharedTextDir)) {
        if (sourceFile.delete()) {
          LogUtils.d(TAG, "Deleted shared text cache file: " + sourceFile.getName());
        }
      }
    } catch (Exception e) {
      LogUtils.w(TAG, "Failed to clean up shared text source file: " + e.getMessage());
    }
  }

  private void sendTransferCompletedBroadcast(PendingTransfer transfer) {
    Intent intent = new Intent(ACTION_TRANSFER_COMPLETED);
    intent.setPackage(getApplicationContext().getPackageName());
    intent.putExtra(EXTRA_DISPLAY_NAME, transfer.displayName);
    intent.putExtra(EXTRA_REMOTE_PATH, transfer.remotePath);
    intent.putExtra(EXTRA_TRANSFER_TYPE, transfer.transferType);
    getApplicationContext().sendBroadcast(intent);
  }

  private void cleanup(PendingTransferDao dao) {
    try {
      long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(CLEANUP_AGE_DAYS);
      int deleted = dao.cleanupOld(cutoff);
      if (deleted > 0) {
        LogUtils.i(TAG, "Cleaned up " + deleted + " old transfer entries");
      }
    } catch (Exception e) {
      LogUtils.w(TAG, "Cleanup failed: " + e.getMessage());
    }
  }

  /**
   * Creates an SMBClient configured based on the connection's encryption and signing settings.
   * Mirrors the configuration logic from {@code FolderSyncWorker.createSmbClient()}.
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
      LogUtils.i(TAG, "Using AsyncDirectTcpTransport for improved transfer performance");
    }

    try {
      builder.withDialects(
          com.hierynomus.mssmb2.SMB2Dialect.SMB_3_1_1,
          com.hierynomus.mssmb2.SMB2Dialect.SMB_3_0_2,
          com.hierynomus.mssmb2.SMB2Dialect.SMB_3_0,
          com.hierynomus.mssmb2.SMB2Dialect.SMB_2_1,
          com.hierynomus.mssmb2.SMB2Dialect.SMB_2_0_2);
    } catch (Throwable ignored) {
    }

    LogUtils.d(
        TAG, "SMB client config: encrypt=" + encrypt + ", sign=" + sign + ", async=" + async);
    return new SMBClient(builder.build());
  }

  /**
   * Checks whether the device has enough free disk space to continue transfers.
   *
   * @return true if available space >= MIN_DISK_SPACE_BYTES, false otherwise
   */
  private boolean hasEnoughDiskSpace() {
    try {
      java.io.File dataDir = getApplicationContext().getFilesDir();
      if (dataDir != null) {
        StatFs stat = new StatFs(dataDir.getPath());
        long available = stat.getAvailableBytes();
        if (available < MIN_DISK_SPACE_BYTES) {
          LogUtils.w(
              TAG,
              "Low disk space: "
                  + available
                  + " bytes available (min: "
                  + MIN_DISK_SPACE_BYTES
                  + ")");
          return false;
        }
      }
    } catch (Exception e) {
      LogUtils.w(TAG, "Could not check disk space: " + e.getMessage());
    }
    return true;
  }

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
}
