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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import org.junit.jupiter.api.Test;

public class SpireOptionalTrustManagerTest {

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
}
