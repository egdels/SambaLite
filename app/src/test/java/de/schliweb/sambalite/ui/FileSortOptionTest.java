package de.schliweb.sambalite.ui;

import static org.junit.Assert.*;

import org.junit.Test;

/** Unit tests for {@link FileSortOption}. */
public class FileSortOptionTest {

  @Test
  public void enum_hasThreeValues() {
    assertEquals(3, FileSortOption.values().length);
  }

  @Test
  public void valueOf_returnsCorrectValues() {
    assertEquals(FileSortOption.NAME, FileSortOption.valueOf("NAME"));
    assertEquals(FileSortOption.DATE, FileSortOption.valueOf("DATE"));
    assertEquals(FileSortOption.SIZE, FileSortOption.valueOf("SIZE"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void valueOf_invalidName_throwsException() {
    FileSortOption.valueOf("INVALID");
  }
}
