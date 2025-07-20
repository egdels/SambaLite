package de.schliweb.sambalite.cache.key;

import de.schliweb.sambalite.cache.exception.CacheExceptionHandler;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.util.LogUtils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Generates cache keys for different types of cache entries.
 * This class centralizes the key generation logic that was previously scattered
 * throughout the original IntelligentCacheManager.
 */
public class CacheKeyGenerator {
    private static final String TAG = "CacheKeyGenerator";

    // Exception handler for reporting key generation errors
    private final CacheExceptionHandler exceptionHandler;

    /**
     * Creates a new CacheKeyGenerator.
     *
     * @param exceptionHandler The exception handler for reporting key generation errors
     */
    public CacheKeyGenerator(CacheExceptionHandler exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }

    /**
     * Generates a cache key for a file list.
     *
     * @param connection The SMB connection
     * @param path       The path to the directory
     * @return The cache key
     */
    public String generateFileListKey(SmbConnection connection, String path) {
        try {
            String connectionId = String.valueOf(connection.getId());
            String sanitizedPath = sanitizePath(path);
            return "files_conn_" + connectionId + "_path_" + sanitizedPath;
        } catch (Exception e) {
            exceptionHandler.handleException(e, "Error generating file list cache key");
            return "files_fallback_" + System.currentTimeMillis();
        }
    }

    /**
     * Generates a cache key for search results.
     *
     * @param connection        The SMB connection
     * @param path              The path to the directory
     * @param query             The search query
     * @param searchType        The type of search (0=all, 1=files only, 2=folders only)
     * @param includeSubfolders Whether to include subfolders in the search
     * @return The cache key
     */
    public String generateSearchKey(SmbConnection connection, String path, String query, int searchType, boolean includeSubfolders) {
        try {
            String connectionId = String.valueOf(connection.getId());
            String sanitizedPath = sanitizePath(path);
            String sanitizedQuery = sanitizeSearchQuery(query);
            String subfoldersFlag = includeSubfolders ? "sub_true" : "sub_false";

            return String.format(Locale.US, "search_conn_%s_path_%s_query_%s_type_%d_%s", connectionId, sanitizedPath, sanitizedQuery, searchType, subfoldersFlag);
        } catch (Exception e) {
            exceptionHandler.handleException(e, "Error generating search cache key");
            return "search_fallback_" + System.currentTimeMillis();
        }
    }

    /**
     * Generates a cache key for a specific file or folder.
     *
     * @param connection The SMB connection
     * @param path       The path to the file or folder
     * @param fileName   The name of the file or folder
     * @return The cache key
     */
    public String generateFileKey(SmbConnection connection, String path, String fileName) {
        try {
            String connectionId = String.valueOf(connection.getId());
            String sanitizedPath = sanitizePath(path);
            String sanitizedFileName = sanitizeFileName(fileName);

            return "file_conn_" + connectionId + "_path_" + sanitizedPath + "_name_" + sanitizedFileName;
        } catch (Exception e) {
            exceptionHandler.handleException(e, "Error generating file cache key");
            return "file_fallback_" + System.currentTimeMillis();
        }
    }

    /**
     * Generates a cache key for a custom object.
     *
     * @param prefix The prefix for the key
     * @param params The parameters to include in the key
     * @return The cache key
     */
    public String generateCustomKey(String prefix, String... params) {
        try {
            StringBuilder keyBuilder = new StringBuilder(prefix);

            for (String param : params) {
                if (param != null && !param.isEmpty()) {
                    keyBuilder.append("_").append(sanitizeParameter(param));
                }
            }

            return keyBuilder.toString();
        } catch (Exception e) {
            exceptionHandler.handleException(e, "Error generating custom cache key");
            return prefix + "_fallback_" + System.currentTimeMillis();
        }
    }

    /**
     * Generates a cache key pattern for invalidating cache entries.
     *
     * @param connection The SMB connection
     * @param path       The path to match
     * @return The cache key pattern
     */
    public String generateInvalidationPattern(SmbConnection connection, String path) {
        try {
            String connectionId = String.valueOf(connection.getId());
            String sanitizedPath = sanitizePath(path);

            return "conn_" + connectionId + "_path_" + sanitizedPath;
        } catch (Exception e) {
            exceptionHandler.handleException(e, "Error generating invalidation pattern");
            return "conn_fallback";
        }
    }

    /**
     * Sanitizes a path for use in a cache key.
     *
     * @param path The path to sanitize
     * @return The sanitized path
     */
    public String sanitizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "root";
        }

        // Remove leading and trailing slashes
        String sanitized = path.replaceAll("^/+|/+$", "");

        // Replace special characters with underscores
        sanitized = sanitized.replaceAll("[^a-zA-Z0-9_\\-./]", "_");

        // Replace multiple slashes with a single slash
        sanitized = sanitized.replaceAll("/+", "/");

        // Use hash for long paths to avoid key length issues
        if (sanitized.length() > 100) {
            sanitized = hashString(sanitized);
        }

        return sanitized;
    }

    /**
     * Sanitizes a search query for use in a cache key.
     *
     * @param query The query to sanitize
     * @return The sanitized query
     */
    public String sanitizeSearchQuery(String query) {
        if (query == null || query.isEmpty()) {
            return "empty";
        }

        // Replace special characters with underscores
        String sanitized = query.replaceAll("[^a-zA-Z0-9_\\-]", "_");

        // Use hash for long queries to avoid key length issues
        if (sanitized.length() > 50) {
            sanitized = hashString(sanitized);
        }

        return sanitized;
    }

    /**
     * Sanitizes a file name for use in a cache key.
     *
     * @param fileName The file name to sanitize
     * @return The sanitized file name
     */
    public String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "unnamed";
        }

        // Replace special characters with underscores
        String sanitized = fileName.replaceAll("[^a-zA-Z0-9_\\-.]", "_");

        // Use hash for long file names to avoid key length issues
        if (sanitized.length() > 50) {
            sanitized = hashString(sanitized);
        }

        return sanitized;
    }

    /**
     * Sanitizes a parameter for use in a cache key.
     *
     * @param param The parameter to sanitize
     * @return The sanitized parameter
     */
    public String sanitizeParameter(String param) {
        if (param == null || param.isEmpty()) {
            return "empty";
        }

        // Replace special characters with underscores
        String sanitized = param.replaceAll("[^a-zA-Z0-9_\\-]", "_");

        // Use hash for long parameters to avoid key length issues
        if (sanitized.length() > 50) {
            sanitized = hashString(sanitized);
        }

        return sanitized;
    }

    /**
     * Creates a hash of a string.
     *
     * @param input The string to hash
     * @return The hashed string
     */
    private String hashString(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());

            // Convert to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            // Use first 16 characters of the hash
            return hexString.toString().substring(0, 16);

        } catch (NoSuchAlgorithmException e) {
            LogUtils.e(TAG, "Error hashing string: " + e.getMessage());

            // Fallback to simple hash code
            return String.valueOf(Math.abs(input.hashCode()));
        }
    }
}