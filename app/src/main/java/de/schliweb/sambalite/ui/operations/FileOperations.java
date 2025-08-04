package de.schliweb.sambalite.ui.operations;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import androidx.documentfile.provider.DocumentFile;
import de.schliweb.sambalite.util.LogUtils;

import java.io.*;

/**
 * Utility class for common file operations.
 * Reduces complexity by centralizing file handling logic.
 */
public class FileOperations {

    private static final int BUFFER_SIZE = 8192;

    /**
     * Copies a file to a content URI with proper error handling.
     */
    public static void copyFileToUri(File file, Uri uri, Context context) throws IOException {
        LogUtils.d("FileOperations", "Copying file to URI: " + uri + ", size: " + file.length() + " bytes");

        try (FileOutputStream outputStream = (FileOutputStream) context.getContentResolver().openOutputStream(uri); FileInputStream inputStream = new FileInputStream(file)) {

            if (outputStream == null) {
                throw new IOException("Failed to open output stream for URI: " + uri);
            }

            copyStream(inputStream, outputStream);
            LogUtils.d("FileOperations", "File copied successfully");

        } catch (IOException e) {
            LogUtils.e("FileOperations", "Error copying file to URI: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Recursively copies folder contents to a DocumentFile destination.
     */
    public static void copyFolderToDocumentFile(File sourceFolder, DocumentFile destFolder, Context context) throws IOException {
        LogUtils.d("FileOperations", "Copying folder: " + sourceFolder.getAbsolutePath() + " -> " + destFolder.getUri());

        File[] files = sourceFolder.listFiles();
        if (files == null) {
            LogUtils.w("FileOperations", "No files found in source folder");
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                copyDirectoryToDocumentFile(file, destFolder, context);
            } else {
                copyFileToDocumentFile(file, destFolder, context);
            }
        }
    }

    /**
     * Recursively deletes a file or directory.
     */
    public static boolean deleteRecursive(File fileOrDirectory) {
        LogUtils.d("FileOperations", "Deleting: " + fileOrDirectory.getAbsolutePath());

        if (fileOrDirectory.isDirectory()) {
            File[] files = fileOrDirectory.listFiles();
            if (files != null) {
                for (File child : files) {
                    deleteRecursive(child);
                }
            }
        }

        boolean deleted = fileOrDirectory.delete();
        if (!deleted) {
            LogUtils.w("FileOperations", "Failed to delete: " + fileOrDirectory.getAbsolutePath());
        }
        return deleted;
    }

    /**
     * Copies content from URI to a temporary file.
     */
    public static void copyUriToFile(Uri uri, File targetFile, Context context) throws IOException {
        LogUtils.d("FileOperations", "Copying URI content to file: " + targetFile.getAbsolutePath());

        try (InputStream inputStream = context.getContentResolver().openInputStream(uri); FileOutputStream outputStream = new FileOutputStream(targetFile)) {

            if (inputStream == null) {
                throw new IOException("Failed to open input stream from URI: " + uri);
            }

            copyStream(inputStream, outputStream);
            LogUtils.d("FileOperations", "URI content copied successfully");

        } catch (IOException e) {
            LogUtils.e("FileOperations", "Error copying URI to file: " + e.getMessage());
            throw e;
        }
    }

    private static void copyDirectoryToDocumentFile(File sourceDir, DocumentFile destFolder, Context context) throws IOException {
        DocumentFile newFolder = destFolder.createDirectory(sourceDir.getName());
        if (newFolder == null) {
            throw new IOException("Failed to create directory: " + sourceDir.getName());
        }
        copyFolderToDocumentFile(sourceDir, newFolder, context);
    }

    private static void copyFileToDocumentFile(File sourceFile, DocumentFile destFolder, Context context) throws IOException {
        DocumentFile newFile = destFolder.createFile("*/*", sourceFile.getName());
        if (newFile == null) {
            throw new IOException("Failed to create file: " + sourceFile.getName());
        }

        try (OutputStream outputStream = context.getContentResolver().openOutputStream(newFile.getUri()); FileInputStream inputStream = new FileInputStream(sourceFile)) {

            if (outputStream == null) {
                throw new IOException("Failed to open output stream for file: " + sourceFile.getName());
            }

            copyStream(inputStream, outputStream);
        }
    }

    private static void copyStream(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        long totalBytes = 0;

        while ((bytesRead = input.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
            totalBytes += bytesRead;
        }

        LogUtils.d("FileOperations", "Stream copied: " + totalBytes + " bytes");
    }

    /**
     * Creates a temporary file from a content URI.
     * The file is stored in the app's cache directory.
     */
    public static File createTempFileFromUri(Context context, Uri uri) throws IOException {
        String fileName = getDisplayNameFromUri(context, uri);
        File tempFile = new File(context.getCacheDir(), fileName);

        try (InputStream inputStream = context.getContentResolver().openInputStream(uri); OutputStream outputStream = new FileOutputStream(tempFile)) {

            if (inputStream == null) {
                throw new IOException("Could not open input stream from URI: " + uri);
            }

            copyStream(inputStream, outputStream);
        }

        return tempFile;
    }


    /**
     * Retrieves the display name of a file from its URI.
     * Uses OpenableColumns to get the display name if available.
     */
    public static String getDisplayNameFromUri(Context context, Uri uri) {
        String result = "shared_file";

        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    result = cursor.getString(nameIndex);
                }
            }
        } catch (Exception e) {
            LogUtils.w("FileOperations", "Could not get display name: " + e.getMessage());
        }

        return result;
    }
}
