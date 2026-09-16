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

import io.spiffe.bundle.x509bundle.X509Bundle;
import io.spiffe.spiffeid.TrustDomain;
import io.spiffe.svid.x509svid.X509Svid;
import io.spiffe.workloadapi.X509Source;
import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.metrics.stats.CumulativeCount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A client-certificate trust manager for a <b>full-SPIRE listener with a SPIFFE-ID allowlist</b>.
 * The listener serves SPIRE callers only, so <em>every</em> connection is validated. It is intended
 * to sit behind {@code ssl.client.authentication=required} on a full-SPIRE listener (the server
 * presents its own SVID; there is no keystore/one-way-TLS traffic to distinguish).
 *
 * <h2>What it enforces on every client certificate</h2>
 * <ol>
 *   <li><b>Chain-to-bundle</b> — the presented SVID must chain to the live SPIRE trust bundle.
 *       Delegated to the wrapped SPIRE trust manager (auto-rotating bundle).</li>
 *   <li><b>SPIFFE-ID allowlist</b> — the SVID's {@code spiffe://} URI must fully match one of the
 *       configured accept patterns (from {@code ssl.spire.accepted.spiffe.id.patterns}). If no
 *       patterns are configured this manager is not installed (see {@code SslFactory}).</li>
 * </ol>
 * Each pattern is compiled with {@link Pattern#compile} and tested with
 * {@link java.util.regex.Matcher#matches()} (full match against the whole URI). The patterns are
 * arbitrary operator-supplied regexes matched against the entire SPIFFE ID, so <em>any</em> SPIFFE
 * pattern is acceptable — nothing here is limited to a particular trust domain or path shape (e.g.
 * {@code spiffe://<trust-domain>/<path>/<workload>}).
 *
 * <h2>Metrics (per-listener)</h2>
 * Registered into the owning listener's own {@link Metrics} registry / JMX prefix (resolved lazily
 * at handshake time via a supplier) — the same registry that exports that listener's built-in REST
 * metrics, so the counters are emitted through the same reporters. Per-listener separation comes
 * from each listener's Application having its own {@link Metrics} instance, not from a tag; the
 * counters carry whatever {@code metrics.tag.map} tags are configured (none by default). Four
 * counters, in
 * the {@code spire} group:
 * <ul>
 *   <li>{@code spire-handshake-success-total} — SVID chained and passed the allowlist.</li>
 *   <li>{@code spire-handshake-failure-total} — SVID failed chain-to-bundle validation
 *       ({@link CertificateException} from the delegate: expired/untrusted/malformed cert).</li>
 *   <li>{@code spire-x509-source-unavailable-total} — the SPIRE X509Source could not be resolved
 *       at handshake time (fail-closed; SPIRE down / SVID not yet fetched).</li>
 *   <li>{@code spiffe-allowlist-reject-total} — SVID chained but its SPIFFE ID is not allowlisted
 *       (unauthorized caller).</li>
 * </ul>
 * If no {@link Metrics} is resolvable yet (or telemetry is off) recording is a no-op; spire
 * validation is unaffected.
 */
final class SpireSpiffeAllowlistTrustManager extends X509ExtendedTrustManager {

  private static final Logger log =
      LoggerFactory.getLogger(SpireSpiffeAllowlistTrustManager.class);
  private static final String SPIFFE_SCHEME = "spiffe://";
  /**
   * X.509 {@code subjectAltName} general-name type for a URI (RFC 5280); a SPIFFE ID is a URI.
   */
  private static final int SAN_TYPE_URI = 6;

  private static final String METRIC_GROUP = "spire";
  private static final String HANDSHAKE_SUCCESS = "spire-handshake-success-total";
  private static final String HANDSHAKE_FAILURE = "spire-handshake-failure-total";
  private static final String X509_SOURCE_UNAVAILABLE = "spire-x509-source-unavailable-total";
  private static final String ALLOWLIST_REJECT = "spiffe-allowlist-reject-total";

  /** The real SPIRE trust manager we delegate chain-to-bundle validation to. */
  private final X509ExtendedTrustManager delegate;
  /** The server's own SPIRE workload source */
  private final X509Source x509Source;
  /** Pre-compiled allowlist of accepted SPIFFE IDs; empty means "bundle check only". */
  private final List<Pattern> acceptedSpiffeIdPatterns;
  /** Lazily-resolved per-listener metrics (null-tolerant); resolved at first handshake. */
  private final Supplier<Metrics> metricsSupplier;
  private final Supplier<Map<String, String>> metricsTagsSupplier;

  // Sensors are created lazily on first successful metrics resolution.
  private volatile boolean sensorsInitialized;
  private Sensor handshakeSuccessSensor;
  private Sensor handshakeFailureSensor;
  private Sensor x509SourceUnavailableSensor;
  private Sensor allowlistRejectSensor;

  SpireSpiffeAllowlistTrustManager(
      X509ExtendedTrustManager delegate,
      X509Source x509Source,
      List<Pattern> acceptedSpiffeIdPatterns,
      Supplier<Metrics> metricsSupplier,
      Supplier<Map<String, String>> metricsTagsSupplier) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.x509Source = x509Source;
    this.acceptedSpiffeIdPatterns = List.copyOf(acceptedSpiffeIdPatterns);
    this.metricsSupplier = metricsSupplier;
    this.metricsTagsSupplier = metricsTagsSupplier;
  }

  // ---------------------------------------------------------------------------
  // Client-certificate checks. Every connection is validated.
  // Jetty server-side TLS uses the SSLEngine overload
  // ---------------------------------------------------------------------------

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
      throws CertificateException {
    validate(chain, () -> delegate.checkClientTrusted(chain, authType, engine));
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
      throws CertificateException {
    validate(chain, () -> delegate.checkClientTrusted(chain, authType, socket));
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType)
      throws CertificateException {
    validate(chain, () -> delegate.checkClientTrusted(chain, authType));
  }

  /**
   * Run chain-to-bundle validation then the allowlist, recording the outcome. Fails closed: any
   * error aborts the handshake. Distinguishes three failure modes for metrics/debugging:
   * chain failure ({@link CertificateException}), SPIRE-source-unavailable
   * ({@link RuntimeException} from the lazy X509Source), and allowlist reject.
   */
  private void validate(X509Certificate[] chain, ChainCheck chainCheck)
      throws CertificateException {
    try {
      chainCheck.run(); // (1) chain -> SPIRE bundle
    } catch (CertificateException e) {
      record(() -> handshakeFailureSensor);
      logChainValidationFailure(chain, e);
      throw e;
    } catch (RuntimeException e) {
      // The SPIRE X509Source can throw (e.g. IllegalStateException) when it is not resolvable
      // (SPIRE down / SVID not fetched). Fail closed and count it distinctly.
      record(() -> x509SourceUnavailableSensor);
      log.warn("SPIRE X509Source unavailable at handshake -- failing closed. Client SPIFFE ID [{}]."
          + " Error: {}", spiffeIdOfLeaf(chain), e.getMessage(), e);
      throw e;
    }
    enforceSpiffeAllowlist(chain); // (2) SPIFFE ID in allowlist (records reject + throws)
    record(() -> handshakeSuccessSensor);
  }

  @FunctionalInterface
  private interface ChainCheck {
    void run() throws CertificateException;
  }

  // ---------------------------------------------------------------------------
  // Server-certificate checks. We are a server; delegate unchanged (only exercised if this trust
  // manager were ever reused on a client socket).
  // ---------------------------------------------------------------------------

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
      throws CertificateException {
    delegate.checkServerTrusted(chain, authType, engine);
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
      throws CertificateException {
    delegate.checkServerTrusted(chain, authType, socket);
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType)
      throws CertificateException {
    delegate.checkServerTrusted(chain, authType);
  }

  @Override
  public X509Certificate[] getAcceptedIssuers() {
    // Delegate to SPIRE (returns empty), so no CA DNs are advertised in the CertificateRequest.
    return delegate.getAcceptedIssuers();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Reject the caller unless its SVID's SPIFFE ID matches the configured allowlist. */
  private void enforceSpiffeAllowlist(X509Certificate[] chain) throws CertificateException {
    if (acceptedSpiffeIdPatterns.isEmpty()) {
      // No allowlist configured: chain-to-bundle validation only.
      return;
    }
    String spiffeId = (chain != null && chain.length > 0) ? spiffeIdOf(chain[0]) : null;
    if (!spiffeIdAllowed(spiffeId, acceptedSpiffeIdPatterns)) {
      record(() -> allowlistRejectSensor);
      // WARN: a validly-chained SVID that fails the allowlist is a misconfiguration or an
      // unexpected/unauthorized caller -- worth alerting on. Do not log the whole certificate.
      log.warn("Rejecting client: SPIFFE ID [{}] is not in the accepted allowlist", spiffeId);
      throw new CertificateException(
          "SPIFFE ID " + spiffeId + " is not in the accepted-identity allowlist");
    }
  }

  /**
   * Log a chain-to-bundle validation failure with enough detail to tell a client-side problem (a
   * bad / expired / unexpected client cert) from a server-side one (wrong SPIRE trust bundle).
   * When the client genuinely presented a SPIRE cert (leaf carries a {@code spiffe://} URI SAN) we
   * WARN with the exception, the full client chain, and the server-side SPIRE CA authorities;
   * otherwise (no SPIFFE ID -- an unexpected non-SPIRE client on this SPIRE-only listener) we log
   * at DEBUG to avoid noise. The handshake still fails either way (the caller re-throws); this only
   * records evidence.
   */
  private void logChainValidationFailure(X509Certificate[] chain, CertificateException e) {
    String spiffeId = spiffeIdOfLeaf(chain);
    if (spiffeId != null) {
      log.warn("SPIRE chain-to-bundle validation failed even though the client presented a SPIRE "
              + "certificate (SPIFFE ID [{}]). Client certificate chain: {}. Server-side SPIRE CA: "
              + "{}. Error: {}",
          spiffeId, Arrays.toString(chain), describeServerSpireCa(), e.getMessage(), e);
    } else {
      log.debug("SPIRE chain-to-bundle validation failed for a non-SPIRE client certificate "
          + "(no spiffe:// SAN). Error: {}", e.getMessage(), e);
    }
  }

  /** The leaf cert's SPIFFE ID, or null if the chain is empty or carries no spiffe:// SAN. */
  private static String spiffeIdOfLeaf(X509Certificate[] chain) {
    return (chain != null && chain.length > 0) ? spiffeIdOf(chain[0]) : null;
  }

  /**
   * Human-readable description of the server-side SPIRE CA authorities (trust bundle) this listener
   * validates client certs against, derived from the server's own SVID trust domain (no client
   * input needed). Any failure to resolve the bundle is captured in the returned string, never
   * thrown, so this is safe to call while building a log message.
   */
  private String describeServerSpireCa() {
    if (x509Source == null) {
      return "<unavailable: SPIRE x509Source is null>";
    }
    try {
      X509Svid svid = x509Source.getX509Svid();
      if (svid == null) {
        return "<unavailable: SPIRE X509Svid is null>";
      }
      TrustDomain trustDomain = svid.getSpiffeId().getTrustDomain();
      X509Bundle bundle = x509Source.getBundleForTrustDomain(trustDomain);
      return "trustDomain=" + trustDomain + ", authorities=" + bundle.getX509Authorities();
    } catch (Exception ex) {
      return "<unavailable: " + ex.getMessage() + ">";
    }
  }

  /**
   * Extract the {@code spiffe://} URI SAN from a certificate, or null if none. Visible for testing.
   */
  static String spiffeIdOf(X509Certificate cert) {
    try {
      Collection<List<?>> sans = cert.getSubjectAlternativeNames();
      if (sans == null) {
        return null;
      }
      for (List<?> san : sans) {
        // Each SAN entry is [type, value]; type 6 == URI. The SPIFFE ID is a URI SAN.
        if (san.size() >= 2 && Integer.valueOf(SAN_TYPE_URI).equals(san.get(0))) {
          String uri = String.valueOf(san.get(1));
          if (uri.startsWith(SPIFFE_SCHEME)) {
            return uri;
          }
        }
      }
    } catch (CertificateParsingException e) {
      log.warn("Could not parse SANs from client certificate", e);
    }
    return null;
  }

  /**
   * True if {@code spiffeId} is non-null and fully matches an allowlist pattern (start-to-end,
   * via {@link java.util.regex.Matcher#matches()}). Visible for testing.
   */
  static boolean spiffeIdAllowed(String spiffeId, List<Pattern> patterns) {
    if (spiffeId == null) {
      return false;
    }
    for (Pattern p : patterns) {
      if (p.matcher(spiffeId).matches()) {
        return true;
      }
    }
    return false;
  }

  // ---------------------------------------------------------------------------
  // Metrics (lazy, per-listener, null-tolerant)
  // ---------------------------------------------------------------------------

  // Takes a supplier, not a Sensor, so the field is read AFTER ensureSensors() has (lazily) created
  // it. Passing the field directly would capture its pre-init null on the first handshake and drop
  // that record.
  private void record(Supplier<Sensor> sensorRef) {
    ensureSensors();
    Sensor sensor = sensorRef.get();
    if (sensor != null) {
      sensor.record();
    }
  }

  private void ensureSensors() {
    if (sensorsInitialized || metricsSupplier == null) {
      return;
    }
    Metrics metrics = metricsSupplier.get();
    if (metrics == null) {
      return; // applications not registered yet, or telemetry not wired -> retry next handshake
    }
    synchronized (this) {
      if (sensorsInitialized) {
        return;
      }
      Map<String, String> resolvedTags =
          metricsTagsSupplier != null ? metricsTagsSupplier.get() : null;
      Map<String, String> tags = resolvedTags != null ? resolvedTags : Map.of();
      handshakeSuccessSensor = counter(metrics, HANDSHAKE_SUCCESS, tags);
      handshakeFailureSensor = counter(metrics, HANDSHAKE_FAILURE, tags);
      x509SourceUnavailableSensor = counter(metrics, X509_SOURCE_UNAVAILABLE, tags);
      allowlistRejectSensor = counter(metrics, ALLOWLIST_REJECT, tags);
      sensorsInitialized = true;
    }
  }

  private static Sensor counter(Metrics metrics, String name, Map<String, String> tags) {
    Sensor sensor = metrics.sensor(name);
    sensor.add(metrics.metricName(name, METRIC_GROUP,
        "SPIRE mTLS client-cert validation counter for a REST listener", tags),
        new CumulativeCount());
    return sensor;
  }
}
