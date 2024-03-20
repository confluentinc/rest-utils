/*
 * Copyright 2024 Confluent Inc.
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

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

class TestSslUtils {

  private TestSslUtils() {
    // prevent instantiation
  }

  /**
   * Generate a self-signed certificate with the given key pair and subject alternative name with
   * BouncyCastle FIPS.
   */
  static X509Certificate generateCertificate(KeyPair keyPair, String dirName,
      String subjectAlternativeName) throws Exception {
    X500Name issuer = new X500Name(dirName);
    X500Name subject = new X500Name(dirName);
    BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
    Date notBefore = Date.from(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant());
    Date notAfter = Date.from(
        LocalDate.now().plusYears(1).atStartOfDay(ZoneId.systemDefault()).toInstant());

    X509v3CertificateBuilder certificateBuilder = new JcaX509v3CertificateBuilder(
        issuer, serial, notBefore, notAfter, subject, keyPair.getPublic());

    GeneralNames generalNames = new GeneralNames(
        new GeneralName(GeneralName.dNSName, subjectAlternativeName));
    certificateBuilder.addExtension(Extension.subjectAlternativeName, false, generalNames);

    ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA").setProvider(
        BouncyCastleFipsProvider.PROVIDER_NAME).build(keyPair.getPrivate());
    return new JcaX509CertificateConverter().setProvider(BouncyCastleFipsProvider.PROVIDER_NAME)
        .getCertificate(certificateBuilder.build(signer));
  }
}
