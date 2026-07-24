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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import org.junit.jupiter.api.Test;

public class DualTrustManagerTest {

  private static X509Certificate certWithSans(List<List<?>> sans) throws Exception {
    X509Certificate cert = mock(X509Certificate.class);
    when(cert.getSubjectAlternativeNames()).thenReturn(sans);
    return cert;
  }

  private static X509ExtendedTrustManager wrappedDualTrustManager(
      X509ExtendedTrustManager spiffeManager, X509ExtendedTrustManager legacyManager) {
    TrustManager[] wrapped = DualTrustManager.wrap(
        new TrustManager[] {spiffeManager}, new TrustManager[] {legacyManager}, false);
    return (X509ExtendedTrustManager) wrapped[0];
  }

  @Test
  public void checkClientTrustedUsesSpiffeManagerForSpiffeCert() throws Exception {
    X509ExtendedTrustManager spiffeManager = mock(X509ExtendedTrustManager.class);
    X509ExtendedTrustManager legacyManager = mock(X509ExtendedTrustManager.class);
    X509ExtendedTrustManager dual = wrappedDualTrustManager(spiffeManager, legacyManager);

    X509Certificate[] chain = {certWithSans(
        Collections.singletonList(Arrays.asList(6, "spiffe://example.org/workload")))};

    dual.checkClientTrusted(chain, "RSA");

    verify(spiffeManager).checkClientTrusted(chain, "RSA");
    verify(legacyManager, never())
        .checkClientTrusted(any(X509Certificate[].class), any(String.class));
  }

  @Test
  public void checkClientTrustedUsesLegacyManagerForNonSpiffeCert() throws Exception {
    X509ExtendedTrustManager spiffeManager = mock(X509ExtendedTrustManager.class);
    X509ExtendedTrustManager legacyManager = mock(X509ExtendedTrustManager.class);
    X509ExtendedTrustManager dual = wrappedDualTrustManager(spiffeManager, legacyManager);

    X509Certificate[] chain = {certWithSans(
        Collections.singletonList(Arrays.asList(2, "leader.internal.example.com")))};

    dual.checkClientTrusted(chain, "RSA");

    verify(legacyManager).checkClientTrusted(chain, "RSA");
    verify(spiffeManager, never())
        .checkClientTrusted(any(X509Certificate[].class), any(String.class));
  }

  @Test
  public void checkClientTrustedUsesLegacyManagerWhenNoSansPresent() throws Exception {
    X509ExtendedTrustManager spiffeManager = mock(X509ExtendedTrustManager.class);
    X509ExtendedTrustManager legacyManager = mock(X509ExtendedTrustManager.class);
    X509ExtendedTrustManager dual = wrappedDualTrustManager(spiffeManager, legacyManager);

    X509Certificate[] chain = {certWithSans(null)};

    dual.checkClientTrusted(chain, "RSA");

    verify(legacyManager).checkClientTrusted(chain, "RSA");
  }

  @Test
  public void checkClientTrustedUsesLegacyManagerWhenSanEntryIsNull() throws Exception {
    X509ExtendedTrustManager spiffeManager = mock(X509ExtendedTrustManager.class);
    X509ExtendedTrustManager legacyManager = mock(X509ExtendedTrustManager.class);
    X509ExtendedTrustManager dual = wrappedDualTrustManager(spiffeManager, legacyManager);

    X509Certificate[] chain = {certWithSans(Collections.singletonList(null))};

    dual.checkClientTrusted(chain, "RSA");

    verify(legacyManager).checkClientTrusted(chain, "RSA");
  }

  @Test
  public void checkClientTrustedUsesLegacyManagerWhenSansUnparseable() throws Exception {
    X509ExtendedTrustManager spiffeManager = mock(X509ExtendedTrustManager.class);
    X509ExtendedTrustManager legacyManager = mock(X509ExtendedTrustManager.class);
    X509ExtendedTrustManager dual = wrappedDualTrustManager(spiffeManager, legacyManager);

    X509Certificate cert = mock(X509Certificate.class);
    when(cert.getSubjectAlternativeNames())
        .thenThrow(new CertificateParsingException("boom"));
    X509Certificate[] chain = {cert};

    dual.checkClientTrusted(chain, "RSA");

    verify(legacyManager).checkClientTrusted(chain, "RSA");
  }

  @Test
  public void getAcceptedIssuersReportsNoneSoNoClientIsFilteredOut() {
    X509ExtendedTrustManager spiffeManager = mock(X509ExtendedTrustManager.class);
    X509ExtendedTrustManager legacyManager = mock(X509ExtendedTrustManager.class);

    X509ExtendedTrustManager dual = wrappedDualTrustManager(spiffeManager, legacyManager);

    assertArrayEquals(new X509Certificate[0], dual.getAcceptedIssuers());
    verify(spiffeManager, never()).getAcceptedIssuers();
    verify(legacyManager, never()).getAcceptedIssuers();
  }

  @Test
  public void wrapThrowsWhenNoX509ExtendedTrustManagerAmongSpiffeManagers() {
    TrustManager[] notExtended = new TrustManager[] {mock(TrustManager.class)};
    TrustManager[] legacy = new TrustManager[] {mock(X509ExtendedTrustManager.class)};

    assertThrows(IllegalStateException.class,
        () -> DualTrustManager.wrap(notExtended, legacy, false));
  }

  @Test
  public void wrapThrowsWhenNoX509ExtendedTrustManagerAmongLegacyManagers() {
    TrustManager[] spiffe = new TrustManager[] {mock(X509ExtendedTrustManager.class)};
    TrustManager[] notExtended = new TrustManager[] {mock(TrustManager.class)};

    assertThrows(IllegalStateException.class,
        () -> DualTrustManager.wrap(spiffe, notExtended, false));
  }
}
