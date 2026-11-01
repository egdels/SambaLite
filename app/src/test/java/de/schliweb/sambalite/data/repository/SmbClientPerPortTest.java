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

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;

import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import org.junit.Test;

/**
 * SMBJ keeps one server per host name in a client, so servers on the same host but different ports
 * must not share a client ("Different server found for same hostname").
 */
public class SmbClientPerPortTest {

  private final SmbRepositoryImpl repository =
      new SmbRepositoryImpl(mock(BackgroundSmbManager.class));

  @Test
  public void standardPort_usesTheMainClient() {
    assertSame(repository.defaultClientForPort(445), repository.defaultClientForPort(0));
  }

  @Test
  public void otherPort_getsItsOwnClient_reusedForThatPort() {
    assertNotSame(repository.defaultClientForPort(445), repository.defaultClientForPort(4450));
    assertSame(repository.defaultClientForPort(4450), repository.defaultClientForPort(4450));
    assertNotSame(repository.defaultClientForPort(4450), repository.defaultClientForPort(1445));
  }
}
