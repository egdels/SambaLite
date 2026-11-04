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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ImpersonationLevel;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.NamedPipe;
import com.hierynomus.smbj.share.PipeShare;
import com.hierynomus.smbj.share.Share;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * Lists the shares a server publishes, the way Windows Explorer and {@code smbclient -L} do: one
 * {@code NetrShareEnum} call (MS-SRVS opnum 15, information level 1) over the {@code IPC$\srvsvc}
 * named pipe.
 *
 * <p>smbj has no DCE/RPC layer, so the bind and the request are encoded here by hand. Only the
 * parts of DCE/RPC (C706, MS-RPCE) and NDR that this single call needs are implemented:
 * unauthenticated binding over an already authenticated SMB session, and response fragments.
 *
 * <p>The server decides which shares it lists. Shares hidden with {@code browseable = no} or
 * access-based share enumeration do not appear, and neither do hidden ({@code $}) shares, IPC and
 * printer shares, which {@link #isBrowsableDiskShare} filters out.
 */
final class SrvsvcShareEnumerator {

  /** The calls the enumeration makes on the named pipe; a seam for the unit tests. */
  interface RpcPipe {
    /** Writes one request PDU and returns the first fragment of the response. */
    @NonNull
    byte[] transact(@NonNull byte[] request) throws IOException;

    /** Returns the next fragment of a response that did not fit into one fragment. */
    @NonNull
    byte[] read() throws IOException;
  }

  /** One entry of a {@code SHARE_INFO_1} array. */
  static final class ShareInfo {
    final String name;
    final int type;
    @Nullable final String remark;

    ShareInfo(String name, int type, @Nullable String remark) {
      this.name = name;
      this.type = type;
      this.remark = remark;
    }
  }

  // DCE/RPC packet types
  static final int PTYPE_REQUEST = 0;
  static final int PTYPE_RESPONSE = 2;
  static final int PTYPE_FAULT = 3;
  static final int PTYPE_BIND = 11;
  static final int PTYPE_BIND_ACK = 12;
  static final int PTYPE_BIND_NAK = 13;

  static final int PFC_FIRST_FRAG = 0x01;
  static final int PFC_LAST_FRAG = 0x02;

  private static final int HEADER_LENGTH = 16;

  /**
   * Header plus alloc_hint, context id and cancel count / opnum: where request/response stubs
   * start.
   */
  private static final int STUB_OFFSET = 24;

  /** The largest fragment we accept; the server never sends more than this. */
  static final int MAX_FRAGMENT = 4280;

  static final int OPNUM_NETR_SHARE_ENUM = 15;

  /** srvsvc interface 4b324fc8-1670-01d3-1278-5a47bf6ee188, version 3.0. */
  private static final byte[] SRVSVC_UUID = {
    (byte) 0xc8,
    0x4f,
    0x32,
    0x4b,
    0x70,
    0x16,
    (byte) 0xd3,
    0x01,
    0x12,
    0x78,
    0x5a,
    0x47,
    (byte) 0xbf,
    0x6e,
    (byte) 0xe1,
    (byte) 0x88
  };

  /** NDR transfer syntax 8a885d04-1ceb-11c9-9fe8-08002b104860, version 2. */
  private static final byte[] NDR_UUID = {
    0x04,
    0x5d,
    (byte) 0x88,
    (byte) 0x8a,
    (byte) 0xeb,
    0x1c,
    (byte) 0xc9,
    0x11,
    (byte) 0x9f,
    (byte) 0xe8,
    0x08,
    0x00,
    0x2b,
    0x10,
    0x48,
    0x60
  };

  // MS-SRVS share types
  static final int STYPE_DISKTREE = 0x00000000;
  static final int STYPE_PRINTQ = 0x00000001;
  static final int STYPE_IPC = 0x00000003;
  static final int STYPE_SPECIAL = 0x80000000;
  private static final int STYPE_MASK = 0x0FFFFFFF;

  static final int ERROR_SUCCESS = 0;
  static final int ERROR_MORE_DATA = 234;

  /** Generous upper bound on entries, so a corrupt count cannot make us allocate gigabytes. */
  private static final int MAX_ENTRIES = 100_000;

  private SrvsvcShareEnumerator() {}

  /**
   * Returns the names of the disk shares the server lists for the session's user, sorted
   * case-insensitively.
   *
   * @param session an authenticated session
   * @param serverName the server name or address the session was opened to
   * @throws IOException if the server does not offer the srvsvc pipe or the call fails; the caller
   *     then falls back to other ways of finding shares
   */
  @NonNull
  static List<String> listDiskShares(@NonNull Session session, @NonNull String serverName)
      throws IOException {
    Share ipc = session.connectShare("IPC$");
    try {
      if (!(ipc instanceof PipeShare)) {
        throw new IOException("IPC$ is not a pipe share");
      }
      try (NamedPipe pipe =
          ((PipeShare) ipc)
              .open(
                  "srvsvc",
                  SMB2ImpersonationLevel.Impersonation,
                  EnumSet.of(
                      AccessMask.FILE_READ_DATA,
                      AccessMask.FILE_WRITE_DATA,
                      AccessMask.FILE_APPEND_DATA,
                      AccessMask.FILE_READ_EA,
                      AccessMask.FILE_WRITE_EA,
                      AccessMask.FILE_READ_ATTRIBUTES,
                      AccessMask.FILE_WRITE_ATTRIBUTES,
                      AccessMask.READ_CONTROL,
                      AccessMask.SYNCHRONIZE),
                  null,
                  EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                  SMB2CreateDisposition.FILE_OPEN,
                  null)) {
        return listDiskShares(new SmbjPipe(pipe), serverName);
      }
    } catch (RuntimeException e) {
      // smbj reports protocol and status errors as unchecked exceptions
      throw new IOException("srvsvc share enumeration failed: " + e.getMessage(), e);
    } finally {
      try {
        ipc.close();
      } catch (IOException | RuntimeException ignored) {
        // the enumeration result does not depend on a clean tree disconnect
      }
    }
  }

  /** Runs bind and {@code NetrShareEnum} over the given pipe. */
  @NonNull
  static List<String> listDiskShares(@NonNull RpcPipe pipe, @NonNull String serverName)
      throws IOException {
    checkBindAck(pipe.transact(buildBindRequest(1)));
    byte[] stub = readResponseStub(pipe, pipe.transact(buildShareEnumRequest(2, serverName)), 2);
    List<String> names = new ArrayList<>();
    for (ShareInfo info : parseShareEnumResponse(stub)) {
      if (isBrowsableDiskShare(info) && !containsIgnoreCase(names, info.name)) {
        names.add(info.name);
      }
    }
    Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
    return names;
  }

  /**
   * Whether a share belongs in a list the user picks from: a plain disk share, not an
   * administrative ({@code C$}, {@code ADMIN$}) or other hidden {@code $} share, IPC or printer.
   */
  static boolean isBrowsableDiskShare(@NonNull ShareInfo info) {
    if ((info.type & STYPE_SPECIAL) != 0) return false;
    if ((info.type & STYPE_MASK) != STYPE_DISKTREE) return false;
    return !info.name.isEmpty() && !info.name.endsWith("$");
  }

  // ---------------------------------------------------------------------------------------------
  // Encoding

  /** A bind PDU offering the srvsvc interface with the NDR transfer syntax. */
  @NonNull
  static byte[] buildBindRequest(int callId) {
    ByteBuffer b = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN);
    writeHeader(b, PTYPE_BIND, 72, callId);
    b.putShort((short) MAX_FRAGMENT); // max_xmit_frag
    b.putShort((short) MAX_FRAGMENT); // max_recv_frag
    b.putInt(0); // assoc_group_id
    b.put((byte) 1); // n_context_elem
    b.put((byte) 0);
    b.putShort((short) 0);
    b.putShort((short) 0); // p_cont_id
    b.put((byte) 1); // n_transfer_syn
    b.put((byte) 0);
    b.put(SRVSVC_UUID);
    b.putShort((short) 3); // interface version 3.0
    b.putShort((short) 0);
    b.put(NDR_UUID);
    b.putInt(2); // NDR version 2
    return b.array();
  }

  /** A request PDU calling {@code NetrShareEnum} at information level 1. */
  @NonNull
  static byte[] buildShareEnumRequest(int callId, @NonNull String serverName) {
    ByteArrayOutputStream stub = new ByteArrayOutputStream();
    // [in, string, unique] SRVSVC_HANDLE ServerName
    writeInt(stub, 0x00020000);
    writeConformantVaryingString(stub, "\\\\" + serverName);
    // [in, out] LPSHARE_ENUM_STRUCT InfoStruct (top-level reference pointer, no referent id)
    writeInt(stub, 1); // Level
    writeInt(stub, 1); // union discriminant
    writeInt(stub, 0x00020004); // Level1: pointer to SHARE_INFO_1_CONTAINER
    writeInt(stub, 0); // EntriesRead
    writeInt(stub, 0); // Buffer: null
    // [in] DWORD PreferedMaximumLength: everything in one go
    writeInt(stub, 0xFFFFFFFF);
    // [in, out, unique] DWORD* ResumeHandle
    writeInt(stub, 0x00020008);
    writeInt(stub, 0);
    byte[] stubBytes = stub.toByteArray();

    int length = STUB_OFFSET + stubBytes.length;
    ByteBuffer b = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
    writeHeader(b, PTYPE_REQUEST, length, callId);
    b.putInt(stubBytes.length); // alloc_hint
    b.putShort((short) 0); // p_cont_id
    b.putShort((short) OPNUM_NETR_SHARE_ENUM);
    b.put(stubBytes);
    return b.array();
  }

  private static void writeHeader(ByteBuffer b, int ptype, int fragLength, int callId) {
    b.put((byte) 5); // rpc_vers
    b.put((byte) 0); // rpc_vers_minor
    b.put((byte) ptype);
    b.put((byte) (PFC_FIRST_FRAG | PFC_LAST_FRAG));
    b.put(new byte[] {0x10, 0, 0, 0}); // data representation: little-endian, ASCII, IEEE
    b.putShort((short) fragLength);
    b.putShort((short) 0); // auth_length
    b.putInt(callId);
  }

  private static void writeConformantVaryingString(ByteArrayOutputStream out, String value) {
    byte[] chars = (value + '\0').getBytes(StandardCharsets.UTF_16LE);
    int count = chars.length / 2;
    writeInt(out, count); // max_count
    writeInt(out, 0); // offset
    writeInt(out, count); // actual_count
    out.write(chars, 0, chars.length);
    while (out.size() % 4 != 0) out.write(0);
  }

  private static void writeInt(ByteArrayOutputStream out, int value) {
    out.write(value);
    out.write(value >>> 8);
    out.write(value >>> 16);
    out.write(value >>> 24);
  }

  // ---------------------------------------------------------------------------------------------
  // Decoding

  /** Throws unless the PDU is a bind_ack that accepted our presentation context. */
  static void checkBindAck(@NonNull byte[] pdu) throws IOException {
    try {
      ByteBuffer b = readHeader(pdu);
      int ptype = pdu[2] & 0xFF;
      if (ptype == PTYPE_BIND_NAK) {
        throw new IOException("srvsvc bind rejected, reason " + (b.getShort(16) & 0xFFFF));
      }
      if (ptype != PTYPE_BIND_ACK) {
        throw new IOException("Unexpected DCE/RPC packet type " + ptype + " in reply to bind");
      }
      b.position(24);
      int secAddrLength = b.getShort() & 0xFFFF;
      b.position(align4(b.position() + secAddrLength));
      int results = b.get() & 0xFF;
      b.position(b.position() + 3);
      if (results < 1) throw new IOException("srvsvc bind_ack without results");
      int result = b.getShort() & 0xFFFF;
      if (result != 0) {
        throw new IOException("srvsvc presentation context rejected, result " + result);
      }
    } catch (BufferUnderflowException | IllegalArgumentException e) {
      throw new IOException("Truncated srvsvc bind_ack", e);
    }
  }

  /**
   * Collects the stub data of a response that may span several fragments: the first is {@code
   * first}, any further ones are read from the pipe until one carries the last-fragment flag.
   */
  @NonNull
  static byte[] readResponseStub(@NonNull RpcPipe pipe, @NonNull byte[] first, int callId)
      throws IOException {
    ByteArrayOutputStream stub = new ByteArrayOutputStream();
    byte[] pdu = first;
    // A response is a few kilobytes even with hundreds of shares; bound the loop anyway
    for (int fragments = 0; fragments < 10_000; fragments++) {
      ByteBuffer b;
      try {
        b = readHeader(pdu);
      } catch (BufferUnderflowException e) {
        throw new IOException("Truncated srvsvc response", e);
      }
      int ptype = pdu[2] & 0xFF;
      if (ptype == PTYPE_FAULT) {
        String status =
            pdu.length >= STUB_OFFSET + 4
                ? String.format(Locale.ROOT, "0x%08x", b.getInt(STUB_OFFSET))
                : "unknown";
        throw new IOException("srvsvc call failed with DCE/RPC fault " + status);
      }
      if (ptype != PTYPE_RESPONSE) {
        throw new IOException("Unexpected DCE/RPC packet type " + ptype + " in reply to request");
      }
      if (b.getInt(12) != callId) {
        throw new IOException("srvsvc response for another call");
      }
      int fragLength = b.getShort(8) & 0xFFFF;
      int authLength = b.getShort(10) & 0xFFFF;
      int stubEnd = fragLength - (authLength > 0 ? authLength + 8 : 0);
      if (fragLength > pdu.length || stubEnd < STUB_OFFSET) {
        throw new IOException("Malformed srvsvc response fragment");
      }
      stub.write(pdu, STUB_OFFSET, stubEnd - STUB_OFFSET);
      if ((pdu[3] & PFC_LAST_FRAG) != 0) {
        return stub.toByteArray();
      }
      pdu = pipe.read();
    }
    throw new IOException("srvsvc response has too many fragments");
  }

  private static ByteBuffer readHeader(byte[] pdu) throws IOException {
    if (pdu.length < HEADER_LENGTH) throw new BufferUnderflowException();
    if (pdu[0] != 5) throw new IOException("Not a DCE/RPC version 5 packet");
    if ((pdu[4] & 0xF0) != 0x10) {
      throw new IOException("Big-endian DCE/RPC responses are not supported");
    }
    return ByteBuffer.wrap(pdu).order(ByteOrder.LITTLE_ENDIAN);
  }

  /**
   * Decodes the NDR output of {@code NetrShareEnum} at level 1 and returns all entries, unfiltered.
   *
   * @throws IOException if the data is malformed or the call returned an error status
   */
  @NonNull
  static List<ShareInfo> parseShareEnumResponse(@NonNull byte[] stub) throws IOException {
    try {
      ByteBuffer b = ByteBuffer.wrap(stub).order(ByteOrder.LITTLE_ENDIAN);
      List<ShareInfo> shares = new ArrayList<>();

      int level = b.getInt();
      int discriminant = b.getInt();
      if (level != 1 || discriminant != 1) {
        throw new IOException("srvsvc answered with information level " + level);
      }
      if (b.getInt() != 0) { // pointer to SHARE_INFO_1_CONTAINER
        int entriesRead = b.getInt();
        if (b.getInt() != 0) { // pointer to the SHARE_INFO_1 array
          int count = b.getInt(); // conformance
          if (count < 0 || count > MAX_ENTRIES || count < entriesRead) {
            throw new IOException("Implausible srvsvc entry count " + count);
          }
          int[] namePointers = new int[count];
          int[] types = new int[count];
          int[] remarkPointers = new int[count];
          for (int i = 0; i < count; i++) {
            namePointers[i] = b.getInt();
            types[i] = b.getInt();
            remarkPointers[i] = b.getInt();
          }
          for (int i = 0; i < count; i++) {
            String name = namePointers[i] != 0 ? readConformantVaryingString(b) : null;
            String remark = remarkPointers[i] != 0 ? readConformantVaryingString(b) : null;
            if (name != null) shares.add(new ShareInfo(name, types[i], remark));
          }
        }
      }

      b.getInt(); // TotalEntries
      if (b.getInt() != 0) b.getInt(); // ResumeHandle
      int status = b.getInt();
      if (status != ERROR_SUCCESS && status != ERROR_MORE_DATA) {
        throw new IOException("NetrShareEnum returned error " + status);
      }
      return shares;
    } catch (BufferUnderflowException | IllegalArgumentException e) {
      throw new IOException("Malformed srvsvc share list", e);
    }
  }

  private static String readConformantVaryingString(ByteBuffer b) throws IOException {
    b.getInt(); // max_count
    int offset = b.getInt();
    int actual = b.getInt();
    if (offset != 0 || actual < 0 || actual > b.remaining() / 2) {
      throw new IOException("Malformed string in srvsvc share list");
    }
    byte[] chars = new byte[actual * 2];
    b.get(chars);
    b.position(align4(b.position()));
    String value = new String(chars, StandardCharsets.UTF_16LE);
    int nul = value.indexOf('\0');
    return nul >= 0 ? value.substring(0, nul) : value;
  }

  private static int align4(int position) {
    return (position + 3) & ~3;
  }

  private static boolean containsIgnoreCase(List<String> names, String name) {
    for (String existing : names) {
      if (existing.equalsIgnoreCase(name)) return true;
    }
    return false;
  }

  /** {@link RpcPipe} over an smbj named pipe in message mode. */
  private static final class SmbjPipe implements RpcPipe {
    private final NamedPipe pipe;

    SmbjPipe(NamedPipe pipe) {
      this.pipe = pipe;
    }

    @NonNull
    @Override
    public byte[] transact(@NonNull byte[] request) {
      return pipe.transact(request);
    }

    @NonNull
    @Override
    public byte[] read() throws IOException {
      // A message-mode read returns one whole fragment; ours are at most MAX_FRAGMENT bytes
      byte[] buffer = new byte[MAX_FRAGMENT];
      int length = pipe.read(buffer);
      if (length <= 0) throw new IOException("srvsvc pipe closed mid-response");
      byte[] fragment = new byte[length];
      System.arraycopy(buffer, 0, fragment, 0, length);
      return fragment;
    }
  }
}
