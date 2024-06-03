/*
 * Copyright 2014 - 2023 Confluent Inc.
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

package io.confluent.rest.metrics;

import static com.google.common.base.Preconditions.checkArgument;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.confluent.rest.Application;
import io.confluent.rest.RestConfig;
import io.confluent.rest.TestMetricsReporter;
import io.confluent.rest.TestRestConfig;
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
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import org.apache.kafka.common.metrics.KafkaMetric;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;

@Tag("IntegrationTest")
class JettyRequestMetricsFilterIntegrationTest {

  private ScheduledExecutorService executor;
  private Server server;
  private Client client;

  @BeforeEach
  public void setUp() throws Exception {
    TestMetricsReporter.reset();

    Properties props = new Properties();
    props.setProperty("debug", "false");
    props.put(RestConfig.METRICS_REPORTER_CLASSES_CONFIG, "io.confluent.rest.TestMetricsReporter");

    TestRestConfig config = new TestRestConfig(props);
    TestApplication app = new TestApplication(config);
    app.createServer();
    server = app.createServer();
    server.start();

    executor = Executors.newScheduledThreadPool(4);
    client = ClientBuilder.newClient(app.resourceConfig.getConfiguration());
  }

  @AfterEach
  public void tearDown() throws Exception {
    server.stop();
    server.join();

    client.close();
    awaitTerminationAfterShutdown(executor);
  }

  @RepeatedTest(value = 5)
  public void test_doFilter_requestCount() {
    // send 20 requests, 10 are warmup and not counted, in theory, all the requests are accepted
    final int warmupRequests = 10;
    final int totalRequests = 20;

    int response200s = hammerAtConstantRate(server.getURI(),
        "/public/hello", Duration.ofMillis(1),
        warmupRequests, totalRequests
    );

    // check for 200s
    assertEquals(totalRequests - warmupRequests, response200s);
    for (KafkaMetric metric : TestMetricsReporter.getMetricTimeseries()) {
      if (metric.metricName().name().equals("request-count")
          && metric.metricName().group().equals("jetty-metrics")) {
        assertTrue(metric.measurable().toString().toLowerCase().startsWith("sampledstat"));
        Object metricValue = metric.metricValue();
        assertTrue(metricValue instanceof Double, "Request count metrics should be measurable");
        double count = (double) metricValue;
        assertEquals(totalRequests, count, "Actual: " + count);
      }

      if (metric.metricName().name().equals("request-total")
          && metric.metricName().group().equals("jetty-metrics")) {
        assertTrue(metric.measurable().toString().toLowerCase().startsWith("cumulativesum"));
        Object metricValue = metric.metricValue();
        assertTrue(metricValue instanceof Double, "Request total metrics should be measurable");
        double total = (double) metricValue;
        assertEquals(totalRequests, total, "Actual: " + total);
      }

      if (metric.metricName().name().equals("request-rate")
          && metric.metricName().group().equals("jetty-metrics")) {
        assertTrue(metric.measurable().toString().toLowerCase().startsWith("rate"));
        Object metricValue = metric.metricValue();
        assertTrue(metricValue instanceof Double, "Request rate metrics should be measurable");
        double rate = (double) metricValue;
        assertEquals(
            // 30 seconds is the approximate window size for Rate that comes from the calculation of
            // org.apache.kafka.common.metrics.stats.Rate.windowSize
            Math.floor(
                (double) (totalRequests) / 30),
            Math.floor(rate), "Actual: " + rate);
      }
    }
  }

  // Send many concurrent requests and return the number of requests with 200 status
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
                            .request(MediaType.APPLICATION_JSON_TYPE)
                            .get(),
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

  private static class TestApplication extends Application<TestRestConfig> {

    Configurable<?> resourceConfig;

    TestApplication(TestRestConfig props) {
      super(props);
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      resourceConfig = config;
      config.register(PublicResource.class);
    }
  }

  @Produces(MediaType.APPLICATION_JSON)
  @Path("/public/")
  public static class PublicResource {

    @GET
    @Path("/hello")
    public String hello() {
      return "hello";
    }
  }

}
