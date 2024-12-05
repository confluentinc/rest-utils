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
import org.eclipse.jetty.io.NetworkTrafficListener;

/**
 * With Jetty 11+, the NetworkTrafficServerConnector has been updated to only accept one
 * NetworkTrafficListener per instance. This class merges the MetricsListener and
 * RateLimitNetworkTrafficListener into one class to be used as a single NetworkTrafficListener
 */
public class RateLimitMetricsListener implements NetworkTrafficListener {

  private final MetricsListener metricsListener;
  private final RateLimitNetworkTrafficListener rateLimitNetworkTrafficListener;

  public RateLimitMetricsListener(
      MetricsListener metricsListener,
      RateLimitNetworkTrafficListener rateLimitNetworkTrafficListener) {
    this.metricsListener = metricsListener;
    this.rateLimitNetworkTrafficListener = rateLimitNetworkTrafficListener;
  }

  @Override
  public void opened(Socket socket) {
    metricsListener.opened(socket);
    rateLimitNetworkTrafficListener.opened(socket);
  }

  @Override
  public void incoming(Socket socket, ByteBuffer bytes) {
    metricsListener.incoming(socket, bytes);
    rateLimitNetworkTrafficListener.incoming(socket, bytes);
  }

  @Override
  public void outgoing(Socket socket, ByteBuffer bytes) {
    metricsListener.outgoing(socket, bytes);
    rateLimitNetworkTrafficListener.outgoing(socket, bytes);
  }

  @Override
  public void closed(Socket socket) {
    metricsListener.closed(socket);
    rateLimitNetworkTrafficListener.closed(socket);
  }
}
