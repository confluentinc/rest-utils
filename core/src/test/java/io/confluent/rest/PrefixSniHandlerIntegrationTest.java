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
import static io.confluent.rest.TestUtils.httpClient;
import static org.eclipse.jetty.http.HttpStatus.Code.MISDIRECTED_REQUEST;
import static org.eclipse.jetty.http.HttpStatus.Code.OK;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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
import org.eclipse.jetty.http.HttpHeader;
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

@Tag("IntegrationTest")
public class PrefixSniHandlerIntegrationTest {
  public static final String TEST_SSL_PASSWORD = "test1234";

  protected Server server;
  protected int port;
  protected HttpClient httpClient;
  protected Properties props;
  protected File clientKeystore;

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

  protected String getPrimarySniHostname() {
    return "lsrc-123.us-east-1.aws.private.confluent.localhost";
  }

  protected String getAlternateSniHostname() {
    return "lsrc-456.us-east-1.aws.private.confluent.localhost";
  }

  protected String getInvalidHostHeader() {
    return "invalid.localhost";
  }

  protected List<String> getValidHostHeaders() {
    return Arrays.asList(
        "lsrc-123.us-east-1.aws.private.confluent.localhost",
        "lsrc-123-domtest.us-east-1.aws.glb.confluent.cloud",
        "lsrc-123.domtest.us-west-2.aws.confluent.cloud",
        "lsrc-123-node456.eu-central-1.azure.glb.confluent.cloud",
        "lsrc-123-node789.ap-southeast-1.gcp.confluent.cloud",
        "lsrc-123.us-east-1.aws.private.confluent.cloud"
    );
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void test_http_TenantPrefixSniHandlerEnabled_no_effect(boolean http2Enabled) throws Exception {
    props.setProperty(RestConfig.HTTP2_ENABLED_CONFIG, String.valueOf(http2Enabled));
    props.setProperty(RestConfig.PREFIX_SNI_CHECK_ENABLED_CONFIG, "true");
    props.setProperty(RestConfig.PREFIX_SNI_PREFIX_CONFIG, "lsrc-");
    // http doesn't have SNI concept, SNI is an extension for TLS
    startHttpServer("http");
    startHttpClient("http");

    ContentResponse response = httpClient.newRequest(server.getURI())
        .path("/resource")
        .accept(MediaType.TEXT_HTML)
        // make Host different from SNI
        .headers(headers -> headers.put(HttpHeader.HOST, getInvalidHostHeader()))
        .send();

    assertEquals(OK.getCode(), response.getStatus());
  }

  @ParameterizedTest
  @MethodSource("provideParameters")
  public void test_https_TenantPrefixSniHandlerDisabled_wrong_host_pass(boolean mTLSEnabled,
      boolean http2Enabled) throws Exception {
    props.setProperty(RestConfig.PREFIX_SNI_CHECK_ENABLED_CONFIG, "false");
    if (mTLSEnabled) {
      props.setProperty(RestConfig.SSL_CLIENT_AUTHENTICATION_CONFIG,
          RestConfig.SSL_CLIENT_AUTHENTICATION_REQUIRED);
    }
    props.setProperty(RestConfig.HTTP2_ENABLED_CONFIG, String.valueOf(http2Enabled));
    startHttpServer("https");
    startHttpClient("https");

    // The Jetty HTTP client automatically uses the hostname from the server URI as the SNI value
    ContentResponse response = httpClient.newRequest(server.getURI())
        .path("/resource")
        .accept(MediaType.TEXT_PLAIN)
        // SNI is lsrc-123.* but Host is lsrc-456.*
        .headers(headers -> headers.put(HttpHeader.HOST, getAlternateSniHostname()))
        .send();

    // the request is successful because tenant prefix check is disabled
    assertEquals(OK.getCode(), response.getStatus());
  }

  @ParameterizedTest
  @MethodSource("provideParameters")
  public void test_https_TenantPrefixSniHandlerEnabled_wrong_host_421(boolean mTLSEnabled, boolean http2Enabled)
      throws Exception {
    props.setProperty(RestConfig.PREFIX_SNI_CHECK_ENABLED_CONFIG, "true");
    props.setProperty(RestConfig.PREFIX_SNI_PREFIX_CONFIG, "lsrc-");
    props.setProperty(RestConfig.SNI_HOST_CHECK_ENABLED_CONFIG, "false");
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
        // SNI is lsrc-123.* but Host doesn't start with lsrc-123
        .headers(headers -> headers.put(HttpHeader.HOST, getInvalidHostHeader()))
        .send();

    // 421 because tenant prefix SNI check is enabled and host doesn't start with tenant ID
    assertEquals(MISDIRECTED_REQUEST.getCode(), response.getStatus());
    String responseContent = response.getContentAsString();
    assertThat(responseContent, containsString(MISDIRECTED_REQUEST.getMessage()));
  }

  @ParameterizedTest
  @MethodSource("provideParameters")
  public void test_https_TenantPrefixSniHandlerEnabled_fallback_localhost_pass(boolean mTLSEnabled, boolean http2Enabled)
      throws Exception {
    props.setProperty(RestConfig.PREFIX_SNI_CHECK_ENABLED_CONFIG, "true");
    props.setProperty(RestConfig.PREFIX_SNI_PREFIX_CONFIG, "lsrc-");
    props.setProperty(RestConfig.SNI_HOST_CHECK_ENABLED_CONFIG, "false");
    if (mTLSEnabled) {
      props.setProperty(RestConfig.SSL_CLIENT_AUTHENTICATION_CONFIG,
          RestConfig.SSL_CLIENT_AUTHENTICATION_REQUIRED);
    }
    props.setProperty(RestConfig.HTTP2_ENABLED_CONFIG, String.valueOf(http2Enabled));
    startHttpServer("https");
    startHttpClient("https");

    // Use the server's actual URI but override the host header
    ContentResponse response = httpClient.newRequest("https://localhost:" + port)
        .path("/resource")
        .accept(MediaType.TEXT_PLAIN)
        .headers(headers -> headers.put(HttpHeader.HOST, "localhost"))
        .send();

    // fallback to the original SNI check if sniServerName does not start with the tenant prefix
    assertEquals(OK.getCode(), response.getStatus(),
        "Expected OK status for host header: " + "localhost");
  }

  @ParameterizedTest
  @MethodSource("provideParameters")
  public void test_https_TenantPrefixSniHandlerEnabled_fallback_localhost_wrongHeader_421(boolean mTLSEnabled, boolean http2Enabled)
      throws Exception {
    props.setProperty(RestConfig.PREFIX_SNI_CHECK_ENABLED_CONFIG, "true");
    props.setProperty(RestConfig.PREFIX_SNI_PREFIX_CONFIG, "lsrc-");
    props.setProperty(RestConfig.SNI_HOST_CHECK_ENABLED_CONFIG, "false");
    if (mTLSEnabled) {
      props.setProperty(RestConfig.SSL_CLIENT_AUTHENTICATION_CONFIG,
          RestConfig.SSL_CLIENT_AUTHENTICATION_REQUIRED);
    }
    props.setProperty(RestConfig.HTTP2_ENABLED_CONFIG, String.valueOf(http2Enabled));
    startHttpServer("https");
    startHttpClient("https");

    // Test each valid host header
    for (String validHost : getValidHostHeaders()) {
      ContentResponse response = httpClient.newRequest("https://localhost:" + port)
          .path("/resource")
          .accept(MediaType.TEXT_PLAIN)
          // Test each valid host header pattern
          .headers(headers -> headers.put(HttpHeader.HOST, validHost))
          .send();

      // 421 because host header does not match the SNI value
      assertEquals(MISDIRECTED_REQUEST.getCode(), response.getStatus());
      String responseContent = response.getContentAsString();
      assertThat(responseContent, containsString(MISDIRECTED_REQUEST.getMessage()));
    }
  }

  @ParameterizedTest
  @MethodSource("provideParameters")
  public void test_https_TenantPrefixSniHandlerEnabled_same_tenant_pass(boolean mTLSEnabled, boolean http2Enabled)
      throws Exception {
    props.setProperty(RestConfig.PREFIX_SNI_CHECK_ENABLED_CONFIG, "true");
    props.setProperty(RestConfig.PREFIX_SNI_PREFIX_CONFIG, "lsrc-");
    props.setProperty(RestConfig.SNI_HOST_CHECK_ENABLED_CONFIG, "false");
    if (mTLSEnabled) {
      props.setProperty(RestConfig.SSL_CLIENT_AUTHENTICATION_CONFIG,
          RestConfig.SSL_CLIENT_AUTHENTICATION_REQUIRED);
    }
    props.setProperty(RestConfig.HTTP2_ENABLED_CONFIG, String.valueOf(http2Enabled));
    startHttpServer("https");
    startHttpClient("https");

    // First test with default host header
    ContentResponse response = httpClient.newRequest(server.getURI())
        .path("/resource")
        .accept(MediaType.TEXT_PLAIN)
        .send();
    assertEquals(OK.getCode(), response.getStatus());

    // Test each valid host header
    for (String validHost : getValidHostHeaders()) {
      response = httpClient.newRequest(server.getURI())
          .path("/resource")
          .accept(MediaType.TEXT_PLAIN)
          // Test each valid host header pattern
          .headers(headers -> headers.put(HttpHeader.HOST, validHost))
          .send();

      assertEquals(OK.getCode(), response.getStatus(), 
          "Expected OK status for host header: " + validHost);
    }
  }

  // generate mTLS enablement and http2 enablement parameters for tests
  private static Stream<Arguments> provideParameters() {
    return Stream.of(
        Arguments.of(false, false),
        Arguments.of(false, true),
        Arguments.of(true, false),
        Arguments.of(true, true)
    );
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

      httpClient = httpClient(sslContextFactory);
    } else {
      httpClient = new HttpClient();
    }

    httpClient.start();
  }

  private void startHttpServer(String scheme) throws Exception {
    port = getFreePort();
    String url = scheme + "://" + getPrimarySniHostname() + ":" + port;
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
      createKeystoreWithCert(clientKeystore, PrefixSniHandlerIntegrationTest.ServiceType.CLIENT, certs);
      createKeystoreWithCert(serverKeystore, PrefixSniHandlerIntegrationTest.ServiceType.SERVER, certs);
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

  private void createKeystoreWithCert(File file, PrefixSniHandlerIntegrationTest.ServiceType type,
      Map<String, X509Certificate> certs)
      throws Exception {
    KeyPair keypair = TestSslUtils.generateKeyPair("RSA");
    TestSslUtils.CertificateBuilder certificateBuilder = new TestSslUtils.CertificateBuilder(30,
        "SHA1withRSA");

    X509Certificate cCert = certificateBuilder
        // create two SANs (Subject Alternative Name) in the certificate,
        // imagine "localhost" is kafka rest, and "anotherhost" is ksql
        .sanDnsNames(getPrimarySniHostname(), getAlternateSniHostname(), "localhost")
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
