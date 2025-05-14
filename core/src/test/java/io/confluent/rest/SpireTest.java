package io.confluent.rest;

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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class SpireTest {

    @Test
    public void testServerWithSpiffeMtlsClient() throws Exception {
        String response = callServer("https://localhost:8080/hello?name=fff");
        
        assertTrue(response.contains("Hello, fff!"));
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
            conn.setRequestProperty("Accept", "application/vnd.hello.v1+json");

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
        // Create keystore for client identity
        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, null);
        keyStore.setKeyEntry("svid-key", svid.getPrivateKey(), "changeit".toCharArray(),
                svid.getChain().toArray(new X509Certificate[0]));

        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(keyStore, "changeit".toCharArray());

        // Create truststore for server validation
        TrustDomain trustDomain = svid.getSpiffeId().getTrustDomain();
        X509Bundle bundle = x509Source.getBundleForTrustDomain(trustDomain);

        KeyStore trustStore = KeyStore.getInstance("JKS");
        trustStore.load(null, null);
        int i = 0;
        for (X509Certificate cert : bundle.getX509Authorities()) {
            trustStore.setCertificateEntry("trust-" + i, cert);
            i++;
        }

        // Create custom trust manager that validates SPIFFE IDs
        TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
        tmf.init(trustStore);
        TrustManager[] trustManagers = new TrustManager[] {
                new SpiffeTrustManager(Set.of(
                        SpiffeId.parse("spiffe://example.org/test-workload222"),
                        SpiffeId.parse("spiffe://example.org/client2")
                ))
        };

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), trustManagers, null);
        return sslContext;
    }
}