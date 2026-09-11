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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Trust manager for the SPIRE trust-only listener. A leaf certificate carrying a {@code
 * spiffe://} URI SAN is validated against the SPIFFE trust bundle, and the connection is
 * rejected if that validation fails.
 */
final class SpireOptionalTrustManager extends X509ExtendedTrustManager {

  private static final Logger log = LoggerFactory.getLogger(SpireOptionalTrustManager.class);

  // From the GeneralName ASN.1 CHOICE in RFC 5280 4.2.1.6: type 6 is uniformResourceIdentifier.
  private static final int URI_SAN_TYPE = 6;
  private static final String SPIFFE_URI_SCHEME = "spiffe://";

  private final X509ExtendedTrustManager spiffeTrustManager;

  private SpireOptionalTrustManager(X509ExtendedTrustManager spiffeTrustManager) {
    this.spiffeTrustManager = Objects.requireNonNull(spiffeTrustManager, "spiffeTrustManager");
  }

  /**
   * Wraps the first {@link X509ExtendedTrustManager} found among {@code spiffeTrustManagers}.
   */
  static TrustManager[] wrap(TrustManager[] spiffeTrustManagers) {
    return new TrustManager[] {new SpireOptionalTrustManager(firstX509(spiffeTrustManagers))};
  }

  private static X509ExtendedTrustManager firstX509(TrustManager[] trustManagers) {
    if (trustManagers != null) {
      for (TrustManager trustManager : trustManagers) {
        if (trustManager instanceof X509ExtendedTrustManager) {
          return (X509ExtendedTrustManager) trustManager;
        }
      }
    }
    throw new IllegalStateException(
        "No X509ExtendedTrustManager found among the SPIFFE trust managers");
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType)
      throws CertificateException {
    if (isSpiffeCert(chain)) {
      logValidatingSpiffeCert(chain);
      spiffeTrustManager.checkClientTrusted(chain, authType);
    } else {
      logSkippingNonSpiffeCert(chain);
    }
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
      throws CertificateException {
    if (isSpiffeCert(chain)) {
      logValidatingSpiffeCert(chain);
      spiffeTrustManager.checkClientTrusted(chain, authType, socket);
    } else {
      logSkippingNonSpiffeCert(chain);
    }
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
      throws CertificateException {
    if (isSpiffeCert(chain)) {
      logValidatingSpiffeCert(chain);
      spiffeTrustManager.checkClientTrusted(chain, authType, engine);
    } else {
      logSkippingNonSpiffeCert(chain);
    }
  }

  private static void logValidatingSpiffeCert(X509Certificate[] chain) {
    // The SAN summary is only computed when debug logging is enabled: it walks attacker-influenced
    // ASN.1 data that the JDK hands back as a loosely-typed Collection<List<?>>, and there is no
    // reason to pay that cost -- or accept any risk from it, however well-guarded -- on every
    // handshake in production.
    if (log.isDebugEnabled()) {
      log.debug("Client certificate carries a spiffe:// SAN; validating against the SPIFFE "
          + "trust bundle. SAN entries: {}", safeSanSummary(chain));
    }
  }

  private static void logSkippingNonSpiffeCert(X509Certificate[] chain) {
    if (log.isDebugEnabled()) {
      log.debug("Client certificate does not carry a spiffe:// SAN; skipping validation and "
          + "treating the connection as unauthenticated. SAN entries: {}", safeSanSummary(chain));
    }
  }

  /**
   * Best-effort, exception-safe rendering of a certificate chain's leaf SAN entries, for debug
   * logging only. SAN entries are attacker-influenced ASN.1 data that {@link
   * X509Certificate#getSubjectAlternativeNames()} hands back as a loosely-typed {@code
   * Collection<List<?>>}; a malformed or unexpected entry here must never propagate out of this
   * method and fail (or otherwise affect) an otherwise-valid handshake for an unrelated,
   * logging-only reason. Every failure mode is caught and turned into a descriptive placeholder
   * instead. Package-private so tests can call it directly, independent of the logger's
   * configured level.
   */
  static String safeSanSummary(X509Certificate[] chain) {
    try {
      if (chain == null || chain.length == 0 || chain[0] == null) {
        return "<no certificate>";
      }
      Collection<List<?>> sans;
      try {
        sans = chain[0].getSubjectAlternativeNames();
      } catch (CertificateParsingException e) {
        return "<unparsable SAN entries: " + e.getMessage() + ">";
      }
      if (sans == null) {
        return "<no SAN extension>";
      }
      if (sans.isEmpty()) {
        return "<empty SAN extension>";
      }
      List<String> entries = new ArrayList<>();
      for (List<?> san : sans) {
        entries.add(safeSanEntry(san));
      }
      return entries.toString();
    } catch (RuntimeException e) {
      // Belt-and-suspenders: nothing above is expected to throw an unchecked exception, but this
      // is debug-only logging support code, so a bug here must never affect the handshake outcome.
      return "<error summarizing SAN entries: " + e + ">";
    }
  }

  private static String safeSanEntry(List<?> san) {
    try {
      if (san == null) {
        // getSubjectAlternativeNames() can legally contain null entries.
        return "<null entry>";
      }
      if (san.size() < 2) {
        return "<malformed entry: " + san + ">";
      }
      Object type = san.get(0);
      Object value = san.get(1);
      if (type instanceof Integer && (Integer) type == URI_SAN_TYPE && value instanceof String) {
        return "uniformResourceIdentifier=" + value;
      }
      return "type=" + type + ", value=" + value;
    } catch (RuntimeException e) {
      return "<error reading entry: " + e + ">";
    }
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType)
      throws CertificateException {
    spiffeTrustManager.checkServerTrusted(chain, authType);
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
      throws CertificateException {
    spiffeTrustManager.checkServerTrusted(chain, authType, socket);
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
      throws CertificateException {
    spiffeTrustManager.checkServerTrusted(chain, authType, engine);
  }

  @Override
  public X509Certificate[] getAcceptedIssuers() {
    return spiffeTrustManager.getAcceptedIssuers();
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
      if (isSpiffeUriSan(san)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isSpiffeUriSan(List<?> san) {
    // getSubjectAlternativeNames() can legally contain null entries.
    if (san == null || san.size() < 2 || !(san.get(0) instanceof Integer)
        || (Integer) san.get(0) != URI_SAN_TYPE) {
      return false;
    }
    Object value = san.get(1);
    return value instanceof String && ((String) value).startsWith(SPIFFE_URI_SCHEME);
  }
}
