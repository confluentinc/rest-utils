/*
 * Copyright 2024 Confluent Inc.
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

import static io.confluent.rest.TestSslUtils.generateCertificate;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.auto.service.AutoService;
import java.io.StringWriter;
import java.security.KeyPair;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Configurable;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.SSLContexts;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.test.TestSslUtils;
import org.apache.kafka.test.TestUtils;
import org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.util.io.pem.PemObject;
import org.eclipse.jetty.alpn.java.client.JDK9ClientALPNProcessor;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.ssl.ALPNProcessor;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This tests HTTP/2 support in the REST server with FIPS mode enabled.
 **/
class Http2FipsTest {
  private static final String BC_FIPS_APPROVED_ONLY_PROP = "org.bouncycastle.fips.approved_only";
  private static final Logger log = LoggerFactory.getLogger(Http2FipsTest.class);

  private static final String HTTP_URI = "http://localhost:8080";
  private static final String HTTPS_URI = "https://localhost:8081";
  private static final String EXPECTED_200_MSG = "Response status must be 200.";

  private String SERVER_CERT;
  private String SERVER_KEY;

  HttpClient httpClient(SslContextFactory.Client sslContextFactory, HTTP2Client http2Client) {
    final HttpClient client;
    if (http2Client != null) {
      client = new HttpClient(new HttpClientTransportOverHTTP2(http2Client));
    } else if (sslContextFactory != null) {
      ClientConnector clientConnector = new ClientConnector();
      clientConnector.setSslContextFactory(sslContextFactory);
      client = new HttpClient(new HttpClientTransportDynamic(clientConnector));
    } else {
      client = new HttpClient();
    }
    return client;
  }
  @BeforeAll
  public static void setupAll() {
    // set fips approved mode for the system
    System.setProperty(BC_FIPS_APPROVED_ONLY_PROP, "true");
    Security.insertProviderAt(new BouncyCastleFipsProvider(), 1);
    Security.insertProviderAt(new BouncyCastleJsseProvider(), 2);
  }

  @AfterAll
  public static void tearDownAll() {
    System.clearProperty(BC_FIPS_APPROVED_ONLY_PROP);
    Security.removeProvider(BouncyCastleFipsProvider.PROVIDER_NAME);
    Security.removeProvider(BouncyCastleJsseProvider.PROVIDER_NAME);
  }

  @BeforeEach
  public void setUp() throws Exception {
    KeyPair keyPair = TestSslUtils.generateKeyPair("RSA");
    X509Certificate certificate = generateCertificate(keyPair,
        "CN=localhost", "localhost");
    StringWriter privateStringWriter = new StringWriter();
    try (JcaPEMWriter pemWriter = new JcaPEMWriter(privateStringWriter)) {
      pemWriter.writeObject(new PemObject("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
    }
    StringWriter certStringWriter = new StringWriter();
    try (JcaPEMWriter pemWriter = new JcaPEMWriter(certStringWriter)) {
      pemWriter.writeObject(new PemObject("CERTIFICATE", certificate.getEncoded()));
    }
    SERVER_CERT = certStringWriter.toString();
    SERVER_KEY = privateStringWriter.toString();
  }

  private void configServerKeystore(Properties props) throws Exception {
    props.put(RestConfig.SSL_KEYSTORE_LOCATION_CONFIG, asFile(asString(SERVER_KEY, SERVER_CERT)));
    props.put(RestConfig.SSL_KEYSTORE_TYPE_CONFIG, "PEM");
  }

  private TestRestConfig buildTestConfig(boolean enableHttp2) throws Exception {
    return buildTestConfig(enableHttp2, null, null);
  }

  private TestRestConfig buildTestConfig(boolean enableHttp2, String sslProtocol,
      String sslProvider) throws Exception {
    Properties props = new Properties();
    props.put(RestConfig.LISTENERS_CONFIG, HTTP_URI + "," + HTTPS_URI);
    if (!enableHttp2) {
      props.put(RestConfig.HTTP2_ENABLED_CONFIG, false);
    }
    if (sslProtocol != null) {
      props.put(RestConfig.SSL_PROTOCOL_CONFIG, sslProtocol);
    }
    if (sslProvider != null) {
      props.put(RestConfig.SSL_PROVIDER_CONFIG, sslProvider);
    }
    configServerKeystore(props);
    return new TestRestConfig(props);
  }

  // Flaky test disabled: KNET-19715
  @Disabled
  @Test
  public void testHttp2() throws Exception {
    TestRestConfig config = buildTestConfig(true, "TLSv1.3", "BCJSSE");
    Http2TestApplication app = new Http2TestApplication(config);
    try {
      app.start();

      int statusCode;

      // Just skip HTTP/2 for earlier than Java 11
      if (ApplicationServer.isJava11Compatible()) {
        statusCode = makeGetRequestHttp2(HTTP_URI + "/test");
        assertEquals(200, statusCode, EXPECTED_200_MSG);
        statusCode = makeGetRequestHttps2(HTTPS_URI + "/test");
        assertEquals(200, statusCode, EXPECTED_200_MSG);
      }

      // HTTP/1.1 should work whether HTTP/2 is available or not
      statusCode = makeGetRequestHttp(HTTP_URI + "/test");
      assertEquals(200, statusCode, EXPECTED_200_MSG);
      statusCode = makeGetRequestHttps(HTTPS_URI + "/test");
      assertEquals(200, statusCode, EXPECTED_200_MSG);
    } finally {
      app.stop();
    }
  }

  // Flaky test disabled: KNET-19715
  @Disabled
  @Test
  public void testHttp2AmbiguousSegment() throws Exception {
    // This test is ensuring that URI-encoded / characters work in URIs in all variants
    TestRestConfig config = buildTestConfig(true);
    Http2TestApplication app = new Http2TestApplication(config);
    try {
      app.start();

      int statusCode;

      // Just skip HTTP/2 for earlier than Java 11
      if (ApplicationServer.isJava11Compatible()) {
        statusCode = makeGetRequestHttp2(HTTP_URI + "/test%2fambiguous%2fsegment");
        assertEquals(200, statusCode, EXPECTED_200_MSG);
        statusCode = makeGetRequestHttps2(HTTPS_URI + "/test%2fambiguous%2fsegment");
        assertEquals(200, statusCode, EXPECTED_200_MSG);
      }

      // HTTP/1.1 should work whether HTTP/2 is available or not
      statusCode = makeGetRequestHttp(HTTP_URI + "/test%2fambiguous%2fsegment");
      assertEquals(200, statusCode, EXPECTED_200_MSG);
      statusCode = makeGetRequestHttps(HTTPS_URI + "/test%2fambiguous%2fsegment");
      assertEquals(200, statusCode, EXPECTED_200_MSG);
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
    } finally {
      app.stop();
    }
  }

  // Flaky test disabled: KNET-19715
  @Disabled
  @Test
  public void testHttp2NotEnabled() throws Exception {
    TestRestConfig config = buildTestConfig(false);
    Http2TestApplication app = new Http2TestApplication(config);
    try {
      app.start();

      int statusCode;
      try {
        statusCode = makeGetRequestHttps2(HTTPS_URI + "/test");
        fail("HTTP/2 Cleartext should not be enabled");
      } catch (java.util.concurrent.ExecutionException exc) {
        // Fall back to HTTP/1.1 once we've seen HTTP/2 fail
        statusCode = makeGetRequestHttps(HTTPS_URI + "/test");
        assertEquals(200, statusCode, EXPECTED_200_MSG);
      }
    } finally {
      app.stop();
    }
  }

  private SslContextFactory.Client buildSslContextFactory()
      throws Exception {
    SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
    // trust all self-signed certs.
    SSLContextBuilder sslContextBuilder = SSLContexts.custom()
        .loadTrustMaterial(new TrustSelfSignedStrategy());
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
  private int makeGetRequestHttps(String url)
      throws Exception {
    log.debug("Making GET using HTTPS " + url);
    HttpClient httpClient = httpClient(buildSslContextFactory(),null);
    httpClient.start();

    int statusCode = httpClient.GET(url).getStatus();
    httpClient.stop();
    return statusCode;
  }

  // returns the http response status code.
  private int makeGetRequestHttp2(String url) throws Exception {
    log.debug("Making GET using HTTP over HTTP/2 Cleartext " + url);
    HTTP2Client http2Client = new HTTP2Client();
    HttpClient httpClient = httpClient(buildSslContextFactory(),http2Client);
    httpClient.start();

    int statusCode = httpClient.GET(url).getStatus();
    httpClient.stop();
    return statusCode;
  }

  // returns the http response status code.
  private int makeGetRequestHttps2(String url)
      throws Exception {
    log.debug("Making GET using HTTP/2 " + url);

    SslContextFactory.Client sslContextFactory = buildSslContextFactory();
    ClientConnector clientConnector = new ClientConnector();
    clientConnector.setSslContextFactory(sslContextFactory);
    HTTP2Client http2Client = new HTTP2Client(clientConnector);
    HttpClient httpClient = httpClient(sslContextFactory,http2Client);
    httpClient.start();

    int statusCode = httpClient.GET(url).getStatus();
    httpClient.stop();
    return statusCode;
  }

  private String asString(String... pems) {
    StringBuilder builder = new StringBuilder();
    for (String pem : pems) {
      builder.append(pem);
      builder.append("\n");
    }
    return builder.toString().trim();
  }

  private String asFile(String pem) throws Exception {
    return TestUtils.tempFile(pem).getAbsolutePath();
  }

  private static class TestRestConfig extends RestConfig {

    private static final ConfigDef config;

    static {
      config = baseConfigDef();
    }

    TestRestConfig() {
      this(emptyMap());
    }

    TestRestConfig(Map<?, ?> originals) {
      super(config, originals);
    }
  }

  private static class Http2TestApplication extends Application<TestRestConfig> {

    Http2TestApplication(TestRestConfig props) {
      super(props);
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      config.register(new Http2TestResource());
      config.register(new Http2TestAmbiguousSegmentResource());
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
    public Http2TestResponse hello() {
      return new Http2TestResponse();
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

  @AutoService(ALPNProcessor.Client.class)
  public static class TestBouncyCastleClientALPNProcessor extends JDK9ClientALPNProcessor {

    @Override
    public boolean appliesTo(SSLEngine sslEngine) {
      return sslEngine.getClass().getName().startsWith("org.bouncycastle.jsse.provider");
    }
  }
}
