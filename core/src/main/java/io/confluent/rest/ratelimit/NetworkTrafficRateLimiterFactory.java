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

package io.confluent.rest.ratelimit;

import com.google.common.util.concurrent.RateLimiter;
import io.confluent.rest.RestConfig;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import java.time.Duration;

public final class NetworkTrafficRateLimiterFactory {

  private NetworkTrafficRateLimiterFactory() {
    // prevent instantiation
  }

  public static NetworkTrafficRateLimiter create(RestConfig restConfig) {
    int bytesPerSecond = restConfig.getNetworkTrafficRateLimitBytesPerSec();
    switch (restConfig.getNetworkTrafficRateLimitBackend()) {
      case GUAVA:
        return GuavaNetworkTrafficRateLimiter.create(bytesPerSecond);
      case RESILIENCE4J:
        return Resilience4JNetworkTrafficRateLimiter.create(bytesPerSecond);
      default:
        throw new AssertionError("Unknown enum constant: "
            + restConfig.getNetworkTrafficRateLimitBackend());
    }
  }

  public abstract static class NetworkTrafficRateLimiter {

    public abstract void rateLimit(int cost);
  }

  static final class GuavaNetworkTrafficRateLimiter extends NetworkTrafficRateLimiter {

    private final RateLimiter delegate;

    GuavaNetworkTrafficRateLimiter(RateLimiter delegate) {
      this.delegate = delegate;
    }

    static GuavaNetworkTrafficRateLimiter create(int bytesPerSecond) {
      return new GuavaNetworkTrafficRateLimiter(RateLimiter.create(bytesPerSecond));
    }

    @Override
    public void rateLimit(final int cost) {
      delegate.acquire(cost);
    }
  }

  static final class Resilience4JNetworkTrafficRateLimiter extends NetworkTrafficRateLimiter {

    private final io.github.resilience4j.ratelimiter.RateLimiter delegate;

    Resilience4JNetworkTrafficRateLimiter(io.github.resilience4j.ratelimiter.RateLimiter delegate) {
      this.delegate = delegate;
    }

    static Resilience4JNetworkTrafficRateLimiter create(int bytesPerSecond) {
      RateLimiterConfig config =
          RateLimiterConfig.custom()
              .limitRefreshPeriod(Duration.ofSeconds(1))
              .limitForPeriod(bytesPerSecond)
              .build();
      return new Resilience4JNetworkTrafficRateLimiter(
          io.github.resilience4j.ratelimiter.RateLimiter.of(
              "Resilience4JNetworkTrafficRateLimiter", config)
      );
    }

    @Override
    public void rateLimit(final int cost) {
      delegate.acquirePermission(cost);
    }
  }

}
