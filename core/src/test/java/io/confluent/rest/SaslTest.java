/*
 * Copyright 2016 Confluent Inc.
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

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;
import jakarta.ws.rs.core.Response;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.Dsl;
import org.asynchttpclient.ws.WebSocket;
import org.asynchttpclient.ws.WebSocketListener;
import org.asynchttpclient.ws.WebSocketUpgradeHandler;
import jakarta.websocket.server.ServerContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;

import javax.security.auth.login.Configuration;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Configurable;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;

import org.apache.kafka.common.metrics.KafkaMetric;
import io.confluent.rest.annotations.PerformanceMetric;

import static jakarta.ws.rs.core.Response.Status.FORBIDDEN;
import static jakarta.ws.rs.core.Response.Status.OK;
import static jakarta.ws.rs.core.Response.Status.UNAUTHORIZED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class SaslTest {

  private static final Logger log = LoggerFactory.getLogger(SaslTest.class);
  private static final String NEHA_BASIC_AUTH = "bmVoYTpha2Zhaw==";
  private static final String JUN_BASIC_AUTH = "anVuOmthZmthLQ==";
  private static final String HTTP_URI = "http://localhost:8080";
  private static final String WS_URI = "ws://localhost:8080/ws";
  private static final Pattern WS_ERROR_PATTERN = Pattern.compile(".*code=(\\d+).*");

  private String previousAuthConfig;
  private SaslTestApplication app;
  private CloseableHttpClient httpclient;


  @BeforeEach
  public void setUp(@TempDir Path tmpFolder) throws Exception {
    Path jaasFile = tmpFolder.resolve("jaas.config");
    Path loginPropertiesFile = tmpFolder.resolve("login.properties");

    String jaas = "c3 {\n"
                  + "  org.eclipse.jetty.security.jaas.spi.PropertyFileLoginModule required\n"
                  + "  debug=\"true\"\n"
                  + "  file=\"" + loginPropertiesFile.toAbsolutePath() + "\";\n"
                  + "};\n";

    Files.write(jaasFile, Collections.EMPTY_LIST);
    Files.write(loginPropertiesFile, Collections.EMPTY_LIST);
    Files.write(
        jaasFile,
        jaas.getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.TRUNCATE_EXISTING
    );

    String loginProperties = "jay: kafka,Administrators\n"
                             + "neha: akfak,Administrators\n"
                             + "jun: kafka-\n";
    Files.write(
        loginPropertiesFile,
        loginProperties.getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.TRUNCATE_EXISTING
    );
    previousAuthConfig = System.getProperty("java.security.auth.login.config");
    Configuration.setConfiguration(null);
    System.setProperty("java.security.auth.login.config", jaasFile.toAbsolutePath().toString());
    httpclient = HttpClients.createDefault();
    TestMetricsReporter.reset();
    Properties props = new Properties();
    props.put(RestConfig.LISTENERS_CONFIG, HTTP_URI);
    props.put(RestConfig.METRICS_REPORTER_CLASSES_CONFIG, "io.confluent.rest.TestMetricsReporter");
    configBasic(props);
    TestRestConfig config = new TestRestConfig(props);
    app = new SaslTestApplication(config);
    app.start();
  }

  @AfterEach
  public void cleanup() throws Exception {
    assertMetricsCollected();

    Configuration.setConfiguration(null);
    if (previousAuthConfig != null) {
      System.setProperty("java.security.auth.login.config", previousAuthConfig);
    }
    httpclient.close();
    app.stop();
  }

  private void configBasic(Properties props) {
    props.put(RestConfig.AUTHENTICATION_METHOD_CONFIG, RestConfig.AUTHENTICATION_METHOD_BASIC);
    props.put(RestConfig.AUTHENTICATION_REALM_CONFIG, "c3");
    props.put(RestConfig.AUTHENTICATION_ROLES_CONFIG, Collections.singletonList("Administrators"));
  }

  @Test
  public void testNoAuthAttempt() throws Exception {
    try (CloseableHttpResponse response = makeGetRequest( "/test")) {
      assertEquals(UNAUTHORIZED.getStatusCode(), response.getStatusLine().getStatusCode());
    }
  }

  @Test
  public void testNoAuthAttemptOnWs() throws Exception {
    int statusCode = makeWsGetRequest(null);
    assertEquals(UNAUTHORIZED.getStatusCode(), statusCode);
  }

  @Test
  public void testBadLoginAttempt() throws Exception {
    try (CloseableHttpResponse response =
        makeGetRequest("/test", "dGVzdA==")) {
      assertEquals(UNAUTHORIZED.getStatusCode(), response.getStatusLine().getStatusCode());
    }
  }

  @Test
  public void testBadLoginAttemptOnWs() throws Exception {
    int statusCode = makeWsGetRequest("dGVzdA==");
    assertEquals(UNAUTHORIZED.getStatusCode(), statusCode);
  }

  @Test
  public void testAuthorizedAttempt() throws Exception {
    try (CloseableHttpResponse response =
        makeGetRequest("/principal", NEHA_BASIC_AUTH)) {
      assertEquals(OK.getStatusCode(), response.getStatusLine().getStatusCode());
      assertEquals("neha", EntityUtils.toString(response.getEntity()));
    }

    try (CloseableHttpResponse response =
        makeGetRequest("/role/Administrators", NEHA_BASIC_AUTH)) {
      assertEquals(OK.getStatusCode(), response.getStatusLine().getStatusCode());
      assertEquals("true", EntityUtils.toString(response.getEntity()));
    }

    try (CloseableHttpResponse response =
      makeGetRequest("/role/blah", NEHA_BASIC_AUTH)) {
      assertEquals(OK.getStatusCode(), response.getStatusLine().getStatusCode());
      assertEquals("false", EntityUtils.toString(response.getEntity()));
    }
  }

  @Test
  public void testAuthorizedAttemptOnWs() throws Exception {
    int statusCode = makeWsGetRequest(NEHA_BASIC_AUTH);
    assertEquals(OK.getStatusCode(), statusCode);
  }

  @Test
  public void testUnauthorizedAttempt() throws Exception {
    try (CloseableHttpResponse response =
        makeGetRequest("/principal", JUN_BASIC_AUTH)) {
      assertEquals(FORBIDDEN.getStatusCode(), response.getStatusLine().getStatusCode());
    }
  }

  @Test
  public void testUnAuthorizedAttemptOnWs() throws Exception {
    int statusCode = makeWsGetRequest(JUN_BASIC_AUTH);
    assertEquals(FORBIDDEN.getStatusCode(), statusCode);
  }

  private void assertMetricsCollected() {
    assertNotEquals(
        0,
        TestMetricsReporter.getMetricTimeseries().size(),
        "Expected to have metrics.");
    for (KafkaMetric metric : TestMetricsReporter.getMetricTimeseries()) {
      if (metric.metricName().name().equals("request-latency-max")) {
        Object metricValue = metric.metricValue();
        assertTrue(
            metricValue instanceof Double,
            "Request latency metrics should be measurable");
        double latencyMaxValue = (double) metricValue;
        assertNotEquals(
            0.0,
            metricValue,
            "Metrics should be collected (max latency shouldn't be 0)");
      }
    }
  }

  // returns the http response status code.
  private CloseableHttpResponse makeGetRequest(
      String url,
      String basicAuth
  ) throws Exception {
    log.debug("Making GET " + HTTP_URI + url);
    HttpGet httpget = new HttpGet(HTTP_URI + url);
    if (basicAuth != null) {
      httpget.setHeader(HttpHeaders.AUTHORIZATION, "Basic " + basicAuth);
    }

    return httpclient.execute(httpget);
  }

  private CloseableHttpResponse makeGetRequest(String url) throws Exception {
    return makeGetRequest(url, null);
  }

  private int makeWsGetRequest(String basicAuth) throws Exception {
    log.debug("Making WebSocket GET " + WS_URI + "/test");

    final AtomicReference<Throwable> error = new AtomicReference<>();

    WebSocketUpgradeHandler wsHandler = new WebSocketUpgradeHandler.Builder()
        .addWebSocketListener(new WebSocketListener() {
          public void onOpen(WebSocket websocket) {
            // WebSocket connection opened
          }

          public void onClose(WebSocket websocket, int code, String reason) {
            // WebSocket connection closed
          }

          public void onError(Throwable t) {
            log.info("Websocket failed", t);
            error.set(t);
          }
        }).build();

    BoundRequestBuilder requestBuilder = Dsl.asyncHttpClient()
        .prepareGet(WS_URI + "/test");

    if (basicAuth != null) {
      requestBuilder = requestBuilder
          .addHeader(HttpHeaders.AUTHORIZATION, "Basic " + basicAuth);
    }

    WebSocket ws = requestBuilder
        .setRequestTimeout(10000)
        .execute(wsHandler)
        .get();

    if (error.get() != null) {
      return extractStatusCode(error.get().getMessage());
    }

    ws.sendCloseFrame();
    return Response.Status.OK.getStatusCode();
  }

  private static int extractStatusCode(final String message) {
    final Matcher matcher = WS_ERROR_PATTERN.matcher(message);
    assertTrue(matcher.matches(), "Test invalid");
    return Integer.parseInt(matcher.group(1));
  }

  private static class SaslTestApplication extends Application<TestRestConfig> {
    private SaslTestApplication(TestRestConfig props) {
      super(props);
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      config.register(new SaslTestResource());
    }

    @Override
    protected void registerWebSocketEndpoints(final ServerContainer container) {
      try {
        container.addEndpoint(ServerEndpointConfig.Builder
            .create(
                WSEndpoint.class,
                WSEndpoint.class.getAnnotation(ServerEndpoint.class).value()
            ).build());
      } catch (DeploymentException e) {
        fail("Invalid test");
      }
    }

    @Override
    public Map<String, String> getMetricsTags() {
      return Collections.singletonMap("instance-id", "1");
    }
  }

  @jakarta.ws.rs.Path("/")
  @Produces(MediaType.TEXT_PLAIN)
  public static class SaslTestResource {
    @GET
    @jakarta.ws.rs.Path("/principal")
    @PerformanceMetric("principal")
    public String principal(@Context SecurityContext context) {
      return context.getUserPrincipal().getName();
    }

    @GET
    @jakarta.ws.rs.Path("/role/{role}")
    @PerformanceMetric("role")
    public boolean hello(
        @PathParam("role") String role,
        @Context SecurityContext context
    ) {
      return context.isUserInRole(role);
    }
  }

  @ServerEndpoint(value = "/test")
  public static class WSEndpoint {
    @OnOpen
    public void onOpen(final Session session, final EndpointConfig endpointConfig) {
      session.getAsyncRemote().sendText("Test message",
          result -> {
            if (!result.isOK()) {
              log.warn(
                  "Error sending websocket message for session {}",
                  session.getId(),
                  result.getException()
              );
            }
          });
    }
  }
}
