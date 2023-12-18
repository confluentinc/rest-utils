/*
 * Copyright 2023 Confluent Inc.
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

import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.security.ssl.DefaultSslEngineFactory;

import java.security.KeyStore;
import java.util.Objects;

/**
 * This class provides util methods to handle Pem type keystore. The primary aim
 * is to encapsulate the DefaultSslEngineFactory invocation in a separate class.
 * Based on the provider type, an intermediate keystore of appropriate type will be
 * created.
 */
public class SslFactoryPemHelper {
  public static boolean isPemSecurityStore(String keyStoreType) {
    return Objects.equals(DefaultSslEngineFactory.PEM_TYPE, keyStoreType);
  }

  private static void checkAndThrowRuntimeException(String keyStoreType, String provider) {
    if (Objects.equals(SslConfigs.FIPS_SSL_PROVIDER, provider)) {
      if (!Objects.equals(DefaultSslEngineFactory.PEM_TYPE, keyStoreType)) {
        throw new RuntimeException(
            String.format("Only %s security store supported with %s ssl provider",
                DefaultSslEngineFactory.PEM_TYPE, SslConfigs.FIPS_SSL_PROVIDER));
      }
    }
  }

  public static KeyStore getKeyStoreFromPem(String keyStorePath, String keyStoreType,
                                            String provider, boolean isKeyStore) {
    checkAndThrowRuntimeException(keyStoreType, provider);
    DefaultSslEngineFactory.FileBasedPemStore store = new DefaultSslEngineFactory.FileBasedPemStore(
        keyStorePath, null, isKeyStore, useBcfks(provider));
    return store.get();
  }

  private static boolean useBcfks(String provider) {
    return Objects.equals(SslConfigs.FIPS_SSL_PROVIDER, provider);
  }

  public static String getKeyStoreType(String keyStoreType, String provider) {
    return useBcfks(provider) ? SslConfigs.FIPS_KEYSTORE_TYPE : keyStoreType;
  }
}
