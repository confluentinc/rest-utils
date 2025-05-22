package io.confluent.rest;

import io.spiffe.provider.SpiffeSslContextFactory;
import io.spiffe.spiffeid.SpiffeId;
import io.spiffe.spiffeid.TrustDomain;
import io.spiffe.svid.x509svid.X509Svid;
import io.spiffe.workloadapi.DefaultX509Source;
import io.spiffe.workloadapi.X509Source;
import io.spiffe.bundle.x509bundle.X509Bundle;

import javax.net.ssl.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Set;

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

import static org.junit.jupiter.api.Assertions.assertTrue;

public class SpireTest {

    private Server server1;
    private Server server2;
    private TestApp application1;
    private TestApp application2;

    @BeforeEach
    public void setUp() throws Exception {
        // Setup first application with default initialization
        // The first application is initialized with the X509Source initialized by the ApplicationServer
        Properties props1 = new Properties();
        props1.setProperty(RestConfig.LISTENERS_CONFIG, "https://localhost:8080");
        props1.setProperty(RestConfig.SNI_HOST_CHECK_ENABLED_CONFIG, "false");
        props1.setProperty(RestConfig.SSL_IS_SPIRE_ENABLED_CONFIG, "true");
        props1.setProperty(RestConfig.SSL_SPIRE_AGENT_SOCKET_PATH_CONFIG, "tcp://127.0.0.1:31523");
        
        TestRestConfig config1 = new TestRestConfig(props1);
        application1 = new TestApp(config1);
        server1 = application1.createServer();
        server1.start();

        // Setup second application with explicit X509Source
        DefaultX509Source.X509SourceOptions x509SourceOptions = DefaultX509Source.X509SourceOptions
                .builder()
                .spiffeSocketPath("tcp://127.0.0.1:31523")
                .svidPicker(list -> list.get(list.size()-1))
                .build();

        X509Source x509Source = DefaultX509Source.newSource(x509SourceOptions);
        Properties props2 = new Properties();
        props2.setProperty(RestConfig.LISTENERS_CONFIG, "https://localhost:8081");
        props2.setProperty(RestConfig.SNI_HOST_CHECK_ENABLED_CONFIG, "false");
        props2.setProperty(RestConfig.SSL_IS_SPIRE_ENABLED_CONFIG, "true");
        props2.setProperty(RestConfig.SSL_SPIRE_AGENT_SOCKET_PATH_CONFIG, "tcp://127.0.0.1:31523");

        TestRestConfig config2 = new TestRestConfig(props2);
        application2 = new TestApp(config2, x509Source);
        server2 = application2.createServer();
        server2.start();
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
        String response1 = callServer("https://localhost:8080/hello?name=fff");
        assertTrue(response1.contains("Hello, fff!"));

        String response2 = callServer("https://localhost:8081/hello?name=fff");
        assertTrue(response2.contains("Hello, fff!"));
    }

    private String callServer(String serverUrl) throws Exception {
        DefaultX509Source.X509SourceOptions x509SourceOptions = DefaultX509Source.X509SourceOptions
                .builder()
                .spiffeSocketPath("tcp://127.0.0.1:31523")
                .svidPicker(list -> list.get(list.size()-1))
                .build();

        try (X509Source x509Source = DefaultX509Source.newSource(x509SourceOptions)) {
            X509Svid svid = x509Source.getX509Svid();

            SSLContext sslContext = buildSpiffeSslContext(x509Source, svid);
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
    }

    private SSLContext buildSpiffeSslContext(X509Source x509Source, X509Svid svid) throws Exception {
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