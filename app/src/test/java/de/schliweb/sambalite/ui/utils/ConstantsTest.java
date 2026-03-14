package de.schliweb.sambalite.ui.utils;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import org.junit.Test;

/** Unit tests for {@link Constants}. */
public class ConstantsTest {

  @Test
  public void constants_haveExpectedValues() {
    assertEquals("current_smb_folder", Constants.PREF_CURRENT_SMB_FOLDER);
    assertEquals("current_smb_connection_id", Constants.PREF_CURRENT_SMB_CONNECTION_ID);
    assertEquals("needs_refresh", Constants.NEEDS_REFRESH);
  }

  @Test
  public void constructor_isPrivate() throws Exception {
    Constructor<Constants> c = Constants.class.getDeclaredConstructor();
    assertFalse(
        "Constructor should be private", java.lang.reflect.Modifier.isPublic(c.getModifiers()));
  }
}
