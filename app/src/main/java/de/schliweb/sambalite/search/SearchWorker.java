/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.search;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.repository.ConnectionRepositoryImpl;
import de.schliweb.sambalite.search.db.SearchDatabase;
import de.schliweb.sambalite.search.db.SearchResult;
import de.schliweb.sambalite.search.db.SearchResultDao;
import de.schliweb.sambalite.util.LogUtils;
import java.util.ArrayList;
import java.util.List;

/**
 * WorkManager Worker that performs SMB file search in the background. Writes each result to the
 * Room database immediately so the UI can display live updates via LiveData.
 */
public class SearchWorker extends Worker {

  private static final String TAG = "SearchWorker";
  private static final String CHANNEL_ID = "SEARCH_QUEUE";
  private static final int NOTIFICATION_ID = 2003;
  private static final int BATCH_SIZE = 10;

  public static final String KEY_SEARCH_ID = "search_id";
  public static final String KEY_CONNECTION_ID = "connection_id";
  public static final String KEY_SEARCH_PATH = "search_path";
  public static final String KEY_QUERY = "query";
  public static final String KEY_SEARCH_TYPE = "search_type";
  public static final String KEY_INCLUDE_SUBFOLDERS = "include_subfolders";

  private NotificationManager notificationManager;
  private long lastNotificationUpdateMs;
  private static final long NOTIFICATION_MIN_INTERVAL_MS = 1000;

  public SearchWorker(@NonNull Context context, @NonNull WorkerParameters params) {
    super(context, params);
  }

  @NonNull
  @Override
  public ForegroundInfo getForegroundInfo() {
    return createForegroundInfo("Suche läuft…", "");
  }

  @NonNull
  @Override
  public Result doWork() {
    String searchId = getInputData().getString(KEY_SEARCH_ID);
    String connectionId = getInputData().getString(KEY_CONNECTION_ID);
    String searchPath = getInputData().getString(KEY_SEARCH_PATH);
    String query = getInputData().getString(KEY_QUERY);
    int searchType = getInputData().getInt(KEY_SEARCH_TYPE, 0);
    boolean includeSubfolders = getInputData().getBoolean(KEY_INCLUDE_SUBFOLDERS, true);

    if (searchId == null || connectionId == null || query == null) {
      LogUtils.e(TAG, "Missing required input data");
      return Result.failure();
    }

    LogUtils.i(TAG, "Starting search: query=" + query + ", searchId=" + searchId);

    try {
      setForegroundAsync(createForegroundInfo("Suche: " + query, ""));
    } catch (Exception e) {
      LogUtils.w(TAG, "Could not promote to foreground: " + e.getMessage());
    }

    SearchResultDao dao = SearchDatabase.getInstance(getApplicationContext()).searchResultDao();

    // Clear previous results for this search
    dao.deleteBySearchId(searchId);

    // Find the connection
    SmbConnection connection = findConnection(connectionId);
    if (connection == null) {
      LogUtils.e(TAG, "Connection not found: " + connectionId);
      return Result.failure();
    }

    String path = searchPath != null ? searchPath : "";
    int hitCount = 0;
    List<SearchResult> batch = new ArrayList<>(BATCH_SIZE);

    try {
      SMBClient client = createSmbClient(connection);
      try (client;
          Connection conn = client.connect(connection.getServer())) {
        if (isStopped()) return Result.success();

        AuthenticationContext authContext = createAuthContext(connection);
        try (Session session = conn.authenticate(authContext)) {
          if (isStopped()) return Result.success();

          String shareName = getShareName(connection.getShare());
          try (DiskShare share = (DiskShare) session.connectShare(shareName)) {
            if (isStopped()) return Result.success();

            // Use streaming search from SmbRepositoryImpl pattern
            searchRecursive(
                share,
                path,
                query,
                searchType,
                includeSubfolders,
                dao,
                searchId,
                connectionId,
                batch);

            // Flush remaining batch
            if (!batch.isEmpty()) {
              dao.insertAll(batch);
              batch.clear();
            }

            hitCount = dao.getResultsSync(searchId).size();
          }
        }
      }
    } catch (Exception e) {
      if (isStopped()) {
        LogUtils.i(TAG, "Search cancelled during exception: " + e.getMessage());
        return Result.success();
      }
      LogUtils.e(TAG, "Search failed: " + e.getMessage());
      // Flush any remaining batch before returning
      if (!batch.isEmpty()) {
        try {
          dao.insertAll(batch);
        } catch (Exception ignored) {
        }
      }
      return Result.failure(new Data.Builder().putString("error", e.getMessage()).build());
    }

    LogUtils.i(TAG, "Search completed: " + hitCount + " results for query=" + query);
    return Result.success(new Data.Builder().putInt("hit_count", hitCount).build());
  }

  private void searchRecursive(
      DiskShare share,
      String path,
      String query,
      int searchType,
      boolean includeSubfolders,
      SearchResultDao dao,
      String searchId,
      String connectionId,
      List<SearchResult> batch) {
    if (isStopped()) return;

    try {
      for (com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation info :
          share.list(path)) {
        if (isStopped()) return;

        String name = info.getFileName();
        if (".".equals(name) || "..".equals(name)) continue;

        String nextPath = path.isEmpty() ? name : path + "\\" + name;
        String uiFullPath = path.isEmpty() ? name : path.replace('\\', '/') + "/" + name;

        boolean isDirectory =
            (info.getFileAttributes()
                    & com.hierynomus.msfscc.FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue())
                != 0;

        if (matchesSearchCriteria(name, query, searchType, isDirectory)) {
          SearchResult result = new SearchResult();
          result.searchId = searchId;
          result.name = name;
          result.path = uiFullPath;
          result.type = isDirectory ? "DIRECTORY" : "FILE";
          result.size = info.getEndOfFile();
          result.lastModified = info.getLastWriteTime().toEpochMillis();
          result.connectionId = connectionId;
          result.foundAt = System.currentTimeMillis();

          batch.add(result);
          if (batch.size() >= BATCH_SIZE) {
            dao.insertAll(batch);
            batch.clear();
            updateNotification("Suche: " + query, "Treffer gefunden…");
          }
        }

        if (isDirectory && includeSubfolders && !isStopped()) {
          searchRecursive(
              share,
              nextPath,
              query,
              searchType,
              includeSubfolders,
              dao,
              searchId,
              connectionId,
              batch);
        }
      }
    } catch (Exception e) {
      if (!isStopped()) {
        LogUtils.w(TAG, "Error searching directory " + path + ": " + e.getMessage());
      }
    }
  }

  private boolean matchesSearchCriteria(
      String name, String query, int searchType, boolean isDirectory) {
    return ((searchType == 0)
            || (searchType == 1 && !isDirectory)
            || (searchType == 2 && isDirectory))
        && matchesWildcard(name, query);
  }

  private boolean matchesWildcard(String name, String pattern) {
    String lowerName = name.toLowerCase(java.util.Locale.ROOT);
    String lowerPattern = pattern.toLowerCase(java.util.Locale.ROOT);

    if (!lowerPattern.contains("*") && !lowerPattern.contains("?")) {
      return lowerName.contains(lowerPattern);
    }

    return wildcardMatch(lowerName, lowerPattern, 0, 0);
  }

  private boolean wildcardMatch(String str, String pattern, int si, int pi) {
    while (si < str.length() && pi < pattern.length()) {
      char pc = pattern.charAt(pi);
      if (pc == '*') {
        pi++;
        if (pi == pattern.length()) return true;
        for (int i = si; i <= str.length(); i++) {
          if (wildcardMatch(str, pattern, i, pi)) return true;
        }
        return false;
      } else if (pc == '?' || pc == str.charAt(si)) {
        si++;
        pi++;
      } else {
        return false;
      }
    }
    while (pi < pattern.length() && pattern.charAt(pi) == '*') pi++;
    return si == str.length() && pi == pattern.length();
  }

  private SmbConnection findConnection(String connectionId) {
    ConnectionRepositoryImpl repo = new ConnectionRepositoryImpl(getApplicationContext());
    for (SmbConnection conn : repo.getAllConnections()) {
      if (connectionId.equals(conn.getId())) {
        return conn;
      }
    }
    return null;
  }

  private ForegroundInfo createForegroundInfo(String title, String content) {
    Context context = getApplicationContext();

    NotificationChannel channel =
        new NotificationChannel(CHANNEL_ID, "Dateisuche", NotificationManager.IMPORTANCE_LOW);
    channel.setDescription("Shows the status of file search operations");
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
    if (now - lastNotificationUpdateMs < NOTIFICATION_MIN_INTERVAL_MS) return;
    lastNotificationUpdateMs = now;
    if (notificationManager == null) {
      notificationManager = getApplicationContext().getSystemService(NotificationManager.class);
    }
    if (notificationManager != null) {
      notificationManager.notify(NOTIFICATION_ID, buildNotification(title, content));
    }
  }

  private SMBClient createSmbClient(SmbConnection connection) {
    boolean encrypt = false;
    boolean sign = false;
    try {
      encrypt = connection.isEncryptData();
      sign = connection.isSigningRequired();
    } catch (Throwable ignored) {
    }

    if (!encrypt && !sign) {
      return new SMBClient();
    }

    SmbConfig.Builder builder =
        SmbConfig.builder().withEncryptData(encrypt).withSigningRequired(sign);

    try {
      builder.withDialects(
          com.hierynomus.mssmb2.SMB2Dialect.SMB_3_1_1,
          com.hierynomus.mssmb2.SMB2Dialect.SMB_3_0_2,
          com.hierynomus.mssmb2.SMB2Dialect.SMB_3_0,
          com.hierynomus.mssmb2.SMB2Dialect.SMB_2_1,
          com.hierynomus.mssmb2.SMB2Dialect.SMB_2_0_2);
    } catch (Throwable ignored) {
    }

    return new SMBClient(builder.build());
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
