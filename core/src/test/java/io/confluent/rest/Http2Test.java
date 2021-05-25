/**
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
 **/

package io.confluent.rest;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.SSLContexts;
import org.apache.kafka.common.config.types.Password;
import org.apache.kafka.test.TestSslUtils;
import org.apache.kafka.test.TestSslUtils.CertificateBuilder;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.http.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory.Client;

import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.net.SocketException;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Configurable;

import org.apache.kafka.common.metrics.KafkaMetric;
import io.confluent.rest.annotations.PerformanceMetric;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
  private File serverKeystoreBak;
  private File serverKeystoreErr;

  public static final String SSL_PASSWORD = "test1234";
  public static final String EXPECTED_200_MSG = "Response status must be 200.";
  public static final int CERT_RELOAD_WAIT_TIME = 20000;

  private static boolean isJava11Compatible() {
    final String versionString = System.getProperty("java.specification.version");
  
    final StringTokenizer st = new StringTokenizer(versionString, ".");
    int majorVersion = Integer.parseInt(st.nextToken());
    int minorVersion;
    if (st.hasMoreTokens()) {
      minorVersion = Integer.parseInt(st.nextToken());
    } else {
      minorVersion = 0;
    }
  
    return majorVersion >= 11;
  }

  @Before
  public void setUp() throws Exception {
    try {
      trustStore = File.createTempFile("Http2Test-truststore", ".jks");
      clientKeystore = File.createTempFile("Http2Test-client-keystore", ".jks");
      serverKeystore = File.createTempFile("Http2Test-server-keystore", ".jks");
      serverKeystoreBak = File.createTempFile("Http2Test-server-keystore", ".jks.bak");
      serverKeystoreErr = File.createTempFile("Http2Test-server-keystore", ".jks.err");
    } catch (IOException ioe) {
      throw new RuntimeException("Unable to create temporary files for trust stores and keystores.");
    }
    Map<String, X509Certificate> certs = new HashMap<>();
    createKeystoreWithCert(clientKeystore, "client", certs);
    createKeystoreWithCert(serverKeystore, "server", certs);
    TestSslUtils.createTrustStore(trustStore.getAbsolutePath(), new Password(SSL_PASSWORD), certs);

    Files.copy(serverKeystore.toPath(), serverKeystoreBak.toPath(), StandardCopyOption.REPLACE_EXISTING);
    certs = new HashMap<>();
    createWrongKeystoreWithCert(serverKeystoreErr, "server", certs);
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

  private void configServerTruststore(Properties props) {
    props.put(RestConfig.SSL_TRUSTSTORE_LOCATION_CONFIG, trustStore.getAbsolutePath());
    props.put(RestConfig.SSL_TRUSTSTORE_PASSWORD_CONFIG, SSL_PASSWORD);
  }

  private void enableSslClientAuth(Properties props) {
    props.put(RestConfig.SSL_CLIENT_AUTHENTICATION_CONFIG, RestConfig.SSL_CLIENT_AUTHENTICATION_REQUIRED);
  }

  private void createWrongKeystoreWithCert(File file, String alias, Map<String, X509Certificate> certs) throws Exception {
    KeyPair keypair = TestSslUtils.generateKeyPair("RSA");
    CertificateBuilder certificateBuilder = new CertificateBuilder(30, "SHA1withRSA");
    X509Certificate cCert = certificateBuilder.sanDnsNames("fail")
        .generate("CN=mymachine.local, O=A client", keypair);
    TestSslUtils.createKeyStore(file.getPath(), new Password(SSL_PASSWORD), new Password(SSL_PASSWORD), alias, keypair.getPrivate(), cCert);
    certs.put(alias, cCert);
  }


  @Test
  public void testHttp2() throws Exception {
    TestMetricsReporter.reset();
    Properties props = new Properties();
    String httpUri = "http://localhost:8080";
    String httpsUri = "https://localhost:8081";
    props.put(RestConfig.LISTENERS_CONFIG, httpUri + "," + httpsUri);
    props.put(RestConfig.METRICS_REPORTER_CLASSES_CONFIG, "io.confluent.rest.TestMetricsReporter");
    configServerKeystore(props);
    TestRestConfig config = new TestRestConfig(props);
    Http2TestApplication app = new Http2TestApplication(config);
    try {
      app.start();

      int statusCode;

      // Just skip HTTP/2 for earlier than Java 11
      if (isJava11Compatible()) {
        statusCode = makeGetRequestHttp2(httpUri + "/test");
        assertEquals(EXPECTED_200_MSG, 200, statusCode);
        statusCode = makeGetRequestHttp2(httpsUri + "/test",
                                         clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
        assertEquals(EXPECTED_200_MSG, 200, statusCode);
      }

      // HTTP/1.1 should work whether HTTP/2 is available or not
      statusCode = makeGetRequestHttp(httpUri + "/test");
      assertEquals(EXPECTED_200_MSG, 200, statusCode);
      statusCode = makeGetRequestHttp(httpsUri + "/test",
                                      clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
      assertEquals(EXPECTED_200_MSG, 200, statusCode);
      assertMetricsCollected();
    } finally {
      app.stop();
    }
  }

  @Test
  public void testHttp2CNotEnabled() throws Exception {
    TestMetricsReporter.reset();
    Properties props = new Properties();
    String httpUri = "http://localhost:8080";
    props.put(RestConfig.LISTENERS_CONFIG, httpUri);
    props.put(RestConfig.METRICS_REPORTER_CLASSES_CONFIG, "io.confluent.rest.TestMetricsReporter");
    props.put(RestConfig.HTTP2_ENABLED_CONFIG, false);
    configServerKeystore(props);
    TestRestConfig config = new TestRestConfig(props);
    Http2TestApplication app = new Http2TestApplication(config);
    try {
      app.start();

      int statusCode;
      try {
        statusCode = makeGetRequestHttp2(httpUri + "/test");
        fail("HTTP/2 Cleartext should not be enabled");
      } catch (java.util.concurrent.ExecutionException exc) {
        // Fall back to HTTP/1.1 once we've seen HTTP/2C fail
        assertTrue(exc.getCause() instanceof java.net.ConnectException);
        statusCode = makeGetRequestHttp(httpUri + "/test");
        assertEquals(EXPECTED_200_MSG, 200, statusCode);
      }
      assertMetricsCollected();
    } finally {
      app.stop();
    }
  }

  @Test
  public void testHttp2NotEnabled() throws Exception {
    TestMetricsReporter.reset();
    Properties props = new Properties();
    String httpsUri = "https://localhost:8081";
    props.put(RestConfig.LISTENERS_CONFIG, httpsUri);
    props.put(RestConfig.METRICS_REPORTER_CLASSES_CONFIG, "io.confluent.rest.TestMetricsReporter");
    props.put(RestConfig.HTTP2_ENABLED_CONFIG, false);
    configServerKeystore(props);
    TestRestConfig config = new TestRestConfig(props);
    Http2TestApplication app = new Http2TestApplication(config);
    try {
      app.start();

      int statusCode;
      try {
        statusCode = makeGetRequestHttp2(httpsUri + "/test",
                                         clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
        fail("HTTP/2 Cleartext should not be enabled");
      } catch (java.util.concurrent.ExecutionException exc) {
        // Fall back to HTTP/1.1 once we've seen HTTP/2 fail
        assertTrue(exc.getCause() instanceof java.net.ConnectException);
        statusCode = makeGetRequestHttp(httpsUri + "/test",
                                        clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
        assertEquals(EXPECTED_200_MSG, 200, statusCode);
      }
      assertMetricsCollected();
    } finally {
      app.stop();
    }
  }

  private void assertMetricsCollected() {
    assertNotEquals(
        "Expected to have metrics.",
        0,
        TestMetricsReporter.getMetricTimeseries().size());
    for (KafkaMetric metric : TestMetricsReporter.getMetricTimeseries()) {
      if (metric.metricName().name().equals("request-latency-max")) {
        Object metricValue = metric.metricValue();
        assertTrue(
            "Request latency metrics should be measurable",
            metricValue instanceof Double);
        double latencyMaxValue = (double) metricValue;
        assertNotEquals(
            "Metrics should be collected (max latency shouldn't be 0)",
            0.0,
            latencyMaxValue);
      }
    }
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
    SslContextFactory sslContextFactory = new SslContextFactory.Client();
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

    HttpClient httpClient = new HttpClient(sslContextFactory);
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
    HTTP2Client http2Client = new HTTP2Client();

    SslContextFactory sslContextFactory = new SslContextFactory.Client();
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

    HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP2(http2Client), sslContextFactory);
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
}
