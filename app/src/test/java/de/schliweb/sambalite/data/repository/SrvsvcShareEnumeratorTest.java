/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.data.repository;

import static org.junit.Assert.*;

import androidx.annotation.NonNull;
import de.schliweb.sambalite.data.repository.SrvsvcShareEnumerator.ShareInfo;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import org.junit.Test;

/**
 * Encoding and decoding of the srvsvc {@code NetrShareEnum} call (issue #47). The round trip
 * against a real server is covered by {@link ShareEnumerationIntegrationTest}; these tests pin the
 * wire format and the handling of fragments, faults and malformed replies.
 */
public class SrvsvcShareEnumeratorTest {

  // ===== Encoding =====

  @Test
  public void bindRequestOffersSrvsvcWithNdr() {
    byte[] bind = SrvsvcShareEnumerator.buildBindRequest(7);
    ByteBuffer b = le(bind);

    assertEquals(72, bind.length);
    assertEquals(5, bind[0]);
    assertEquals(SrvsvcShareEnumerator.PTYPE_BIND, bind[2]);
    assertEquals(0x03, bind[3]);
    assertEquals(0x10, bind[4]);
    assertEquals(72, b.getShort(8));
    assertEquals(7, b.getInt(12));
    assertEquals(SrvsvcShareEnumerator.MAX_FRAGMENT, b.getShort(16) & 0xFFFF);
    assertEquals(1, bind[24]); // one presentation context
    assertEquals(
        "c84f324b7016d30112785a47bf6ee188", hex(Arrays.copyOfRange(bind, 32, 48)));
    assertEquals(3, b.getShort(48)); // srvsvc 3.0
    assertEquals(
        "045d888aeb1cc9119fe808002b104860", hex(Arrays.copyOfRange(bind, 52, 68)));
    assertEquals(2, b.getInt(68)); // NDR version 2
  }

  @Test
  public void shareEnumRequestAsksForLevelOne() {
    byte[] request = SrvsvcShareEnumerator.buildShareEnumRequest(2, "nas");
    ByteBuffer b = le(request);

    assertEquals(SrvsvcShareEnumerator.PTYPE_REQUEST, request[2]);
    assertEquals(request.length, b.getShort(8));
    assertEquals(2, b.getInt(12));
    assertEquals(request.length - 24, b.getInt(16)); // alloc_hint
    assertEquals(SrvsvcShareEnumerator.OPNUM_NETR_SHARE_ENUM, b.getShort(22));

    b.position(24);
    assertNotEquals(0, b.getInt()); // ServerName pointer
    assertEquals(6, b.getInt()); // "\\nas" plus terminator
    assertEquals(0, b.getInt());
    assertEquals(6, b.getInt());
    byte[] name = new byte[12];
    b.get(name);
    assertEquals("\\\\nas\0", new String(name, StandardCharsets.UTF_16LE));
    assertEquals(1, b.getInt()); // Level
    assertEquals(1, b.getInt()); // union discriminant
    assertNotEquals(0, b.getInt()); // container pointer
    assertEquals(0, b.getInt()); // EntriesRead
    assertEquals(0, b.getInt()); // Buffer
    assertEquals(0xFFFFFFFF, b.getInt()); // PreferedMaximumLength
    assertNotEquals(0, b.getInt()); // ResumeHandle pointer
    assertEquals(0, b.getInt());
    assertFalse(b.hasRemaining());
  }

  // ===== Decoding =====

  @Test
  public void parsesSharesWithAndWithoutRemarks() throws Exception {
    byte[] stub =
        shareEnumStub(
            0,
            new ShareInfo("Data", SrvsvcShareEnumerator.STYPE_DISKTREE, "Daten"),
            new ShareInfo("IPC$", SrvsvcShareEnumerator.STYPE_IPC, null),
            new ShareInfo("Fotos Ä", SrvsvcShareEnumerator.STYPE_DISKTREE, ""));

    List<ShareInfo> shares = SrvsvcShareEnumerator.parseShareEnumResponse(stub);

    assertEquals(3, shares.size());
    assertEquals("Data", shares.get(0).name);
    assertEquals("Daten", shares.get(0).remark);
    assertEquals("IPC$", shares.get(1).name);
    assertNull(shares.get(1).remark);
    assertEquals(SrvsvcShareEnumerator.STYPE_IPC, shares.get(1).type);
    assertEquals("Fotos Ä", shares.get(2).name);
  }

  @Test
  public void parsesEmptyShareList() throws Exception {
    assertTrue(SrvsvcShareEnumerator.parseShareEnumResponse(shareEnumStub(0)).isEmpty());
  }

  @Test
  public void acceptsMoreDataStatus() throws Exception {
    byte[] stub =
        shareEnumStub(
            SrvsvcShareEnumerator.ERROR_MORE_DATA,
            new ShareInfo("Data", SrvsvcShareEnumerator.STYPE_DISKTREE, null));

    assertEquals(1, SrvsvcShareEnumerator.parseShareEnumResponse(stub).size());
  }

  @Test
  public void rejectsAccessDenied() {
    byte[] stub = shareEnumStub(5);

    IOException e =
        assertThrows(IOException.class, () -> SrvsvcShareEnumerator.parseShareEnumResponse(stub));
    assertTrue(e.getMessage().contains("5"));
  }

  @Test
  public void rejectsTruncatedStubWithIoException() {
    byte[] stub =
        shareEnumStub(0, new ShareInfo("Data", SrvsvcShareEnumerator.STYPE_DISKTREE, "x"));
    for (int length = 0; length < stub.length; length++) {
      byte[] truncated = Arrays.copyOf(stub, length);
      assertThrows(
          "length " + length,
          IOException.class,
          () -> SrvsvcShareEnumerator.parseShareEnumResponse(truncated));
    }
  }

  @Test
  public void rejectsImplausibleEntryCount() {
    ByteBuffer b = le(new byte[32]);
    b.putInt(1).putInt(1).putInt(0x20000).putInt(1).putInt(0x20004).putInt(Integer.MAX_VALUE);

    assertThrows(
        IOException.class, () -> SrvsvcShareEnumerator.parseShareEnumResponse(b.array()));
  }

  // ===== Filtering =====

  @Test
  public void keepsOnlyVisibleDiskShares() {
    assertTrue(browsable("Data", SrvsvcShareEnumerator.STYPE_DISKTREE));
    assertTrue(browsable("Temp", SrvsvcShareEnumerator.STYPE_DISKTREE | 0x40000000));
    assertFalse(browsable("IPC$", SrvsvcShareEnumerator.STYPE_IPC));
    assertFalse(browsable("ADMIN$", SrvsvcShareEnumerator.STYPE_SPECIAL));
    assertFalse(browsable("C$", SrvsvcShareEnumerator.STYPE_SPECIAL));
    assertFalse(browsable("backup$", SrvsvcShareEnumerator.STYPE_DISKTREE));
    assertFalse(browsable("Laser", SrvsvcShareEnumerator.STYPE_PRINTQ));
    assertFalse(browsable("", SrvsvcShareEnumerator.STYPE_DISKTREE));
  }

  // ===== Whole exchange over a fake pipe =====

  @Test
  public void listsFilteredSortedSharesAcrossFragments() throws Exception {
    byte[] stub =
        shareEnumStub(
            0,
            new ShareInfo("zeta", SrvsvcShareEnumerator.STYPE_DISKTREE, null),
            new ShareInfo("IPC$", SrvsvcShareEnumerator.STYPE_IPC, "IPC Service"),
            new ShareInfo("Alpha", SrvsvcShareEnumerator.STYPE_DISKTREE, "first"),
            new ShareInfo("ALPHA", SrvsvcShareEnumerator.STYPE_DISKTREE, null),
            new ShareInfo("beta", SrvsvcShareEnumerator.STYPE_DISKTREE, null));
    // Split the stub over three fragments at arbitrary points
    int third = stub.length / 3;
    FakePipe pipe =
        new FakePipe(
            bindAck(0),
            response(2, 0x01, Arrays.copyOfRange(stub, 0, third)),
            response(2, 0x00, Arrays.copyOfRange(stub, third, 2 * third)),
            response(2, 0x02, Arrays.copyOfRange(stub, 2 * third, stub.length)));

    List<String> shares = SrvsvcShareEnumerator.listDiskShares(pipe, "nas");

    assertEquals(Arrays.asList("Alpha", "beta", "zeta"), shares);
    assertEquals(2, pipe.transacts);
    assertEquals(2, pipe.reads);
  }

  @Test
  public void bindNakFails() {
    byte[] nak = new byte[24];
    header(le(nak), SrvsvcShareEnumerator.PTYPE_BIND_NAK, 0x03, nak.length, 1);

    assertThrows(
        IOException.class,
        () -> SrvsvcShareEnumerator.listDiskShares(new FakePipe(nak), "nas"));
  }

  @Test
  public void rejectedPresentationContextFails() {
    assertThrows(
        IOException.class,
        () -> SrvsvcShareEnumerator.listDiskShares(new FakePipe(bindAck(2)), "nas"));
  }

  @Test
  public void faultReportsStatus() {
    byte[] fault = new byte[32];
    ByteBuffer b = le(fault);
    header(b, SrvsvcShareEnumerator.PTYPE_FAULT, 0x03, fault.length, 2);
    b.putInt(24, 0x1c010002); // nca_s_op_rng_error

    IOException e =
        assertThrows(
            IOException.class,
            () -> SrvsvcShareEnumerator.listDiskShares(new FakePipe(bindAck(0), fault), "nas"));
    assertTrue(e.getMessage().contains("0x1c010002"));
  }

  @Test
  public void responseForAnotherCallFails() {
    byte[] stub = shareEnumStub(0);
    assertThrows(
        IOException.class,
        () ->
            SrvsvcShareEnumerator.listDiskShares(
                new FakePipe(bindAck(0), response(9, 0x03, stub)), "nas"));
  }

  // ===== Helpers =====

  private static boolean browsable(String name, int type) {
    return SrvsvcShareEnumerator.isBrowsableDiskShare(new ShareInfo(name, type, null));
  }

  /** Encodes NetrShareEnum output the way an NDR marshaller does. */
  private static byte[] shareEnumStub(int status, ShareInfo... shares) {
    Ndr out = new Ndr();
    out.u32(1).u32(1).u32(0x00020000); // level, discriminant, container pointer
    out.u32(shares.length);
    if (shares.length == 0) {
      out.u32(0);
    } else {
      out.u32(0x00020004).u32(shares.length);
      int referent = 0x00020008;
      for (ShareInfo share : shares) {
        out.u32(referent++).u32(share.type).u32(share.remark != null ? referent++ : 0);
      }
      for (ShareInfo share : shares) {
        out.string(share.name);
        if (share.remark != null) out.string(share.remark);
      }
    }
    out.u32(shares.length); // TotalEntries
    out.u32(0); // ResumeHandle: null
    out.u32(status);
    return out.bytes();
  }

  private static byte[] bindAck(int result) {
    byte[] secAddr = "\\PIPE\\srvsvc\0".getBytes(StandardCharsets.US_ASCII);
    int resultsOffset = (26 + secAddr.length + 3) & ~3;
    byte[] pdu = new byte[resultsOffset + 4 + 24];
    ByteBuffer b = le(pdu);
    header(b, SrvsvcShareEnumerator.PTYPE_BIND_ACK, 0x03, pdu.length, 1);
    b.putShort(16, (short) 4280).putShort(18, (short) 4280).putInt(20, 0x1234);
    b.putShort(24, (short) secAddr.length);
    System.arraycopy(secAddr, 0, pdu, 26, secAddr.length);
    pdu[resultsOffset] = 1;
    b.putShort(resultsOffset + 4, (short) result);
    return pdu;
  }

  private static byte[] response(int callId, int flags, byte[] stubPart) {
    byte[] pdu = new byte[24 + stubPart.length];
    ByteBuffer b = le(pdu);
    header(b, SrvsvcShareEnumerator.PTYPE_RESPONSE, flags, pdu.length, callId);
    b.putInt(16, stubPart.length);
    System.arraycopy(stubPart, 0, pdu, 24, stubPart.length);
    return pdu;
  }

  private static void header(ByteBuffer b, int ptype, int flags, int length, int callId) {
    b.put(0, (byte) 5);
    b.put(2, (byte) ptype);
    b.put(3, (byte) flags);
    b.put(4, (byte) 0x10);
    b.putShort(8, (short) length);
    b.putInt(12, callId);
  }

  private static ByteBuffer le(byte[] bytes) {
    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
  }

  private static String hex(byte[] bytes) {
    StringBuilder sb = new StringBuilder();
    for (byte value : bytes) sb.append(String.format("%02x", value));
    return sb.toString();
  }

  private static final class Ndr {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    Ndr u32(int value) {
      out.write(value);
      out.write(value >>> 8);
      out.write(value >>> 16);
      out.write(value >>> 24);
      return this;
    }

    void string(String value) {
      byte[] chars = (value + '\0').getBytes(StandardCharsets.UTF_16LE);
      u32(chars.length / 2).u32(0).u32(chars.length / 2);
      out.write(chars, 0, chars.length);
      while (out.size() % 4 != 0) out.write(0);
    }

    byte[] bytes() {
      return out.toByteArray();
    }
  }

  /** Replays canned PDUs: the first answers to each transact, the rest to reads. */
  private static final class FakePipe implements SrvsvcShareEnumerator.RpcPipe {
    private final Deque<byte[]> replies;
    int transacts;
    int reads;

    FakePipe(byte[]... replies) {
      this.replies = new ArrayDeque<>(Arrays.asList(replies));
    }

    @NonNull
    @Override
    public byte[] transact(@NonNull byte[] request) throws IOException {
      transacts++;
      return next();
    }

    @NonNull
    @Override
    public byte[] read() throws IOException {
      reads++;
      return next();
    }

    private byte[] next() throws IOException {
      if (replies.isEmpty()) throw new IOException("no more replies");
      return replies.poll();
    }
  }
}
