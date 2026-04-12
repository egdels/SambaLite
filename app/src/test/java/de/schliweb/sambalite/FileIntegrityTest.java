package de.schliweb.sambalite;

import static org.junit.Assert.*;

import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.data.repository.SmbRepositoryImpl;
import de.schliweb.sambalite.util.SambaContainer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Test class specifically focused on ensuring file integrity during SMB operations. These tests
 * verify that files are not lost and their sizes don't change during operations.
 */
public class FileIntegrityTest {

  private SambaContainer sambaContainer;
  private SmbRepository smbRepository;
  private SmbConnection testConnection;
  private final Random random = new Random();

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

  /**
   * Test that file integrity is maintained during upload and download operations. This test
   * verifies that files don't lose data or change size during transfer.
   */
  @Test
  public void testFileIntegrityDuringUploadAndDownload() throws Exception {
    int[] fileSizes = {0, 1024, 10 * 1024, 100 * 1024, 1024 * 1024}; // 0B, 1KB, 10KB, 100KB, 1MB

    for (int size : fileSizes) {
      Path tempFile = Files.createTempFile("integrity-test", ".dat");
      File localFile = tempFile.toFile();
      localFile.deleteOnExit();

      byte[] data = new byte[size];
      random.nextBytes(data);
      try (FileOutputStream fos = new FileOutputStream(localFile)) {
        fos.write(data);
      }

      long originalSize = Files.size(tempFile);
      String originalHash = calculateMD5(tempFile);

      String remotePath = "integrity-test-" + size + ".dat";
      smbRepository.uploadFile(testConnection, localFile, remotePath);

      boolean exists = smbRepository.fileExists(testConnection, remotePath);
      assertTrue("File should exist on server after upload (size=" + size + ")", exists);

      Path downloadedFile = Files.createTempFile("downloaded-integrity-test", ".dat");
      File downloadedLocalFile = downloadedFile.toFile();
      downloadedLocalFile.deleteOnExit();

      smbRepository.downloadFile(testConnection, remotePath, downloadedLocalFile);

      long downloadedSize = Files.size(downloadedFile);
      String downloadedHash = calculateMD5(downloadedFile);

      assertEquals(
          "File size should not change during upload/download (size=" + size + ")",
          originalSize, downloadedSize);
      assertEquals(
          "File content should not change during upload/download (size=" + size + ")",
          originalHash, downloadedHash);

      Files.deleteIfExists(downloadedFile);
      Files.deleteIfExists(tempFile);
    }
  }

  /**
   * Test that file integrity is maintained during rename operations. This test verifies that files
   * don't lose data or change size when renamed.
   */
  @Test
  public void testFileIntegrityDuringRename() throws Exception {
    int fileSize = 50 * 1024; // 50KB
    Path tempFile = Files.createTempFile("rename-test", ".dat");
    File localFile = tempFile.toFile();
    localFile.deleteOnExit();

    byte[] data = new byte[fileSize];
    random.nextBytes(data);
    try (FileOutputStream fos = new FileOutputStream(localFile)) {
      fos.write(data);
    }

    long originalSize = Files.size(tempFile);
    String originalHash = calculateMD5(tempFile);

    try {
      String originalPath = "original-file.dat";
      smbRepository.uploadFile(testConnection, localFile, originalPath);

      String newPath = "renamed-file.dat";
      smbRepository.renameFile(testConnection, originalPath, newPath);

      boolean originalExists = smbRepository.fileExists(testConnection, originalPath);
      assertFalse("Original file should not exist after rename", originalExists);

      boolean newExists = smbRepository.fileExists(testConnection, newPath);
      assertTrue("Renamed file should exist", newExists);

      Path downloadedFile = Files.createTempFile("downloaded-renamed-file", ".dat");
      File downloadedLocalFile = downloadedFile.toFile();
      downloadedLocalFile.deleteOnExit();

      smbRepository.downloadFile(testConnection, newPath, downloadedLocalFile);

      long downloadedSize = Files.size(downloadedFile);
      String downloadedHash = calculateMD5(downloadedFile);

      assertEquals(
          "File size should not change during rename", originalSize, downloadedSize);
      assertEquals(
          "File content should not change during rename", originalHash, downloadedHash);

      Files.deleteIfExists(downloadedFile);
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  /**
   * Test that file integrity is maintained during folder operations. This test verifies that files
   * don't lose data or change size when downloaded as part of a folder.
   */
  @Test
  public void testFileIntegrityDuringFolderOperations() throws Exception {
    Map<String, FileInfo> fileInfoMap = new HashMap<>();
    List<Path> tempFiles = new ArrayList<>();
    Path tempDir = null;

    try {
      String folderName = "integrity-test-folder";
      smbRepository.createDirectory(testConnection, "", folderName);

      int[] fileSizes = {1024, 10 * 1024, 50 * 1024}; // 1KB, 10KB, 50KB

      for (int i = 0; i < fileSizes.length; i++) {
        int size = fileSizes[i];
        String fileName = "file" + i + ".dat";
        String remotePath = folderName + "/" + fileName;

        Path tempFile = Files.createTempFile("folder-test", ".dat");
        tempFiles.add(tempFile);
        File localFile = tempFile.toFile();
        localFile.deleteOnExit();

        byte[] data = new byte[size];
        random.nextBytes(data);
        try (FileOutputStream fos = new FileOutputStream(localFile)) {
          fos.write(data);
        }

        long originalSize = Files.size(tempFile);
        String originalHash = calculateMD5(tempFile);

        fileInfoMap.put(fileName, new FileInfo(originalSize, originalHash));

        smbRepository.uploadFile(testConnection, localFile, remotePath);
      }

      tempDir = Files.createTempDirectory("downloaded-folder");
      File localFolder = tempDir.toFile();

      smbRepository.downloadFolder(testConnection, folderName, localFolder);

      for (int i = 0; i < fileSizes.length; i++) {
        String fileName = "file" + i + ".dat";
        File downloadedFile = new File(localFolder, fileName);

        assertTrue("Downloaded file should exist: " + fileName, downloadedFile.exists());

        FileInfo originalInfo = fileInfoMap.get(fileName);

        long downloadedSize = Files.size(downloadedFile.toPath());
        String downloadedHash = calculateMD5(downloadedFile.toPath());

        assertEquals(
            "File size should not change during folder operations: " + fileName,
            originalInfo.size, downloadedSize);
        assertEquals(
            "File content should not change during folder operations: " + fileName,
            originalInfo.hash, downloadedHash);
      }
    } finally {
      for (Path tempFile : tempFiles) {
        Files.deleteIfExists(tempFile);
      }
      if (tempDir != null) {
        deleteDirectory(tempDir.toFile());
      }
    }
  }

  /**
   * Test that file integrity is maintained during multiple operations. This test performs a
   * sequence of operations on files and verifies integrity at each step.
   */
  @Test
  public void testFileIntegrityDuringMultipleOperations() throws Exception {
    int fileSize = 25 * 1024; // 25KB
    Path tempFile = Files.createTempFile("multi-op-test", ".dat");
    File localFile = tempFile.toFile();
    localFile.deleteOnExit();

    byte[] data = new byte[fileSize];
    random.nextBytes(data);
    try (FileOutputStream fos = new FileOutputStream(localFile)) {
      fos.write(data);
    }

    long originalSize = Files.size(tempFile);
    String originalHash = calculateMD5(tempFile);

    try {
      // Step 1: Upload the file
      String originalPath = "multi-op-file.dat";
      smbRepository.uploadFile(testConnection, localFile, originalPath);

      // Step 2: Create a directory
      String folderName = "multi-op-folder";
      smbRepository.createDirectory(testConnection, "", folderName);

      // Step 3: Rename the file
      String renamedPath = "multi-op-renamed.dat";
      smbRepository.renameFile(testConnection, originalPath, renamedPath);

      // Step 4: Download the renamed file
      Path downloadedFile1 = Files.createTempFile("downloaded-renamed", ".dat");
      File downloadedLocalFile1 = downloadedFile1.toFile();
      downloadedLocalFile1.deleteOnExit();

      smbRepository.downloadFile(testConnection, renamedPath, downloadedLocalFile1);

      long downloadedSize1 = Files.size(downloadedFile1);
      String downloadedHash1 = calculateMD5(downloadedFile1);

      assertEquals(
          "File size should not change after rename and download", originalSize, downloadedSize1);
      assertEquals(
          "File content should not change after rename and download",
          originalHash, downloadedHash1);

      // Step 5: Upload the file to the folder
      String folderPath = folderName + "/multi-op-file-in-folder.dat";
      smbRepository.uploadFile(testConnection, localFile, folderPath);

      // Step 6: Download the file from the folder
      Path downloadedFile2 = Files.createTempFile("downloaded-from-folder", ".dat");
      File downloadedLocalFile2 = downloadedFile2.toFile();
      downloadedLocalFile2.deleteOnExit();

      smbRepository.downloadFile(testConnection, folderPath, downloadedLocalFile2);

      long downloadedSize2 = Files.size(downloadedFile2);
      String downloadedHash2 = calculateMD5(downloadedFile2);

      assertEquals(
          "File size should not change after folder upload and download",
          originalSize, downloadedSize2);
      assertEquals(
          "File content should not change after folder upload and download",
          originalHash, downloadedHash2);

      // Step 7: Download the entire folder
      Path tempDir = Files.createTempDirectory("downloaded-multi-op-folder");
      File localFolder = tempDir.toFile();

      smbRepository.downloadFolder(testConnection, folderName, localFolder);

      File downloadedFileInFolder = new File(localFolder, "multi-op-file-in-folder.dat");
      assertTrue("File should exist in downloaded folder", downloadedFileInFolder.exists());

      long downloadedSizeInFolder = Files.size(downloadedFileInFolder.toPath());
      String downloadedHashInFolder = calculateMD5(downloadedFileInFolder.toPath());

      assertEquals(
          "File size should not change when downloaded as part of folder",
          originalSize, downloadedSizeInFolder);
      assertEquals(
          "File content should not change when downloaded as part of folder",
          originalHash, downloadedHashInFolder);

      Files.deleteIfExists(downloadedFile1);
      Files.deleteIfExists(downloadedFile2);
      deleteDirectory(localFolder);
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  /** Calculate MD5 hash of a file. */
  private String calculateMD5(Path file) throws IOException, NoSuchAlgorithmException {
    MessageDigest md = MessageDigest.getInstance("MD5");
    md.update(Files.readAllBytes(file));
    byte[] digest = md.digest();

    StringBuilder sb = new StringBuilder();
    for (byte b : digest) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

  /** Helper method to recursively delete a directory. */
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

  /** Helper class to store file information. */
  private static class FileInfo {
    final long size;
    final String hash;

    FileInfo(long size, String hash) {
      this.size = size;
      this.hash = hash;
    }
  }
}
