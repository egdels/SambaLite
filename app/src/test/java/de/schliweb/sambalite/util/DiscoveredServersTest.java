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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the server list of the network search dialog, which combines the port scan with SMB
 * services announced via mDNS/DNS-SD, and the host name helpers for resolved services.
 */
public class DiscoveredServersTest {

  private DiscoveredServers servers;

  @Before
  public void setUp() {
    servers = new DiscoveredServers();
  }

  private DiscoveredServers.Entry only() {
    assertEquals(1, servers.size());
    return servers.getEntries().get(0);
  }

  // --- port scan ---

  @Test
  public void addScanned_listsHostOnceByAddress() {
    servers.addScanned("192.168.1.10", null, true, false, 12);
    servers.addScanned("192.168.1.10", "nas.fritz.box", true, true, 8);

    DiscoveredServers.Entry entry = only();
    assertTrue(entry.isFoundByScan());
    assertFalse(entry.isFoundByMdns());
    assertEquals("nas.fritz.box", entry.getDisplayName());
    assertEquals("192.168.1.10", entry.getServerToUse(false));
    assertEquals(445, entry.getPortToUse());
  }

  @Test
  public void addScanned_netbiosOnlyHostLeavesThePortUnchanged() {
    servers.addScanned("192.168.1.11", null, false, true, 5);
    assertEquals(0, only().getPortToUse());
    assertEquals("192.168.1.11", only().getDisplayName());
  }

  @Test
  public void addScanned_ignoresMissingAddress() {
    servers.addScanned(null, "x", true, false, 1);
    servers.addScanned(" ", "x", true, false, 1);
    assertEquals(0, servers.size());
  }

  // --- mDNS ---

  @Test
  public void mdns_foundServiceIsListedButNotUsableUntilResolved() {
    assertTrue(servers.addMdnsFound("Media NAS"));
    assertFalse(servers.addMdnsFound("Media NAS"));
    assertFalse(servers.addMdnsFound("  "));
    assertFalse(servers.addMdnsFound(null));

    DiscoveredServers.Entry entry = only();
    assertEquals("Media NAS", entry.getDisplayName());
    assertFalse(entry.isUsable());
  }

  @Test
  public void mdns_resolvedServiceBringsHostNameAddressAndAnnouncedPort() {
    servers.addMdnsFound("Media NAS");
    assertTrue(servers.markMdnsResolved("Media NAS", "medianas", "192.168.1.20", 4455));

    DiscoveredServers.Entry entry = only();
    assertTrue(entry.isUsable());
    assertEquals("medianas.local", entry.getMdnsHostName());
    assertEquals(4455, entry.getPortToUse());
  }

  @Test
  public void mdns_invalidAnnouncedPortFallsBackTo445() {
    servers.addMdnsFound("A");
    servers.markMdnsResolved("A", "a.local", "192.168.1.21", 0);
    assertEquals(445, only().getPortToUse());

    servers.clear();
    servers.addMdnsFound("B");
    servers.markMdnsResolved("B", "b.local", "192.168.1.22", 70_000);
    assertEquals(445, only().getPortToUse());
  }

  @Test
  public void mdns_resolveOfUnknownOrHostlessServiceIsIgnored() {
    assertFalse(servers.markMdnsResolved("Unknown", "x.local", "192.168.1.1", 445));
    servers.addMdnsFound("A");
    assertFalse(servers.markMdnsResolved("A", null, null, 445));
    assertFalse(servers.markMdnsResolved("A", "192.168.1.1", " ", 445)); // IP literal is no name
    assertFalse(only().isUsable());
  }

  @Test
  public void mdns_lostServiceIsRemoved() {
    servers.addMdnsFound("A");
    assertTrue(servers.removeMdns("A"));
    assertFalse(servers.removeMdns("A"));
    assertEquals(0, servers.size());
  }

  // --- merging both sources ---

  @Test
  public void merge_scanFirstThenMdns_keepsOneEntryAtTheScanPosition() {
    servers.addScanned("192.168.1.5", null, true, false, 3);
    servers.addScanned("192.168.1.10", null, true, false, 4);
    servers.addMdnsFound("Media NAS");
    servers.markMdnsResolved("Media NAS", "medianas.local", "192.168.1.10", 4455);

    List<DiscoveredServers.Entry> entries = servers.getEntries();
    assertEquals(2, entries.size());
    DiscoveredServers.Entry merged = entries.get(1);
    assertTrue(merged.isFoundByScan());
    assertTrue(merged.isFoundByMdns());
    assertEquals("Media NAS", merged.getDisplayName());
    assertEquals(4455, merged.getPortToUse());
  }

  @Test
  public void merge_mdnsFirstThenScan_addsScanDetailsToTheMdnsEntry() {
    servers.addMdnsFound("Media NAS");
    servers.markMdnsResolved("Media NAS", "medianas.local", "192.168.1.10", 4455);
    int key = only().getKey();
    servers.addScanned("192.168.1.10", "medianas", false, false, 7);

    DiscoveredServers.Entry entry = only();
    assertEquals(key, entry.getKey());
    assertTrue(entry.isFoundByScan());
    assertEquals(4455, entry.getPortToUse()); // the announcement wins over the scan
  }

  @Test
  public void merge_mdnsFoundBeforeScanButResolvedAfter_keepsTheEarlierEntry() {
    servers.addMdnsFound("Media NAS"); // listed first, still unresolved
    servers.addScanned("192.168.1.10", null, true, false, 3);
    int mdnsKey = servers.getEntries().get(0).getKey();
    servers.markMdnsResolved("Media NAS", "medianas.local", "192.168.1.10", 445);

    DiscoveredServers.Entry entry = only();
    assertEquals(mdnsKey, entry.getKey());
    assertTrue(entry.isFoundByScan());
    assertTrue(entry.isSmbPortOpen());
  }

  @Test
  public void merge_lostMdnsServiceKeepsTheScannedHost() {
    servers.addScanned("192.168.1.10", null, true, false, 3);
    servers.addMdnsFound("Media NAS");
    servers.markMdnsResolved("Media NAS", "medianas.local", "192.168.1.10", 4455);

    assertTrue(servers.removeMdns("Media NAS"));
    DiscoveredServers.Entry entry = only();
    assertFalse(entry.isFoundByMdns());
    assertNull(entry.getMdnsHostName());
    assertEquals(445, entry.getPortToUse());
    assertEquals("192.168.1.10", entry.getDisplayName());
  }

  @Test
  public void merge_differentAddressesStaySeparate() {
    servers.addScanned("192.168.1.10", null, true, false, 3);
    servers.addMdnsFound("Other");
    servers.markMdnsResolved("Other", "other.local", "192.168.1.11", 445);
    assertEquals(2, servers.size());
  }

  // --- choosing the server to fill in ---

  @Test
  public void serverToUse_mdnsHostNameOnlyIfItResolvesOnTheDevice() {
    servers.addMdnsFound("Media NAS");
    servers.markMdnsResolved("Media NAS", "medianas.local", "192.168.1.10", 445);
    DiscoveredServers.Entry entry = only();

    assertEquals("medianas.local", entry.getServerToUse(true));
    assertEquals("192.168.1.10", entry.getServerToUse(false));
  }

  @Test
  public void serverToUse_hostNameWithoutAddressIsUsedAnyway() {
    servers.addMdnsFound("Media NAS");
    servers.markMdnsResolved("Media NAS", "medianas.local", null, 445);
    assertEquals("medianas.local", only().getServerToUse(false));
  }

  // --- host helpers (from the mDNS discovery of the private branch) ---

  @Test
  public void preferredAddress_prefersIpv4OverIpv6() throws Exception {
    InetAddress v6 = InetAddress.getByName("fe80::1");
    InetAddress v4 = InetAddress.getByName("192.168.1.10");
    assertEquals("192.168.1.10", DiscoveredServers.preferredAddress(Arrays.asList(v6, v4)));
  }

  @Test
  public void preferredAddress_usesIpv6WithoutBracketsWhenOnlyIpv6() throws Exception {
    InetAddress v6 = InetAddress.getByName("fd00::10");
    String host = DiscoveredServers.preferredAddress(Collections.singletonList(v6));
    assertEquals("fd00:0:0:0:0:0:0:10", host);
    assertFalse(host.contains("["));
  }

  @Test
  public void preferredAddress_returnsNullForMissingInformation() {
    assertNull(DiscoveredServers.preferredAddress(null));
    assertNull(DiscoveredServers.preferredAddress(Collections.emptyList()));
    assertNull(DiscoveredServers.preferredAddress(Collections.singletonList((InetAddress) null)));
  }

  @Test
  public void nonResolvingHostName_returnsStoredHostName() throws Exception {
    InetAddress withName =
        InetAddress.getByAddress("nas.local", new byte[] {(byte) 192, (byte) 168, 1, 10});
    assertEquals("nas.local", DiscoveredServers.nonResolvingHostName(withName));
  }

  @Test
  public void nonResolvingHostName_returnsNullWithoutHostName() throws Exception {
    InetAddress withoutName = InetAddress.getByAddress(new byte[] {(byte) 192, (byte) 168, 1, 10});
    assertNull(DiscoveredServers.nonResolvingHostName(withoutName));
    assertNull(DiscoveredServers.nonResolvingHostName(null));
  }

  @Test
  public void normalizeMdnsHostName_appendsLocalToBareLabel() {
    assertEquals("schlinas.local", DiscoveredServers.normalizeMdnsHostName("schlinas"));
    assertEquals("schlinas.local", DiscoveredServers.normalizeMdnsHostName(" schlinas. "));
  }

  @Test
  public void normalizeMdnsHostName_keepsQualifiedNames() {
    assertEquals("nas.local", DiscoveredServers.normalizeMdnsHostName("nas.local."));
    assertEquals("nas.fritz.box", DiscoveredServers.normalizeMdnsHostName("nas.fritz.box"));
  }

  @Test
  public void normalizeMdnsHostName_rejectsEmptyAndIpLiterals() {
    assertNull(DiscoveredServers.normalizeMdnsHostName(null));
    assertNull(DiscoveredServers.normalizeMdnsHostName("  "));
    assertNull(DiscoveredServers.normalizeMdnsHostName("..."));
    assertNull(DiscoveredServers.normalizeMdnsHostName("192.168.1.10"));
    assertNull(DiscoveredServers.normalizeMdnsHostName("fe80::1"));
  }

  @Test
  public void isValidPort_boundaries() {
    assertFalse(DiscoveredServers.isValidPort(0));
    assertTrue(DiscoveredServers.isValidPort(1));
    assertTrue(DiscoveredServers.isValidPort(65535));
    assertFalse(DiscoveredServers.isValidPort(65536));
  }
}
