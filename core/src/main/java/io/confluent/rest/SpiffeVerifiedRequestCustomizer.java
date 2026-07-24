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

import java.security.cert.X509Certificate;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.util.ssl.X509;

/**
 * Sets {@link #SPIFFE_VERIFIED_ATTRIBUTE} on every request, so that authorization code can
 * tell whether the client certificate on this connection was actually verified as a SPIFFE
 * SVID by {@link DualTrustManager}, as opposed to merely being present. This distinction
 * matters because {@link DualTrustManager} does not enforce the JSSE client-auth EKU purpose
 * check for non-SPIFFE ("legacy") client certificates (and may skip validating them entirely,
 * see {@link RestConfig#SKIP_LEGACY_CLIENT_VALIDATION_CONFIG}): their presence alone must
 * never be used to grant SPIFFE-based privileges.
 *
 * <p>Must run after {@link SecureRequestCustomizer}, which populates
 * {@link SecureRequestCustomizer#X509_ATTRIBUTE}.
 */
public final class SpiffeVerifiedRequestCustomizer implements HttpConfiguration.Customizer {

  public static final String SPIFFE_VERIFIED_ATTRIBUTE = "io.confluent.rest.spiffe.verified";

  @Override
  public Request customize(Request request, HttpFields.Mutable responseHeaders) {
    Object x509Attribute = request.getAttribute(SecureRequestCustomizer.X509_ATTRIBUTE);
    boolean spiffeVerified = false;
    if (x509Attribute instanceof X509) {
      X509Certificate certificate = ((X509) x509Attribute).getCertificate();
      spiffeVerified = DualTrustManager.isSpiffeCert(new X509Certificate[] {certificate});
    }
    request.setAttribute(SPIFFE_VERIFIED_ATTRIBUTE, spiffeVerified);
    return request;
  }
}
