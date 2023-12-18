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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Security;

import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.security.ssl.DefaultSslEngineFactory;
import org.conscrypt.OpenSSLProvider;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SslFactory {

  private static final Logger log = LoggerFactory.getLogger(SslFactory.class);

  private SslFactory() {}

  private static boolean useBcfks(String provider) {
    if(provider == null) {
      return false;
    }
    return provider.equals(SslConfigs.FIPS_SSL_PROVIDER);
  }

  private static String getKeyStoreType(SslConfig config) {
    return useBcfks(config.getProvider()) ? SslConfigs.FIPS_KEYSTORE_TYPE : config.getKeyStoreType();
  }

  public static SslContextFactory createSslContextFactory(SslConfig sslConfig) {
    SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();

    if (!sslConfig.getKeyStorePath().isEmpty()) {
      if (useBcfks(sslConfig.getProvider())) {
        log.info("Will use {} keystore type as provider is {}", SslConfigs.FIPS_KEYSTORE_TYPE, SslConfigs.FIPS_SSL_PROVIDER);
        if (!sslConfig.getKeyStoreType().equals(DefaultSslEngineFactory.PEM_TYPE)) {
          log.error("The config supplied keystore is {} but {} was expected, exiting...",
                  sslConfig.getKeyStoreType(), DefaultSslEngineFactory.PEM_TYPE);
          throw new RuntimeException("Only " + DefaultSslEngineFactory.PEM_TYPE
                  + " keystore supported in approved mode.");
        }

        // we need to parse the PEM store to BCFKS store
        DefaultSslEngineFactory.FileBasedPemStore store = new DefaultSslEngineFactory.FileBasedPemStore(
                sslConfig.getKeyStorePath(), null, true, true);
        sslContextFactory.setKeyStore(store.get());
      } else {
        sslContextFactory.setKeyStorePath(sslConfig.getKeyStorePath());
        sslContextFactory.setKeyStorePassword(sslConfig.getKeyStorePassword());
        sslContextFactory.setKeyStoreType(sslConfig.getKeyStoreType());
      }
      sslContextFactory.setKeyManagerPassword(sslConfig.getKeyManagerPassword());

      if (!sslConfig.getKeyManagerFactoryAlgorithm().isEmpty()) {
        sslContextFactory.setKeyManagerFactoryAlgorithm(sslConfig.getKeyManagerFactoryAlgorithm());
      }

      if (sslConfig.getReloadOnKeyStoreChange()) {
        Path watchLocation = Paths.get(sslConfig.getReloadOnKeyStoreChangePath());
        try {
          FileWatcher.onFileChange(watchLocation, () -> {
                // Need to reset the key store path for symbolic link case
                sslContextFactory.setKeyStorePath(sslConfig.getKeyStorePath());
                sslContextFactory.reload(scf -> {
                  log.info("SSL cert auto reload begun: " + scf.getKeyStorePath());
                });
                log.info("SSL cert auto reload complete");
              }
          );
          log.info("Enabled SSL cert auto reload for: " + watchLocation);
        } catch (java.io.IOException e) {
          log.error("Cannot enable SSL cert auto reload", e);
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
    if (SslConfig.TLS_CONSCRYPT.equalsIgnoreCase(sslConfig.getProvider())) {
      Security.addProvider(new OpenSSLProvider());
    }
  }
}
