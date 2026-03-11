package de.schliweb.sambalite.ui;

import static org.junit.Assert.*;

import de.schliweb.sambalite.sync.SyncConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Tests for the local folder collision detection logic in FileBrowserActivity. Verifies that sync
 * configurations cannot overlap (exact match, parent, or child).
 */
public class LocalFolderCollisionTest {

  private static final String BASE_URI =
      "content://com.android.externalstorage.documents/tree/primary%3A";

  private SyncConfig createConfig(String id, String localFolderUri, boolean enabled) {
    SyncConfig config = new SyncConfig();
    config.setId(id);
    config.setLocalFolderUri(localFolderUri);
    config.setEnabled(enabled);
    return config;
  }

  @Test
  public void noCollision_emptyList() {
    assertNull(
        FileBrowserActivity.checkLocalFolderCollision(
            BASE_URI + "Photos", Collections.emptyList(), null));
  }

  @Test
  public void noCollision_differentFolders() {
    List<SyncConfig> configs = new ArrayList<>();
    configs.add(createConfig("1", BASE_URI + "Music", true));

    assertNull(FileBrowserActivity.checkLocalFolderCollision(BASE_URI + "Photos", configs, null));
  }

  @Test
  public void exactMatch_detected() {
    List<SyncConfig> configs = new ArrayList<>();
    configs.add(createConfig("1", BASE_URI + "Photos", true));

    assertEquals(
        FileBrowserActivity.LocalFolderCollisionType.EXACT_MATCH,
        FileBrowserActivity.checkLocalFolderCollision(BASE_URI + "Photos", configs, null));
  }

  @Test
  public void parentSynced_detected() {
    List<SyncConfig> configs = new ArrayList<>();
    configs.add(createConfig("1", BASE_URI + "Photos", true));

    assertEquals(
        FileBrowserActivity.LocalFolderCollisionType.PARENT_SYNCED,
        FileBrowserActivity.checkLocalFolderCollision(BASE_URI + "Photos/Vacation", configs, null));
  }

  @Test
  public void childSynced_detected() {
    List<SyncConfig> configs = new ArrayList<>();
    configs.add(createConfig("1", BASE_URI + "Photos/Vacation", true));

    assertEquals(
        FileBrowserActivity.LocalFolderCollisionType.CHILD_SYNCED,
        FileBrowserActivity.checkLocalFolderCollision(BASE_URI + "Photos", configs, null));
  }

  @Test
  public void disabledConfig_ignored() {
    List<SyncConfig> configs = new ArrayList<>();
    configs.add(createConfig("1", BASE_URI + "Photos", false));

    assertNull(FileBrowserActivity.checkLocalFolderCollision(BASE_URI + "Photos", configs, null));
  }

  @Test
  public void editingConfig_excluded() {
    List<SyncConfig> configs = new ArrayList<>();
    configs.add(createConfig("edit-1", BASE_URI + "Photos", true));

    assertNull(
        FileBrowserActivity.checkLocalFolderCollision(BASE_URI + "Photos", configs, "edit-1"));
  }

  @Test
  public void editingConfig_otherCollisionsStillDetected() {
    List<SyncConfig> configs = new ArrayList<>();
    configs.add(createConfig("edit-1", BASE_URI + "Music", true));
    configs.add(createConfig("other-2", BASE_URI + "Photos", true));

    assertEquals(
        FileBrowserActivity.LocalFolderCollisionType.EXACT_MATCH,
        FileBrowserActivity.checkLocalFolderCollision(BASE_URI + "Photos", configs, "edit-1"));
  }

  @Test
  public void nullLocalFolderUri_ignored() {
    List<SyncConfig> configs = new ArrayList<>();
    configs.add(createConfig("1", null, true));

    assertNull(FileBrowserActivity.checkLocalFolderCollision(BASE_URI + "Photos", configs, null));
  }

  @Test
  public void trailingSlash_handledCorrectly() {
    List<SyncConfig> configs = new ArrayList<>();
    configs.add(createConfig("1", BASE_URI + "Photos/", true));

    // Exact match even with trailing slash difference
    assertEquals(
        FileBrowserActivity.LocalFolderCollisionType.PARENT_SYNCED,
        FileBrowserActivity.checkLocalFolderCollision(BASE_URI + "Photos/Vacation", configs, null));
  }

  @Test
  public void similarNames_noFalsePositive() {
    List<SyncConfig> configs = new ArrayList<>();
    configs.add(createConfig("1", BASE_URI + "Photo", true));

    // "Photos" should NOT be detected as child of "Photo" (different folder name)
    assertNull(FileBrowserActivity.checkLocalFolderCollision(BASE_URI + "Photos", configs, null));
  }

  @Test
  public void multipleConfigs_firstCollisionReturned() {
    List<SyncConfig> configs = new ArrayList<>();
    configs.add(createConfig("1", BASE_URI + "Photos", true));
    configs.add(createConfig("2", BASE_URI + "Photos/Vacation", true));

    assertEquals(
        FileBrowserActivity.LocalFolderCollisionType.EXACT_MATCH,
        FileBrowserActivity.checkLocalFolderCollision(BASE_URI + "Photos", configs, null));
  }

  @Test
  public void deeplyNestedChild_detected() {
    List<SyncConfig> configs = new ArrayList<>();
    configs.add(createConfig("1", BASE_URI + "Photos/2024/Summer/Beach", true));

    assertEquals(
        FileBrowserActivity.LocalFolderCollisionType.CHILD_SYNCED,
        FileBrowserActivity.checkLocalFolderCollision(BASE_URI + "Photos", configs, null));
  }

  @Test
  public void deeplyNestedParent_detected() {
    List<SyncConfig> configs = new ArrayList<>();
    configs.add(createConfig("1", BASE_URI + "Photos", true));

    assertEquals(
        FileBrowserActivity.LocalFolderCollisionType.PARENT_SYNCED,
        FileBrowserActivity.checkLocalFolderCollision(
            BASE_URI + "Photos/2024/Summer/Beach", configs, null));
  }

  // --- Tests for encoded URI separators (%2F) ---

  private static final String ENCODED_URI =
      "content://com.android.externalstorage.documents/tree/primary%3A";

  @Test
  public void encodedUri_parentSynced_detected() {
    List<SyncConfig> configs = new ArrayList<>();
    configs.add(createConfig("1", ENCODED_URI + "FolderA", true));

    assertEquals(
        FileBrowserActivity.LocalFolderCollisionType.PARENT_SYNCED,
        FileBrowserActivity.checkLocalFolderCollision(
            ENCODED_URI + "FolderA%2FFolderB", configs, null));
  }

  @Test
  public void encodedUri_childSynced_detected() {
    List<SyncConfig> configs = new ArrayList<>();
    configs.add(createConfig("1", ENCODED_URI + "FolderA%2FFolderB", true));

    assertEquals(
        FileBrowserActivity.LocalFolderCollisionType.CHILD_SYNCED,
        FileBrowserActivity.checkLocalFolderCollision(ENCODED_URI + "FolderA", configs, null));
  }

  @Test
  public void encodedUri_exactMatch_detected() {
    List<SyncConfig> configs = new ArrayList<>();
    configs.add(createConfig("1", ENCODED_URI + "FolderA%2FFolderB", true));

    assertEquals(
        FileBrowserActivity.LocalFolderCollisionType.EXACT_MATCH,
        FileBrowserActivity.checkLocalFolderCollision(
            ENCODED_URI + "FolderA%2FFolderB", configs, null));
  }

  @Test
  public void encodedUri_similarNames_noFalsePositive() {
    List<SyncConfig> configs = new ArrayList<>();
    configs.add(createConfig("1", ENCODED_URI + "Folder", true));

    // "FolderA" should NOT collide with "Folder"
    assertNull(
        FileBrowserActivity.checkLocalFolderCollision(ENCODED_URI + "FolderA", configs, null));
  }

  @Test
  public void encodedUri_deeplyNested_parentDetected() {
    List<SyncConfig> configs = new ArrayList<>();
    configs.add(createConfig("1", ENCODED_URI + "A", true));

    assertEquals(
        FileBrowserActivity.LocalFolderCollisionType.PARENT_SYNCED,
        FileBrowserActivity.checkLocalFolderCollision(ENCODED_URI + "A%2FB%2FC", configs, null));
  }
}
