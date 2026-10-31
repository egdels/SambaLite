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
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Editing a connection must keep its position in the connection list. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ConnectionRepositoryOrderTest {

  private SmbConnection connection(String id, String name) {
    SmbConnection connection = new SmbConnection();
    connection.setId(id);
    connection.setName(name);
    connection.setServer("192.168.1.10");
    connection.setShare("share");
    connection.setUsername("user");
    connection.setPassword("secret");
    connection.setDomain("");
    return connection;
  }

  private List<String> names(ConnectionRepositoryImpl repository) {
    return repository.getAllConnections().stream()
        .map(SmbConnection::getName)
        .collect(Collectors.toList());
  }

  @Test
  public void editedConnection_keepsItsPosition() {
    Context context = ApplicationProvider.getApplicationContext();
    ConnectionRepositoryImpl repository = new ConnectionRepositoryImpl(context);
    repository.saveConnection(connection("a", "A"));
    repository.saveConnection(connection("b", "B"));
    repository.saveConnection(connection("c", "C"));

    repository.saveConnection(connection("a", "A edited"));

    assertEquals(List.of("A edited", "B", "C"), names(new ConnectionRepositoryImpl(context)));
  }

  @Test
  public void newConnection_isAddedAtTheEnd() {
    Context context = ApplicationProvider.getApplicationContext();
    ConnectionRepositoryImpl repository = new ConnectionRepositoryImpl(context);
    repository.saveConnection(connection("a", "A"));
    repository.saveConnection(connection(null, "New"));

    assertEquals(List.of("A", "New"), names(repository));
  }
}
