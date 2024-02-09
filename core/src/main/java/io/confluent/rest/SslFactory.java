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

import com.google.common.annotations.VisibleForTesting;
import org.apache.kafka.common.config.types.Password;
import org.conscrypt.OpenSSLProvider;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Security;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class SslFactory {

  private static final Logger log = LoggerFactory.getLogger(SslFactory.class);
  private static AtomicReference<Exception> watcherExecException = new AtomicReference<>(null);

  @VisibleForTesting
  public static Optional<Exception> lastLoadFailure() {
    return Optional.ofNullable(watcherExecException.get());
  }

  private SslFactory() {
  }

  private static void setSecurityStoreProps(SslConfig sslConfig,
                                            SslContextFactory.Server sslContextFactory,
                                            boolean isKeyStore,
                                            boolean setPathOnly) {
    boolean isPem = SslFactoryPemHelper.isPemSecurityStore(
        isKeyStore ? sslConfig.getKeyStoreType() : sslConfig.getTrustStoreType());

    if (isPem) {
      log.info("PEM security store detected! Converting to {} - isKeyStore {}",
          SslFactoryPemHelper.getKeyStoreType(sslConfig.getProvider()),
          isKeyStore);

      if (isKeyStore) {
        sslContextFactory.setKeyStore(
            SslFactoryPemHelper.getKeyStoreFromPem(
                sslConfig.getKeyStorePath(), sslConfig.getKeyStoreType(),
                new Password(sslConfig.getKeyManagerPassword()),
                sslConfig.getProvider(), isKeyStore));
      } else {
        sslContextFactory.setTrustStore(
            SslFactoryPemHelper.getKeyStoreFromPem(
                sslConfig.getTrustStorePath(), sslConfig.getTrustStoreType(),
                new Password(sslConfig.getKeyManagerPassword()),
                sslConfig.getProvider(), isKeyStore));
      }
    } else {
      if (isKeyStore) {
        sslContextFactory.setKeyStorePath(sslConfig.getKeyStorePath());
        if (!setPathOnly) {
          sslContextFactory.setKeyStorePassword(sslConfig.getKeyStorePassword());
          sslContextFactory.setKeyStoreType(sslConfig.getKeyStoreType());
        }
      } else {
        sslContextFactory.setTrustStorePath(sslConfig.getTrustStorePath());
        if (!setPathOnly) {
          sslContextFactory.setTrustStorePassword(sslConfig.getTrustStorePassword());
          sslContextFactory.setTrustStoreType(sslConfig.getTrustStoreType());
        }
      }
    }
  }

  private static FileWatcher.Callback onFileChangeCallback(SslConfig sslConfig,
                                                  SslContextFactory.Server sslContextFactory) {
    return () -> {
      // Need to reset the key store path for symbolic link case
      try {
        setSecurityStoreProps(sslConfig, sslContextFactory, true, true);
        sslContextFactory.reload(scf -> {
          log.info("SSL cert auto reload begun: " + scf.getKeyStorePath());
        });
        log.info("SSL cert auto reload complete");
        watcherExecException.set(null);
      } catch (Exception e) {
        watcherExecException.set(e);
        throw e;
      }
    };
  }

  public static SslContextFactory createSslContextFactory(SslConfig sslConfig) {
    SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();

    if (!sslConfig.getKeyStorePath().isEmpty()) {
      setSecurityStoreProps(sslConfig, sslContextFactory, true, false);
      sslContextFactory.setKeyManagerPassword(sslConfig.getKeyManagerPassword());

      if (!sslConfig.getKeyManagerFactoryAlgorithm().isEmpty()) {
        sslContextFactory.setKeyManagerFactoryAlgorithm(sslConfig.getKeyManagerFactoryAlgorithm());
      }

      if (sslConfig.getReloadOnKeyStoreChange()) {
        Path watchLocation = Paths.get(sslConfig.getReloadOnKeyStoreChangePath());
        try {
          FileWatcher.onFileChange(watchLocation,
              onFileChangeCallback(sslConfig, sslContextFactory));
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
      setSecurityStoreProps(sslConfig, sslContextFactory, false, false);
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
