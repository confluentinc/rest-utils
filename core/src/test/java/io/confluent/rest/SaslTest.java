/**
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
 **/

package io.confluent.rest;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;

import javax.security.auth.login.Configuration;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import io.confluent.common.metrics.KafkaMetric;
import io.confluent.rest.annotations.PerformanceMetric;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class SaslTest {
  private static final Logger log = LoggerFactory.getLogger(SaslTest.class);

  private File jaasFile;
  private File loginPropertiesFile;
  private String previousAuthConfig;
  private SaslTestApplication app;
  private CloseableHttpClient httpclient;
  String httpUri = "http://localhost:8080";

  @Before
  public void setUp() throws Exception {
    jaasFile = File.createTempFile("jaas", ".config");
    loginPropertiesFile = File.createTempFile("login", ".properties");

    String jaas = "c3 {\n"
                  + "  org.eclipse.jetty.jaas.spi.PropertyFileLoginModule required\n"
                  + "  debug=\"true\"\n"
                  + "  file=\"" + loginPropertiesFile.getAbsolutePath() + "\";\n"
                  + "};\n";
    Files.write(
        jaasFile.toPath(),
        jaas.getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.TRUNCATE_EXISTING
    );

    String loginProperties = "jay: kafka,Administrators\n"
                             + "neha: akfak,Administrators\n"
                             + "jun: kafka-\n";
    Files.write(
        loginPropertiesFile.toPath(),
        loginProperties.getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.TRUNCATE_EXISTING
    );
    previousAuthConfig = System.getProperty("java.security.auth.login.config");
    Configuration.setConfiguration(null);
    System.setProperty("java.security.auth.login.config", jaasFile.getAbsolutePath());
    httpclient = HttpClients.createDefault();
    TestMetricsReporter.reset();
    Properties props = new Properties();
    props.put(RestConfig.LISTENERS_CONFIG, httpUri);
    props.put(RestConfig.METRICS_REPORTER_CLASSES_CONFIG, "io.confluent.rest.TestMetricsReporter");
    configBasic(props);
    TestRestConfig config = new TestRestConfig(props);
    app = new SaslTestApplication(config);
    app.start();
  }

  @After
  public void cleanup() throws Exception {
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
    props.put(RestConfig.AUTHENTICATION_ROLES_CONFIG, Arrays.asList("Administrators"));
  }

  @Test
  public void testNoAuthAttempt() throws Exception {
    HttpResponse response = makeGetRequest(httpUri + "/test");
    assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatusLine().getStatusCode());
    assertMetricsCollected();
  }

  @Test
  public void testBadLoginAttempt() throws Exception {
    HttpResponse response = makeGetRequest(httpUri + "/test", "this shouldnt work");
    assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatusLine().getStatusCode());
    assertMetricsCollected();
  }


  @Test
  public void testAuthorizedAttempt() throws Exception {
    CloseableHttpResponse response = null;
    try {
      response = makeGetRequest(httpUri + "/principal", "bmVoYTpha2Zhaw=="); // neha's user/pass
      assertEquals(Response.Status.OK.getStatusCode(), response.getStatusLine().getStatusCode());
      assertEquals("neha", EntityUtils.toString(response.getEntity()));
      response.close();

      response = makeGetRequest(httpUri + "/role/Administrators", "bmVoYTpha2Zhaw=="); // neha's user/pass
      assertEquals(Response.Status.OK.getStatusCode(), response.getStatusLine().getStatusCode());
      assertEquals("true", EntityUtils.toString(response.getEntity()));
      response.close();

      response = makeGetRequest(httpUri + "/role/blah", "bmVoYTpha2Zhaw=="); // neha's user/pass
      assertEquals(Response.Status.OK.getStatusCode(), response.getStatusLine().getStatusCode());
      assertEquals("false", EntityUtils.toString(response.getEntity()));
      response.close();

      assertMetricsCollected();
    } finally {
      if (response != null) {
        response.close();
      }
    }
  }

  @Test
  public void testUnauthorizedAttempt() throws Exception {
    HttpResponse response = makeGetRequest(httpUri + "/principal", "anVuOmthZmthLQ=="); // jun's user/pass
    assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatusLine().getStatusCode());
    assertMetricsCollected();
  }

  private void assertMetricsCollected() {
    assertNotEquals("Expected to have metrics.", 0, TestMetricsReporter.getMetricTimeseries().size());
    for (KafkaMetric metric : TestMetricsReporter.getMetricTimeseries()) {
      if (metric.metricName().name().equals("request-latency-max")) {
        assertTrue("Metrics should be collected (max latency shouldn't be 0)", metric.value() != 0.0);
      }
    }
  }

  // returns the http response status code.
  private CloseableHttpResponse makeGetRequest(
      String url,
      String basicAuth
  ) throws Exception {
    log.debug("Making GET " + url);
    HttpGet httpget = new HttpGet(url);
    if (basicAuth != null) {
      httpget.setHeader(HttpHeaders.AUTHORIZATION, "Basic " + basicAuth);
    }

    CloseableHttpResponse response = null;
    response = httpclient.execute(httpget);
    return response;
  }

  // returns the http response status code.
  private HttpResponse makeGetRequest(String url) throws Exception {
    return makeGetRequest(url, null);
  }

  private static class SaslTestApplication extends Application<TestRestConfig> {
    public SaslTestApplication(TestRestConfig props) {
      super(props);
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      config.register(new SaslTestResource());
    }

    @Override
    public Map<String, String> getMetricsTags() {
      return Collections.singletonMap("instance-id", "1");
    }
  }

  @Path("/")
  @Produces(MediaType.TEXT_PLAIN)
  public static class SaslTestResource {
    @GET
    @Path("/principal")
    @PerformanceMetric("principal")
    public String principal(@Context SecurityContext context) {
      return context.getUserPrincipal().getName();
    }

    @GET
    @Path("/role/{role}")
    @PerformanceMetric("role")
    public boolean hello(
        @PathParam("role") String role,
        @Context SecurityContext context
    ) {
      return context.isUserInRole(role);
    }
  }
}
