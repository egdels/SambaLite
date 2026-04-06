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
import de.schliweb.sambalite.data.model.SmbConnection;
import java.io.IOException;
import java.io.InputStream;

/**
 * An InputStream that reads data from an SMB file in ranges. This is useful for memory-efficient
 * decoding of thumbnails.
 */
public class SmbInputStream extends InputStream {
  private final SmbRepository repository;
  private final SmbConnection connection;
  private final String remotePath;
  private final long fileSize;
  private long position = 0;
  private byte[] buffer;
  private int bufferPos = 0;
  private int bufferLength = 0;
  private static final int BUFFER_SIZE = 64 * 1024; // 64KB chunks

  public SmbInputStream(
      SmbRepository repository, SmbConnection connection, String remotePath, long fileSize) {
    this.repository = repository;
    this.connection = connection;
    this.remotePath = remotePath;
    this.fileSize = fileSize;
  }

  @Override
  public int read() throws IOException {
    if (position >= fileSize && bufferPos >= bufferLength) {
      return -1;
    }
    if (bufferPos >= bufferLength) {
      if (!fillBuffer()) {
        return -1;
      }
    }
    return buffer[bufferPos++] & 0xFF;
  }

  @Override
  public int read(@NonNull byte[] b, int off, int len) throws IOException {
    if (position >= fileSize && bufferPos >= bufferLength) {
      return -1;
    }

    int totalRead = 0;
    while (len > 0) {
      if (bufferPos >= bufferLength) {
        if (!fillBuffer()) {
          return totalRead > 0 ? totalRead : -1;
        }
      }

      int toCopy = Math.min(len, bufferLength - bufferPos);
      System.arraycopy(buffer, bufferPos, b, off, toCopy);

      off += toCopy;
      len -= toCopy;
      bufferPos += toCopy;
      totalRead += toCopy;
    }
    return totalRead;
  }

  private boolean fillBuffer() throws IOException {
    if (position >= fileSize) {
      return false;
    }
    try {
      int toRead = (int) Math.min(BUFFER_SIZE, fileSize - position);
      buffer = repository.readRange(connection, remotePath, position, toRead);
      bufferLength = buffer.length;
      bufferPos = 0;
      position += bufferLength;
      return bufferLength > 0;
    } catch (Exception e) {
      throw new IOException("Failed to read from SMB: " + e.getMessage(), e);
    }
  }

  @Override
  public long skip(long n) {
    if (n <= 0) return 0;
    long availableInBuffer = (long) bufferLength - bufferPos;
    if (n <= availableInBuffer) {
      bufferPos += (int) n;
      return n;
    } else {
      bufferPos = bufferLength;
      long remainingToSkip = n - availableInBuffer;
      long skipAmount = Math.min(remainingToSkip, fileSize - position);
      position += skipAmount;
      return availableInBuffer + skipAmount;
    }
  }

  @Override
  public int available() {
    return (int) Math.min(Integer.MAX_VALUE, (fileSize - position) + (bufferLength - bufferPos));
  }
}
