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

/**
 * Implementation of SmbRepository using the SMBJ library.
 */
@Singleton
public class SmbRepositoryImpl implements SmbRepository {

    private final SMBClient smbClient;
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
    public List<SmbFileItem> searchFiles(SmbConnection connection, String path, String query,
                                         int searchType, boolean includeSubfolders) throws Exception {
        LogUtils.d("SmbRepositoryImpl", "Searching for files matching query: '" + query +
                "' in path: " + path +
                ", searchType: " + searchType +
                ", includeSubfolders: " + includeSubfolders);

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
    private void searchFilesRecursive(DiskShare share, String path, String query, List<SmbFileItem> result,
                                      int searchType, boolean includeSubfolders) {
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
    }

    @Override
    public List<SmbFileItem> listFiles(SmbConnection connection, String path) throws Exception {
        String folderPath = path == null || path.isEmpty() ? "" : path;
        LogUtils.d("SmbRepositoryImpl", "Listing files in path: " + folderPath + " on server: " + connection.getServer());
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
    }

    @Override
    public void downloadFile(SmbConnection connection, String remotePath, java.io.File localFile) throws Exception {
        LogUtils.d("SmbRepositoryImpl", "Downloading file: " + remotePath + " to " + localFile.getAbsolutePath());
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
                    try (File remoteFile = share.openFile(
                            filePath,
                            EnumSet.of(AccessMask.GENERIC_READ),
                            null,
                            SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OPEN,
                            null)) {
                        LogUtils.d("SmbRepositoryImpl", "Remote file opened successfully");

                        try (InputStream is = remoteFile.getInputStream();
                             java.io.FileOutputStream fos = new java.io.FileOutputStream(localFile)) {
                            LogUtils.d("SmbRepositoryImpl", "Starting file transfer");

                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            long totalBytesRead = 0;
                            while ((bytesRead = is.read(buffer)) != -1) {
                                fos.write(buffer, 0, bytesRead);
                                totalBytesRead += bytesRead;
                            }
                            LogUtils.i("SmbRepositoryImpl", "File downloaded successfully: " + totalBytesRead + " bytes");
                        }
                    }
                }
            }
        } catch (Exception e) {
            LogUtils.e("SmbRepositoryImpl", "Error downloading file: " + e.getMessage());
            throw e;
        }
    }

    @Override
    public void uploadFile(SmbConnection connection, java.io.File localFile, String remotePath) throws Exception {
        LogUtils.d("SmbRepositoryImpl", "Uploading file: " + localFile.getAbsolutePath() + " to " + remotePath);
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

                    try (File remoteFile = share.openFile(
                            filePath,
                            EnumSet.of(AccessMask.GENERIC_WRITE),
                            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                            SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OVERWRITE_IF,
                            null)) {
                        LogUtils.d("SmbRepositoryImpl", "Remote file created successfully");

                        try (java.io.FileInputStream fis = new java.io.FileInputStream(localFile);
                             OutputStream os = remoteFile.getOutputStream()) {
                            LogUtils.d("SmbRepositoryImpl", "Starting file transfer");

                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            long totalBytesWritten = 0;
                            while ((bytesRead = fis.read(buffer)) != -1) {
                                os.write(buffer, 0, bytesRead);
                                totalBytesWritten += bytesRead;
                            }
                            LogUtils.i("SmbRepositoryImpl", "File uploaded successfully: " + totalBytesWritten + " bytes");
                        }
                    }
                }
            }
        } catch (Exception e) {
            LogUtils.e("SmbRepositoryImpl", "Error uploading file: " + e.getMessage());
            throw e;
        }
    }

    @Override
    public void deleteFile(SmbConnection connection, String path) throws Exception {
        LogUtils.d("SmbRepositoryImpl", "Deleting file/directory: " + path);
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
    }

    @Override
    public void renameFile(SmbConnection connection, String oldPath, String newName) throws Exception {
        LogUtils.d("SmbRepositoryImpl", "Renaming file/directory: " + oldPath + " to " + newName);
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

                    if (share.fileExists(oldFilePath)) {
                        LogUtils.d("SmbRepositoryImpl", "Path is a file, copying content to new file");
                        // For files, we need to copy the content and then delete the original
                        try (File sourceFile = share.openFile(
                                oldFilePath,
                                EnumSet.of(AccessMask.GENERIC_READ),
                                null,
                                SMB2ShareAccess.ALL,
                                SMB2CreateDisposition.FILE_OPEN,
                                null);
                             File targetFile = share.openFile(
                                     newFilePath,
                                     EnumSet.of(AccessMask.GENERIC_WRITE),
                                     EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                                     SMB2ShareAccess.ALL,
                                     SMB2CreateDisposition.FILE_CREATE,
                                     null)) {
                            LogUtils.d("SmbRepositoryImpl", "Source and target files opened successfully");

                            try (InputStream is = sourceFile.getInputStream();
                                 OutputStream os = targetFile.getOutputStream()) {
                                LogUtils.d("SmbRepositoryImpl", "Starting file content copy");

                                byte[] buffer = new byte[8192];
                                int bytesRead;
                                long totalBytesCopied = 0;
                                while ((bytesRead = is.read(buffer)) != -1) {
                                    os.write(buffer, 0, bytesRead);
                                    totalBytesCopied += bytesRead;
                                }
                                LogUtils.d("SmbRepositoryImpl", "File content copied: " + totalBytesCopied + " bytes");
                            }
                        }

                        // Delete the original file
                        LogUtils.d("SmbRepositoryImpl", "Deleting original file: " + oldFilePath);
                        share.rm(oldFilePath);
                        LogUtils.i("SmbRepositoryImpl", "File renamed successfully from " + oldFilePath + " to " + newFilePath);
                    } else if (share.folderExists(oldFilePath)) {
                        LogUtils.d("SmbRepositoryImpl", "Path is a directory, creating new directory: " + newFilePath);
                        // For directories, create the new directory
                        share.mkdir(newFilePath);
                        LogUtils.d("SmbRepositoryImpl", "New directory created: " + newFilePath);

                        // Copy all files and subdirectories (not implemented here)
                        // This would require recursively listing and copying all contents
                        LogUtils.w("SmbRepositoryImpl", "Directory content copying not implemented");

                        // Delete the original directory
                        LogUtils.d("SmbRepositoryImpl", "Deleting original directory: " + oldFilePath);
                        share.rmdir(oldFilePath, true);
                        LogUtils.w("SmbRepositoryImpl", "Renaming directories is not fully implemented");

                        throw new UnsupportedOperationException("Renaming directories is not fully implemented");
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
    }

    @Override
    public void createDirectory(SmbConnection connection, String path, String name) throws Exception {
        LogUtils.d("SmbRepositoryImpl", "Creating directory: " + name + " in path: " + path);
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
    }

    /**
     * Creates an AuthenticationContext from the connection details.
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
        String regex = pattern.toLowerCase()
                .replace(".", "\\.")  // Escape dots
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
    }

    @Override
    public void downloadFolder(SmbConnection connection, String remotePath, java.io.File localFolder) throws Exception {
        LogUtils.d("SmbRepositoryImpl", "Downloading folder: " + remotePath + " to " + localFolder.getAbsolutePath());
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

                    // Download the folder contents recursively
                    downloadFolderContents(share, folderPath, localFolder);
                    LogUtils.i("SmbRepositoryImpl", "Folder downloaded successfully: " + remotePath);
                }
            }
        } catch (Exception e) {
            LogUtils.e("SmbRepositoryImpl", "Error downloading folder: " + e.getMessage());
            throw e;
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

                String remoteFilePath = remotePath.isEmpty() || remotePath.equals("\\") ? 
                    "\\" + fileName : remotePath + "\\" + fileName;
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
                    // It's a file, download it
                    LogUtils.d("SmbRepositoryImpl", "Downloading file: " + remoteFilePath + " to " + localFile.getAbsolutePath());
                    try (File remoteFile = share.openFile(
                            remoteFilePath,
                            EnumSet.of(AccessMask.GENERIC_READ),
                            null,
                            SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OPEN,
                            null)) {
                        LogUtils.d("SmbRepositoryImpl", "Remote file opened successfully");

                        try (InputStream is = remoteFile.getInputStream();
                             java.io.FileOutputStream fos = new java.io.FileOutputStream(localFile)) {
                            LogUtils.d("SmbRepositoryImpl", "Starting file transfer");

                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            long totalBytesRead = 0;
                            while ((bytesRead = is.read(buffer)) != -1) {
                                fos.write(buffer, 0, bytesRead);
                                totalBytesRead += bytesRead;
                            }
                            LogUtils.i("SmbRepositoryImpl", "File downloaded successfully: " + totalBytesRead + " bytes");
                        }
                    } catch (Exception e) {
                        LogUtils.e("SmbRepositoryImpl", "Error downloading file: " + remoteFilePath + " - " + e.getMessage());
                        throw new IOException("Error downloading file: " + remoteFilePath, e);
                    }
                }
            }
        } catch (Exception e) {
            LogUtils.e("SmbRepositoryImpl", "Error listing files in folder: " + remotePath + " - " + e.getMessage());
            throw new IOException("Error listing files in folder: " + remotePath, e);
        }
    }
}
