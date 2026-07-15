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
import io.spiffe.provider.SpiffeSslContextFactory;
import io.spiffe.provider.SpiffeTrustManagerFactory;
import io.spiffe.workloadapi.X509Source;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.config.types.Password;
import org.conscrypt.OpenSSLProvider;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.CRL;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class SslFactory {

  /**
   * {@link javax.net.ssl.SSLSession} attribute set to {@link Boolean#TRUE} (via {@link
   * javax.net.ssl.SSLSession#putValue}) when, in SPIRE trust-only mode (see {@link
   * #createSpireTrustOnlyServer}), the peer's certificate was validated against the SPIFFE trust
   * bundle rather than the legacy trust store. Not set at all for a legacy-validated connection.
   *
   * <p>Downstream code that reads a {@code spiffe://} SAN off the peer certificate (e.g. for
   * logging or identification) should check this attribute before treating that SAN as a
   * verified SPIFFE identity: the SAN by itself is just an unverified claim by whichever CA
   * issued the certificate, which may not be SPIRE-aware at all.
   */
  public static final String SPIFFE_VERIFIED_SESSION_ATTRIBUTE =
      "io.confluent.rest.sslSpiffeVerified";

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
    return createSslContextFactory(sslConfig, null);
  }

  public static SslContextFactory createSslContextFactory(
      SslConfig sslConfig,
      X509Source x509Source) {
    SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
    
    /*
     * When sslConfig.getIsSpireEnabled() == true, the application is expected to use SPIFFE/SPIRE 
     * for mTLS, which means it will get its certificates and keys from the SPIFFE Workload API 
     * (via X509Source), not from a traditional Java keystore.
     * 
     * X509Source establishes a connection to the Workload API and sets up a watcher to monitor for 
     * updates to the X.509 SVIDs and bundles. This watcher listens for changes and automatically 
     * updates the in-memory certificates when new ones are issued, ensuring that expired 
     * certificates are replaced seamlessly.
     * 
     */
    if (sslConfig.getIsSpireEnabled()) {
      if (sslConfig.getIsSpireTrustOnlyEnabled()) {
        if (sslConfig.getKeyStorePath().isEmpty()) {
          throw new ConfigException(
              RestConfig.SSL_KEYSTORE_LOCATION_CONFIG + " must be set when "
                  + RestConfig.SSL_SPIRE_TRUST_ONLY_ENABLED_CONFIG + " is enabled.");
        }
        log.info("SPIRE trust-only SSL mode enabled");
        sslContextFactory = createSpireTrustOnlyServer(x509Source);
      } else {
        log.info("SPIRE SSL mode enabled");
        configureSpiffeSslContext(sslContextFactory, x509Source);
      }
    }

    if (!sslConfig.getKeyStorePath().isEmpty()) {
      configureKeyStore(sslContextFactory, sslConfig);
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

  private static void configureSpiffeSslContext(
      SslContextFactory.Server sslContextFactory,
      X509Source x509Source) {

    /*
     * The underlying 'java-spiffe' library does not support complex pattern matching for SPIFFE 
     * IDs. Supplying a static list of accepted IDs is too restrictive for dynamic environments 
     * where all client IDs may not be known in advance.
     *
     * To provide more flexible authorization, a callback function can be supplied to the 
     * constructor. If the callback is provided, it will be invoked to authorize a client's 
     * SPIFFE ID. If the callback is null, any SPIFFE ID will be accepted.
     *
     * This approach serves as an interim solution until a more comprehensive authorization 
     * layer is implemented.
     */
    SpiffeSslContextFactory.SslContextOptions options = SpiffeSslContextFactory.SslContextOptions
        .builder()
        .x509Source(x509Source)
        .acceptAnySpiffeId()
        .build();

    try {
      SSLContext sslContext = SpiffeSslContextFactory.getSslContext(options);
      sslContextFactory.setSslContext(sslContext);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // SPIRE trust-only mode: subclass to override getTrustManagers(...) so the TrustManager
  // accepts either a SPIFFE SVID (validated against the SPIFFE bundle) or a traditional
  // certificate (validated against the configured, or JVM default, trust store) via
  // DualTrustManager. This lets a listener adopt trust-only mode before every client has been
  // switched to present a SPIFFE SVID. KeyManager continues to be loaded from the configured
  // keystore via Jetty's normal load() path.
  private static SslContextFactory.Server createSpireTrustOnlyServer(X509Source x509Source) {
    if (x509Source == null) {
      throw new RuntimeException(
          "X509Source must be provided when SPIRE trust-only SSL is enabled");
    }
    return new SslContextFactory.Server() {
      @Override
      protected TrustManager[] getTrustManagers(KeyStore trustStore,
                                                Collection<? extends CRL> crls) throws Exception {
        TrustManager[] spiffeTrustManagers = new SpiffeTrustManagerFactory()
            .engineGetTrustManagersAcceptAnySpiffeId(x509Source);
        // Jetty's own getTrustManagers(...) returns null (rather than the JVM default trust
        // managers) when no trust store is configured; it relies on SSLContext.init(...)
        // substituting the JVM default when passed a null TrustManager[] later on. Since we
        // need a concrete legacy TrustManager to combine with the SPIFFE one here, build that
        // JVM default explicitly instead of forwarding a null onward.
        TrustManager[] legacyTrustManagers = super.getTrustManagers(trustStore, crls);
        if (legacyTrustManagers == null) {
          TrustManagerFactory tmf =
              TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
          tmf.init((KeyStore) null);
          legacyTrustManagers = tmf.getTrustManagers();
        }
        return DualTrustManager.wrap(spiffeTrustManagers, legacyTrustManagers);
      }
    };
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

  private static void configureKeyStore(
      SslContextFactory.Server sslContextFactory,
      SslConfig sslConfig) {
    setSecurityStoreProps(sslConfig, sslContextFactory, true, false);
    sslContextFactory.setKeyManagerPassword(sslConfig.getKeyManagerPassword());

    if (!sslConfig.getKeyManagerFactoryAlgorithm().isEmpty()) {
      sslContextFactory.setKeyManagerFactoryAlgorithm(
          sslConfig.getKeyManagerFactoryAlgorithm());
    }

    if (sslConfig.getReloadOnKeyStoreChange()) {
      configureKeyStoreReload(sslContextFactory, sslConfig);
    }
  }

  private static void configureKeyStoreReload(
      SslContextFactory.Server sslContextFactory,
      SslConfig sslConfig) {
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
