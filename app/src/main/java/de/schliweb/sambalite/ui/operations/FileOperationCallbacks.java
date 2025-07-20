package de.schliweb.sambalite.ui.operations;

/**
 * Callback interfaces for file operations.
 * Extracted from FileBrowserViewModel to be used by FileOperationsViewModel.
 */
public class FileOperationCallbacks {

    /**
     * Callback for download operations.
     */
    public interface DownloadCallback {
        /**
         * Called when the download operation completes.
         *
         * @param success Whether the operation was successful
         * @param message A message describing the result
         */
        void onResult(boolean success, String message);

        /**
         * Called to report progress during the download.
         *
         * @param status     A status message
         * @param percentage The progress percentage (0-100)
         */
        void onProgress(String status, int percentage);
    }

    /**
     * Callback for file existence checks.
     */
    public interface FileExistsCallback {
        /**
         * Called when a file already exists.
         *
         * @param fileName      The name of the file that exists
         * @param confirmAction The action to take if the user confirms overwrite
         * @param cancelAction  The action to take if the user cancels
         */
        void onFileExists(String fileName, Runnable confirmAction, Runnable cancelAction);
    }

    /**
     * Callback for folder creation operations.
     */
    public interface CreateFolderCallback {
        /**
         * Called when the folder creation operation completes.
         *
         * @param success Whether the operation was successful
         * @param message A message describing the result
         */
        void onResult(boolean success, String message);
    }

    /**
     * Callback for file deletion operations.
     */
    public interface DeleteFileCallback {
        /**
         * Called when the file deletion operation completes.
         *
         * @param success Whether the operation was successful
         * @param message A message describing the result
         */
        void onResult(boolean success, String message);
    }

    /**
     * Callback for file rename operations.
     */
    public interface RenameFileCallback {
        /**
         * Called when the file rename operation completes.
         *
         * @param success Whether the operation was successful
         * @param message A message describing the result
         */
        void onResult(boolean success, String message);
    }

    /**
     * Callback for upload operations.
     */
    public interface UploadCallback {
        /**
         * Called when the upload operation completes.
         *
         * @param success Whether the operation was successful
         * @param message A message describing the result
         */
        void onResult(boolean success, String message);

        /**
         * Called to report progress during the upload.
         *
         * @param status     A status message
         * @param percentage The progress percentage (0-100)
         */
        void onProgress(String status, int percentage);
    }

    /**
     * Callback for detailed progress tracking.
     */
    public interface ProgressCallback {
        /**
         * Called to report file progress in multi-file operations.
         *
         * @param currentFile     The current file index
         * @param totalFiles      The total number of files
         * @param currentFileName The name of the current file
         */
        void updateFileProgress(int currentFile, int totalFiles, String currentFileName);

        /**
         * Called to report byte progress in file operations.
         *
         * @param currentBytes The current number of bytes processed
         * @param totalBytes   The total number of bytes
         * @param fileName     The name of the file being processed
         */
        void updateBytesProgress(long currentBytes, long totalBytes, String fileName);

        /**
         * Called to report general progress information.
         *
         * @param progressInfo A progress information message
         */
        void updateProgress(String progressInfo);
    }
}