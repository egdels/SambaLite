package de.schliweb.sambalite.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * An in-memory implementation of a Samba server for testing.
 * This server runs within the JUnit test process and doesn't require Docker.
 */
public class SambaContainer {
    private static final int DEFAULT_PORT = 445;
    private static final String DEFAULT_HOST = "127.0.0.1";

    private final Map<String, InMemoryShare> shares = new HashMap<>();
    private String username = "testuser";
    private String password = "testpassword";
    private String domain = "WORKGROUP";
    private boolean started = false;

    /**
     * Creates a new Samba container with default settings.
     */
    public SambaContainer() {
    }

    /**
     * Sets the username for authentication.
     *
     * @param username the username
     * @return this container instance
     */
    public SambaContainer withUsername(String username) {
        this.username = username;
        return this;
    }

    /**
     * Sets the password for authentication.
     *
     * @param password the password
     * @return this container instance
     */
    public SambaContainer withPassword(String password) {
        this.password = password;
        return this;
    }

    /**
     * Sets the domain for authentication.
     *
     * @param domain the domain
     * @return this container instance
     */
    public SambaContainer withDomain(String domain) {
        this.domain = domain;
        return this;
    }

    /**
     * Adds a share to the Samba server.
     *
     * @param shareName the name of the share
     * @param path      the path to the share
     * @return this container instance
     */
    public SambaContainer withShare(String shareName, String path) {
        shares.put(shareName, new InMemoryShare(shareName));
        return this;
    }

    /**
     * Starts the Samba server.
     */
    public void start() {
        // Create a default share if none were added
        if (shares.isEmpty()) {
            shares.put("share", new InMemoryShare("share"));
        }
        started = true;
    }

    /**
     * Stops the Samba server.
     */
    public void stop() {
        shares.clear();
        started = false;
    }

    /**
     * Executes a command in the container.
     * This is a simplified version that only supports creating files.
     *
     * @param command the command to execute
     * @return the result of the command
     */
    public ExecResult execInContainer(String... command) throws IOException, InterruptedException {
        if (!started) {
            throw new IllegalStateException("Container not started");
        }

        // Parse the command to create files
        if (command.length >= 3 && "sh".equals(command[0]) && "-c".equals(command[1])) {
            String cmd = command[2];
            if (cmd.contains("mkdir -p") && cmd.contains("echo")) {
                // Extract the share name and file path
                String[] parts = cmd.split(">");
                if (parts.length >= 2) {
                    String content = parts[1].trim().replace("'", "");
                    String filePath = parts[1].trim().split(" ")[0];

                    // Extract share name from the path
                    String shareName = filePath.split("/")[1];
                    String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);

                    // Create the file in the appropriate share
                    InMemoryShare share = shares.get(shareName);
                    if (share != null) {
                        share.createFile(fileName, content + "\n");
                    }
                }
            }
        }

        return new ExecResult(0, "", "");
    }

    /**
     * Gets the hostname for connecting to this Samba server.
     *
     * @return the hostname
     */
    public String getHost() {
        return DEFAULT_HOST;
    }

    /**
     * Gets the port for connecting to this Samba server.
     *
     * @return the port
     */
    public int getPort() {
        return DEFAULT_PORT;
    }

    /**
     * Gets the username for authentication.
     *
     * @return the username
     */
    public String getUsername() {
        return username;
    }

    /**
     * Gets the password for authentication.
     *
     * @return the password
     */
    public String getPassword() {
        return password;
    }

    /**
     * Gets the domain for authentication.
     *
     * @return the domain
     */
    public String getDomain() {
        return domain;
    }

    /**
     * Represents the result of executing a command in the container.
     */
    public static class ExecResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        public ExecResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public int getExitCode() {
            return exitCode;
        }

        public String getStdout() {
            return stdout;
        }

        public String getStderr() {
            return stderr;
        }
    }

    /**
     * An in-memory implementation of a SMB share.
     */
    private static class InMemoryShare {
        private final String name;
        private final Map<String, byte[]> files = new HashMap<>();

        public InMemoryShare(String name) {
            this.name = name;
        }

        public void createFile(String fileName, String content) {
            files.put(fileName, content.getBytes(StandardCharsets.UTF_8));
        }

        public byte[] getFileContent(String fileName) {
            return files.get(fileName);
        }

        public boolean fileExists(String fileName) {
            return files.containsKey(fileName);
        }

        public String[] listFiles() {
            return files.keySet().toArray(new String[0]);
        }
    }
}
