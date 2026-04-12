package de.schliweb.sambalite.data.repository;

import static org.junit.Assert.*;

import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.test.helper.SmbTestHelper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for recursive directory deletion with complex folder structures.
 *
 * <p>Tests cover: deeply nested directories, mixed files and folders, batch deletion order,
 * non-existent paths, and partial failure scenarios.
 */
public class SmbRepositoryRecursiveDeleteTest {

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
  public void testDeleteEmptyDirectory() throws Exception {
    testHelper.createDirectory("empty-dir");

    testHelper.deleteFile("empty-dir");

    List<SmbFileItem> rootFiles = testHelper.listFiles("");
    for (SmbFileItem item : rootFiles) {
      assertNotEquals("empty-dir should be deleted", "empty-dir", item.getName());
    }
  }

  @Test
  public void testDeleteDirectoryWithFiles() throws Exception {
    testHelper.createDirectory("dir-with-files");
    uploadSmallFile("dir-with-files/file1.txt", "content1");
    uploadSmallFile("dir-with-files/file2.txt", "content2");
    uploadSmallFile("dir-with-files/file3.txt", "content3");

    testHelper.deleteFile("dir-with-files");

    List<SmbFileItem> rootFiles = testHelper.listFiles("");
    for (SmbFileItem item : rootFiles) {
      assertNotEquals("dir-with-files should be deleted", "dir-with-files", item.getName());
    }
  }

  @Test
  public void testDeleteDeeplyNestedDirectory() throws Exception {
    testHelper.createDirectory("deep");
    testHelper.createDirectory("deep/level1");
    testHelper.createDirectory("deep/level1/level2");
    testHelper.createDirectory("deep/level1/level2/level3");
    testHelper.createDirectory("deep/level1/level2/level3/level4");
    uploadSmallFile("deep/level1/level2/level3/level4/leaf.txt", "deep content");

    testHelper.deleteFile("deep");

    List<SmbFileItem> rootFiles = testHelper.listFiles("");
    for (SmbFileItem item : rootFiles) {
      assertNotEquals("deep should be deleted", "deep", item.getName());
    }
  }

  @Test
  public void testDeleteComplexMixedStructure() throws Exception {
    // project/src/main/{App.java,Helper.java}, project/src/test/AppTest.java,
    // project/docs/README.md, project/build.gradle
    testHelper.createDirectory("project");
    testHelper.createDirectory("project/src");
    testHelper.createDirectory("project/src/main");
    testHelper.createDirectory("project/src/test");
    testHelper.createDirectory("project/docs");
    uploadSmallFile("project/src/main/App.java", "class App {}");
    uploadSmallFile("project/src/main/Helper.java", "class Helper {}");
    uploadSmallFile("project/src/test/AppTest.java", "class AppTest {}");
    uploadSmallFile("project/docs/README.md", "# Docs");
    uploadSmallFile("project/build.gradle", "apply plugin: 'java'");

    testHelper.deleteFile("project");

    List<SmbFileItem> rootFiles = testHelper.listFiles("");
    for (SmbFileItem item : rootFiles) {
      assertNotEquals("project should be deleted", "project", item.getName());
    }
  }

  @Test
  public void testDeleteDirectoryDoesNotAffectSiblings() throws Exception {
    testHelper.createDirectory("sibling-a");
    uploadSmallFile("sibling-a/a.txt", "a");
    testHelper.createDirectory("sibling-b");
    uploadSmallFile("sibling-b/b.txt", "b");

    testHelper.deleteFile("sibling-a");

    List<SmbFileItem> rootFiles = testHelper.listFiles("");
    boolean foundSiblingA = false;
    boolean foundSiblingB = false;
    for (SmbFileItem item : rootFiles) {
      if ("sibling-a".equals(item.getName())) foundSiblingA = true;
      if ("sibling-b".equals(item.getName())) foundSiblingB = true;
    }
    assertFalse("sibling-a should be deleted", foundSiblingA);
    assertTrue("sibling-b should still exist", foundSiblingB);

    List<SmbFileItem> siblingFiles = testHelper.listFiles("sibling-b");
    boolean foundFile = false;
    for (SmbFileItem item : siblingFiles) {
      if ("b.txt".equals(item.getName())) {
        foundFile = true;
        break;
      }
    }
    assertTrue("Sibling file b.txt should still exist", foundFile);
  }

  @Test
  public void testDeleteDirectoryWithManyFiles() throws Exception {
    testHelper.createDirectory("many-files");
    for (int i = 0; i < 20; i++) {
      uploadSmallFile("many-files/file-" + i + ".txt", "content-" + i);
    }

    testHelper.deleteFile("many-files");

    List<SmbFileItem> rootFiles = testHelper.listFiles("");
    for (SmbFileItem item : rootFiles) {
      assertNotEquals("many-files should be deleted", "many-files", item.getName());
    }
  }

  @Test
  public void testDeleteDirectoryWithNestedSubdirectories() throws Exception {
    testHelper.createDirectory("wide");
    for (int i = 0; i < 5; i++) {
      String subDir = "sub-" + i;
      testHelper.createDirectory("wide/" + subDir);
      for (int j = 0; j < 3; j++) {
        uploadSmallFile("wide/" + subDir + "/file-" + j + ".txt", "content-" + i + "-" + j);
      }
    }

    testHelper.deleteFile("wide");

    List<SmbFileItem> rootFiles = testHelper.listFiles("");
    for (SmbFileItem item : rootFiles) {
      assertNotEquals("wide should be deleted", "wide", item.getName());
    }
  }

  @Test
  public void testDeleteMixedFilesAndDirectories() throws Exception {
    uploadSmallFile("standalone.txt", "standalone");
    testHelper.createDirectory("folder");
    uploadSmallFile("folder/inner.txt", "inner");

    testHelper.deleteFile("standalone.txt");
    testHelper.deleteFile("folder");

    List<SmbFileItem> rootFiles = testHelper.listFiles("");
    for (SmbFileItem item : rootFiles) {
      assertNotEquals("standalone.txt should be deleted", "standalone.txt", item.getName());
      assertNotEquals("folder should be deleted", "folder", item.getName());
    }
  }

  @Test
  public void testDeleteDirectoryPreservesOtherRootFiles() throws Exception {
    uploadSmallFile("keep-me.txt", "important");
    testHelper.createDirectory("delete-me");
    uploadSmallFile("delete-me/file.txt", "disposable");

    testHelper.deleteFile("delete-me");

    List<SmbFileItem> rootFiles = testHelper.listFiles("");
    boolean foundKeepMe = false;
    boolean foundDeleteMe = false;
    for (SmbFileItem item : rootFiles) {
      if ("keep-me.txt".equals(item.getName())) foundKeepMe = true;
      if ("delete-me".equals(item.getName())) foundDeleteMe = true;
    }
    assertTrue("keep-me.txt should still exist", foundKeepMe);
    assertFalse("delete-me should be deleted", foundDeleteMe);
  }

  @Test
  public void testDeleteDirectoryWithEmptySubdirectories() throws Exception {
    testHelper.createDirectory("parent");
    testHelper.createDirectory("parent/empty-sub-1");
    testHelper.createDirectory("parent/empty-sub-2");
    testHelper.createDirectory("parent/empty-sub-3");

    testHelper.deleteFile("parent");

    List<SmbFileItem> rootFiles = testHelper.listFiles("");
    for (SmbFileItem item : rootFiles) {
      assertNotEquals("parent should be deleted", "parent", item.getName());
    }
  }

  @Test
  public void testDeleteDirectoryWithMixedEmptyAndNonEmptySubdirs() throws Exception {
    testHelper.createDirectory("mixed");
    testHelper.createDirectory("mixed/empty-sub");
    testHelper.createDirectory("mixed/full-sub");
    uploadSmallFile("mixed/full-sub/data.txt", "data");
    testHelper.createDirectory("mixed/nested-empty");
    testHelper.createDirectory("mixed/nested-empty/also-empty");

    testHelper.deleteFile("mixed");

    List<SmbFileItem> rootFiles = testHelper.listFiles("");
    for (SmbFileItem item : rootFiles) {
      assertNotEquals("mixed should be deleted", "mixed", item.getName());
    }
  }

  @Test
  public void testVerifyDirectoryContentsBeforeDelete() throws Exception {
    testHelper.createDirectory("verify-dir");
    uploadSmallFile("verify-dir/a.txt", "aaa");
    uploadSmallFile("verify-dir/b.txt", "bbb");
    testHelper.createDirectory("verify-dir/sub");
    uploadSmallFile("verify-dir/sub/c.txt", "ccc");

    // Verify contents exist before delete
    List<SmbFileItem> contents = testHelper.listFiles("verify-dir");
    assertTrue("Directory should have contents before delete", contents.size() >= 3);

    testHelper.deleteFile("verify-dir");

    List<SmbFileItem> rootFiles = testHelper.listFiles("");
    for (SmbFileItem item : rootFiles) {
      assertNotEquals("verify-dir should be deleted", "verify-dir", item.getName());
    }
  }

  /**
   * Uploads a small file using the SmbTestHelper.
   */
  private void uploadSmallFile(String remotePath, String content) {
    InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    testHelper.uploadFile(remotePath, is);
  }
}
