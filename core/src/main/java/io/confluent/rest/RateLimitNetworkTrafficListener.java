/*
 * Copyright 2023 Confluent Inc.
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

import io.confluent.rest.ratelimit.NetworkTrafficRateLimiter;
import io.confluent.rest.ratelimit.NetworkTrafficRateLimiterFactory;
import java.net.Socket;
import java.nio.ByteBuffer;
import org.eclipse.jetty.io.NetworkTrafficListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is used as a way to apply back-pressure on all the network traffic sockets when
 * incoming rate exceeds a threshold
 */
public class RateLimitNetworkTrafficListener implements NetworkTrafficListener {

  private static final Logger log = LoggerFactory.getLogger(RateLimitNetworkTrafficListener.class);

  private final NetworkTrafficRateLimiter rateLimiter;

  public RateLimitNetworkTrafficListener(RestConfig restConfig) {
    rateLimiter = NetworkTrafficRateLimiterFactory.create(restConfig);
  }

  @Override
  public void incoming(final Socket socket, final ByteBuffer bytes) {
    int cost = bytes.limit() - bytes.position();
    if (cost > 0) {
      log.debug("Applying network traffic rate limit on socket: {} with cost: {}", socket, cost);
      rateLimiter.rateLimit(cost);
    }
  }
}
