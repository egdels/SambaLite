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
import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.util.EnhancedFileUtils;
import de.schliweb.sambalite.util.LogUtils;
import de.schliweb.sambalite.util.SmartErrorHandler;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Implementation of SmbRepository using the SMBJ library.
 */
@Singleton
public class SmbRepositoryImpl implements SmbRepository {

    private final SMBClient smbClient;
    private final BackgroundSmbManager backgroundManager;
    private final SmartErrorHandler errorHandler;
    /**
     * Lock to prevent concurrent SMB operations.
     * This lock ensures that only one SMB operation can be executed at a time,
     * which helps prevent issues with concurrent access to SMB resources.
     * All public methods that perform SMB operations acquire this lock before
     * executing the operation and release it when the operation is complete.
     */
    private final ReentrantLock operationLock = new ReentrantLock();
    private volatile boolean searchCancelled = false;
    private volatile boolean downloadCancelled = false;
    private volatile boolean uploadCancelled = false;

    @Inject
    public SmbRepositoryImpl(BackgroundSmbManager backgroundManager) {
        this.smbClient = new SMBClient();
        this.backgroundManager = backgroundManager;
        this.errorHandler = SmartErrorHandler.getInstance();
    }

    @Override
    public void cancelSearch() {
        LogUtils.d("SmbRepositoryImpl", "Search cancellation requested");
        searchCancelled = true;
    }

    @Override
    public void cancelDownload() {
        LogUtils.d("SmbRepositoryImpl", "Download cancellation requested");
        downloadCancelled = true;
    }

    @Override
    public void cancelUpload() {
        LogUtils.d("SmbRepositoryImpl", "Upload cancellation requested");
        uploadCancelled = true;
    }

    @Override
    public List<SmbFileItem> searchFiles(SmbConnection connection, String path, String query, int searchType, boolean includeSubfolders) throws Exception {
        LogUtils.d("SmbRepositoryImpl", "Searching for files matching query: '" + query + "' in path: " + path + ", searchType: " + searchType + ", includeSubfolders: " + includeSubfolders);

        // Use tryLock with timeout to prevent indefinite blocking
        boolean lockAcquired = false;
        try {
            lockAcquired = operationLock.tryLock(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!lockAcquired) {
                LogUtils.w("SmbRepositoryImpl", "Could not acquire operation lock for search within 5 seconds - possible deadlock");
                throw new Exception("Search operation timeout - another operation may be blocking");
            }

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

                        // Perform search with options and lifecycle awareness
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
                errorHandler.recordError(e, "SmbRepositoryImpl.searchFiles", SmartErrorHandler.ErrorSeverity.HIGH);
                throw e;
            }

            return result;
        } catch (InterruptedException e) {
            LogUtils.w("SmbRepositoryImpl", "Search operation interrupted while waiting for lock");
            Thread.currentThread().interrupt();
            throw new Exception("Search operation was interrupted");
        } finally {
            if (lockAcquired) {
                operationLock.unlock();
            }
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
        // Check for cancellation before processing this directory
        if (searchCancelled) {
            LogUtils.d("SmbRepositoryImpl", "Search cancelled before searching directory: " + path);
            return;
        }

        LogUtils.d("SmbRepositoryImpl", "Searching in directory: " + path);

        try {
            for (FileIdBothDirectoryInformation info : share.list(path)) {
                // Check for cancellation during each file iteration
                if (searchCancelled) {
                    LogUtils.d("SmbRepositoryImpl", "Search cancelled while processing directory: " + path);
                    return;
                }

                String name = info.getFileName();
                if (".".equals(name) || "..".equals(name)) continue;

                String fullPath = path.isEmpty() ? name : path + "/" + name;
                boolean isDirectory = (info.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;

                if (matchesSearchCriteria(name, query, searchType, isDirectory)) {
                    result.add(createSmbFileItem(info, name, fullPath, isDirectory));
                }

                // Recursive call with cancellation check
                if (isDirectory && includeSubfolders && !searchCancelled) {
                    searchFilesRecursive(share, fullPath, query, result, searchType, includeSubfolders);
                }
            }
        } catch (Exception e) {
            handleSearchException(path, e);
        }
    }

    private boolean matchesSearchCriteria(String name, String query, int searchType, boolean isDirectory) {
        return ((searchType == 0) || (searchType == 1 && !isDirectory) || (searchType == 2 && isDirectory)) && matchesWildcard(name, query);
    }

    private SmbFileItem createSmbFileItem(FileIdBothDirectoryInformation info, String name, String fullPath, boolean isDirectory) {
        SmbFileItem.Type type = isDirectory ? SmbFileItem.Type.DIRECTORY : SmbFileItem.Type.FILE;
        long size = info.getEndOfFile();
        Date lastModified = new Date(info.getLastWriteTime().toEpochMillis());
        LogUtils.d("SmbRepositoryImpl", "Found matching item: " + name);
        return new SmbFileItem(name, fullPath, type, size, lastModified);
    }

    private void handleSearchException(String path, Exception e) {
        if (searchCancelled) {
            LogUtils.d("SmbRepositoryImpl", "Search cancelled during exception in directory: " + path);
        } else {
            LogUtils.e("SmbRepositoryImpl", "Error searching in directory " + path + ": " + e.getMessage());
            errorHandler.recordError(e, "SmbRepositoryImpl.searchFiles.directory:" + path, SmartErrorHandler.ErrorSeverity.MEDIUM);
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
     * This method extracts and removes the share name from the path.
     * <p>
     * Note: This method is conservative about removing path segments to avoid
     * incorrectly removing folder names that might look like share names.
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

        // Extract the first segment of the path (potential share name)
        int slashIndex = path.indexOf('/');
        if (slashIndex == -1) {
            slashIndex = path.indexOf('\\');
        }

        // If there's no slash, the path might be just a single file or folder in the root
        if (slashIndex == -1) {
            LogUtils.d("SmbRepositoryImpl", "Path without share: " + path);
            return path;
        }

        // Extract the first segment and the rest of the path
        String firstSegment = path.substring(0, slashIndex);
        String remainingPath = path.substring(slashIndex + 1);

        // Check if the first segment is likely a share name
        // In SMB paths, the share name is typically the first segment
        // For example, in "christian/Test/file.pdf", "christian" is the share name

        // IMPORTANT: We're being more conservative here to avoid removing folder names
        // Only remove the first segment if we're very confident it's a share name
        // Common share names like "users", "public", "shared", etc.
        String[] commonShareNames = {"users", "public", "shared", "documents", "media", "christian"};
        boolean isLikelyShareName = false;

        for (String shareName : commonShareNames) {
            if (firstSegment.equalsIgnoreCase(shareName)) {
                isLikelyShareName = true;
                break;
            }
        }

        // If the first segment contains dots, spaces, or other special characters,
        // it's likely not a share name (share names typically don't contain these characters)
        if (firstSegment.contains(".") || firstSegment.contains(" ") || firstSegment.contains("-") || firstSegment.contains("_")) {
            isLikelyShareName = false;
        }

        // If we're not confident it's a share name, return the full path
        if (!isLikelyShareName) {
            LogUtils.d("SmbRepositoryImpl", "First segment doesn't look like a share name: " + firstSegment);
            LogUtils.d("SmbRepositoryImpl", "Path without share: " + path);
            return path;
        }

        // The first segment is likely a share name, so remove it
        LogUtils.d("SmbRepositoryImpl", "Removed likely share name '" + firstSegment + "' from path");
        LogUtils.d("SmbRepositoryImpl", "Path without share: " + remainingPath);
        return remainingPath;
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
            return filename.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
        }

        // Convert the pattern to a regex pattern
        String regex = pattern.toLowerCase(Locale.ROOT).replace(".", "\\.")  // Escape dots
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
        return filename.toLowerCase(Locale.ROOT).matches(regex);
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
        List<FileIdBothDirectoryInformation> files;
        try {
            files = share.list(remotePath);
            LogUtils.d("SmbRepositoryImpl", "Found " + files.size() + " items in folder: " + remotePath);
        } catch (Exception e) {
            LogUtils.e("SmbRepositoryImpl", "Error listing files in folder: " + remotePath + " - " + e.getMessage());
            errorHandler.recordError(e, "SmbRepositoryImpl.downloadFolderContents.list:" + remotePath, SmartErrorHandler.ErrorSeverity.HIGH);
            throw new IOException("Error listing files in folder: " + remotePath, e);
        }

        for (FileIdBothDirectoryInformation file : files) {
            String fileName = file.getFileName();
            if (".".equals(fileName) || "..".equals(fileName)) continue;

            String remoteFilePath = remotePath.isEmpty() || remotePath.equals("\\") ? "\\" + fileName : remotePath + "\\" + fileName;
            java.io.File localFile = new java.io.File(localFolder, fileName);

            if ((file.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0) {
                if (!localFile.exists() && !localFile.mkdir()) {
                    String errorMessage = "Failed to create local directory: " + localFile.getAbsolutePath();
                    LogUtils.e("SmbRepositoryImpl", errorMessage);
                    throw new IOException(errorMessage);
                }
                downloadFolderContents(share, remoteFilePath, localFile);
            } else {
                downloadFileWithRetry(share, remoteFilePath, localFile);
            }
        }
    }

    private void downloadFileWithRetry(DiskShare share, String remoteFilePath, java.io.File localFile) throws IOException {
        int maxRetries = 3;
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (attempt > 1) {
                LogUtils.i("SmbRepositoryImpl", "Retrying file download (attempt " + attempt + " of " + maxRetries + "): " + remoteFilePath);
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try (File remoteFile = share.openFile(remoteFilePath, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)) {
                long resumeFrom = (attempt > 1 && localFile.exists()) ? localFile.length() : 0;
                try (InputStream is = remoteFile.getInputStream(); java.io.FileOutputStream fos = new java.io.FileOutputStream(localFile, resumeFrom > 0)) {
                    if (resumeFrom > 0) {
                        long skipped = is.skip(resumeFrom);
                        LogUtils.d("SmbRepositoryImpl", "Skipped " + skipped + " bytes for resume");
                    }
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalBytes = resumeFrom;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                        totalBytes += bytesRead;
                    }
                    LogUtils.i("SmbRepositoryImpl", "File downloaded successfully: " + localFile.getAbsolutePath() + " (" + totalBytes + " bytes)");
                    return;
                }
            } catch (Exception e) {
                LogUtils.e("SmbRepositoryImpl", "Error downloading file (attempt " + attempt + "): " + e.getMessage());
                lastException = e;

                // Special handling for background-related connection errors
                if (isBackgroundRelatedError(e)) {
                    LogUtils.w("SmbRepositoryImpl", "Background-related connection error detected - forcing fresh connection");
                    // For background errors, abort immediately and allow reconnection
                    break;
                }
            }
        }
        throw new IOException("Error downloading file: " + remoteFilePath, lastException);
    }

    // Generic helper method for SMB operations with Background-Awareness
    private <T> T withShare(SmbConnection connection, SmbShareCallback<T> callback) throws Exception {
        return withShareWithRetry(connection, callback, 1);
    }

    // Extended withShare with Retry-Logic for Background-Problems
    private <T> T withShareWithRetry(SmbConnection connection, SmbShareCallback<T> callback, int attempt) throws Exception {
        final int MAX_ATTEMPTS = 3;
        operationLock.lock();
        try (Connection conn = smbClient.connect(connection.getServer())) {
            AuthenticationContext authContext = createAuthContext(connection);
            try (Session session = conn.authenticate(authContext)) {
                String shareName = getShareName(connection.getShare());
                try (DiskShare share = (DiskShare) session.connectShare(shareName)) {

                    // Check connection status before use
                    if (!share.isConnected()) {
                        throw new IOException("Share connection failed after creation: " + shareName);
                    }

                    LogUtils.d("SmbRepositoryImpl", "Share connection established: " + shareName + " (attempt " + attempt + ")");
                    return callback.doWithShare(share);
                }
            }
        } catch (Exception e) {
            LogUtils.w("SmbRepositoryImpl", "Share operation failed (attempt " + attempt + "): " + e.getMessage());
            recordErrorWithContext(e, "shareOperation", "attempt:" + attempt);

            // For background-related errors: Retry with exponential backoff
            if (attempt < MAX_ATTEMPTS && isBackgroundRelatedError(e)) {
                LogUtils.i("SmbRepositoryImpl", "Retrying share operation due to background-related error (attempt " + (attempt + 1) + "/" + MAX_ATTEMPTS + ")");

                try {
                    Thread.sleep(1000L * attempt); // 1s, 2s, 3s backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Share retry interrupted", ie);
                }

                return withShareWithRetry(connection, callback, attempt + 1);
            }

            // Non-retryable error or maximum attempts reached
            throw new IOException("Failed to execute share operation after " + attempt + " attempts", e);
        } finally {
            operationLock.unlock();
        }
    }

    @Override
    public List<SmbFileItem> listFiles(SmbConnection connection, String path) throws Exception {
        String folderPath = path == null || path.isEmpty() ? "" : path;
        LogUtils.d("SmbRepositoryImpl", "Listing files in path: " + folderPath + " on server: " + connection.getServer());
        return withShare(connection, share -> {
            List<SmbFileItem> result = new ArrayList<>();
            for (FileIdBothDirectoryInformation info : share.list(folderPath)) {
                if (".".equals(info.getFileName()) || "..".equals(info.getFileName())) continue;
                String name = info.getFileName();
                String fullPath = folderPath.isEmpty() ? name : folderPath + "/" + name;
                boolean isDirectory = (info.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
                SmbFileItem.Type type = isDirectory ? SmbFileItem.Type.DIRECTORY : SmbFileItem.Type.FILE;
                long size = info.getEndOfFile();
                Date lastModified = new Date(info.getLastWriteTime().toEpochMillis());
                result.add(new SmbFileItem(name, fullPath, type, size, lastModified));
            }
            LogUtils.i("SmbRepositoryImpl", "Listed " + result.size() + " items in " + folderPath);
            return result;
        });
    }

    @Override
    public boolean testConnection(SmbConnection connection) throws Exception {
        LogUtils.d("SmbRepositoryImpl", "Testing connection to server: " + connection.getServer() + ", share: " + connection.getShare());
        return withShare(connection, share -> {
            boolean connected = share.isConnected();
            LogUtils.i("SmbRepositoryImpl", "Test connection result: " + connected);
            return connected;
        });
    }

    @Override
    public void deleteFile(SmbConnection connection, String path) throws Exception {
        LogUtils.d("SmbRepositoryImpl", "Deleting file/directory: " + path);
        withShare(connection, share -> {
            String filePath = getPathWithoutShare(path);
            if (share.fileExists(filePath)) {
                share.rm(filePath);
                LogUtils.i("SmbRepositoryImpl", "File deleted successfully: " + filePath);
            } else if (share.folderExists(filePath)) {
                share.rmdir(filePath, true);
                LogUtils.i("SmbRepositoryImpl", "Directory deleted successfully: " + filePath);
            } else {
                LogUtils.w("SmbRepositoryImpl", "File or directory not found: " + filePath);
                throw new IOException("File or directory not found: " + filePath);
            }
            return null;
        });
    }

    @Override
    public void renameFile(SmbConnection connection, String oldPath, String newName) throws Exception {
        LogUtils.d("SmbRepositoryImpl", "Renaming file/directory: " + oldPath + " to " + newName);
        withShare(connection, share -> {
            String oldFilePath = getPathWithoutShare(oldPath);
            String parentPath = "";
            int lastSlash = oldFilePath.lastIndexOf('/');
            if (lastSlash > 0) parentPath = oldFilePath.substring(0, lastSlash);
            String newFilePath = parentPath.isEmpty() ? newName : parentPath + "/" + newName;

            if (share.fileExists(newFilePath) || share.folderExists(newFilePath)) {
                throw new IOException("Target path already exists: " + newFilePath);
            }

            if (share.fileExists(oldFilePath)) {
                renameSmbFile(share, oldFilePath, newFilePath);
            } else if (share.folderExists(oldFilePath)) {
                renameSmbDirectory(share, oldFilePath, newFilePath);
            } else {
                throw new IOException("File or directory not found: " + oldFilePath);
            }
            return null;
        });
    }

    private void renameSmbFile(DiskShare share, String oldFilePath, String newFilePath) throws IOException {
        try (File file = share.openFile(oldFilePath, EnumSet.of(AccessMask.GENERIC_ALL), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)) {
            file.rename(newFilePath, false);
            LogUtils.i("SmbRepositoryImpl", "File renamed successfully using File.rename from " + oldFilePath + " to " + newFilePath);
        }
    }

    private void renameSmbDirectory(DiskShare share, String oldFilePath, String newFilePath) throws IOException {
        try (Directory directory = share.openDirectory(oldFilePath, EnumSet.of(AccessMask.GENERIC_ALL), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)) {
            directory.rename(newFilePath, false);
            LogUtils.i("SmbRepositoryImpl", "Directory renamed successfully using Directory.rename from " + oldFilePath + " to " + newFilePath);
        }
    }

    @Override
    public void createDirectory(SmbConnection connection, String path, String name) throws Exception {
        LogUtils.d("SmbRepositoryImpl", "Creating directory: " + name + " in path: " + path);
        withShare(connection, share -> {
            String dirPath = getPathWithoutShare(path);
            String newDirPath = dirPath.isEmpty() ? name : dirPath + "/" + name;
            share.mkdir(newDirPath);
            LogUtils.i("SmbRepositoryImpl", "Directory created successfully: " + newDirPath);
            return null;
        });
    }

    @Override
    public boolean fileExists(SmbConnection connection, String path) throws Exception {
        LogUtils.d("SmbRepositoryImpl", "Checking if file exists: " + path);
        return withShare(connection, share -> {
            String filePath = getPathWithoutShare(path);
            boolean exists = share.fileExists(filePath);
            LogUtils.i("SmbRepositoryImpl", "File " + (exists ? "exists" : "does not exist") + ": " + filePath);
            return exists;
        });
    }

    @Override
    public void downloadFile(SmbConnection connection, String remotePath, java.io.File localFile) throws Exception {
        LogUtils.d("SmbRepositoryImpl", "Downloading file: " + remotePath + " to " + localFile.getAbsolutePath());

        // For larger downloads: Use Background Service
        if (shouldUseBackgroundService(connection, remotePath)) {
            downloadFileWithBackgroundService(connection, remotePath, localFile);
        } else {
            downloadFileDirectly(connection, remotePath, localFile);
        }
    }

    /**
     * Decides whether the background service should be used for download
     */
    private boolean shouldUseBackgroundService(SmbConnection connection, String remotePath) {
        // Use Background Service for all downloads to ensure connection stability
        return true;
    }

    /**
     * Download mit Background Service Integration
     */
    private void downloadFileWithBackgroundService(SmbConnection connection, String remotePath, java.io.File localFile) throws Exception {
        String operationId = "download_" + System.currentTimeMillis();
        String operationName = "Download: " + localFile.getName();

        try {
            backgroundManager.executeBackgroundOperation(operationId, operationName, (BackgroundSmbManager.ProgressCallback callback) -> {
                return downloadFileDirectly(connection, remotePath, localFile, callback);
            }).get(); // Warten auf Completion

        } catch (Exception e) {
            LogUtils.e("SmbRepositoryImpl", "Background download failed: " + e.getMessage());
            throw new Exception("Download failed: " + e.getMessage(), e);
        }
    }

    /**
     * Downloads a file directly from the specified remote SMB path to the given local file.
     *
     * @param connection the SMB connection to use for downloading the file
     * @param remotePath the path of the remote file to download
     * @param localFile  the local file to which the remote file will be downloaded
     * @return always returns null
     * @throws Exception if an error occurs during the download process
     */
    private Void downloadFileDirectly(SmbConnection connection, String remotePath, java.io.File localFile) throws Exception {
        return downloadFileDirectly(connection, remotePath, localFile, null);
    }

    /**
     * Downloads a file directly from an SMB share to a local file.
     * <p>
     * This method connects to the specified SMB share, checks for the existence of the remote file,
     * and streams its contents to the provided local file. Progress updates are reported via the
     * {@link BackgroundSmbManager.ProgressCallback} interface. The download can be cancelled at any time,
     * in which case an {@link IOException} is thrown.
     * </p>
     *
     * @param connection       The SMB connection to use for accessing the share.
     * @param remotePath       The full path to the remote file on the SMB share.
     * @param localFile        The local file to which the remote file will be downloaded.
     * @param progressCallback Callback for reporting download progress, may be {@code null}.
     * @return Always returns {@code null}.
     * @throws Exception If the file does not exist, the download is cancelled, or any I/O error occurs.
     */
    private Void downloadFileDirectly(SmbConnection connection, String remotePath, java.io.File localFile, BackgroundSmbManager.ProgressCallback progressCallback) throws Exception {
        // Reset download cancellation flag at the start
        downloadCancelled = false;

        withShare(connection, share -> {
            String filePath = getPathWithoutShare(remotePath);
            if (!share.fileExists(filePath)) {
                throw new IOException("File not found: " + filePath);
            }

            // Check if download was cancelled before starting
            if (downloadCancelled) {
                LogUtils.i("SmbRepositoryImpl", "Download cancelled before starting: " + filePath);
                throw new IOException("Download was cancelled by user");
            }

            try (File remoteFile = share.openFile(filePath, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null); InputStream is = remoteFile.getInputStream(); java.io.FileOutputStream fos = new java.io.FileOutputStream(localFile)) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytes = 0;
                long fileSize = remoteFile.getFileInformation().getStandardInformation().getEndOfFile();

                while ((bytesRead = is.read(buffer)) != -1) {
                    // Check for cancellation during download
                    if (downloadCancelled) {
                        LogUtils.i("SmbRepositoryImpl", "Download cancelled during transfer: " + filePath);
                        throw new IOException("Download was cancelled by user");
                    }

                    fos.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;

                    // Progress Update
                    if (progressCallback != null && fileSize > 0) {
                        int progress = (int) ((totalBytes * 100) / fileSize);
                        progressCallback.updateProgress("Download: " + progress + "% (" + EnhancedFileUtils.formatFileSize(totalBytes) + " / " + EnhancedFileUtils.formatFileSize(fileSize) + ")");
                    }
                }

                LogUtils.i("SmbRepositoryImpl", "File downloaded successfully: " + localFile.getAbsolutePath() + " (" + totalBytes + " bytes)");
            }
            return null;
        });
        return null;
    }

    /**
     * Downloads a file from the SMB server using a local file path.
     *
     * @param connection    The SMB connection to use
     * @param remotePath    The path to the file on the SMB server
     * @param localFilePath The path to the local file to save the downloaded file to
     * @throws Exception if an error occurs during the download
     */
    public void downloadFile(SmbConnection connection, String remotePath, String localFilePath) throws Exception {
        downloadFile(connection, remotePath, new java.io.File(localFilePath));
    }

    @Override
    public void uploadFile(SmbConnection connection, java.io.File localFile, String remotePath) throws Exception {
        LogUtils.d("SmbRepositoryImpl", "Uploading file: " + localFile.getAbsolutePath() + " to " + remotePath);

        // For larger uploads: Use Background Service
        if (shouldUseBackgroundService(connection, remotePath)) {
            uploadFileWithBackgroundService(connection, localFile, remotePath);
        } else {
            uploadFileDirectly(connection, localFile, remotePath);
        }
    }

    @Override
    public void uploadFileWithProgress(SmbConnection connection, java.io.File localFile, String remotePath, BackgroundSmbManager.ProgressCallback progressCallback) throws Exception {
        LogUtils.d("SmbRepositoryImpl", "Uploading file with progress: " + localFile.getAbsolutePath() + " to " + remotePath);

        // For progress tracking, we should use direct upload to get fine-grained updates
        uploadFileDirectly(connection, localFile, remotePath, progressCallback);
    }

    /**
     * Uploads a local file to a remote SMB location using a background service.
     * <p>
     * This method initiates a background operation to upload the specified local file
     * to the given remote path via the provided SMB connection. The operation is tracked
     * with a unique ID and name for progress monitoring. If the upload fails, an exception
     * is thrown with details about the failure.
     * </p>
     *
     * @param connection the SMB connection to use for uploading the file
     * @param localFile  the local file to be uploaded
     * @param remotePath the destination path on the remote SMB server
     * @throws Exception if the upload operation fails
     */
    private void uploadFileWithBackgroundService(SmbConnection connection, java.io.File localFile, String remotePath) throws Exception {
        String operationId = "upload_" + System.currentTimeMillis();
        String operationName = "Upload: " + localFile.getName();

        try {
            backgroundManager.executeBackgroundOperation(operationId, operationName, (BackgroundSmbManager.ProgressCallback callback) -> {
                return uploadFileDirectly(connection, localFile, remotePath, callback);
            }).get(); // Wait for completion

        } catch (Exception e) {
            LogUtils.e("SmbRepositoryImpl", "Background upload failed: " + e.getMessage());
            throw new Exception("Upload failed: " + e.getMessage(), e);
        }
    }

    /**
     * Uploads a local file directly to the specified remote path using the provided SMB connection.
     * This method delegates to the overloaded version of {@code uploadFileDirectly} with a {@code null} progress listener.
     *
     * @param connection the SMB connection to use for uploading the file
     * @param localFile  the local file to be uploaded
     * @param remotePath the destination path on the remote SMB server
     * @return always returns {@code null}
     * @throws Exception if an error occurs during the upload process
     */
    private Void uploadFileDirectly(SmbConnection connection, java.io.File localFile, String remotePath) throws Exception {
        return uploadFileDirectly(connection, localFile, remotePath, null);
    }

    /**
     * Uploads a local file directly to a remote SMB share.
     *
     * <p>This method opens the specified local file and writes its contents to the remote SMB share at the given path.
     * It supports progress updates via the provided {@link BackgroundSmbManager.ProgressCallback} and allows for
     * cancellation of the upload process. If the upload is cancelled before or during the transfer, an {@link IOException}
     * is thrown. The method logs relevant information about the upload process, including cancellation and completion.</p>
     *
     * @param connection       The SMB connection to use for uploading the file.
     * @param localFile        The local file to upload.
     * @param remotePath       The remote path (including filename) where the file should be uploaded.
     * @param progressCallback Optional callback for reporting upload progress.
     * @return Always returns {@code null}.
     * @throws Exception If an error occurs during upload, including cancellation or I/O errors.
     */
    private Void uploadFileDirectly(SmbConnection connection, java.io.File localFile, String remotePath, BackgroundSmbManager.ProgressCallback progressCallback) throws Exception {
        // Reset upload cancellation flag at the start of a new upload
        uploadCancelled = false;

        // Check if upload was cancelled before starting
        if (uploadCancelled) {
            LogUtils.i("SmbRepositoryImpl", "Upload cancelled before starting: " + localFile.getName());
            throw new IOException("Upload was cancelled by user");
        }

        withShare(connection, share -> {
            String filePath = getPathWithoutShare(remotePath);
            try (File remoteFile = share.openFile(filePath, EnumSet.of(AccessMask.GENERIC_WRITE), EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null); java.io.FileInputStream fis = new java.io.FileInputStream(localFile); OutputStream os = remoteFile.getOutputStream()) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytes = 0;
                long fileSize = localFile.length();

                while ((bytesRead = fis.read(buffer)) != -1) {
                    // Check for cancellation during upload
                    if (uploadCancelled) {
                        LogUtils.i("SmbRepositoryImpl", "Upload cancelled during transfer: " + localFile.getName());
                        throw new IOException("Upload was cancelled by user");
                    }

                    os.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;

                    // Progress Update
                    if (progressCallback != null && fileSize > 0) {
                        int progress = (int) ((totalBytes * 100) / fileSize);
                        progressCallback.updateProgress("Upload: " + progress + "% (" + EnhancedFileUtils.formatFileSize(totalBytes) + " / " + EnhancedFileUtils.formatFileSize(fileSize) + ")");
                    }
                }

                LogUtils.i("SmbRepositoryImpl", "File uploaded successfully: " + remotePath + " (" + totalBytes + " bytes)");
            }
            return null;
        });
        return null;
    }

    @Override
    public void downloadFolder(SmbConnection connection, String remotePath, java.io.File localFolder) throws Exception {
        LogUtils.d("SmbRepositoryImpl", "Downloading folder: " + remotePath + " to " + localFolder.getAbsolutePath());

        // For folder downloads: Use Background Service with file counter
        if (shouldUseBackgroundService(connection, remotePath)) {
            downloadFolderWithBackgroundService(connection, remotePath, localFolder);
        } else {
            downloadFolderDirectly(connection, remotePath, localFolder);
        }
    }

    /**
     * Downloads a folder from a remote SMB share to a local directory using a background service.
     * The operation is tracked for progress and completion, and exceptions are handled and logged.
     *
     * @param connection  The SMB connection to use for accessing the remote folder.
     * @param remotePath  The path of the remote folder to download.
     * @param localFolder The local directory where the folder contents will be saved.
     * @throws Exception If the download operation fails or encounters an error.
     */
    private void downloadFolderWithBackgroundService(SmbConnection connection, String remotePath, java.io.File localFolder) throws Exception {
        String operationId = "download_folder_" + System.currentTimeMillis();
        String operationName = "Download Ordner: " + new java.io.File(remotePath).getName();

        // First count files for progress
        int totalFiles = countFilesInFolder(connection, remotePath);
        LogUtils.d("SmbRepositoryImpl", "Folder contains " + totalFiles + " files for download");

        try {
            backgroundManager.executeMultiFileOperation(operationId, operationName, totalFiles, (BackgroundSmbManager.MultiFileProgressCallback callback) -> {
                downloadFolderWithProgress(connection, remotePath, localFolder, callback);
                return null; // Return Void
            }).get(); // Warten auf Completion

        } catch (Exception e) {
            LogUtils.e("SmbRepositoryImpl", "Background folder download failed: " + e.getMessage());
            throw new Exception("Folder download failed: " + e.getMessage(), e);
        }
    }

    /**
     * Downloads a folder from a remote SMB share directly to a local directory.
     *
     * <p>This method establishes a connection to the SMB share, verifies the existence of the remote folder,
     * creates the local folder if it does not exist, and downloads all contents from the remote folder to the local folder.
     * If the remote folder does not exist or the local folder cannot be created, an {@link IOException} is thrown.
     *
     * @param connection  the SMB connection to use for accessing the remote share
     * @param remotePath  the full path to the remote folder on the SMB share
     * @param localFolder the local directory to which the folder contents will be downloaded
     * @return always returns {@code null}
     * @throws Exception if an error occurs during the download process, such as folder not found or failure to create local folder
     */
    private Void downloadFolderDirectly(SmbConnection connection, String remotePath, java.io.File localFolder) throws Exception {
        withShare(connection, share -> {
            String folderPath = getPathWithoutShare(remotePath);
            if (!share.folderExists(folderPath)) {
                throw new IOException("Folder not found: " + folderPath);
            }
            if (!localFolder.exists() && !localFolder.mkdirs()) {
                throw new IOException("Failed to create local folder: " + localFolder.getAbsolutePath());
            }
            downloadFolderContents(share, folderPath, localFolder);
            LogUtils.i("SmbRepositoryImpl", "Folder downloaded successfully: " + remotePath);
            return null;
        });
        return null;
    }

    /**
     * Downloads the contents of a remote SMB folder to a local directory, providing progress updates via a callback.
     * <p>
     * This method resets the download cancellation flag, verifies the existence of the remote folder,
     * creates the local folder if necessary, and initiates the download of all folder contents.
     * Progress for each file is reported through the provided {@link BackgroundSmbManager.MultiFileProgressCallback}.
     * If the download is cancelled or a background-related connection error occurs, retries are aborted immediately.
     * </p>
     *
     * @param connection       The SMB connection to use for accessing the remote folder.
     * @param remotePath       The path to the remote folder to download.
     * @param localFolder      The local directory where the folder contents will be saved.
     * @param progressCallback Callback for reporting progress of each file download.
     * @throws Exception If the remote folder does not exist, the local folder cannot be created,
     *                   the download is cancelled, or any other error occurs during the download process.
     */
    @Override
    public void downloadFolderWithProgress(SmbConnection connection, String remotePath, java.io.File localFolder, BackgroundSmbManager.MultiFileProgressCallback progressCallback) throws Exception {
        // Reset download cancellation flag at the start
        downloadCancelled = false;

        withShare(connection, share -> {
            String folderPath = getPathWithoutShare(remotePath);
            if (!share.folderExists(folderPath)) {
                throw new IOException("Folder not found: " + folderPath);
            }
            if (!localFolder.exists() && !localFolder.mkdirs()) {
                throw new IOException("Failed to create local folder: " + localFolder.getAbsolutePath());
            }

            // Check if download was cancelled before starting
            if (downloadCancelled) {
                LogUtils.i("SmbRepositoryImpl", "Folder download cancelled before starting: " + folderPath);
                throw new IOException("Download was cancelled by user");
            }

            // Count total files before starting download
            int totalFiles = countFilesRecursive(share, folderPath);
            LogUtils.d("SmbRepositoryImpl", "Folder contains " + totalFiles + " files for download");

            // Progress-Counter initialisieren
            final java.util.concurrent.atomic.AtomicInteger fileCounter = new java.util.concurrent.atomic.AtomicInteger(0);

            // Create a wrapper around the callback that includes the total file count
            final BackgroundSmbManager.MultiFileProgressCallback callbackWithTotal = new BackgroundSmbManager.MultiFileProgressCallback() {
                @Override
                public void updateFileProgress(int currentFile, String currentFileName) {
                    // Calculate percentage based on current file and total files
                    int percentage = totalFiles > 0 ? (currentFile * 100) / totalFiles : 0;

                    // Update progress with file info and percentage
                    String progressInfo = "File progress: " + percentage + "% (" + currentFile + "/" + totalFiles + ") " + currentFileName;
                    progressCallback.updateProgress(progressInfo);

                    // Include percentage and total files in the file name for the FileOperationsViewModel to parse
                    // Format: [PROGRESS:percentage:currentFile:totalFiles]currentFileName
                    String enhancedFileName = "[PROGRESS:" + percentage + ":" + currentFile + ":" + totalFiles + "]" + currentFileName;

                    // Forward the call with the enhanced file name
                    progressCallback.updateFileProgress(currentFile, enhancedFileName);
                }

                @Override
                public void updateBytesProgress(long currentBytes, long totalBytes, String fileName) {
                    progressCallback.updateBytesProgress(currentBytes, totalBytes, fileName);
                }

                @Override
                public void updateProgress(String progressInfo) {
                    progressCallback.updateProgress(progressInfo);
                }
            };

            downloadFolderContentsWithProgress(share, folderPath, localFolder, callbackWithTotal, fileCounter);

            if (downloadCancelled) {
                LogUtils.i("SmbRepositoryImpl", "Folder download was cancelled. Partial download completed: " + remotePath);
                throw new IOException("Download was cancelled by user");
            } else {
                LogUtils.i("SmbRepositoryImpl", "Folder downloaded successfully with progress: " + remotePath);
            }
            return null;
        });
    }

    @Override
    public List<String> listShares(SmbConnection connection) throws Exception {
        LogUtils.d("SmbRepositoryImpl", "Listing shares on server: " + connection.getServer());
        operationLock.lock();
        try (Connection conn = smbClient.connect(connection.getServer())) {
            AuthenticationContext authContext = createAuthContext(connection);
            try (Session session = conn.authenticate(authContext)) {
                List<String> shareList = new ArrayList<>();

                try {
                    // Try to connect to the IPC$ share to enumerate other shares
                    try (DiskShare ipcShare = (DiskShare) session.connectShare("IPC$")) {
                        // Use server manager to list shares (this is a more advanced approach)
                        // For now, we'll fallback to trying common share names
                        LogUtils.d("SmbRepositoryImpl", "Connected to IPC$ share for enumeration");
                    }
                } catch (Exception ipcException) {
                    LogUtils.d("SmbRepositoryImpl", "Could not connect to IPC$ share: " + ipcException.getMessage());
                }

                // Fallback: Try common share names
                String[] commonShares = {"Users", "Public", "Documents", "Downloads", "Music", "Pictures", "Videos", "Share", "Data", "Files", "Home", "Shared"};

                for (String shareName : commonShares) {
                    try {
                        // Try to connect to the share to see if it exists
                        try (DiskShare share = (DiskShare) session.connectShare(shareName)) {
                            if (share.isConnected()) {
                                shareList.add(shareName);
                                LogUtils.d("SmbRepositoryImpl", "Found share: " + shareName);
                            }
                        }
                    } catch (Exception e) {
                        // Share doesn't exist or is not accessible, ignore silently
                    }
                }

                // If no shares found, suggest the user enter manually
                if (shareList.isEmpty()) {
                    LogUtils.w("SmbRepositoryImpl", "No accessible shares found using common names");
                }

                LogUtils.i("SmbRepositoryImpl", "Found " + shareList.size() + " accessible shares on server: " + connection.getServer());
                return shareList;
            }
        } finally {
            operationLock.unlock();
        }
    }

    /**
     * Checks if an error was caused by background transition or memory management
     * Diese Fehler rechtfertigen eine sofortige Neuerstellung der Verbindung
     */
    private boolean isBackgroundRelatedError(Exception e) {
        String message = e.getMessage();
        if (message == null) return false;

        // Common errors after app background transition
        return message.contains("DiskShare has already been closed") || message.contains("Connection has been closed") || message.contains("Socket closed") || message.contains("Broken pipe") || message.contains("Connection reset") || message.contains("Connection refused") || message.contains("Transport") || e instanceof java.net.SocketException || e instanceof java.net.ConnectException;
    }

    /**
     * Recursively counts all files in a folder
     */
    private int countFilesInFolder(SmbConnection connection, String remotePath) throws Exception {
        return withShare(connection, share -> {
            String folderPath = getPathWithoutShare(remotePath);
            return countFilesRecursive(share, folderPath);
        });
    }

    /**
     * Recursively counts all files in a share folder
     */
    private int countFilesRecursive(DiskShare share, String path) {
        int fileCount = 0;
        try {
            List<FileIdBothDirectoryInformation> files = share.list(path);
            for (FileIdBothDirectoryInformation file : files) {
                String fileName = file.getFileName();
                if (".".equals(fileName) || "..".equals(fileName)) continue;

                boolean isDirectory = (file.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
                if (isDirectory) {
                    String remoteFilePath = path.isEmpty() || path.equals("\\") ? "\\" + fileName : path + "\\" + fileName;
                    fileCount += countFilesRecursive(share, remoteFilePath);
                } else {
                    fileCount++;
                }
            }
        } catch (Exception e) {
            LogUtils.w("SmbRepositoryImpl", "Error counting files in: " + path + " - " + e.getMessage());
        }
        return fileCount;
    }

    /**
     * Downloads the contents of a folder recursively with progress tracking.
     */
    private void downloadFolderContentsWithProgress(DiskShare share, String remotePath, java.io.File localFolder, BackgroundSmbManager.MultiFileProgressCallback progressCallback, java.util.concurrent.atomic.AtomicInteger fileCounter) throws IOException {
        LogUtils.d("SmbRepositoryImpl", "Downloading folder contents with progress: " + remotePath + " to " + localFolder.getAbsolutePath());
        List<FileIdBothDirectoryInformation> files;
        try {
            files = share.list(remotePath);
            LogUtils.d("SmbRepositoryImpl", "Found " + files.size() + " items in folder: " + remotePath);
        } catch (Exception e) {
            LogUtils.e("SmbRepositoryImpl", "Error listing files in folder: " + remotePath + " - " + e.getMessage());
            throw new IOException("Error listing files in folder: " + remotePath, e);
        }

        for (FileIdBothDirectoryInformation file : files) {
            String fileName = file.getFileName();
            if (".".equals(fileName) || "..".equals(fileName)) continue;

            // Check for cancellation before processing each file/folder
            if (downloadCancelled) {
                LogUtils.i("SmbRepositoryImpl", "Download cancelled while processing: " + fileName);
                throw new IOException("Download was cancelled by user");
            }

            String remoteFilePath = remotePath.isEmpty() || remotePath.equals("\\") ? "\\" + fileName : remotePath + "\\" + fileName;
            java.io.File localFile = new java.io.File(localFolder, fileName);

            if ((file.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0) {
                if (!localFile.exists() && !localFile.mkdir()) {
                    String errorMessage = "Failed to create local directory: " + localFile.getAbsolutePath();
                    LogUtils.e("SmbRepositoryImpl", errorMessage);
                    throw new IOException(errorMessage);
                }
                downloadFolderContentsWithProgress(share, remoteFilePath, localFile, progressCallback, fileCounter);
            } else {
                // Increment file counter and update progress
                int currentFile = fileCounter.incrementAndGet();
                progressCallback.updateFileProgress(currentFile, fileName);

                downloadFileWithProgressCallback(share, remoteFilePath, localFile, progressCallback);
            }
        }
    }

    /**
     * Downloads a file from a remote SMB share to a local file with progress updates and retry logic.
     * <p>
     * This method attempts to download the specified remote file to the given local file, providing progress
     * updates via the supplied {@link BackgroundSmbManager.MultiFileProgressCallback}. If the download fails,
     * it will retry up to a maximum number of attempts. Supports resuming downloads if partially completed.
     * Progress updates are throttled for large files to avoid excessive callbacks.
     * <p>
     * If the download is cancelled or a background-related connection error occurs, retries are aborted immediately.
     *
     * @param share            The {@link DiskShare} representing the SMB share to download from.
     * @param remoteFilePath   The path to the remote file on the SMB share.
     * @param localFile        The local {@link java.io.File} to save the downloaded content.
     * @param progressCallback Callback for reporting download progress (may be {@code null}).
     * @throws IOException If the download fails after all retries, is cancelled, or a connection error occurs.
     */
    private void downloadFileWithProgressCallback(DiskShare share, String remoteFilePath, java.io.File localFile, BackgroundSmbManager.MultiFileProgressCallback progressCallback) throws IOException {
        int maxRetries = 3;
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (attempt > 1) {
                LogUtils.i("SmbRepositoryImpl", "Retrying file download (attempt " + attempt + " of " + maxRetries + "): " + remoteFilePath);
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try (File remoteFile = share.openFile(remoteFilePath, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)) {
                long resumeFrom = (attempt > 1 && localFile.exists()) ? localFile.length() : 0;
                try (InputStream is = remoteFile.getInputStream(); java.io.FileOutputStream fos = new java.io.FileOutputStream(localFile, resumeFrom > 0)) {
                    if (resumeFrom > 0) {
                        long skipped = is.skip(resumeFrom);
                        LogUtils.d("SmbRepositoryImpl", "Skipped " + skipped + " bytes for resume");
                    }

                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalBytes = resumeFrom;
                    long fileSize = remoteFile.getFileInformation().getStandardInformation().getEndOfFile();

                    // Progress throttling for large files
                    long lastProgressUpdate = 0;
                    final long PROGRESS_UPDATE_INTERVAL = 500; // Max every 0.5 seconds (reduced from 2 seconds)

                    // Limit progress updates to max 300 times (increased from 100)
                    final int MAX_PROGRESS_UPDATES = 300;
                    final long updateThreshold = Math.max(fileSize / MAX_PROGRESS_UPDATES, 1); // Ensure at least 1 byte
                    long lastUpdateBytes = 0;

                    while ((bytesRead = is.read(buffer)) != -1) {
                        // Check for cancellation during file download
                        if (downloadCancelled) {
                            LogUtils.i("SmbRepositoryImpl", "Download cancelled during file transfer: " + localFile.getName());
                            throw new IOException("Download was cancelled by user");
                        }

                        fos.write(buffer, 0, bytesRead);
                        totalBytes += bytesRead;

                        // Progress update for bytes - but throttled for large files
                        if (progressCallback != null && fileSize > 0) {
                            long currentTime = System.currentTimeMillis();
                            // Use Math.round with floating-point division for accurate percentage calculation
                            int percentage = (int) Math.round((totalBytes * 100.0) / fileSize);

                            // Update at important milestones or after significant progress
                            boolean shouldUpdate =
                                    // Time-based throttling OR bytes-based throttling
                                    (currentTime - lastProgressUpdate >= PROGRESS_UPDATE_INTERVAL) ||
                                            // Bytes-based throttling (ensure we don't update too frequently)
                                            (totalBytes - lastUpdateBytes >= updateThreshold) ||
                                            // Important percentage milestones (every 5% instead of 10%)
                                            (percentage % 5 == 0 && percentage != 0) ||
                                            // First update (0%)
                                            (lastUpdateBytes == 0) ||
                                            // Last update (100%)
                                            (totalBytes == fileSize);

                            if (shouldUpdate) {
                                progressCallback.updateBytesProgress(totalBytes, fileSize, localFile.getName());
                                lastProgressUpdate = currentTime;
                                lastUpdateBytes = totalBytes;
                            }
                        }
                    }
                    LogUtils.i("SmbRepositoryImpl", "File downloaded successfully with progress: " + localFile.getAbsolutePath() + " (" + totalBytes + " bytes)");
                    return;
                }
            } catch (Exception e) {
                LogUtils.e("SmbRepositoryImpl", "Error downloading file (attempt " + attempt + "): " + e.getMessage());
                lastException = e;

                // If user has cancelled, stop retries immediately
                if (downloadCancelled || (e.getMessage() != null && e.getMessage().contains("cancelled by user"))) {
                    LogUtils.i("SmbRepositoryImpl", "Download was cancelled - stopping retries");
                    break;
                }

                // Special handling for background-related connection errors
                if (isBackgroundRelatedError(e)) {
                    LogUtils.w("SmbRepositoryImpl", "Background-related connection error detected - forcing fresh connection");
                    // For background errors, abort immediately and allow reconnection
                    break;
                }
            }
        }
        throw new IOException("Error downloading file: " + remoteFilePath, lastException);
    }

    /**
     * Downloads a file from the SMB server with progress tracking.
     *
     * @param connection       The SMB connection to use
     * @param remotePath       The path to the file on the SMB server
     * @param localFile        The local file to save the downloaded file to
     * @param progressCallback The callback to report progress updates
     * @throws Exception if an error occurs during the download
     */
    @Override
    public void downloadFileWithProgress(SmbConnection connection, String remotePath, java.io.File localFile, BackgroundSmbManager.ProgressCallback progressCallback) throws Exception {
        LogUtils.d("SmbRepositoryImpl", "Starting file download with progress tracking: " + remotePath);

        operationLock.lock();
        try {
            downloadFileDirectly(connection, remotePath, localFile, progressCallback);
        } finally {
            operationLock.unlock();
        }
    }

    /**
     * Helper method to record errors to SmartErrorHandler with appropriate severity.
     */
    private void recordError(Exception e, String context, SmartErrorHandler.ErrorSeverity severity) {
        errorHandler.recordError(e, "SmbRepositoryImpl." + context, severity);
    }

    /**
     * Helper method to record errors with contextual severity assessment.
     */
    private void recordErrorWithContext(Exception e, String operation, String details) {
        SmartErrorHandler.ErrorSeverity severity = assessErrorSeverity(e);
        String context = operation + (details != null ? ":" + details : "");
        recordError(e, context, severity);
    }

    /**
     * Assesses the severity of an exception based on its type and message.
     */
    private SmartErrorHandler.ErrorSeverity assessErrorSeverity(Exception e) {
        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        String className = e.getClass().getSimpleName().toLowerCase();

        // Critical errors that prevent core functionality
        if (className.contains("outofmemory") || message.contains("out of memory")) {
            return SmartErrorHandler.ErrorSeverity.CRITICAL;
        }

        // High severity errors
        if (className.contains("authentication") || message.contains("authentication") || className.contains("access") && message.contains("denied") || className.contains("connection") && message.contains("refused")) {
            return SmartErrorHandler.ErrorSeverity.HIGH;
        }

        // Network and timeout related errors - usually medium severity
        if (className.contains("timeout") || message.contains("timeout") || className.contains("network") || message.contains("network") || className.contains("socket") || message.contains("connection")) {
            return SmartErrorHandler.ErrorSeverity.MEDIUM;
        }

        // Default to medium for unknown exceptions
        return SmartErrorHandler.ErrorSeverity.MEDIUM;
    }

    // Callback-Interface
    @FunctionalInterface
    private interface SmbShareCallback<T> {
        T doWithShare(DiskShare share) throws Exception;
    }
}

