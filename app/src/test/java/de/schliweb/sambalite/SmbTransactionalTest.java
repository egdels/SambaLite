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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Test class for transactional-safe SMB operations.
 * Transactional-safe means that operations are either fully completed or can be safely retried,
 * and they use the background queue for better reliability.
 */
public class SmbTransactionalTest {

    private SambaContainer sambaContainer;
    private SmbRepository smbRepository;
    private SmbConnection testConnection;
    private BackgroundSmbManager backgroundManager;

    @Before
    public void setUp() {
        System.out.println("[DEBUG_LOG] Setting up SmbTransactionalTest");
        
        // Manual setup for SambaContainer to ensure it's started
        sambaContainer = new SambaContainer()
                .withUsername("testuser")
                .withPassword("testpassword")
                .withShare("testshare", "/testshare");
        sambaContainer.start();
        
        // Wait for container to be fully ready
        try {
            Thread.sleep(2000); 
            sambaContainer.execInContainer("chmod", "0777", "/testshare");
            System.out.println("[DEBUG_LOG] Share directory /testshare permissions updated");
        } catch (Exception e) {
            System.out.println("[DEBUG_LOG] Pre-setup warning: " + e.getMessage());
        }

        // Setup connection
        testConnection = new SmbConnection();
        testConnection.setServer(sambaContainer.getHost());
        testConnection.setPort(sambaContainer.getPort());
        testConnection.setShare("testshare");
        testConnection.setUsername(sambaContainer.getUsername());
        testConnection.setPassword(sambaContainer.getPassword());
        System.out.println("[DEBUG_LOG] SmbConnection configured: " + testConnection.getServer() + ":" + testConnection.getPort() + "/" + testConnection.getShare());

        // Setup real SmbRepository with a controlled BackgroundSmbManager mock
        backgroundManager = Mockito.mock(BackgroundSmbManager.class);
        
        Mockito.when(backgroundManager.executeBackgroundOperation(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> {
                    BackgroundSmbManager.BackgroundOperation<?> operation = invocation.getArgument(2);
                    try {
                        Object result = operation.execute(new BackgroundSmbManager.ProgressCallback() {
                            @Override
                            public void updateProgress(String info) {
                                System.out.println("[DEBUG_LOG] Progress: " + info);
                            }
                            @Override
                            public void updateFileProgress(int current, int total, String name) {
                                System.out.println("[DEBUG_LOG] File Progress: " + current + "/" + total + " - " + name);
                            }
                            @Override
                            public void updateBytesProgress(long cur, long total, String name) {}
                        });
                        return CompletableFuture.completedFuture(result);
                    } catch (Exception e) {
                        System.out.println("[DEBUG_LOG] Operation failed: " + e.getMessage());
                        CompletableFuture<Object> future = new CompletableFuture<>();
                        future.completeExceptionally(e);
                        return future;
                    }
                });
        
        Mockito.when(backgroundManager.executeMultiFileOperation(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> {
                    BackgroundSmbManager.MultiFileOperation<?> operation = invocation.getArgument(2);
                    try {
                        Object result = operation.execute(new BackgroundSmbManager.MultiFileProgressCallback() {
                            @Override
                            public void updateFileProgress(int current, int total, String name) {
                                System.out.println("[DEBUG_LOG] Multi-File Progress: " + current + "/" + total + " - " + name);
                            }
                            @Override
                            public void updateBytesProgress(long cur, long total, String name) {}
                            @Override
                            public void updateProgress(String info) {}
                        });
                        return CompletableFuture.completedFuture(result);
                    } catch (Exception e) {
                        System.out.println("[DEBUG_LOG] Multi-Operation failed: " + e.getMessage());
                        CompletableFuture<Object> future = new CompletableFuture<>();
                        future.completeExceptionally(e);
                        return future;
                    }
                });

        smbRepository = new SmbRepositoryImpl(backgroundManager);
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
    public void testTransactionalSingleFileUpload() throws Exception {
        Path tempFile = Files.createTempFile("upload-test", ".txt");
        String content = "Hello SMB Transactional Upload";
        Files.write(tempFile, content.getBytes(UTF_8));
        File localFile = tempFile.toFile();

        String remotePath = "transactional_upload.txt";
        smbRepository.uploadFile(testConnection, localFile, remotePath);

        assertTrue("Uploaded file should exist", smbRepository.fileExists(testConnection, remotePath));
        
        Path downloaded = Files.createTempFile("download-verify", ".txt");
        smbRepository.downloadFile(testConnection, remotePath, downloaded.toFile());
        String downloadedContent = new String(Files.readAllBytes(downloaded), UTF_8);
        assertEquals("Content should match", content, downloadedContent);

        Files.deleteIfExists(tempFile);
        Files.deleteIfExists(downloaded);
    }

    @Test
    public void testTransactionalSingleFileDownload() throws Exception {
        String remotePath = "transactional_download.txt";
        String content = "Hello SMB Transactional Download";
        sambaContainer.execInContainer("sh", "-c", "echo '" + content + "' > /testshare/" + remotePath);

        Path tempFile = Files.createTempFile("download-test", ".txt");
        File localFile = tempFile.toFile();

        smbRepository.downloadFile(testConnection, remotePath, localFile);

        String downloadedContent = new String(Files.readAllBytes(tempFile), UTF_8);
        assertEquals("Downloaded content should match", content.trim(), downloadedContent.trim());

        Files.deleteIfExists(tempFile);
    }

    @Test
    public void testBatchFileUpload() throws Exception {
        int numFiles = 3;
        List<Path> localFiles = new ArrayList<>();
        List<String> remotePaths = new ArrayList<>();

        smbRepository.createDirectory(testConnection, "", "batch-up");

        for (int i = 0; i < numFiles; i++) {
            Path p = Files.createTempFile("batch-up-" + i, ".txt");
            Files.write(p, ("Content " + i).getBytes(UTF_8));
            localFiles.add(p);
            remotePaths.add("batch-up/file_" + i + ".txt");
            
            smbRepository.uploadFile(testConnection, p.toFile(), remotePaths.get(i));
        }

        for (String path : remotePaths) {
            assertTrue("File should exist: " + path, smbRepository.fileExists(testConnection, path));
        }

        for (Path p : localFiles) {
            Files.deleteIfExists(p);
        }
    }

    @Test
    public void testBatchFileDownload() throws Exception {
        int numFiles = 3;
        String remoteDir = "batch-down";
        sambaContainer.execInContainer("mkdir", "-p", "/testshare/" + remoteDir);
        
        for (int i = 0; i < numFiles; i++) {
            sambaContainer.execInContainer("sh", "-c", "echo 'Content " + i + "' > /testshare/" + remoteDir + "/file_" + i + ".txt");
        }

        Path localDir = Files.createTempDirectory("batch-download-local");
        List<SmbFileItem> items = smbRepository.listFiles(testConnection, remoteDir);
        assertEquals("Should find " + numFiles + " files", numFiles, items.size());

        for (SmbFileItem item : items) {
            File localFile = new File(localDir.toFile(), item.getName());
            smbRepository.downloadFile(testConnection, item.getPath(), localFile);
            assertTrue("Downloaded file should exist: " + localFile.getName(), localFile.exists());
        }

        deleteRecursive(localDir.toFile());
    }

    @Test
    public void testFolderDownload() throws Exception {
        sambaContainer.execInContainer("mkdir", "-p", "/testshare/folder-down/sub");
        sambaContainer.execInContainer("sh", "-c", "echo 'Base' > /testshare/folder-down/base.txt");
        sambaContainer.execInContainer("sh", "-c", "echo 'Sub' > /testshare/folder-down/sub/sub.txt");

        Path localBase = Files.createTempDirectory("folder-down-local");
        
        smbRepository.downloadFolder(testConnection, "folder-down", localBase.toFile());

        // Note: SmbRepositoryImpl.downloadFolder(remote, local) downloads CONTENTS of remote into local
        File baseFile = new File(localBase.toFile(), "base.txt");
        File subDir = new File(localBase.toFile(), "sub");
        File subFile = new File(subDir, "sub.txt");

        assertTrue("Base file should exist", baseFile.exists());
        assertTrue("Sub directory should exist", subDir.exists());
        assertTrue("Sub file should exist", subFile.exists());
        
        assertEquals("Base content", "Base", Files.readAllLines(baseFile.toPath()).get(0).trim());
        assertEquals("Sub content", "Sub", Files.readAllLines(subFile.toPath()).get(0).trim());

        deleteRecursive(localBase.toFile());
    }

    @Test
    public void testFolderUpload() throws Exception {
        Path localBase = Files.createTempDirectory("folder-up-local");
        Files.write(localBase.resolve("file1.txt"), "File 1".getBytes(UTF_8));
        Path subDir = Files.createDirectory(localBase.resolve("subdir"));
        Files.write(subDir.resolve("file2.txt"), "File 2".getBytes(UTF_8));

        // Since SmbRepository doesn't have a direct uploadFolder, we implement it as a transactional sequence
        uploadFolderRecursive(testConnection, localBase.toFile(), "folder-up");

        // Verify with listFiles instead of fileExists for directory
        List<SmbFileItem> items = smbRepository.listFiles(testConnection, "");
        boolean foundFolder = items.stream().anyMatch(i -> i.getName().equals("folder-up") && i.isDirectory());
        assertTrue("Remote folder-up should exist in root", foundFolder);

        assertTrue("Remote file1 should exist", smbRepository.fileExists(testConnection, "folder-up/file1.txt"));
        
        List<SmbFileItem> subItems = smbRepository.listFiles(testConnection, "folder-up");
        boolean foundSubdir = subItems.stream().anyMatch(i -> i.getName().equals("subdir") && i.isDirectory());
        assertTrue("Remote subdir should exist in folder-up", foundSubdir);
        
        assertTrue("Remote file2 should exist", smbRepository.fileExists(testConnection, "folder-up/subdir/file2.txt"));

        deleteRecursive(localBase.toFile());
    }

    private void uploadFolderRecursive(SmbConnection conn, File local, String remote) throws Exception {
        System.out.println("[DEBUG_LOG] Uploading folder recursively: " + local.getAbsolutePath() + " to " + remote);
        if (local.isDirectory()) {
            smbRepository.createDirectory(conn, "", remote);
            File[] children = local.listFiles();
            if (children != null) {
                for (File child : children) {
                    uploadFolderRecursive(conn, child, remote + "/" + child.getName());
                }
            }
        } else {
            smbRepository.uploadFile(conn, local, remote);
        }
    }

    @Test
    public void testRobustnessOnNetworkBreak() throws Exception {
        Path tempFile = Files.createTempFile("robust-test", ".txt");
        Files.write(tempFile, "Robustness Test Content".getBytes(UTF_8));
        
        AtomicInteger attempt = new AtomicInteger(0);
        BackgroundSmbManager robustBM = Mockito.mock(BackgroundSmbManager.class);
        
        Mockito.when(robustBM.executeBackgroundOperation(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> {
                    BackgroundSmbManager.BackgroundOperation<?> op = invocation.getArgument(2);
                    if (attempt.incrementAndGet() == 1) {
                        return CompletableFuture.failedFuture(new IOException("Simulated Network Break"));
                    }
                    Object res = op.execute(null);
                    return CompletableFuture.completedFuture(res);
                });

        SmbRepository robustRepo = new SmbRepositoryImpl(robustBM);
        
        try {
            robustRepo.uploadFile(testConnection, tempFile.toFile(), "robust.txt");
        } catch (Exception e) {
            System.out.println("[DEBUG_LOG] Initial upload failed as expected, retrying...");
            robustRepo.uploadFile(testConnection, tempFile.toFile(), "robust.txt");
        }

        assertTrue("File should exist after retry", robustRepo.fileExists(testConnection, "robust.txt"));
        
        Files.deleteIfExists(tempFile);
        robustRepo.closeConnections();
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }
}
