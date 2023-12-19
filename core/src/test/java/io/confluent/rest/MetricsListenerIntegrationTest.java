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

import static io.confluent.rest.TestUtils.getFreePort;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import org.apache.kafka.common.metrics.KafkaMetric;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.NetworkTrafficServerConnector;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

@Tag("IntegrationTest")
class MetricsListenerIntegrationTest {

  private Server server;
  private Client client;
  private String internalEndpointUri;
  private String externalEndpointUri;

  @BeforeEach
  public void setUp(TestInfo testInfo) throws Exception {
    TestMetricsReporter.reset();
    Properties props = new Properties();
    props.put(RestConfig.LISTENERS_CONFIG, "internal://localhost:" + getFreePort() + ","
        + "external://localhost:" + getFreePort());
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "internal:http,external:http");
    TestRestConfig config = new TestRestConfig(props);
    server = new ApplicationServer<RestConfig>(config);
    TestApplication externalApp = createApp("external");
    ((ApplicationServer<?>) server).registerApplication(createApp("internal"));
    ((ApplicationServer<?>) server).registerApplication(externalApp);
    server.start();

    for (Connector connector : server.getConnectors()) {
      if (connector.getName().equals("internal")) {
        internalEndpointUri =
            "http://localhost:" + ((NetworkTrafficServerConnector) connector).getLocalPort();
      } else if (connector.getName().equals("external")) {
        externalEndpointUri =
            "http://localhost:" + ((NetworkTrafficServerConnector) connector).getLocalPort();
      }
    }
    client = ClientBuilder.newClient(externalApp.resourceConfig.getConfiguration());
  }

  private TestApplication createApp(String name) {
    Properties props = new Properties();
    props.put(RestConfig.METRICS_REPORTER_CLASSES_CONFIG, TestMetricsReporter.class.getName());
    props.put(RestConfig.METRICS_JMX_PREFIX_CONFIG, "rest-utils." + name);
    TestRestConfig config = new TestRestConfig(props);
    return new TestApplication(config, name);
  }

  @AfterEach
  public void tearDown() throws Exception {
    server.stop();
    server.join();
    client.close();
  }

  @Test
  public void testMetricsListenerRegisteredCorrectly() {
    // send 5 requests to external endpoint
    for (int i = 0; i < 5; i++) {
      Response response = client.target(externalEndpointUri)
          .path("/public/hello")
          .request(MediaType.APPLICATION_JSON_TYPE)
          .get();
      assertEquals(Status.OK.getStatusCode(), response.getStatus());
    }
    // send 1 request to internal endpoint
    for (int i = 0; i < 1; i++) {
      Response response = client.target(internalEndpointUri)
          .path("/public/hello")
          .request(MediaType.APPLICATION_JSON_TYPE)
          .get();
      assertEquals(Status.OK.getStatusCode(), response.getStatus());
    }

    for (KafkaMetric metric : TestMetricsReporter.getMetricTimeseries()) {
      if (metric.metricName().group().equals("jetty-metrics")
          && metric.metricName().name().equals("connections-active")
      ) {
        if (metric.metricName().tags().getOrDefault("instance-name", "").equals("internal")) {
          // 1 connections-active in internal metrics
          assertConnectionsActiveMetrics(metric, /*expected value*/ 1.0);
        } else if (metric.metricName().tags().getOrDefault("instance-name", "")
            .equals("external")) {
          // 5 connections-active in external metrics
          assertConnectionsActiveMetrics(metric, /*expected value*/ 5.0);
        }
      }
    }
  }

  private static void assertConnectionsActiveMetrics(final KafkaMetric metric, double expected) {
    assertTrue(metric.measurable().toString().toLowerCase().startsWith("cumulativesum"));
    Object metricValue = metric.metricValue();
    assertInstanceOf(Double.class, metricValue, "Connections active metrics should be measurable");
    assertEquals(expected, (double) metricValue);
  }

  private static class TestApplication extends Application<TestRestConfig> {

    Configurable<?> resourceConfig;

    TestApplication(TestRestConfig props, String listenerName) {
      super(props, "/", listenerName);
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      resourceConfig = config;
      resourceConfig.register(PublicResource.class);
    }

    @Override
    public Map<String, String> getMetricsTags() {
      return new LinkedHashMap<String, String>() {{
        // to identify metrics by tag
        put("instance-name", getListenerName());
      }};
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
