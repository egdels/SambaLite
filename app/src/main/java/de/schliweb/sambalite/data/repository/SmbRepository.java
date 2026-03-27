/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.data.repository;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import java.io.File;
import java.util.List;

/** Repository interface for SMB operations. */
public interface SmbRepository {

  /**
   * Searches for files and directories matching the query.
   *
   * @param connection The SMB connection to use
   * @param path The path to start the search from (null or empty for root)
   * @param query The search query to match against file names
   * @param searchType The type of items to search for (0=all, 1=files only, 2=folders only)
   * @param includeSubfolders Whether to include subfolders in the search
   * @return A list of SmbFileItem objects that match the query
   * @throws Exception if an error occurs during the search
   */
  @NonNull
  List<SmbFileItem> searchFiles(
      @NonNull SmbConnection connection,
      @NonNull String path,
      @NonNull String query,
      int searchType,
      boolean includeSubfolders)
      throws Exception;

  /**
   * Cancels any ongoing search operation. This method should be called when a user wants to stop a
   * search in progress.
   */
  void cancelSearch();

  /**
   * Returns the current number of search hits found so far during an ongoing search. This can be
   * used to display live progress while the search is still running.
   *
   * @return the number of items found so far
   */
  int getSearchHitCount();

  /**
   * Cancels any ongoing download operation. This method should be called when a user wants to stop
   * a download in progress.
   */
  void cancelDownload();

  /**
   * Cancels any ongoing upload operation. This method should be called when a user wants to stop an
   * upload in progress.
   */
  void cancelUpload();

  /**
   * Tests a connection to an SMB server.
   *
   * @param connection The connection to test
   * @return true if the connection is successful, false otherwise
   * @throws Exception if an error occurs during the connection test
   */
  boolean testConnection(@NonNull SmbConnection connection) throws Exception;

  /**
   * Lists files and directories in the specified path.
   *
   * @param connection The SMB connection to use
   * @param path The path to list (null or empty for root)
   * @return A list of SmbFileItem objects representing files and directories
   * @throws Exception if an error occurs during the listing
   */
  @NonNull
  List<SmbFileItem> listFiles(@NonNull SmbConnection connection, @NonNull String path)
      throws Exception;

  /**
   * Downloads a file from the SMB server.
   *
   * @param connection The SMB connection to use
   * @param remotePath The path to the file on the SMB server
   * @param localFile The local file to save the downloaded file to
   * @throws Exception if an error occurs during the download
   */
  void downloadFile(
      @NonNull SmbConnection connection, @NonNull String remotePath, @NonNull File localFile)
      throws Exception;

  /**
   * Uploads a file to the SMB server.
   *
   * @param connection The SMB connection to use
   * @param localFile The local file to upload
   * @param remotePath The path on the SMB server to upload the file to
   * @throws Exception if an error occurs during the upload
   */
  void uploadFile(
      @NonNull SmbConnection connection, @NonNull File localFile, @NonNull String remotePath)
      throws Exception;

  /**
   * Uploads a file to the SMB server with progress tracking.
   *
   * @param connection The SMB connection to use
   * @param localFile The local file to upload
   * @param remotePath The path on the SMB server to upload the file to
   * @param progressCallback The callback to report progress updates
   * @throws Exception if an error occurs during the upload
   */
  void uploadFileWithProgress(
      @NonNull SmbConnection connection,
      @NonNull File localFile,
      @NonNull String remotePath,
      @Nullable BackgroundSmbManager.ProgressCallback progressCallback)
      throws Exception;

  /**
   * Downloads a file from the SMB server using a local file path.
   *
   * @param connection The SMB connection to use
   * @param remotePath The path to the file on the SMB server
   * @param localFilePath The path to the local file to save the downloaded file to
   * @throws Exception if an error occurs during the download
   */
  void downloadFile(
      @NonNull SmbConnection connection, @NonNull String remotePath, @NonNull String localFilePath)
      throws Exception;

  /**
   * Deletes a file or directory on the SMB server.
   *
   * @param connection The SMB connection to use
   * @param path The path to the file or directory to delete
   * @throws Exception if an error occurs during the deletion
   */
  void deleteFile(@NonNull SmbConnection connection, @NonNull String path) throws Exception;

  /**
   * Renames a file or directory on the SMB server.
   *
   * @param connection The SMB connection to use
   * @param oldPath The current path of the file or directory
   * @param newName The new name for the file or directory
   * @throws Exception if an error occurs during the rename
   */
  void renameFile(
      @NonNull SmbConnection connection, @NonNull String oldPath, @NonNull String newName)
      throws Exception;

  /**
   * Creates a new directory on the SMB server.
   *
   * @param connection The SMB connection to use
   * @param path The path where the new directory should be created
   * @param name The name of the new directory
   * @throws Exception if an error occurs during directory creation
   */
  void createDirectory(
      @NonNull SmbConnection connection, @NonNull String path, @NonNull String name)
      throws Exception;

  /**
   * Checks if a file exists on the SMB server.
   *
   * @param connection The SMB connection to use
   * @param path The path to the file on the SMB server
   * @return true if the file exists, false otherwise
   * @throws Exception if an error occurs during the check
   */
  boolean fileExists(@NonNull SmbConnection connection, @NonNull String path) throws Exception;

  /**
   * Returns the size of a remote file in bytes.
   *
   * @param connection The SMB connection to use
   * @param path The path to the file on the SMB server
   * @return the file size in bytes, or -1 if the file does not exist or an error occurs
   */
  long getRemoteFileSize(@NonNull SmbConnection connection, @NonNull String path);

  /**
   * Lists available shares on the SMB server.
   *
   * @param connection The SMB connection to use (only server, username, password, domain are
   *     needed)
   * @return A list of share names available on the server
   * @throws Exception if an error occurs during the share listing
   */
  @NonNull
  List<String> listShares(@NonNull SmbConnection connection) throws Exception;

  /**
   * Downloads a folder from the SMB server.
   *
   * @param connection The SMB connection to use
   * @param remotePath The path to the folder on the SMB server
   * @param localFolder The local folder to save the downloaded folder to
   * @throws Exception if an error occurs during the download
   */
  void downloadFolder(
      @NonNull SmbConnection connection,
      @NonNull String remotePath,
      @NonNull java.io.File localFolder)
      throws Exception;

  /**
   * Downloads a folder from the SMB server with progress tracking.
   *
   * @param connection The SMB connection to use
   * @param remotePath The path to the folder on the SMB server
   * @param localFolder The local folder to save the downloaded folder to
   * @param progressCallback The callback to report progress updates
   * @throws Exception if an error occurs during the download
   */
  void downloadFolderWithProgress(
      @NonNull SmbConnection connection,
      @NonNull String remotePath,
      @NonNull java.io.File localFolder,
      @Nullable BackgroundSmbManager.MultiFileProgressCallback progressCallback)
      throws Exception;

  /**
   * Downloads a file from the SMB server with progress tracking.
   *
   * @param connection The SMB connection to use
   * @param remotePath The path to the file on the SMB server
   * @param localFile The local file to save the downloaded file to
   * @param progressCallback The callback to report progress updates
   * @throws Exception if an error occurs during the download
   */
  void downloadFileWithProgress(
      @NonNull SmbConnection connection,
      @NonNull String remotePath,
      @NonNull java.io.File localFile,
      @Nullable BackgroundSmbManager.ProgressCallback progressCallback)
      throws Exception;
}
