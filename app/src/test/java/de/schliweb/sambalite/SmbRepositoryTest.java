package de.schliweb.sambalite;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.*;

import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.data.repository.SmbRepositoryImpl;
import de.schliweb.sambalite.util.SambaContainer;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Comprehensive test class for SmbRepository implementation.
 *
 * <p>This test uses an in-memory implementation of a Samba server that runs within the JUnit test
 * process and doesn't require Docker.
 */
public class SmbRepositoryTest {

  private SambaContainer sambaContainer;
  private SmbRepository smbRepository;
  private SmbConnection testConnection;

  @Before
  public void setUp() {
    // Create and start the in-memory Samba server
    sambaContainer =
        new SambaContainer()
            .withUsername("testuser")
            .withPassword("testpassword")
            .withDomain("WORKGROUP")
            .withShare("testshare", "/testshare");

    sambaContainer.start();

    // Create a test file in the in-memory server
    try {
      SambaContainer.ExecResult result =
          sambaContainer.execInContainer(
              "sh", "-c", "mkdir -p /testshare && echo 'Test content' > /testshare/testfile.txt");
      assertEquals(0, result.getExitCode());
    } catch (IOException | InterruptedException e) {
      fail("Failed to create test file in server: " + e.getMessage());
    }

    // Create a test connection
    testConnection = new SmbConnection();
    testConnection.setServer(sambaContainer.getHost());
    testConnection.setPort(sambaContainer.getPort());
    testConnection.setShare("testshare");
    testConnection.setUsername(sambaContainer.getUsername());
    testConnection.setPassword(sambaContainer.getPassword());
    testConnection.setDomain(sambaContainer.getDomain());

    // Create the repository with mock BackgroundSmbManager that executes operations synchronously
    BackgroundSmbManager mockBackgroundManager = Mockito.mock(BackgroundSmbManager.class);
    Mockito.when(
            mockBackgroundManager.executeBackgroundOperation(
                Mockito.anyString(), Mockito.anyString(), Mockito.any()))
        .thenAnswer(
            invocation -> {
              BackgroundSmbManager.BackgroundOperation<?> operation = invocation.getArgument(2);
              try {
                Object result = operation.execute(new BackgroundSmbManager.ProgressCallback() {
                  @Override
                  public void updateProgress(String info) {}
                });
                return CompletableFuture.completedFuture(result);
              } catch (Exception e) {
                CompletableFuture<Object> future = new CompletableFuture<>();
                future.completeExceptionally(e);
                return future;
              }
            });
    Mockito.when(
            mockBackgroundManager.executeMultiFileOperation(
                Mockito.anyString(), Mockito.anyString(), Mockito.any()))
        .thenAnswer(
            invocation -> {
              BackgroundSmbManager.MultiFileOperation<?> operation = invocation.getArgument(2);
              try {
                Object result = operation.execute(new BackgroundSmbManager.MultiFileProgressCallback() {
                  @Override
                  public void updateFileProgress(int current, int total, String name) {}
                  @Override
                  public void updateBytesProgress(long cur, long total, String name) {}
                  @Override
                  public void updateProgress(String info) {}
                });
                return CompletableFuture.completedFuture(result);
              } catch (Exception e) {
                CompletableFuture<Object> future = new CompletableFuture<>();
                future.completeExceptionally(e);
                return future;
              }
            });
    smbRepository = new SmbRepositoryImpl(mockBackgroundManager);
  }

  @After
  public void tearDown() {
    if (smbRepository != null) {
      smbRepository.closeConnections();
    }
    if (sambaContainer != null) {
      sambaContainer.stop();
    }
  }

  @Test
  public void testConnectionToSambaServer() throws Exception {
    boolean connected = smbRepository.testConnection(testConnection);
    assertTrue("Should be able to connect to the Samba server", connected);
  }

  @SuppressWarnings("MissingFail")
  @Test
  public void testConnectionWithEmptyCredentials() throws Exception {
    // Create a connection with empty credentials
    SmbConnection emptyCredentialsConnection = new SmbConnection();
    emptyCredentialsConnection.setServer(sambaContainer.getHost());
    emptyCredentialsConnection.setShare("testshare");
    emptyCredentialsConnection.setUsername("");
    emptyCredentialsConnection.setPassword("");

    try {
      boolean connected = smbRepository.testConnection(emptyCredentialsConnection);
      // The connection might succeed or fail depending on the server configuration,
      // but the important thing is that it doesn't throw a NullPointerException
      System.out.println("[DEBUG_LOG] Connection with empty credentials result: " + connected);
    } catch (Exception e) {
      // The test should not throw a NullPointerException related to SecretKey
      assertFalse(
          "Should not get a NullPointerException related to SecretKey",
          e.getMessage()
              .contains(
                  "Attempt to invoke interface method 'byte[] javax.crypto.SecretKey.getEncoded()' on a null object reference"));
    }
  }

  @Test
  public void testListFiles() throws Exception {
    List<SmbFileItem> files = smbRepository.listFiles(testConnection, "");

    boolean foundTestFile = false;
    for (SmbFileItem file : files) {
      if ("testfile.txt".equals(file.getName())) {
        foundTestFile = true;
        break;
      }
    }
    assertTrue("Test file should be found", foundTestFile);
  }

  @Test
  public void testDownloadFile() throws Exception {
    Path tempFile = Files.createTempFile("download-test", ".txt");
    File localFile = tempFile.toFile();
    localFile.deleteOnExit();

    try {
      smbRepository.downloadFile(testConnection, "testfile.txt", localFile);

      String content = new String(Files.readAllBytes(tempFile), UTF_8);
      assertEquals("Test content\n", content);
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  @Test
  public void testUploadFile() throws Exception {
    Path tempFile = Files.createTempFile("upload-test", ".txt");
    File localFile = tempFile.toFile();
    localFile.deleteOnExit();

    String testContent = "Upload test content";
    try (Writer writer = Files.newBufferedWriter(localFile.toPath(), UTF_8)) {
      writer.write(testContent);
    }

    try {
      String remotePath = "uploaded-file.txt";
      smbRepository.uploadFile(testConnection, localFile, remotePath);

      // Verify the file was uploaded by trying to download it
      Path downloadedFile = Files.createTempFile("downloaded-upload-test", ".txt");
      File downloadedLocalFile = downloadedFile.toFile();
      downloadedLocalFile.deleteOnExit();

      smbRepository.downloadFile(testConnection, remotePath, downloadedLocalFile);

      String downloadedContent = new String(Files.readAllBytes(downloadedFile), UTF_8);
      assertEquals(testContent, downloadedContent);

      Files.deleteIfExists(downloadedFile);
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  @Test
  public void testDeleteFile() throws Exception {
    // First, ensure the test file exists
    assertTrue("Test file should exist before deletion",
        smbRepository.fileExists(testConnection, "testfile.txt"));

    // Delete the file
    smbRepository.deleteFile(testConnection, "testfile.txt");

    // Verify the file was deleted
    boolean stillExists = smbRepository.fileExists(testConnection, "testfile.txt");
    assertFalse("File should be deleted", stillExists);
  }

  @Test
  public void testRenameFile() throws Exception {
    // First, ensure the test file exists
    assertTrue("Test file should exist before rename",
        smbRepository.fileExists(testConnection, "testfile.txt"));

    // Rename the file
    String newName = "renamed-file.txt";
    smbRepository.renameFile(testConnection, "testfile.txt", newName);

    // Verify the file was renamed
    boolean oldExists = smbRepository.fileExists(testConnection, "testfile.txt");
    boolean newExists = smbRepository.fileExists(testConnection, newName);

    assertFalse("Old file should not exist", oldExists);
    assertTrue("New file should exist", newExists);
  }

  @Test
  public void testCreateDirectory() throws Exception {
    String dirName = "test-directory";
    smbRepository.createDirectory(testConnection, "", dirName);

    // Verify the directory was created by listing files
    List<SmbFileItem> files = smbRepository.listFiles(testConnection, "");
    boolean foundDir = false;
    for (SmbFileItem file : files) {
      if (dirName.equals(file.getName()) && file.getType() == SmbFileItem.Type.DIRECTORY) {
        foundDir = true;
        break;
      }
    }
    assertTrue("Directory should be in the file listing", foundDir);
  }

  @Test
  public void testFileExists() throws Exception {
    // Check if the test file exists
    boolean exists = smbRepository.fileExists(testConnection, "testfile.txt");
    assertTrue("Test file should exist", exists);

    // Check if a non-existent file exists
    boolean nonExistentExists = smbRepository.fileExists(testConnection, "non-existent-file.txt");
    assertFalse("Non-existent file should not exist", nonExistentExists);
  }

  @Test
  public void testDownloadFolder() throws Exception {
    Path tempDir = Files.createTempDirectory("download-folder-test");
    File localFolder = tempDir.toFile();
    localFolder.deleteOnExit();

    try {
      // First, create a test directory with some files
      String dirName = "test-folder";
      smbRepository.createDirectory(testConnection, "", dirName);

      // Create some files in the directory
      File testFile1 = File.createTempFile("test1", ".txt");
      testFile1.deleteOnExit();
      try (Writer writer = Files.newBufferedWriter(testFile1.toPath(), UTF_8)) {
        writer.write("Test content 1");
      }
      smbRepository.uploadFile(testConnection, testFile1, dirName + "/test1.txt");

      File testFile2 = File.createTempFile("test2", ".txt");
      testFile2.deleteOnExit();
      try (Writer writer = Files.newBufferedWriter(testFile2.toPath(), UTF_8)) {
        writer.write("Test content 2");
      }
      smbRepository.uploadFile(testConnection, testFile2, dirName + "/test2.txt");

      // Download the folder
      smbRepository.downloadFolder(testConnection, dirName, localFolder);

      // Verify the folder was downloaded with its contents
      File downloadedFile1 = new File(localFolder, "test1.txt");
      File downloadedFile2 = new File(localFolder, "test2.txt");

      assertTrue("Downloaded file 1 should exist", downloadedFile1.exists());
      assertTrue("Downloaded file 2 should exist", downloadedFile2.exists());

      String content1 = new String(Files.readAllBytes(downloadedFile1.toPath()), UTF_8);
      String content2 = new String(Files.readAllBytes(downloadedFile2.toPath()), UTF_8);

      assertEquals("Test content 1", content1);
      assertEquals("Test content 2", content2);
    } finally {
      deleteDirectory(localFolder);
    }
  }

  // Helper method to recursively delete a directory
  private void deleteDirectory(File directory) {
    if (directory.exists()) {
      File[] files = directory.listFiles();
      if (files != null) {
        for (File file : files) {
          if (file.isDirectory()) {
            deleteDirectory(file);
          } else {
            file.delete();
          }
        }
      }
      directory.delete();
    }
  }
}
