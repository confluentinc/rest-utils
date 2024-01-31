/*
 * Copyright 2024 Confluent Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.confluent.rest;

import org.apache.kafka.common.config.types.Password;
import org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Security;
import java.util.Map;
import java.util.Objects;

public class SslFactoryFipsTest extends SslFactoryTest {
  @BeforeAll
  public static void setupAll() {
    Security.insertProviderAt(new BouncyCastleFipsProvider(), 1);
    Security.insertProviderAt(new BouncyCastleJsseProvider(), 2);
    org.bouncycastle.crypto.CryptoServicesRegistrar.setApprovedOnlyMode(true);
  }

  @AfterAll
  public static void tearDownAll() {
    Security.removeProvider(SslFactoryPemHelper.FIPS_PROVIDER);
    Security.removeProvider(SslFactoryPemHelper.FIPS_SSL_PROVIDER);
  }

  @BeforeEach
  public void setUp() {
    try {
      // load resources from current jar
      Path path = Paths.get(Objects.requireNonNull(
          SslFactoryFipsTest.class.getClassLoader().getResource("certs/cert1.pem").toURI()));
      CA1 = new String(Files.readAllBytes(path));

      path = Paths.get(Objects.requireNonNull(
          SslFactoryFipsTest.class.getClassLoader().getResource("certs/cert2.pem").toURI()));
      CA2 = new String(Files.readAllBytes(path));

      path = Paths.get(Objects.requireNonNull(
          SslFactoryFipsTest.class.getClassLoader().getResource("certs/privkey_non_enc.pem").toURI()));
      KEY = new String(Files.readAllBytes(path));

      path = Paths.get(Objects.requireNonNull(
          SslFactoryFipsTest.class.getClassLoader().getResource("certs/privkey_enc.pem").toURI()));
      ENCRYPTED_KEY = new String(Files.readAllBytes(path));

      path = Paths.get(Objects.requireNonNull(
          SslFactoryFipsTest.class.getClassLoader().getResource("certs/cert_chain.pem").toURI()));
      CERTCHAIN = new String(Files.readAllBytes(path));
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  @Override
  protected void setConfigs(Map<String, String> configs) {
    configs.put(RestConfig.SSL_PROVIDER_CONFIG, SslFactoryPemHelper.FIPS_SSL_PROVIDER);
    config = new RestConfig(RestConfig.baseConfigDef(), configs);
  }

  @Override
  protected String getKeyStoreType() {
    return SslFactoryPemHelper.FIPS_KEYSTORE_TYPE;
  }

  @Override
  protected String getEncryptedKey() {
    return KEY;
  }

  protected Password getKeyPassword() {
    return null;
  }
}
