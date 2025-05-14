package io.confluent.rest;

import io.spiffe.svid.x509svid.X509Svid;
import io.spiffe.spiffeid.SpiffeId;

import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import java.net.Socket;
import java.security.cert.X509Certificate;
import java.util.Set;

import java.util.List;
import java.util.Collection;


public class SpiffeTrustManager extends X509ExtendedTrustManager {

    private final Set<SpiffeId> allowedIds;

    public SpiffeTrustManager(Set<SpiffeId> allowedIds) {
        this.allowedIds = allowedIds;
    }

    private void validate(X509Certificate[] chain) {
        try {
            X509Certificate leafCert = chain[0];  // SPIFFE ID is in the leaf cert

            Collection<List<?>> sans = leafCert.getSubjectAlternativeNames();
            if (sans == null) {
                throw new SecurityException("No Subject Alternative Names in client certificate");
            }

            SpiffeId spiffeId = null;
            for (List<?> sanEntry : sans) {
                Integer type = (Integer) sanEntry.get(0);
                if (type == 6) { // 6 = URI
                    String uriSan = (String) sanEntry.get(1);
                    spiffeId = SpiffeId.parse(uriSan);
                    if (!this.allowedIds.contains(spiffeId)) {
                        throw new SecurityException("SPIFFE ID not allowed: " + spiffeId.toString());
                    }

                    break;
                }
            }
        } catch (Exception e) {
            throw new SecurityException("Invalid or unauthorized client certificate", e);
        }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
        validate(chain);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        validate(chain);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) {
        validate(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) {
        // Not needed for server-side
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {}

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }
}
