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
import io.spiffe.bundle.x509bundle.X509Bundle;
import io.spiffe.spiffeid.SpiffeId;
import io.spiffe.spiffeid.TrustDomain;
import io.spiffe.svid.x509svid.X509Svid;
import io.spiffe.workloadapi.DefaultX509Source;
import io.spiffe.workloadapi.X509Source;
import org.apache.kafka.common.config.types.Password;
import org.conscrypt.OpenSSLProvider;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Optional;
import java.util.Set;
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

  private static KeyStore createKeyStore(X509Svid svid) throws Exception {
    KeyStore keyStore = KeyStore.getInstance("JKS");
    keyStore.load(null, null);

    keyStore.setKeyEntry("svid-key",
            svid.getPrivateKey(),
            "changeit".toCharArray(),
            svid.getChain().toArray(new X509Certificate[0]));
    return keyStore;
  }

  private static KeyStore createTrustStore(X509Source x509Source) throws Exception {


    X509Svid svid = x509Source.getX509Svid();
    TrustDomain trustDomain = svid.getSpiffeId().getTrustDomain();

    // Get the bundle (root/intermediate authorities) for this trust domain
    X509Bundle bundle = x509Source.getBundleForTrustDomain(trustDomain);

    // Create in-memory trust store
    KeyStore trustStore = KeyStore.getInstance("JKS");
    trustStore.load(null, null);

    int i = 0;
    for (X509Certificate authority : bundle.getX509Authorities()) {
        trustStore.setCertificateEntry("authority-" + i, authority);
        i++;
    }

    return trustStore;
  }

  public static SslContextFactory createSslContextFactory(SslConfig sslConfig) {
    SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();

    // todo: make the socketPath passing from sslConfig maybe?
    // todo: enable the following only ppl select the spire option
    DefaultX509Source.X509SourceOptions x509SourceOptions = DefaultX509Source.X509SourceOptions
            .builder()
            .spiffeSocketPath("tcp://127.0.0.1:31523")
            .svidPicker(list -> list.get(list.size()-1))
            .build();
    try (X509Source x509Source = DefaultX509Source.newSource(x509SourceOptions)) {
      X509Svid svid = x509Source.getX509Svid();
      
      // for spire
//      KeyStore keyStore = createKeyStore(svid);
//      KeyStore trustStore = createTrustStore(x509Source);
//      sslContextFactory.setKeyStore(keyStore);
//      sslContextFactory.setKeyStorePassword("changeit");
//      sslContextFactory.setTrustStore(trustStore);
//      sslContextFactory.setTrustStorePassword("changeit");


      KeyStore keyStore = KeyStore.getInstance("JKS");
      keyStore.load(null, null);
      keyStore.setKeyEntry("svid", svid.getPrivateKey(), "changeit".toCharArray(),
              svid.getChain().toArray(new X509Certificate[0]));

      KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
      kmf.init(keyStore, "changeit".toCharArray());

      SSLContext sslContext = SSLContext.getInstance("TLS");
      TrustManager[] trustManagers = new TrustManager[] {
              new SpiffeTrustManager(Set.of(
                      SpiffeId.parse("spiffe://example.org/test-workload"),
                      SpiffeId.parse("spiffe://example.org/client2")
              ))
      };

      sslContext.init(kmf.getKeyManagers(), trustManagers, null);

      sslContextFactory.setSslContext(sslContext);
      sslContextFactory.setNeedClientAuth(true); // Enforce mTLS

    } catch (Exception e) {
      log.warn("Failed to initialize SPIFFE X509 source: {}", e.getMessage());
    }

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
