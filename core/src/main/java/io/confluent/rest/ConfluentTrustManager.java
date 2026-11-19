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

import io.spiffe.provider.SpiffeTrustManager;
import io.spiffe.svid.x509svid.X509Svid;
import io.spiffe.workloadapi.X509Source;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side trust manager that validates a client certificate in up to two steps, in order:
 *
 * <ol>
 *   <li>SPIRE: the chain is a valid SPIFFE SVID chaining to the SPIRE bundle.</li>
 *   <li>Server cert as client auth: some certificate in the chain has a DNS SAN ending with one
 *       of the configured suffixes, and the chain validates as a server certificate against the
 *       JVM default truststore. This accepts certificates that lack the clientAuth EKU.</li>
 * </ol>
 *
 * <p>Each step is enabled separately. If no enabled step passes, the chain must still have a
 * DNS SAN ending with one of the configured suffixes and pass the JVM default client-cert check.
 * The structure mirrors the broker's ConfluentTrustManager, without the revoked-certificate list.
 */
final class ConfluentTrustManager extends X509ExtendedTrustManager {

  private static final Logger log = LoggerFactory.getLogger(ConfluentTrustManager.class);
  // index for DNS is 2 as per X509Certificate.getSubjectAlternativeNames javadocs
  private static final Integer DNS = 2;

  private final X509ExtendedTrustManager defaultTrustManager;
  private final boolean spireServersideValidationEnabled;
  private final boolean serverCertForClientAuthServersideEnabled;
  private final List<String> serverCertSanSuffixes;
  private final List<String> advertisedIssuerPatterns;
  private final X509Source x509Source;
  private X509ExtendedTrustManager spiffeTrustManager;

  ConfluentTrustManager(X509Source x509Source,
                        boolean spireServersideValidationEnabled,
                        boolean serverCertForClientAuthServersideEnabled,
                        List<String> serverCertSanSuffixes,
                        List<String> advertisedIssuerPatterns) {
    this.defaultTrustManager = createDefaultTrustManager();
    this.spireServersideValidationEnabled = spireServersideValidationEnabled;
    this.serverCertForClientAuthServersideEnabled = serverCertForClientAuthServersideEnabled;
    this.serverCertSanSuffixes = List.copyOf(serverCertSanSuffixes);
    this.advertisedIssuerPatterns = List.copyOf(advertisedIssuerPatterns);
    this.x509Source = x509Source;
    this.initSpire();
  }

  private static X509ExtendedTrustManager createDefaultTrustManager() {
    try {
      TrustManagerFactory tmf =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init((KeyStore) null);
      for (TrustManager tm : tmf.getTrustManagers()) {
        if (tm instanceof X509ExtendedTrustManager) {
          return (X509ExtendedTrustManager) tm;
        }
      }
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to create the default trust manager", e);
    }
    throw new IllegalStateException("No X509ExtendedTrustManager in the default trust managers");
  }

  private void initSpire() {
    if (!isSpireConfigEnabled()) {
      log.debug("SPIRE mTLS validation is not enabled");
      return;
    }

    // The broker checks broker.session.uuid here. For the HTTP server, ce-kafka does that check
    // and passes a null X509Source when it is not set.
    if (x509Source == null) {
      log.warn("SPIRE mTLS validation can not be enabled because X509Source is not provided");
      return;
    }

    // The broker checks SpireClientFactory.hasInstance here. The X509Source handed in may resolve
    // lazily and throw IllegalStateException while the SPIRE client is not yet available.
    try {
      x509Source.getX509Svid();
    } catch (IllegalStateException e) {
      log.debug("SPIRE client is not yet available. Will retry on next access. {}",
          e.getMessage());
      return;
    }

    try {
      // Same as the broker's SPIRE client with no SPIFFE ID allowlist: accept any SPIFFE ID.
      spiffeTrustManager = new SpiffeTrustManager(x509Source);
      log.debug("SPIRE initialized successfully in ConfluentTrustManager.");
    } catch (Exception e) {
      log.warn("Failed to initialize SPIRE in ConfluentTrustManager. SPIRE validation will be "
          + "skipped; falling back to server certificate authentication. Error: {}",
          e.getMessage(), e);
    }
  }

  private boolean isSpireConfigEnabled() {
    return spireServersideValidationEnabled;
  }

  /**
   * Checks if SPIRE is both configured AND successfully initialized.
   */
  private boolean isSpireEnabled() {
    // Lazy retry: the SPIRE client may not have been available when this TrustManager was
    // constructed. Retry initSpire() so that the first TLS handshake after it becomes available
    // picks it up.
    if (isSpireConfigEnabled() && spiffeTrustManager == null) {
      initSpire();
    }
    boolean enabled = isSpireConfigEnabled() && spiffeTrustManager != null;
    log.debug("isSpireEnabled in TrustManager: {}, {}", enabled, spiffeTrustManager);
    return enabled;
  }

  private boolean acceptServerCertForClientAuth() {
    return serverCertForClientAuthServersideEnabled;
  }

  @Override
  public void checkClientTrusted(X509Certificate[] x509Certificates, String authType, Socket socket)
      throws CertificateException {
    if (socket instanceof SSLSocket) {
      // SPIRE and the server-cert fallback apply only if client authentication is needed
      // (required or requested), as on the broker.
      if (isClientAuthNeeded(socket)) {
        if (trySpireValidation(x509Certificates, authType, socket)) {
          return;
        }
        if (tryServerCertAsClientAuthValidation(x509Certificates, authType, socket)) {
          return;
        }
        verifyClientCerts(x509Certificates);
      }
    } else {
      if (trySpireValidation(x509Certificates, authType, socket)) {
        return;
      }
      if (tryServerCertAsClientAuthValidation(x509Certificates, authType, socket)) {
        return;
      }
      verifyClientCerts(x509Certificates);
    }
    defaultTrustManager.checkClientTrusted(x509Certificates, authType, socket);
  }

  @Override
  public void checkClientTrusted(X509Certificate[] x509Certificates, String authType,
                                 SSLEngine sslEngine) throws CertificateException {
    // SPIRE and the server-cert fallback apply only if client authentication is needed
    // (required or requested), as on the broker.
    if (isClientAuthNeeded(sslEngine)) {
      if (trySpireValidation(x509Certificates, authType, sslEngine)) {
        return;
      }
      if (tryServerCertAsClientAuthValidation(x509Certificates, authType, sslEngine)) {
        return;
      }
      verifyClientCerts(x509Certificates);
    }
    defaultTrustManager.checkClientTrusted(x509Certificates, authType, sslEngine);
  }

  @Override
  public void checkClientTrusted(X509Certificate[] x509Certificates, String authType)
      throws CertificateException {
    if (trySpireValidation(x509Certificates, authType)) {
      return;
    }
    if (tryServerCertAsClientAuthValidation(x509Certificates, authType)) {
      return;
    }
    verifyClientCerts(x509Certificates);
    defaultTrustManager.checkClientTrusted(x509Certificates, authType);
  }

  private boolean isClientAuthNeeded(Socket socket) {
    SSLSocket sslSocket = (SSLSocket) socket;
    return sslSocket.getNeedClientAuth() || sslSocket.getWantClientAuth();
  }

  private boolean isClientAuthNeeded(SSLEngine engine) {
    return engine.getNeedClientAuth() || engine.getWantClientAuth();
  }

  private boolean trySpireValidation(X509Certificate[] chain, String authType, Socket socket) {
    if (!this.isSpireEnabled()) {
      return false;
    }
    try {
      spiffeTrustManager.checkClientTrusted(chain, authType, socket);
      log.debug("Client certificate validated successfully via SPIRE for socket connection");
      return true;
    } catch (CertificateException e) {
      log.debug("SPIRE validation failed for socket connection: {}", e.getMessage());
      return false;
    }
  }

  private boolean trySpireValidation(X509Certificate[] chain, String authType, SSLEngine engine) {
    if (!this.isSpireEnabled()) {
      return false;
    }
    try {
      spiffeTrustManager.checkClientTrusted(chain, authType, engine);
      log.debug("Client certificate validated successfully via SPIRE for SSLEngine connection");
      return true;
    } catch (CertificateException e) {
      log.debug("SPIRE validation failed for SSLEngine connection: {}", e.getMessage());
      return false;
    }
  }

  private boolean trySpireValidation(X509Certificate[] chain, String authType) {
    if (!this.isSpireEnabled()) {
      return false;
    }
    try {
      spiffeTrustManager.checkClientTrusted(chain, authType);
      log.debug("Client certificate validated successfully via SPIRE");
      return true;
    } catch (CertificateException e) {
      log.debug("SPIRE validation failed for connection: {}", e.getMessage());
      return false;
    }
  }

  private boolean tryServerCertAsClientAuthValidation(X509Certificate[] x509Certificates,
                                                      String authType, Socket socket) {
    if (this.acceptServerCertForClientAuth()) {
      try {
        verifyServerCerts(x509Certificates);
        defaultTrustManager.checkServerTrusted(x509Certificates, authType, socket);
        log.debug("Server certificate accepted for client authentication (socket)");
        return true;
      } catch (CertificateException e) {
        log.debug("Server cert as client auth validation failed for socket: {}", e.getMessage());
        return false;
      }
    }
    return false;
  }

  private boolean tryServerCertAsClientAuthValidation(X509Certificate[] x509Certificates,
                                                      String authType, SSLEngine engine) {
    if (this.acceptServerCertForClientAuth()) {
      try {
        verifyServerCerts(x509Certificates);
        defaultTrustManager.checkServerTrusted(x509Certificates, authType, engine);
        log.debug("Server certificate accepted for client authentication (SSLEngine)");
        return true;
      } catch (CertificateException e) {
        log.debug("Server cert as client auth validation failed for SSLEngine: {}",
            e.getMessage());
        return false;
      }
    }
    return false;
  }

  private boolean tryServerCertAsClientAuthValidation(X509Certificate[] x509Certificates,
                                                      String authType) {
    if (this.acceptServerCertForClientAuth()) {
      try {
        verifyServerCerts(x509Certificates);
        defaultTrustManager.checkServerTrusted(x509Certificates, authType);
        log.debug("Server certificate accepted for client authentication");
        return true;
      } catch (CertificateException e) {
        log.debug("Server cert as client auth validation failed: {}", e.getMessage());
        return false;
      }
    }
    return false;
  }

  // Not expected on a server-side listener; same as the broker for consistency.
  @Override
  public void checkServerTrusted(X509Certificate[] x509Certificates, String authType,
                                 Socket socket) throws CertificateException {
    verifyServerCerts(x509Certificates);
    defaultTrustManager.checkServerTrusted(x509Certificates, authType, socket);
  }

  @Override
  public void checkServerTrusted(X509Certificate[] x509Certificates, String authType,
                                 SSLEngine sslEngine) throws CertificateException {
    verifyServerCerts(x509Certificates);
    defaultTrustManager.checkServerTrusted(x509Certificates, authType, sslEngine);
  }

  @Override
  public void checkServerTrusted(X509Certificate[] x509Certificates, String authType)
      throws CertificateException {
    verifyServerCerts(x509Certificates);
    defaultTrustManager.checkServerTrusted(x509Certificates, authType);
  }

  /**
   * Returns the CAs advertised in the CertificateRequest: default-truststore CAs whose subject
   * matches one of the advertised issuer patterns, plus the SPIRE CA when SPIRE is enabled.
   */
  @Override
  public X509Certificate[] getAcceptedIssuers() {
    X509Certificate[] allDefaultIssuers = defaultTrustManager.getAcceptedIssuers();
    X509Certificate[] letsEncryptIssuers = filterLetsEncryptIssuers(allDefaultIssuers);

    if (!this.isSpireEnabled()) {
      return letsEncryptIssuers;
    }

    X509Svid svid = x509Source.getX509Svid();
    if (svid == null) {
      log.warn("getAcceptedIssuers: SPIRE X509Svid is null, returning Let's Encrypt issuers");
      return letsEncryptIssuers;
    }

    // Chain order: [leaf, intermediate..., root]
    List<X509Certificate> chain = svid.getChain();
    if (chain == null || chain.isEmpty()) {
      log.debug("getAcceptedIssuers: SPIRE certificate chain is empty");
      return letsEncryptIssuers;
    }

    // The last certificate in the chain is the root CA
    X509Certificate caCert = chain.get(chain.size() - 1);

    // Merge SPIRE CA issuer with default issuers
    // This allows clients to present either SPIRE certificates or Let's Encrypt certificates
    X509Certificate[] spireIssuers = new X509Certificate[] {caCert};
    return mergeIssuers(letsEncryptIssuers, spireIssuers);
  }

  private X509Certificate[] mergeIssuers(X509Certificate[] issuers1, X509Certificate[] issuers2) {
    if (issuers1 == null || issuers1.length == 0) {
      return issuers2 != null ? issuers2 : new X509Certificate[0];
    }
    if (issuers2 == null || issuers2.length == 0) {
      return issuers1;
    }

    X509Certificate[] merged = new X509Certificate[issuers1.length + issuers2.length];
    System.arraycopy(issuers1, 0, merged, 0, issuers1.length);
    System.arraycopy(issuers2, 0, merged, issuers1.length, issuers2.length);
    return merged;
  }

  private X509Certificate[] filterLetsEncryptIssuers(X509Certificate[] issuers) {
    if (issuers == null || issuers.length == 0) {
      return new X509Certificate[0];
    }
    List<X509Certificate> result = new ArrayList<>();
    for (X509Certificate cert : issuers) {
      String subjectDn = cert.getSubjectX500Principal().getName();
      if (advertisedIssuerPatterns.stream().anyMatch(subjectDn::contains)) {
        result.add(cert);
      }
    }
    return result.toArray(new X509Certificate[0]);
  }

  private boolean verifyClientCerts(X509Certificate[] x509Certificates)
      throws CertificateException {
    return verifyCerts(x509Certificates, true);
  }

  private boolean verifyServerCerts(X509Certificate[] x509Certificates)
      throws CertificateException {
    return verifyCerts(x509Certificates, false);
  }

  // Mirrors ConfluentTrustManagerHelper.verifyCerts, without the revoked-certificate check and
  // the client-cert expiry metric.
  private boolean verifyCerts(X509Certificate[] x509Certificates, boolean isClientCert)
      throws CertificateException {
    for (X509Certificate cert : x509Certificates) {
      if (isConfluentCert(cert)) {
        return true;
      }
    }
    throw new CertificateException(String.format(
        "A trusted %s certificate not found", isClientCert ? "client" : "server"));
  }

  private boolean isConfluentCert(X509Certificate cert) {
    for (String altName : getSubjectAltNames(cert)) {
      if (hasCCloudHostPattern(altName)) {
        return true;
      }
    }
    return false;
  }

  private static List<String> getSubjectAltNames(X509Certificate cert) {
    try {
      Collection<List<?>> altNames = cert.getSubjectAlternativeNames();
      if (altNames == null) {
        return Collections.emptyList();
      }
      List<String> result = new ArrayList<>();
      for (List<?> altName : altNames) {
        Object type = altName.size() >= 2 ? altName.get(0) : null;
        if (DNS.equals(type) && altName.get(1) instanceof String) {
          result.add((String) altName.get(1));
        }
      }
      return result;
    } catch (CertificateParsingException ce) {
      log.trace("Ignoring certificate parsing exception", ce);
      return Collections.emptyList();
    }
  }

  private boolean hasCCloudHostPattern(String host) {
    if (host == null) {
      return false;
    }
    return serverCertSanSuffixes.stream()
        .anyMatch(pattern -> host.toLowerCase(Locale.ROOT).endsWith(pattern));
  }
}
