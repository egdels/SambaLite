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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** A rename that only changes upper and lower case must not be refused as "already exists". */
public class CaseOnlyRenameTest {

  @Test
  public void caseOnlyChange_isDetected() {
    assertTrue(SmbRepositoryImpl.isCaseOnlyRename("Case/Photo.JPG", "Case\\photo.jpg"));
    assertTrue(SmbRepositoryImpl.isCaseOnlyRename("Docs", "DOCS"));
  }

  @Test
  public void otherRenames_areNotCaseOnly() {
    assertFalse(SmbRepositoryImpl.isCaseOnlyRename("Case/Photo.JPG", "Case\\Photo.JPG"));
    assertFalse(SmbRepositoryImpl.isCaseOnlyRename("Case/Photo.JPG", "Case\\photo2.jpg"));
  }
}
