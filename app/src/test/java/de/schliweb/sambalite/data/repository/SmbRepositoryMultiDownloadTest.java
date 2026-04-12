package de.schliweb.sambalite.data.repository;

import static org.junit.Assert.*;

import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.test.helper.SmbTestHelper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for multi-select download with complex folder structures against a real
 * Docker-Samba server.
 *
 * <p>Tests verify: recursive directory listing for download flattening, file content integrity after
 * download, deeply nested structures, mixed files and folders, and correct path resolution.
 *
 * <p>Note: Actual file download is tested via {@code SmbTestHelper.downloadFile()} which reads
 * files directly from the SMB share. The recursive flattening logic mirrors
 * {@code TransferWorker.collectDirectoryFiles()}.
 */
public class SmbRepositoryMultiDownloadTest {

  private SmbTestHelper testHelper;

  @Before
  public void setUp() throws Exception {
    testHelper =
        new SmbTestHelper.Builder().withTestMode(SmbTestHelper.TestMode.AUTO_DETECT).build();
    Assume.assumeTrue(
        "Docker/Samba container required for integration tests", !testHelper.isMockMode());
    testHelper.setupTestData();
  }

  @After
  public void tearDown() {
    if (testHelper != null) {
      testHelper.cleanup();
    }
  }

  @Test
  public void testDownloadSingleFileFromRoot() throws Exception {
    uploadSmallFile("root-download.txt", "root content");

    InputStream is = testHelper.downloadFile("root-download.txt");
    String content = readStream(is);
    assertEquals("root content", content);
  }

  @Test
  public void testDownloadFileFromNestedDirectory() throws Exception {
    testHelper.createDirectory("nested");
    testHelper.createDirectory("nested/sub1");
    testHelper.createDirectory("nested/sub1/sub2");
    uploadSmallFile("nested/sub1/sub2/deep-file.txt", "deep content");

    InputStream is = testHelper.downloadFile("nested/sub1/sub2/deep-file.txt");
    String content = readStream(is);
    assertEquals("deep content", content);
  }

  @Test
  public void testListAndDownloadAllFilesFromDirectory() throws Exception {
    testHelper.createDirectory("multi-dl");
    uploadSmallFile("multi-dl/file1.txt", "content1");
    uploadSmallFile("multi-dl/file2.txt", "content2");
    uploadSmallFile("multi-dl/file3.txt", "content3");

    List<SmbFileItem> files = testHelper.listFiles("multi-dl");
    List<SmbFileItem> regularFiles = new ArrayList<>();
    for (SmbFileItem item : files) {
      if (item.isFile()) regularFiles.add(item);
    }
    assertEquals("Should have 3 files", 3, regularFiles.size());

    // Download each file using its path from listFiles
    Set<String> contents = new HashSet<>();
    for (SmbFileItem file : regularFiles) {
      InputStream is = testHelper.downloadFile(file.getPath());
      contents.add(readStream(is));
    }
    assertTrue("Should contain content1", contents.contains("content1"));
    assertTrue("Should contain content2", contents.contains("content2"));
    assertTrue("Should contain content3", contents.contains("content3"));
  }

  @Test
  public void testFlattenComplexDirectoryForDownload() throws Exception {
    // Create a project-like structure
    testHelper.createDirectory("project-dl");
    testHelper.createDirectory("project-dl/src");
    testHelper.createDirectory("project-dl/src/main");
    testHelper.createDirectory("project-dl/src/test");
    testHelper.createDirectory("project-dl/docs");
    uploadSmallFile("project-dl/src/main/App.java", "class App {}");
    uploadSmallFile("project-dl/src/main/Helper.java", "class Helper {}");
    uploadSmallFile("project-dl/src/test/AppTest.java", "class AppTest {}");
    uploadSmallFile("project-dl/docs/README.md", "# README");
    uploadSmallFile("project-dl/build.gradle", "apply plugin");

    // Recursively collect all files
    List<SmbFileItem> allFiles = flattenDirectory("project-dl");
    assertEquals("Should have 5 files total", 5, allFiles.size());

    Set<String> fileNames = new HashSet<>();
    for (SmbFileItem f : allFiles) {
      fileNames.add(f.getName());
    }
    assertTrue(fileNames.contains("App.java"));
    assertTrue(fileNames.contains("Helper.java"));
    assertTrue(fileNames.contains("AppTest.java"));
    assertTrue(fileNames.contains("README.md"));
    assertTrue(fileNames.contains("build.gradle"));
  }

  @Test
  public void testDownloadAllFilesFromComplexStructure() throws Exception {
    testHelper.createDirectory("complex-dl");
    testHelper.createDirectory("complex-dl/a");
    testHelper.createDirectory("complex-dl/a/b");
    testHelper.createDirectory("complex-dl/c");
    uploadSmallFile("complex-dl/root.txt", "root");
    uploadSmallFile("complex-dl/a/mid.txt", "mid");
    uploadSmallFile("complex-dl/a/b/deep.txt", "deep");
    uploadSmallFile("complex-dl/c/side.txt", "side");

    // Flatten and download each file
    List<SmbFileItem> allFiles = flattenDirectory("complex-dl");
    assertEquals("Should have 4 files", 4, allFiles.size());

    Set<String> contents = new HashSet<>();
    for (SmbFileItem file : allFiles) {
      InputStream is = testHelper.downloadFile(file.getPath());
      contents.add(readStream(is));
    }
    assertTrue(contents.contains("root"));
    assertTrue(contents.contains("mid"));
    assertTrue(contents.contains("deep"));
    assertTrue(contents.contains("side"));
  }

  @Test
  public void testDownloadPreservesFileContent() throws Exception {
    testHelper.createDirectory("content-check");
    String longContent = "Line1\nLine2\nLine3\nSpecial chars: abc xyz 123";
    uploadSmallFile("content-check/special.txt", longContent);

    InputStream is = testHelper.downloadFile("content-check/special.txt");
    String downloaded = readStream(is);
    assertEquals("Content should match exactly", longContent, downloaded);
  }

  @Test
  public void testFlattenDeeplyNestedStructure5Levels() throws Exception {
    testHelper.createDirectory("deep-dl");
    testHelper.createDirectory("deep-dl/l1");
    testHelper.createDirectory("deep-dl/l1/l2");
    testHelper.createDirectory("deep-dl/l1/l2/l3");
    testHelper.createDirectory("deep-dl/l1/l2/l3/l4");
    uploadSmallFile("deep-dl/l1/f1.txt", "level1");
    uploadSmallFile("deep-dl/l1/l2/f2.txt", "level2");
    uploadSmallFile("deep-dl/l1/l2/l3/f3.txt", "level3");
    uploadSmallFile("deep-dl/l1/l2/l3/l4/f4.txt", "level4");

    List<SmbFileItem> allFiles = flattenDirectory("deep-dl");
    assertEquals("Should have 4 files across 4 levels", 4, allFiles.size());

    // Verify each file can be downloaded with correct content
    Set<String> contents = new HashSet<>();
    for (SmbFileItem file : allFiles) {
      contents.add(readStream(testHelper.downloadFile(file.getPath())));
    }
    assertTrue(contents.contains("level1"));
    assertTrue(contents.contains("level2"));
    assertTrue(contents.contains("level3"));
    assertTrue(contents.contains("level4"));
  }

  @Test
  public void testFlattenDirectoryWithEmptySubdirectories() throws Exception {
    testHelper.createDirectory("mixed-empty-dl");
    testHelper.createDirectory("mixed-empty-dl/empty1");
    testHelper.createDirectory("mixed-empty-dl/empty2");
    testHelper.createDirectory("mixed-empty-dl/has-files");
    uploadSmallFile("mixed-empty-dl/has-files/data.txt", "data");
    uploadSmallFile("mixed-empty-dl/root-file.txt", "root");

    List<SmbFileItem> allFiles = flattenDirectory("mixed-empty-dl");
    // Only actual files, not empty directories
    assertEquals("Should have 2 files (empty dirs excluded)", 2, allFiles.size());

    Set<String> names = new HashSet<>();
    for (SmbFileItem f : allFiles) names.add(f.getName());
    assertTrue(names.contains("data.txt"));
    assertTrue(names.contains("root-file.txt"));
  }

  @Test
  public void testMultiSelectMixedFilesAndDirectories() throws Exception {
    // Simulate multi-select: user selects a file and a directory
    uploadSmallFile("standalone.txt", "standalone");
    testHelper.createDirectory("folder-sel");
    uploadSmallFile("folder-sel/inner1.txt", "inner1");
    uploadSmallFile("folder-sel/inner2.txt", "inner2");

    // Collect files as multi-select would: standalone file + flattened directory
    List<SmbFileItem> downloadList = new ArrayList<>();

    // Add standalone file directly
    List<SmbFileItem> rootFiles = testHelper.listFiles("");
    for (SmbFileItem item : rootFiles) {
      if ("standalone.txt".equals(item.getName())) {
        downloadList.add(item);
        break;
      }
    }

    // Flatten the selected directory
    downloadList.addAll(flattenDirectory("folder-sel"));

    assertEquals(
        "Should have 3 files total (1 standalone + 2 from folder)", 3, downloadList.size());

    // Verify all can be downloaded
    Set<String> contents = new HashSet<>();
    for (SmbFileItem file : downloadList) {
      contents.add(readStream(testHelper.downloadFile(file.getPath())));
    }
    assertTrue(contents.contains("standalone"));
    assertTrue(contents.contains("inner1"));
    assertTrue(contents.contains("inner2"));
  }

  @Test
  public void testFlattenDirectoryWithManyFiles() throws Exception {
    testHelper.createDirectory("many-dl");
    for (int i = 0; i < 20; i++) {
      uploadSmallFile("many-dl/file" + i + ".txt", "content" + i);
    }

    List<SmbFileItem> allFiles = flattenDirectory("many-dl");
    assertEquals("Should have 20 files", 20, allFiles.size());

    // Verify a sample of downloads
    Set<String> contents = new HashSet<>();
    for (SmbFileItem file : allFiles) {
      contents.add(readStream(testHelper.downloadFile(file.getPath())));
    }
    assertEquals("All 20 files should have unique content", 20, contents.size());
  }

  @Test
  public void testFlattenMultipleSelectedDirectories() throws Exception {
    // Simulate selecting multiple directories at once
    testHelper.createDirectory("sel-dir-a");
    testHelper.createDirectory("sel-dir-b");
    testHelper.createDirectory("sel-dir-c");
    uploadSmallFile("sel-dir-a/a1.txt", "a1");
    uploadSmallFile("sel-dir-a/a2.txt", "a2");
    uploadSmallFile("sel-dir-b/b1.txt", "b1");
    uploadSmallFile("sel-dir-c/c1.txt", "c1");
    uploadSmallFile("sel-dir-c/c2.txt", "c2");
    uploadSmallFile("sel-dir-c/c3.txt", "c3");

    // Flatten all three directories (as multi-select would)
    List<SmbFileItem> allFiles = new ArrayList<>();
    allFiles.addAll(flattenDirectory("sel-dir-a"));
    allFiles.addAll(flattenDirectory("sel-dir-b"));
    allFiles.addAll(flattenDirectory("sel-dir-c"));

    assertEquals("Should have 6 files from 3 directories", 6, allFiles.size());

    Set<String> contents = new HashSet<>();
    for (SmbFileItem file : allFiles) {
      contents.add(readStream(testHelper.downloadFile(file.getPath())));
    }
    assertEquals("All 6 files should have unique content", 6, contents.size());
  }

  @Test
  public void testDownloadFilePathsPreserveStructure() throws Exception {
    testHelper.createDirectory("struct-dl");
    testHelper.createDirectory("struct-dl/sub");
    uploadSmallFile("struct-dl/top.txt", "top");
    uploadSmallFile("struct-dl/sub/bottom.txt", "bottom");

    List<SmbFileItem> allFiles = flattenDirectory("struct-dl");
    assertEquals(2, allFiles.size());

    // Verify paths contain the directory structure
    Set<String> paths = new HashSet<>();
    for (SmbFileItem f : allFiles) paths.add(f.getPath());

    boolean hasTopLevel = false;
    boolean hasSubLevel = false;
    for (String path : paths) {
      if (path.contains("top.txt") && !path.contains("sub")) hasTopLevel = true;
      if (path.contains("sub") && path.contains("bottom.txt")) hasSubLevel = true;
    }
    assertTrue("Should have top-level file path", hasTopLevel);
    assertTrue("Should have sub-level file path", hasSubLevel);
  }

  // --- Helper methods ---

  /**
   * Recursively collects all files from a remote directory (mirrors the
   * collectDirectoryFiles logic in TransferWorker).
   */
  private List<SmbFileItem> flattenDirectory(String dirPath) {
    List<SmbFileItem> result = new ArrayList<>();
    List<SmbFileItem> children = testHelper.listFiles(dirPath);
    for (SmbFileItem child : children) {
      if (child == null || child.getName() == null) continue;
      if (child.isDirectory()) {
        result.addAll(flattenDirectory(child.getPath()));
      } else if (child.isFile()) {
        result.add(child);
      }
    }
    return result;
  }

  private void uploadSmallFile(String remotePath, String content) {
    InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    testHelper.uploadFile(remotePath, is);
  }

  private String readStream(InputStream is) throws Exception {
    byte[] buffer = new byte[8192];
    StringBuilder sb = new StringBuilder();
    int read;
    while ((read = is.read(buffer)) != -1) {
      sb.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
    }
    is.close();
    return sb.toString();
  }
}
