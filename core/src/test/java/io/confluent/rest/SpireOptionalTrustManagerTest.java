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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class SpireOptionalTrustManagerTest {

  @AfterEach
  public void resetLogLevel() {
    Configurator.setLevel(SpireOptionalTrustManager.class, (Level) null);
  }

  private static X509Certificate certWithSans(List<List<?>> sans) throws Exception {
    X509Certificate cert = mock(X509Certificate.class);
    when(cert.getSubjectAlternativeNames()).thenReturn(sans);
    return cert;
  }

  private static X509ExtendedTrustManager wrappedTrustManager(
      X509ExtendedTrustManager spiffeManager) {
    TrustManager[] wrapped = SpireOptionalTrustManager.wrap(new TrustManager[] {spiffeManager});
    return (X509ExtendedTrustManager) wrapped[0];
  }

  @Test
  public void checkClientTrustedValidatesSpiffeCertAgainstSpiffeManager() throws Exception {
    X509ExtendedTrustManager spiffeManager = mock(X509ExtendedTrustManager.class);
    X509ExtendedTrustManager trustManager = wrappedTrustManager(spiffeManager);

    X509Certificate[] chain = {certWithSans(
        Collections.singletonList(Arrays.asList(6, "spiffe://example.org/workload")))};

    trustManager.checkClientTrusted(chain, "RSA");

    verify(spiffeManager).checkClientTrusted(chain, "RSA");
  }

  @Test
  public void checkClientTrustedPropagatesFailureForInvalidSpiffeCert() throws Exception {
    X509ExtendedTrustManager spiffeManager = mock(X509ExtendedTrustManager.class);
    X509ExtendedTrustManager trustManager = wrappedTrustManager(spiffeManager);

    X509Certificate[] chain = {certWithSans(
        Collections.singletonList(Arrays.asList(6, "spiffe://example.org/workload")))};
    doThrow(new CertificateException("untrusted SVID"))
        .when(spiffeManager).checkClientTrusted(chain, "RSA");

    assertThrows(CertificateException.class, () -> trustManager.checkClientTrusted(chain, "RSA"));
  }

  private static final class CapturingAppender extends AbstractAppender {
    private final List<LogEvent> events = new ArrayList<>();

    CapturingAppender() {
      super("capturing-test-appender", null, null);
    }

    @Override
    public void append(LogEvent event) {
      events.add(event.toImmutable());
    }
  }

  @Test
  public void checkClientTrustedLogsHandshakeFailureForInvalidSpiffeCert() throws Exception {
    Configurator.setLevel(SpireOptionalTrustManager.class, Level.DEBUG);
    org.apache.logging.log4j.core.Logger coreLogger =
        (org.apache.logging.log4j.core.Logger) LogManager.getLogger(SpireOptionalTrustManager.class);
    CapturingAppender appender = new CapturingAppender();
    appender.start();
    coreLogger.addAppender(appender);

    try {
      X509ExtendedTrustManager spiffeManager = mock(X509ExtendedTrustManager.class);
      X509ExtendedTrustManager trustManager = wrappedTrustManager(spiffeManager);

      X509Certificate[] chain = {certWithSans(
          Collections.singletonList(Arrays.asList(6, "spiffe://example.org/workload")))};
      CertificateException cause = new CertificateException("untrusted SVID");
      doThrow(cause).when(spiffeManager).checkClientTrusted(chain, "RSA");

      assertThrows(CertificateException.class,
          () -> trustManager.checkClientTrusted(chain, "RSA"));

      assertTrue(appender.events.stream().anyMatch(e ->
          e.getLevel() == Level.DEBUG
              && e.getMessage().getFormattedMessage().contains("TLS handshake failed")
              && e.getThrown() == cause));
    } finally {
      coreLogger.removeAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void checkClientTrustedSkipsValidationForNonSpiffeCert() throws Exception {
    X509ExtendedTrustManager spiffeManager = mock(X509ExtendedTrustManager.class);
    X509ExtendedTrustManager trustManager = wrappedTrustManager(spiffeManager);

    X509Certificate[] chain = {certWithSans(
        Collections.singletonList(Arrays.asList(2, "leader.internal.example.com")))};

    trustManager.checkClientTrusted(chain, "RSA");

    verify(spiffeManager, never())
        .checkClientTrusted(any(X509Certificate[].class), any(String.class));
  }

  @Test
  public void checkClientTrustedSkipsValidationWhenNoSansPresent() throws Exception {
    X509ExtendedTrustManager spiffeManager = mock(X509ExtendedTrustManager.class);
    X509ExtendedTrustManager trustManager = wrappedTrustManager(spiffeManager);

    X509Certificate[] chain = {certWithSans(null)};

    trustManager.checkClientTrusted(chain, "RSA");

    verify(spiffeManager, never())
        .checkClientTrusted(any(X509Certificate[].class), any(String.class));
  }

  @Test
  public void checkClientTrustedSkipsValidationWhenSanEntryIsNull() throws Exception {
    // getSubjectAlternativeNames() can legally contain null entries; this must not NPE.
    X509ExtendedTrustManager spiffeManager = mock(X509ExtendedTrustManager.class);
    X509ExtendedTrustManager trustManager = wrappedTrustManager(spiffeManager);

    X509Certificate[] chain = {certWithSans(Collections.singletonList(null))};

    trustManager.checkClientTrusted(chain, "RSA");

    verify(spiffeManager, never())
        .checkClientTrusted(any(X509Certificate[].class), any(String.class));
  }

  @Test
  public void checkClientTrustedSkipsValidationWhenSansUnparseable() throws Exception {
    X509ExtendedTrustManager spiffeManager = mock(X509ExtendedTrustManager.class);
    X509ExtendedTrustManager trustManager = wrappedTrustManager(spiffeManager);

    X509Certificate cert = mock(X509Certificate.class);
    when(cert.getSubjectAlternativeNames())
        .thenThrow(new CertificateParsingException("boom"));
    X509Certificate[] chain = {cert};

    trustManager.checkClientTrusted(chain, "RSA");

    verify(spiffeManager, never())
        .checkClientTrusted(any(X509Certificate[].class), any(String.class));
  }

  @Test
  public void checkClientTrustedSkipsValidationWhenChainIsEmpty() throws Exception {
    X509ExtendedTrustManager spiffeManager = mock(X509ExtendedTrustManager.class);
    X509ExtendedTrustManager trustManager = wrappedTrustManager(spiffeManager);

    trustManager.checkClientTrusted(new X509Certificate[0], "RSA");

    verify(spiffeManager, never())
        .checkClientTrusted(any(X509Certificate[].class), any(String.class));
  }

  @Test
  public void getAcceptedIssuersDelegatesToSpiffeManager() {
    X509ExtendedTrustManager spiffeManager = mock(X509ExtendedTrustManager.class);
    X509Certificate[] issuers = {mock(X509Certificate.class)};
    when(spiffeManager.getAcceptedIssuers()).thenReturn(issuers);
    X509ExtendedTrustManager trustManager = wrappedTrustManager(spiffeManager);

    assertArrayEquals(issuers, trustManager.getAcceptedIssuers());
  }

  @Test
  public void wrapThrowsWhenNoX509ExtendedTrustManagerAmongSpiffeManagers() {
    TrustManager[] notExtended = new TrustManager[] {mock(TrustManager.class)};

    assertThrows(IllegalStateException.class, () -> SpireOptionalTrustManager.wrap(notExtended));
  }

  @Test
  public void safeSanSummaryHandlesNullChain() {
    assertDoesNotThrow(() -> SpireOptionalTrustManager.safeSanSummary(null));
  }

  @Test
  public void safeSanSummaryHandlesEmptyChain() {
    assertDoesNotThrow(() -> SpireOptionalTrustManager.safeSanSummary(new X509Certificate[0]));
  }

  @Test
  public void safeSanSummaryHandlesNullLeafCertificate() {
    X509Certificate[] chain = {null};

    assertDoesNotThrow(() -> SpireOptionalTrustManager.safeSanSummary(chain));
  }

  @Test
  public void safeSanSummaryHandlesUnparseableSans() throws Exception {
    X509Certificate cert = mock(X509Certificate.class);
    when(cert.getSubjectAlternativeNames()).thenThrow(new CertificateParsingException("boom"));
    X509Certificate[] chain = {cert};

    String summary = SpireOptionalTrustManager.safeSanSummary(chain);

    assertTrue(summary.contains("boom"));
  }

  @Test
  public void safeSanSummaryHandlesGetSubjectAlternativeNamesThrowingRuntimeException()
      throws Exception {
    X509Certificate cert = mock(X509Certificate.class);
    when(cert.getSubjectAlternativeNames()).thenThrow(new IllegalStateException("boom"));
    X509Certificate[] chain = {cert};

    assertDoesNotThrow(() -> SpireOptionalTrustManager.safeSanSummary(chain));
  }

  @Test
  public void safeSanSummaryHandlesNullSans() throws Exception {
    X509Certificate[] chain = {certWithSans(null)};

    assertDoesNotThrow(() -> SpireOptionalTrustManager.safeSanSummary(chain));
  }

  @Test
  public void safeSanSummaryHandlesEmptySans() throws Exception {
    X509Certificate[] chain = {certWithSans(Collections.emptyList())};

    assertDoesNotThrow(() -> SpireOptionalTrustManager.safeSanSummary(chain));
  }

  @Test
  public void safeSanSummaryHandlesNullSanEntry() throws Exception {
    X509Certificate[] chain = {certWithSans(Collections.singletonList(null))};

    assertDoesNotThrow(() -> SpireOptionalTrustManager.safeSanSummary(chain));
  }

  @Test
  public void safeSanSummaryHandlesSanEntryTooShort() throws Exception {
    X509Certificate[] chain = {certWithSans(
        Collections.singletonList(Collections.singletonList(6)))};

    assertDoesNotThrow(() -> SpireOptionalTrustManager.safeSanSummary(chain));
  }

  @Test
  public void safeSanSummaryHandlesSanEntryWithEmptyList() throws Exception {
    X509Certificate[] chain = {certWithSans(
        Collections.singletonList(Collections.emptyList()))};

    assertDoesNotThrow(() -> SpireOptionalTrustManager.safeSanSummary(chain));
  }

  @Test
  public void safeSanSummaryHandlesSanEntryWithWrongTypeForFirstElement() throws Exception {
    X509Certificate[] chain = {certWithSans(
        Collections.singletonList(Arrays.asList("not-an-integer", "spiffe://example.org/x")))};

    assertDoesNotThrow(() -> SpireOptionalTrustManager.safeSanSummary(chain));
  }

  @Test
  public void safeSanSummaryHandlesSanEntryWithNonStringValue() throws Exception {
    X509Certificate[] chain = {certWithSans(
        Collections.singletonList(Arrays.asList(6, 12345)))};

    assertDoesNotThrow(() -> SpireOptionalTrustManager.safeSanSummary(chain));
  }

  @Test
  public void safeSanSummaryHandlesSanEntryWithNullValue() throws Exception {
    X509Certificate[] chain = {certWithSans(
        Collections.singletonList(Arrays.asList(6, null)))};

    assertDoesNotThrow(() -> SpireOptionalTrustManager.safeSanSummary(chain));
  }

  @Test
  public void safeSanSummaryHandlesMixOfValidAndMalformedEntries() throws Exception {
    List<List<?>> sans = Arrays.asList(
        null,
        Collections.singletonList(6),
        Arrays.asList(6, "spiffe://example.org/workload"),
        Arrays.asList("bad", 42),
        Arrays.asList(2, "leader.internal.example.com"));
    X509Certificate[] chain = {certWithSans(sans)};

    String summary = SpireOptionalTrustManager.safeSanSummary(chain);

    assertTrue(summary.contains("spiffe://example.org/workload"));
  }

  @Test
  public void safeSanSummaryIncludesSpiffeUriForValidSan() throws Exception {
    X509Certificate[] chain = {certWithSans(
        Collections.singletonList(Arrays.asList(6, "spiffe://example.org/workload")))};

    String summary = SpireOptionalTrustManager.safeSanSummary(chain);

    assertTrue(summary.contains("spiffe://example.org/workload"));
  }

  @Test
  public void checkClientTrustedNeverThrowsFromLoggingWithMalformedSpiffeSan() throws Exception {
    Configurator.setLevel(SpireOptionalTrustManager.class, Level.DEBUG);
    X509ExtendedTrustManager spiffeManager = mock(X509ExtendedTrustManager.class);
    X509ExtendedTrustManager trustManager = wrappedTrustManager(spiffeManager);

    List<List<?>> sans = Arrays.asList(
        null,
        Collections.singletonList(6),
        Arrays.asList(6, "spiffe://example.org/workload"),
        Arrays.asList("bad", 42));
    X509Certificate[] chain = {certWithSans(sans)};

    assertDoesNotThrow(() -> trustManager.checkClientTrusted(chain, "RSA"));
    verify(spiffeManager).checkClientTrusted(chain, "RSA");
  }

  @Test
  public void checkClientTrustedNeverThrowsFromLoggingWithMalformedNonSpiffeSan()
      throws Exception {
    Configurator.setLevel(SpireOptionalTrustManager.class, Level.DEBUG);
    X509ExtendedTrustManager spiffeManager = mock(X509ExtendedTrustManager.class);
    X509ExtendedTrustManager trustManager = wrappedTrustManager(spiffeManager);

    List<List<?>> sans = Arrays.asList(
        null,
        Collections.singletonList(6),
        Arrays.asList("bad", 42),
        Arrays.asList(2, "leader.internal.example.com"));
    X509Certificate[] chain = {certWithSans(sans)};

    assertDoesNotThrow(() -> trustManager.checkClientTrusted(chain, "RSA"));
    verify(spiffeManager, never())
        .checkClientTrusted(any(X509Certificate[].class), any(String.class));
  }
}
