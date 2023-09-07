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
import static org.eclipse.jetty.http.HttpStatus.Code.MISDIRECTED_REQUEST;
import static org.eclipse.jetty.http.HttpStatus.Code.OK;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("IntegrationTest")
public class SniHandlerIntegrationTest {

  public static final String TEST_SSL_PASSWORD = "test1234";
  public static final String KAFKA_REST_HOST = "localhost";
  public static final String KSQL_HOST = "anotherhost";

  private Server server;
  private HttpClient httpClient;
  private Properties props;
  private File clientKeystore;

  @BeforeEach
  public void setup(TestInfo info) throws Exception {
    props = new Properties();
  }

  @AfterEach
  public void tearDown() throws Exception {
    httpClient.stop();
    server.stop();
    server.join();
  }

  @Test
  public void test_http_SniHandlerEnabled_no_effect() throws Exception {
    props.setProperty(RestConfig.SNI_CHECK_ENABLED_CONFIG, "true");
    // http doesn't have SNI concept, SNI is an extension for TLS
    startHttpServer("http");
    startHttpClient("http");

    ContentResponse response = httpClient.newRequest(server.getURI())
        .path("/resource")
        .accept(MediaType.TEXT_HTML)
        // make Host different from SNI
        .header(HttpHeader.HOST, "host.value.does.not.matter")
        .send();

    assertEquals(OK.getCode(), response.getStatus());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void test_https_SniHandlerDisabled_wrong_host_pass(boolean mTLSEnabled) throws Exception {
    props.setProperty(RestConfig.SNI_CHECK_ENABLED_CONFIG, "false");
    if (mTLSEnabled) {
      props.setProperty(RestConfig.SSL_CLIENT_AUTHENTICATION_CONFIG,
          RestConfig.SSL_CLIENT_AUTHENTICATION_REQUIRED);
    }
    startHttpServer("https");
    startHttpClient("https");

    ContentResponse response = httpClient.newRequest(server.getURI())
        .path("/resource")
        .accept(MediaType.TEXT_PLAIN)
        // SNI is localhost but Host is anotherhost
        .header(HttpHeader.HOST, KSQL_HOST)
        .send();

    // the request is successful because anotherhost is SAN in certificate
    assertEquals(OK.getCode(), response.getStatus());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void test_https_SniHandlerEnabled_wrong_host_421(boolean mTLSEnabled) throws Exception {
    props.setProperty(RestConfig.SNI_CHECK_ENABLED_CONFIG, "true");
    if (mTLSEnabled) {
      props.setProperty(RestConfig.SSL_CLIENT_AUTHENTICATION_CONFIG,
          RestConfig.SSL_CLIENT_AUTHENTICATION_REQUIRED);
    }
    startHttpServer("https");
    startHttpClient("https");

    ContentResponse response = httpClient.newRequest(server.getURI())
        .path("/resource")
        .accept(MediaType.TEXT_PLAIN)
        // SNI is localhost but Host is anotherhost
        .header(HttpHeader.HOST, KSQL_HOST)
        .send();

    // 421 because SNI check is enabled
    assertEquals(MISDIRECTED_REQUEST.getCode(), response.getStatus());
    String responseContent = response.getContentAsString();
    assertThat(responseContent, containsString(MISDIRECTED_REQUEST.getMessage()));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void test_https_SniHandlerEnabled_same_host_pass(boolean mTLSEnabled) throws Exception {
    props.setProperty(RestConfig.SNI_CHECK_ENABLED_CONFIG, "true");
    if (mTLSEnabled) {
      props.setProperty(RestConfig.SSL_CLIENT_AUTHENTICATION_CONFIG,
          RestConfig.SSL_CLIENT_AUTHENTICATION_REQUIRED);
    }
    startHttpServer("https");
    startHttpClient("https");

    ContentResponse response = httpClient.newRequest(server.getURI())
        .path("/resource")
        .accept(MediaType.TEXT_PLAIN)
        .send();

    assertEquals(OK.getCode(), response.getStatus());

    response = httpClient.newRequest(server.getURI())
        .path("/resource")
        .accept(MediaType.TEXT_PLAIN)
        .header(HttpHeader.HOST, KAFKA_REST_HOST)
        .send();

    assertEquals(OK.getCode(), response.getStatus());
  }

  private void startHttpClient(String scheme) throws Exception {
    // allow setting Host header
    System.setProperty("sun.net.http.allowRestrictedHeaders", "true");

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
      // this forces non-standard domains (localhost) in SNI and X509,
      // see https://github.com/eclipse/jetty.project/pull/6296
      sslContextFactory.setSNIProvider(
          SslContextFactory.Client.SniProvider.NON_DOMAIN_SNI_PROVIDER);
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
        // create two SANs (Subject Alternative Name) in the certificate,
        // imagine "localhost" is kafka rest, and "anotherhost" is ksql
        .sanDnsNames(KAFKA_REST_HOST, KSQL_HOST)
        .generate("CN=mymachine.local, O=A client", keypair);

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
