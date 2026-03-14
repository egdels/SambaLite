package de.schliweb.sambalite.data.model;

import static org.junit.Assert.*;

import java.util.Date;
import org.junit.Test;

/** Unit tests for {@link SmbFileItem}. */
public class SmbFileItemTest {

  @Test
  public void constructor_setsAllFields() {
    Date now = new Date();
    SmbFileItem item =
        new SmbFileItem("file.txt", "/path/file.txt", SmbFileItem.Type.FILE, 1024, now);

    assertEquals("file.txt", item.getName());
    assertEquals("/path/file.txt", item.getPath());
    assertEquals(SmbFileItem.Type.FILE, item.getType());
    assertEquals(1024, item.getSize());
    assertEquals(now, item.getLastModified());
  }

  @Test
  public void isDirectory_returnsTrueForDirectory() {
    SmbFileItem item = new SmbFileItem("dir", "/dir", SmbFileItem.Type.DIRECTORY, 0, new Date());
    assertTrue(item.isDirectory());
    assertFalse(item.isFile());
  }

  @Test
  public void isFile_returnsTrueForFile() {
    SmbFileItem item = new SmbFileItem("f.txt", "/f.txt", SmbFileItem.Type.FILE, 100, new Date());
    assertTrue(item.isFile());
    assertFalse(item.isDirectory());
  }

  @Test
  public void setters_updateFields() {
    SmbFileItem item = new SmbFileItem("a", "/a", SmbFileItem.Type.FILE, 0, new Date());
    Date newDate = new Date(0);

    item.setName("b");
    item.setPath("/b");
    item.setType(SmbFileItem.Type.DIRECTORY);
    item.setSize(999);
    item.setLastModified(newDate);

    assertEquals("b", item.getName());
    assertEquals("/b", item.getPath());
    assertEquals(SmbFileItem.Type.DIRECTORY, item.getType());
    assertEquals(999, item.getSize());
    assertEquals(newDate, item.getLastModified());
  }

  @Test
  public void toString_containsFieldValues() {
    SmbFileItem item =
        new SmbFileItem("test.pdf", "/docs/test.pdf", SmbFileItem.Type.FILE, 2048, new Date());
    String result = item.toString();

    assertTrue(result.contains("test.pdf"));
    assertTrue(result.contains("/docs/test.pdf"));
    assertTrue(result.contains("FILE"));
    assertTrue(result.contains("2048"));
  }

  @Test
  public void serializable_implementsInterface() {
    SmbFileItem item = new SmbFileItem("f", "/f", SmbFileItem.Type.FILE, 0, new Date());
    assertTrue(item instanceof java.io.Serializable);
  }

  @Test
  public void typeEnum_hasTwoValues() {
    SmbFileItem.Type[] values = SmbFileItem.Type.values();
    assertEquals(2, values.length);
    assertEquals(SmbFileItem.Type.FILE, SmbFileItem.Type.valueOf("FILE"));
    assertEquals(SmbFileItem.Type.DIRECTORY, SmbFileItem.Type.valueOf("DIRECTORY"));
  }
}
