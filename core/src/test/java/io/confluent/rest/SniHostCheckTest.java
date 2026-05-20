package io.confluent.rest;

import static io.confluent.rest.TestUtils.getFreePort;
import static io.confluent.rest.TestUtils.httpClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.HashMap;
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
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SniHostCheckTest {
    private static final String SSL_PASSWORD = "test1234";
    private Server server;
    private HttpClient httpClient;
    private Properties props;
    private File clientKeystore;

    @BeforeEach
    public void setUp() throws Exception {
        props = new Properties();
    }

    @AfterEach
    public void tearDown() throws Exception {
        httpClient.stop();
        server.stop();
        server.join();
    }

    @Test
    public void testSniHostCheckDefaultEnabled() throws Exception {
        startHttpServer("https");
        startHttpClient("https");

        ContentResponse response = httpClient.newRequest(server.getURI())
            .path("/test")
            .accept(MediaType.TEXT_PLAIN)
            // make Host different from SNI (localhost)
            .headers(headers -> headers.put(HttpHeader.HOST, "abc.com"))
            .send();

        assertEquals(400, response.getStatus());
        String responseContent = response.getContentAsString();
        // Jetty 12's SecureRequestCustomizer uses "Invalid SNI" as the 400 message
        assertTrue(responseContent.toLowerCase().contains("invalid sni"));
    }

    @Test
    public void testSniHostCheckDisabled() throws Exception {
        props.setProperty(RestConfig.SNI_HOST_CHECK_ENABLED_CONFIG, "false");
        startHttpServer("https");
        startHttpClient("https");

        ContentResponse response = httpClient.newRequest(server.getURI())
            .path("/test")
            .accept(MediaType.TEXT_PLAIN)
            // make Host different from SNI (localhost)
            .headers(headers -> headers.put(HttpHeader.HOST, "abc.com"))
            .send();

        assertEquals(200, response.getStatus());
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
            httpClient = new HttpClient();
        }

        httpClient.start();
    }

    private void startHttpServer(String scheme) throws Exception {
        String url = scheme + "://localhost:" + getFreePort();
        props.setProperty(RestConfig.LISTENERS_CONFIG, url);

        if (scheme.equals("https")) {
            File trustStore;
            File serverKeystore;

            try {
                trustStore = File.createTempFile("SniHostCheckTest-truststore", ".jks");
                clientKeystore = File.createTempFile("SniHostCheckTest-client-keystore", ".jks");
                serverKeystore = File.createTempFile("SniHostCheckTest-server-keystore", ".jks");
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
        TestApplication(TestRestConfig config) {
            super(config);
        }

        @Override
        public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
            config.register(TestResource.class);
        }
    }

    @Path("/test")
    public static class TestResource {
        @GET
        public String get() {
            return "Hello";
        }
    }
}
