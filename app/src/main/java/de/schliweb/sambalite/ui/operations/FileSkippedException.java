package de.schliweb.sambalite.ui.operations;

/**
 * Exception thrown when a file upload is skipped.
 * Extracted from FileBrowserViewModel to be used by FileOperationsViewModel.
 */
public class FileSkippedException extends Exception {

    /**
     * Creates a new FileSkippedException with the specified message.
     *
     * @param message The exception message
     */
    public FileSkippedException(String message) {
        super(message);
    }
}