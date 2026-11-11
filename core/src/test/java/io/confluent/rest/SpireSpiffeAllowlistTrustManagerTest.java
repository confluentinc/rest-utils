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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.spiffe.workloadapi.X509Source;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import org.apache.kafka.common.metrics.KafkaMetric;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SpireSpiffeAllowlistTrustManager}: the SPIFFE-ID SAN extraction and
 * allowlist matching helpers, plus the client-cert validation flow and its failure logging (chain
 * failure, X509Source-unavailable, allowlist reject) that lets an operator tell a bad client cert
 * from a wrong server-side trust bundle.
 */
final class SpireSpiffeAllowlistTrustManagerTest {

  private static final int SAN_TYPE_URI = 6;
  private static final int SAN_TYPE_DNS = 2;

  private static final String METRIC_GROUP = "spire";
  private static final String M_SUCCESS = "spire-handshake-success-total";
  private static final String M_FAILURE = "spire-handshake-failure-total";
  private static final String M_X509_UNAVAILABLE = "spire-x509-source-unavailable-total";
  private static final String M_REJECT = "spiffe-allowlist-reject-total";
  private static final String SPIFFE_ID = "spiffe://example.org/ns/prod/service-a/instance";

  private CapturingAppender appender;
  private Logger targetLogger;
  private Level previousLevel;

  @BeforeEach
  void attachLogCapture() {
    targetLogger = (Logger) LogManager.getLogger(SpireSpiffeAllowlistTrustManager.class);
    appender = new CapturingAppender();
    appender.start();
    previousLevel = targetLogger.getLevel();
    targetLogger.setLevel(Level.DEBUG); // ensure DEBUG-vs-WARN distinction is observable
    targetLogger.addAppender(appender);
  }

  @AfterEach
  void detachLogCapture() {
    targetLogger.removeAppender(appender);
    targetLogger.setLevel(previousLevel);
    appender.stop();
  }

  // ---------------------------------------------------------------------------
  // spiffeIdOf / spiffeIdAllowed (static helpers)
  // ---------------------------------------------------------------------------

  @Test
  void spiffeIdOf_returnsUri_whenSpiffeUriSanPresent() throws Exception {
    assertEquals(SPIFFE_ID,
        SpireSpiffeAllowlistTrustManager.spiffeIdOf(certWithSans(SAN_TYPE_URI, SPIFFE_ID)));
  }

  @Test
  void spiffeIdOf_returnsNull_whenUriSanIsNotSpiffe() throws Exception {
    assertNull(SpireSpiffeAllowlistTrustManager.spiffeIdOf(
        certWithSans(SAN_TYPE_URI, "https://example.com/foo")));
  }

  @Test
  void spiffeIdOf_returnsNull_whenNoSanAtAll() throws Exception {
    X509Certificate cert = mock(X509Certificate.class);
    when(cert.getSubjectAlternativeNames()).thenReturn(null);
    assertNull(SpireSpiffeAllowlistTrustManager.spiffeIdOf(cert));
  }

  @Test
  void spiffeIdOf_returnsNull_whenSanParsingThrows() throws Exception {
    X509Certificate cert = mock(X509Certificate.class);
    when(cert.getSubjectAlternativeNames())
        .thenThrow(new CertificateParsingException("bad san"));
    assertNull(SpireSpiffeAllowlistTrustManager.spiffeIdOf(cert));
  }

  @Test
  void spiffeIdAllowed_matchesFullPatternOnly() {
    List<Pattern> patterns = List.of(Pattern.compile(".*/service-a/.*"));
    assertTrue(SpireSpiffeAllowlistTrustManager.spiffeIdAllowed(SPIFFE_ID, patterns));
    assertFalse(SpireSpiffeAllowlistTrustManager.spiffeIdAllowed(
        "spiffe://example.org/ns/prod/other/instance", patterns));
    // matches() is a full match: a pattern for a prefix must not partially match.
    assertFalse(SpireSpiffeAllowlistTrustManager.spiffeIdAllowed(
        SPIFFE_ID, List.of(Pattern.compile("spiffe://example.org"))));
  }

  @Test
  void spiffeIdAllowed_nullIdIsNeverAllowed() {
    assertFalse(SpireSpiffeAllowlistTrustManager.spiffeIdAllowed(
        null, List.of(Pattern.compile(".*"))));
  }

  // ---------------------------------------------------------------------------
  // Validation flow + failure logging
  // ---------------------------------------------------------------------------

  @Test
  void checkClientTrusted_passes_whenChainValidAndAllowlisted() throws Exception {
    X509ExtendedTrustManager delegate = mock(X509ExtendedTrustManager.class);
    doNothing().when(delegate).checkClientTrusted(any(), anyString(), any(SSLEngine.class));
    SpireSpiffeAllowlistTrustManager tm = newTrustManager(delegate, /* x509Source= */ null,
        List.of(Pattern.compile(".*/service-a/.*")));

    tm.checkClientTrusted(chainWith(SPIFFE_ID), "RSA", mock(SSLEngine.class));

    assertTrue(warnMessages().isEmpty(), "a successful handshake must not WARN");
  }

  @Test
  void checkClientTrusted_warnsOnChainFailureForSpiffeCert() throws Exception {
    X509ExtendedTrustManager delegate = mock(X509ExtendedTrustManager.class);
    CertificateException cause = new CertificateException("certificate expired");
    doThrow(cause).when(delegate).checkClientTrusted(any(), anyString(), any(SSLEngine.class));
    // x509Source is null -> the CA-describe path must degrade gracefully (no NPE), not that the
    // WARN says anything in particular.
    SpireSpiffeAllowlistTrustManager tm =
        newTrustManager(delegate, /* x509Source= */ null, List.of());

    CertificateException thrown = assertThrows(CertificateException.class,
        () -> tm.checkClientTrusted(chainWith(SPIFFE_ID), "RSA", mock(SSLEngine.class)));
    assertSame(cause, thrown, "the original chain failure must be re-thrown unchanged");
    assertEquals(1, warnMessages().size(), "a SPIRE cert that fails to chain must WARN once");
  }

  @Test
  void checkClientTrusted_logsDebugNotWarn_onChainFailureForNonSpiffeCert() throws Exception {
    X509ExtendedTrustManager delegate = mock(X509ExtendedTrustManager.class);
    doThrow(new CertificateException("untrusted"))
        .when(delegate).checkClientTrusted(any(), anyString(), any(SSLEngine.class));
    SpireSpiffeAllowlistTrustManager tm =
        newTrustManager(delegate, /* x509Source= */ null, List.of());

    X509Certificate nonSpiffe = certWithSans(SAN_TYPE_DNS, "broker.example.com");
    assertThrows(CertificateException.class,
        () -> tm.checkClientTrusted(
            new X509Certificate[] {nonSpiffe}, "RSA", mock(SSLEngine.class)));

    assertTrue(warnMessages().isEmpty(),
        "a non-SPIRE client cert failure must stay at DEBUG, not WARN");
  }

  @Test
  void checkClientTrusted_rethrowsAndWarns_onX509SourceUnavailable() throws Exception {
    X509ExtendedTrustManager delegate = mock(X509ExtendedTrustManager.class);
    // Mimics the SPIRE X509Source throwing when the SPIRE client is not yet resolvable.
    doThrow(new IllegalStateException("SPIRE client not yet available"))
        .when(delegate).checkClientTrusted(any(), anyString(), any(SSLEngine.class));
    SpireSpiffeAllowlistTrustManager tm =
        newTrustManager(delegate, /* x509Source= */ null, List.of());

    assertThrows(IllegalStateException.class,
        () -> tm.checkClientTrusted(chainWith(SPIFFE_ID), "RSA", mock(SSLEngine.class)));

    assertEquals(1, warnMessages().size(), "source-unavailable must WARN exactly once");
  }

  @Test
  void checkClientTrusted_throwsAndWarns_whenSpiffeIdNotAllowed() throws Exception {
    X509ExtendedTrustManager delegate = mock(X509ExtendedTrustManager.class);
    doNothing().when(delegate).checkClientTrusted(any(), anyString(), any(SSLEngine.class));
    SpireSpiffeAllowlistTrustManager tm = newTrustManager(delegate, /* x509Source= */ null,
        List.of(Pattern.compile(".*/service-b/.*"))); // does NOT match SPIFFE_ID

    // Chain passed (delegate did nothing) but the SPIFFE ID is not allowlisted -> reject.
    assertThrows(CertificateException.class,
        () -> tm.checkClientTrusted(chainWith(SPIFFE_ID), "RSA", mock(SSLEngine.class)));
    assertEquals(1, warnMessages().size(), "an unauthorized caller must WARN exactly once");
  }

  // ---------------------------------------------------------------------------
  // Metrics — the counters must increment on the FIRST handshake (regression guard for the
  // lazy-sensor-init bug where record() captured the field's pre-init null and dropped it).
  // ---------------------------------------------------------------------------

  @Test
  void firstSuccessHandshake_incrementsSuccessCounter() throws Exception {
    Metrics metrics = new Metrics();
    try {
      X509ExtendedTrustManager delegate = mock(X509ExtendedTrustManager.class);
      doNothing().when(delegate).checkClientTrusted(any(), anyString(), any(SSLEngine.class));
      SpireSpiffeAllowlistTrustManager tm = withMetrics(
          delegate, List.of(Pattern.compile(".*/service-a/.*")), metrics);

      tm.checkClientTrusted(chainWith(SPIFFE_ID), "RSA", mock(SSLEngine.class));

      assertEquals(1.0, counter(metrics, M_SUCCESS), "first success must be counted");
      assertEquals(0.0, counter(metrics, M_FAILURE));
      assertEquals(0.0, counter(metrics, M_REJECT));
    } finally {
      metrics.close();
    }
  }

  @Test
  void firstChainFailureHandshake_incrementsFailureCounter() throws Exception {
    Metrics metrics = new Metrics();
    try {
      X509ExtendedTrustManager delegate = mock(X509ExtendedTrustManager.class);
      doThrow(new CertificateException("expired"))
          .when(delegate).checkClientTrusted(any(), anyString(), any(SSLEngine.class));
      SpireSpiffeAllowlistTrustManager tm = withMetrics(delegate, List.of(), metrics);

      assertThrows(CertificateException.class,
          () -> tm.checkClientTrusted(chainWith(SPIFFE_ID), "RSA", mock(SSLEngine.class)));

      assertEquals(1.0, counter(metrics, M_FAILURE), "first chain failure must be counted");
      assertEquals(0.0, counter(metrics, M_SUCCESS));
    } finally {
      metrics.close();
    }
  }

  @Test
  void firstX509UnavailableHandshake_incrementsUnavailableCounter() throws Exception {
    Metrics metrics = new Metrics();
    try {
      X509ExtendedTrustManager delegate = mock(X509ExtendedTrustManager.class);
      doThrow(new IllegalStateException("SPIRE client not yet available"))
          .when(delegate).checkClientTrusted(any(), anyString(), any(SSLEngine.class));
      SpireSpiffeAllowlistTrustManager tm = withMetrics(delegate, List.of(), metrics);

      assertThrows(IllegalStateException.class,
          () -> tm.checkClientTrusted(chainWith(SPIFFE_ID), "RSA", mock(SSLEngine.class)));

      assertEquals(1.0, counter(metrics, M_X509_UNAVAILABLE),
          "first source-unavailable must be counted");
      assertEquals(0.0, counter(metrics, M_FAILURE));
    } finally {
      metrics.close();
    }
  }

  @Test
  void firstAllowlistReject_incrementsRejectCounter() throws Exception {
    Metrics metrics = new Metrics();
    try {
      X509ExtendedTrustManager delegate = mock(X509ExtendedTrustManager.class);
      doNothing().when(delegate).checkClientTrusted(any(), anyString(), any(SSLEngine.class));
      SpireSpiffeAllowlistTrustManager tm = withMetrics(
          delegate, List.of(Pattern.compile(".*/service-b/.*")), metrics);

      assertThrows(CertificateException.class,
          () -> tm.checkClientTrusted(chainWith(SPIFFE_ID), "RSA", mock(SSLEngine.class)));

      assertEquals(1.0, counter(metrics, M_REJECT), "first allowlist reject must be counted");
      assertEquals(0.0, counter(metrics, M_SUCCESS));
    } finally {
      metrics.close();
    }
  }

  @Test
  void nullMetricsSupplier_recordsNothingButStillEnforces() throws Exception {
    X509ExtendedTrustManager delegate = mock(X509ExtendedTrustManager.class);
    doNothing().when(delegate).checkClientTrusted(any(), anyString(), any(SSLEngine.class));
    // metricsSupplier returns null -> ensureSensors bails; validation must still reject.
    SpireSpiffeAllowlistTrustManager tm = newTrustManager(delegate, /* x509Source= */ null,
        List.of(Pattern.compile(".*/service-b/.*")));

    assertThrows(CertificateException.class,
        () -> tm.checkClientTrusted(chainWith(SPIFFE_ID), "RSA", mock(SSLEngine.class)));
  }

  @Test
  void sensorsResolveLazily_whenMetricsAvailableOnlyAfterFirstHandshake() throws Exception {
    Metrics metrics = new Metrics();
    try {
      // Supplier returns null on the first call (app not registered yet), metrics thereafter.
      AtomicReference<Metrics> ref = new AtomicReference<>(null);
      Supplier<Metrics> lazy = () -> {
        Metrics current = ref.get();
        ref.set(metrics);
        return current;
      };
      X509ExtendedTrustManager delegate = mock(X509ExtendedTrustManager.class);
      doNothing().when(delegate).checkClientTrusted(any(), anyString(), any(SSLEngine.class));
      SpireSpiffeAllowlistTrustManager tm = new SpireSpiffeAllowlistTrustManager(
          delegate, /* x509Source= */ null,
          List.of(Pattern.compile(".*/service-a/.*")), lazy, Map::of);

      // 1st handshake: metrics unresolvable -> no counter registered yet.
      tm.checkClientTrusted(chainWith(SPIFFE_ID), "RSA", mock(SSLEngine.class));
      assertNull(metrics.metric(metrics.metricName(M_SUCCESS, METRIC_GROUP)),
          "sensors must not be created while metrics is null");

      // 2nd handshake: metrics now resolvable -> sensors created and this one counts.
      tm.checkClientTrusted(chainWith(SPIFFE_ID), "RSA", mock(SSLEngine.class));
      assertEquals(1.0, counter(metrics, M_SUCCESS), "handshake after resolution must count");
    } finally {
      metrics.close();
    }
  }

  // ---------------------------------------------------------------------------
  // Fixtures / helpers
  // ---------------------------------------------------------------------------

  private static SpireSpiffeAllowlistTrustManager newTrustManager(
      X509ExtendedTrustManager delegate, X509Source x509Source, List<Pattern> patterns) {
    Supplier<Map<String, String>> tags = Map::of;
    return new SpireSpiffeAllowlistTrustManager(
        delegate, x509Source, patterns, /* metricsSupplier= */ () -> null, tags);
  }

  private static SpireSpiffeAllowlistTrustManager withMetrics(
      X509ExtendedTrustManager delegate, List<Pattern> patterns, Metrics metrics) {
    return new SpireSpiffeAllowlistTrustManager(
        delegate, /* x509Source= */ null, patterns, () -> metrics, Map::of);
  }

  /**
   * Current value of a {@code spire}-group counter, or 0.0 if the sensor isn't registered.
   */
  private static double counter(Metrics metrics, String name) {
    KafkaMetric metric = (KafkaMetric) metrics.metric(metrics.metricName(name, METRIC_GROUP));
    return metric == null ? 0.0 : ((Number) metric.metricValue()).doubleValue();
  }

  private static X509Certificate[] chainWith(String spiffeId) throws Exception {
    return new X509Certificate[] {certWithSans(SAN_TYPE_URI, spiffeId)};
  }

  private static X509Certificate certWithSans(int sanType, String value) throws Exception {
    X509Certificate cert = mock(X509Certificate.class);
    List<Object> san = new ArrayList<>();
    san.add(sanType);
    san.add(value);
    when(cert.getSubjectAlternativeNames()).thenReturn(Collections.singletonList(san));
    return cert;
  }

  private List<String> warnMessages() {
    return appender.events.stream()
        .filter(e -> e.getLevel() == Level.WARN)
        .map(e -> e.getMessage().getFormattedMessage())
        .collect(Collectors.toList());
  }

  /** A minimal log4j2 appender that captures emitted events for assertions. */
  private static final class CapturingAppender extends AbstractAppender {
    private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

    CapturingAppender() {
      super("capture-" + System.nanoTime(), null, null, true, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
      events.add(event.toImmutable());
    }
  }
}
