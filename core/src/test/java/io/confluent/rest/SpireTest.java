package io.confluent.rest;

import io.spiffe.provider.SpiffeSslContextFactory;
import io.spiffe.spiffeid.SpiffeId;
import io.spiffe.spiffeid.TrustDomain;
import io.spiffe.svid.x509svid.X509Svid;
import io.spiffe.workloadapi.DefaultX509Source;
import io.spiffe.workloadapi.X509Source;
import io.spiffe.bundle.x509bundle.X509Bundle;
import io.spiffe.bundle.BundleSource;
import org.mockito.Mockito;

import javax.net.ssl.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
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
    private TestApp application1;
    private X509Source x509Source;

    // Mock implementation of X509Source for testing
    private static class MockX509Source implements X509Source {
        private final X509Svid svid;
        private final X509Bundle bundle;

        public MockX509Source() throws Exception {
            // Create a self-signed certificate for testing
            KeyPair keyPair = TestSslUtils.generateKeyPair("RSA");
            TestSslUtils.CertificateBuilder certificateBuilder = new TestSslUtils.CertificateBuilder(30, "SHA1withRSA");
            X509Certificate cert = certificateBuilder
                .sanDnsNames("spiffe://test.domain/test")
                .generate("CN=test.local, O=Test", keyPair);
            
            // Create mock SVID
            this.svid = mock(X509Svid.class);
            when(svid.getSpiffeId()).thenReturn(SpiffeId.parse("spiffe://test.domain/test"));
            when(svid.getChainArray()).thenReturn(Collections.singletonList(cert).toArray(new X509Certificate[0]));
            when(svid.getPrivateKey()).thenReturn(keyPair.getPrivate());

            // Create mock bundle
            this.bundle = mock(X509Bundle.class);
            when(bundle.getX509Authorities()).thenReturn(Collections.singleton(cert));
        }

        @Override
        public X509Svid getX509Svid() {
            return svid;
        }

        @Override
        public X509Bundle getBundleForTrustDomain(TrustDomain trustDomain) {
            return bundle;
        }

        @Override
        public void close() {
            // No resources to clean up
        }
    }

    @BeforeEach
    public void setUp() throws Exception {
        // Setup application with mock X509Source
        Properties props1 = new Properties();
        props1.setProperty(RestConfig.LISTENERS_CONFIG, "https://localhost:8080");
        props1.setProperty(RestConfig.SNI_HOST_CHECK_ENABLED_CONFIG, "false");
        props1.setProperty(RestConfig.SSL_IS_SPIRE_ENABLED_CONFIG, "true");
        
        TestRestConfig config1 = new TestRestConfig(props1);
        this.x509Source = new MockX509Source();
        application1 = new TestApp(config1, this.x509Source);
        server1 = application1.createServer();
        server1.start();
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (server1 != null) {
            server1.stop();
            server1.join();
        }
    }

    @Test
    public void testServerWithSpiffeMtlsClient() throws Exception {
        String response = callServer("https://localhost:8080/hello?name=fff");
        assertTrue(response.contains("Hello, fff!"));
    }

    private String callServer(String serverUrl) throws Exception {
        SSLContext sslContext = buildSpiffeSslContext(this.x509Source);
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
                .x509Source(this.x509Source)
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