package io.confluent.rest;

import org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.security.Security;
import java.util.Map;

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

  @Override
  protected void setConfigs(Map<String, String> configs) {
    configs.put(RestConfig.SSL_PROVIDER_CONFIG, SslFactoryPemHelper.FIPS_SSL_PROVIDER);
    config = new RestConfig(RestConfig.baseConfigDef(), configs);
  }

  @Override
  protected String getKeyStoreType() {
    return SslFactoryPemHelper.FIPS_KEYSTORE_TYPE;
  }
}
