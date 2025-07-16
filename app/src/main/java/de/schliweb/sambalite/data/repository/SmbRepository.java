package de.schliweb.sambalite.data.repository;

import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;

import java.io.File;
import java.util.List;

/**
 * Repository interface for SMB operations.
 */
public interface SmbRepository {

    /**
     * Searches for files and directories matching the query.
     *
     * @param connection        The SMB connection to use
     * @param path              The path to start the search from (null or empty for root)
     * @param query             The search query to match against file names
     * @param searchType        The type of items to search for (0=all, 1=files only, 2=folders only)
     * @param includeSubfolders Whether to include subfolders in the search
     * @return A list of SmbFileItem objects that match the query
     * @throws Exception if an error occurs during the search
     */
    List<SmbFileItem> searchFiles(SmbConnection connection, String path, String query, int searchType, boolean includeSubfolders) throws Exception;

    /**
     * Cancels any ongoing search operation.
     * This method should be called when a user wants to stop a search in progress.
     */
    void cancelSearch();

    /**
     * Cancels any ongoing download operation.
     * This method should be called when a user wants to stop a download in progress.
     */
    void cancelDownload();

    /**
     * Cancels any ongoing upload operation.
     * This method should be called when a user wants to stop an upload in progress.
     */
    void cancelUpload();

    /**
     * Tests a connection to an SMB server.
     *
     * @param connection The connection to test
     * @return true if the connection is successful, false otherwise
     * @throws Exception if an error occurs during the connection test
     */
    boolean testConnection(SmbConnection connection) throws Exception;

    /**
     * Lists files and directories in the specified path.
     *
     * @param connection The SMB connection to use
     * @param path       The path to list (null or empty for root)
     * @return A list of SmbFileItem objects representing files and directories
     * @throws Exception if an error occurs during the listing
     */
    List<SmbFileItem> listFiles(SmbConnection connection, String path) throws Exception;

    /**
     * Downloads a file from the SMB server.
     *
     * @param connection The SMB connection to use
     * @param remotePath The path to the file on the SMB server
     * @param localFile  The local file to save the downloaded file to
     * @throws Exception if an error occurs during the download
     */
    void downloadFile(SmbConnection connection, String remotePath, File localFile) throws Exception;

    /**
     * Uploads a file to the SMB server.
     *
     * @param connection The SMB connection to use
     * @param localFile  The local file to upload
     * @param remotePath The path on the SMB server to upload the file to
     * @throws Exception if an error occurs during the upload
     */
    void uploadFile(SmbConnection connection, File localFile, String remotePath) throws Exception;

    /**
     * Uploads a file to the SMB server with progress tracking.
     *
     * @param connection       The SMB connection to use
     * @param localFile        The local file to upload
     * @param remotePath       The path on the SMB server to upload the file to
     * @param progressCallback The callback to report progress updates
     * @throws Exception if an error occurs during the upload
     */
    void uploadFileWithProgress(SmbConnection connection, File localFile, String remotePath, BackgroundSmbManager.ProgressCallback progressCallback) throws Exception;

    /**
     * Downloads a file from the SMB server using a local file path.
     *
     * @param connection    The SMB connection to use
     * @param remotePath    The path to the file on the SMB server
     * @param localFilePath The path to the local file to save the downloaded file to
     * @throws Exception if an error occurs during the download
     */
    void downloadFile(SmbConnection connection, String remotePath, String localFilePath) throws Exception;

    /**
     * Deletes a file or directory on the SMB server.
     *
     * @param connection The SMB connection to use
     * @param path       The path to the file or directory to delete
     * @throws Exception if an error occurs during the deletion
     */
    void deleteFile(SmbConnection connection, String path) throws Exception;

    /**
     * Renames a file or directory on the SMB server.
     *
     * @param connection The SMB connection to use
     * @param oldPath    The current path of the file or directory
     * @param newName    The new name for the file or directory
     * @throws Exception if an error occurs during the rename
     */
    void renameFile(SmbConnection connection, String oldPath, String newName) throws Exception;

    /**
     * Creates a new directory on the SMB server.
     *
     * @param connection The SMB connection to use
     * @param path       The path where the new directory should be created
     * @param name       The name of the new directory
     * @throws Exception if an error occurs during directory creation
     */
    void createDirectory(SmbConnection connection, String path, String name) throws Exception;

    /**
     * Checks if a file exists on the SMB server.
     *
     * @param connection The SMB connection to use
     * @param path       The path to the file on the SMB server
     * @return true if the file exists, false otherwise
     * @throws Exception if an error occurs during the check
     */
    boolean fileExists(SmbConnection connection, String path) throws Exception;

    /**
     * Lists available shares on the SMB server.
     *
     * @param connection The SMB connection to use (only server, username, password, domain are needed)
     * @return A list of share names available on the server
     * @throws Exception if an error occurs during the share listing
     */
    List<String> listShares(SmbConnection connection) throws Exception;

    /**
     * Downloads a folder from the SMB server.
     *
     * @param connection  The SMB connection to use
     * @param remotePath  The path to the folder on the SMB server
     * @param localFolder The local folder to save the downloaded folder to
     * @throws Exception if an error occurs during the download
     */
    void downloadFolder(SmbConnection connection, String remotePath, java.io.File localFolder) throws Exception;

    /**
     * Downloads a folder from the SMB server with progress tracking.
     *
     * @param connection       The SMB connection to use
     * @param remotePath       The path to the folder on the SMB server
     * @param localFolder      The local folder to save the downloaded folder to
     * @param progressCallback The callback to report progress updates
     * @throws Exception if an error occurs during the download
     */
    void downloadFolderWithProgress(SmbConnection connection, String remotePath, java.io.File localFolder, BackgroundSmbManager.MultiFileProgressCallback progressCallback) throws Exception;

    /**
     * Downloads a file from the SMB server with progress tracking.
     *
     * @param connection       The SMB connection to use
     * @param remotePath       The path to the file on the SMB server
     * @param localFile        The local file to save the downloaded file to
     * @param progressCallback The callback to report progress updates
     * @throws Exception if an error occurs during the download
     */
    void downloadFileWithProgress(SmbConnection connection, String remotePath, java.io.File localFile, BackgroundSmbManager.ProgressCallback progressCallback) throws Exception;
}
