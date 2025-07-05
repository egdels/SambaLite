package de.schliweb.sambalite.data.repository;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.Directory;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.util.LogUtils;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Implementation of SmbRepository using the SMBJ library.
 */
@Singleton
public class SmbRepositoryImpl implements SmbRepository {

    private final SMBClient smbClient;
    /**
     * Lock to prevent concurrent SMB operations.
     * This lock ensures that only one SMB operation can be executed at a time,
     * which helps prevent issues with concurrent access to SMB resources.
     * All public methods that perform SMB operations acquire this lock before
     * executing the operation and release it when the operation is complete.
     */
    private final ReentrantLock operationLock = new ReentrantLock();
    private volatile boolean searchCancelled = false;

    @Inject
    public SmbRepositoryImpl() {
        this.smbClient = new SMBClient();
    }

    @Override
    public void cancelSearch() {
        LogUtils.d("SmbRepositoryImpl", "Search cancellation requested");
        searchCancelled = true;
    }

    @Override
    public List<SmbFileItem> searchFiles(SmbConnection connection, String path, String query, int searchType, boolean includeSubfolders) throws Exception {
        LogUtils.d("SmbRepositoryImpl", "Searching for files matching query: '" + query + "' in path: " + path + ", searchType: " + searchType + ", includeSubfolders: " + includeSubfolders);

        operationLock.lock();
        try {
            // Reset cancellation flag at the start of a new search
            searchCancelled = false;

            List<SmbFileItem> result = new ArrayList<>();
            String folderPath = path == null || path.isEmpty() ? "" : path;

            try (Connection conn = smbClient.connect(connection.getServer())) {
                LogUtils.d("SmbRepositoryImpl", "Connected to server: " + connection.getServer());

                // Check if search was cancelled
                if (searchCancelled) {
                    LogUtils.i("SmbRepositoryImpl", "Search cancelled after connecting to server");
                    return result;
                }

                AuthenticationContext authContext = createAuthContext(connection);
                try (Session session = conn.authenticate(authContext)) {
                    LogUtils.d("SmbRepositoryImpl", "Authentication successful");

                    // Check if search was cancelled
                    if (searchCancelled) {
                        LogUtils.i("SmbRepositoryImpl", "Search cancelled after authentication");
                        return result;
                    }

                    String shareName = getShareName(connection.getShare());
                    try (DiskShare share = (DiskShare) session.connectShare(shareName)) {
                        LogUtils.d("SmbRepositoryImpl", "Connected to share: " + shareName);

                        // Check if search was cancelled
                        if (searchCancelled) {
                            LogUtils.i("SmbRepositoryImpl", "Search cancelled after connecting to share");
                            return result;
                        }

                        // Perform search with options
                        searchFilesRecursive(share, folderPath, query, result, searchType, includeSubfolders);

                        if (searchCancelled) {
                            LogUtils.i("SmbRepositoryImpl", "Search was cancelled. Returning partial results: " + result.size() + " items");
                        } else {
                            LogUtils.i("SmbRepositoryImpl", "Search completed. Found " + result.size() + " matching items");
                        }
                    }
                }
            } catch (Exception e) {
                if (searchCancelled) {
                    LogUtils.i("SmbRepositoryImpl", "Search was cancelled during exception: " + e.getMessage());
                    return result;
                }
                LogUtils.e("SmbRepositoryImpl", "Error searching files: " + e.getMessage());
                throw e;
            }

            return result;
        } finally {
            operationLock.unlock();
        }
    }

    /**
     * Recursively searches for files matching the query in the given path and its subdirectories.
     *
     * @param share             The disk share to search in
     * @param path              The path to search in
     * @param query             The search query
     * @param result            The list to add results to
     * @param searchType        The type of items to search for (0=all, 1=files only, 2=folders only)
     * @param includeSubfolders Whether to include subfolders in the search
     */
    private void searchFilesRecursive(DiskShare share, String path, String query, List<SmbFileItem> result, int searchType, boolean includeSubfolders) {
        // Check if search was cancelled
        if (searchCancelled) {
            LogUtils.d("SmbRepositoryImpl", "Search cancelled before searching directory: " + path);
            return;
        }

        LogUtils.d("SmbRepositoryImpl", "Searching in directory: " + path);

        try {
            // List files in current directory
            for (FileIdBothDirectoryInformation info : share.list(path)) {
                // Check if search was cancelled
                if (searchCancelled) {
                    LogUtils.d("SmbRepositoryImpl", "Search cancelled while processing directory: " + path);
                    return;
                }

                // Skip "." and ".." entries
                if (".".equals(info.getFileName()) || "..".equals(info.getFileName())) {
                    continue;
                }

                String name = info.getFileName();
                String fullPath = path.isEmpty() ? name : path + "/" + name;
                boolean isDirectory = (info.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;

                // Check if the item matches the search type filter
                boolean matchesType = (searchType == 0) || // All items
                        (searchType == 1 && !isDirectory) || // Files only
                        (searchType == 2 && isDirectory);  // Folders only

                // Check if the file name matches the query (case-insensitive, with wildcard support)
                if (matchesType && matchesWildcard(name, query)) {
                    LogUtils.d("SmbRepositoryImpl", "Found matching item: " + name);

                    SmbFileItem.Type type = isDirectory ? SmbFileItem.Type.DIRECTORY : SmbFileItem.Type.FILE;
                    long size = info.getEndOfFile();
                    Date lastModified = new Date(info.getLastWriteTime().toEpochMillis());

                    result.add(new SmbFileItem(name, fullPath, type, size, lastModified));
                }

                // If it's a directory and we should include subfolders, search inside it recursively
                if (isDirectory && includeSubfolders && !searchCancelled) {
                    searchFilesRecursive(share, fullPath, query, result, searchType, includeSubfolders);
                }
            }
        } catch (Exception e) {
            // Log error but continue with other directories if not cancelled
            if (!searchCancelled) {
                LogUtils.e("SmbRepositoryImpl", "Error searching in directory " + path + ": " + e.getMessage());
            } else {
                LogUtils.d("SmbRepositoryImpl", "Search cancelled during exception in directory " + path);
            }
        }
    }

    @Override
    public boolean testConnection(SmbConnection connection) throws Exception {
        LogUtils.d("SmbRepositoryImpl", "Testing connection to server: " + connection.getServer() + ", share: " + connection.getShare());
        operationLock.lock();
        try {
            try (Connection conn = smbClient.connect(connection.getServer())) {
                LogUtils.d("SmbRepositoryImpl", "Connected to server: " + connection.getServer());
                AuthenticationContext authContext = createAuthContext(connection);
                try (Session session = conn.authenticate(authContext)) {
                    LogUtils.d("SmbRepositoryImpl", "Authentication successful for user: " + connection.getUsername());
                    // Try to connect to the share
                    String shareName = getShareName(connection.getShare());
                    LogUtils.d("SmbRepositoryImpl", "Attempting to connect to share: " + shareName);
                    try (DiskShare share = (DiskShare) session.connectShare(shareName)) {
                        boolean connected = share.isConnected();
                        if (connected) {
                            LogUtils.i("SmbRepositoryImpl", "Successfully connected to share: " + shareName);
                        } else {
                            LogUtils.w("SmbRepositoryImpl", "Failed to connect to share: " + shareName);
                        }
                        return connected;
                    }
                }
            } catch (Exception e) {
                LogUtils.e("SmbRepositoryImpl", "Connection test failed: " + e.getMessage());
                throw e;
            }
        } finally {
            operationLock.unlock();
        }
    }

    @Override
    public List<SmbFileItem> listFiles(SmbConnection connection, String path) throws Exception {
        String folderPath = path == null || path.isEmpty() ? "" : path;
        LogUtils.d("SmbRepositoryImpl", "Listing files in path: " + folderPath + " on server: " + connection.getServer());

        operationLock.lock();
        try {
            List<SmbFileItem> result = new ArrayList<>();

            try (Connection conn = smbClient.connect(connection.getServer())) {
                LogUtils.d("SmbRepositoryImpl", "Connected to server: " + connection.getServer());
                AuthenticationContext authContext = createAuthContext(connection);
                try (Session session = conn.authenticate(authContext)) {
                    LogUtils.d("SmbRepositoryImpl", "Authentication successful");
                    String shareName = getShareName(connection.getShare());
                    try (DiskShare share = (DiskShare) session.connectShare(shareName)) {
                        LogUtils.d("SmbRepositoryImpl", "Connected to share: " + shareName);

                        // List files and directories
                        LogUtils.d("SmbRepositoryImpl", "Listing contents of: " + folderPath);
                        for (FileIdBothDirectoryInformation info : share.list(folderPath)) {
                            // Skip "." and ".." entries
                            if (".".equals(info.getFileName()) || "..".equals(info.getFileName())) {
                                continue;
                            }

                            String name = info.getFileName();
                            String fullPath = folderPath.isEmpty() ? name : folderPath + "/" + name;
                            boolean isDirectory = (info.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;

                            SmbFileItem.Type type = isDirectory ? SmbFileItem.Type.DIRECTORY : SmbFileItem.Type.FILE;
                            long size = info.getEndOfFile();
                            Date lastModified = new Date(info.getLastWriteTime().toEpochMillis());

                            result.add(new SmbFileItem(name, fullPath, type, size, lastModified));
                        }
                        LogUtils.i("SmbRepositoryImpl", "Listed " + result.size() + " items in " + folderPath);
                    }
                }
            } catch (Exception e) {
                LogUtils.e("SmbRepositoryImpl", "Error listing files: " + e.getMessage());
                throw e;
            }

            return result;
        } finally {
            operationLock.unlock();
        }
    }

    @Override
    public void downloadFile(SmbConnection connection, String remotePath, java.io.File localFile) throws Exception {
        LogUtils.d("SmbRepositoryImpl", "Downloading file: " + remotePath + " to " + localFile.getAbsolutePath());

        operationLock.lock();
        try {
            int maxRetries = 3;
            int retryCount = 0;
            boolean downloadSuccessful = false;
            Exception lastException = null;

            while (!downloadSuccessful && retryCount < maxRetries) {
                if (retryCount > 0) {
                    LogUtils.i("SmbRepositoryImpl", "Retrying download (attempt " + (retryCount + 1) + " of " + maxRetries + ")");
                    // Wait a bit before retrying
                    try {
                        Thread.sleep(1000 * retryCount);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                try (Connection conn = smbClient.connect(connection.getServer())) {
                    LogUtils.d("SmbRepositoryImpl", "Connected to server: " + connection.getServer());
                    AuthenticationContext authContext = createAuthContext(connection);
                    try (Session session = conn.authenticate(authContext)) {
                        LogUtils.d("SmbRepositoryImpl", "Authentication successful");
                        String shareName = getShareName(connection.getShare());
                        try (DiskShare share = (DiskShare) session.connectShare(shareName)) {
                            LogUtils.d("SmbRepositoryImpl", "Connected to share: " + shareName);
                            String filePath = getPathWithoutShare(remotePath);
                            LogUtils.d("SmbRepositoryImpl", "Checking if remote file exists: " + filePath);

                            // Check if the file exists before attempting to open it
                            if (!share.fileExists(filePath)) {
                                String errorMessage = "File not found: " + filePath;
                                LogUtils.e("SmbRepositoryImpl", errorMessage);
                                throw new IOException(errorMessage);
                            }

                            LogUtils.d("SmbRepositoryImpl", "Opening remote file: " + filePath);
                            try (File remoteFile = share.openFile(filePath, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)) {
                                LogUtils.d("SmbRepositoryImpl", "Remote file opened successfully");

                                // If this is a retry and the file already exists, we need to determine where to resume from
                                long fileSize = 0;
                                if (retryCount > 0 && localFile.exists()) {
                                    fileSize = localFile.length();
                                    LogUtils.d("SmbRepositoryImpl", "Resuming download from byte position: " + fileSize);
                                }

                                try (InputStream is = remoteFile.getInputStream(); java.io.FileOutputStream fos = new java.io.FileOutputStream(localFile, fileSize > 0)) {
                                    LogUtils.d("SmbRepositoryImpl", "Starting file transfer");

                                    // Skip bytes if resuming
                                    if (fileSize > 0) {
                                        long skipped = is.skip(fileSize);
                                        LogUtils.d("SmbRepositoryImpl", "Skipped " + skipped + " bytes");
                                    }

                                    byte[] buffer = new byte[8192];
                                    int bytesRead;
                                    long totalBytesRead = fileSize; // Start from current file size if resuming

                                    try {
                                        while ((bytesRead = is.read(buffer)) != -1) {
                                            fos.write(buffer, 0, bytesRead);
                                            totalBytesRead += bytesRead;
                                        }
                                        LogUtils.i("SmbRepositoryImpl", "File downloaded successfully: " + totalBytesRead + " bytes");
                                        downloadSuccessful = true;
                                        break; // Exit the retry loop
                                    } catch (Exception e) {
                                        LogUtils.e("SmbRepositoryImpl", "Error during file transfer: " + e.getMessage());
                                        lastException = e;
                                        // Don't rethrow here, let the retry mechanism handle it
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    LogUtils.e("SmbRepositoryImpl", "Error downloading file (attempt " + (retryCount + 1) + "): " + e.getMessage());
                    lastException = e;
                    // Don't rethrow here, let the retry mechanism handle it
                }

                retryCount++;
            }

            if (!downloadSuccessful) {
                LogUtils.e("SmbRepositoryImpl", "Download failed after " + maxRetries + " attempts");
                if (lastException != null) {
                    throw lastException;
                } else {
                    throw new IOException("Download failed after " + maxRetries + " attempts");
                }
            }
        } finally {
            operationLock.unlock();
        }
    }

    @Override
    public void uploadFile(SmbConnection connection, java.io.File localFile, String remotePath) throws Exception {
        LogUtils.d("SmbRepositoryImpl", "Uploading file: " + localFile.getAbsolutePath() + " to " + remotePath);

        operationLock.lock();
        try {
            int maxRetries = 3;
            int retryCount = 0;
            boolean uploadSuccessful = false;
            Exception lastException = null;

            while (!uploadSuccessful && retryCount < maxRetries) {
                if (retryCount > 0) {
                    LogUtils.i("SmbRepositoryImpl", "Retrying upload (attempt " + (retryCount + 1) + " of " + maxRetries + ")");
                    // Wait a bit before retrying
                    try {
                        Thread.sleep(1000 * retryCount);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                try (Connection conn = smbClient.connect(connection.getServer())) {
                    LogUtils.d("SmbRepositoryImpl", "Connected to server: " + connection.getServer());
                    AuthenticationContext authContext = createAuthContext(connection);
                    try (Session session = conn.authenticate(authContext)) {
                        LogUtils.d("SmbRepositoryImpl", "Authentication successful");
                        String shareName = getShareName(connection.getShare());
                        try (DiskShare share = (DiskShare) session.connectShare(shareName)) {
                            LogUtils.d("SmbRepositoryImpl", "Connected to share: " + shareName);
                            String filePath = getPathWithoutShare(remotePath);
                            LogUtils.d("SmbRepositoryImpl", "Creating remote file: " + filePath);

                            try (File remoteFile = share.openFile(filePath, EnumSet.of(AccessMask.GENERIC_WRITE), EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null)) {
                                LogUtils.d("SmbRepositoryImpl", "Remote file created successfully");

                                try (java.io.FileInputStream fis = new java.io.FileInputStream(localFile); OutputStream os = remoteFile.getOutputStream()) {
                                    LogUtils.d("SmbRepositoryImpl", "Starting file transfer");

                                    byte[] buffer = new byte[8192];
                                    int bytesRead;
                                    long totalBytesWritten = 0;

                                    try {
                                        while ((bytesRead = fis.read(buffer)) != -1) {
                                            os.write(buffer, 0, bytesRead);
                                            totalBytesWritten += bytesRead;
                                        }
                                        LogUtils.i("SmbRepositoryImpl", "File uploaded successfully: " + totalBytesWritten + " bytes");
                                        uploadSuccessful = true;

                                        // Delete the local file from cache after successful upload
                                        if (localFile.exists()) {
                                            boolean deleted = localFile.delete();
                                            if (deleted) {
                                                LogUtils.i("SmbRepositoryImpl", "Successfully deleted local file from cache: " + localFile.getAbsolutePath());
                                            } else {
                                                LogUtils.w("SmbRepositoryImpl", "Failed to delete local file from cache: " + localFile.getAbsolutePath());
                                            }
                                        }

                                        break; // Exit the retry loop
                                    } catch (Exception e) {
                                        LogUtils.e("SmbRepositoryImpl", "Error during file transfer: " + e.getMessage());
                                        lastException = e;
                                        // Don't rethrow here, let the retry mechanism handle it
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    LogUtils.e("SmbRepositoryImpl", "Error uploading file (attempt " + (retryCount + 1) + "): " + e.getMessage());
                    lastException = e;
                    // Don't rethrow here, let the retry mechanism handle it
                }

                retryCount++;
            }

            if (!uploadSuccessful) {
                LogUtils.e("SmbRepositoryImpl", "Upload failed after " + maxRetries + " attempts");
                if (lastException != null) {
                    throw lastException;
                } else {
                    throw new IOException("Upload failed after " + maxRetries + " attempts");
                }
            }
        } finally {
            operationLock.unlock();
        }
    }

    @Override
    public void deleteFile(SmbConnection connection, String path) throws Exception {
        LogUtils.d("SmbRepositoryImpl", "Deleting file/directory: " + path);

        operationLock.lock();
        try {
            try (Connection conn = smbClient.connect(connection.getServer())) {
                LogUtils.d("SmbRepositoryImpl", "Connected to server: " + connection.getServer());
                AuthenticationContext authContext = createAuthContext(connection);
                try (Session session = conn.authenticate(authContext)) {
                    LogUtils.d("SmbRepositoryImpl", "Authentication successful");
                    String shareName = getShareName(connection.getShare());
                    try (DiskShare share = (DiskShare) session.connectShare(shareName)) {
                        LogUtils.d("SmbRepositoryImpl", "Connected to share: " + shareName);
                        String filePath = getPathWithoutShare(path);
                        LogUtils.d("SmbRepositoryImpl", "Checking path: " + filePath);

                        if (share.fileExists(filePath)) {
                            LogUtils.d("SmbRepositoryImpl", "Path is a file, deleting: " + filePath);
                            share.rm(filePath);
                            LogUtils.i("SmbRepositoryImpl", "File deleted successfully: " + filePath);
                        } else if (share.folderExists(filePath)) {
                            LogUtils.d("SmbRepositoryImpl", "Path is a directory, deleting: " + filePath);
                            share.rmdir(filePath, true);
                            LogUtils.i("SmbRepositoryImpl", "Directory deleted successfully: " + filePath);
                        } else {
                            LogUtils.w("SmbRepositoryImpl", "File or directory not found: " + filePath);
                            throw new IOException("File or directory not found: " + filePath);
                        }
                    }
                }
            } catch (Exception e) {
                LogUtils.e("SmbRepositoryImpl", "Error deleting file/directory: " + e.getMessage());
                throw e;
            }
        } finally {
            operationLock.unlock();
        }
    }

    @Override
    public void renameFile(SmbConnection connection, String oldPath, String newName) throws Exception {
        LogUtils.d("SmbRepositoryImpl", "Renaming file/directory: " + oldPath + " to " + newName);

        operationLock.lock();
        try {
            try (Connection conn = smbClient.connect(connection.getServer())) {
                LogUtils.d("SmbRepositoryImpl", "Connected to server: " + connection.getServer());
                AuthenticationContext authContext = createAuthContext(connection);
                try (Session session = conn.authenticate(authContext)) {
                    LogUtils.d("SmbRepositoryImpl", "Authentication successful");
                    String shareName = getShareName(connection.getShare());
                    try (DiskShare share = (DiskShare) session.connectShare(shareName)) {
                        LogUtils.d("SmbRepositoryImpl", "Connected to share: " + shareName);
                        String oldFilePath = getPathWithoutShare(oldPath);
                        LogUtils.d("SmbRepositoryImpl", "Old file path: " + oldFilePath);

                        // Get the parent directory path
                        String parentPath = "";
                        int lastSlash = oldFilePath.lastIndexOf('/');
                        if (lastSlash > 0) {
                            parentPath = oldFilePath.substring(0, lastSlash);
                        }

                        String newFilePath = parentPath.isEmpty() ? newName : parentPath + "/" + newName;
                        LogUtils.d("SmbRepositoryImpl", "New file path: " + newFilePath);

                        // Check if the target path already exists to avoid overwriting
                        if (share.fileExists(newFilePath) || share.folderExists(newFilePath)) {
                            String errorMessage = "Target path already exists: " + newFilePath;
                            LogUtils.e("SmbRepositoryImpl", errorMessage);
                            throw new IOException(errorMessage);
                        }

                        if (share.fileExists(oldFilePath)) {
                            LogUtils.d("SmbRepositoryImpl", "Path is a file, copying content to new file");
                            // For files, we need to copy the content and then delete the original
                            try (File sourceFile = share.openFile(oldFilePath, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null); File targetFile = share.openFile(newFilePath, EnumSet.of(AccessMask.GENERIC_WRITE), EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_CREATE, null)) {
                                LogUtils.d("SmbRepositoryImpl", "Source and target files opened successfully");

                                try (InputStream is = sourceFile.getInputStream(); OutputStream os = targetFile.getOutputStream()) {
                                    LogUtils.d("SmbRepositoryImpl", "Starting file content copy");

                                    byte[] buffer = new byte[8192];
                                    int bytesRead;
                                    long totalBytesCopied = 0;
                                    long sourceSize = sourceFile.getFileInformation().getStandardInformation().getEndOfFile();

                                    while ((bytesRead = is.read(buffer)) != -1) {
                                        os.write(buffer, 0, bytesRead);
                                        totalBytesCopied += bytesRead;
                                    }

                                    LogUtils.d("SmbRepositoryImpl", "File content copied: " + totalBytesCopied + " bytes");

                                    // Verify file size matches
                                    if (totalBytesCopied != sourceSize) {
                                        String errorMessage = "File size mismatch after copy. Expected: " + sourceSize + ", Actual: " + totalBytesCopied;
                                        LogUtils.e("SmbRepositoryImpl", errorMessage);
                                        throw new IOException(errorMessage);
                                    }
                                }
                            }

                            // Verify the new file exists and has the correct size before deleting the original
                            if (!share.fileExists(newFilePath)) {
                                String errorMessage = "Target file not found after copy: " + newFilePath;
                                LogUtils.e("SmbRepositoryImpl", errorMessage);
                                throw new IOException(errorMessage);
                            }

                            // Delete the original file only after successful verification
                            LogUtils.d("SmbRepositoryImpl", "Deleting original file: " + oldFilePath);
                            share.rm(oldFilePath);
                            LogUtils.i("SmbRepositoryImpl", "File renamed successfully from " + oldFilePath + " to " + newFilePath);
                        } else if (share.folderExists(oldFilePath)) {
                            LogUtils.d("SmbRepositoryImpl", "Path is a directory, using Directory class for renaming");
                            // Use the Directory class for direct renaming
                            try (Directory directory = share.openDirectory(oldFilePath, EnumSet.of(AccessMask.GENERIC_ALL), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)) {
                                LogUtils.d("SmbRepositoryImpl", "Renaming directory using Directory.rename: " + oldFilePath + " to " + newFilePath);
                                directory.rename(newFilePath, false);
                                LogUtils.i("SmbRepositoryImpl", "Directory renamed successfully using Directory.rename from " + oldFilePath + " to " + newFilePath);
                            }
                        } else {
                            LogUtils.w("SmbRepositoryImpl", "File or directory not found: " + oldFilePath);
                            throw new IOException("File or directory not found: " + oldFilePath);
                        }
                    }
                }
            } catch (Exception e) {
                LogUtils.e("SmbRepositoryImpl", "Error renaming file/directory: " + e.getMessage());
                throw e;
            }
        } finally {
            operationLock.unlock();
        }
    }

    @Override
    public void createDirectory(SmbConnection connection, String path, String name) throws Exception {
        LogUtils.d("SmbRepositoryImpl", "Creating directory: " + name + " in path: " + path);

        operationLock.lock();
        try {
            try (Connection conn = smbClient.connect(connection.getServer())) {
                LogUtils.d("SmbRepositoryImpl", "Connected to server: " + connection.getServer());
                AuthenticationContext authContext = createAuthContext(connection);
                try (Session session = conn.authenticate(authContext)) {
                    LogUtils.d("SmbRepositoryImpl", "Authentication successful");
                    String shareName = getShareName(connection.getShare());
                    try (DiskShare share = (DiskShare) session.connectShare(shareName)) {
                        LogUtils.d("SmbRepositoryImpl", "Connected to share: " + shareName);
                        String dirPath = getPathWithoutShare(path);
                        String newDirPath = dirPath.isEmpty() ? name : dirPath + "/" + name;
                        LogUtils.d("SmbRepositoryImpl", "Creating directory at path: " + newDirPath);

                        share.mkdir(newDirPath);
                        LogUtils.i("SmbRepositoryImpl", "Directory created successfully: " + newDirPath);
                    }
                }
            } catch (Exception e) {
                LogUtils.e("SmbRepositoryImpl", "Error creating directory: " + e.getMessage());
                throw e;
            }
        } finally {
            operationLock.unlock();
        }
    }

    /**
     * Creates an AuthenticationContext from the connection details.
     * If both username and password are empty, uses guest authentication.
     */
    private AuthenticationContext createAuthContext(SmbConnection connection) {
        LogUtils.d("SmbRepositoryImpl", "Creating authentication context for user: " + connection.getUsername());
        String domain = connection.getDomain() != null ? connection.getDomain() : "";
        String username = connection.getUsername() != null ? connection.getUsername() : "";
        String password = connection.getPassword() != null ? connection.getPassword() : "";

        if (domain.isEmpty()) {
            LogUtils.d("SmbRepositoryImpl", "No domain specified");
        } else {
            LogUtils.d("SmbRepositoryImpl", "Using domain: " + domain);
        }

        // If both username and password are empty, use guest authentication
        if (username.isEmpty() && password.isEmpty()) {
            LogUtils.d("SmbRepositoryImpl", "Using guest authentication");
            return AuthenticationContext.guest();
        }

        return new AuthenticationContext(username, password.toCharArray(), domain);
    }

    /**
     * Extracts the share name from the full share path.
     */
    private String getShareName(String sharePath) {
        LogUtils.d("SmbRepositoryImpl", "Extracting share name from path: " + sharePath);
        if (sharePath == null || sharePath.isEmpty()) {
            LogUtils.w("SmbRepositoryImpl", "Share path is null or empty");
            return "";
        }

        // Remove leading slashes
        String path = sharePath;
        while (path.startsWith("/") || path.startsWith("\\")) {
            path = path.substring(1);
        }

        // Extract share name (everything before the first slash)
        int slashIndex = path.indexOf('/');
        if (slashIndex == -1) {
            slashIndex = path.indexOf('\\');
        }

        String shareName = slashIndex == -1 ? path : path.substring(0, slashIndex);
        LogUtils.d("SmbRepositoryImpl", "Extracted share name: " + shareName);
        return shareName;
    }

    /**
     * Gets the path without the share name.
     * Note: This method assumes the share name is already handled separately
     * when connecting to the share, so it just normalizes the path.
     */
    private String getPathWithoutShare(String fullPath) {
        LogUtils.d("SmbRepositoryImpl", "Getting path without share from: " + fullPath);
        if (fullPath == null || fullPath.isEmpty()) {
            LogUtils.w("SmbRepositoryImpl", "Full path is null or empty");
            return "";
        }

        // Remove leading slashes
        String path = fullPath;
        while (path.startsWith("/") || path.startsWith("\\")) {
            path = path.substring(1);
        }

        // Return the normalized path without removing any segments
        LogUtils.d("SmbRepositoryImpl", "Path without share: " + path);
        return path;
    }

    /**
     * Checks if a filename matches a pattern that may contain wildcards.
     * Supports '*' (matches any sequence of characters) and '?' (matches any single character).
     *
     * @param filename The filename to check
     * @param pattern  The pattern to match against, may contain wildcards
     * @return true if the filename matches the pattern, false otherwise
     */
    private boolean matchesWildcard(String filename, String pattern) {
        // If no wildcards, use simple case-insensitive contains check for backward compatibility
        if (!pattern.contains("*") && !pattern.contains("?")) {
            return filename.toLowerCase().contains(pattern.toLowerCase());
        }

        // Convert the pattern to a regex pattern
        String regex = pattern.toLowerCase().replace(".", "\\.")  // Escape dots
                .replace("?", ".")    // ? matches any single character
                .replace("*", ".*");  // * matches any sequence of characters

        // For partial matching (like contains), we need to allow any characters before and after
        if (!regex.startsWith(".*")) {
            regex = ".*" + regex;
        }
        if (!regex.endsWith(".*")) {
            regex = regex + ".*";
        }

        // Check if the filename matches the regex pattern
        return filename.toLowerCase().matches(regex);
    }

    @Override
    public boolean fileExists(SmbConnection connection, String path) throws Exception {
        LogUtils.d("SmbRepositoryImpl", "Checking if file exists: " + path);

        operationLock.lock();
        try {
            try (Connection conn = smbClient.connect(connection.getServer())) {
                LogUtils.d("SmbRepositoryImpl", "Connected to server: " + connection.getServer());
                AuthenticationContext authContext = createAuthContext(connection);
                try (Session session = conn.authenticate(authContext)) {
                    LogUtils.d("SmbRepositoryImpl", "Authentication successful");
                    String shareName = getShareName(connection.getShare());
                    try (DiskShare share = (DiskShare) session.connectShare(shareName)) {
                        LogUtils.d("SmbRepositoryImpl", "Connected to share: " + shareName);
                        String filePath = getPathWithoutShare(path);
                        LogUtils.d("SmbRepositoryImpl", "Checking if file exists: " + filePath);

                        boolean exists = share.fileExists(filePath);
                        LogUtils.i("SmbRepositoryImpl", "File " + (exists ? "exists" : "does not exist") + ": " + filePath);
                        return exists;
                    }
                }
            } catch (Exception e) {
                LogUtils.e("SmbRepositoryImpl", "Error checking if file exists: " + e.getMessage());
                throw e;
            }
        } finally {
            operationLock.unlock();
        }
    }

    @Override
    public void downloadFolder(SmbConnection connection, String remotePath, java.io.File localFolder) throws Exception {
        LogUtils.d("SmbRepositoryImpl", "Downloading folder: " + remotePath + " to " + localFolder.getAbsolutePath());

        operationLock.lock();
        try {
            int maxRetries = 3;
            int retryCount = 0;
            boolean downloadSuccessful = false;
            Exception lastException = null;

            while (!downloadSuccessful && retryCount < maxRetries) {
                if (retryCount > 0) {
                    LogUtils.i("SmbRepositoryImpl", "Retrying folder download (attempt " + (retryCount + 1) + " of " + maxRetries + ")");
                    // Wait a bit before retrying
                    try {
                        Thread.sleep(1000 * retryCount);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                try (Connection conn = smbClient.connect(connection.getServer())) {
                    LogUtils.d("SmbRepositoryImpl", "Connected to server: " + connection.getServer());
                    AuthenticationContext authContext = createAuthContext(connection);
                    try (Session session = conn.authenticate(authContext)) {
                        LogUtils.d("SmbRepositoryImpl", "Authentication successful");
                        String shareName = getShareName(connection.getShare());
                        try (DiskShare share = (DiskShare) session.connectShare(shareName)) {
                            LogUtils.d("SmbRepositoryImpl", "Connected to share: " + shareName);
                            String folderPath = getPathWithoutShare(remotePath);
                            LogUtils.d("SmbRepositoryImpl", "Checking if remote folder exists: " + folderPath);

                            // Check if the folder exists before attempting to download it
                            if (!share.folderExists(folderPath)) {
                                String errorMessage = "Folder not found: " + folderPath;
                                LogUtils.e("SmbRepositoryImpl", errorMessage);
                                throw new IOException(errorMessage);
                            }

                            // Create the local folder if it doesn't exist
                            if (!localFolder.exists()) {
                                LogUtils.d("SmbRepositoryImpl", "Creating local folder: " + localFolder.getAbsolutePath());
                                if (!localFolder.mkdirs()) {
                                    String errorMessage = "Failed to create local folder: " + localFolder.getAbsolutePath();
                                    LogUtils.e("SmbRepositoryImpl", errorMessage);
                                    throw new IOException(errorMessage);
                                }
                            }

                            try {
                                // Download the folder contents recursively
                                downloadFolderContents(share, folderPath, localFolder);
                                LogUtils.i("SmbRepositoryImpl", "Folder downloaded successfully: " + remotePath);
                                downloadSuccessful = true;
                                break; // Exit the retry loop
                            } catch (Exception e) {
                                LogUtils.e("SmbRepositoryImpl", "Error during folder download: " + e.getMessage());
                                lastException = e;
                                // Don't rethrow here, let the retry mechanism handle it
                            }
                        }
                    }
                } catch (Exception e) {
                    LogUtils.e("SmbRepositoryImpl", "Error downloading folder (attempt " + (retryCount + 1) + "): " + e.getMessage());
                    lastException = e;
                    // Don't rethrow here, let the retry mechanism handle it
                }

                retryCount++;
            }

            if (!downloadSuccessful) {
                LogUtils.e("SmbRepositoryImpl", "Folder download failed after " + maxRetries + " attempts");
                if (lastException != null) {
                    throw lastException;
                } else {
                    throw new IOException("Folder download failed after " + maxRetries + " attempts");
                }
            }
        } finally {
            operationLock.unlock();
        }
    }

    /**
     * Downloads the contents of a folder recursively.
     *
     * @param share       The DiskShare to download from
     * @param remotePath  The remote path to download from
     * @param localFolder The local folder to download to
     * @throws IOException if an error occurs during the download
     */
    private void downloadFolderContents(DiskShare share, String remotePath, java.io.File localFolder) throws IOException {
        LogUtils.d("SmbRepositoryImpl", "Downloading folder contents: " + remotePath + " to " + localFolder.getAbsolutePath());

        // List files in the remote folder
        try {
            List<FileIdBothDirectoryInformation> files = share.list(remotePath);
            LogUtils.d("SmbRepositoryImpl", "Found " + files.size() + " items in folder: " + remotePath);

            for (FileIdBothDirectoryInformation file : files) {
                String fileName = file.getFileName();

                // Skip "." and ".." entries
                if (fileName.equals(".") || fileName.equals("..")) {
                    continue;
                }

                String remoteFilePath = remotePath.isEmpty() || remotePath.equals("\\") ? "\\" + fileName : remotePath + "\\" + fileName;
                java.io.File localFile = new java.io.File(localFolder, fileName);

                if ((file.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0) {
                    // It's a directory, create it locally and download its contents recursively
                    LogUtils.d("SmbRepositoryImpl", "Creating local directory: " + localFile.getAbsolutePath());
                    if (!localFile.exists() && !localFile.mkdir()) {
                        String errorMessage = "Failed to create local directory: " + localFile.getAbsolutePath();
                        LogUtils.e("SmbRepositoryImpl", errorMessage);
                        throw new IOException(errorMessage);
                    }
                    downloadFolderContents(share, remoteFilePath, localFile);
                } else {
                    // It's a file, download it with retry logic
                    LogUtils.d("SmbRepositoryImpl", "Downloading file: " + remoteFilePath + " to " + localFile.getAbsolutePath());

                    int maxRetries = 3;
                    int retryCount = 0;
                    boolean downloadSuccessful = false;
                    Exception lastException = null;

                    while (!downloadSuccessful && retryCount < maxRetries) {
                        if (retryCount > 0) {
                            LogUtils.i("SmbRepositoryImpl", "Retrying file download (attempt " + (retryCount + 1) + " of " + maxRetries + "): " + remoteFilePath);
                            // Wait a bit before retrying
                            try {
                                Thread.sleep(1000 * retryCount);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }

                        try (File remoteFile = share.openFile(remoteFilePath, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)) {
                            LogUtils.d("SmbRepositoryImpl", "Remote file opened successfully");

                            // If this is a retry and the file already exists, we need to determine where to resume from
                            long fileSize = 0;
                            if (retryCount > 0 && localFile.exists()) {
                                fileSize = localFile.length();
                                LogUtils.d("SmbRepositoryImpl", "Resuming download from byte position: " + fileSize);
                            }

                            try (InputStream is = remoteFile.getInputStream(); java.io.FileOutputStream fos = new java.io.FileOutputStream(localFile, fileSize > 0)) {
                                LogUtils.d("SmbRepositoryImpl", "Starting file transfer");

                                // Skip bytes if resuming
                                if (fileSize > 0) {
                                    long skipped = is.skip(fileSize);
                                    LogUtils.d("SmbRepositoryImpl", "Skipped " + skipped + " bytes");
                                }

                                byte[] buffer = new byte[8192];
                                int bytesRead;
                                long totalBytesRead = fileSize; // Start from current file size if resuming

                                try {
                                    while ((bytesRead = is.read(buffer)) != -1) {
                                        fos.write(buffer, 0, bytesRead);
                                        totalBytesRead += bytesRead;
                                    }
                                    LogUtils.i("SmbRepositoryImpl", "File downloaded successfully: " + totalBytesRead + " bytes");
                                    downloadSuccessful = true;
                                    break; // Exit the retry loop
                                } catch (Exception e) {
                                    LogUtils.e("SmbRepositoryImpl", "Error during file transfer: " + e.getMessage());
                                    lastException = e;
                                    // Don't rethrow here, let the retry mechanism handle it
                                }
                            }
                        } catch (Exception e) {
                            LogUtils.e("SmbRepositoryImpl", "Error downloading file (attempt " + (retryCount + 1) + "): " + e.getMessage());
                            lastException = e;
                            // Don't rethrow here, let the retry mechanism handle it
                        }

                        retryCount++;
                    }

                    if (!downloadSuccessful) {
                        LogUtils.e("SmbRepositoryImpl", "File download failed after " + maxRetries + " attempts: " + remoteFilePath);
                        if (lastException != null) {
                            throw new IOException("Error downloading file: " + remoteFilePath, lastException);
                        } else {
                            throw new IOException("File download failed after " + maxRetries + " attempts: " + remoteFilePath);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LogUtils.e("SmbRepositoryImpl", "Error listing files in folder: " + remotePath + " - " + e.getMessage());
            throw new IOException("Error listing files in folder: " + remotePath, e);
        }
    }
}
