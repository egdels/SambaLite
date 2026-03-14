package de.schliweb.sambalite.data.model;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link SmbConnection}. */
public class SmbConnectionTest {

  private SmbConnection connection;

  @Before
  public void setUp() {
    connection = new SmbConnection();
  }

  @Test
  public void defaultConstructor_fieldsAreNull() {
    assertNull(connection.getId());
    assertNull(connection.getName());
    assertNull(connection.getServer());
    assertNull(connection.getShare());
    assertNull(connection.getUsername());
    assertNull(connection.getPassword());
    assertNull(connection.getDomain());
  }

  @Test
  public void defaultConstructor_securitySettingsAreFalse() {
    assertFalse(connection.isEncryptData());
    assertFalse(connection.isSigningRequired());
  }

  @Test
  public void settersAndGetters_workCorrectly() {
    connection.setId("id1");
    connection.setName("My Server");
    connection.setServer("192.168.1.1");
    connection.setShare("share1");
    connection.setUsername("user");
    connection.setPassword("pass");
    connection.setDomain("WORKGROUP");
    connection.setEncryptData(true);
    connection.setSigningRequired(true);

    assertEquals("id1", connection.getId());
    assertEquals("My Server", connection.getName());
    assertEquals("192.168.1.1", connection.getServer());
    assertEquals("share1", connection.getShare());
    assertEquals("user", connection.getUsername());
    assertEquals("pass", connection.getPassword());
    assertEquals("WORKGROUP", connection.getDomain());
    assertTrue(connection.isEncryptData());
    assertTrue(connection.isSigningRequired());
  }

  @Test
  public void toString_containsFieldValues() {
    connection.setId("id1");
    connection.setName("TestServer");
    connection.setServer("10.0.0.1");
    connection.setShare("docs");
    connection.setUsername("admin");
    connection.setPassword("secret");
    connection.setDomain("DOM");

    String result = connection.toString();
    assertTrue(result.contains("id1"));
    assertTrue(result.contains("TestServer"));
    assertTrue(result.contains("10.0.0.1"));
    assertTrue(result.contains("docs"));
    assertTrue(result.contains("admin"));
    assertTrue(result.contains("DOM"));
  }

  @Test
  public void toString_masksPassword() {
    connection.setPassword("secret123");
    String result = connection.toString();
    assertFalse(result.contains("secret123"));
    assertTrue(result.contains("********"));
  }

  @Test
  public void toString_containsSecuritySettings() {
    connection.setEncryptData(true);
    connection.setSigningRequired(true);
    String result = connection.toString();
    assertTrue(result.contains("encryptData=true"));
    assertTrue(result.contains("signingRequired=true"));
  }

  @Test
  public void serializable_implementsInterface() {
    assertTrue(connection instanceof java.io.Serializable);
  }
}
