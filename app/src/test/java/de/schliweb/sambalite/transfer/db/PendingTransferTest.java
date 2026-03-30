/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.transfer.db;

import static org.junit.Assert.*;

import org.junit.Test;

/** Unit tests for {@link PendingTransfer} entity defaults and field assignments. */
public class PendingTransferTest {

  @Test
  public void defaultValues_areCorrect() {
    PendingTransfer transfer = new PendingTransfer();

    assertEquals(0, transfer.id);
    assertEquals("", transfer.transferType);
    assertEquals("", transfer.localUri);
    assertEquals("", transfer.remotePath);
    assertEquals("", transfer.connectionId);
    assertEquals("", transfer.displayName);
    assertEquals("", transfer.mimeType);
    assertEquals(0, transfer.fileSize);
    assertEquals(0, transfer.bytesTransferred);
    assertEquals("PENDING", transfer.status);
    assertEquals(0, transfer.retryCount);
    assertEquals(3, transfer.maxRetries);
    assertNull(transfer.lastError);
    assertEquals(0, transfer.createdAt);
    assertEquals(0, transfer.updatedAt);
    assertEquals("", transfer.batchId);
    assertEquals(0, transfer.sortOrder);
  }

  @Test
  public void fieldAssignment_worksCorrectly() {
    PendingTransfer transfer = new PendingTransfer();
    transfer.id = 42;
    transfer.transferType = "UPLOAD";
    transfer.localUri = "content://com.android.providers/document/1234";
    transfer.remotePath = "docs\\report.pdf";
    transfer.connectionId = "conn-abc-123";
    transfer.displayName = "report.pdf";
    transfer.mimeType = "application/pdf";
    transfer.fileSize = 1048576;
    transfer.bytesTransferred = 524288;
    transfer.status = "ACTIVE";
    transfer.retryCount = 1;
    transfer.maxRetries = 5;
    transfer.lastError = "Connection timeout";
    transfer.createdAt = 1700000000000L;
    transfer.updatedAt = 1700000001000L;
    transfer.batchId = "batch-xyz";
    transfer.sortOrder = 3;

    assertEquals(42, transfer.id);
    assertEquals("UPLOAD", transfer.transferType);
    assertEquals("content://com.android.providers/document/1234", transfer.localUri);
    assertEquals("docs\\report.pdf", transfer.remotePath);
    assertEquals("conn-abc-123", transfer.connectionId);
    assertEquals("report.pdf", transfer.displayName);
    assertEquals("application/pdf", transfer.mimeType);
    assertEquals(1048576, transfer.fileSize);
    assertEquals(524288, transfer.bytesTransferred);
    assertEquals("ACTIVE", transfer.status);
    assertEquals(1, transfer.retryCount);
    assertEquals(5, transfer.maxRetries);
    assertEquals("Connection timeout", transfer.lastError);
    assertEquals(1700000000000L, transfer.createdAt);
    assertEquals(1700000001000L, transfer.updatedAt);
    assertEquals("batch-xyz", transfer.batchId);
    assertEquals(3, transfer.sortOrder);
  }

  @Test
  public void downloadType_canBeSet() {
    PendingTransfer transfer = new PendingTransfer();
    transfer.transferType = "DOWNLOAD";

    assertEquals("DOWNLOAD", transfer.transferType);
  }

  @Test
  public void statusTransitions_canBeSet() {
    PendingTransfer transfer = new PendingTransfer();

    transfer.status = "PENDING";
    assertEquals("PENDING", transfer.status);

    transfer.status = "ACTIVE";
    assertEquals("ACTIVE", transfer.status);

    transfer.status = "COMPLETED";
    assertEquals("COMPLETED", transfer.status);

    transfer.status = "FAILED";
    assertEquals("FAILED", transfer.status);

    transfer.status = "CANCELLED";
    assertEquals("CANCELLED", transfer.status);
  }
}
