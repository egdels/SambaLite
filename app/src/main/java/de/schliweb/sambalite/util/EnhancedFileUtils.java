package de.schliweb.sambalite.util;

import java.util.Locale;

/**
 * Enhanced file operation utilities for SambaLite.
 * Provides better file handling, validation, and progress tracking.
 */
public class EnhancedFileUtils {

    /**
     * Formats file size in human-readable format.
     */
    public static String formatFileSize(long bytes) {
        if (bytes < 0) return "Unknown";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /**
     * Gets file extension from filename.
     */
    public static String getFileExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) return "";

        int lastDot = fileName.lastIndexOf('.');
        if (lastDot == -1 || lastDot == fileName.length() - 1) {
            return "";
        }

        return fileName.substring(lastDot + 1).toLowerCase(Locale.US);
    }

    /**
     * Determines file type category from extension.
     */
    public static FileType getFileType(String fileName) {
        String extension = getFileExtension(fileName);

        if (extension.isEmpty()) return FileType.UNKNOWN;

        // Image files
        if (isImageExtension(extension)) return FileType.IMAGE;

        // Video files  
        if (isVideoExtension(extension)) return FileType.VIDEO;

        // Audio files
        if (isAudioExtension(extension)) return FileType.AUDIO;

        // Document files
        if (isDocumentExtension(extension)) return FileType.DOCUMENT;

        // Archive files
        if (isArchiveExtension(extension)) return FileType.ARCHIVE;

        return FileType.OTHER;
    }

    // Helper methods for file type detection

    private static boolean isImageExtension(String ext) {
        String[] imageExts = {"jpg", "jpeg", "png", "gif", "bmp", "webp", "ico", "svg", "tiff", "tif"};
        for (String imageExt : imageExts) {
            if (imageExt.equals(ext)) return true;
        }
        return false;
    }

    private static boolean isVideoExtension(String ext) {
        String[] videoExts = {"mp4", "avi", "mkv", "mov", "wmv", "flv", "webm", "m4v", "3gp", "ogv"};
        for (String videoExt : videoExts) {
            if (videoExt.equals(ext)) return true;
        }
        return false;
    }

    private static boolean isAudioExtension(String ext) {
        String[] audioExts = {"mp3", "wav", "flac", "aac", "ogg", "wma", "m4a", "opus", "aiff"};
        for (String audioExt : audioExts) {
            if (audioExt.equals(ext)) return true;
        }
        return false;
    }

    private static boolean isDocumentExtension(String ext) {
        String[] docExts = {"pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "ods", "odp"};
        for (String docExt : docExts) {
            if (docExt.equals(ext)) return true;
        }
        return false;
    }

    private static boolean isArchiveExtension(String ext) {
        String[] archiveExts = {"zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso"};
        for (String archiveExt : archiveExts) {
            if (archiveExt.equals(ext)) return true;
        }
        return false;
    }

    // Enums and data classes

    public enum FileType {
        IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, OTHER, UNKNOWN
    }
}
