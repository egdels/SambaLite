package de.schliweb.sambalite.data.model;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 * Model class representing an SMB connection.
 */
@Setter
@Getter
public class SmbConnection implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private String name;
    private String server;
    private String share;
    private String username;
    private String password;
    private String domain;

    // Security settings (per connection)
    // If true, require SMB3 encryption for data
    private boolean encryptData = false;
    // If true, require SMB signing
    private boolean signingRequired = false;

    /**
     * Default constructor for SmbConnection.
     */
    public SmbConnection() {
    }

    @Override
    public String toString() {
        return "SmbConnection{" + "id='" + id + '\'' + ", name='" + name + '\'' + ", server='" + server + '\'' + ", share='" + share + '\'' + ", username='" + username + '\'' + ", password='********'" + ", domain='" + domain + '\'' + ", encryptData=" + encryptData + ", signingRequired=" + signingRequired + '}';
    }
}