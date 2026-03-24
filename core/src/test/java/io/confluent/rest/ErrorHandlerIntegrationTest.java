package io.confluent.rest;

import static io.confluent.rest.TestUtils.getFreePort;
import static io.confluent.rest.TestUtils.httpClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.ee10.servlet.security.ConstraintMapping;
import org.eclipse.jetty.ee10.servlet.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.AbstractLoginService;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.RolePrincipal;
import org.eclipse.jetty.security.UserPrincipal;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.security.AuthenticationState;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ErrorHandlerIntegrationTest {

  private static final String DUMMY_EXCEPTION = "dummy exception";
  private Server server;
  private HttpClient httpClient;
  private Properties props;
  private File clientKeystore;

  public static final String SSL_PASSWORD = "test1234";

  @BeforeEach
  public void setUp() {
    props = new Properties();
  }

  @AfterEach
  public void tearDown() throws Exception {
    httpClient.stop();
    server.stop();
    server.join();
  }

  @Test
  public void test_http_unhandledServerExceptionDisplaysStackTraceForInvalidAuthentication()
      throws Exception {
    props.setProperty(RestConfig.SUPPRESS_STACK_TRACE_IN_RESPONSE, "false");
    startHttpServer("http");

    startHttpClient("http");
    ContentResponse response = httpClient.newRequest(server.getURI())
        .path("/test/path")
        .accept(MediaType.TEXT_HTML)
        .send();

    String responseValue = response.getContentAsString();

    assertEquals(500, response.getStatus());
    assertTrue(responseValue.toLowerCase().contains(DUMMY_EXCEPTION));
    assertTrue(responseValue.toLowerCase().contains("caused by"));
  }

  @Test
  public void test_https_unhandledServerExceptionDisplaysStackTraceFor400SNICheck()
      throws Exception {
    props.setProperty(RestConfig.SUPPRESS_STACK_TRACE_IN_RESPONSE, "false");
    startHttpServer("https");

    startHttpClient("https");
    ContentResponse response = httpClient.newRequest(server.getURI())
        .path("/test/path")
        .accept(MediaType.TEXT_HTML)
        // make Host different from SNI (localhost)
        .headers(headers -> headers.put("Host", "abc.com"))
        .send();

    String responseValue = response.getContentAsString();
    assertEquals(400, response.getStatus());
    // Jetty 12's SecureRequestCustomizer uses "Invalid SNI" as the 400 message
    assertTrue(responseValue.toLowerCase().contains("invalid sni"));
    assertTrue(responseValue.toLowerCase().contains("caused by"));
  }

  @Test
  public void test_http_handledServerExceptionDoesNotDisplayStackTraceForInvalidAuthentication()
      throws Exception {
    startHttpServer("http");

    startHttpClient("http");
    ContentResponse response = httpClient
        .newRequest(server.getURI())
        .path("/test/path")
        .accept(MediaType.TEXT_HTML)
        .send();

    String responseValue = response.getContentAsString().toLowerCase();

    assertEquals(500, response.getStatus());
    assertFalse(responseValue.contains(DUMMY_EXCEPTION));
    assertFalse(responseValue.contains("caused by"));
    assertTrue(responseValue.contains("server error"));
  }

  @Test
  public void test_https_handledServerExceptionDoesNotDisplayStackTraceFor400SNICheck()
      throws Exception {
    startHttpServer("https");

    startHttpClient("https");
    ContentResponse response = httpClient.newRequest(server.getURI())
        .path("/test/path")
        .accept(MediaType.TEXT_HTML)
        // make Host different from SNI (localhost)
        .headers(headers -> headers.put("Host", "abc.com"))
        .send();

    String responseValue = response.getContentAsString();
    assertEquals(400, response.getStatus());
    // Jetty 12's SecureRequestCustomizer uses "Invalid SNI" as the 400 message
    assertTrue(responseValue.toLowerCase().contains("invalid sni"));
    assertFalse(responseValue.toLowerCase().contains("caused by"));
  }

  private void startHttpClient(String scheme) throws Exception {
    // allow to set Host header
    System.setProperty("sun.net.http.allowRestrictedHeaders", "true");

    if (scheme.equals("https")) {
      // trust all self-signed certs.
      SSLContextBuilder sslContextBuilder = SSLContexts.custom()
          .loadTrustMaterial(new TrustSelfSignedStrategy());
      // add the client keystore if it's configured.
      sslContextBuilder.loadKeyMaterial(new File(clientKeystore.getAbsolutePath()),
          SSL_PASSWORD.toCharArray(),
          SSL_PASSWORD.toCharArray());
      SSLContext sslContext = sslContextBuilder.build();

      SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
      // this forces non-standard domains (localhost) in SNI and X509,
      // see https://github.com/eclipse/jetty.project/pull/6296
      sslContextFactory.setSNIProvider(
          SslContextFactory.Client.SniProvider.NON_DOMAIN_SNI_PROVIDER);
      sslContextFactory.setSslContext(sslContext);

      httpClient = httpClient(sslContextFactory);
    } else {
      httpClient = httpClient(null);
    }

    httpClient.start();
  }

  private void startHttpServer(String scheme) throws Exception {
    String url = scheme + "://localhost:" + getFreePort();
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
            "Unable to create temporary files for trust stores and keystores.");
      }
      Map<String, X509Certificate> certs = new HashMap<>();
      createKeystoreWithCert(clientKeystore, "client", certs);
      createKeystoreWithCert(serverKeystore, "server", certs);
      TestSslUtils.createTrustStore(trustStore.getAbsolutePath(), new Password(SSL_PASSWORD),
          certs);

      configServerKeystore(props, serverKeystore);
      configServerTruststore(props, trustStore);
    }
    TestApplication application = new TestApplication(new TestRestConfig(props));
    server = application.createServer();

    server.start();
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

  private void createKeystoreWithCert(File file, String alias, Map<String, X509Certificate> certs)
      throws Exception {
    KeyPair keypair = TestSslUtils.generateKeyPair("RSA");
    TestSslUtils.CertificateBuilder certificateBuilder = new TestSslUtils.CertificateBuilder(30,
        "SHA1withRSA");
    X509Certificate cCert = certificateBuilder
        .sanDnsNames("localhost")
        .generate("CN=mymachine.localhost, O=A client", keypair);
    TestSslUtils.createKeyStore(file.getPath(), new Password(SSL_PASSWORD),
        new Password(SSL_PASSWORD), alias, keypair.getPrivate(), cCert);
    certs.put(alias, cCert);
  }

  private static class TestApplication extends Application<TestRestConfig> {

    TestApplication(TestRestConfig restConfig) {
      super(restConfig);
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      config.register(TestResource.class);
    }

    @Override
    protected void configureSecurityHandler(ServletContextHandler context) {
      final ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
      Constraint.Builder constraint = new Constraint.Builder();
      String[] roles = {"**"};
      constraint.roles(roles);
      ConstraintMapping mapping = new ConstraintMapping();
      mapping.setConstraint(constraint.build());
      mapping.setMethod("*");
      mapping.setPathSpec("/*");

      securityHandler.addConstraintMapping(mapping);
      securityHandler.setAuthenticator(new DummyAuthenticator());
      securityHandler.setLoginService(new DummyLoginService());

      context.setSecurityHandler(securityHandler);
    }
  }

  private static class DummyAuthenticator extends BasicAuthenticator {

    @Override
    public AuthenticationState validateRequest(Request req, Response res,
        Callback callback) throws ServerAuthException {
      throw new RuntimeException(DUMMY_EXCEPTION);
    }
  }

  private static class DummyLoginService extends AbstractLoginService {

    @Override
    protected List<RolePrincipal> loadRoleInfo(final UserPrincipal user) {
      return List.of();
    }

    @Override
    protected UserPrincipal loadUserInfo(final String username) {
      return null;
    }
  }

  @Path("/test")
  public static class TestResource {

    @GET
    @Path("/path")
    public String path() {
      return "Ok";
    }
  }
}
