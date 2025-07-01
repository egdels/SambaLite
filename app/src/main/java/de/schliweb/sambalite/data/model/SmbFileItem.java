package de.schliweb.sambalite.data.model;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * Model class representing an SMB file or directory item.
 */
@Setter
@Getter
public class SmbFileItem {

    private String name;
    private String path;
    private Type type;
    private long size;
    private Date lastModified;
    /**
     * Constructor for SmbFileItem.
     *
     * @param name         Name of the file or directory
     * @param path         Full path to the file or directory
     * @param type         Type of the item (FILE or DIRECTORY)
     * @param size         Size of the file in bytes (0 for directories)
     * @param lastModified Last modified date
     */
    public SmbFileItem(String name, String path, Type type, long size, Date lastModified) {
        this.name = name;
        this.path = path;
        this.type = type;
        this.size = size;
        this.lastModified = lastModified;
    }

    /**
     * Checks if this item is a directory.
     *
     * @return true if this item is a directory, false otherwise
     */
    public boolean isDirectory() {
        return type == Type.DIRECTORY;
    }


    /**
     * Checks if this item is a file.
     *
     * @return true if this item is a file, false otherwise
     */
    public boolean isFile() {
        return type == Type.FILE;
    }

    @Override
    public String toString() {
        return "SmbFileItem{" + "name='" + name + '\'' + ", path='" + path + '\'' + ", type=" + type + ", size=" + size + ", lastModified=" + lastModified + '}';
    }

    public enum Type {
        FILE, DIRECTORY
    }
}