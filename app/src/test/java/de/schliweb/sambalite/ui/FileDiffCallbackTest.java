package de.schliweb.sambalite.ui;

import static org.junit.Assert.*;

import de.schliweb.sambalite.data.model.SmbFileItem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import org.junit.Test;

/** Unit tests for {@link FileDiffCallback}. */
public class FileDiffCallbackTest {

  private SmbFileItem createFile(String name, String path, long size) {
    return new SmbFileItem(name, path, SmbFileItem.Type.FILE, size, new Date(1000L));
  }

  private SmbFileItem createDir(String name, String path) {
    return new SmbFileItem(name, path, SmbFileItem.Type.DIRECTORY, 0, new Date(1000L));
  }

  @Test
  public void getOldListSize_returnsCorrectSize() {
    List<SmbFileItem> oldList = Arrays.asList(createFile("a.txt", "/a.txt", 100));
    List<SmbFileItem> newList = Collections.emptyList();
    FileDiffCallback callback = new FileDiffCallback(oldList, newList);
    assertEquals(1, callback.getOldListSize());
  }

  @Test
  public void getNewListSize_returnsCorrectSize() {
    List<SmbFileItem> oldList = Collections.emptyList();
    List<SmbFileItem> newList = Arrays.asList(createFile("a.txt", "/a.txt", 100));
    FileDiffCallback callback = new FileDiffCallback(oldList, newList);
    assertEquals(1, callback.getNewListSize());
  }

  @Test
  public void areItemsTheSame_samePath_returnsTrue() {
    SmbFileItem old = createFile("a.txt", "/docs/a.txt", 100);
    SmbFileItem updated = createFile("a.txt", "/docs/a.txt", 200);
    FileDiffCallback callback =
        new FileDiffCallback(Collections.singletonList(old), Collections.singletonList(updated));
    assertTrue(callback.areItemsTheSame(0, 0));
  }

  @Test
  public void areItemsTheSame_differentPath_returnsFalse() {
    SmbFileItem old = createFile("a.txt", "/docs/a.txt", 100);
    SmbFileItem other = createFile("b.txt", "/docs/b.txt", 100);
    FileDiffCallback callback =
        new FileDiffCallback(Collections.singletonList(old), Collections.singletonList(other));
    assertFalse(callback.areItemsTheSame(0, 0));
  }

  @Test
  public void areContentsTheSame_identicalItems_returnsTrue() {
    SmbFileItem old = createFile("a.txt", "/a.txt", 100);
    SmbFileItem same = createFile("a.txt", "/a.txt", 100);
    FileDiffCallback callback =
        new FileDiffCallback(Collections.singletonList(old), Collections.singletonList(same));
    assertTrue(callback.areContentsTheSame(0, 0));
  }

  @Test
  public void areContentsTheSame_differentSize_returnsFalse() {
    SmbFileItem old = createFile("a.txt", "/a.txt", 100);
    SmbFileItem changed = createFile("a.txt", "/a.txt", 200);
    FileDiffCallback callback =
        new FileDiffCallback(Collections.singletonList(old), Collections.singletonList(changed));
    assertFalse(callback.areContentsTheSame(0, 0));
  }

  @Test
  public void areContentsTheSame_differentType_returnsFalse() {
    SmbFileItem file = createFile("docs", "/docs", 100);
    SmbFileItem dir = createDir("docs", "/docs");
    FileDiffCallback callback =
        new FileDiffCallback(Collections.singletonList(file), Collections.singletonList(dir));
    assertFalse(callback.areContentsTheSame(0, 0));
  }

  @Test
  public void areContentsTheSame_differentName_returnsFalse() {
    SmbFileItem old =
        new SmbFileItem("a.txt", "/a.txt", SmbFileItem.Type.FILE, 100, new Date(1000L));
    SmbFileItem renamed =
        new SmbFileItem("b.txt", "/a.txt", SmbFileItem.Type.FILE, 100, new Date(1000L));
    FileDiffCallback callback =
        new FileDiffCallback(Collections.singletonList(old), Collections.singletonList(renamed));
    assertFalse(callback.areContentsTheSame(0, 0));
  }

  @Test
  public void areContentsTheSame_differentDate_returnsFalse() {
    SmbFileItem old =
        new SmbFileItem("a.txt", "/a.txt", SmbFileItem.Type.FILE, 100, new Date(1000L));
    SmbFileItem newer =
        new SmbFileItem("a.txt", "/a.txt", SmbFileItem.Type.FILE, 100, new Date(2000L));
    FileDiffCallback callback =
        new FileDiffCallback(Collections.singletonList(old), Collections.singletonList(newer));
    assertFalse(callback.areContentsTheSame(0, 0));
  }

  @Test
  public void emptyLists_bothSizesZero() {
    FileDiffCallback callback = new FileDiffCallback(new ArrayList<>(), new ArrayList<>());
    assertEquals(0, callback.getOldListSize());
    assertEquals(0, callback.getNewListSize());
  }
}
