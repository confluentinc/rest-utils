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
import org.apache.kafka.common.errors.InvalidConfigurationException;
import org.apache.kafka.test.TestUtils;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Non-FIPS specific tests for SslFactory, please see SslFactoryFipsTest for FIPS tests.
 * Please also keep tests in this class in sync with SslFactoryFipsTest.
 */
public class SslFactoryTest {
  protected String CA1;
  protected String CA2;
  protected String CERTCHAIN;
  protected String KEY;
  protected String ENCRYPTED_KEY;
  private static final String PEM_TYPE = "PEM";
  private static final Password KEY_PASSWORD = new Password("key-password");

  protected RestConfig config;

  @BeforeEach
  public void setUp() {
    try {
      Path path = Paths.get(Objects.requireNonNull(
          SslFactory.class.getClassLoader().getResource("certs/cert1.pem").toURI()));
      CA1 = new String(Files.readAllBytes(path));

      path = Paths.get(Objects.requireNonNull(
          SslFactory.class.getClassLoader().getResource("certs/cert2.pem").toURI()));
      CA2 = new String(Files.readAllBytes(path));

      path = Paths.get(Objects.requireNonNull(
          SslFactory.class.getClassLoader().getResource("certs/privkey_non_enc.pem").toURI()));
      KEY = new String(Files.readAllBytes(path));

      path = Paths.get(Objects.requireNonNull(
          SslFactory.class.getClassLoader().getResource("certs/privkey_enc.pem").toURI()));
      ENCRYPTED_KEY = new String(Files.readAllBytes(path));

      path = Paths.get(Objects.requireNonNull(
          SslFactory.class.getClassLoader().getResource("certs/cert_chain.pem").toURI()));
      CERTCHAIN = new String(Files.readAllBytes(path));
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  protected String getEncryptedKey() {
    return ENCRYPTED_KEY;
  }

  protected Password getKeyPassword() {
    return KEY_PASSWORD;
  }

  protected void setConfigs(Map<String, String> configs) {
    config = new RestConfig(RestConfig.baseConfigDef(), configs);
  }

  protected String getKeyStoreType() {
    return SslFactoryPemHelper.NONFIPS_KEYSTORE_TYPE;
  }

  @Test
  public void testPemKeyStoreSuccessKeyNoPassword() throws Exception {
    Map<String, String> rawConfig = new HashMap<>();
    rawConfig.put(RestConfig.SSL_KEYSTORE_LOCATION_CONFIG, asFile(asString(KEY, CERTCHAIN)));
    rawConfig.put(RestConfig.SSL_KEYSTORE_TYPE_CONFIG, PEM_TYPE);
    setConfigs(rawConfig);
    SslContextFactory factory = SslFactory.createSslContextFactory(new SslConfig(config));
    Assertions.assertNotNull(factory.getKeyStore());
    Assertions.assertEquals(getKeyStoreType(), factory.getKeyStore().getType());
    verifyKeyStore(factory.getKeyStore(), null);
  }

  @Test
  public void testPemKeyStoreSuccessKeyPassword() throws Exception {
    Map<String, String> rawConfig = new HashMap<>();
    rawConfig.put(RestConfig.SSL_KEYSTORE_LOCATION_CONFIG, asFile(asString(getEncryptedKey(), CERTCHAIN)));
    rawConfig.put(RestConfig.SSL_KEYSTORE_TYPE_CONFIG, PEM_TYPE);
    if (getKeyPassword() != null) {
      rawConfig.put(RestConfig.SSL_KEY_PASSWORD_CONFIG, getKeyPassword().value());
    }
    setConfigs(rawConfig);
    SslContextFactory factory = SslFactory.createSslContextFactory(new SslConfig(config));
    Assertions.assertNotNull(factory.getKeyStore());
    Assertions.assertEquals(getKeyStoreType(), factory.getKeyStore().getType());
    verifyKeyStore(factory.getKeyStore(), getKeyPassword());
  }

  @Test
  public void testBadPemKeyStoreFailure() throws Exception {
    Map<String, String> rawConfig = new HashMap<>();
    rawConfig.put(RestConfig.SSL_KEYSTORE_LOCATION_CONFIG, asFile(asString(KEY)));
    rawConfig.put(RestConfig.SSL_KEYSTORE_TYPE_CONFIG, PEM_TYPE);
    setConfigs(rawConfig);
    Assertions.assertThrows(InvalidConfigurationException.class, () -> SslFactory.createSslContextFactory(new SslConfig(config)));
  }

  @Test
  public void testPemKeyStoreReload() throws Exception {
    Map<String, String> rawConfig = new HashMap<>();
    String storeLocation = asFile(asString(getEncryptedKey(), CERTCHAIN));
    rawConfig.put(RestConfig.SSL_KEYSTORE_LOCATION_CONFIG, storeLocation);
    rawConfig.put(RestConfig.SSL_KEYSTORE_TYPE_CONFIG, PEM_TYPE);
    if (getKeyPassword() != null) {
      rawConfig.put(RestConfig.SSL_KEY_PASSWORD_CONFIG, getKeyPassword().value());
    }
    rawConfig.put(RestConfig.SSL_KEYSTORE_RELOAD_CONFIG, "true");
    setConfigs(rawConfig);
    SslContextFactory factory = SslFactory.createSslContextFactory(new SslConfig(config));
    Assertions.assertNotNull(factory.getKeyStore());
    Assertions.assertEquals(getKeyStoreType(), factory.getKeyStore().getType());
    verifyKeyStore(factory.getKeyStore(), getKeyPassword());

    TestUtils.waitForCondition(() -> !SslFactory.lastLoadFailure().isPresent(), "could not load keystore");

    // rewrite file (invalid)
    try (FileWriter writer = new FileWriter(storeLocation)) {
      writer.write(asString(KEY, CERTCHAIN));
      writer.flush();
    }

    // rewrite file (valid)
    try (FileWriter writer = new FileWriter(storeLocation)) {
      writer.write(asString(getEncryptedKey(), CERTCHAIN));
      writer.flush();
    }

    TestUtils.waitForCondition(() -> !SslFactory.lastLoadFailure().isPresent(), "keystore not loaded unexpectedly");

    verifyKeyStore(factory.getKeyStore(), getKeyPassword());
  }

  @Test
  public void testPemTrustStoreSuccessSingleCert() throws Exception {
    Map<String, String> rawConfig = new HashMap<>();
    rawConfig.put(RestConfig.SSL_TRUSTSTORE_LOCATION_CONFIG, asFile(asString(CA1)));
    rawConfig.put(RestConfig.SSL_TRUSTSTORE_TYPE_CONFIG, PEM_TYPE);
    setConfigs(rawConfig);
    SslContextFactory factory = SslFactory.createSslContextFactory(new SslConfig(config));
    Assertions.assertNotNull(factory.getTrustStore());
    Assertions.assertEquals(getKeyStoreType(), factory.getTrustStore().getType());
  }

  @Test
  public void testPemTrustStoreSuccessMultiCert() throws Exception {
    Map<String, String> rawConfig = new HashMap<>();
    rawConfig.put(RestConfig.SSL_TRUSTSTORE_LOCATION_CONFIG, asFile(asString(CA1, CA2)));
    rawConfig.put(RestConfig.SSL_TRUSTSTORE_TYPE_CONFIG, PEM_TYPE);
    setConfigs(rawConfig);
    SslContextFactory factory = SslFactory.createSslContextFactory(new SslConfig(config));
    Assertions.assertNotNull(factory.getTrustStore());
    Assertions.assertEquals(getKeyStoreType(), factory.getTrustStore().getType());
  }

  @Test
  public void testBadPemTrustStoreFailure() throws Exception {
    Map<String, String> rawConfig = new HashMap<>();
    rawConfig.put(RestConfig.SSL_TRUSTSTORE_LOCATION_CONFIG, asFile(asString(KEY)));
    rawConfig.put(RestConfig.SSL_TRUSTSTORE_TYPE_CONFIG, PEM_TYPE);
    setConfigs(rawConfig);
    Assertions.assertThrows(InvalidConfigurationException.class, () -> SslFactory.createSslContextFactory(new SslConfig(config)));
  }

  private String asString(String... pems) {
    StringBuilder builder = new StringBuilder();
    for (String pem : pems) {
      builder.append(pem);
      builder.append("\n");
    }
    return builder.toString().trim();
  }

  private String asFile(String pem) throws Exception {
    return TestUtils.tempFile(pem).getAbsolutePath();
  }

  private void verifyKeyStore(KeyStore ks, Password keyPassword) throws Exception {
    List<String> aliases = Collections.list(ks.aliases());
    Assertions.assertEquals(Collections.singletonList("kafka"), aliases);
    Assertions.assertNotNull(ks.getCertificate("kafka"), "Certificate not loaded");
    Assertions.assertNotNull(ks.getKey("kafka", keyPassword == null ? null : keyPassword.value().toCharArray()),
        "Private key not loaded");
    Assertions.assertEquals(getKeyStoreType(), ks.getType());
  }
}
