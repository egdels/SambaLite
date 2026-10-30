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

import static org.junit.Assert.assertEquals;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import de.schliweb.sambalite.data.model.SmbConnection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Issue #46: the SMB port of a connection must survive saving and loading. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ConnectionRepositoryPortTest {

  private SmbConnection connection(String id) {
    SmbConnection connection = new SmbConnection();
    connection.setId(id);
    connection.setName("Connection " + id);
    connection.setServer("192.168.1.10");
    connection.setShare("share");
    connection.setUsername("user");
    connection.setPassword("secret");
    connection.setDomain("");
    return connection;
  }

  private SmbConnection load(ConnectionRepositoryImpl repository, String id) {
    return repository.getAllConnections().stream()
        .filter(c -> id.equals(c.getId()))
        .findFirst()
        .orElseThrow();
  }

  @Test
  public void customPort_isPersisted() {
    Context context = ApplicationProvider.getApplicationContext();
    ConnectionRepositoryImpl repository = new ConnectionRepositoryImpl(context);
    SmbConnection connection = connection("custom");
    connection.setPort(4455);
    repository.saveConnection(connection);

    assertEquals(4455, load(new ConnectionRepositoryImpl(context), "custom").getPort());
  }

  @Test
  public void defaultPort_isKeptWhenNotSet() {
    Context context = ApplicationProvider.getApplicationContext();
    ConnectionRepositoryImpl repository = new ConnectionRepositoryImpl(context);
    repository.saveConnection(connection("default"));

    assertEquals(
        SmbConnection.DEFAULT_PORT, load(new ConnectionRepositoryImpl(context), "default").getPort());
  }
}
