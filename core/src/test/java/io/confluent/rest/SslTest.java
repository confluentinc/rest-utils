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

import com.fasterxml.jackson.annotation.JsonProperty;

import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.SSLContexts;
import org.apache.kafka.common.config.types.Password;
import org.apache.kafka.test.TestSslUtils;
import org.apache.kafka.test.TestSslUtils.CertificateBuilder;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.SocketException;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Configurable;

import io.confluent.common.metrics.KafkaMetric;
import io.confluent.rest.annotations.PerformanceMetric;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class SslTest {
  private static final Logger log = LoggerFactory.getLogger(SslTest.class);

  private File trustStore;
  private File clientKeystore;
  private File serverKeystore;

  public static final String SSL_PASSWORD = "test1234";
  public static final String EXPECTED_200_MSG = "Response status must be 200.";

  @Before
  public void setUp() throws Exception {
    try {
      trustStore = File.createTempFile("SslTest-truststore", ".jks");
      clientKeystore = File.createTempFile("SslTest-client-keystore", ".jks");
      serverKeystore = File.createTempFile("SslTest-server-keystore", ".jks");
    } catch (IOException ioe) {
      throw new RuntimeException("Unable to create temporary files for trust stores and keystores.");
    }
    Map<String, X509Certificate> certs = new HashMap<>();
    createKeystoreWithCert(clientKeystore, "client", certs);
    createKeystoreWithCert(serverKeystore, "server", certs);
    TestSslUtils.createTrustStore(trustStore.getAbsolutePath(), new Password(SSL_PASSWORD), certs);
  }

  private void createKeystoreWithCert(File file, String alias, Map<String, X509Certificate> certs) throws Exception {
    KeyPair keypair = TestSslUtils.generateKeyPair("RSA");
    CertificateBuilder certificateBuilder = new CertificateBuilder(30, "SHA1withRSA");
    X509Certificate cCert = certificateBuilder.sanDnsName("localhost")
        .generate("CN=mymachine.local, O=A client", keypair);
    TestSslUtils.createKeyStore(file.getPath(), new Password(SSL_PASSWORD), alias, keypair.getPrivate(), cCert);
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
    props.put(RestConfig.SSL_CLIENT_AUTH_CONFIG, true);
  }

  @Test
  public void testHttpAndHttps() throws Exception {
    TestMetricsReporter.reset();
    Properties props = new Properties();
    String httpUri = "http://localhost:8080";
    String httpsUri = "https://localhost:8081";
    props.put(RestConfig.LISTENERS_CONFIG, httpUri + "," + httpsUri);
    props.put(RestConfig.METRICS_REPORTER_CLASSES_CONFIG, "io.confluent.rest.TestMetricsReporter");
    configServerKeystore(props);
    TestRestConfig config = new TestRestConfig(props);
    SslTestApplication app = new SslTestApplication(config);
    try {
      app.start();

      int statusCode = makeGetRequest(httpUri + "/test");
      assertEquals(EXPECTED_200_MSG, 200, statusCode);
      statusCode = makeGetRequest(httpsUri + "/test",
                                  clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
      assertEquals(EXPECTED_200_MSG, 200, statusCode);
      assertMetricsCollected();
    } finally {
      app.stop();
    }
  }

  @Test(expected = ClientProtocolException.class)
  public void testHttpsOnly() throws Exception {
    TestMetricsReporter.reset();
    Properties props = new Properties();
    String uri = "https://localhost:8080";
    props.put(RestConfig.LISTENERS_CONFIG, uri);
    props.put(RestConfig.METRICS_REPORTER_CLASSES_CONFIG, "io.confluent.rest.TestMetricsReporter");
    configServerKeystore(props);
    TestRestConfig config = new TestRestConfig(props);
    SslTestApplication app = new SslTestApplication(config);
    try {
      app.start();

      int statusCode = makeGetRequest(uri + "/test",
                                      clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
      assertEquals(EXPECTED_200_MSG, 200, statusCode);
      assertMetricsCollected();
      makeGetRequest("http://localhost:8080/test");
    } finally {
      app.stop();
    }
  }

  @Test(expected = SSLException.class)
  public void testHttpOnly() throws Exception {
    TestMetricsReporter.reset();
    Properties props = new Properties();
    String uri = "http://localhost:8080";
    props.put(RestConfig.LISTENERS_CONFIG, uri);
    props.put(RestConfig.METRICS_REPORTER_CLASSES_CONFIG, "io.confluent.rest.TestMetricsReporter");
    TestRestConfig config = new TestRestConfig(props);
    SslTestApplication app = new SslTestApplication(config);
    try {
      app.start();

      int statusCode = makeGetRequest(uri + "/test");
      assertEquals(EXPECTED_200_MSG, 200, statusCode);
      assertMetricsCollected();
      makeGetRequest("https://localhost:8080/test",
                     clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
    } finally {
      app.stop();
    }
  }

  private void assertMetricsCollected() {
    assertNotEquals("Expected to have metrics.", 0, TestMetricsReporter.getMetricTimeseries().size());
    for (KafkaMetric metric : TestMetricsReporter.getMetricTimeseries()) {
      if (metric.metricName().name().equals("request-latency-max")) {
        assertTrue("Metrics should be collected (max latency shouldn't be 0)", metric.value() != 0.0);
      }
    }
  }

  @Test
  public void testHttpsWithNoClientCertAndNoServerTruststore() throws Exception {
    Properties props = new Properties();
    String uri = "https://localhost:8080";
    props.put(RestConfig.LISTENERS_CONFIG, uri);
    configServerKeystore(props);
    TestRestConfig config = new TestRestConfig(props);
    SslTestApplication app = new SslTestApplication(config);
    try {
      app.start();

      int statusCode = makeGetRequest(uri + "/test");
      assertEquals(EXPECTED_200_MSG, 200, statusCode);
    } finally {
      app.stop();
    }
  }

  @Test(expected = SocketException.class)
  public void testHttpsWithAuthAndBadClientCert() throws Exception {
    Properties props = new Properties();
    String uri = "https://localhost:8080";
    props.put(RestConfig.LISTENERS_CONFIG, uri);
    configServerKeystore(props);
    configServerTruststore(props);
    enableSslClientAuth(props);
    TestRestConfig config = new TestRestConfig(props);
    SslTestApplication app = new SslTestApplication(config);
    try {
      app.start();

      // create a new client cert that isn't in the server's trust store.
      File untrustedClient = File.createTempFile("SslTest-client-keystore", ".jks");
      Map<String, X509Certificate> certs = new HashMap<>();
      createKeystoreWithCert(untrustedClient, "client", certs);
      try {
        makeGetRequest(uri + "/test",
                untrustedClient.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
      } catch (SSLHandshakeException she) { // handle a transient failure.
        // nodeLabel:docker-oraclejdk8
        throw new SocketException(she.getMessage());
      } catch (SSLException se) {
        // nodeLabel:docker-debian-10-jdk8
        throw new SocketException(se.toString());
      }
    } finally {
      app.stop();
    }
  }

  @Test(expected = SocketException.class)
  public void testHttpsWithAuthAndNoClientCert() throws Exception {
    Properties props = new Properties();
    String uri = "https://localhost:8080";
    props.put(RestConfig.LISTENERS_CONFIG, uri);
    configServerKeystore(props);
    configServerTruststore(props);
    enableSslClientAuth(props);
    TestRestConfig config = new TestRestConfig(props);
    SslTestApplication app = new SslTestApplication(config);
    try {
      app.start();
      try {
        makeGetRequest(uri + "/test");
      } catch (SSLHandshakeException she) {
        // JDK7 will throw the SHE, but JDK8 will throw the SE. This catch allows this code
        // to run on JDK7 and JDK8.
        // nodeLabel:docker-oraclejdk8
        throw new SocketException(she.toString());
      } catch (SSLException se) {
        // nodeLabel:docker-debian-10-jdk8
        throw new SocketException(se.toString());
      }
    } finally {
      app.stop();
    }
  }

  // returns the http response status code.
  private int makeGetRequest(String url, String clientKeystoreLocation, String clientKeystorePassword,
                             String clientKeyPassword)
      throws Exception {
    log.debug("Making GET " + url);
    HttpGet httpget = new HttpGet(url);
    CloseableHttpClient httpclient;
    if (url.startsWith("http://")) {
      httpclient = HttpClients.createDefault();
    } else {
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

      SSLConnectionSocketFactory sslSf = new SSLConnectionSocketFactory(sslContext, new String[]{"TLSv1.2"},
              null, SSLConnectionSocketFactory.getDefaultHostnameVerifier());

      httpclient = HttpClients.custom()
              .setSSLSocketFactory(sslSf)
              .build();
    }

    int statusCode = -1;
    CloseableHttpResponse response = null;
    try {
      response = httpclient.execute(httpget);
      statusCode = response.getStatusLine().getStatusCode();
    } finally {
      if (response != null) {
        response.close();
      }
      httpclient.close();
    }
    return statusCode;
  }

  // returns the http response status code.
  private int makeGetRequest(String url) throws Exception {
    return makeGetRequest(url, null, null, null);
  }

  private static class SslTestApplication extends Application<TestRestConfig> {
    public SslTestApplication(TestRestConfig props) {
      super(props);
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      config.register(new SslTestResource());
    }

    @Override
    public Map<String, String> getMetricsTags() {
      Map<String, String> tags = new LinkedHashMap<String, String>();
      tags.put("instance-id", "1");
      return tags;
    }
  }

  @Path("/test")
  @Produces("application/test.v1+json")
  public static class SslTestResource {
    public static class SslTestResponse {
      @JsonProperty
      public String getMessage() {
        return "foo";
      }
    }

    @GET
    @PerformanceMetric("test")
    public SslTestResponse hello() {
      return new SslTestResponse();
    }
  }
}
