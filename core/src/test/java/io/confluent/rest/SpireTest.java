package io.confluent.rest;

import io.spiffe.provider.SpiffeSslContextFactory;
import io.spiffe.spiffeid.SpiffeId;
import io.spiffe.spiffeid.TrustDomain;
import io.spiffe.svid.x509svid.X509Svid;
import io.spiffe.workloadapi.X509Source;
import io.spiffe.bundle.x509bundle.X509Bundle;

import javax.net.ssl.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Map;
import java.util.HashMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import java.util.Properties;
import org.eclipse.jetty.server.Server;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Configurable;
import jakarta.ws.rs.core.MediaType;
import org.apache.kafka.test.TestSslUtils;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SpireTest {

    private Server mtlsServer;
    private Server tlsServer;
    private MockX509Source serverX509Source;

    private static final String BASE_URL = "https://localhost:";
    private static final int MTLS_SERVER_PORT = 9876;
    private static final int TLS_SERVER_PORT = 9877;
    private static final String SERVER_TRUST_DOMAIN = "authorized.domain";

    // Mock implementation of X509Source for testing
    private static class MockX509Source implements X509Source {
        private final X509Svid svid;
        private final Map<TrustDomain, X509Bundle> bundles;

        private MockX509Source(X509Svid svid, Map<TrustDomain, X509Bundle> bundles) {
            this.svid = svid;
            this.bundles = bundles;
        }

        public static class Builder {
            private String trustDomain = SERVER_TRUST_DOMAIN;
            private String path = "test";
            private String cn = "test.local";
            private String organization = "Test";
            private Map<TrustDomain, X509Bundle> trustDomainBundles = new HashMap<>();

            public Builder trustDomain(String trustDomain) {
                this.trustDomain = trustDomain;
                return this;
            }

            public Builder addTrustDomainBundle(String domain, X509Bundle bundle) {
                this.trustDomainBundles.put(TrustDomain.parse(domain), bundle);
                return this;
            }

            public MockX509Source build() throws Exception {
                // Create a self-signed certificate for testing
                KeyPair keyPair = TestSslUtils.generateKeyPair("RSA");
                TestSslUtils.CertificateBuilder certificateBuilder = new TestSslUtils.CertificateBuilder(30, "SHA1withRSA");
                X509Certificate cert = certificateBuilder
                    .sanDnsNames("spiffe://" + trustDomain + "/" + path)
                    .generate("CN=" + cn + ", O=" + organization, keyPair);
                
                // Create mock SVID
                X509Svid svid = mock(X509Svid.class);
                when(svid.getSpiffeId()).thenReturn(SpiffeId.parse("spiffe://" + trustDomain + "/" + path));
                when(svid.getChainArray()).thenReturn(Collections.singletonList(cert).toArray(new X509Certificate[0]));
                when(svid.getPrivateKey()).thenReturn(keyPair.getPrivate());

                // Create bundles map starting with the default bundle
                Map<TrustDomain, X509Bundle> bundles = new HashMap<>(trustDomainBundles);
                
                // Add default bundle for the main trust domain if not already present
                if (!bundles.containsKey(TrustDomain.parse(trustDomain))) {
                    X509Bundle defaultBundle = mock(X509Bundle.class);
                    when(defaultBundle.getX509Authorities()).thenReturn(Collections.singleton(cert));
                    bundles.put(TrustDomain.parse(trustDomain), defaultBundle);
                }

                return new MockX509Source(svid, bundles);
            }
        }

        @Override
        public X509Svid getX509Svid() {
            return svid;
        }

        @Override
        public X509Bundle getBundleForTrustDomain(TrustDomain trustDomain) {
            return bundles.get(trustDomain);
        }

        @Override
        public void close() {
            // No resources to clean up
        }

        public void addTrustDomainBundle(String domain, X509Bundle bundle) {
            this.bundles.put(TrustDomain.parse(domain), bundle);
        }
    }

    private Server setupServer(int port, boolean enableMtls) throws Exception {
        Properties props = new Properties();
        props.setProperty(RestConfig.LISTENERS_CONFIG, "https://localhost:" + port);
        props.setProperty(RestConfig.SNI_HOST_CHECK_ENABLED_CONFIG, "false");
        props.setProperty(RestConfig.SSL_SPIRE_ENABLED_CONFIG, "true");
        if (enableMtls) {
            props.setProperty(RestConfig.SSL_CLIENT_AUTHENTICATION_CONFIG, RestConfig.SSL_CLIENT_AUTHENTICATION_REQUIRED);
        }

        TestRestConfig config = new TestRestConfig(props);
        TestApp app = new TestApp(config, this.serverX509Source);
        Server server = app.createServer();
        server.start();
        return server;
    }

    @BeforeEach
    public void setUp() throws Exception {
        // Setup application with mock X509Source
        this.serverX509Source = new MockX509Source.Builder()
            .trustDomain(SERVER_TRUST_DOMAIN)
            .build();
        
        // Setup MTLS server
        mtlsServer = setupServer(MTLS_SERVER_PORT, true);
        
        // Setup TLS server
        tlsServer = setupServer(TLS_SERVER_PORT, false);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (mtlsServer != null) {
            mtlsServer.stop();
            mtlsServer.join();
        }
        if (tlsServer != null) {
            tlsServer.stop();
            tlsServer.join();
        }
    }

    private String buildUrl(int port, String endpoint, String name) {
        return BASE_URL + port + endpoint + "?name=" + name;
    }

    @Test
    public void testMTLsServerWithTrustedClients() throws Exception {
        String clientTrustDomain = "authorized-client.domain";
        // Create a client that trusts the server's bundle
        MockX509Source trustedClientSource = new MockX509Source.Builder()
            .trustDomain(clientTrustDomain)
            .addTrustDomainBundle(SERVER_TRUST_DOMAIN, this.serverX509Source.getBundleForTrustDomain(TrustDomain.parse(SERVER_TRUST_DOMAIN)))
            .build();

        // Add the client's bundle to the server's trust bundles
        this.serverX509Source.addTrustDomainBundle(clientTrustDomain, 
            trustedClientSource.getBundleForTrustDomain(TrustDomain.parse(clientTrustDomain)));
        
        String name = "trusted-client";
        String response = callHelloEndpoint(buildUrl(MTLS_SERVER_PORT, "/hello", name), trustedClientSource);
        assertTrue(response.contains(name));
    }

    @Test
    public void testMTLsServerRejectsUntrustedClients() throws Exception {
        // Create a client that trusts the server's bundle
        X509Source untrustedClientSource = new MockX509Source.Builder()
            .trustDomain("unauthorized.domain")
            .addTrustDomainBundle(SERVER_TRUST_DOMAIN, this.serverX509Source.getBundleForTrustDomain(TrustDomain.parse(SERVER_TRUST_DOMAIN)))
            .build();

        // Attempt to call server with untrusted client
        try {
            callHelloEndpoint(buildUrl(MTLS_SERVER_PORT, "/hello", "untrusted-client"), untrustedClientSource);
            // If we get here, the test should fail as we expect an exception
            assertTrue(false, "Expected connection to be rejected due to untrusted certificate");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("Broken pipe"),
                "Expected certificate chain verification error, got: " + e.getMessage());
        }
    }

    @Test
    public void testTLSServerAcceptsUntrustedClients() throws Exception {
        // Create a client that trusts the server's bundle
        X509Source untrustedClientSource = new MockX509Source.Builder()
            .trustDomain("unauthorized.domain")
            .addTrustDomainBundle(SERVER_TRUST_DOMAIN, this.serverX509Source.getBundleForTrustDomain(TrustDomain.parse(SERVER_TRUST_DOMAIN)))
            .build();

        // Call TLS server with untrusted client - should succeed since mTLS is disabled
        String name = "untrusted-client";
        String response = callHelloEndpoint(buildUrl(TLS_SERVER_PORT, "/hello", name), untrustedClientSource);
        assertTrue(response.contains(name), 
            "Expected successful response from TLS server with untrusted client");
    }

    @Test
    public void testAppCreateServerFailsWhenSpireEnabledButNoX509Source() throws Exception {
        Properties props = new Properties();
        props.setProperty(RestConfig.LISTENERS_CONFIG, "https://localhost:9999");
        props.setProperty(RestConfig.SSL_SPIRE_ENABLED_CONFIG, "true");
        
        TestRestConfig config = new TestRestConfig(props);
        TestApp app = new TestApp(config);
        
        try {
            app.createServer();
            assertTrue(false, "Expected RuntimeException when SPIRE is enabled but no X509Source is provided");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("X509Source must be provided when SPIRE SSL is enabled"),
                "Expected error message about missing X509Source, got: " + e.getMessage());
        }
    }

    private String callHelloEndpoint(String serverUrl, X509Source x509Source) throws Exception {
        SSLContext sslContext = buildSpiffeSslContext(x509Source);
        HttpsURLConnection conn = (HttpsURLConnection) new URL(serverUrl).openConnection();
        conn.setHostnameVerifier((hostname, session) -> true); // disables hostname check
        conn.setSSLSocketFactory(sslContext.getSocketFactory());
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", MediaType.TEXT_PLAIN);

        int responseCode = conn.getResponseCode();
        System.out.println("Response Code: " + responseCode);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream()))) {
            String response = reader.readLine();
            System.out.println("Response: " + response);
            return response;
        }
    }

    private SSLContext buildSpiffeSslContext(X509Source x509Source) throws Exception {
        SpiffeSslContextFactory.SslContextOptions options = SpiffeSslContextFactory.SslContextOptions
                .builder()
                .x509Source(x509Source)
                .acceptAnySpiffeId()
                .build();

        SSLContext sslContext = SpiffeSslContextFactory.getSslContext(options);
        return sslContext;
    }

    private static class TestApp extends Application<TestRestConfig> {
        public TestApp(TestRestConfig config) {
            super(config);
        }

        public TestApp(TestRestConfig config, X509Source x509Source) {
            super(config, x509Source);
        }

        @Override
        public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
            config.register(HelloResource.class);
        }
    }

    @Path("/")
    @Produces(MediaType.TEXT_PLAIN)
    public static class HelloResource {
        @GET
        @Path("/hello")
        public String hello(@QueryParam("name") String name) {
            return "Hello, " + name + "!";
        }
    }
}