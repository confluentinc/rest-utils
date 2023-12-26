package io.confluent.rest;

import org.apache.kafka.common.config.types.Password;
import org.apache.kafka.common.errors.InvalidConfigurationException;
import org.apache.kafka.test.TestUtils;
import org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.Security;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class SslFactoryTest {
  private static String CA1;
  private static String CA2;
  private static String CERTCHAIN;
  private static String KEY;
  private static String ENCRYPTED_KEY;
  private static final String PEM_TYPE = "PEM";
  private static final Password KEY_PASSWORD = new Password("key-password");

  static {
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

  @AfterEach
  public void tearDown() {
    Security.removeProvider(SslFactoryPemHelper.FIPS_PROVIDER);
    Security.removeProvider(SslFactoryPemHelper.FIPS_SSL_PROVIDER);
  }

  @Test
  public void testPemKeyStoreSuccessKeyNoPasswordNonFIPS() throws Exception {
    Map<String, String> rawConfig = new HashMap<>();
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_LOCATION_CONFIG, asFile(asString(KEY, CERTCHAIN)));
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_TYPE_CONFIG, PEM_TYPE);
    RestConfig rConfig = new TestRestConfig(rawConfig);
    SslContextFactory factory = SslFactory.createSslContextFactory(new SslConfig(rConfig));
    assertNotNull(factory.getKeyStore());
    assertEquals(SslFactoryPemHelper.NONFIPS_KEYSTORE_TYPE, factory.getKeyStore().getType());
    verifyKeyStore(factory.getKeyStore(), null, false);
  }

  @Test
  public void testPemKeyStoreSuccessKeyPasswordNonFIPS() throws Exception {
    Map<String, String> rawConfig = new HashMap<>();
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_LOCATION_CONFIG, asFile(asString(ENCRYPTED_KEY, CERTCHAIN)));
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_TYPE_CONFIG, PEM_TYPE);
    rawConfig.put(TestRestConfig.SSL_KEY_PASSWORD_CONFIG, KEY_PASSWORD.value());
    RestConfig rConfig = new TestRestConfig(rawConfig);
    SslContextFactory factory = SslFactory.createSslContextFactory(new SslConfig(rConfig));
    assertNotNull(factory.getKeyStore());
    assertEquals(SslFactoryPemHelper.NONFIPS_KEYSTORE_TYPE, factory.getKeyStore().getType());
    verifyKeyStore(factory.getKeyStore(), KEY_PASSWORD, false);
  }

  @Test
  public void testBadPemKeyStoreFailureNonFIPS() throws Exception {
    Map<String, String> rawConfig = new HashMap<>();
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_LOCATION_CONFIG, asFile(asString(KEY)));
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_TYPE_CONFIG, PEM_TYPE);
    RestConfig rConfig = new TestRestConfig(rawConfig);
    assertThrows(InvalidConfigurationException.class, () -> SslFactory.createSslContextFactory(new SslConfig(rConfig)));
  }

  @Test
  public void testPemKeyStoreReloadNonFIPS() throws Exception {
    Map<String, String> rawConfig = new HashMap<>();
    String storeLocation = asFile(asString(ENCRYPTED_KEY, CERTCHAIN));
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_LOCATION_CONFIG, storeLocation);
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_TYPE_CONFIG, PEM_TYPE);
    rawConfig.put(TestRestConfig.SSL_KEY_PASSWORD_CONFIG, KEY_PASSWORD.value());
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_RELOAD_CONFIG, "true");
    RestConfig rConfig = new TestRestConfig(rawConfig);
    SslContextFactory factory = SslFactory.createSslContextFactory(new SslConfig(rConfig));
    assertNotNull(factory.getKeyStore());
    assertEquals(SslFactoryPemHelper.NONFIPS_KEYSTORE_TYPE, factory.getKeyStore().getType());
    verifyKeyStore(factory.getKeyStore(), KEY_PASSWORD, false);

    TestUtils.waitForCondition(() -> SslFactory.getFileWatcher() != null, "filewatcher not ready");

    // rewrite file (invalid)
    try (FileWriter writer = new FileWriter(storeLocation)) {
      writer.write(asFile(asString(KEY, CERTCHAIN)));
      writer.flush();
    }
    TestUtils.waitForCondition(() -> SslFactory.getFileWatcher().maybeGetException().isPresent(),
        "expected exception to be thrown");

    // rewrite file (valid)
    try (FileWriter writer = new FileWriter(storeLocation)) {
      writer.write(asFile(asString(ENCRYPTED_KEY, CERTCHAIN)));
      writer.flush();
    }

    verifyKeyStore(factory.getKeyStore(), KEY_PASSWORD, false);
  }

  @Test
  public void testPemKeyStoreSuccessKeyNoPasswordFIPS() throws Exception {
    Security.insertProviderAt(new BouncyCastleFipsProvider(), 1); //security provider
    Security.insertProviderAt(new BouncyCastleJsseProvider(), 2); //ssl provider

    Map<String, String> rawConfig = new HashMap<>();
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_LOCATION_CONFIG, asFile(asString(KEY, CERTCHAIN)));
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_TYPE_CONFIG, PEM_TYPE);
    rawConfig.put(TestRestConfig.SSL_PROVIDER_CONFIG, SslFactoryPemHelper.FIPS_SSL_PROVIDER);
    RestConfig rConfig = new TestRestConfig(rawConfig);
    SslContextFactory factory = SslFactory.createSslContextFactory(new SslConfig(rConfig));
    KeyStore ks = factory.getKeyStore();
    assertNotNull(ks);
    assertEquals(SslFactoryPemHelper.FIPS_KEYSTORE_TYPE, ks.getType());
    verifyKeyStore(ks, null, true);
  }

  @Test
  public void testPemKeyStoreSuccessKeyPasswordFIPS() throws Exception {
    Security.insertProviderAt(new BouncyCastleFipsProvider(), 1); //security provider
    Security.insertProviderAt(new BouncyCastleJsseProvider(), 2); //ssl provider

    Map<String, String> rawConfig = new HashMap<>();
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_LOCATION_CONFIG, asFile(asString(ENCRYPTED_KEY, CERTCHAIN)));
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_TYPE_CONFIG, PEM_TYPE);
    rawConfig.put(TestRestConfig.SSL_KEY_PASSWORD_CONFIG, KEY_PASSWORD.value());
    rawConfig.put(TestRestConfig.SSL_PROVIDER_CONFIG, SslFactoryPemHelper.FIPS_SSL_PROVIDER);
    RestConfig rConfig = new TestRestConfig(rawConfig);
    SslContextFactory factory = SslFactory.createSslContextFactory(new SslConfig(rConfig));
    assertNotNull(factory.getKeyStore());
    assertEquals(SslFactoryPemHelper.FIPS_KEYSTORE_TYPE, factory.getKeyStore().getType());
    verifyKeyStore(factory.getKeyStore(), KEY_PASSWORD, true);
  }

  @Test
  public void testPemKeyStoreReloadFIPS() throws Exception {
    Security.insertProviderAt(new BouncyCastleFipsProvider(), 1); //security provider
    Security.insertProviderAt(new BouncyCastleJsseProvider(), 2); //ssl provider

    Map<String, String> rawConfig = new HashMap<>();
    String storeLocation = asFile(asString(ENCRYPTED_KEY, CERTCHAIN));
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_LOCATION_CONFIG, storeLocation);
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_TYPE_CONFIG, PEM_TYPE);
    rawConfig.put(TestRestConfig.SSL_KEY_PASSWORD_CONFIG, KEY_PASSWORD.value());
    rawConfig.put(TestRestConfig.SSL_PROVIDER_CONFIG, SslFactoryPemHelper.FIPS_SSL_PROVIDER);
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_RELOAD_CONFIG, "true");

    RestConfig rConfig = new TestRestConfig(rawConfig);
    SslContextFactory factory = SslFactory.createSslContextFactory(new SslConfig(rConfig));
    assertNotNull(factory.getKeyStore());
    assertEquals(SslFactoryPemHelper.FIPS_KEYSTORE_TYPE, factory.getKeyStore().getType());
    verifyKeyStore(factory.getKeyStore(), KEY_PASSWORD, true);

    TestUtils.waitForCondition(() -> SslFactory.getFileWatcher() != null, "filewatcher not ready");
    // rewrite file (invalid)
    try (FileWriter writer = new FileWriter(storeLocation)) {
      writer.write(asFile(asString(KEY, CERTCHAIN)));
      writer.flush();
    }
    TestUtils.waitForCondition(() -> SslFactory.getFileWatcher().maybeGetException().isPresent(),
        "expected exception to be thrown");
    // rewrite file (valid)
    try (FileWriter writer = new FileWriter(storeLocation)) {
      writer.write(asFile(asString(ENCRYPTED_KEY, CERTCHAIN)));
      writer.flush();
    }

    verifyKeyStore(factory.getKeyStore(), KEY_PASSWORD, true);
  }

  @Test
  public void testPemTrustStoreSuccessSingleCertNonFIPS() throws Exception {
    Map<String, String> rawConfig = new HashMap<>();
    rawConfig.put(TestRestConfig.SSL_TRUSTSTORE_LOCATION_CONFIG, asFile(asString(CA1)));
    rawConfig.put(TestRestConfig.SSL_TRUSTSTORE_TYPE_CONFIG, PEM_TYPE);
    RestConfig rConfig = new TestRestConfig(rawConfig);
    SslContextFactory factory = SslFactory.createSslContextFactory(new SslConfig(rConfig));
    assertNotNull(factory.getTrustStore());
    assertEquals(SslFactoryPemHelper.NONFIPS_KEYSTORE_TYPE, factory.getTrustStore().getType());
  }

  @Test
  public void testPemTrustStoreSuccessMultiCertNonFIPS() throws Exception {
    Map<String, String> rawConfig = new HashMap<>();
    rawConfig.put(TestRestConfig.SSL_TRUSTSTORE_LOCATION_CONFIG, asFile(asString(CA1, CA2)));
    rawConfig.put(TestRestConfig.SSL_TRUSTSTORE_TYPE_CONFIG, PEM_TYPE);
    RestConfig rConfig = new TestRestConfig(rawConfig);
    SslContextFactory factory = SslFactory.createSslContextFactory(new SslConfig(rConfig));
    assertNotNull(factory.getTrustStore());
    assertEquals(SslFactoryPemHelper.NONFIPS_KEYSTORE_TYPE, factory.getTrustStore().getType());
  }

  @Test
  public void testBadPemTrustStoreFailureNonFIPS() throws Exception {
    Map<String, String> rawConfig = new HashMap<>();
    rawConfig.put(TestRestConfig.SSL_TRUSTSTORE_LOCATION_CONFIG, asFile(asString(KEY)));
    rawConfig.put(TestRestConfig.SSL_TRUSTSTORE_TYPE_CONFIG, PEM_TYPE);
    RestConfig rConfig = new TestRestConfig(rawConfig);
    assertThrows(InvalidConfigurationException.class, () -> SslFactory.createSslContextFactory(new SslConfig(rConfig)));
  }

  @Test
  public void testPemTrustStoreSuccessSingleCertFIPS() throws Exception {
    Security.insertProviderAt(new BouncyCastleFipsProvider(), 1); //security provider
    Security.insertProviderAt(new BouncyCastleJsseProvider(), 2); //ssl provider

    Map<String, String> rawConfig = new HashMap<>();
    rawConfig.put(TestRestConfig.SSL_TRUSTSTORE_LOCATION_CONFIG, asFile(asString(CA1)));
    rawConfig.put(TestRestConfig.SSL_TRUSTSTORE_TYPE_CONFIG, PEM_TYPE);
    rawConfig.put(TestRestConfig.SSL_PROVIDER_CONFIG, SslFactoryPemHelper.FIPS_SSL_PROVIDER);
    RestConfig rConfig = new TestRestConfig(rawConfig);
    SslContextFactory factory = SslFactory.createSslContextFactory(new SslConfig(rConfig));
    KeyStore ks = factory.getTrustStore();
    assertNotNull(factory.getTrustStore());
    assertEquals(SslFactoryPemHelper.FIPS_KEYSTORE_TYPE, factory.getTrustStore().getType());
  }

  @Test
  public void testPemTrustStoreSuccessMultiCertFIPS() throws Exception {
    Security.insertProviderAt(new BouncyCastleFipsProvider(), 1); //security provider
    Security.insertProviderAt(new BouncyCastleJsseProvider(), 2); //ssl provider

    Map<String, String> rawConfig = new HashMap<>();
    rawConfig.put(TestRestConfig.SSL_TRUSTSTORE_LOCATION_CONFIG, asFile(asString(CA1, CA2)));
    rawConfig.put(TestRestConfig.SSL_TRUSTSTORE_TYPE_CONFIG, PEM_TYPE);
    rawConfig.put(TestRestConfig.SSL_PROVIDER_CONFIG, SslFactoryPemHelper.FIPS_SSL_PROVIDER);
    RestConfig rConfig = new TestRestConfig(rawConfig);
    SslContextFactory factory = SslFactory.createSslContextFactory(new SslConfig(rConfig));
    assertNotNull(factory.getTrustStore());
    assertEquals(SslFactoryPemHelper.FIPS_KEYSTORE_TYPE, factory.getTrustStore().getType());
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

  private void verifyKeyStore(KeyStore ks, Password keyPassword, boolean isBcfks) throws Exception {
    List<String> aliases = Collections.list(ks.aliases());
    assertEquals(Collections.singletonList("kafka"), aliases);
    assertNotNull(ks.getCertificate("kafka"), "Certificate not loaded");
    assertNotNull(ks.getKey("kafka", keyPassword == null ? null : keyPassword.value().toCharArray()),
        "Private key not loaded");
    assertEquals(isBcfks ? SslFactoryPemHelper.FIPS_KEYSTORE_TYPE : SslFactoryPemHelper.NONFIPS_KEYSTORE_TYPE, ks.getType());
  }
}
