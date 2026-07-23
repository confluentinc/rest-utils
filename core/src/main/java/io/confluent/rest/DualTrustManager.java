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
 * Trust manager for SPIRE trust-only listeners that accepts either a SPIFFE SVID or a
 * traditional certificate, based on whether the leaf certificate carries a {@code spiffe://}
 * URI SAN. Lets a listener adopt trust-only mode before every client presents a SPIFFE SVID.
 *
 * <p>Client certificates without a {@code spiffe://} URI SAN are not cryptographically
 * validated: the connection is allowed to proceed the same as if no client certificate had
 * been presented at all, rather than failing the handshake. This matches {@code WANT} client
 * auth semantics, where an absent certificate is already an accepted case, and avoids hard
 * connection failures caused by legacy trust-store configuration drift. Callers must not treat
 * the mere presence of a client certificate as authorization; only a certificate verified via
 * the SPIFFE branch (see {@link SpiffeVerifiedRequestCustomizer}) should be used to grant
 * SPIFFE-based privileges.
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
    if (isSpiffeCert(chain)) {
      spiffeTrustManager.checkClientTrusted(chain, authType);
    }
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
      throws CertificateException {
    if (isSpiffeCert(chain)) {
      spiffeTrustManager.checkClientTrusted(chain, authType, socket);
    }
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
      throws CertificateException {
    if (isSpiffeCert(chain)) {
      spiffeTrustManager.checkClientTrusted(chain, authType, engine);
    }
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
    // Only an advisory CA hint; the real decision is in checkClientTrusted. Reporting none
    // avoids a SPIFFE client withholding its SVID because the hint doesn't look like a match.
    return new X509Certificate[0];
  }

  private X509ExtendedTrustManager trustManagerFor(X509Certificate[] chain) {
    return isSpiffeCert(chain) ? spiffeTrustManager : legacyTrustManager;
  }

  static boolean isSpiffeCert(X509Certificate[] chain) {
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
      if (isSpiffeUriSan(san)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isSpiffeUriSan(List<?> san) {
    if (san == null || san.size() < 2 || !(san.get(0) instanceof Integer)
        || (Integer) san.get(0) != URI_SAN_TYPE) {
      return false;
    }
    Object value = san.get(1);
    return value instanceof String && ((String) value).startsWith(SPIFFE_URI_SCHEME);
  }
}
