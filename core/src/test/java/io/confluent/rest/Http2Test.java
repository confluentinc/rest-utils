/*
 * Copyright 2021 Confluent Inc.
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

import com.fasterxml.jackson.annotation.JsonProperty;

import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.SSLContexts;
import org.apache.kafka.common.config.types.Password;
import org.apache.kafka.test.TestSslUtils;
import org.apache.kafka.test.TestSslUtils.CertificateBuilder;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import javax.net.ssl.SSLContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Configurable;

import org.apache.kafka.common.metrics.KafkaMetric;
import io.confluent.rest.annotations.PerformanceMetric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * This test is a bit unusual because of the way that multiple Java versions are handled.
 * Essentially, the HTTP/2 support is only properly viable in Java 9 and later. Actually,
 * Java 11 is the first LTS version after that. Because we do not want to have a variable
 * set of dependencies based on which version of Java was used to build and package,
 * this test is packaged to exploit HTTP/2 on Java 11 and then conditionally avoid that
 * code on earlier versions of Java. This is the client equivalent of how the HTTP/2
 * support works in the server.
 **/
public class Http2Test {
  private static final Logger log = LoggerFactory.getLogger(Http2Test.class);

  private File trustStore;
  private File clientKeystore;
  private File serverKeystore;

  private static final String HTTP_URI = "http://localhost:8080";
  private static final String HTTPS_URI = "https://localhost:8081";
  private static final String SSL_PASSWORD = "test1234";
  private static final String EXPECTED_200_MSG = "Response status must be 200.";
  private static final String EXPECTED_400_MSG = "Response status must be 400.";
  private static final String UNENCODED_BACKSLASH_PATH = "/test\\unencoded-backslash";
  private static final String URL_ENCODED_BACKSLASH_PATH = "/test%5Cencoded-backslash";

  @BeforeEach
  public void setUp() throws Exception {
    try {
      trustStore = File.createTempFile("Http2Test-truststore", ".jks");
      clientKeystore = File.createTempFile("Http2Test-client-keystore", ".jks");
      serverKeystore = File.createTempFile("Http2Test-server-keystore", ".jks");
    } catch (IOException ioe) {
      throw new RuntimeException("Unable to create temporary files for trust stores and keystores.");
    }
    Map<String, X509Certificate> certs = new HashMap<>();
    createKeystoreWithCert(clientKeystore, "client", certs);
    createKeystoreWithCert(serverKeystore, "server", certs);
    TestSslUtils.createTrustStore(trustStore.getAbsolutePath(), new Password(SSL_PASSWORD), certs);

    TestMetricsReporter.reset();
  }

  private void createKeystoreWithCert(File file, String alias, Map<String, X509Certificate> certs) throws Exception {
    KeyPair keypair = TestSslUtils.generateKeyPair("RSA");
    CertificateBuilder certificateBuilder = new CertificateBuilder(30, "SHA1withRSA");
    X509Certificate cCert = certificateBuilder.sanDnsNames("localhost")
        .generate("CN=mymachine.local, O=A client", keypair);
    TestSslUtils.createKeyStore(file.getPath(), new Password(SSL_PASSWORD), new Password(SSL_PASSWORD),alias, keypair.getPrivate(), cCert);
    certs.put(alias, cCert);
  }

  private void configServerKeystore(Properties props) {
    props.put(RestConfig.SSL_KEYSTORE_LOCATION_CONFIG, serverKeystore.getAbsolutePath());
    props.put(RestConfig.SSL_KEYSTORE_PASSWORD_CONFIG, SSL_PASSWORD);
    props.put(RestConfig.SSL_KEY_PASSWORD_CONFIG, SSL_PASSWORD);
  }

  private TestRestConfig buildTestConfig(boolean enableHttp2) {
    return buildTestConfig(enableHttp2, null, null, false);
  }

  private TestRestConfig buildTestConfig(boolean enableHttp2, boolean enableLegacURI) {
    return buildTestConfig(enableHttp2, null, null, enableLegacURI);
  }

  private TestRestConfig buildTestConfig(boolean enableHttp2, String sslProtocol,
      String sslProvider, boolean enableLegacURI) {
    Properties props = new Properties();
    props.put(RestConfig.LISTENERS_CONFIG, HTTP_URI + "," + HTTPS_URI);
    props.put(RestConfig.METRICS_REPORTER_CLASSES_CONFIG, "io.confluent.rest.TestMetricsReporter");
    if (!enableHttp2) {
      props.put(RestConfig.HTTP2_ENABLED_CONFIG, false);
    }
    if (sslProtocol != null) {
      props.put(RestConfig.SSL_PROTOCOL_CONFIG, sslProtocol);
    }
    if (sslProvider != null) {
      props.put(RestConfig.SSL_PROVIDER_CONFIG, sslProvider);
    }
    if (enableLegacURI) {
      props.put(RestConfig.JETTY_LEGACY_URI_COMPLIANCE, true);
    }
    configServerKeystore(props);
    return new TestRestConfig(props);
  }

  @Test
  public void testHttp2() throws Exception {
    TestRestConfig config = buildTestConfig(true);
    Http2TestApplication app = new Http2TestApplication(config);
    try {
      app.start();

      int statusCode;

      // Just skip HTTP/2 for earlier than Java 11
      if (ApplicationServer.isJava11Compatible()) {
        statusCode = makeGetRequestHttp2(HTTP_URI + "/test");
        assertEquals(200, statusCode, EXPECTED_200_MSG);
        statusCode = makeGetRequestHttp2(HTTPS_URI + "/test",
                                         clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
        assertEquals(200, statusCode, EXPECTED_200_MSG);
      }

      // HTTP/1.1 should work whether HTTP/2 is available or not
      statusCode = makeGetRequestHttp(HTTP_URI + "/test");
      assertEquals(200, statusCode, EXPECTED_200_MSG);
      statusCode = makeGetRequestHttp(HTTPS_URI + "/test",
                                      clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
      assertEquals(200, statusCode, EXPECTED_200_MSG);
      assertMetricsCollected();
    } finally {
      app.stop();
    }
  }

  @Test
  public void testHttp2WithConscrypt() throws Exception {
    TestRestConfig config = buildTestConfig(true, "TLSv1.3", SslConfig.TLS_CONSCRYPT, false);
    Http2TestApplication app = new Http2TestApplication(config);
    try {
      app.start();
      // Should be true irrespective of java version as conscrypt is used as ssl provider.
      assertTrue(ApplicationServer.isHttp2Compatible(config.getBaseSslConfig()));

      int statusCode = makeGetRequestHttp2(HTTP_URI + "/test");
      assertEquals(200, statusCode, EXPECTED_200_MSG);
      statusCode = makeGetRequestHttp2(HTTPS_URI + "/test",
          clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
      assertEquals(200, statusCode, EXPECTED_200_MSG);

      // HTTP/1.1 should work whether HTTP/2 is available or not
      statusCode = makeGetRequestHttp(HTTP_URI + "/test");
      assertEquals(200, statusCode, EXPECTED_200_MSG);
      statusCode = makeGetRequestHttp(HTTPS_URI + "/test",
          clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
      assertEquals(200, statusCode, EXPECTED_200_MSG);
      assertMetricsCollected();
    } finally {
      app.stop();
    }
  }

  @Test
  public void testHttp2AmbiguousSegment() throws Exception {
    // This test is ensuring that URI-encoded / characters work in URIs in all variants
    // With Jetty 12, additional changes to configuring the servlet and URI handling were needed
    // to allow HTTP requests with ambiguous segments to be handled properly.
    TestRestConfig config = buildTestConfig(true);
    Http2TestApplication app = new Http2TestApplication(config);
    try {
      app.start();

      int statusCode;

      // Just skip HTTP/2 for earlier than Java 11
      if (ApplicationServer.isJava11Compatible()) {
        statusCode = makeGetRequestHttp2(HTTP_URI + "/test%2fambiguous%2fsegment");
        assertEquals(200, statusCode, EXPECTED_200_MSG);
        statusCode = makeGetRequestHttp2(HTTPS_URI + "/test%2fambiguous%2fsegment",
                                         clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
        assertEquals(200, statusCode, EXPECTED_200_MSG);
      }

      // HTTP/1.1 should work whether HTTP/2 is available or not
      statusCode = makeGetRequestHttp(HTTP_URI + "/test%2fambiguous%2fsegment");
      assertEquals(200, statusCode, EXPECTED_200_MSG);
      statusCode = makeGetRequestHttp(HTTPS_URI + "/test%2fambiguous%2fsegment",
                                      clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
      assertEquals(200, statusCode, EXPECTED_200_MSG);
      assertMetricsCollected();
    } finally {
      app.stop();
    }
  }

  @Test
  public void testHttp2EncodedBackslashLegacyCompatibility() throws Exception {
    // This test is ensuring that URI-encoded / characters work in URIs in all variants
    // With Jetty 12, additional changes to configuring the servlet and URI handling were needed
    // to allow HTTP requests with ambiguous segments to be handled properly.
    TestRestConfig config = buildTestConfig(true, true);
    Http2TestApplication app = new Http2TestApplication(config);
    try {
      app.start();

      int statusCode;

      // Just skip HTTP/2 for earlier than Java 11
      if (ApplicationServer.isJava11Compatible()) {
        statusCode = makeGetRequestHttp2(HTTP_URI + URL_ENCODED_BACKSLASH_PATH);
        assertEquals(200, statusCode, EXPECTED_200_MSG);
        statusCode = makeGetRequestHttp2(HTTPS_URI + URL_ENCODED_BACKSLASH_PATH,
                                         clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
        assertEquals(200, statusCode, EXPECTED_200_MSG);
      }

      // HTTP/1.1 should work whether HTTP/2 is available or not
      statusCode = makeGetRequestHttp(HTTP_URI + URL_ENCODED_BACKSLASH_PATH);
      assertEquals(200, statusCode, EXPECTED_200_MSG);
      statusCode = makeGetRequestHttp(HTTPS_URI + URL_ENCODED_BACKSLASH_PATH,
                                      clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
      assertEquals(200, statusCode, EXPECTED_200_MSG);
      assertMetricsCollected();
    } finally {
      app.stop();
    }
  }

  @Test
  public void testHttp2EncodedBackslash() throws Exception {
    // This test is ensuring that URI-encoded / characters work in URIs in all variants
    // With Jetty 12, additional changes to configuring the servlet and URI handling were needed
    // to allow HTTP requests with ambiguous segments to be handled properly.
    TestRestConfig config = buildTestConfig(true);
    Http2TestApplication app = new Http2TestApplication(config);
    try {
      app.start();

      int statusCode;

      // Just skip HTTP/2 for earlier than Java 11
      if (ApplicationServer.isJava11Compatible()) {
        statusCode = makeGetRequestHttp2(HTTP_URI + URL_ENCODED_BACKSLASH_PATH);
        assertEquals(400, statusCode, EXPECTED_400_MSG);
        statusCode = makeGetRequestHttp2(HTTPS_URI + URL_ENCODED_BACKSLASH_PATH,
                                         clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
        assertEquals(400, statusCode, EXPECTED_400_MSG);
      }

      // HTTP/1.1 should work whether HTTP/2 is available or not
      statusCode = makeGetRequestHttp(HTTP_URI + URL_ENCODED_BACKSLASH_PATH);
      assertEquals(400, statusCode, EXPECTED_400_MSG);
      statusCode = makeGetRequestHttp(HTTPS_URI + URL_ENCODED_BACKSLASH_PATH,
                                      clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
      assertEquals(400, statusCode, EXPECTED_400_MSG);
      assertMetricsCollected();
    } finally {
      app.stop();
    }
  }

  @Test
  public void testHttp2UnencodedBackslashLegacyCompatibility() throws Exception {
    // This test is ensuring that Jetty 12 URI Compliance enforcement is
    // compatible with the legacy Jetty 9 enforcement.
    TestRestConfig config = buildTestConfig(true, true);
    Http2TestApplication app = new Http2TestApplication(config);
    try {
      app.start();

      int statusCode;

      // Just skip HTTP/2 for earlier than Java 11
      if (ApplicationServer.isJava11Compatible()) {
        HTTP2Client http2Client = new HTTP2Client();
        HttpClient http2ClientHttp = new HttpClient(new HttpClientTransportOverHTTP2(http2Client));
        http2ClientHttp.start();
        try {
          statusCode = http2ClientHttp
              .newRequest(HTTP_URI)
              .path(UNENCODED_BACKSLASH_PATH)
              .send()
              .getStatus();
          assertEquals(200, statusCode, EXPECTED_200_MSG);
        } finally {
          http2ClientHttp.stop();
        }

        // HTTPS over HTTP/2 with client keystore, preserving raw backslash
        SslContextFactory.Client sslContextFactoryH2 = buildSslContextFactory(
            clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
        ClientConnector h2TlsConnector = new ClientConnector();
        h2TlsConnector.setSslContextFactory(sslContextFactoryH2);
        HTTP2Client http2TlsClient = new HTTP2Client(h2TlsConnector);
        HttpClient https2Client = new HttpClient(new HttpClientTransportOverHTTP2(http2TlsClient));
        https2Client.start();
        try {
          statusCode = https2Client
              .newRequest(HTTPS_URI)
              .path(UNENCODED_BACKSLASH_PATH)
              .send()
              .getStatus();
          assertEquals(200, statusCode, EXPECTED_200_MSG);
        } finally {
          https2Client.stop();
        }
      }

      // HTTP/1.1 should behave consistently as well (raw backslash)
      HttpClient http11Client = new HttpClient();
      http11Client.start();
      try {
        statusCode = http11Client
            .newRequest(HTTP_URI)
            .path(UNENCODED_BACKSLASH_PATH)
            .send()
            .getStatus();
        assertEquals(200, statusCode, EXPECTED_200_MSG);
      } finally {
        http11Client.stop();
      }

      // HTTPS over HTTP/1.1 with client keystore, preserving raw backslash
      SslContextFactory.Client sslContextFactory = buildSslContextFactory(
          clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
      ClientConnector clientConnector = new ClientConnector();
      clientConnector.setSslContextFactory(sslContextFactory);
      HttpClient https11Client = new HttpClient(new HttpClientTransportDynamic(clientConnector));
      https11Client.start();
      try {
        statusCode = https11Client
            .newRequest(HTTPS_URI)
            .path(UNENCODED_BACKSLASH_PATH)
            .send()
            .getStatus();
        assertEquals(200, statusCode, EXPECTED_200_MSG);
      } finally {
        https11Client.stop();
      }

      assertMetricsCollected();
    } finally {
      app.stop();
    }
  }

    @Test
  public void testHttp2UnencodedBackslash() throws Exception {
    // This test is ensuring that Jetty 12 URI Compliance enforcement is
    // compatible with the legacy Jetty 9 enforcement.
    TestRestConfig config = buildTestConfig(true);
    Http2TestApplication app = new Http2TestApplication(config);
    try {
      app.start();

      int statusCode;

      // Just skip HTTP/2 for earlier than Java 11
      if (ApplicationServer.isJava11Compatible()) {
        HTTP2Client http2Client = new HTTP2Client();
        HttpClient http2ClientHttp = new HttpClient(new HttpClientTransportOverHTTP2(http2Client));
        http2ClientHttp.start();
        try {
          statusCode = http2ClientHttp
              .newRequest(HTTP_URI)
              .path(UNENCODED_BACKSLASH_PATH)
              .send()
              .getStatus();
          assertEquals(400, statusCode, EXPECTED_400_MSG);
        } finally {
          http2ClientHttp.stop();
        }

        // HTTPS over HTTP/2 with client keystore, preserving raw backslash
        SslContextFactory.Client sslContextFactoryH2 = buildSslContextFactory(
            clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
        ClientConnector h2TlsConnector = new ClientConnector();
        h2TlsConnector.setSslContextFactory(sslContextFactoryH2);
        HTTP2Client http2TlsClient = new HTTP2Client(h2TlsConnector);
        HttpClient https2Client = new HttpClient(new HttpClientTransportOverHTTP2(http2TlsClient));
        https2Client.start();
        try {
          statusCode = https2Client
              .newRequest(HTTPS_URI)
              .path(UNENCODED_BACKSLASH_PATH)
              .send()
              .getStatus();
          assertEquals(400, statusCode, EXPECTED_400_MSG);
        } finally {
          https2Client.stop();
        }
      }

      // HTTP/1.1 should behave consistently as well (raw backslash)
      HttpClient http11Client = new HttpClient();
      http11Client.start();
      try {
        statusCode = http11Client
            .newRequest(HTTP_URI)
            .path(UNENCODED_BACKSLASH_PATH)
            .send()
            .getStatus();
        assertEquals(400, statusCode, EXPECTED_400_MSG);
      } finally {
        http11Client.stop();
      }

      // HTTPS over HTTP/1.1 with client keystore, preserving raw backslash
      SslContextFactory.Client sslContextFactory = buildSslContextFactory(
          clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
      ClientConnector clientConnector = new ClientConnector();
      clientConnector.setSslContextFactory(sslContextFactory);
      HttpClient https11Client = new HttpClient(new HttpClientTransportDynamic(clientConnector));
      https11Client.start();
      try {
        statusCode = https11Client
            .newRequest(HTTPS_URI)
            .path(UNENCODED_BACKSLASH_PATH)
            .send()
            .getStatus();
        assertEquals(400, statusCode, EXPECTED_400_MSG);
      } finally {
        https11Client.stop();
      }

      assertMetricsCollected();
    } finally {
      app.stop();
    }
  }

  @Test
  public void testHttp2CNotEnabled() throws Exception {
    TestRestConfig config = buildTestConfig(false);
    Http2TestApplication app = new Http2TestApplication(config);
    try {
      app.start();

      int statusCode;
      try {
        statusCode = makeGetRequestHttp2(HTTP_URI + "/test");
        fail("HTTP/2 Cleartext should not be enabled");
      } catch (java.util.concurrent.ExecutionException exc) {
        // Fall back to HTTP/1.1 once we've seen HTTP/2C fail
        statusCode = makeGetRequestHttp(HTTP_URI + "/test");
        assertEquals(200, statusCode, EXPECTED_200_MSG);
      }
      assertMetricsCollected();
    } finally {
      app.stop();
    }
  }

  @Test
  public void testHttp2NotEnabled() throws Exception {
    TestRestConfig config = buildTestConfig(false);
    Http2TestApplication app = new Http2TestApplication(config);
    try {
      app.start();

      int statusCode;
      try {
        statusCode = makeGetRequestHttp2(HTTPS_URI + "/test",
                                         clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
        fail("HTTP/2 Cleartext should not be enabled");
      } catch (java.util.concurrent.ExecutionException exc) {
        // Fall back to HTTP/1.1 once we've seen HTTP/2 fail
        statusCode = makeGetRequestHttp(HTTPS_URI + "/test",
                                        clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
        assertEquals(200, statusCode, EXPECTED_200_MSG);
      }
      assertMetricsCollected();
    } finally {
      app.stop();
    }
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
            latencyMaxValue,
            "Metrics should be collected (max latency shouldn't be 0)");
      }
    }
  }

  private SslContextFactory.Client buildSslContextFactory(String clientKeystoreLocation,
                                                   String clientKeystorePassword,
                                                   String clientKeyPassword)
      throws Exception {
    SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
    // trust all self-signed certs.
    SSLContextBuilder sslContextBuilder = SSLContexts.custom()
            .loadTrustMaterial(new TrustSelfSignedStrategy());

    // add the client keystore if it's configured.
    if (clientKeystoreLocation != null) {
      sslContextBuilder.loadKeyMaterial(new File(clientKeystoreLocation),
              clientKeystorePassword.toCharArray(),
              clientKeyPassword.toCharArray());
    }
    SSLContext sslContext = sslContextBuilder.build();
    sslContextFactory.setSslContext(sslContext);
    return sslContextFactory;
  }

  // returns the http response status code.
  private int makeGetRequestHttp(String url) throws Exception {
    log.debug("Making GET using HTTP " + url);
    HttpClient httpClient = new HttpClient();
    httpClient.start();

    int statusCode = httpClient.GET(url).getStatus();
    httpClient.stop();
    return statusCode;
  }

  // returns the http response status code.
  private int makeGetRequestHttp(String url, String clientKeystoreLocation, String clientKeystorePassword,
                                String clientKeyPassword)
      throws Exception {
    log.debug("Making GET using HTTPS " + url);
    SslContextFactory.Client sslContextFactory = buildSslContextFactory(clientKeystoreLocation,
            clientKeystorePassword,
            clientKeyPassword);
    ClientConnector clientConnector = new ClientConnector();
    clientConnector.setSslContextFactory(sslContextFactory);
    HttpClient httpClient = new HttpClient(new HttpClientTransportDynamic(clientConnector));
    httpClient.start();

    int statusCode = httpClient.GET(url).getStatus();
    httpClient.stop();
    return statusCode;
  }

  // returns the http response status code.
  private int makeGetRequestHttp2(String url) throws Exception {
    log.debug("Making GET using HTTP over HTTP/2 Cleartext " + url);

    HTTP2Client http2Client = new HTTP2Client();
    HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP2(http2Client));
    httpClient.start();

    int statusCode = httpClient.GET(url).getStatus();
    httpClient.stop();
    return statusCode;
  }

  // returns the http response status code.
  private int makeGetRequestHttp2(String url, String clientKeystoreLocation, String clientKeystorePassword,
                                  String clientKeyPassword)
      throws Exception {
    log.debug("Making GET using HTTP/2 " + url);

    SslContextFactory.Client sslContextFactory = buildSslContextFactory(clientKeystoreLocation,
        clientKeystorePassword,
        clientKeyPassword);
    ClientConnector clientConnector = new ClientConnector();
    clientConnector.setSslContextFactory(sslContextFactory);
    HTTP2Client http2Client = new HTTP2Client(clientConnector);
    HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP2(http2Client));
    httpClient.start();

    int statusCode = httpClient.GET(url).getStatus();
    httpClient.stop();
    return statusCode;
  }

  private static class Http2TestApplication extends Application<TestRestConfig> {
    public Http2TestApplication(TestRestConfig props) {
      super(props);
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      config.register(new Http2TestResource());
      config.register(new Http2TestAmbiguousSegmentResource());
      config.register(new Http2EncodedBackslashResource());
      config.register(new Http2UnencodedBackslashResource());
    }

    @Override
    public Map<String, String> getMetricsTags() {
      Map<String, String> tags = new LinkedHashMap<>();
      tags.put("instance-id", "1");
      return tags;
    }
  }

  @Path("/test")
  @Produces("application/test.v1+json")
  public static class Http2TestResource {
    public static class Http2TestResponse {
      @JsonProperty
      public String getMessage() {
        return "foo";
      }
    }

    @GET
    @PerformanceMetric("test")
    public Http2TestResponse hello() {
      return new Http2TestResponse();
    }
  }

  @Path(URL_ENCODED_BACKSLASH_PATH)
  @Produces("application/test.v1+json")
  public static class Http2UnencodedBackslashResource {
    public static class Http2UnencodedBackslashResponse {
      @JsonProperty
      public String getMessage() {
        return "unencoded-backslash";
      }
    }

    @GET
    @PerformanceMetric("test")
    public Http2UnencodedBackslashResponse hello() {
      return new Http2UnencodedBackslashResponse();
    }
  }

  @Path(UNENCODED_BACKSLASH_PATH)
  @Produces("application/test.v1+json")
  public static class Http2EncodedBackslashResource {
    public static class Http2EncodedBackslashResponse {
      @JsonProperty
      public String getMessage() {
        return "encoded-backslash";
      }
    }

    @GET
    @PerformanceMetric("test")
    public Http2EncodedBackslashResponse hello() {
      return new Http2EncodedBackslashResponse();
    }
  }

  @Path("/test%2Fambiguous%2Fsegment")
  @Produces("application/test.v1+json")
  public static class Http2TestAmbiguousSegmentResource {
    public static class Http2TestAmbiguousSegmentResponse {
      @JsonProperty
      public String getMessage() {
        return "foo";
      }
    }

    @GET
    public Http2TestAmbiguousSegmentResponse hello() {
      return new Http2TestAmbiguousSegmentResponse();
    }
  }
}
