package de.schliweb.sambalite.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;

import androidx.documentfile.provider.DocumentFile;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.Test;

/**
 * Unit tests for the local file name lookup in {@link FolderSyncWorker}, which guards against
 * duplicate "B (1)" directories created by SAF when a remote name does not match the locally
 * stored name byte-for-byte (Unicode NFD vs. NFC, case differences).
 */
public class FolderSyncWorkerNameLookupTest {

  private static final String NFC_NAME = "B\u00e4der"; // "Bäder" precomposed (NFC)
  private static final String NFD_NAME = "Ba\u0308der"; // "Bäder" decomposed (NFD)

  private static Map<String, DocumentFile>[] buildMaps(Map<String, DocumentFile> entries) {
    Map<String, DocumentFile> byName = new HashMap<>();
    Map<String, DocumentFile> byLower = new HashMap<>();
    for (Map.Entry<String, DocumentFile> e : entries.entrySet()) {
      String key = FolderSyncWorker.normalizeName(e.getKey());
      byName.put(key, e.getValue());
      byLower.put(key.toLowerCase(Locale.ROOT), e.getValue());
    }
    @SuppressWarnings("unchecked")
    Map<String, DocumentFile>[] maps = new Map[] {byName, byLower};
    return maps;
  }

  @Test
  public void normalizeName_convertsNfdToNfc() {
    assertEquals(NFC_NAME, FolderSyncWorker.normalizeName(NFD_NAME));
    assertEquals(NFC_NAME, FolderSyncWorker.normalizeName(NFC_NAME));
  }

  @Test
  public void lookup_findsExactMatch() {
    DocumentFile file = mock(DocumentFile.class);
    Map<String, DocumentFile> entries = new HashMap<>();
    entries.put("B", file);
    Map<String, DocumentFile>[] maps = buildMaps(entries);

    assertSame(file, FolderSyncWorker.lookupLocal(maps[0], maps[1], "B"));
  }

  @Test
  public void lookup_matchesNfdRemoteNameAgainstNfcLocalName() {
    DocumentFile file = mock(DocumentFile.class);
    Map<String, DocumentFile> entries = new HashMap<>();
    entries.put(NFC_NAME, file);
    Map<String, DocumentFile>[] maps = buildMaps(entries);

    assertSame(file, FolderSyncWorker.lookupLocal(maps[0], maps[1], NFD_NAME));
  }

  @Test
  public void lookup_matchesNfcRemoteNameAgainstNfdLocalName() {
    DocumentFile file = mock(DocumentFile.class);
    Map<String, DocumentFile> entries = new HashMap<>();
    entries.put(NFD_NAME, file);
    Map<String, DocumentFile>[] maps = buildMaps(entries);

    assertSame(file, FolderSyncWorker.lookupLocal(maps[0], maps[1], NFC_NAME));
  }

  @Test
  public void lookup_fallsBackToCaseInsensitiveMatch() {
    DocumentFile file = mock(DocumentFile.class);
    Map<String, DocumentFile> entries = new HashMap<>();
    entries.put("b", file);
    Map<String, DocumentFile>[] maps = buildMaps(entries);

    assertSame(file, FolderSyncWorker.lookupLocal(maps[0], maps[1], "B"));
  }

  @Test
  public void lookup_prefersExactMatchOverCaseInsensitiveMatch() {
    DocumentFile exact = mock(DocumentFile.class);
    DocumentFile other = mock(DocumentFile.class);
    Map<String, DocumentFile> byName = new HashMap<>();
    Map<String, DocumentFile> byLower = new HashMap<>();
    byName.put("B", exact);
    byLower.put("b", other);

    assertSame(exact, FolderSyncWorker.lookupLocal(byName, byLower, "B"));
  }

  @Test
  public void lookup_returnsNullWhenNoMatchExists() {
    DocumentFile file = mock(DocumentFile.class);
    Map<String, DocumentFile> entries = new HashMap<>();
    entries.put("A", file);
    Map<String, DocumentFile>[] maps = buildMaps(entries);

    assertNull(FolderSyncWorker.lookupLocal(maps[0], maps[1], "B"));
  }
}
