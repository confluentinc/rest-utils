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

import static com.google.common.base.Preconditions.checkArgument;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

public class RateLimitNetworkTrafficListenerTest {

  private static TestRestConfig testConfig;
  private static ApplicationServer<TestRestConfig> server;
  private static final String TEST_MESSAGE = "Test message";

  private ScheduledExecutorService executor;
  private TestApp app;
  private Client client;

  @BeforeEach
  public void setup(TestInfo info) throws Exception {
    Properties props = new Properties();
    props.setProperty(RestConfig.LISTENERS_CONFIG, "http://0.0.0.0:0");

    if (info.getDisplayName().contains("NetworkTrafficRateLimitEnabled")) {
      props.put(RestConfig.NETWORK_TRAFFIC_RATE_LIMIT_ENABLE_CONFIG, "true");
      props.put(RestConfig.NETWORK_TRAFFIC_RATE_LIMIT_BYTES_PER_SEC_CONFIG, 10000);
      if (info.getDisplayName().contains("Resilience4j")) {
        props.put(RestConfig.NETWORK_TRAFFIC_RATE_LIMIT_BACKEND_CONFIG, "Resilience4j");
      }
    }

    testConfig = new TestRestConfig(props);
    server = new ApplicationServer<>(testConfig);
    app = new TestApp("/app");
    server.registerApplication(app);
    server.start();

    executor = Executors.newScheduledThreadPool(4);
    client = ClientBuilder.newClient();
  }

  @AfterEach
  public void tearDown() throws Exception {
    server.stop();
    server.join();

    client.close();
    awaitTerminationAfterShutdown(executor);
  }

  @Test
  @DisplayName("NetworkTrafficRateLimitDisabled")
  public void testNetworkTrafficRateLimitDisabled_unlimited() throws Exception {
    long startTime = System.nanoTime();
    // send 1000 POST requests in 1 second
    hammerAtConstantRate(app.getServer().getURI(), "/resource", Duration.ofMillis(1), 10, 1000);
    double durationMillis = (System.nanoTime() - startTime) / 1_000_000.0;
    // with no rate limit, 1000 requests should finish less than 2 seconds
    assertThat("Duration must be greater than 1 second", durationMillis >= 1000);
    assertThat("Duration must be smaller than 2 seconds", durationMillis < 2000);
  }

  @Test
  @DisplayName("NetworkTrafficRateLimitEnabled")
  public void testNetworkTrafficRateLimitEnabled_Guava_slowdown() throws Exception {
    long startTime = System.nanoTime();
    // send 1000 POST requests in 1 second
    hammerAtConstantRate(app.getServer().getURI(), "/resource", Duration.ofMillis(1), 10, 1000);
    double durationMillis = (System.nanoTime() - startTime) / 1_000_000.0;
    // with rate limiting, 1000 requests should finish in more than 2 seconds
    assertThat("Duration must be greater than 10 seconds",
        durationMillis >= Duration.ofSeconds(10).toMillis());
  }

  @Test
  @DisplayName("NetworkTrafficRateLimitEnabled_Resilience4j")
  public void testNetworkTrafficRateLimitEnabled_Resilience4j_slowdown() throws Exception {
    long startTime = System.nanoTime();
    // send 1000 POST requests in 1 second
    hammerAtConstantRate(app.getServer().getURI(), "/resource", Duration.ofMillis(1), 10, 1000);
    double durationMillis = (System.nanoTime() - startTime) / 1_000_000.0;
    // with rate limiting, 1000 requests should finish in more than 2 seconds
    assertThat("Duration must be greater than 10 seconds",
        durationMillis >= Duration.ofSeconds(10).toMillis());
  }

  // Send many concurrent requests and return the number of requests with "200" status
  private int hammerAtConstantRate(URI server,
      String path, Duration rate, int warmupRequests, int totalRequests) {
    checkArgument(!rate.isNegative(), "rate must be non-negative");
    checkArgument(warmupRequests <= totalRequests, "warmupRequests must be at most totalRequests");

    List<Response> responses =
        IntStream.range(0, totalRequests)
            .mapToObj(
                i ->
                    executor.schedule(
                        () -> client.target(server)
                            .path(path)
                            .request(MediaType.APPLICATION_FORM_URLENCODED_TYPE)
                            .post(Entity.form(new Form("message", TEST_MESSAGE))),
                        /* delay= */ i * rate.toMillis(),
                        TimeUnit.MILLISECONDS))
            .collect(Collectors.toList()).stream()
            .map(
                future -> {
                  try {
                    return future.get();
                  } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException(e);
                  }
                })
            .collect(Collectors.toList());

    for (Response response : responses) {
      int status = response.getStatus();
      if (status != 200 && status != 429) {
        fail(
            String.format(
                "Expected HTTP 200 or HTTP 429, but got HTTP %d instead: %s",
                status, response.readEntity(String.class)));
      }
    }

    return (int)
        responses.subList(warmupRequests, responses.size()).stream()
            .filter(response -> response.getStatus() == Status.OK.getStatusCode())
            .count();
  }

  private void awaitTerminationAfterShutdown(ExecutorService threadPool) {
    threadPool.shutdown();
    try {
      if (!threadPool.awaitTermination(60, TimeUnit.SECONDS)) {
        threadPool.shutdownNow();
      }
    } catch (InterruptedException ex) {
      threadPool.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  private static class TestApp extends Application<TestRestConfig> implements AutoCloseable {

    TestApp(String path) {
      this(testConfig, path);
    }

    TestApp(TestRestConfig config, String path) {
      super(config, path);
    }

    @Override
    public void setupResources(final Configurable<?> config, final TestRestConfig appConfig) {
      config.register(RestResource.class);
    }

    @Override
    public void close() throws Exception {
      stop();
    }
  }

  @Path("/")
  @Produces(MediaType.TEXT_PLAIN)
  public static class RestResource {

    @POST
    @Path("/resource")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.WILDCARD)
    public String post() {
      return "Hello";
    }
  }
}
