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
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.ProxyConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.MediaType;
import java.io.File;
import java.io.IOException;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class ProxyProtocolTest {
  private static TestRestConfig testConfig;

  private Properties props;
  private ApplicationServer<TestRestConfig> server;
  private File clientKeystore;

  public static final String SSL_PASSWORD = "test1234";

  @BeforeEach
  public void setup() throws Exception {
    props = new Properties();
    props.setProperty(RestConfig.PROXY_PROTOCOL_ENABLED_CONFIG, "true");
  }

  private void createKeystoreWithCert(File file, String alias, Map<String, X509Certificate> certs) throws Exception {
    KeyPair keypair = TestSslUtils.generateKeyPair("RSA");
    TestSslUtils.CertificateBuilder certificateBuilder = new TestSslUtils.CertificateBuilder(30, "SHA1withRSA");
    X509Certificate cCert = certificateBuilder.sanDnsNames("localhost")
        .generate("CN=mymachine.local, O=A client", keypair);
    TestSslUtils.createKeyStore(file.getPath(), new Password(SSL_PASSWORD), new Password(SSL_PASSWORD), alias, keypair.getPrivate(), cCert);
    certs.put(alias, cCert);
  }

  private void configServerKeystore(Properties props, File serverKeystore) {
    props.put(RestConfig.SSL_KEYSTORE_LOCATION_CONFIG, serverKeystore.getAbsolutePath());
    props.put(RestConfig.SSL_KEYSTORE_PASSWORD_CONFIG, SSL_PASSWORD);
    props.put(RestConfig.SSL_KEY_PASSWORD_CONFIG, SSL_PASSWORD);
  }

  private void configServerTruststore(Properties props, File trustStore) {
    props.put(RestConfig.SSL_TRUSTSTORE_LOCATION_CONFIG, trustStore.getAbsolutePath());
    props.put(RestConfig.SSL_TRUSTSTORE_PASSWORD_CONFIG, SSL_PASSWORD);
  }

  @AfterEach
  public void tearDown() throws Exception {
    server.stop();
  }

  @Test
  public void testConnectionFactoriesHttp() throws Exception {
    testConnectionFactories("http", false);
  }

  @Test
  public void testConnectionFactoriesHttpWithHttp2() throws Exception {
    testConnectionFactories("http", true);
  }

  @Test
  public void testConnectionFactoriesHttps() throws Exception {
    testConnectionFactories("https", false);
  }

  @Test
  public void testConnectionFactoriesHttpsWithHttp2() throws Exception {
    testConnectionFactories("https", true);
  }

  private void testConnectionFactories(String scheme, boolean http2Enabled) throws Exception {
    String url = scheme + "://localhost:9000";
    props.setProperty(RestConfig.LISTENERS_CONFIG, url);
    props.setProperty(RestConfig.HTTP2_ENABLED_CONFIG, Boolean.toString(http2Enabled));

    if (scheme.equals("https")) {
      File trustStore;
      File serverKeystore;

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

      configServerKeystore(props, serverKeystore);
      configServerTruststore(props, trustStore);
    }

    testConfig = new TestRestConfig(props);

    server = new ApplicationServer<>(testConfig);
    ProxyTestApp app = new ProxyTestApp("/app");
    server.registerApplication(app);
    server.start();

    boolean proxyConnectionFactoryFound = false;
    for (ConnectionFactory factory : server.getConnectors()[0].getConnectionFactories()) {
      if (factory instanceof ProxyConnectionFactory) {
        proxyConnectionFactoryFound = true;
        break;
      }
    }

    assertThat("ProxyConnectionFactory was not found in server's connection factories",
        proxyConnectionFactoryFound);
    assertThat(makeGetRequest(url + "/app/resource"), is(HttpStatus.Code.OK.getCode()));
  }

  // returns the http response status code.
  private int makeGetRequest(String url) throws Exception {
    HttpGet httpget = new HttpGet(url);
    CloseableHttpClient httpclient;
    if (url.startsWith("http://")) {
      httpclient = HttpClients.createDefault();
    } else {
      // trust all self-signed certs.
      SSLContextBuilder sslContextBuilder = SSLContexts.custom()
          .loadTrustMaterial(new TrustSelfSignedStrategy());

      // add the client keystore if it's configured.
      sslContextBuilder.loadKeyMaterial(new File(clientKeystore.getAbsolutePath()),
          SSL_PASSWORD.toCharArray(),
          SSL_PASSWORD.toCharArray());
      SSLContext sslContext = sslContextBuilder.build();

      SSLConnectionSocketFactory sslSf = new SSLConnectionSocketFactory(sslContext, new String[]{"TLSv1.2"},
          null, SSLConnectionSocketFactory.getDefaultHostnameVerifier());

      httpclient = HttpClients.custom()
          .setSSLSocketFactory(sslSf)
          .build();
    }

    int statusCode;
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

    private static class ProxyTestApp extends Application<TestRestConfig> implements AutoCloseable {
      private static final AtomicBoolean SHUTDOWN_CALLED = new AtomicBoolean(true);

    ProxyTestApp(String path) {
      this(testConfig, path);
    }

    ProxyTestApp(TestRestConfig config, String path) {
      super(config, path);
    }

    @Override
    public void setupResources(final Configurable<?> config, final TestRestConfig appConfig) {
      config.register(ApplicationServerTest.RestResource.class);
    }

    @Override
    public void close() throws Exception {
      stop();
    }

    @Override
    public void onShutdown() {
      SHUTDOWN_CALLED.set(true);
    }
  }

  @Path("/")
  @Produces(MediaType.TEXT_PLAIN)
  public static class RestResource {
    @GET
    @Path("/resource")
    public String get() {
      return "Hello";
    }
  }
}
