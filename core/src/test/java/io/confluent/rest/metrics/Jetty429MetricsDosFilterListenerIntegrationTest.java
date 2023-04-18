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
import org.glassfish.jersey.server.ServerProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("IntegrationTest")
class Jetty429MetricsDosFilterListenerIntegrationTest {

  private static final int DOS_FILTER_MAX_REQUESTS_PER_CONNECTION_PER_SEC = 25;

  private ScheduledExecutorService executor;
  private Server server;
  private Client client;

  @BeforeEach
  public void setUp() throws Exception {
    TestMetricsReporter.reset();

    Properties props = new Properties();
    props.setProperty("debug", "false");
    props.put(RestConfig.METRICS_REPORTER_CLASSES_CONFIG, "io.confluent.rest.TestMetricsReporter");
    // enabled dos filters
    props.put("dos.filter.enabled", true);
    props.put("dos.filter.delay.ms", -1L); // to reject request, i.e 429
    props.put("dos.filter.max.requests.per.connection.per.sec",
        DOS_FILTER_MAX_REQUESTS_PER_CONNECTION_PER_SEC); // local dos filter
    // NOTE: we can't test both global and local dos filter in integration tests because
    // all requests are sent from only one client ip, the only dos filter in effect in the tests
    // is local dos filer
    props.put("dos.filter.max.requests.per.sec", 100); // global dos filter

    TestRestConfig config = new TestRestConfig(props);
    ApplicationWithDoSFilterEnabled app = new ApplicationWithDoSFilterEnabled(config);
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

  @Test
  public void testDosFilterRateLimitMetrics() {
    final int warmupRequests = 20;
    final int totalRequests = 100;

    int response200s = hammerAtConstantRate(server.getURI(),
        "/public/hello", Duration.ofMillis(1),
        warmupRequests, totalRequests
    );

    // Check 429 metrics
    for (KafkaMetric metric : TestMetricsReporter.getMetricTimeseries()) {
      if (metric.metricName().name().equals("request-error-count")
          && metric.metricName().group().equals("jetty-metrics")
          && metric.metricName().tags()
          .getOrDefault("http_status_code", "").equals("429")) {
        assertTrue(metric.measurable().toString().toLowerCase().startsWith("sampledstat"));
        Object metricValue = metric.metricValue();
        assertTrue(metricValue instanceof Double, "Error count metrics should be measurable");
        double errorCountValue = (double) metricValue;
        assertEquals(DOS_FILTER_MAX_REQUESTS_PER_CONNECTION_PER_SEC - warmupRequests, response200s);
        assertEquals(totalRequests - DOS_FILTER_MAX_REQUESTS_PER_CONNECTION_PER_SEC,
            errorCountValue, "Actual: " + errorCountValue);
      }

      if (metric.metricName().name().equals("request-error-total")
          && metric.metricName().group().equals("jetty-metrics")
          && metric.metricName().tags()
          .getOrDefault("http_status_code", "").equals("429")) {
        assertTrue(metric.measurable().toString().toLowerCase().startsWith("cumulativesum"));
        Object metricValue = metric.metricValue();
        assertTrue(metricValue instanceof Double, "Error total metrics should be measurable");
        double errorTotalValue = (double) metricValue;
        assertEquals(DOS_FILTER_MAX_REQUESTS_PER_CONNECTION_PER_SEC - warmupRequests, response200s);
        assertEquals(totalRequests - DOS_FILTER_MAX_REQUESTS_PER_CONNECTION_PER_SEC,
            errorTotalValue, "Actual: " + errorTotalValue);
      }

      if (metric.metricName().name().equals("request-error-rate")
          && metric.metricName().group().equals("jetty-metrics")
          && metric.metricName().tags()
          .getOrDefault("http_status_code", "").equals("429")) {
        assertTrue(metric.measurable().toString().toLowerCase().startsWith("rate"));
        Object metricValue = metric.metricValue();
        assertTrue(metricValue instanceof Double, "Error rate metrics should be measurable");
        double errorRateValue = (double) metricValue;
        assertEquals(DOS_FILTER_MAX_REQUESTS_PER_CONNECTION_PER_SEC - warmupRequests, response200s);
        assertEquals(
            // 30 seconds is the approximate window size for Rate that comes from the calculation of
            // org.apache.kafka.common.metrics.stats.Rate.windowSize
            Math.floor(
                (double) (totalRequests - DOS_FILTER_MAX_REQUESTS_PER_CONNECTION_PER_SEC) / 30),
            Math.floor(errorRateValue), "Actual: " + errorRateValue);
      }
    }
  }

  // Send many concurrent requests and return the number of request with 200 status
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

  /**
   * Test application with DoSFilter enabled to test 429 metrics
   */
  private static class ApplicationWithDoSFilterEnabled extends Application<TestRestConfig> {

    Configurable<?> resourceConfig;

    ApplicationWithDoSFilterEnabled(TestRestConfig props) {
      super(props);
    }


    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      resourceConfig = config;
      config.register(PublicResource.class);

      // ensures the dispatch error message gets shown in the response
      // as opposed to a generic error page
      config.property(ServerProperties.RESPONSE_SET_STATUS_OVER_SEND_ERROR, true);
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
