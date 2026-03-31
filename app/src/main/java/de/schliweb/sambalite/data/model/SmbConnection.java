/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.data.model;

import java.io.Serializable;
import lombok.Getter;
import lombok.Setter;

/** Model class representing an SMB connection. */
@Setter
@Getter
public class SmbConnection implements Serializable {

  private static final long serialVersionUID = 1L;

  private String id;
  private String name;
  private String server;
  private String share;
  private String username;
  private String password;
  private String domain;

  // Security settings (per connection)
  // If true, require SMB3 encryption for data
  private boolean encryptData = false;
  // If true, require SMB signing
  private boolean signingRequired = false;
  // If true, use async transport for improved transfer performance
  private boolean asyncTransport = false;

  /** Default constructor for SmbConnection. */
  public SmbConnection() {}

  @Override
  public String toString() {
    return "SmbConnection{"
        + "id='"
        + id
        + '\''
        + ", name='"
        + name
        + '\''
        + ", server='"
        + server
        + '\''
        + ", share='"
        + share
        + '\''
        + ", username='"
        + username
        + '\''
        + ", password='********'"
        + ", domain='"
        + domain
        + '\''
        + ", encryptData="
        + encryptData
        + ", signingRequired="
        + signingRequired
        + ", asyncTransport="
        + asyncTransport
        + '}';
  }
}
