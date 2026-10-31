/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.schliweb.sambalite.data.repository.ConnectionRepository;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.sync.SyncManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Deleting a connection also removes its sync configs. The main screen must reload them, otherwise
 * the sync card of the deleted connection stays visible until the next app start.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainViewModelDeleteConnectionTest {

  @Test
  public void deleteConnection_reloadsSyncConfigs() {
    ConnectionRepository connections = mock(ConnectionRepository.class);
    SyncManager syncManager = mock(SyncManager.class);
    MainViewModel viewModel =
        new MainViewModel(connections, mock(SmbRepository.class), syncManager);
    // The constructor loads the sync configs once
    verify(syncManager, timeout(2000).times(1)).getAllSyncConfigs();

    viewModel.deleteConnection("conn-1");

    verify(connections, timeout(2000)).deleteConnection("conn-1");
    verify(syncManager, timeout(2000)).removeConfigsForConnection("conn-1");
    verify(syncManager, timeout(2000).times(2)).getAllSyncConfigs();
  }
}
