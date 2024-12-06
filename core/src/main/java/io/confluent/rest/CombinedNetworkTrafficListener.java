/*
 * Copyright 2024 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.confluent.rest;

import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.List;

import org.eclipse.jetty.io.NetworkTrafficListener;

/**
 * With Jetty 11+, the NetworkTrafficServerConnector has been updated to only accept one
 * NetworkTrafficListener per instance. This class collects a list of existing listeners
 * into one class to be used as a single NetworkTrafficListener.
 */
public class CombinedNetworkTrafficListener implements NetworkTrafficListener {

  private final List<NetworkTrafficListener> delegates;

  public CombinedNetworkTrafficListener(List<NetworkTrafficListener> listeners) {
    this.delegates = listeners;
  }

  @Override
  public void opened(Socket socket) {
    delegates.forEach(listener -> listener.opened(socket));
  }

  @Override
  public void incoming(Socket socket, ByteBuffer bytes) {
    delegates.forEach(listener -> listener.incoming(socket, bytes));
  }

  @Override
  public void outgoing(Socket socket, ByteBuffer bytes) {
    delegates.forEach(listener -> listener.outgoing(socket, bytes));
  }

  @Override
  public void closed(Socket socket) {
    delegates.forEach(listener -> listener.closed(socket));
  }
}
