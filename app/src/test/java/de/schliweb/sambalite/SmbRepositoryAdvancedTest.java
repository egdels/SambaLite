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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Advanced test class for SmbRepository implementation.
 *
 * <p>This test class focuses on edge cases, error handling, and more complex scenarios.
 */
public class SmbRepositoryAdvancedTest {

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

  /** Test handling of special characters in filenames. */
  @Test
  public void testSpecialCharactersInFilenames() throws Exception {
    String[] specialFilenames = {
      "file with spaces.txt",
      "file_with_underscore.txt",
      "file-with-hyphens.txt",
      "file.with.dots.txt",
      "file_with_numbers_123.txt",
      "file_with_symbols_!@#$%^&()_+.txt"
    };

    for (String filename : specialFilenames) {
      Path tempFile = Files.createTempFile("special-char-test", ".txt");
      File localFile = tempFile.toFile();
      localFile.deleteOnExit();

      String testContent = "Content for " + filename;
      try (Writer writer = Files.newBufferedWriter(localFile.toPath(), UTF_8)) {
        writer.write(testContent);
      }

      smbRepository.uploadFile(testConnection, localFile, filename);

      boolean exists = smbRepository.fileExists(testConnection, filename);
      assertTrue("File with special characters should exist: " + filename, exists);

      Path downloadedFile = Files.createTempFile("downloaded-special-char", ".txt");
      File downloadedLocalFile = downloadedFile.toFile();
      downloadedLocalFile.deleteOnExit();

      smbRepository.downloadFile(testConnection, filename, downloadedLocalFile);

      String downloadedContent = new String(Files.readAllBytes(downloadedFile), UTF_8);
      assertEquals("Content should match for file: " + filename, testContent, downloadedContent);

      Files.deleteIfExists(tempFile);
      Files.deleteIfExists(downloadedFile);
    }
  }

  /** Test handling of deep nested directory structures. */
  @Test
  public void testDeepNestedDirectories() throws Exception {
    String basePath = "";
    int depth = 5;

    for (int i = 1; i <= depth; i++) {
      String dirName = "level" + i;
      String currentPath = basePath.isEmpty() ? dirName : basePath + "/" + dirName;

      if (i == 1) {
        smbRepository.createDirectory(testConnection, "", dirName);
      } else {
        smbRepository.createDirectory(testConnection, basePath, dirName);
      }

      // Verify directory exists via listFiles (fileExists only works for files, not directories)
      String parentPath = basePath;
      List<SmbFileItem> parentFiles = smbRepository.listFiles(testConnection, parentPath);
      boolean dirFound = false;
      for (SmbFileItem file : parentFiles) {
        if (dirName.equals(file.getName()) && file.getType() == SmbFileItem.Type.DIRECTORY) {
          dirFound = true;
          break;
        }
      }
      assertTrue("Directory should exist: " + currentPath, dirFound);

      Path tempFile = Files.createTempFile("nested-dir-test", ".txt");
      File localFile = tempFile.toFile();
      localFile.deleteOnExit();

      String testContent = "Content for level " + i;
      try (Writer writer = Files.newBufferedWriter(localFile.toPath(), UTF_8)) {
        writer.write(testContent);
      }

      String remoteFilePath = currentPath + "/file_at_level" + i + ".txt";
      smbRepository.uploadFile(testConnection, localFile, remoteFilePath);

      boolean fileExists = smbRepository.fileExists(testConnection, remoteFilePath);
      assertTrue("File should exist: " + remoteFilePath, fileExists);

      basePath = currentPath;

      Files.deleteIfExists(tempFile);
    }

    // Now try to list files in the deepest directory
    List<SmbFileItem> files = smbRepository.listFiles(testConnection, basePath);
    boolean foundFile = false;
    for (SmbFileItem file : files) {
      if (file.getName().equals("file_at_level" + depth + ".txt")) {
        foundFile = true;
        break;
      }
    }

    assertTrue("Should find file in the deepest directory", foundFile);
  }

  /** Test handling of empty files. */
  @Test
  public void testEmptyFiles() throws Exception {
    Path tempFile = Files.createTempFile("empty-file-test", ".txt");
    File localFile = tempFile.toFile();
    localFile.deleteOnExit();

    try (Writer writer = Files.newBufferedWriter(localFile.toPath(), UTF_8)) {
      writer.write("");
    }

    String remotePath = "empty-file.txt";
    smbRepository.uploadFile(testConnection, localFile, remotePath);

    boolean exists = smbRepository.fileExists(testConnection, remotePath);
    assertTrue("Empty file should exist", exists);

    Path downloadedFile = Files.createTempFile("downloaded-empty-file", ".txt");
    File downloadedLocalFile = downloadedFile.toFile();
    downloadedLocalFile.deleteOnExit();

    smbRepository.downloadFile(testConnection, remotePath, downloadedLocalFile);

    String downloadedContent = new String(Files.readAllBytes(downloadedFile), UTF_8);
    assertEquals("Downloaded content should be empty", "", downloadedContent);

    Files.deleteIfExists(tempFile);
    Files.deleteIfExists(downloadedFile);
  }

  /** Test handling of large files. */
  @Test
  public void testLargeFiles() throws Exception {
    Path tempFile = Files.createTempFile("large-file-test", ".dat");
    File localFile = tempFile.toFile();
    localFile.deleteOnExit();

    int fileSizeMB = 5;
    int bufferSize = 1024 * 1024;
    byte[] buffer = new byte[bufferSize];

    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(localFile)) {
      for (int i = 0; i < fileSizeMB; i++) {
        for (int j = 0; j < buffer.length; j++) {
          buffer[j] = (byte) (Math.random() * 256);
        }
        fos.write(buffer);
      }
    }

    long fileSize = Files.size(tempFile);
    assertEquals(
        "File size should be approximately " + fileSizeMB + " MB",
        (double) fileSizeMB * 1024 * 1024,
        (double) fileSize,
        1024);

    String remotePath = "large-file.dat";
    smbRepository.uploadFile(testConnection, localFile, remotePath);

    boolean exists = smbRepository.fileExists(testConnection, remotePath);
    assertTrue("Large file should exist", exists);

    Path downloadedFile = Files.createTempFile("downloaded-large-file", ".dat");
    File downloadedLocalFile = downloadedFile.toFile();
    downloadedLocalFile.deleteOnExit();

    smbRepository.downloadFile(testConnection, remotePath, downloadedLocalFile);

    long downloadedFileSize = Files.size(downloadedFile);
    assertEquals("Downloaded file size should match original", fileSize, downloadedFileSize);

    Files.deleteIfExists(tempFile);
    Files.deleteIfExists(downloadedFile);
  }

  /** Test concurrent operations. */
  @Test
  public void testConcurrentOperations() throws Exception {
    int numThreads = 10;
    ExecutorService executor = Executors.newFixedThreadPool(numThreads);

    List<Future<?>> futures = new ArrayList<>();
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);

    for (int i = 0; i < numThreads; i++) {
      final int threadNum = i;
      Future<?> future =
          executor.submit(
              () -> {
                try {
                  switch (threadNum % 5) {
                    case 0: // Upload a file
                      Path tempFile = Files.createTempFile("concurrent-test", ".txt");
                      File localFile = tempFile.toFile();
                      localFile.deleteOnExit();

                      try (Writer writer = Files.newBufferedWriter(localFile.toPath(), UTF_8)) {
                        writer.write("Content from thread " + threadNum);
                      }

                      String remotePath = "concurrent-file-" + threadNum + ".txt";
                      smbRepository.uploadFile(testConnection, localFile, remotePath);

                      boolean exists = smbRepository.fileExists(testConnection, remotePath);
                      if (exists) {
                        successCount.incrementAndGet();
                      } else {
                        failureCount.incrementAndGet();
                      }

                      Files.deleteIfExists(tempFile);
                      break;

                    case 1: // Create a directory
                      String dirName = "concurrent-dir-" + threadNum;
                      smbRepository.createDirectory(testConnection, "", dirName);

                      boolean dirExists = smbRepository.fileExists(testConnection, dirName);
                      if (dirExists) {
                        successCount.incrementAndGet();
                      } else {
                        failureCount.incrementAndGet();
                      }
                      break;

                    case 2: // List files
                      List<SmbFileItem> files = smbRepository.listFiles(testConnection, "");
                      if (files != null) {
                        successCount.incrementAndGet();
                      } else {
                        failureCount.incrementAndGet();
                      }
                      break;

                    case 3: // Test connection
                      boolean connected = smbRepository.testConnection(testConnection);
                      if (connected) {
                        successCount.incrementAndGet();
                      } else {
                        failureCount.incrementAndGet();
                      }
                      break;

                    case 4: // List files (alternative)
                      List<SmbFileItem> altFiles = smbRepository.listFiles(testConnection, "");
                      if (altFiles != null) {
                        successCount.incrementAndGet();
                      } else {
                        failureCount.incrementAndGet();
                      }
                      break;
                  }
                } catch (Exception e) {
                  failureCount.incrementAndGet();
                  System.out.println(
                      "[DEBUG_LOG] Concurrent operation exception in thread "
                          + threadNum
                          + ": "
                          + e.getMessage());
                }
              });

      futures.add(future);
    }

    for (Future<?> future : futures) {
      future.get(30, TimeUnit.SECONDS);
    }

    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);

    assertTrue(
        "At least some concurrent operations should succeed, but got "
            + successCount.get() + " successes and " + failureCount.get() + " failures",
        successCount.get() > 0);
  }

  /** Test error handling for non-existent files. */
  @Test
  public void testErrorHandlingForNonExistentFiles() throws Exception {
    // Try to download a non-existent file
    Path tempFile = Files.createTempFile("non-existent-download", ".txt");
    File localFile = tempFile.toFile();
    localFile.deleteOnExit();

    try {
      smbRepository.downloadFile(testConnection, "non-existent-file.txt", localFile);
      fail("Should throw an exception when downloading a non-existent file");
    } catch (Exception e) {
      // Expected exception
      System.out.println(
          "[DEBUG_LOG] Expected exception when downloading non-existent file: " + e.getMessage());
    }

    // Try to delete a non-existent file
    try {
      smbRepository.deleteFile(testConnection, "non-existent-file.txt");
      fail("Should throw an exception when deleting a non-existent file");
    } catch (Exception e) {
      // Expected exception
      System.out.println(
          "[DEBUG_LOG] Expected exception when deleting non-existent file: " + e.getMessage());
    }

    // Try to rename a non-existent file
    try {
      smbRepository.renameFile(testConnection, "non-existent-file.txt", "new-name.txt");
      fail("Should throw an exception when renaming a non-existent file");
    } catch (Exception e) {
      // Expected exception
      System.out.println(
          "[DEBUG_LOG] Expected exception when renaming non-existent file: " + e.getMessage());
    }

    Files.deleteIfExists(tempFile);
  }

  /** Test authentication with invalid credentials. */
  @Test
  public void testAuthenticationWithInvalidCredentials() throws Exception {
    SmbConnection invalidConnection = new SmbConnection();
    invalidConnection.setServer(sambaContainer.getHost());
    invalidConnection.setShare("testshare");
    invalidConnection.setUsername("wronguser");
    invalidConnection.setPassword("wrongpassword");
    invalidConnection.setDomain(sambaContainer.getDomain());

    // Try to connect with invalid credentials
    try {
      boolean connected = smbRepository.testConnection(invalidConnection);
      assertFalse("Connection should fail with invalid credentials", connected);
    } catch (Exception e) {
      // Expected exception - authentication failure
      System.out.println(
          "[DEBUG_LOG] Expected exception with invalid credentials: " + e.getMessage());
    }

    // Try to list files with invalid credentials
    try {
      smbRepository.listFiles(invalidConnection, "");
      fail("Should throw an exception when listing files with invalid credentials");
    } catch (Exception e) {
      // Expected exception
      System.out.println(
          "[DEBUG_LOG] Expected exception when listing files with invalid credentials: "
              + e.getMessage());
    }
  }

  /** Test handling of invalid share names. */
  @Test
  public void testInvalidShareNames() throws Exception {
    SmbConnection invalidShareConnection = new SmbConnection();
    invalidShareConnection.setServer(sambaContainer.getHost());
    invalidShareConnection.setShare("nonexistentshare");
    invalidShareConnection.setUsername(sambaContainer.getUsername());
    invalidShareConnection.setPassword(sambaContainer.getPassword());
    invalidShareConnection.setDomain(sambaContainer.getDomain());

    // Try to connect to an invalid share
    try {
      boolean connected = smbRepository.testConnection(invalidShareConnection);
      assertFalse("Connection should fail with invalid share name", connected);
    } catch (Exception e) {
      // Expected exception
      System.out.println(
          "[DEBUG_LOG] Expected exception with invalid share name: " + e.getMessage());
    }

    // Try to list files with an invalid share
    try {
      smbRepository.listFiles(invalidShareConnection, "");
      fail("Should throw an exception when listing files with invalid share name");
    } catch (Exception e) {
      // Expected exception
      System.out.println(
          "[DEBUG_LOG] Expected exception when listing files with invalid share name: "
              + e.getMessage());
    }
  }

  /** Test handling of file name collisions. */
  @Test
  public void testFileNameCollisions() throws Exception {
    Path tempFile1 = Files.createTempFile("collision-test", ".txt");
    File localFile1 = tempFile1.toFile();
    localFile1.deleteOnExit();

    String testContent1 = "Original content";
    try (Writer writer = Files.newBufferedWriter(localFile1.toPath(), UTF_8)) {
      writer.write(testContent1);
    }

    String remotePath = "collision-file.txt";
    smbRepository.uploadFile(testConnection, localFile1, remotePath);

    Path tempFile2 = Files.createTempFile("collision-test", ".txt");
    File localFile2 = tempFile2.toFile();
    localFile2.deleteOnExit();

    String testContent2 = "New content that should overwrite the original";
    try (Writer writer = Files.newBufferedWriter(localFile2.toPath(), UTF_8)) {
      writer.write(testContent2);
    }

    // Upload the second file with the same name (should overwrite)
    smbRepository.uploadFile(testConnection, localFile2, remotePath);

    Path downloadedFile = Files.createTempFile("downloaded-collision", ".txt");
    File downloadedLocalFile = downloadedFile.toFile();
    downloadedLocalFile.deleteOnExit();

    smbRepository.downloadFile(testConnection, remotePath, downloadedLocalFile);

    String downloadedContent = new String(Files.readAllBytes(downloadedFile), UTF_8);
    assertEquals(
        "Downloaded content should match the second file", testContent2, downloadedContent);

    Files.deleteIfExists(tempFile1);
    Files.deleteIfExists(tempFile2);
    Files.deleteIfExists(downloadedFile);
  }

  /** Test handling of very long file names. */
  @Test
  public void testVeryLongFileNames() throws Exception {
    StringBuilder longNameBuilder = new StringBuilder();
    for (int i = 0; i < 20; i++) {
      longNameBuilder.append("very_long_file_name_segment_");
    }
    longNameBuilder.append(".txt");
    String longFileName = longNameBuilder.toString();

    Path tempFile = Files.createTempFile("long-name-test", ".txt");
    File localFile = tempFile.toFile();
    localFile.deleteOnExit();

    String testContent = "Content for file with very long name";
    try (Writer writer = Files.newBufferedWriter(localFile.toPath(), UTF_8)) {
      writer.write(testContent);
    }

    try {
      smbRepository.uploadFile(testConnection, localFile, longFileName);

      boolean exists = smbRepository.fileExists(testConnection, longFileName);
      assertTrue("File with long name should exist", exists);

      Path downloadedFile = Files.createTempFile("downloaded-long-name", ".txt");
      File downloadedLocalFile = downloadedFile.toFile();
      downloadedLocalFile.deleteOnExit();

      smbRepository.downloadFile(testConnection, longFileName, downloadedLocalFile);

      String downloadedContent = new String(Files.readAllBytes(downloadedFile), UTF_8);
      assertEquals(
          "Content should match for file with long name", testContent, downloadedContent);

      Files.deleteIfExists(downloadedFile);
    } catch (Exception e) {
      // Some SMB implementations may have filename length limitations - this is acceptable
      System.out.println("[DEBUG_LOG] Long filename limitation: " + e.getMessage());
    }

    Files.deleteIfExists(tempFile);
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
