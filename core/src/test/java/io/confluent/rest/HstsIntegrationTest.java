/*
 * Copyright 2014 - 2024 Confluent Inc.
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
import static org.eclipse.jetty.http.HttpStatus.Code.OK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.io.IOException;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import javax.net.ssl.SSLContext;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.MediaType;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.SSLContexts;
import org.apache.kafka.common.config.types.Password;
import org.apache.kafka.test.TestSslUtils;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("IntegrationTest")
public class HstsIntegrationTest {

  private static final String TEST_SSL_PASSWORD = "test1234";
  private static final String KAFKA_REST_HOST = "localhost";

  private Server server;
  private HttpClient httpClient;
  private Properties props;
  private File clientKeystore;

  @BeforeEach
  public void setup() throws Exception {
    props = new Properties();
  }

  @AfterEach
  public void tearDown() throws Exception {
    httpClient.stop();
    server.stop();
    server.join();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void test_http_hsts_enabled_no_header(boolean http2Enabled) throws Exception {
    props.setProperty(RestConfig.HTTP2_ENABLED_CONFIG, String.valueOf(http2Enabled));
    props.setProperty(RestConfig.HSTS_HEADER_ENABLE_CONFIG, "true");
    startHttpServer("http");
    startHttpClient("http");

    ContentResponse response = httpClient.newRequest(server.getURI())
        .path("/resource")
        .accept(MediaType.TEXT_PLAIN)
        .send();

    assertEquals(OK.getCode(), response.getStatus());
    // http server so no hsts header should be present.
    assertNull(response.getHeaders().get(HttpHeader.STRICT_TRANSPORT_SECURITY));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void test_https_hsts_disabled_no_header(boolean http2Enabled) throws Exception {
    props.setProperty(RestConfig.HTTP2_ENABLED_CONFIG, String.valueOf(http2Enabled));
    props.setProperty(RestConfig.HSTS_HEADER_ENABLE_CONFIG, "false");
    startHttpServer("https");
    startHttpClient("https");

    ContentResponse response = httpClient.newRequest(server.getURI())
        .path("/resource")
        .accept(MediaType.TEXT_PLAIN)
        .send();

    assertEquals(OK.getCode(), response.getStatus());
    // hsts header is disabled so no header should be present.
    assertNull(response.getHeaders().get(HttpHeader.STRICT_TRANSPORT_SECURITY));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void test_https_hsts_enabled_has_header(boolean http2Enabled) throws Exception {
    props.setProperty(RestConfig.HTTP2_ENABLED_CONFIG, String.valueOf(http2Enabled));
    props.setProperty(RestConfig.HSTS_HEADER_ENABLE_CONFIG, "true");
    startHttpServer("https");
    startHttpClient("https");

    ContentResponse response = httpClient.newRequest(server.getURI())
        .path("/resource")
        .accept(MediaType.TEXT_PLAIN)
        .send();

    assertEquals(OK.getCode(), response.getStatus());
    // hsts header is enabled so the header should be present
    assertEquals("max-age=63072000; includeSubDomains",
        response.getHeaders().get(HttpHeader.STRICT_TRANSPORT_SECURITY));
  }

  private void startHttpClient(String scheme) throws Exception {
    if (scheme.equals("https")) {
      // trust all self-signed certs.
      SSLContextBuilder sslContextBuilder = SSLContexts.custom()
          .loadTrustMaterial(new TrustSelfSignedStrategy());
      // add the client keystore if it's configured.
      sslContextBuilder.loadKeyMaterial(new File(clientKeystore.getAbsolutePath()),
          TEST_SSL_PASSWORD.toCharArray(),
          TEST_SSL_PASSWORD.toCharArray());
      SSLContext sslContext = sslContextBuilder.build();

      SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
      sslContextFactory.setSslContext(sslContext);

      httpClient = new HttpClient(sslContextFactory);
    } else {
      httpClient = new HttpClient();
    }

    httpClient.start();
  }

  private void startHttpServer(String scheme) throws Exception {
    String url = scheme + "://" + KAFKA_REST_HOST + ":" + getFreePort();
    props.setProperty(RestConfig.LISTENERS_CONFIG, url);

    if (scheme.equals("https")) {
      File serverKeystore;
      File trustStore;
      try {
        trustStore = File.createTempFile("SslTest-truststore", ".jks");
        serverKeystore = File.createTempFile("SslTest-server-keystore", ".jks");
        clientKeystore = File.createTempFile("SslTest-client-keystore", ".jks");
      } catch (IOException ioe) {
        throw new RuntimeException(
            "Unable to create temporary files for truststores and keystores.");
      }
      Map<String, X509Certificate> certs = new HashMap<>();
      createKeystoreWithCert(clientKeystore, ServiceType.CLIENT, certs);
      createKeystoreWithCert(serverKeystore, ServiceType.SERVER, certs);
      TestSslUtils.createTrustStore(trustStore.getAbsolutePath(), new Password(TEST_SSL_PASSWORD),
          certs);

      configServerKeystore(props, serverKeystore);
      configServerTruststore(props, trustStore);
    }
    TestApp application = new TestApp(new TestRestConfig(props), "/");
    server = application.createServer();

    server.start();
  }

  private void configServerKeystore(Properties props, File serverKeystore) {
    props.put(RestConfig.SSL_KEYSTORE_LOCATION_CONFIG, serverKeystore.getAbsolutePath());
    props.put(RestConfig.SSL_KEYSTORE_PASSWORD_CONFIG, TEST_SSL_PASSWORD);
    props.put(RestConfig.SSL_KEY_PASSWORD_CONFIG, TEST_SSL_PASSWORD);
  }

  private void configServerTruststore(Properties props, File trustStore) {
    props.put(RestConfig.SSL_TRUSTSTORE_LOCATION_CONFIG, trustStore.getAbsolutePath());
    props.put(RestConfig.SSL_TRUSTSTORE_PASSWORD_CONFIG, TEST_SSL_PASSWORD);
  }

  private void createKeystoreWithCert(File file, ServiceType type,
      Map<String, X509Certificate> certs)
      throws Exception {
    KeyPair keypair = TestSslUtils.generateKeyPair("RSA");
    TestSslUtils.CertificateBuilder certificateBuilder = new TestSslUtils.CertificateBuilder(30,
        "SHA1withRSA");

    X509Certificate cCert = certificateBuilder
        .sanDnsNames(KAFKA_REST_HOST)
        .generate("CN=mymachine.local", keypair);

    String alias = type.toString().toLowerCase();
    TestSslUtils.createKeyStore(file.getPath(), new Password(TEST_SSL_PASSWORD),
        new Password(TEST_SSL_PASSWORD), alias, keypair.getPrivate(), cCert);
    certs.put(alias, cCert);
  }

  private static class TestApp extends Application<TestRestConfig> implements AutoCloseable {

    TestApp(TestRestConfig config, String path) {
      super(config, path);
    }

    @Override
    public void setupResources(final Configurable<?> config, final TestRestConfig appConfig) {
      config.register(RestResource.class);
    }

    @Override
    public void close() throws Exception {
      stop();
    }
  }

  @Path("/")
  public static class RestResource {

    @GET
    @Path("/resource")
    public String get() {
      return "Hello";
    }
  }

  private enum ServiceType {
    CLIENT,
    SERVER
  }
}
