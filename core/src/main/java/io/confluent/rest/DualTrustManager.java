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
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
 * <p>Non-SPIFFE ("legacy") client certificates are validated with the standard PKIX
 * certification path algorithm directly (chain-to-anchor, signature, validity,
 * {@code BasicConstraints}/path length on intermediates), against {@code legacyTrustManager}'s
 * own trust anchors, but deliberately bypassing {@code sun.security.validator.EndEntityChecker}
 * (the internal JSSE component invoked by {@code X509TrustManagerImpl}), which additionally
 * requires the leaf certificate's {@code ExtendedKeyUsage} to include {@code id-kp-clientAuth}.
 * That check is not part of the core PKIX algorithm defined in RFC 5280; it is a JSSE-specific
 * purpose check layered on top for the caller's intended use. Skipping only that check lets a
 * validly-issued certificate that was provisioned for {@code serverAuth} (and is being reused
 * as a legacy S2S peer identity ahead of a SPIFFE migration) be accepted, while a genuinely
 * invalid chain (untrusted root, expired, wrong signature, bad path constraints) is still
 * rejected.
 *
 * <p>If {@code skipLegacyClientValidation} is set (see
 * {@link RestConfig#SKIP_LEGACY_CLIENT_VALIDATION_CONFIG}), legacy certificates are not
 * validated at all: the connection proceeds the same as if no client certificate had been
 * presented. This is an operational kill switch, not the default behavior.
 *
 * <p>Callers must not treat the mere presence of a client certificate as authorization; only
 * a certificate verified via the SPIFFE branch (see {@link SpiffeVerifiedRequestCustomizer})
 * should be used to grant SPIFFE-based privileges.
 */
final class DualTrustManager extends X509ExtendedTrustManager {

  private static final Logger log = LoggerFactory.getLogger(DualTrustManager.class);

  // From the GeneralName ASN.1 CHOICE in RFC 5280 4.2.1.6: type 6 is uniformResourceIdentifier.
  private static final int URI_SAN_TYPE = 6;
  private static final String SPIFFE_URI_SCHEME = "spiffe://";
  private static final String PKIX_ALGORITHM = "PKIX";
  private static final String X509_CERTIFICATE_TYPE = "X.509";

  private final X509ExtendedTrustManager spiffeTrustManager;
  private final X509ExtendedTrustManager legacyTrustManager;
  private final Set<TrustAnchor> legacyTrustAnchors;
  private final boolean skipLegacyClientValidation;

  private DualTrustManager(X509ExtendedTrustManager spiffeTrustManager,
      X509ExtendedTrustManager legacyTrustManager, boolean skipLegacyClientValidation) {
    this.spiffeTrustManager = Objects.requireNonNull(spiffeTrustManager, "spiffeTrustManager");
    this.legacyTrustManager = Objects.requireNonNull(legacyTrustManager, "legacyTrustManager");
    this.legacyTrustAnchors = toTrustAnchors(legacyTrustManager.getAcceptedIssuers());
    this.skipLegacyClientValidation = skipLegacyClientValidation;
  }

  private static Set<TrustAnchor> toTrustAnchors(X509Certificate[] certificates) {
    Set<TrustAnchor> anchors = new HashSet<>();
    for (X509Certificate certificate : certificates) {
      anchors.add(new TrustAnchor(certificate, null));
    }
    return anchors;
  }

  /**
   * Wraps the first {@link X509ExtendedTrustManager} found in each array into a single
   * dual-mode trust manager.
   *
   * @param skipLegacyClientValidation see
   *     {@link RestConfig#SKIP_LEGACY_CLIENT_VALIDATION_CONFIG}
   */
  static TrustManager[] wrap(TrustManager[] spiffeTrustManagers,
      TrustManager[] legacyTrustManagers, boolean skipLegacyClientValidation) {
    return new TrustManager[] {
        new DualTrustManager(
            firstX509(spiffeTrustManagers, "SPIFFE"),
            firstX509(legacyTrustManagers, "legacy"),
            skipLegacyClientValidation)
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
      return;
    }
    if (skipLegacyClientValidation) {
      return;
    }
    validateLegacyChain(chain);
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
      throws CertificateException {
    if (isSpiffeCert(chain)) {
      spiffeTrustManager.checkClientTrusted(chain, authType, socket);
      return;
    }
    if (skipLegacyClientValidation) {
      return;
    }
    validateLegacyChain(chain);
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
      throws CertificateException {
    if (isSpiffeCert(chain)) {
      spiffeTrustManager.checkClientTrusted(chain, authType, engine);
      return;
    }
    if (skipLegacyClientValidation) {
      return;
    }
    validateLegacyChain(chain);
  }

  /**
   * Validates {@code chain} with the standard PKIX certification path algorithm against
   * {@link #legacyTrustAnchors}, deliberately without the JSSE end-entity purpose check that
   * would otherwise require the leaf's EKU to include {@code id-kp-clientAuth}. See the class
   * Javadoc for why that specific check is intentionally skipped.
   */
  private void validateLegacyChain(X509Certificate[] chain) throws CertificateException {
    try {
      CertificateFactory certificateFactory =
          CertificateFactory.getInstance(X509_CERTIFICATE_TYPE);
      CertPath certPath = certificateFactory.generateCertPath(Arrays.asList(chain));
      PKIXParameters params = new PKIXParameters(legacyTrustAnchors);
      params.setRevocationEnabled(false);
      CertPathValidator.getInstance(PKIX_ALGORITHM).validate(certPath, params);
      log.debug("Legacy client certificate chain passed PKIX validation (EKU check skipped): "
          + "subject={}, chain length={}", chain[0].getSubjectX500Principal(), chain.length);
    } catch (CertPathValidatorException | InvalidAlgorithmParameterException
        | NoSuchAlgorithmException e) {
      throw new CertificateException("Legacy client certificate chain failed PKIX validation",
          e);
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
