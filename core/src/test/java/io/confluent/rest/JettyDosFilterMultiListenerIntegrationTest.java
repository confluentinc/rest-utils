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

package io.confluent.rest;

import static com.google.common.base.Preconditions.checkArgument;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.RepeatedTest.LONG_DISPLAY_NAME;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.servlet.http.HttpServletRequest;
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
import org.eclipse.jetty.servlets.DoSFilter;
import org.eclipse.jetty.servlets.DoSFilter.Action;
import org.eclipse.jetty.servlets.DoSFilter.Listener;
import org.eclipse.jetty.servlets.DoSFilter.OverLimit;
import org.glassfish.jersey.server.ServerProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInfo;

@Tag("IntegrationTest")
/**
 * This test makes sure when DosFilter rejects requests, then configured dosfilter-listeners
 * are run, including the mandatory Jetty429MetricsDosFilterListener.
 */
class JettyDosFilterMultiListenerIntegrationTest {

  private static final int DOS_FILTER_MAX_REQUESTS_PER_CONNECTION_PER_SEC = 25;

  private static final int DOS_FILTER_MAX_REQUESTS_PER_SEC = 25;
  private ScheduledExecutorService executor;
  private Server server;
  private Client client;
  private TestDosFilterListener nonGlobalDosFilterListener = new TestDosFilterListener();
  private TestDosFilterListener globalDosFilterListener = new TestDosFilterListener();

  @BeforeEach
  public void setUp(TestInfo testInfo) throws Exception {
    TestMetricsReporter.reset();
    nonGlobalDosFilterListener.rejectedCounter.set(0);
    globalDosFilterListener.rejectedCounter.set(0);

    Properties props = new Properties();
    props.setProperty("debug", "false");
    props.put(RestConfig.METRICS_REPORTER_CLASSES_CONFIG, "io.confluent.rest.TestMetricsReporter");
    // enabled dos filters
    props.put("dos.filter.enabled", true);
    props.put("dos.filter.delay.ms", -1L); // to reject request, i.e 429
    if (testInfo.getDisplayName().contains(
        "test_dosFilterMultiListener_withGlobalDosFilterRejecting_CheckRelevantListenersCalled")) {
      // Make sure global-dos-filter kicks in before non-global-dos-filter. So
      // set non-global/per-connection limit to be higher, 100, than the global limit, 25.
      // Set non-global limit.
      props.put("dos.filter.max.requests.per.connection.per.sec",
          100);
      // Set the global limit
      props.put("dos.filter.max.requests.per.sec",
          DOS_FILTER_MAX_REQUESTS_PER_SEC);
    } else {
      // Make sure non-global-dos-filter kicks in before the global-dos-filter. So
      // set non-global/per-connection limit to be lower, 25, than the global limit, 100.
      // Set non-global limit.
      props.put("dos.filter.max.requests.per.connection.per.sec",
          DOS_FILTER_MAX_REQUESTS_PER_CONNECTION_PER_SEC);
      // Set the global limit
      props.put("dos.filter.max.requests.per.sec",
          100);
    }

    TestRestConfig config = new TestRestConfig(props);
    ApplicationWithDoSFilterEnabled app = new ApplicationWithDoSFilterEnabled(config);
    app.addNonGlobalDosfilterListener(nonGlobalDosFilterListener);
    app.addGlobalDosfilterListener(globalDosFilterListener);
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

  @RepeatedTest(value = 5, name = LONG_DISPLAY_NAME)
  @DisplayName("test_dosFilterMultiListener_noRequestRejected_CheckNoListenerCalled")
  /**
   * This test will query such that no dos-filter kicks-in, so no requests rejected.
   * Check that no dos-filter-listener kicks-in.
   */
  public void test_dosFilterMultiListener_noRequestRejected_CheckNoListenerCalled() {
    // send 20 requests, 10 are warmup (not counted), in theory, all the requests are accepted
    final int warmupRequests = 10;
    final int totalRequests = 20;

    int response200s = hammerAtConstantRate(server.getURI(),
        "/public/hello", Duration.ofMillis(1),
        warmupRequests, totalRequests
    );

    // Verify Jetty429MetricsDosFilterListener wasn't called.
    // check for 200s
    assertEquals(totalRequests - warmupRequests, response200s);
    // Check 429 metrics, should be all 0 values
    for (KafkaMetric metric : TestMetricsReporter.getMetricTimeseries()) {
      if (metric.metricName().name().equals("request-error-count")
          && metric.metricName().group().equals("jetty-metrics")
          && metric.metricName().tags()
          .getOrDefault("http_status_code", "").equals("429")) {
        assertTrue(metric.measurable().toString().toLowerCase().startsWith("sampledstat"));
        Object metricValue = metric.metricValue();
        assertTrue(metricValue instanceof Double, "Error count metrics should be measurable");
        double errorCountValue = (double) metricValue;
        assertEquals(0,
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
        assertEquals(0, errorTotalValue, "Actual: " + errorTotalValue);
      }

      if (metric.metricName().name().equals("request-error-rate")
          && metric.metricName().group().equals("jetty-metrics")
          && metric.metricName().tags()
          .getOrDefault("http_status_code", "").equals("429")) {
        assertTrue(metric.measurable().toString().toLowerCase().startsWith("rate"));
        Object metricValue = metric.metricValue();
        assertTrue(metricValue instanceof Double, "Error rate metrics should be measurable");
        double errorRateValue = (double) metricValue;
        assertEquals(0.0, errorRateValue, "Actual: " + errorRateValue);
      }
    }

    // Verify that non-global-dos-filter-listener wasn't called.
    assertEquals(nonGlobalDosFilterListener.rejectedCounter.get(), 0);
    // Verify that global-dos-filter-listener wasn't called.
    assertEquals(globalDosFilterListener.rejectedCounter.get(), 0);
  }

  @RepeatedTest(value = 5, name = LONG_DISPLAY_NAME)
  @DisplayName("test_dosFilterMultiListener_withNonGlobalDosFilterRejecting_CheckRelevantListenersCalled")
  /**
   * This test will query such that non-global dos-filter kicks-in, so requests are rejected.
   * Check that non-global-dos-filter-listener and Jetty429MetricsDosFilterListener are called.
   */
  public void test_dosFilterMultiListener_withNonGlobalDosFilterRejecting_CheckRelevantListenersCalled() {
    // send 100 requests, in which 20 are warmup, in theory,
    // - the first 25 (including warmups) are accepted, so response200s=5
    // - the rest of 75 are rejected
    final int warmupRequests = 20;
    final int totalRequests = 100;

    int response200s = hammerAtConstantRate(server.getURI(),
        "/public/hello", Duration.ofMillis(1),
        warmupRequests, totalRequests
    );

    // Verify Jetty429MetricsDosFilterListener was called.
    // check for 200s
    assertEquals(DOS_FILTER_MAX_REQUESTS_PER_CONNECTION_PER_SEC - warmupRequests, response200s);
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
        assertEquals(
            // 30 seconds is the approximate window size for Rate that comes from the calculation of
            // org.apache.kafka.common.metrics.stats.Rate.windowSize
            Math.floor(
                (double) (totalRequests - DOS_FILTER_MAX_REQUESTS_PER_CONNECTION_PER_SEC) / 30),
            Math.floor(errorRateValue), "Actual: " + errorRateValue);
      }
    }

    // Verify that non-global-dos-filter-listener was called.
    assertEquals(nonGlobalDosFilterListener.rejectedCounter.get(),
        totalRequests - DOS_FILTER_MAX_REQUESTS_PER_CONNECTION_PER_SEC);
    // Verify that global-dos-filter-listener wasn't called.
    assertEquals(globalDosFilterListener.rejectedCounter.get(), 0);
  }

  @RepeatedTest(value = 5, name = LONG_DISPLAY_NAME)
  @DisplayName("test_dosFilterMultiListener_withGlobalDosFilterRejecting_CheckRelevantListenersCalled")
  /**
   * This test will query such that non-global dos-filter kicks-in, so requests are rejected.
   * Check that global-dos-filter-listener and Jetty429MetricsDosFilterListener are called.
   */
  public void test_dosFilterMultiListener_withGlobalDosFilterRejecting_CheckRelevantListenersCalled() {
    // send 100 requests, in which 20 are warmup, in theory,
    // - the first 25 (including warmups) are accepted, so response200s=5
    // - the rest of 75 are rejected
    final int warmupRequests = 20;
    final int totalRequests = 100;

    int response200s = hammerAtConstantRate(server.getURI(),
        "/public/hello", Duration.ofMillis(1),
        warmupRequests, totalRequests
    );

    // Verify Jetty429MetricsDosFilterListener is called.
    // check for 200s
    assertEquals(DOS_FILTER_MAX_REQUESTS_PER_SEC - warmupRequests, response200s);
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
        assertEquals(totalRequests - DOS_FILTER_MAX_REQUESTS_PER_SEC,
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
        assertEquals(totalRequests - DOS_FILTER_MAX_REQUESTS_PER_SEC,
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
        assertEquals(
            // 30 seconds is the approximate window size for Rate that comes from the calculation of
            // org.apache.kafka.common.metrics.stats.Rate.windowSize
            Math.floor(
                (double) (totalRequests - DOS_FILTER_MAX_REQUESTS_PER_SEC) / 30),
            Math.floor(errorRateValue), "Actual: " + errorRateValue);
      }
    }

    // Verify that global-dos-filter-listener was called.
    assertEquals(globalDosFilterListener.rejectedCounter.get(),
        totalRequests - DOS_FILTER_MAX_REQUESTS_PER_SEC);
    // Verify that non-global-dos-filter-listener wasn't called.
    assertEquals(nonGlobalDosFilterListener.rejectedCounter.get(), 0);
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

  class TestDosFilterListener extends DoSFilter.Listener {

    AtomicInteger rejectedCounter = new AtomicInteger(0);

    public Action onRequestOverLimit(HttpServletRequest request, OverLimit overlimit,
        DoSFilter dosFilter) {
      Action action = DoSFilter.Action.fromDelay(dosFilter.getDelayMs());
      if (action == Action.REJECT) {
        rejectedCounter.addAndGet(1);
      }

      return action;
    }
  }
}
