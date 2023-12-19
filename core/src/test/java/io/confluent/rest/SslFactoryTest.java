package io.confluent.rest;

import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.config.types.Password;
import org.apache.kafka.common.errors.InvalidConfigurationException;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.Test;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
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
  private static final String PEM_KEYSTORE_NAME_PREFIX = "pemKeyStore";
  private static final String PEM_KEYSTORE_NAME_SUFFIX = ".pem";

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

  @Test
  public void testPemStoreSuccessNonFIPS() throws Exception {
    Path pemPath = getPemStorePath("");
    writePemFile(pemPath, true);
    Map<String, String> rawConfig = new HashMap<>();
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_LOCATION_CONFIG, pemPath.toAbsolutePath().toString());
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_TYPE_CONFIG, PEM_TYPE);
    RestConfig rConfig = new TestRestConfig(rawConfig);
    SslContextFactory factory = SslFactory.createSslContextFactory(new SslConfig(rConfig));
    assertNotNull(factory.getKeyStore());
    assertEquals(SslConfigs.NONFIPS_KEYSTORE_TYPE, factory.getKeyStore().getType());
    verifyKeyStore(factory.getKeyStore(), null, false);
  }

  @Test
  public void testBadPemStoreFailureNonFIPS() throws Exception {
    Path pemPath = getPemStorePath("");
    writePemFile(pemPath, false);
    Map<String, String> rawConfig = new HashMap<>();
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_LOCATION_CONFIG, pemPath.toAbsolutePath().toString());
    rawConfig.put(TestRestConfig.SSL_KEYSTORE_TYPE_CONFIG, PEM_TYPE);
    RestConfig rConfig = new TestRestConfig(rawConfig);
    assertThrows(InvalidConfigurationException.class, () -> SslFactory.createSslContextFactory(new SslConfig(rConfig)));
  }

  private Path getPemStorePath(String prefix) throws IOException {
    prefix = prefix == null ? "" : prefix;
    return Files.createTempFile(prefix + PEM_KEYSTORE_NAME_PREFIX,
        PEM_KEYSTORE_NAME_SUFFIX);
  }

  private void writePemFile(Path path, boolean isValid) throws IOException {
    try (FileWriter fw = new FileWriter(path.toFile())) {
      if (!isValid) {
        fw.write("garbage");
      } else {
        fw.write(CA1);
        fw.write(CA2);
        fw.write(KEY);
      }
    }
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
