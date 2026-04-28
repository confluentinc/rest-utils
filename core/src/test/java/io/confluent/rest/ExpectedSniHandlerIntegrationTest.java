/*
 * Copyright 2026 Confluent Inc.
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
import static io.confluent.rest.TestUtils.httpClient;
import static org.eclipse.jetty.http.HttpStatus.Code.BAD_REQUEST;
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
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Configurable;
import jakarta.ws.rs.core.MediaType;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.SSLContexts;
import org.apache.kafka.common.config.types.Password;
import org.apache.kafka.test.TestSslUtils;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * End-to-end coverage for {@link io.confluent.rest.handlers.ExpectedSniHandler}, exercising
 * the {@code expected.sni.headers} and {@code reject.invalid.sni.headers} configs through a
 * real in-memory {@link Application} with TLS.
 *
 * <p>The server always listens on {@code localhost} (so the client's SNI value is
 * "localhost"). Mismatches are produced by configuring {@code expected.sni.headers} to a
 * value other than "localhost".
 */
@Tag("IntegrationTest")
public class ExpectedSniHandlerIntegrationTest {

  public static final String TEST_SSL_PASSWORD = "test1234";
  public static final String LISTENER_HOST = "localhost";
  public static final String OTHER_HOST = "expected-other-host";

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

  /** Plain HTTP has no SNI on the wire; when reject is disabled, the request is allowed
   * through with only a warning. */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void test_http_rejectDisabled_pass(boolean http2Enabled) throws Exception {
    props.setProperty(RestConfig.HTTP2_ENABLED_CONFIG, String.valueOf(http2Enabled));
    props.setProperty(RestConfig.EXPECTED_SNI_HEADERS_CONFIG, OTHER_HOST);
    props.setProperty(RestConfig.REJECT_INVALID_SNI_HEADERS_CONFIG, "false");
    startHttpServer("http");
    startHttpClient("http");

    ContentResponse response = httpClient.newRequest(server.getURI())
        .path("/resource")
        .accept(MediaType.TEXT_HTML)
        .send();

    assertEquals(OK.getCode(), response.getStatus());
  }

  /** Plain HTTP has no SNI on the wire; when reject is enabled, a null SNI is treated
   * as invalid and the request is rejected with HTTP 400 "Invalid SNI". */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void test_http_rejectEnabled_returns400InvalidSni(boolean http2Enabled) throws Exception {
    props.setProperty(RestConfig.HTTP2_ENABLED_CONFIG, String.valueOf(http2Enabled));
    props.setProperty(RestConfig.EXPECTED_SNI_HEADERS_CONFIG, OTHER_HOST);
    props.setProperty(RestConfig.REJECT_INVALID_SNI_HEADERS_CONFIG, "true");
    startHttpServer("http");
    startHttpClient("http");

    ContentResponse response = httpClient.newRequest(server.getURI())
        .path("/resource")
        .accept(MediaType.TEXT_HTML)
        .send();

    assertEquals(BAD_REQUEST.getCode(), response.getStatus());
    assertThat(response.getContentAsString(), containsString("Invalid SNI"));
  }

  @ParameterizedTest
  @MethodSource("provideMtlsAndHttp2")
  public void test_https_sniMatchesExpected_pass(boolean mTLSEnabled, boolean http2Enabled)
      throws Exception {
    props.setProperty(RestConfig.EXPECTED_SNI_HEADERS_CONFIG, LISTENER_HOST);
    props.setProperty(RestConfig.REJECT_INVALID_SNI_HEADERS_CONFIG, "true");
    if (mTLSEnabled) {
      props.setProperty(RestConfig.SSL_CLIENT_AUTHENTICATION_CONFIG,
          RestConfig.SSL_CLIENT_AUTHENTICATION_REQUIRED);
    }
    props.setProperty(RestConfig.HTTP2_ENABLED_CONFIG, String.valueOf(http2Enabled));
    startHttpServer("https");
    startHttpClient("https");

    ContentResponse response = httpClient.newRequest(server.getURI())
        .path("/resource")
        .accept(MediaType.TEXT_PLAIN)
        .send();

    assertEquals(OK.getCode(), response.getStatus());
  }

  @ParameterizedTest
  @MethodSource("provideMtlsAndHttp2")
  public void test_https_sniMismatch_rejectDisabled_pass(boolean mTLSEnabled, boolean http2Enabled)
      throws Exception {
    props.setProperty(RestConfig.EXPECTED_SNI_HEADERS_CONFIG, OTHER_HOST);
    props.setProperty(RestConfig.REJECT_INVALID_SNI_HEADERS_CONFIG, "false");
    if (mTLSEnabled) {
      props.setProperty(RestConfig.SSL_CLIENT_AUTHENTICATION_CONFIG,
          RestConfig.SSL_CLIENT_AUTHENTICATION_REQUIRED);
    }
    props.setProperty(RestConfig.HTTP2_ENABLED_CONFIG, String.valueOf(http2Enabled));
    startHttpServer("https");
    startHttpClient("https");

    // Client sends SNI=localhost, expected list is OTHER_HOST → mismatch, but reject is off.
    ContentResponse response = httpClient.newRequest(server.getURI())
        .path("/resource")
        .accept(MediaType.TEXT_PLAIN)
        .send();

    assertEquals(OK.getCode(), response.getStatus());
  }

  @ParameterizedTest
  @MethodSource("provideMtlsAndHttp2")
  public void test_https_sniMismatch_rejectEnabled_returns400InvalidSni(
      boolean mTLSEnabled, boolean http2Enabled) throws Exception {
    props.setProperty(RestConfig.EXPECTED_SNI_HEADERS_CONFIG, OTHER_HOST);
    props.setProperty(RestConfig.REJECT_INVALID_SNI_HEADERS_CONFIG, "true");
    if (mTLSEnabled) {
      props.setProperty(RestConfig.SSL_CLIENT_AUTHENTICATION_CONFIG,
          RestConfig.SSL_CLIENT_AUTHENTICATION_REQUIRED);
    }
    props.setProperty(RestConfig.HTTP2_ENABLED_CONFIG, String.valueOf(http2Enabled));
    startHttpServer("https");
    startHttpClient("https");

    // Client sends SNI=localhost, expected list is OTHER_HOST → mismatch, reject is on.
    ContentResponse response = httpClient.newRequest(server.getURI())
        .path("/resource")
        .accept(MediaType.TEXT_PLAIN)
        .send();

    assertEquals(BAD_REQUEST.getCode(), response.getStatus());
    assertThat(response.getContentAsString(), containsString("Invalid SNI"));
  }

  private static Stream<Arguments> provideMtlsAndHttp2() {
    return Stream.of(
        Arguments.of(false, false),
        Arguments.of(false, true),
        Arguments.of(true, false),
        Arguments.of(true, true)
    );
  }

  private void startHttpClient(String scheme) throws Exception {
    System.setProperty("sun.net.http.allowRestrictedHeaders", "true");

    if (scheme.equals("https")) {
      SSLContextBuilder sslContextBuilder = SSLContexts.custom()
          .loadTrustMaterial(new TrustSelfSignedStrategy());
      sslContextBuilder.loadKeyMaterial(new File(clientKeystore.getAbsolutePath()),
          TEST_SSL_PASSWORD.toCharArray(),
          TEST_SSL_PASSWORD.toCharArray());
      SSLContext sslContext = sslContextBuilder.build();

      SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
      // Force SNI for non-standard domains like "localhost"
      // (see https://github.com/eclipse/jetty.project/pull/6296)
      sslContextFactory.setSNIProvider(
          SslContextFactory.Client.SniProvider.NON_DOMAIN_SNI_PROVIDER);
      sslContextFactory.setSslContext(sslContext);

      httpClient = httpClient(sslContextFactory);
    } else {
      httpClient = new HttpClient();
    }

    httpClient.start();
  }

  private void startHttpServer(String scheme) throws Exception {
    String url = scheme + "://" + LISTENER_HOST + ":" + getFreePort();
    props.setProperty(RestConfig.LISTENERS_CONFIG, url);

    if (scheme.equals("https")) {
      File serverKeystore;
      File trustStore;
      try {
        trustStore = File.createTempFile("ExpectedSniTest-truststore", ".jks");
        serverKeystore = File.createTempFile("ExpectedSniTest-server-keystore", ".jks");
        clientKeystore = File.createTempFile("ExpectedSniTest-client-keystore", ".jks");
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
      Map<String, X509Certificate> certs) throws Exception {
    KeyPair keypair = TestSslUtils.generateKeyPair("RSA");
    TestSslUtils.CertificateBuilder certificateBuilder = new TestSslUtils.CertificateBuilder(30,
        "SHA1withRSA");

    X509Certificate cCert = certificateBuilder
        .sanDnsNames(LISTENER_HOST)
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
