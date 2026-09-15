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
import io.spiffe.provider.SpiffeKeyManagerFactory;
import io.spiffe.provider.SpiffeSslContextFactory;
import io.spiffe.provider.SpiffeTrustManagerFactory;
import io.spiffe.workloadapi.X509Source;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.config.types.Password;
import org.apache.kafka.common.metrics.Metrics;
import org.conscrypt.OpenSSLProvider;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.CRL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;

// CHECKSTYLE_RULES.OFF: ClassDataAbstractionCoupling
public final class SslFactory {
  // CHECKSTYLE_RULES.ON: ClassDataAbstractionCoupling

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
    return createSslContextFactory(sslConfig, null, null, null);
  }

  public static SslContextFactory createSslContextFactory(
      SslConfig sslConfig,
      X509Source x509Source) {
    return createSslContextFactory(sslConfig, x509Source, null, null);
  }

  /**
   * Build the listener's {@link SslContextFactory}. The optional {@code metricsSupplier} /
   * {@code metricsTagsSupplier} are resolved lazily at handshake time and used only on a
   * full-SPIRE listener with a non-empty SPIFFE-ID allowlist, to record per-listener SPIRE
   * client-cert validation counters (see {@link SpireSpiffeAllowlistTrustManager}). Both may be
   * null (no metrics recorded).
   */
  public static SslContextFactory createSslContextFactory(
      SslConfig sslConfig,
      X509Source x509Source,
      Supplier<Metrics> metricsSupplier,
      Supplier<Map<String, String>> metricsTagsSupplier) {
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
        validateSpireTrustOnlyConfig(sslConfig);
        log.info("SPIRE trust-only SSL mode enabled");
        sslContextFactory = createSpireTrustOnlyServer(x509Source);
      } else {
        log.info("SPIRE SSL mode enabled");
        configureSpiffeSslContext(sslContextFactory, x509Source,
            sslConfig.getAcceptedSpiffeIdPatterns(), metricsSupplier, metricsTagsSupplier);
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

  // Full-SPIRE mode (ssl.spire.enabled=true, ssl.spire.trust.only.enabled=false): the server
  // presents its own SVID (KeyManager from the X509Source) and validates client certs against the
  // live SPIRE bundle (TrustManager from the X509Source).
  //
  // Two behaviors, selected by whether an accepted-SPIFFE-ID allowlist is configured:
  //   * allowlist EMPTY (default): accept any SVID that chains to the bundle. Uses the java-spiffe
  //     helper unchanged: pre-existing behavior.
  //   * allowlist NON-EMPTY (a full-SPIRE listener that restricts callers by SPIFFE ID):
  //     build the SSLContext explicitly so the SPIFFE TrustManager can be wrapped
  //     in a SpireSpiffeAllowlistTrustManager that additionally enforces the SPIFFE-ID allowlist
  //     and records per-listener validation metrics. The java-spiffe helper's acceptAnySpiffeId /
  //     callback path is bypassed here because it offers neither regex allowlisting nor metric
  //     hooks.
  private static void configureSpiffeSslContext(
      SslContextFactory.Server sslContextFactory,
      X509Source x509Source,
      List<String> acceptedSpiffeIdPatterns,
      Supplier<Metrics> metricsSupplier,
      Supplier<Map<String, String>> metricsTagsSupplier) {

    List<Pattern> compiledAcceptedSpiffeIds = compilePatterns(acceptedSpiffeIdPatterns);

    if (compiledAcceptedSpiffeIds.isEmpty()) {
      // No allowlist configured: accept any SPIFFE ID that chains to the bundle (unchanged).
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
      return;
    }

    // Allowlist configured: server SVID from SPIRE + SPIFFE trust wrapped with allowlist + metrics.
    log.info("SPIRE SSL allowlist enforcement enabled ({} accepted SPIFFE-ID pattern(s))",
        compiledAcceptedSpiffeIds.size());
    try {
      KeyManager[] keyManagers =
          new SpiffeKeyManagerFactory().engineGetKeyManagers(x509Source);
      TrustManager[] spiffeTrustManagers =
          new SpiffeTrustManagerFactory().engineGetTrustManagersAcceptAnySpiffeId(x509Source);
      TrustManager[] wrapped = new TrustManager[spiffeTrustManagers.length];
      for (int i = 0; i < spiffeTrustManagers.length; i++) {
        wrapped[i] = (spiffeTrustManagers[i] instanceof X509ExtendedTrustManager)
            ? new SpireSpiffeAllowlistTrustManager(
                (X509ExtendedTrustManager) spiffeTrustManagers[i],
                x509Source,
                compiledAcceptedSpiffeIds,
                metricsSupplier,
                metricsTagsSupplier)
            : spiffeTrustManagers[i];
      }
      SSLContext sslContext = SSLContext.getInstance("TLS");
      sslContext.init(keyManagers, wrapped, null);
      sslContextFactory.setSslContext(sslContext);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static void validateSpireTrustOnlyConfig(SslConfig sslConfig) {
    if (sslConfig.getKeyStorePath().isEmpty()) {
      throw new ConfigException(
          RestConfig.SSL_KEYSTORE_LOCATION_CONFIG + " must be set when "
              + RestConfig.SSL_SPIRE_TRUST_ONLY_ENABLED_CONFIG + " is enabled.");
    }
    if (sslConfig.getClientAuth() == SslClientAuth.NEED) {
      throw new ConfigException(
          RestConfig.SSL_CLIENT_AUTHENTICATION_CONFIG + "="
              + RestConfig.SSL_CLIENT_AUTHENTICATION_REQUIRED + " is incompatible with "
              + RestConfig.SSL_SPIRE_TRUST_ONLY_ENABLED_CONFIG + ": on this listener, a "
              + "non-SPIFFE client certificate is not validated at all, so requiring a "
              + "client certificate would accept any certificate without verifying it.");
    }
  }

  // Compile the accepted-SPIFFE-ID regex allowlist once at startup (not per handshake). Empty or
  // blank entries are skipped. An empty result means "no allowlist configured".
  private static List<Pattern> compilePatterns(List<String> patterns) {
    if (patterns == null || patterns.isEmpty()) {
      return List.of();
    }
    List<Pattern> compiled = new ArrayList<>(patterns.size());
    for (String p : patterns) {
      if (p != null && !p.isEmpty()) {
        compiled.add(Pattern.compile(p));
      }
    }
    return compiled;
  }

  // SPIRE trust-only mode: subclass to override getTrustManagers(...) with a
  // SpireOptionalTrustManager that validates spiffe:// SAN certs against the SPIFFE bundle and
  // skips validation entirely for any other certificate. KeyManager continues to be loaded from
  // the configured keystore via Jetty's normal load() path.
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
        return SpireOptionalTrustManager.wrap(spiffeTrustManagers);
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
