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

import java.util.List;

import static java.util.Objects.requireNonNull;

public final class SslConfig {

  public static final String TLS_CONSCRYPT = "Conscrypt";

  private static final SslConfig DEFAULT_CONFIG =
      new SslConfig(new RestConfig(RestConfig.baseConfigDef()));

  private final RestConfig restConfig;

  SslConfig(RestConfig restConfig) {
    this.restConfig = requireNonNull(restConfig);
  }

  public static SslConfig defaultConfig() {
    return DEFAULT_CONFIG;
  }

  public SslClientAuth getClientAuth() {
    String clientAuthentication =
        (String) restConfig.originals()
            .getOrDefault(RestConfig.SSL_CLIENT_AUTHENTICATION_CONFIG, "");
    switch (clientAuthentication) {
      case RestConfig.SSL_CLIENT_AUTHENTICATION_NONE:
        return SslClientAuth.NONE;
      case RestConfig.SSL_CLIENT_AUTHENTICATION_REQUESTED:
        return SslClientAuth.WANT;
      case RestConfig.SSL_CLIENT_AUTHENTICATION_REQUIRED:
        return SslClientAuth.NEED;
      default:
    }

    if (restConfig.getBoolean(RestConfig.SSL_CLIENT_AUTH_CONFIG)) {
      return SslClientAuth.NEED;
    } else {
      return SslClientAuth.NONE;
    }
  }

  public String getEndpointIdentificationAlgorithm() {
    return restConfig.getString(RestConfig.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG);
  }

  public List<String> getIncludeCipherSuites() {
    return restConfig.getList(RestConfig.SSL_CIPHER_SUITES_CONFIG);
  }

  public List<String> getIncludeProtocols() {
    return restConfig.getList(RestConfig.SSL_ENABLED_PROTOCOLS_CONFIG);
  }

  public String getKeyManagerFactoryAlgorithm() {
    return restConfig.getString(RestConfig.SSL_KEYMANAGER_ALGORITHM_CONFIG);
  }

  public String getKeyManagerPassword() {
    return restConfig.getPassword(RestConfig.SSL_KEY_PASSWORD_CONFIG).value();
  }

  public String getKeyStorePassword() {
    return restConfig.getPassword(RestConfig.SSL_KEYSTORE_PASSWORD_CONFIG).value();
  }

  public String getKeyStorePath() {
    return restConfig.getString(RestConfig.SSL_KEYSTORE_LOCATION_CONFIG);
  }

  public String getKeyStoreType() {
    return restConfig.getString(RestConfig.SSL_KEYSTORE_TYPE_CONFIG);
  }

  public String getProtocol() {
    return restConfig.getString(RestConfig.SSL_PROTOCOL_CONFIG);
  }

  public String getProvider() {
    return restConfig.getString(RestConfig.SSL_PROVIDER_CONFIG);
  }

  public boolean getReloadOnKeyStoreChange() {
    return restConfig.getBoolean(RestConfig.SSL_KEYSTORE_RELOAD_CONFIG);
  }

  public String getReloadOnKeyStoreChangePath() {
    if (!restConfig.getString(RestConfig.SSL_KEYSTORE_WATCH_LOCATION_CONFIG).isEmpty()) {
      return restConfig.getString(RestConfig.SSL_KEYSTORE_WATCH_LOCATION_CONFIG);
    } else {
      return getKeyStorePath();
    }
  }

  public String getTrustManagerFactoryAlgorithm() {
    return restConfig.getString(RestConfig.SSL_TRUSTMANAGER_ALGORITHM_CONFIG);
  }

  public String getTrustStorePassword() {
    return restConfig.getPassword(RestConfig.SSL_TRUSTSTORE_PASSWORD_CONFIG).value();
  }

  public String getTrustStorePath() {
    return restConfig.getString(RestConfig.SSL_TRUSTSTORE_LOCATION_CONFIG);
  }

  public String getTrustStoreType() {
    return restConfig.getString(RestConfig.SSL_TRUSTSTORE_TYPE_CONFIG);
  }
}
