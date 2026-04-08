package de.schliweb.sambalite.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * A wrapper for a real Samba server running in a Docker container using Testcontainers.
 * Fallbacks to in-memory implementation if Docker is not available.
 */
public class SambaContainer {
  private static final int SMB_PORT = 445;
  private static final String IMAGE_NAME = "dperson/samba:latest";

  private GenericContainer<?> container;
  private final Map<String, InMemoryShare> inMemoryShares = new HashMap<>();
  private String username = "testuser";
  private String password = "testpassword";
  private String domain = "WORKGROUP";
  private boolean started = false;
  private boolean useDocker = false;

  /** Creates a new Samba container with default settings. */
  public SambaContainer() {
    try {
        // Check if Docker is available
        this.container = new GenericContainer<>(DockerImageName.parse(IMAGE_NAME))
                .withExposedPorts(SMB_PORT)
                .waitingFor(Wait.forListeningPort());
        
        // Simple connectivity check
        org.testcontainers.DockerClientFactory.instance().client();
        this.useDocker = true;
        System.out.println("[DEBUG_LOG] SambaContainer: Using Docker for integration tests.");
    } catch (Throwable e) {
        // Log explicitly for debugging
        System.out.println("[DEBUG_LOG] SambaContainer: Docker not available, falling back to in-memory mock: " + e.getMessage());
        this.useDocker = false;
    }
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
   * @param path the path to the share
   * @return this container instance
   */
  public SambaContainer withShare(String shareName, String path) {
    if (useDocker) {
        // dperson/samba format: -s "name;path;browse;readonly;guest;users;admins;writelist"
        String shareConfig = String.format("%s;%s;yes;no;no;%s", shareName, path, username);
        container.withCommand("-u", String.format("%s;%s", username, password), "-s", shareConfig);
    } else {
        inMemoryShares.put(shareName, new InMemoryShare());
    }
    return this;
  }

  /** Starts the Samba server. */
  public void start() {
    if (useDocker) {
        container.start();
    } else {
        // Create a default share if none were added
        if (inMemoryShares.isEmpty()) {
            inMemoryShares.put("share", new InMemoryShare());
        }
    }
    started = true;
  }

  /** Stops the Samba server. */
  public void stop() {
    if (useDocker) {
        container.stop();
    } else {
        inMemoryShares.clear();
    }
    started = false;
  }

  /**
   * Executes a command in the container.
   *
   * @param command the command to execute
   * @return the result of the command
   */
  public ExecResult execInContainer(String... command) throws IOException, InterruptedException {
    if (!started) {
      throw new IllegalStateException("Container not started");
    }

    if (useDocker) {
        org.testcontainers.containers.Container.ExecResult result = container.execInContainer(command);
        return new ExecResult(result.getExitCode(), result.getStdout(), result.getStderr());
    }

    // Parse the command to create files (Mock logic)
    if (command.length >= 3 && "sh".equals(command[0]) && "-c".equals(command[1])) {
      String cmd = command[2];
      if (cmd.contains("mkdir -p") && cmd.contains("echo")) {
        // Extract the share name and file path
        String[] parts = cmd.split(">");
        if (parts.length >= 2) {
          String contentAndPath = parts[0].trim(); // echo 'content'
          String filePath = parts[1].trim();

          String content = contentAndPath.replace("echo", "").replace("'", "").trim();

          // Extract share name from the path (assuming /shareName/path)
          String cleanPath = filePath.startsWith("/") ? filePath.substring(1) : filePath;
          int firstSlash = cleanPath.indexOf('/');
          if (firstSlash != -1) {
              String shareName = cleanPath.substring(0, firstSlash);
              String fileName = cleanPath.substring(firstSlash + 1);

              InMemoryShare share = inMemoryShares.get(shareName);
              if (share != null) {
                share.createFile(fileName, content + "\n");
              }
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
    return useDocker ? container.getHost() : "127.0.0.1";
  }

  /**
   * Gets the port for connecting to this Samba server.
   *
   * @return the port
   */
  public int getPort() {
    return useDocker ? container.getMappedPort(SMB_PORT) : 445;
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

  /** Represents the result of executing a command in the container. */
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

  /** An in-memory implementation of a SMB share. */
  private static class InMemoryShare {
    private final Map<String, byte[]> files = new HashMap<>();

    InMemoryShare() {}

    void createFile(String fileName, String content) {
      files.put(fileName, content.getBytes(StandardCharsets.UTF_8));
    }
  }
}
