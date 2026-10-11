/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.test.helper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A local TCP proxy that delays every chunk by a fixed one-way latency in both directions, so a
 * test against a Docker container on localhost behaves like a client on Wi-Fi. Bandwidth is not
 * limited; only round trips get expensive, which is what makes chatty protocols slow.
 */
public final class LatencyProxy implements AutoCloseable {

  private final ServerSocket server;
  private final String targetHost;
  private final int targetPort;
  private final long oneWayDelayMs;
  private volatile boolean closed;

  public LatencyProxy(String targetHost, int targetPort, long oneWayDelayMs) throws IOException {
    this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    this.targetHost = targetHost;
    this.targetPort = targetPort;
    this.oneWayDelayMs = oneWayDelayMs;
    Thread acceptor = new Thread(this::acceptLoop, "latency-proxy-accept");
    acceptor.setDaemon(true);
    acceptor.start();
  }

  public String getHost() {
    return server.getInetAddress().getHostAddress();
  }

  public int getPort() {
    return server.getLocalPort();
  }

  private void acceptLoop() {
    while (!closed) {
      try {
        Socket client = server.accept();
        Socket upstream = new Socket(targetHost, targetPort);
        client.setTcpNoDelay(true);
        upstream.setTcpNoDelay(true);
        pipe(client, upstream);
        pipe(upstream, client);
      } catch (IOException e) {
        if (!closed) e.printStackTrace();
      }
    }
  }

  /** Forwards {@code from} to {@code to}, releasing each chunk after the configured delay. */
  private void pipe(Socket from, Socket to) {
    BlockingQueue<Object[]> queue = new LinkedBlockingQueue<>();
    Thread reader =
        new Thread(
            () -> {
              byte[] buf = new byte[64 * 1024];
              try (InputStream in = from.getInputStream()) {
                int n;
                while ((n = in.read(buf)) >= 0) {
                  long due = System.nanoTime() + oneWayDelayMs * 1_000_000L;
                  queue.put(new Object[] {due, Arrays.copyOf(buf, n)});
                }
              } catch (IOException | InterruptedException ignored) {
              } finally {
                queue.offer(new Object[] {0L, null});
              }
            },
            "latency-proxy-read");
    Thread writer =
        new Thread(
            () -> {
              try (OutputStream out = to.getOutputStream()) {
                while (true) {
                  Object[] item = queue.take();
                  byte[] data = (byte[]) item[1];
                  if (data == null) break;
                  long wait = (long) item[0] - System.nanoTime();
                  if (wait > 0) Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                  out.write(data);
                  out.flush();
                }
              } catch (IOException | InterruptedException ignored) {
              } finally {
                closeQuietly(from);
                closeQuietly(to);
              }
            },
            "latency-proxy-write");
    reader.setDaemon(true);
    writer.setDaemon(true);
    reader.start();
    writer.start();
  }

  private static void closeQuietly(Socket s) {
    try {
      s.close();
    } catch (IOException ignored) {
    }
  }

  @Override
  public void close() throws IOException {
    closed = true;
    server.close();
  }
}
