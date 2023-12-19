package io.confluent.rest;

import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.config.types.Password;
import org.apache.kafka.common.errors.InvalidConfigurationException;
import org.apache.kafka.test.TestUtils;
import org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
    Security.removeProvider(SslConfigs.FIPS_PROVIDER);
    Security.removeProvider(SslConfigs.FIPS_SSL_PROVIDER);
  }

  @Test
  public void testPemStoreSuccessNonFIPS() throws Exception {
    Map<String, String> rawConfig = new HashMap<>();
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_LOCATION_CONFIG, asFile(asString(KEY, CERTCHAIN)));
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_TYPE_CONFIG, PEM_TYPE);
    RestConfig rConfig = new TestRestConfig(rawConfig);
    SslContextFactory factory = SslFactory.createSslContextFactory(new SslConfig(rConfig));
    assertNotNull(factory.getKeyStore());
    assertEquals(SslConfigs.NONFIPS_KEYSTORE_TYPE, factory.getKeyStore().getType());
    verifyKeyStore(factory.getKeyStore(), null, false);
  }

  @Test
  public void testPemStoreSuccessKeyPasswordNonFIPS() throws Exception {
    Map<String, String> rawConfig = new HashMap<>();
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_LOCATION_CONFIG, asFile(asString(ENCRYPTED_KEY, CERTCHAIN)));
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_TYPE_CONFIG, PEM_TYPE);
    rawConfig.put(TestRestConfig.SSL_KEY_PASSWORD_CONFIG, KEY_PASSWORD.value());
    RestConfig rConfig = new TestRestConfig(rawConfig);
    SslContextFactory factory = SslFactory.createSslContextFactory(new SslConfig(rConfig));
    assertNotNull(factory.getKeyStore());
    assertEquals(SslConfigs.NONFIPS_KEYSTORE_TYPE, factory.getKeyStore().getType());
    verifyKeyStore(factory.getKeyStore(), KEY_PASSWORD, false);
  }

  @Test
  public void testBadPemStoreFailureNonFIPS() throws Exception {
    Map<String, String> rawConfig = new HashMap<>();
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_LOCATION_CONFIG, asFile(asString(KEY)));
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_TYPE_CONFIG, PEM_TYPE);
    RestConfig rConfig = new TestRestConfig(rawConfig);
    assertThrows(InvalidConfigurationException.class, () -> SslFactory.createSslContextFactory(new SslConfig(rConfig)));
  }

  @Test
  public void testPemSuccessFIPSProvider() throws Exception {
    Security.insertProviderAt(new BouncyCastleFipsProvider(), 1); //security provider
    Security.insertProviderAt(new BouncyCastleJsseProvider(), 2); //ssl provider

    Map<String, String> rawConfig = new HashMap<>();
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_LOCATION_CONFIG, asFile(asString(KEY, CERTCHAIN)));
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_TYPE_CONFIG, PEM_TYPE);
    rawConfig.put(TestRestConfig.SSL_PROVIDER_CONFIG, SslConfigs.FIPS_SSL_PROVIDER);
    RestConfig rConfig = new TestRestConfig(rawConfig);
    SslContextFactory factory = SslFactory.createSslContextFactory(new SslConfig(rConfig));
    KeyStore ks = factory.getKeyStore();
    assertNotNull(ks);
    assertEquals(SslConfigs.FIPS_KEYSTORE_TYPE, ks.getType());
    verifyKeyStore(ks, null, true);
  }

  @Test
  public void testPemStoreSuccessKeyPasswordFIPS() throws Exception {
    Security.insertProviderAt(new BouncyCastleFipsProvider(), 1); //security provider
    Security.insertProviderAt(new BouncyCastleJsseProvider(), 2); //ssl provider

    Map<String, String> rawConfig = new HashMap<>();
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_LOCATION_CONFIG, asFile(asString(ENCRYPTED_KEY, CERTCHAIN)));
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_TYPE_CONFIG, PEM_TYPE);
    rawConfig.put(TestRestConfig.SSL_KEY_PASSWORD_CONFIG, KEY_PASSWORD.value());
    RestConfig rConfig = new TestRestConfig(rawConfig);
    SslContextFactory factory = SslFactory.createSslContextFactory(new SslConfig(rConfig));
    assertNotNull(factory.getKeyStore());
    assertEquals(SslConfigs.NONFIPS_KEYSTORE_TYPE, factory.getKeyStore().getType());
    verifyKeyStore(factory.getKeyStore(), KEY_PASSWORD, false);
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
    assertEquals(isBcfks ? SslConfigs.FIPS_KEYSTORE_TYPE : SslConfigs.NONFIPS_KEYSTORE_TYPE, ks.getType());
  }
}
