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

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Trust manager for SPIRE trust-only listeners that accepts either a SPIFFE SVID (validated
 * against the SPIFFE trust bundle) or a traditional certificate (validated against the
 * listener's configured, or JVM default, trust store). Which path applies is decided by
 * inspecting the presented leaf certificate for a {@code spiffe://} URI SAN, so a certificate
 * that does carry one is held to the stricter SPIFFE validation rather than silently falling
 * back to the legacy path if that validation fails. This lets a listener adopt trust-only mode
 * before every client has been switched to present a SPIFFE SVID.
 */
final class DualTrustManager extends X509ExtendedTrustManager {

  private static final Logger log = LoggerFactory.getLogger(DualTrustManager.class);

  // From the GeneralName ASN.1 CHOICE in RFC 5280 4.2.1.6: type 6 is uniformResourceIdentifier.
  private static final int URI_SAN_TYPE = 6;
  private static final String SPIFFE_URI_SCHEME = "spiffe://";

  private final X509ExtendedTrustManager spiffeTrustManager;
  private final X509ExtendedTrustManager legacyTrustManager;

  private DualTrustManager(X509ExtendedTrustManager spiffeTrustManager,
      X509ExtendedTrustManager legacyTrustManager) {
    this.spiffeTrustManager = Objects.requireNonNull(spiffeTrustManager, "spiffeTrustManager");
    this.legacyTrustManager = Objects.requireNonNull(legacyTrustManager, "legacyTrustManager");
  }

  /**
   * Wraps the first {@link X509ExtendedTrustManager} found in each array into a single
   * dual-mode trust manager.
   */
  static TrustManager[] wrap(TrustManager[] spiffeTrustManagers,
      TrustManager[] legacyTrustManagers) {
    return new TrustManager[] {
        new DualTrustManager(
            firstX509(spiffeTrustManagers, "SPIFFE"),
            firstX509(legacyTrustManagers, "legacy"))
    };
  }

  private static X509ExtendedTrustManager firstX509(TrustManager[] trustManagers, String kind) {
    if (trustManagers != null) {
      for (TrustManager trustManager : trustManagers) {
        if (trustManager instanceof X509ExtendedTrustManager) {
          return (X509ExtendedTrustManager) trustManager;
        }
      }
    }
    throw new IllegalStateException(
        "No X509ExtendedTrustManager found among the " + kind + " trust managers");
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType)
      throws CertificateException {
    trustManagerFor(chain).checkClientTrusted(chain, authType);
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
      throws CertificateException {
    trustManagerFor(chain).checkClientTrusted(chain, authType, socket);
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
      throws CertificateException {
    trustManagerFor(chain).checkClientTrusted(chain, authType, engine);
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType)
      throws CertificateException {
    trustManagerFor(chain).checkServerTrusted(chain, authType);
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
      throws CertificateException {
    trustManagerFor(chain).checkServerTrusted(chain, authType, socket);
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
      throws CertificateException {
    trustManagerFor(chain).checkServerTrusted(chain, authType, engine);
  }

  @Override
  public X509Certificate[] getAcceptedIssuers() {
    // The SPIFFE trust manager reports no accepted issuers of its own (trust is decided
    // dynamically against the SPIFFE bundle), and SpiffeKeyManager on the client side ignores
    // the advertised issuers entirely when choosing its alias, so advertising only the legacy
    // issuers here does not stop SPIFFE clients from presenting their SVID.
    return legacyTrustManager.getAcceptedIssuers();
  }

  private X509ExtendedTrustManager trustManagerFor(X509Certificate[] chain) {
    return isSpiffeCert(chain) ? spiffeTrustManager : legacyTrustManager;
  }

  private static boolean isSpiffeCert(X509Certificate[] chain) {
    if (chain == null || chain.length == 0) {
      return false;
    }
    Collection<List<?>> subjectAlternativeNames;
    try {
      subjectAlternativeNames = chain[0].getSubjectAlternativeNames();
    } catch (CertificateParsingException e) {
      log.debug("Failed to parse SAN entries while checking for a SPIFFE URI SAN; "
          + "treating certificate as non-SPIFFE", e);
      return false;
    }
    if (subjectAlternativeNames == null) {
      return false;
    }
    for (List<?> san : subjectAlternativeNames) {
      if (san.size() >= 2
          && san.get(0) instanceof Integer
          && (Integer) san.get(0) == URI_SAN_TYPE
          && san.get(1) instanceof String
          && ((String) san.get(1)).startsWith(SPIFFE_URI_SCHEME)) {
        return true;
      }
    }
    return false;
  }
}
