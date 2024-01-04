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
import static io.confluent.rest.TestUtils.getFreePort;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.NetworkTrafficServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

@Tag("IntegrationTest")
public class RateLimitNetworkTrafficListenerTest {

  private static final String TEST_MESSAGE = "Test message";
  private static ApplicationServer<TestRestConfig> server;

  private String internEndpoint;
  private String externEndpoint;
  private ScheduledExecutorService executor;
  private Client client;

  @BeforeEach
  public void setup(TestInfo testInfo) throws Exception {
    Properties props = new Properties();
    // choosing listener name to avoid clashing with RequestLogHandlerIntegrationTest
    props.put(RestConfig.LISTENERS_CONFIG, "intern://localhost:" + getFreePort() + ","
        + "extern://localhost:" + getFreePort());
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "intern:http,extern:http");

    server = new ApplicationServer<>(new TestRestConfig(props));
    TestApp externalApp = createApp("extern", testInfo);
    server.registerApplication(externalApp);
    server.registerApplication(createApp("intern", testInfo));
    server.start();

    for (Connector connector : server.getConnectors()) {
      if (connector.getName().equals("intern")) {
        internEndpoint =
            "http://localhost:" + ((NetworkTrafficServerConnector) connector).getLocalPort();
      } else if (connector.getName().equals("extern")) {
        externEndpoint =
            "http://localhost:" + ((NetworkTrafficServerConnector) connector).getLocalPort();
      }
    }

    executor = Executors.newScheduledThreadPool(4);
    client = ClientBuilder.newClient();
  }

  private TestApp createApp(String name, TestInfo testInfo) {
    Properties props = new Properties();
    if (name.equals("extern") && // only set network traffic limit on external app
        testInfo.getDisplayName().contains("NetworkTrafficRateLimitEnabled")) {
      props.put(RestConfig.NETWORK_TRAFFIC_RATE_LIMIT_ENABLE_CONFIG, "true");
      props.put(RestConfig.NETWORK_TRAFFIC_RATE_LIMIT_BYTES_PER_SEC_CONFIG, 10000);
      if (testInfo.getDisplayName().contains("Resilience4j")) {
        props.put(RestConfig.NETWORK_TRAFFIC_RATE_LIMIT_BACKEND_CONFIG, "Resilience4j");
      }
    }
    TestRestConfig config = new TestRestConfig(props);
    return new TestApp(config, name);
  }

  @AfterEach
  public void tearDown() throws Exception {
    server.stop();
    server.join();

    client.close();
    awaitTerminationAfterShutdown(executor);
  }

  /**
   * All the tests below run with two rest applications "external" & "internal". This is intentional
   * to check the behavior that both apps are independently rate-limited.
   * In the setup below:
   * 1. "external" app's network rate-limits are enabled, disabled, and changed.
   * 2. "internal" app's network rate-limit is disabled.
   */
  @Test
  @DisplayName("NetworkTrafficRateLimitDisabled")
  public void testNetworkTrafficRateLimitDisabled_unlimited() throws Exception {
    { // validate for external endpoint
      long startTime = System.nanoTime();
      // send 1000 POST requests in 1 second
      int response200s = hammerAtConstantRate(URI.create(externEndpoint), "/resource",
          Duration.ofMillis(1), 10,
          1000);
      assertEquals(1000 - 10, response200s);
      double durationMillis = (System.nanoTime() - startTime) / 1_000_000.0;
      // with no rate limit, 1000 requests should finish less than 2 seconds
      assertThat("Duration must be greater than 1 second", durationMillis >= 1000);
      assertThat("Duration must be smaller than 2 seconds", durationMillis < 2000);
    }

    { // validate for internal endpoint
      long startTime = System.nanoTime();
      // send 1000 POST requests in 1 second
      int response200s = hammerAtConstantRate(URI.create(internEndpoint), "/resource",
          Duration.ofMillis(1), 10,
          1000);
      assertEquals(1000 - 10, response200s);
      double durationMillis = (System.nanoTime() - startTime) / 1_000_000.0;
      // with no rate limit, 1000 requests should finish less than 2 seconds
      assertThat("Duration must be greater than 1 second", durationMillis >= 1000);
      assertThat("Duration must be smaller than 2 seconds", durationMillis < 2000);
    }
  }

  @Test
  @DisplayName("NetworkTrafficRateLimitEnabled")
  public void testNetworkTrafficRateLimitEnabled_Guava_slowdown() throws Exception {
    { // validate for external endpoint
      long startTime = System.nanoTime();
      // send 1000 POST requests in 1 second
      int response200s = hammerAtConstantRate(URI.create(externEndpoint), "/resource",
          Duration.ofMillis(1), 10,
          1000);
      assertEquals(1000 - 10, response200s);
      double durationMillis = (System.nanoTime() - startTime) / 1_000_000.0;
      // with rate limiting, 1000 requests should finish in more than 10 seconds
      assertThat("Duration must be greater than 10 seconds",
          durationMillis >= Duration.ofSeconds(10).toMillis());
    }

    { // validate for internal endpoint
      long startTime = System.nanoTime();
      // send 1000 POST requests in 1 second
      int response200s = hammerAtConstantRate(URI.create(internEndpoint), "/resource",
          Duration.ofMillis(1), 10,
          1000);
      assertEquals(1000 - 10, response200s);
      double durationMillis = (System.nanoTime() - startTime) / 1_000_000.0;
      // with no rate limit, 1000 requests should finish less than 2 seconds
      assertThat("Duration must be greater than 1 second", durationMillis >= 1000);
      assertThat("Duration must be smaller than 2 seconds", durationMillis < 2000);
    }
  }

  @Test
  @DisplayName("NetworkTrafficRateLimitEnabled_Resilience4j")
  public void testNetworkTrafficRateLimitEnabled_Resilience4j_slowdown() throws Exception {
    { // validate for external endpoint
      long startTime = System.nanoTime();
      // send 1000 POST requests in 1 second
      int response200s = hammerAtConstantRate(URI.create(externEndpoint), "/resource",
          Duration.ofMillis(1), 10,
          1000);
      assertEquals(1000 - 10, response200s);
      double durationMillis = (System.nanoTime() - startTime) / 1_000_000.0;
      // with rate limiting, 1000 requests should finish in more than 10 seconds
      assertThat("Duration must be greater than 10 seconds",
          durationMillis >= Duration.ofSeconds(10).toMillis());
    }

    { // validate for internal endpoint
      long startTime = System.nanoTime();
      // send 1000 POST requests in 1 second
      int response200s = hammerAtConstantRate(URI.create(internEndpoint), "/resource",
          Duration.ofMillis(1), 10,
          1000);
      assertEquals(1000 - 10, response200s);
      double durationMillis = (System.nanoTime() - startTime) / 1_000_000.0;
      // with no rate limit, 1000 requests should finish less than 2 seconds
      assertThat("Duration must be greater than 1 second", durationMillis >= 1000);
      assertThat("Duration must be smaller than 2 seconds", durationMillis < 2000);
    }
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

    TestApp(TestRestConfig config, String listenerName) {
      super(config, "/", listenerName);
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
