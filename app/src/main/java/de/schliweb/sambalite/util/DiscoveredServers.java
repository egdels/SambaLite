/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.util;

import de.schliweb.sambalite.data.model.SmbConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;

/**
 * The servers shown in the network search dialog, combined from two sources: the port scan of the
 * local subnet ({@link NetworkScanner}) and SMB services announced via mDNS/DNS-SD ({@link
 * SmbServiceDiscovery}, e.g. by Avahi). A server found by both appears once, matched by its IP
 * address. Pure Java so the merging can be unit tested on the JVM; not thread-safe, meant to be
 * used from the main thread.
 */
public class DiscoveredServers {

  private final List<Entry> entries = new ArrayList<>();
  private int nextKey = 0;

  /** Adds a host found by the port scan, or merges it into the mDNS entry with the same address. */
  public void addScanned(
      String ipAddress,
      String hostName,
      boolean smbPortOpen,
      boolean netbiosPortOpen,
      long responseTime) {
    if (ipAddress == null || ipAddress.trim().isEmpty()) {
      return;
    }
    String ip = ipAddress.trim();
    Entry entry = findByAddress(ip);
    if (entry == null) {
      entry = new Entry(nextKey++);
      entry.address = ip;
      entries.add(entry);
    }
    entry.foundByScan = true;
    entry.scanHostName = hostName;
    entry.smbPortOpen = smbPortOpen;
    entry.netbiosPortOpen = netbiosPortOpen;
    entry.responseTime = responseTime;
  }

  /** Registers an announced, not yet resolved service. Returns false for duplicates. */
  public boolean addMdnsFound(String serviceName) {
    if (serviceName == null || serviceName.trim().isEmpty()) {
      return false;
    }
    String name = serviceName.trim();
    if (findByServiceName(name) != null) {
      return false;
    }
    Entry entry = new Entry(nextKey++);
    entry.serviceName = name;
    entries.add(entry);
    return true;
  }

  /**
   * Completes an announced service with its host name, address and port. If the port scan already
   * found the same address, the two entries become one at the earlier position. Returns false if
   * the service is unknown or has neither host name nor address.
   */
  public boolean markMdnsResolved(String serviceName, String hostName, String address, int port) {
    if (serviceName == null) {
      return false;
    }
    Entry entry = findByServiceName(serviceName.trim());
    String host = normalizeMdnsHostName(hostName);
    String ip = address != null && !address.trim().isEmpty() ? address.trim() : null;
    if (entry == null || (host == null && ip == null)) {
      return false;
    }
    Entry scanned = ip != null ? findByAddress(ip) : null;
    if (scanned != null && scanned != entry) {
      // Keep the entry that was listed first, so the list does not jump
      Entry keep = entries.indexOf(scanned) < entries.indexOf(entry) ? scanned : entry;
      Entry drop = keep == scanned ? entry : scanned;
      keep.absorb(drop);
      entries.remove(drop);
      entry = keep;
    }
    entry.serviceName = serviceName.trim();
    entry.mdnsHostName = host;
    entry.address = ip != null ? ip : entry.address;
    entry.announcedPort = isValidPort(port) ? port : 0;
    entry.resolved = true;
    return true;
  }

  /**
   * Handles a service that is no longer announced. A host the port scan found as well stays listed
   * without its mDNS details. Returns true if the list changed.
   */
  public boolean removeMdns(String serviceName) {
    if (serviceName == null) {
      return false;
    }
    Entry entry = findByServiceName(serviceName.trim());
    if (entry == null) {
      return false;
    }
    if (entry.foundByScan) {
      entry.serviceName = null;
      entry.mdnsHostName = null;
      entry.announcedPort = 0;
      entry.resolved = false;
    } else {
      entries.remove(entry);
    }
    return true;
  }

  /** A snapshot of the current entries in the order they were found. */
  public List<Entry> getEntries() {
    return new ArrayList<>(entries);
  }

  public int size() {
    return entries.size();
  }

  /** Clears all results before a new search. */
  public void clear() {
    entries.clear();
  }

  private Entry findByAddress(String ip) {
    for (Entry e : entries) {
      if (ip.equals(e.address)) {
        return e;
      }
    }
    return null;
  }

  private Entry findByServiceName(String name) {
    for (Entry e : entries) {
      if (name.equals(e.serviceName)) {
        return e;
      }
    }
    return null;
  }

  /** Whether the given value is a usable TCP port. */
  public static boolean isValidPort(int port) {
    return port > 0 && port <= 65535;
  }

  /**
   * Extracts the host name stored inside an {@link InetAddress} without triggering a reverse DNS
   * lookup. {@link InetAddress#getHostName()} may block on a network lookup when the address was
   * created from raw bytes, so this parses the {@code "hostname/literal"} form of {@link
   * InetAddress#toString()} instead. Returns null if no host name is present.
   */
  public static String nonResolvingHostName(InetAddress address) {
    if (address == null) {
      return null;
    }
    String text = address.toString();
    int slash = text.indexOf('/');
    if (slash <= 0) {
      return null;
    }
    String name = text.substring(0, slash).trim();
    return name.isEmpty() ? null : name;
  }

  /**
   * Normalizes a host name coming from mDNS/DNS-SD. Trims whitespace, removes trailing dots and
   * appends {@code .local} when the name is a bare label (mDNS host names are always in the {@code
   * host.local} domain, but some resolvers report only the label). Returns null for empty input or
   * IP literals, which must not be treated as host names.
   */
  public static String normalizeMdnsHostName(String hostName) {
    if (hostName == null) {
      return null;
    }
    String name = hostName.trim();
    while (name.endsWith(".")) {
      name = name.substring(0, name.length() - 1);
    }
    if (name.isEmpty() || name.indexOf(':') >= 0 || name.matches("[0-9.]+")) {
      return null; // Empty or an IPv6/IPv4 literal - not a usable host name.
    }
    if (name.indexOf('.') < 0) {
      name = name + ".local";
    }
    return name;
  }

  /**
   * The address to connect to from a resolved service: the first IPv4 address, else the first IPv6
   * address (without brackets; a link-local scope suffix is kept). Returns null if there is none.
   */
  public static String preferredAddress(List<InetAddress> addresses) {
    if (addresses == null) {
      return null;
    }
    String ipv6 = null;
    for (InetAddress address : addresses) {
      if (address == null) {
        continue;
      }
      String literal = address.getHostAddress();
      if (literal == null || literal.isEmpty()) {
        continue;
      }
      if (address instanceof Inet4Address) {
        return literal;
      }
      if (ipv6 == null) {
        ipv6 = literal;
      }
    }
    return ipv6;
  }

  /** One server in the list. */
  @Getter
  public static final class Entry {
    /** Stable for the lifetime of the entry; survives updates and merges into it. */
    private final int key;

    private String address;
    private boolean foundByScan;
    private String scanHostName;
    private boolean smbPortOpen;
    private boolean netbiosPortOpen;
    private long responseTime;

    private String serviceName;
    private String mdnsHostName;
    private int announcedPort;
    private boolean resolved;

    private Entry(int key) {
      this.key = key;
    }

    private void absorb(Entry other) {
      if (other.foundByScan) {
        foundByScan = true;
        scanHostName = other.scanHostName;
        smbPortOpen = other.smbPortOpen;
        netbiosPortOpen = other.netbiosPortOpen;
        responseTime = other.responseTime;
      }
    }

    public boolean isFoundByMdns() {
      return serviceName != null;
    }

    /** Whether the entry can fill in the connection: it has an address or an mDNS host name. */
    public boolean isUsable() {
      return address != null || mdnsHostName != null;
    }

    /**
     * The port to fill in: the announced one, else 445 if the scan found it open, else 0 to leave
     * the port field as it is.
     */
    public int getPortToUse() {
      if (isValidPort(announcedPort)) {
        return announcedPort;
      }
      return smbPortOpen || resolved ? SmbConnection.DEFAULT_PORT : 0;
    }

    /**
     * The server to fill in. The mDNS host name (e.g. {@code nas.local}) survives a change of the
     * DHCP address, but not every Android device resolves {@code .local} names; it is therefore
     * only used if the caller found that it resolves on this device, or if there is no address.
     */
    public String getServerToUse(boolean mdnsHostNameResolves) {
      if (mdnsHostName != null && (mdnsHostNameResolves || address == null)) {
        return mdnsHostName;
      }
      return address;
    }

    /** The announced service name, else the scanned host name, else the address. */
    public String getDisplayName() {
      if (serviceName != null) {
        return serviceName;
      }
      if (scanHostName != null && !scanHostName.equals(address)) {
        return scanHostName;
      }
      return address;
    }

    @Override
    public String toString() {
      return "DiscoveredServers.Entry{"
          + "key="
          + key
          + ", address='"
          + address
          + "', scan="
          + foundByScan
          + ", serviceName='"
          + serviceName
          + "', mdnsHostName='"
          + mdnsHostName
          + "', port="
          + announcedPort
          + ", resolved="
          + resolved
          + '}';
    }
  }
}
