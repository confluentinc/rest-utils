/*
 * Copyright 2022 Confluent Inc.
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

import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Provider;
import java.security.Security;
import java.util.Map;
import org.conscrypt.OpenSSLProvider;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SslFactory {

  private static final Logger log = LoggerFactory.getLogger(SslFactory.class);

  private static final Map<String, Provider> securityProviderMap = ImmutableMap.of(
      SslConfig.TLS_CONSCRYPT, new OpenSSLProvider());

  private SslFactory() {}

  public static SslContextFactory createSslContextFactory(SslConfig sslConfig) {
    SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();

    if (!sslConfig.getKeyStorePath().isEmpty()) {
      sslContextFactory.setKeyStorePath(sslConfig.getKeyStorePath());
      sslContextFactory.setKeyStorePassword(sslConfig.getKeyStorePassword());
      sslContextFactory.setKeyManagerPassword(sslConfig.getKeyManagerPassword());
      sslContextFactory.setKeyStoreType(sslConfig.getKeyStoreType());

      if (!sslConfig.getKeyManagerFactoryAlgorithm().isEmpty()) {
        sslContextFactory.setKeyManagerFactoryAlgorithm(sslConfig.getKeyManagerFactoryAlgorithm());
      }

      if (sslConfig.getReloadOnKeyStoreChange()) {
        Path watchLocation = Paths.get(sslConfig.getReloadOnKeyStoreChangePath());
        try {
          FileWatcher.onFileChange(watchLocation, () -> {
                // Need to reset the key store path for symbolic link case
                sslContextFactory.setKeyStorePath(sslConfig.getKeyStorePath());
                sslContextFactory.reload(scf -> log.info("Reloaded SSL cert"));
              }
          );
          log.info("Enabled SSL cert auto reload for: " + watchLocation);
        } catch (java.io.IOException e) {
          log.error("Can not enabled SSL cert auto reload", e);
        }
      }
    }

    configureClientAuth(sslContextFactory, sslConfig);

    if (!sslConfig.getIncludeProtocols().isEmpty()) {
      sslContextFactory.setIncludeProtocols(
          sslConfig.getIncludeProtocols().toArray(new String[0]));
    }

    if (!sslConfig.getIncludeCipherSuites().isEmpty()) {
      sslContextFactory.setIncludeCipherSuites(
          sslConfig.getIncludeCipherSuites().toArray(new String[0]));
    }

    sslContextFactory.setEndpointIdentificationAlgorithm(
        sslConfig.getEndpointIdentificationAlgorithm());

    if (!sslConfig.getTrustStorePath().isEmpty()) {
      sslContextFactory.setTrustStorePath(sslConfig.getTrustStorePath());
      sslContextFactory.setTrustStorePassword(sslConfig.getTrustStorePassword());
      sslContextFactory.setTrustStoreType(sslConfig.getTrustStoreType());

      if (!sslConfig.getTrustManagerFactoryAlgorithm().isEmpty()) {
        sslContextFactory.setTrustManagerFactoryAlgorithm(
            sslConfig.getTrustManagerFactoryAlgorithm());
      }
    }

    sslContextFactory.setProtocol(sslConfig.getProtocol());
    if (!sslConfig.getProvider().isEmpty()) {
      configureSecurityProvider(sslContextFactory, sslConfig);
    }

    sslContextFactory.setRenegotiationAllowed(false);

    return sslContextFactory;
  }

  private static void configureClientAuth(
      SslContextFactory.Server sslContextFactory, SslConfig config) {
    switch (config.getClientAuth()) {
      case NEED:
        sslContextFactory.setNeedClientAuth(true);
        break;
      case WANT:
        sslContextFactory.setWantClientAuth(true);
        break;
      default:
    }
  }

  private static void configureSecurityProvider(Server sslContextFactory, SslConfig sslConfig) {
    sslContextFactory.setProvider(sslConfig.getProvider());
    if (securityProviderMap.containsKey(sslConfig.getProvider())) {
      Security.addProvider(securityProviderMap.get(sslConfig.getProvider()));
    }
  }
}
