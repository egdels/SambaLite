package de.schliweb.sambalite.ui.adapters;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Unit tests for {@link SharesDiffCallback}. */
public class SharesDiffCallbackTest {

  @Test
  public void getOldListSize_returnsCorrectSize() {
    SharesDiffCallback callback =
        new SharesDiffCallback(Arrays.asList("share1", "share2"), Collections.emptyList());
    assertEquals(2, callback.getOldListSize());
  }

  @Test
  public void getNewListSize_returnsCorrectSize() {
    SharesDiffCallback callback =
        new SharesDiffCallback(Collections.emptyList(), Arrays.asList("share1"));
    assertEquals(1, callback.getNewListSize());
  }

  @Test
  public void areItemsTheSame_sameString_returnsTrue() {
    List<String> list = Arrays.asList("Documents");
    SharesDiffCallback callback = new SharesDiffCallback(list, list);
    assertTrue(callback.areItemsTheSame(0, 0));
  }

  @Test
  public void areItemsTheSame_differentString_returnsFalse() {
    SharesDiffCallback callback =
        new SharesDiffCallback(Arrays.asList("Documents"), Arrays.asList("Photos"));
    assertFalse(callback.areItemsTheSame(0, 0));
  }

  @Test
  public void areContentsTheSame_sameString_returnsTrue() {
    SharesDiffCallback callback =
        new SharesDiffCallback(Arrays.asList("share1"), Arrays.asList("share1"));
    assertTrue(callback.areContentsTheSame(0, 0));
  }

  @Test
  public void areContentsTheSame_differentString_returnsFalse() {
    SharesDiffCallback callback =
        new SharesDiffCallback(Arrays.asList("share1"), Arrays.asList("share2"));
    assertFalse(callback.areContentsTheSame(0, 0));
  }

  @Test
  public void emptyLists_bothSizesZero() {
    SharesDiffCallback callback = new SharesDiffCallback(new ArrayList<>(), new ArrayList<>());
    assertEquals(0, callback.getOldListSize());
    assertEquals(0, callback.getNewListSize());
  }
}
