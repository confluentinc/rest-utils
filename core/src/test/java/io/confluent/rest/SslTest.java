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
import io.confluent.common.metrics.KafkaMetric;
import io.confluent.rest.annotations.PerformanceMetric;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.HttpHostConnectException;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.SSLContexts;
import org.apache.kafka.test.TestSslUtils;
import org.apache.kafka.common.config.types.Password;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Configurable;
import java.io.File;
import java.io.IOException;
import java.net.SocketException;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.assertEquals;

public class SslTest {
  private static final Logger log = LoggerFactory.getLogger(SslTest.class);

  private File trustStore;
  private File clientKeystore;
  private File serverKeystore;

  public static final String SSL_PASSWORD = "test1234";

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
    // IMPORTANT: CN must be "localhost" because Jetty expects the server CN to be the FQDN.
    X509Certificate cCert = TestSslUtils.generateCertificate("CN=localhost, O=A client", keypair, 30, "SHA1withRSA");
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
    Properties props = new Properties();
    props.put(RestConfig.REST_PROTOCOL_CONFIG, RestConfig.REST_PROTOCOL_HTTP_PLUS_HTTPS);
    props.put(RestConfig.METRICS_REPORTER_CLASSES_CONFIG, "io.confluent.rest.TestMetricsReporter");
    configServerKeystore(props);
    TestRestConfig config = new TestRestConfig(props);
    SslTestApplication app = new SslTestApplication(config);
    try {
      app.start();

      makeGetRequestAndVerify200("http://localhost:" + RestConfig.PORT_CONFIG_DEFAULT + "/test");
      makeGetRequestAndVerify200("https://localhost:" + (RestConfig.PORT_HTTPS_CONFIG_DEFAULT) + "/test",
              clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);

      // ensure that jetty-metrics and jetty-https-metrics have the same value.
      int activeConnectionsCount = 0;
      double firstActiveConnectionsValue = 0;
      for (KafkaMetric metric : TestMetricsReporter.getMetricTimeseries()) {
        if (metric.metricName().name().equals("connections-active")) {
          if (activeConnectionsCount == 0) {
            firstActiveConnectionsValue = metric.value();
          } else {
            assertEquals("Metrics values in jetty-metrics and jetty-https-metrics should be equal.",
                    firstActiveConnectionsValue, metric.value(), 0.0);
          }
          activeConnectionsCount++;
        }
      }

      assertEquals("There should be two occurrences of each metric, one for http and one for https",
              2, activeConnectionsCount);
    } finally {
      app.stop();
    }
  }

  @Test
  public void testHttpsOnly() throws Exception {
    Properties props = new Properties();
    props.put(RestConfig.REST_PROTOCOL_CONFIG, RestConfig.REST_PROTOCOL_HTTPS);
    configServerKeystore(props);
    TestRestConfig config = new TestRestConfig(props);
    SslTestApplication app = new SslTestApplication(config);
    try {
      app.start();

      try {
        makeGetRequestAndVerify200("http://localhost:" + RestConfig.PORT_CONFIG_DEFAULT + "/test");
      } catch (HttpHostConnectException hhce) {
        // expected.
      }
      makeGetRequestAndVerify200("https://localhost:" + (RestConfig.PORT_HTTPS_CONFIG_DEFAULT) + "/test",
              clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
    } finally {
      app.stop();
    }
  }

  @Test
  public void testHttpOnly() throws Exception {
    Properties props = new Properties();
    props.put(RestConfig.REST_PROTOCOL_CONFIG, RestConfig.REST_PROTOCOL_HTTP);
    TestRestConfig config = new TestRestConfig(props);
    SslTestApplication app = new SslTestApplication(config);
    try {
      app.start();

      makeGetRequestAndVerify200("http://localhost:" + RestConfig.PORT_CONFIG_DEFAULT + "/test");

      try {
        makeGetRequestAndVerify200("https://localhost:" + (RestConfig.PORT_HTTPS_CONFIG_DEFAULT) + "/test",
                clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
      } catch (HttpHostConnectException hhce) {
        // expected.
      }
    } finally {
      app.stop();
    }
  }

  @Test
  public void testHttpsWithNoClientCertAndNoServerTruststore() throws Exception {
    Properties props = new Properties();
    props.put(RestConfig.REST_PROTOCOL_CONFIG, RestConfig.REST_PROTOCOL_HTTPS);
    configServerKeystore(props);
    TestRestConfig config = new TestRestConfig(props);
    SslTestApplication app = new SslTestApplication(config);
    try {
      app.start();

      makeGetRequestAndVerify200("https://localhost:" + (RestConfig.PORT_HTTPS_CONFIG_DEFAULT) + "/test");
    } finally {
      app.stop();
    }
  }

  @Test
  public void testHttpsWithAuthAndBadClientCert() throws Exception {
    Properties props = new Properties();
    props.put(RestConfig.REST_PROTOCOL_CONFIG, RestConfig.REST_PROTOCOL_HTTPS);
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
        makeGetRequestAndVerify200("https://localhost:" + (RestConfig.PORT_HTTPS_CONFIG_DEFAULT) + "/test",
                untrustedClient.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
      } catch (SSLHandshakeException she) {
        // expected.
      } catch (SocketException se) {
        // expected. SocketException is thrown when SSLHandshakeException is caught.
      }
    } finally {
      app.stop();
    }
  }

  @Test
  public void testHttpsWithAuthAndNoClientCert() throws Exception {
    Properties props = new Properties();
    props.put(RestConfig.REST_PROTOCOL_CONFIG, RestConfig.REST_PROTOCOL_HTTPS);
    configServerKeystore(props);
    configServerTruststore(props);
    enableSslClientAuth(props);
    TestRestConfig config = new TestRestConfig(props);
    SslTestApplication app = new SslTestApplication(config);
    try {
      app.start();

      try {
        makeGetRequestAndVerify200("https://localhost:" + (RestConfig.PORT_HTTPS_CONFIG_DEFAULT) + "/test");
      } catch (SSLHandshakeException she) {
        // expected.
      } catch (SocketException se) {
        // expected. SocketException is thrown when SSLHandshakeException is caught.
      }
    } finally {
      app.stop();
    }
  }

  private void makeGetRequestAndVerify200(String url, String clientKeystoreLocation, String clientKeystorePassword,
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

      SSLConnectionSocketFactory sslSf = new SSLConnectionSocketFactory(sslContext, new String[]{"TLSv1"},
              null, SSLConnectionSocketFactory.getDefaultHostnameVerifier());

      httpclient = HttpClients.custom()
              .setSSLSocketFactory(sslSf)
              .build();
    }

    CloseableHttpResponse response = httpclient.execute(httpget);
    assertEquals("Response status must be 200.", 200, response.getStatusLine().getStatusCode());
    response.close();
    httpclient.close();
  }

  private void makeGetRequestAndVerify200(String url) throws Exception {
    makeGetRequestAndVerify200(url, null, null, null);
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
