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
import java.util.Set;
import java.util.Collections;

import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import java.util.Properties;
import org.eclipse.jetty.server.Server;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Configurable;
import org.apache.kafka.test.TestSslUtils;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SpireTest {

    private Server server1;
    private Server server2;
    private X509Source x509Source;

    // Mock implementation of X509Source for testing
    private static class MockX509Source implements X509Source {
        private final X509Svid svid;
        private final X509Bundle bundle;

        private MockX509Source(X509Svid svid, X509Bundle bundle) {
            this.svid = svid;
            this.bundle = bundle;
        }

        public static class Builder {
            private String trustDomain = "test.domain";
            private String path = "test";
            private String cn = "test.local";
            private String organization = "Test";
            private Set<X509Certificate> trustedCerts;

            public Builder trustDomain(String trustDomain) {
                this.trustDomain = trustDomain;
                return this;
            }

            public Builder path(String path) {
                this.path = path;
                return this;
            }

            public Builder cn(String cn) {
                this.cn = cn;
                return this;
            }

            public Builder trustedCerts(Set<X509Certificate> trustedCerts) {
                this.trustedCerts = trustedCerts;
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

                // Create mock bundle
                X509Bundle bundle = mock(X509Bundle.class);
                when(bundle.getX509Authorities()).thenReturn(trustedCerts != null ? trustedCerts : Collections.singleton(cert));

                return new MockX509Source(svid, bundle);
            }
        }

        @Override
        public X509Svid getX509Svid() {
            return svid;
        }

        @Override
        public X509Bundle getBundleForTrustDomain(TrustDomain trustDomain) {
            if (trustDomain.toString().equals("test.domain")) {
                return bundle;
            }
            return null;
        }

        @Override
        public void close() {
            // No resources to clean up
        }
    }

    private Server setupServer(int port, boolean enableMtls) throws Exception {
        Properties props = new Properties();
        props.setProperty(RestConfig.LISTENERS_CONFIG, "https://localhost:" + port);
        props.setProperty(RestConfig.SNI_HOST_CHECK_ENABLED_CONFIG, "false");
        props.setProperty(RestConfig.SSL_IS_SPIRE_ENABLED_CONFIG, "true");
        props.setProperty(RestConfig.SSL_SPIRE_MTLS_CONFIG, String.valueOf(enableMtls));
        
        TestRestConfig config = new TestRestConfig(props);
        TestApp app = new TestApp(config, this.x509Source);
        Server server = app.createServer();
        server.start();
        return server;
    }

    @BeforeEach
    public void setUp() throws Exception {
        // Setup application with mock X509Source
        this.x509Source = new MockX509Source.Builder().build();
        
        // Setup server1 with mTLS enabled
        server1 = setupServer(9876, true);
        
        // Setup server2 with mTLS disabled
        server2 = setupServer(9877, false);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (server1 != null) {
            server1.stop();
            server1.join();
        }
        if (server2 != null) {
            server2.stop();
            server2.join();
        }
    }

    @Test
    public void testServerWithSpiffeMtlsClient() throws Exception {
        String response = callServer("https://localhost:9876/hello?name=fff", this.x509Source);
        assertTrue(response.contains("Hello, fff!"));
    }

    @Test
    public void testMTLsServerRejectsUntrustedClients() throws Exception {
        // Create a client with a certificate from a different trust domain
        // This server will not have bundle for unauthorized.domain, so it will reject the client
        X509Source untrustedClientSource = new MockX509Source.Builder()
            .trustDomain("unauthorized.domain")  // Different trust domain
            .path("unauthorized")
            .cn("unauthorized.local")
            .trustedCerts(Collections.singleton(this.x509Source.getX509Svid().getChainArray()[0]))
            .build();

        // Attempt to call server with untrusted client
        try {
            callServer("https://localhost:9876/hello?name=fff", untrustedClientSource);
            // If we get here, the test should fail as we expect an exception
            assertTrue(false, "Expected connection to be rejected due to untrusted certificate");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("Broken pipe"),
                "Expected certificate chain verification error, got: " + e.getMessage());
        }
    }

    @Test
    public void testTLSAcceptsUntrustedClients() throws Exception {
        // Create a client with a certificate from a different trust domain
        X509Source untrustedClientSource = new MockX509Source.Builder()
            .trustDomain("unauthorized.domain")  // Different trust domain
            .path("unauthorized")
            .cn("unauthorized.local")
            .trustedCerts(Collections.singleton(this.x509Source.getX509Svid().getChainArray()[0]))
            .build();

        // Call server2 with untrusted client - should succeed since mTLS is disabled
        String response = callServer("https://localhost:9877/hello?name=fff", untrustedClientSource);
        assertTrue(response.contains("Hello, fff!"), 
            "Expected successful response from server2 with untrusted client");
    }

    private String callServer(String serverUrl, X509Source x509Source) throws Exception {
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