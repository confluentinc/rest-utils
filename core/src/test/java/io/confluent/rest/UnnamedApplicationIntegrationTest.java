/*
 * Copyright 2014 - 2025 Confluent Inc.
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

import static io.confluent.rest.TestUtils.getFreePort;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Configurable;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import org.apache.kafka.common.metrics.KafkaMetric;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.NetworkTrafficServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("IntegrationTest")
class UnnamedApplicationIntegrationTest {

  private Client client;
  private UnnamedTestApplication app;
  private String appEndpoint1;
  private String appEndpoint2;

  @BeforeEach
  public void setUp() throws Exception {
    TestMetricsReporter.reset();
    Properties props = new Properties();
    props.put(RestConfig.LISTENERS_CONFIG, "unnamed1://localhost:" + getFreePort() + ","
        + "unnamed2://localhost:" + getFreePort());
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "unnamed1:http,unnamed2:http");
    app = createUnnamedApp(props);
    app.start();
    for (Connector connector : app.getServer().getConnectors()) {
      appEndpoint1 =
          "http://localhost:" + ((NetworkTrafficServerConnector) connector).getLocalPort();
      appEndpoint2 =
          "http://localhost:" + ((NetworkTrafficServerConnector) connector).getLocalPort();
    }
    client = ClientBuilder.newClient(app.resourceConfig.getConfiguration());
    TestMetricsReporter.reset();
  }

  private UnnamedTestApplication createUnnamedApp(Properties props) {
    props.put(RestConfig.METRICS_REPORTER_CLASSES_CONFIG, TestMetricsReporter.class.getName());
    TestRestConfig config = new TestRestConfig(props);
    return new UnnamedTestApplication(config);
  }

  @AfterEach
  public void tearDown() throws Exception {
    app.stop();
    client.close();
  }

  @Test
  public void testMetricsCombineOfAllListeners() {
    final int endpoint1Requests = 5;
    final int endpoint2Requests = 1;
    // send 5 requests to endpoint1
    for (int i = 0; i < endpoint1Requests; i++) {
      Response response = client.target(appEndpoint1)
          .path("/public/hello")
          .request(MediaType.APPLICATION_JSON_TYPE)
          .get();
      assertEquals(Status.OK.getStatusCode(), response.getStatus());
    }
    // send 1 request to endpoint2
    for (int i = 0; i < endpoint2Requests; i++) {
      Response response = client.target(appEndpoint2)
          .path("/public/hello")
          .request(MediaType.APPLICATION_JSON_TYPE)
          .get();
      assertEquals(Status.OK.getStatusCode(), response.getStatus());
    }

    for (KafkaMetric metric : TestMetricsReporter.getMetricTimeseries()) {
      if (metric.metricName().group().equals("jetty-metrics")
          && metric.metricName().name().equals("connections-active")
      ) {
        // since the app is unnamed, the metric should be cumulative of all connectors (listeners)
        assertConnectionsActiveMetrics(metric, endpoint1Requests + endpoint2Requests);
      }
    }
  }

  private static void assertConnectionsActiveMetrics(final KafkaMetric metric, double expected) {
    assertTrue(metric.measurable().toString().toLowerCase().startsWith("cumulativesum"));
    Object metricValue = metric.metricValue();
    assertInstanceOf(Double.class, metricValue, "Connections active metrics should be measurable");
    assertEquals(expected, (double) metricValue);
  }

  private static class UnnamedTestApplication extends Application<TestRestConfig> {

    Configurable<?> resourceConfig;

    UnnamedTestApplication(TestRestConfig props) {
      super(props, "/" /* null listenerName */);
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      resourceConfig = config;
      resourceConfig.register(PublicResource.class);
    }
  }

  @Consumes(MediaType.APPLICATION_JSON)
  @Path("/public/")
  public static class PublicResource {

    @GET
    @Path("/hello")
    public String hello() {
      return "hello";
    }
  }
}
