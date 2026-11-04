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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.schliweb.sambalite.data.model.SmbConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * Reads and writes connection files (issue #48): JSON files that carry connection settings from one
 * device to others, optionally without credentials, so every user can enter their own.
 *
 * <p>The format is documented in {@code docs/connection_file.md}. Only {@code server} is required
 * per connection; everything else has a default. Files written by SambaLite never contain
 * passwords. Files written by hand may, and those passwords are imported.
 */
public final class ConnectionFile {

  /** Value of the top-level {@code format} field. */
  public static final String FORMAT = "sambalite-connections";

  /** The format version this app writes and the highest it reads. */
  public static final int VERSION = 1;

  /** Larger files are rejected before parsing; a file with hundreds of connections is ~100 KB. */
  public static final int MAX_BYTES = 1024 * 1024;

  /** More connections than this are rejected as not a real connection file. */
  public static final int MAX_CONNECTIONS = 500;

  /** The suggested file name for an export. */
  public static final String DEFAULT_FILE_NAME = "sambalite-connections.json";

  /** U+FEFF, which some editors put at the start of UTF-8 files. */
  private static final char BYTE_ORDER_MARK = (char) 0xFEFF;

  private static final String INVALID_SHARE_NAME_CHARS = "/\\:*?\"<>|";
  private static final String INVALID_SERVER_CHARS = "/\\:*?\"<>| \t\r\n";

  /** Why a whole file could not be read. */
  public enum FileError {
    /** Not JSON, or not an object with a {@code connections} array. */
    NOT_A_CONNECTION_FILE,
    /** Written by a newer SambaLite with a format version this app does not know. */
    NEWER_VERSION,
    /** More than {@link #MAX_CONNECTIONS} connections or more than {@link #MAX_BYTES}. */
    TOO_LARGE
  }

  /** Why a single connection of a file was skipped. */
  public enum EntryError {
    MISSING_SERVER,
    INVALID_SERVER,
    INVALID_PORT,
    INVALID_SHARE
  }

  /** Thrown when a file cannot be read at all. */
  public static final class ConnectionFileException extends Exception {
    private static final long serialVersionUID = 1L;
    private final FileError error;

    ConnectionFileException(FileError error, String message) {
      super(message);
      this.error = error;
    }

    @NonNull
    public FileError getError() {
      return error;
    }
  }

  /** A connection read from a file, not yet saved. */
  public static final class Entry {
    /** The connection without an ID; saving it as a new connection assigns one. */
    @NonNull public final SmbConnection connection;

    /** The file contained a password for this connection. */
    public final boolean passwordIncluded;

    Entry(@NonNull SmbConnection connection, boolean passwordIncluded) {
      this.connection = connection;
      this.passwordIncluded = passwordIncluded;
    }
  }

  /** A skipped connection: its position in the file (from 1), its name if any, and why. */
  public static final class SkippedEntry {
    public final int position;
    @Nullable public final String name;
    @NonNull public final EntryError error;

    SkippedEntry(int position, @Nullable String name, @NonNull EntryError error) {
      this.position = position;
      this.name = name;
      this.error = error;
    }
  }

  /** The outcome of reading a file: the usable connections and the skipped ones. */
  public static final class ParseResult {
    @NonNull public final List<Entry> entries;
    @NonNull public final List<SkippedEntry> skipped;

    ParseResult(@NonNull List<Entry> entries, @NonNull List<SkippedEntry> skipped) {
      this.entries = Collections.unmodifiableList(entries);
      this.skipped = Collections.unmodifiableList(skipped);
    }

    /** Whether any imported connection carries a password from the file. */
    public boolean containsPasswords() {
      for (Entry entry : entries) {
        if (entry.passwordIncluded) return true;
      }
      return false;
    }
  }

  private ConnectionFile() {}

  // ---------------------------------------------------------------------------------------------
  // Reading

  /**
   * Reads a connection file. Connections with invalid settings are skipped and reported, the others
   * are returned without IDs, so that saving them adds new connections.
   *
   * @throws ConnectionFileException if the text is not a connection file this app can read
   */
  @NonNull
  public static ParseResult parse(@NonNull String json) throws ConnectionFileException {
    if (json.length() > MAX_BYTES) {
      throw new ConnectionFileException(FileError.TOO_LARGE, "File too large");
    }
    JSONObject root;
    try {
      Object value = new JSONTokener(stripByteOrderMark(json)).nextValue();
      if (!(value instanceof JSONObject)) {
        throw new ConnectionFileException(FileError.NOT_A_CONNECTION_FILE, "Not a JSON object");
      }
      root = (JSONObject) value;
    } catch (JSONException e) {
      throw new ConnectionFileException(FileError.NOT_A_CONNECTION_FILE, e.getMessage());
    }

    String format = root.optString("format", FORMAT);
    if (!FORMAT.equals(format)) {
      throw new ConnectionFileException(
          FileError.NOT_A_CONNECTION_FILE, "Unknown format: " + format);
    }
    if (root.optInt("version", VERSION) > VERSION) {
      throw new ConnectionFileException(
          FileError.NEWER_VERSION, "Format version " + root.optInt("version"));
    }
    JSONArray array = root.optJSONArray("connections");
    if (array == null) {
      throw new ConnectionFileException(FileError.NOT_A_CONNECTION_FILE, "No connections array");
    }
    if (array.length() > MAX_CONNECTIONS) {
      throw new ConnectionFileException(FileError.TOO_LARGE, array.length() + " connections");
    }

    List<Entry> entries = new ArrayList<>();
    List<SkippedEntry> skipped = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      JSONObject object = array.optJSONObject(i);
      if (object == null) {
        skipped.add(new SkippedEntry(i + 1, null, EntryError.MISSING_SERVER));
        continue;
      }
      String name = trimmed(object, "name");
      EntryError error = validate(object);
      if (error != null) {
        skipped.add(new SkippedEntry(i + 1, name.isEmpty() ? null : name, error));
        continue;
      }
      entries.add(toEntry(object));
    }
    return new ParseResult(entries, skipped);
  }

  @Nullable
  private static EntryError validate(JSONObject object) {
    String server = stripLeadingBackslashes(trimmed(object, "server"));
    if (server.isEmpty()) return EntryError.MISSING_SERVER;
    for (int i = 0; i < server.length(); i++) {
      if (INVALID_SERVER_CHARS.indexOf(server.charAt(i)) >= 0
          && !(server.charAt(i) == ':' && isIpv6Literal(server))) {
        return EntryError.INVALID_SERVER;
      }
    }
    if (object.has("port")) {
      int port = object.optInt("port", -1);
      if (port < 1 || port > 65535) return EntryError.INVALID_PORT;
    }
    String share = trimmed(object, "share");
    for (int i = 0; i < share.length(); i++) {
      if (INVALID_SHARE_NAME_CHARS.indexOf(share.charAt(i)) >= 0) return EntryError.INVALID_SHARE;
    }
    return null;
  }

  private static Entry toEntry(JSONObject object) {
    SmbConnection connection = new SmbConnection();
    String server = stripLeadingBackslashes(trimmed(object, "server"));
    String name = trimmed(object, "name");
    connection.setName(name.isEmpty() ? server : name);
    connection.setServer(server);
    connection.setPort(object.optInt("port", SmbConnection.DEFAULT_PORT));
    connection.setShare(trimmed(object, "share"));
    connection.setInitialPath(normalizePath(trimmed(object, "initialPath")));
    connection.setDomain(trimmed(object, "domain"));
    connection.setEncryptData(object.optBoolean("encryptData", false));
    connection.setSigningRequired(object.optBoolean("signingRequired", false));
    connection.setAsyncTransport(object.optBoolean("asyncTransport", false));

    boolean guest = object.optBoolean("guest", false);
    String username = guest ? "" : trimmed(object, "username");
    // Passwords are taken as they are: leading or trailing spaces may belong to them
    String password = guest || object.isNull("password") ? "" : object.optString("password", "");
    connection.setUsername(username);
    connection.setPassword(password);
    // Without explicit guest access, a missing username or password is for the user to fill in;
    // an empty username alone would otherwise mean an anonymous login
    connection.setCredentialsRequired(!guest && (username.isEmpty() || password.isEmpty()));
    return new Entry(connection, !password.isEmpty());
  }

  // ---------------------------------------------------------------------------------------------
  // Writing

  /**
   * Writes connections into a connection file. Passwords are never written. Usernames are written
   * only if {@code includeUsernames} is set; a connection without a username (guest access) is
   * marked {@code "guest": true} so it imports as guest access again.
   */
  @NonNull
  public static String write(@NonNull List<SmbConnection> connections, boolean includeUsernames) {
    try {
      JSONArray array = new JSONArray();
      for (SmbConnection connection : connections) {
        JSONObject object = new JSONObject();
        object.put("name", nonNull(connection.getName()));
        object.put("server", nonNull(connection.getServer()));
        object.put("port", connection.getPort() > 0 ? connection.getPort() : 445);
        putIfNotEmpty(object, "share", connection.getShare());
        putIfNotEmpty(object, "initialPath", connection.getInitialPath());
        putIfNotEmpty(object, "domain", connection.getDomain());
        String username = nonNull(connection.getUsername());
        if (username.isEmpty() && !connection.isCredentialsRequired()) {
          object.put("guest", true);
        } else if (includeUsernames) {
          putIfNotEmpty(object, "username", username);
        }
        object.put("encryptData", connection.isEncryptData());
        object.put("signingRequired", connection.isSigningRequired());
        object.put("asyncTransport", connection.isAsyncTransport());
        array.put(object);
      }
      JSONObject root = new JSONObject();
      root.put("format", FORMAT);
      root.put("version", VERSION);
      root.put("connections", array);
      return root.toString(2) + "\n";
    } catch (JSONException e) {
      // Only thrown for non-finite numbers, which a connection does not contain
      throw new IllegalStateException(e);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers

  private static String trimmed(JSONObject object, String key) {
    if (object.isNull(key)) return "";
    return object.optString(key, "").trim();
  }

  private static String nonNull(@Nullable String value) {
    return value != null ? value : "";
  }

  private static void putIfNotEmpty(JSONObject object, String key, @Nullable String value)
      throws JSONException {
    if (value != null && !value.isEmpty()) object.put(key, value);
  }

  /** Accepts {@code \\nas} as written in Windows, which means the server {@code nas}. */
  private static String stripLeadingBackslashes(String server) {
    int start = 0;
    while (start < server.length() && server.charAt(start) == '\\') start++;
    return server.substring(start);
  }

  private static boolean isIpv6Literal(String server) {
    String lower = server.toLowerCase(Locale.ROOT);
    if (lower.indexOf(':') == lower.lastIndexOf(':')) return false; // host:port, not IPv6
    for (int i = 0; i < lower.length(); i++) {
      char c = lower.charAt(i);
      if (!(c == ':' || c == '.' || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
        return false;
      }
    }
    return true;
  }

  /** Same normalization as the connection dialog: forward slashes, no leading/trailing slash. */
  private static String normalizePath(String path) {
    String p = path.replace('\\', '/');
    while (p.contains("//")) p = p.replace("//", "/");
    while (p.startsWith("/")) p = p.substring(1);
    while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
    return p;
  }

  private static String stripByteOrderMark(String text) {
    return !text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK ? text.substring(1) : text;
  }
}
