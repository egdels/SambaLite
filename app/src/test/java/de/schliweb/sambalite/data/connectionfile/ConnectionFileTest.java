/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.data.connectionfile;

import static org.junit.Assert.*;

import de.schliweb.sambalite.data.connectionfile.ConnectionFile.ConnectionFileException;
import de.schliweb.sambalite.data.connectionfile.ConnectionFile.EntryError;
import de.schliweb.sambalite.data.connectionfile.ConnectionFile.FileError;
import de.schliweb.sambalite.data.connectionfile.ConnectionFile.ParseResult;
import de.schliweb.sambalite.data.model.SmbConnection;
import java.util.Arrays;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Connection files (issue #48): templates without credentials, hand-written files with passwords,
 * invalid entries, and the round trip of an export.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ConnectionFileTest {

  // ===== Reading =====

  @Test
  public void templateWithoutCredentialsNeedsCredentials() throws Exception {
    ParseResult result =
        ConnectionFile.parse(
            "{\"format\":\"sambalite-connections\",\"version\":1,\"connections\":["
                + "{\"name\":\"NAS Daten\",\"server\":\"nas.local\",\"port\":4450,"
                + "\"share\":\"Daten\",\"domain\":\"WORKGROUP\",\"initialPath\":\"/Projekte/2026/\","
                + "\"encryptData\":true,\"username\":\"\",\"password\":\"\"}]}");

    assertEquals(1, result.entries.size());
    assertTrue(result.skipped.isEmpty());
    assertFalse(result.containsPasswords());
    SmbConnection c = result.entries.get(0).connection;
    assertEquals("NAS Daten", c.getName());
    assertEquals("nas.local", c.getServer());
    assertEquals(4450, c.getPort());
    assertEquals("Daten", c.getShare());
    assertEquals("WORKGROUP", c.getDomain());
    assertEquals("Projekte/2026", c.getInitialPath());
    assertTrue(c.isEncryptData());
    assertFalse(c.isSigningRequired());
    assertEquals("", c.getUsername());
    assertEquals("", c.getPassword());
    assertTrue(c.isCredentialsRequired());
    assertNull(c.getId()); // saving assigns a new ID
  }

  @Test
  public void onlyServerIsRequired() throws Exception {
    ParseResult result = ConnectionFile.parse("{\"connections\":[{\"server\":\"192.168.2.5\"}]}");

    SmbConnection c = result.entries.get(0).connection;
    assertEquals("192.168.2.5", c.getName());
    assertEquals(SmbConnection.DEFAULT_PORT, c.getPort());
    assertEquals("", c.getShare());
    assertEquals("", c.getDomain());
    assertTrue(c.isCredentialsRequired());
  }

  @Test
  public void usernameWithoutPasswordNeedsCredentials() throws Exception {
    SmbConnection c =
        ConnectionFile.parse("{\"connections\":[{\"server\":\"nas\",\"username\":\"anna\"}]}")
            .entries
            .get(0)
            .connection;

    assertEquals("anna", c.getUsername());
    assertTrue(c.isCredentialsRequired());
  }

  @Test
  public void passwordsAreImportedAndReported() throws Exception {
    ParseResult result =
        ConnectionFile.parse(
            "{\"connections\":[{\"server\":\"nas\",\"username\":\"scanner\","
                + "\"password\":\" geheim \"},{\"server\":\"other\"}]}");

    assertTrue(result.containsPasswords());
    assertTrue(result.entries.get(0).passwordIncluded);
    assertFalse(result.entries.get(1).passwordIncluded);
    SmbConnection c = result.entries.get(0).connection;
    assertEquals(" geheim ", c.getPassword()); // spaces may belong to a password
    assertFalse(c.isCredentialsRequired());
  }

  @Test
  public void guestNeedsNoCredentialsAndDropsAnyGiven() throws Exception {
    ParseResult result =
        ConnectionFile.parse(
            "{\"connections\":[{\"server\":\"nas\",\"guest\":true,"
                + "\"username\":\"x\",\"password\":\"y\"}]}");

    SmbConnection c = result.entries.get(0).connection;
    assertEquals("", c.getUsername());
    assertEquals("", c.getPassword());
    assertFalse(c.isCredentialsRequired());
    assertFalse(result.containsPasswords());
  }

  @Test
  public void acceptsWindowsServerNotationAndIpv6() throws Exception {
    ParseResult result =
        ConnectionFile.parse(
            "{\"connections\":[{\"server\":\"\\\\\\\\nas\"},{\"server\":\"fe80::1\"}]}");

    assertEquals("nas", result.entries.get(0).connection.getServer());
    assertEquals("fe80::1", result.entries.get(1).connection.getServer());
  }

  @Test
  public void nullValuesCountAsMissing() throws Exception {
    SmbConnection c =
        ConnectionFile.parse(
                "{\"connections\":[{\"server\":\"nas\",\"username\":null,\"password\":null,"
                    + "\"share\":null}]}")
            .entries
            .get(0)
            .connection;

    assertEquals("", c.getUsername());
    assertEquals("", c.getPassword());
    assertEquals("", c.getShare());
  }

  @Test
  public void invalidEntriesAreSkippedWithReason() throws Exception {
    ParseResult result =
        ConnectionFile.parse(
            "{\"connections\":["
                + "{\"name\":\"ohne Server\"},"
                + "{\"name\":\"Pfad\",\"server\":\"nas/share\"},"
                + "{\"server\":\"nas\",\"port\":70000},"
                + "{\"server\":\"nas\",\"port\":\"abc\"},"
                + "{\"server\":\"nas\",\"share\":\"a/b\"},"
                + "\"kein Objekt\","
                + "{\"server\":\"ok\"}]}");

    assertEquals(1, result.entries.size());
    assertEquals("ok", result.entries.get(0).connection.getServer());
    assertEquals(6, result.skipped.size());
    assertEquals(EntryError.MISSING_SERVER, result.skipped.get(0).error);
    assertEquals("ohne Server", result.skipped.get(0).name);
    assertEquals(1, result.skipped.get(0).position);
    assertEquals(EntryError.INVALID_SERVER, result.skipped.get(1).error);
    assertEquals(EntryError.INVALID_PORT, result.skipped.get(2).error);
    assertEquals(EntryError.INVALID_PORT, result.skipped.get(3).error);
    assertEquals(EntryError.INVALID_SHARE, result.skipped.get(4).error);
    assertEquals(EntryError.MISSING_SERVER, result.skipped.get(5).error);
    assertNull(result.skipped.get(5).name);
  }

  @Test
  public void portAsStringIsAccepted() throws Exception {
    assertEquals(
        139,
        ConnectionFile.parse("{\"connections\":[{\"server\":\"nas\",\"port\":\"139\"}]}")
            .entries
            .get(0)
            .connection
            .getPort());
  }

  @Test
  public void byteOrderMarkIsIgnored() throws Exception {
    assertEquals(
        1, ConnectionFile.parse("\uFEFF{\"connections\":[{\"server\":\"nas\"}]}").entries.size());
  }

  // ===== Whole-file errors =====

  @Test
  public void rejectsNonJson() {
    assertFileError(FileError.NOT_A_CONNECTION_FILE, "server=nas");
    assertFileError(FileError.NOT_A_CONNECTION_FILE, "");
    assertFileError(FileError.NOT_A_CONNECTION_FILE, "[{\"server\":\"nas\"}]");
    assertFileError(FileError.NOT_A_CONNECTION_FILE, "{\"server\":\"nas\"}");
  }

  @Test
  public void rejectsOtherFormat() {
    assertFileError(
        FileError.NOT_A_CONNECTION_FILE, "{\"format\":\"something-else\",\"connections\":[]}");
  }

  @Test
  public void rejectsNewerVersion() {
    assertFileError(
        FileError.NEWER_VERSION,
        "{\"format\":\"sambalite-connections\",\"version\":2,\"connections\":[]}");
  }

  @Test
  public void rejectsTooManyConnections() throws Exception {
    JSONArray array = new JSONArray();
    for (int i = 0; i <= ConnectionFile.MAX_CONNECTIONS; i++) {
      array.put(new JSONObject().put("server", "nas" + i));
    }
    assertFileError(
        FileError.TOO_LARGE, new JSONObject().put("connections", array).toString());
  }

  @Test
  public void rejectsTooLargeText() {
    char[] padding = new char[ConnectionFile.MAX_BYTES + 1];
    Arrays.fill(padding, ' ');
    assertFileError(FileError.TOO_LARGE, "{\"connections\":[]}" + new String(padding));
  }

  // ===== Writing =====

  @Test
  public void exportNeverContainsPasswords() throws Exception {
    SmbConnection c = connection("NAS", "nas", "Daten", "anna", "geheim");

    String withUsers = ConnectionFile.write(Collections.singletonList(c), true);
    String withoutUsers = ConnectionFile.write(Collections.singletonList(c), false);

    assertFalse(withUsers.contains("geheim"));
    assertFalse(withUsers.contains("password"));
    assertTrue(withUsers.contains("\"anna\""));
    assertFalse(withoutUsers.contains("anna"));
    JSONObject root = new JSONObject(withoutUsers);
    assertEquals(ConnectionFile.FORMAT, root.getString("format"));
    assertEquals(ConnectionFile.VERSION, root.getInt("version"));
  }

  @Test
  public void exportRoundTripKeepsSettingsAndAsksForCredentials() throws Exception {
    SmbConnection c = connection("Büro", "fileserver", "Projekt Ärger 2026", "anna", "geheim");
    c.setPort(4450);
    c.setDomain("FIRMA");
    c.setInitialPath("Angebote/2026");
    c.setSigningRequired(true);
    c.setAsyncTransport(true);

    SmbConnection back =
        ConnectionFile.parse(ConnectionFile.write(Collections.singletonList(c), true))
            .entries
            .get(0)
            .connection;

    assertEquals("Büro", back.getName());
    assertEquals("fileserver", back.getServer());
    assertEquals(4450, back.getPort());
    assertEquals("Projekt Ärger 2026", back.getShare());
    assertEquals("FIRMA", back.getDomain());
    assertEquals("Angebote/2026", back.getInitialPath());
    assertTrue(back.isSigningRequired());
    assertTrue(back.isAsyncTransport());
    assertFalse(back.isEncryptData());
    assertEquals("anna", back.getUsername());
    assertEquals("", back.getPassword());
    assertTrue(back.isCredentialsRequired());
    assertNull(back.getId());
  }

  @Test
  public void guestConnectionExportsAsGuest() throws Exception {
    SmbConnection guest = connection("Gast", "nas", "public", "", "");
    SmbConnection pending = connection("Vorlage", "nas", "Daten", "", "");
    pending.setCredentialsRequired(true);

    ParseResult result =
        ConnectionFile.parse(ConnectionFile.write(Arrays.asList(guest, pending), false));

    assertFalse(result.entries.get(0).connection.isCredentialsRequired());
    assertTrue(result.entries.get(1).connection.isCredentialsRequired());
  }

  // ===== Helpers =====

  private static SmbConnection connection(
      String name, String server, String share, String username, String password) {
    SmbConnection c = new SmbConnection();
    c.setId("id-" + name);
    c.setName(name);
    c.setServer(server);
    c.setShare(share);
    c.setUsername(username);
    c.setPassword(password);
    return c;
  }

  private static void assertFileError(FileError expected, String json) {
    try {
      ConnectionFile.parse(json);
      fail("Expected " + expected);
    } catch (ConnectionFileException e) {
      assertEquals(expected, e.getError());
    }
  }
}
