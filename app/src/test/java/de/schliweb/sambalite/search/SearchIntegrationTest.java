/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.search;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.*;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import de.schliweb.sambalite.util.SambaContainer;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for search operations against a real Docker Samba Server.
 *
 * <p>Tests the core search logic used by SearchWorker: recursive directory listing,
 * wildcard matching, search type filtering (files only, directories only, all),
 * special characters in filenames, and deep nested searches.
 */
public class SearchIntegrationTest {

  private SambaContainer sambaContainer;
  private SMBClient smbClient;
  private Connection connection;
  private Session session;
  private DiskShare share;

  @Before
  public void setUp() throws Exception {
    sambaContainer =
        new SambaContainer()
            .withUsername("testuser")
            .withPassword("testpassword")
            .withDomain("WORKGROUP")
            .withShare("testshare", "/testshare");
    sambaContainer.start();

    Thread.sleep(2000);
    sambaContainer.execInContainer("chmod", "0777", "/testshare");

    smbClient = new SMBClient();
    connection = smbClient.connect(sambaContainer.getHost(), sambaContainer.getPort());
    AuthenticationContext auth =
        new AuthenticationContext("testuser", "testpassword".toCharArray(), "WORKGROUP");
    session = connection.authenticate(auth);
    share = (DiskShare) session.connectShare("testshare");
  }

  @After
  public void tearDown() throws Exception {
    if (share != null) share.close();
    if (session != null) session.close();
    if (connection != null) connection.close();
    if (smbClient != null) smbClient.close();
    if (sambaContainer != null) sambaContainer.stop();
  }

  // ===== BASIC SEARCH =====

  @Test
  public void search_byExactName_findsFile() throws Exception {
    writeRemoteFile("target.txt", "content");
    writeRemoteFile("other.txt", "other");

    List<SearchResult> results = searchRecursive("", "target.txt", 0, true);

    assertEquals(1, results.size());
    assertEquals("target.txt", results.get(0).name);
  }

  @Test
  public void search_bySubstring_findsMatchingFiles() throws Exception {
    writeRemoteFile("report_2024.pdf", "data");
    writeRemoteFile("report_2025.pdf", "data");
    writeRemoteFile("readme.txt", "text");

    List<SearchResult> results = searchRecursive("", "report", 0, true);

    assertEquals(2, results.size());
    assertTrue(results.stream().anyMatch(r -> r.name.equals("report_2024.pdf")));
    assertTrue(results.stream().anyMatch(r -> r.name.equals("report_2025.pdf")));
  }

  @Test
  public void search_caseInsensitive_findsRegardlessOfCase() throws Exception {
    writeRemoteFile("MyDocument.TXT", "content");

    List<SearchResult> results = searchRecursive("", "mydocument", 0, true);

    assertEquals(1, results.size());
    assertEquals("MyDocument.TXT", results.get(0).name);
  }

  @Test
  public void search_inEmptyShare_returnsNoResults() throws Exception {
    List<SearchResult> results = searchRecursive("", "anything", 0, true);

    assertEquals(0, results.size());
  }

  // ===== WILDCARD SEARCH =====

  @Test
  public void search_wildcardStar_matchesMultipleCharacters() throws Exception {
    writeRemoteFile("photo_001.jpg", "img");
    writeRemoteFile("photo_002.jpg", "img");
    writeRemoteFile("photo_003.png", "img");
    writeRemoteFile("document.pdf", "doc");

    List<SearchResult> results = searchRecursive("", "photo_*.jpg", 0, true);

    assertEquals(2, results.size());
    assertTrue(results.stream().allMatch(r -> r.name.endsWith(".jpg")));
  }

  @Test
  public void search_wildcardQuestion_matchesSingleCharacter() throws Exception {
    writeRemoteFile("file_a.txt", "a");
    writeRemoteFile("file_b.txt", "b");
    writeRemoteFile("file_ab.txt", "ab");

    List<SearchResult> results = searchRecursive("", "file_?.txt", 0, true);

    assertEquals(2, results.size());
    assertTrue(results.stream().anyMatch(r -> r.name.equals("file_a.txt")));
    assertTrue(results.stream().anyMatch(r -> r.name.equals("file_b.txt")));
  }

  @Test
  public void search_wildcardStarAtEnd_matchesAnyExtension() throws Exception {
    writeRemoteFile("readme.txt", "t");
    writeRemoteFile("readme.md", "m");
    writeRemoteFile("readme.pdf", "p");

    List<SearchResult> results = searchRecursive("", "readme.*", 0, true);

    assertEquals(3, results.size());
  }

  @Test
  public void search_wildcardStarAtStart_matchesAnyPrefix() throws Exception {
    writeRemoteFile("backup_data.zip", "z");
    writeRemoteFile("archive_data.zip", "z");
    writeRemoteFile("data.txt", "t");

    List<SearchResult> results = searchRecursive("", "*_data.zip", 0, true);

    assertEquals(2, results.size());
  }

  // ===== SEARCH TYPE FILTERING =====

  @Test
  public void search_filesOnly_excludesDirectories() throws Exception {
    writeRemoteFile("notes.txt", "text");
    ensureRemoteDirectoryExists("notes_folder");

    // searchType 1 = files only
    List<SearchResult> results = searchRecursive("", "notes", 1, true);

    assertEquals(1, results.size());
    assertEquals("FILE", results.get(0).type);
  }

  @Test
  public void search_directoriesOnly_excludesFiles() throws Exception {
    writeRemoteFile("docs.txt", "text");
    ensureRemoteDirectoryExists("docs");

    // searchType 2 = directories only
    List<SearchResult> results = searchRecursive("", "docs", 2, true);

    assertEquals(1, results.size());
    assertEquals("DIRECTORY", results.get(0).type);
  }

  @Test
  public void search_allTypes_includesBothFilesAndDirectories() throws Exception {
    writeRemoteFile("data.txt", "text");
    ensureRemoteDirectoryExists("data");

    // searchType 0 = all
    List<SearchResult> results = searchRecursive("", "data", 0, true);

    assertEquals(2, results.size());
    assertTrue(results.stream().anyMatch(r -> "FILE".equals(r.type)));
    assertTrue(results.stream().anyMatch(r -> "DIRECTORY".equals(r.type)));
  }

  // ===== RECURSIVE SEARCH =====

  @Test
  public void search_recursive_findsInNestedDirectories() throws Exception {
    ensureRemoteDirectoryExists("level1/level2/level3");
    writeRemoteFile("level1\\level2\\level3\\hidden.txt", "found");
    writeRemoteFile("top.txt", "top");

    List<SearchResult> results = searchRecursive("", "hidden", 0, true);

    assertEquals(1, results.size());
    assertEquals("hidden.txt", results.get(0).name);
    assertTrue(results.get(0).path.contains("level1"));
  }

  @Test
  public void search_nonRecursive_onlySearchesTopLevel() throws Exception {
    ensureRemoteDirectoryExists("subdir");
    writeRemoteFile("top_match.txt", "top");
    writeRemoteFile("subdir\\nested_match.txt", "nested");

    List<SearchResult> results = searchRecursive("", "match", 0, false);

    assertEquals(1, results.size());
    assertEquals("top_match.txt", results.get(0).name);
  }

  @Test
  public void search_inSubdirectory_onlySearchesThatPath() throws Exception {
    ensureRemoteDirectoryExists("folderA");
    ensureRemoteDirectoryExists("folderB");
    writeRemoteFile("folderA\\target.txt", "a");
    writeRemoteFile("folderB\\target.txt", "b");

    List<SearchResult> results = searchRecursive("folderA", "target", 0, true);

    assertEquals(1, results.size());
    assertTrue(results.get(0).path.startsWith("folderA"));
  }

  @Test
  public void search_deepNested_7levels_findsFile() throws Exception {
    ensureRemoteDirectoryExists("a/b/c/d/e/f/g");
    writeRemoteFile("a\\b\\c\\d\\e\\f\\g\\deepfile.txt", "deep");

    List<SearchResult> results = searchRecursive("", "deepfile", 0, true);

    assertEquals(1, results.size());
    assertEquals("deepfile.txt", results.get(0).name);
  }

  // ===== SPECIAL CHARACTERS =====

  @Test
  public void search_fileWithSpaces_findsCorrectly() throws Exception {
    writeRemoteFile("my document.txt", "content");

    List<SearchResult> results = searchRecursive("", "my document", 0, true);

    assertEquals(1, results.size());
  }

  @Test
  public void search_fileWithHyphensAndUnderscores_findsCorrectly() throws Exception {
    writeRemoteFile("my-file_v2.txt", "content");

    List<SearchResult> results = searchRecursive("", "my-file_v2", 0, true);

    assertEquals(1, results.size());
  }

  @Test
  public void search_fileWithNumbers_findsCorrectly() throws Exception {
    writeRemoteFile("2024-01-15_report.csv", "data");

    List<SearchResult> results = searchRecursive("", "2024-01-15", 0, true);

    assertEquals(1, results.size());
  }

  // ===== RESULT METADATA =====

  @Test
  public void search_resultContainsCorrectSize() throws Exception {
    String content = "Hello, this is test content for size check!";
    writeRemoteFile("sized.txt", content);

    List<SearchResult> results = searchRecursive("", "sized", 0, true);

    assertEquals(1, results.size());
    assertEquals(content.getBytes(UTF_8).length, results.get(0).size);
  }

  @Test
  public void search_resultContainsLastModified() throws Exception {
    long beforeCreate = System.currentTimeMillis();
    writeRemoteFile("timed.txt", "content");

    List<SearchResult> results = searchRecursive("", "timed", 0, true);

    assertEquals(1, results.size());
    assertTrue("lastModified should be recent", results.get(0).lastModified > beforeCreate - 5000);
  }

  @Test
  public void search_resultContainsCorrectPath() throws Exception {
    ensureRemoteDirectoryExists("parent/child");
    writeRemoteFile("parent\\child\\pathtest.txt", "content");

    List<SearchResult> results = searchRecursive("", "pathtest", 0, true);

    assertEquals(1, results.size());
    assertEquals("parent/child/pathtest.txt", results.get(0).path);
  }

  // ===== COMPLEX SEARCH SCENARIOS =====

  @Test
  public void search_multipleMatchesAcrossDirectories_findsAll() throws Exception {
    ensureRemoteDirectoryExists("dir1/sub1");
    ensureRemoteDirectoryExists("dir2/sub2");
    ensureRemoteDirectoryExists("dir3");
    writeRemoteFile("config.ini", "root");
    writeRemoteFile("dir1\\config.ini", "dir1");
    writeRemoteFile("dir1\\sub1\\config.ini", "sub1");
    writeRemoteFile("dir2\\sub2\\config.ini", "sub2");
    writeRemoteFile("dir3\\config.ini", "dir3");

    List<SearchResult> results = searchRecursive("", "config.ini", 0, true);

    assertEquals(5, results.size());
  }

  @Test
  public void search_wildcardComplexPattern_matchesCorrectly() throws Exception {
    writeRemoteFile("app-release-1.0.apk", "apk1");
    writeRemoteFile("app-debug-1.0.apk", "apk2");
    writeRemoteFile("app-release-2.0.apk", "apk3");
    writeRemoteFile("lib-release-1.0.aar", "aar");

    List<SearchResult> results = searchRecursive("", "app-*-*.apk", 0, true);

    assertEquals(3, results.size());
  }

  @Test
  public void search_manyFiles_findsAllMatches() throws Exception {
    int fileCount = 50;
    for (int i = 0; i < fileCount; i++) {
      writeRemoteFile("item_" + String.format("%03d", i) + ".dat", "data" + i);
    }

    List<SearchResult> results = searchRecursive("", "item_", 0, true);

    assertEquals(fileCount, results.size());
  }

  @Test
  public void search_parallelDirectories_findsInAllBranches() throws Exception {
    String[] branches = {"alpha", "beta", "gamma", "delta"};
    for (String branch : branches) {
      ensureRemoteDirectoryExists(branch);
      writeRemoteFile(branch + "\\readme.txt", "readme in " + branch);
    }

    List<SearchResult> results = searchRecursive("", "readme", 0, true);

    assertEquals(4, results.size());
  }

  // ===== Helper methods (mirror SearchWorker logic) =====

  /** Represents a search result matching SearchWorker's SearchResult structure. */
  static class SearchResult {
    String name;
    String path;
    String type;
    long size;
    long lastModified;
  }

  private List<SearchResult> searchRecursive(
      String basePath, String query, int searchType, boolean includeSubfolders) {
    List<SearchResult> results = new ArrayList<>();
    doSearchRecursive(basePath, query, searchType, includeSubfolders, results);
    return results;
  }

  private void doSearchRecursive(
      String path,
      String query,
      int searchType,
      boolean includeSubfolders,
      List<SearchResult> results) {
    try {
      for (FileIdBothDirectoryInformation info : share.list(path)) {
        String name = info.getFileName();
        if (".".equals(name) || "..".equals(name)) continue;

        String nextPath = path.isEmpty() ? name : path + "\\" + name;
        String uiFullPath = path.isEmpty() ? name : path.replace('\\', '/') + "/" + name;

        boolean isDirectory =
            (info.getFileAttributes()
                    & com.hierynomus.msfscc.FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue())
                != 0;

        if (matchesSearchCriteria(name, query, searchType, isDirectory)) {
          SearchResult result = new SearchResult();
          result.name = name;
          result.path = uiFullPath;
          result.type = isDirectory ? "DIRECTORY" : "FILE";
          result.size = info.getEndOfFile();
          result.lastModified = info.getLastWriteTime().toEpochMillis();
          results.add(result);
        }

        if (isDirectory && includeSubfolders) {
          doSearchRecursive(nextPath, query, searchType, includeSubfolders, results);
        }
      }
    } catch (Exception e) {
      fail("Search failed in directory '" + path + "': " + e.getMessage());
    }
  }

  private boolean matchesSearchCriteria(
      String name, String query, int searchType, boolean isDirectory) {
    return ((searchType == 0)
            || (searchType == 1 && !isDirectory)
            || (searchType == 2 && isDirectory))
        && matchesWildcard(name, query);
  }

  private boolean matchesWildcard(String name, String pattern) {
    String lowerName = name.toLowerCase(Locale.ROOT);
    String lowerPattern = pattern.toLowerCase(Locale.ROOT);

    if (!lowerPattern.contains("*") && !lowerPattern.contains("?")) {
      return lowerName.contains(lowerPattern);
    }

    return wildcardMatch(lowerName, lowerPattern, 0, 0);
  }

  private boolean wildcardMatch(String str, String pattern, int si, int pi) {
    while (si < str.length() && pi < pattern.length()) {
      char pc = pattern.charAt(pi);
      if (pc == '*') {
        pi++;
        if (pi == pattern.length()) return true;
        for (int i = si; i <= str.length(); i++) {
          if (wildcardMatch(str, pattern, i, pi)) return true;
        }
        return false;
      } else if (pc == '?' || pc == str.charAt(si)) {
        si++;
        pi++;
      } else {
        return false;
      }
    }
    while (pi < pattern.length() && pattern.charAt(pi) == '*') pi++;
    return si == str.length() && pi == pattern.length();
  }

  private void writeRemoteFile(String remotePath, String content) throws Exception {
    try (File remoteFile =
            share.openFile(
                remotePath,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null);
        OutputStream os = remoteFile.getOutputStream()) {
      os.write(content.getBytes(UTF_8));
    }
  }

  private void ensureRemoteDirectoryExists(String path) {
    String smbPath = path.replace('/', '\\');
    String[] parts = smbPath.split("\\\\");
    StringBuilder current = new StringBuilder();
    for (String part : parts) {
      if (part.isEmpty()) continue;
      if (current.length() > 0) current.append("\\");
      current.append(part);
      String dirPath = current.toString();
      if (!share.folderExists(dirPath)) {
        share.mkdir(dirPath);
      }
    }
  }
}
