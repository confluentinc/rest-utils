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

import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.config.types.Password;
import org.apache.kafka.common.errors.InvalidConfigurationException;
import org.apache.kafka.common.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class provides util methods to handle Pem type keystore.
 * Based on the provider type, an intermediate keystore of appropriate type will be
 * created.
 */
public class SslFactoryPemHelper {

  public static final String FIPS_KEYSTORE_TYPE = "BCFKS";
  public static final String NONFIPS_KEYSTORE_TYPE = "PKCS12";
  public static final String FIPS_SSL_PROVIDER = "BCJSSE";
  public static final String FIPS_PROVIDER = "BCFIPS";
  public static final String PEM_TYPE = "PEM";

  private static final Logger log = LoggerFactory.getLogger(SslFactoryPemHelper.class);

  public static boolean isPemSecurityStore(String keyStoreType) {
    return Objects.equals(PEM_TYPE, keyStoreType);
  }

  public static KeyStore getKeyStoreFromPem(String keyStorePath, String keyStoreType,
                                            Password keyPassword, String provider,
                                            boolean isKeyStore) {
    if (Objects.equals(FIPS_SSL_PROVIDER, provider)) {
      if (!Objects.equals(PEM_TYPE, keyStoreType)) {
        throw new RuntimeException(
            String.format("Only %s security store supported with %s ssl provider",
                PEM_TYPE, FIPS_SSL_PROVIDER));
      }
    }
    if (keyPassword.value().equals("")) {
      keyPassword = null;
    }
    FileBasedPemStore store = new FileBasedPemStore(
        keyStorePath, keyPassword, isKeyStore, useBcfks(provider));
    return store.get();
  }

  private static boolean useBcfks(String provider) {
    return Objects.equals(FIPS_SSL_PROVIDER, provider);
  }

  public static String getKeyStoreType(String provider) {
    return useBcfks(provider) ? FIPS_KEYSTORE_TYPE : NONFIPS_KEYSTORE_TYPE;
  }

  interface SecurityStore {
    KeyStore get();

    char[] keyPassword();

    boolean modified();
  }

  // package access for testing
  static class FileBasedStore implements SecurityStore {
    private final String type;
    protected final String path;
    private final Password password;
    protected final Password keyPassword;
    private Long fileLastModifiedMs;
    private KeyStore keyStore;
    protected final boolean useBcfks;

    FileBasedStore(String type, String path, Password password, Password keyPassword,
                   boolean isKeyStore, boolean useBcfks) {
      this.type = Objects.requireNonNull(type, "type must not be null");
      this.path = path;
      this.password = password;
      this.keyPassword = keyPassword;
      this.useBcfks = useBcfks;
      this.reloadStore(isKeyStore);
    }

    public void reloadStore(final boolean isKeyStore) {
      fileLastModifiedMs = lastModifiedMs(path);
      keyStore = load(isKeyStore);
    }

    @Override
    public KeyStore get() {
      return keyStore;
    }

    @Override
    public char[] keyPassword() {
      Password passwd = keyPassword != null ? keyPassword : password;
      return passwd == null ? null : passwd.value().toCharArray();
    }

    /**
     * Loads this keystore
     *
     * @return the keystore
     * @throws KafkaException if the file could not be read or if the keystore could not be loaded
     *                        using the specified configs (e.g. if the pass or ks type is invalid)
     */
    protected KeyStore load(boolean isKeyStore) {
      try (InputStream in = Files.newInputStream(Paths.get(path))) {
        KeyStore ks = SecurityFactory.getKeyStoreInstance(type, useBcfks);
        // If a password is not set access to the truststore is still available,
        // but integrity checking is disabled.
        char[] passwordChars = password != null ? password.value().toCharArray() : null;
        ks.load(in, passwordChars);
        return ks;
      } catch (GeneralSecurityException | IOException e) {
        throw new KafkaException("Failed to load SSL keystore " + path + " of type " + type, e);
      }
    }

    private Long lastModifiedMs(String path) {
      try {
        return Files.getLastModifiedTime(Paths.get(path)).toMillis();
      } catch (IOException e) {
        log.error("Modification time of key store could not be obtained: " + path, e);
        return null;
      }
    }

    public boolean modified() {
      Long modifiedMs = lastModifiedMs(path);
      return modifiedMs != null && !Objects.equals(modifiedMs, this.fileLastModifiedMs);
    }

    @Override
    public String toString() {
      return "SecurityStore("
          + "path=" + path
          + ", modificationTime="
          + (fileLastModifiedMs == null ? null : new Date(fileLastModifiedMs)) + ")";
    }
  }

  public static class FileBasedPemStore extends FileBasedStore {
    public FileBasedPemStore(String path, Password keyPassword,
                             boolean isKeyStore, boolean useBcfks) {
      super(PEM_TYPE, path, null, keyPassword, isKeyStore, useBcfks);
    }

    @Override
    protected KeyStore load(boolean isKeyStore) {
      try {
        Password storeContents = new Password(Utils.readFileAsString(path));
        PemStore pemStore = isKeyStore
            ? new PemStore(storeContents, storeContents, keyPassword, useBcfks)
            : new PemStore(storeContents, useBcfks);
        return pemStore.keyStore;
      } catch (Exception e) {
        log.error("Failed to load store, isKeyStore : {}, path {}", isKeyStore, path, e);
        throw new InvalidConfigurationException("Failed to load PEM SSL keystore " + path, e);
      }
    }
  }

  public static class PemStore implements SecurityStore {
    private static final PemParser CERTIFICATE_PARSER = new PemParser("CERTIFICATE");
    private static final PemParser PRIVATE_KEY_PARSER = new PemParser("PRIVATE KEY");
    private final List<KeyFactory> keyFactories = Arrays.asList(
        keyFactory("RSA"),
        keyFactory("DSA"),
        keyFactory("EC")
    );

    private final char[] keyPassword;
    private final KeyStore keyStore;
    private final boolean useBcfks;

    public PemStore(Password certificateChain, Password privateKey, Password keyPassword,
                    boolean useBcfks) {
      this.keyPassword = keyPassword == null ? null : keyPassword.value().toCharArray();
      this.useBcfks = useBcfks;
      keyStore = createKeyStoreFromPem(privateKey.value(), certificateChain.value(),
          this.keyPassword);
    }

    public PemStore(Password trustStoreCerts, boolean useBcfks) {
      this.keyPassword = null;
      this.useBcfks = useBcfks;
      keyStore = createTrustStoreFromPem(trustStoreCerts.value());
    }

    @Override
    public KeyStore get() {
      return keyStore;
    }

    @Override
    public char[] keyPassword() {
      return keyPassword;
    }

    @Override
    public boolean modified() {
      return false;
    }

    private KeyStore createKeyStoreFromPem(String privateKeyPem, String certChainPem,
                                           char[] keyPassword) {
      try {
        KeyStore ks = SecurityFactory.getKeyStoreInstance(useBcfks);
        ks.load(null, null);
        Key key = privateKey(privateKeyPem, keyPassword);
        Certificate[] certChain = certs(certChainPem);
        ks.setKeyEntry("kafka", key, keyPassword, certChain);
        return ks;
      } catch (Exception e) {
        throw new InvalidConfigurationException("Invalid PEM keystore configs", e);
      }
    }

    private KeyStore createTrustStoreFromPem(String trustedCertsPem) {
      try {
        KeyStore ts = SecurityFactory.getKeyStoreInstance(useBcfks);
        ts.load(null, null);
        Certificate[] certs = certs(trustedCertsPem);
        for (int i = 0; i < certs.length; i++) {
          ts.setCertificateEntry("kafka" + i, certs[i]);
        }
        return ts;
      } catch (InvalidConfigurationException e) {
        throw e;
      } catch (Exception e) {
        throw new InvalidConfigurationException("Invalid PEM truststore configs", e);
      }
    }

    private Certificate[] certs(String pem) throws GeneralSecurityException {
      List<byte[]> certEntries = CERTIFICATE_PARSER.pemEntries(pem);
      if (certEntries.isEmpty()) {
        throw new InvalidConfigurationException(
            "At least one certificate expected, but none found");
      }

      Certificate[] certs = new Certificate[certEntries.size()];
      for (int i = 0; i < certs.length; i++) {
        CertificateFactory certificateFactory =
            SecurityFactory.getCertificateFactoryInstance("X.509", useBcfks);
        certs[i] = certificateFactory.generateCertificate(
            new ByteArrayInputStream(certEntries.get(i)));
      }
      return certs;
    }

    private PrivateKey privateKey(String pem, char[] keyPassword) throws Exception {
      List<byte[]> keyEntries = PRIVATE_KEY_PARSER.pemEntries(pem);
      if (keyEntries.isEmpty()) {
        throw new InvalidConfigurationException("Private key not provided");
      }
      if (keyEntries.size() != 1) {
        throw new InvalidConfigurationException(
            "Expected one private key, but found " + keyEntries.size());
      }

      byte[] keyBytes = keyEntries.get(0);
      PKCS8EncodedKeySpec keySpec;
      if (keyPassword == null) {
        keySpec = new PKCS8EncodedKeySpec(keyBytes);
      } else {
        EncryptedPrivateKeyInfo keyInfo = new EncryptedPrivateKeyInfo(keyBytes);
        String algorithm = keyInfo.getAlgName();
        SecretKeyFactory keyFactory =
            SecurityFactory.getSecretKeyFactoryInstance(algorithm, useBcfks);
        SecretKey pbeKey = keyFactory.generateSecret(new PBEKeySpec(keyPassword));
        Cipher cipher = SecurityFactory.getCipherInstance(algorithm, useBcfks);
        cipher.init(Cipher.DECRYPT_MODE, pbeKey, keyInfo.getAlgParameters());
        keySpec = keyInfo.getKeySpec(cipher);
      }

      InvalidKeySpecException firstException = null;
      for (KeyFactory factory : keyFactories) {
        try {
          return factory.generatePrivate(keySpec);
        } catch (InvalidKeySpecException e) {
          if (firstException == null) {
            firstException = e;
          }
        }
      }
      throw new InvalidConfigurationException("Private key could not be loaded", firstException);
    }

    private KeyFactory keyFactory(String algorithm) {
      try {
        return SecurityFactory.getKeyFactoryInstance(algorithm, useBcfks);
      } catch (Exception e) {
        throw new InvalidConfigurationException(
            "Could not create key factory for algorithm " + algorithm, e);
      }
    }
  }

  /**
   * Parser to process certificate/private key entries from PEM files
   * Examples:
   * -----BEGIN CERTIFICATE-----
   * Base64 cert
   * -----END CERTIFICATE-----
   * -----BEGIN [ENCRYPTED] PRIVATE KEY-----
   * Base64 private key
   * -----END [ENCRYPTED] PRIVATE KEY-----
   * Additional data may be included before headers, so we match all entries within the PEM.
   */
  static class PemParser {
    private final String name;
    private final Pattern pattern;

    PemParser(String name) {
      this.name = name;
      String beginOrEndFormat = "-+%s\\s*.*%s[^-]*-+\\s+";
      String nameIgnoreSpace = name.replace(" ", "\\s+");

      String encodingParams = "\\s*[^\\r\\n]*:[^\\r\\n]*[\\r\\n]+";
      String base64Pattern = "([a-zA-Z0-9/+=\\s]*)";
      String patternStr = String.format(beginOrEndFormat, "BEGIN", nameIgnoreSpace)
          + String.format("(?:%s)*", encodingParams)
          + base64Pattern
          + String.format(beginOrEndFormat, "END", nameIgnoreSpace);
      pattern = Pattern.compile(patternStr);
    }

    private List<byte[]> pemEntries(String pem) {
      Matcher matcher = pattern.matcher(pem + "\n"); // allow last newline to be omitted in value
      List<byte[]> entries = new ArrayList<>();
      while (matcher.find()) {
        String base64Str = matcher.group(1).replaceAll("\\s", "");
        entries.add(Base64.getDecoder().decode(base64Str));
      }
      if (entries.isEmpty()) {
        throw new InvalidConfigurationException("No matching " + name + " entries in PEM file");
      }
      return entries;
    }
  }

  private static class SecurityFactory {
    public static KeyStore getKeyStoreInstance(String type, boolean isFips)
        throws KeyStoreException, NoSuchProviderException {
      return isFips ? KeyStore.getInstance(type, FIPS_PROVIDER) :
          KeyStore.getInstance(type);
    }

    public static KeyStore getKeyStoreInstance(boolean isFips)
        throws KeyStoreException, NoSuchProviderException {
      String type = isFips ? FIPS_KEYSTORE_TYPE : NONFIPS_KEYSTORE_TYPE;
      return getKeyStoreInstance(type, isFips);
    }

    public static CertificateFactory getCertificateFactoryInstance(String type, boolean isFips)
        throws CertificateException, NoSuchProviderException {
      return isFips
          ? CertificateFactory.getInstance(type, FIPS_PROVIDER)
          : CertificateFactory.getInstance(type);
    }

    public static SecretKeyFactory getSecretKeyFactoryInstance(String algorithm, boolean isFips)
        throws NoSuchAlgorithmException, NoSuchProviderException {
      return isFips
          ? SecretKeyFactory.getInstance(algorithm, FIPS_PROVIDER)
          : SecretKeyFactory.getInstance(algorithm);
    }

    public static Cipher getCipherInstance(String algorithm, boolean isFips)
        throws NoSuchPaddingException, NoSuchAlgorithmException, NoSuchProviderException {
      return isFips
          ? Cipher.getInstance(algorithm, FIPS_PROVIDER)
          : Cipher.getInstance(algorithm);
    }

    public static KeyFactory getKeyFactoryInstance(String algorithm, boolean isFips)
        throws NoSuchAlgorithmException, NoSuchProviderException {
      return isFips
          ? KeyFactory.getInstance(algorithm, FIPS_PROVIDER)
          : KeyFactory.getInstance(algorithm);
    }
  }
}
