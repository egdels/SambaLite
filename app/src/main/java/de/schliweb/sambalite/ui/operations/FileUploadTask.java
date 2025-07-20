package de.schliweb.sambalite.ui.operations;

import androidx.documentfile.provider.DocumentFile;
import lombok.Getter;
import lombok.Setter;

/**
 * Represents a file upload task.
 * Extracted from FileBrowserViewModel to be used by FileOperationsViewModel.
 */
@Getter
@Setter
public class FileUploadTask {
    private DocumentFile file;
    private String relativePath;
    private boolean isDirectory;
    private boolean uploaded;
    private boolean skipped;

    public FileUploadTask(DocumentFile file, String relativePath, boolean isDirectory) {
        this.file = file;
        this.relativePath = relativePath;
        this.isDirectory = isDirectory;
        this.uploaded = false;
        this.skipped = false;
    }
}