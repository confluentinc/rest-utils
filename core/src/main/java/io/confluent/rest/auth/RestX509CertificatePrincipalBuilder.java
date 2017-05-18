/**
 * Copyright 2014 Confluent Inc.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.confluent.rest.auth;

import io.confluent.rest.RestConfig;
import io.confluent.rest.RestConfigurable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletRequest;
import java.security.cert.X509Certificate;

/**
 * Implementation of principal builder extracting principal name
 * from X509Certificate of https request.
 */
public class RestX509CertificatePrincipalBuilder
        implements RestPrincipalBuilder, RestConfigurable {

  private static final Logger log =
          LoggerFactory.getLogger(RestX509CertificatePrincipalBuilder.class);

  private PrincipalNameConverter principalNameConverter;

  public void configure(RestConfig config) {
    principalNameConverter =
            config.getConfiguredInstance(RestConfig.AUTHORIZATION_PRINCIPAL_CONVERTER_CONFIG,
                    PrincipalNameConverter.class);
  }

  @Override
  public String getPrincipalName(ServletRequest servletRequest) {
    Object certsObj = servletRequest.getAttribute("javax.servlet.request.X509Certificate");
    if (certsObj instanceof X509Certificate[]) {
      X509Certificate[] certs = (X509Certificate[]) certsObj;
      for (X509Certificate cert : certs) {
        String principalName = principalNameConverter
                .convertPrincipalName(cert.getSubjectDN().toString());
        log.debug("Principal name {}", principalName);
        if (principalName != null) {
          return principalName;
        }
      }
    }
    return null;
  }

  public static class IdentityPrincipalNameConverter implements PrincipalNameConverter {
    @Override
    public String convertPrincipalName(String principalName) {
      return principalName;
    }
  }
}
