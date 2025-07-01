package de.schliweb.sambalite.data.model;

import lombok.Getter;
import lombok.Setter;

/**
 * Model class representing an SMB connection.
 */
@Setter
@Getter
public class SmbConnection {

    private String id;
    private String name;
    private String server;
    private String share;
    private String username;
    private String password;
    private String domain;

    /**
     * Default constructor for SmbConnection.
     */
    public SmbConnection() {
    }

    @Override
    public String toString() {
        return "SmbConnection{" + "id='" + id + '\'' + ", name='" + name + '\'' + ", server='" + server + '\'' + ", share='" + share + '\'' + ", username='" + username + '\'' + ", password='********'" + ", domain='" + domain + '\'' + '}';
    }
}