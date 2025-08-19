package io.confluent.rest;

import static io.confluent.rest.RestConfig.getBooleanOrDefault;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableMap;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.kafka.common.config.ConfigException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class RestConfigTest {
  private static final String PROPERTY_KEY = "property.key";

  // getListenerProtocolMap tests

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testValidProtocolMapCases(boolean doLog) {
    // empty LISTENER_PROTOCOL_MAP_CONFIG
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "");
    RestConfig config = new RestConfig(RestConfig.baseConfigDef(), props, doLog);
    Map<String, String> protocolMap = config.getListenerProtocolMap();
    assertEquals(0, protocolMap.size());
    assertEquals(doLog, config.getDoLog());

    // LISTENER_PROTOCOL_MAP_CONFIG not set
    props = new HashMap<>();
    config = new RestConfig(RestConfig.baseConfigDef(), props, doLog);
    protocolMap = config.getListenerProtocolMap();
    assertNotNull(protocolMap);
    assertEquals(0, protocolMap.size());
    assertEquals(doLog, config.getDoLog());

    // single mapping
    props = new HashMap<>();
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "INTERNAL:http");
    config = new RestConfig(RestConfig.baseConfigDef(), props, doLog);
    protocolMap = config.getListenerProtocolMap();
    assertEquals(1, protocolMap.size());
    assertTrue(protocolMap.containsKey("internal"));
    assertEquals("http", protocolMap.get("internal"));
    assertEquals(doLog, config.getDoLog());

    // multiple mappings
    props = new HashMap<>();
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "INTERNAL:http,EXTERNAL:https");
    config = new RestConfig(RestConfig.baseConfigDef(), props, doLog);
    protocolMap = config.getListenerProtocolMap();
    assertEquals(2, protocolMap.size());
    assertTrue(protocolMap.containsKey("internal"));
    assertEquals("http", protocolMap.get("internal"));
    assertTrue(protocolMap.containsKey("external"));
    assertEquals("https", protocolMap.get("external"));
    assertEquals(doLog, config.getDoLog());

    // listener name is a protocol
    props = new HashMap<>();
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "http:http,EXTERNAL:https");
    config = new RestConfig(RestConfig.baseConfigDef(), props, doLog);
    protocolMap = config.getListenerProtocolMap();
    assertEquals(2, protocolMap.size());
    assertTrue(protocolMap.containsKey("http"));
    assertEquals("http", protocolMap.get("http"));
    assertTrue(protocolMap.containsKey("external"));
    assertEquals("https", protocolMap.get("external"));
    assertEquals(doLog, config.getDoLog());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testHttpSetToHttps(boolean doLog) {
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "HTTP:https");
    RestConfig config = new RestConfig(RestConfig.baseConfigDef(), props, doLog);
    assertThrows(ConfigException.class,
        () -> config.getListenerProtocolMap());
    assertEquals(doLog, config.getDoLog());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testInvalidProtocolMapping(boolean doLog) {
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "internal");
    RestConfig config = new RestConfig(RestConfig.baseConfigDef(), props, doLog);
    assertThrows(ConfigException.class,
        () -> config.getListenerProtocolMap());
    assertEquals(doLog, config.getDoLog());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testInvalidProtocolMappingList(boolean doLog) {
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "INTERNAL:http;EXTERNAL:https");
    RestConfig config = new RestConfig(RestConfig.baseConfigDef(), props, doLog);
    assertThrows(ConfigException.class,
        () -> config.getListenerProtocolMap());
    assertEquals(doLog, config.getDoLog());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testEmptyProtocolMappingListenerName(boolean doLog) {
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "INTERNAL:http,:https");
    RestConfig config = new RestConfig(RestConfig.baseConfigDef(), props, doLog);
    assertThrows(ConfigException.class,
        () -> config.getListenerProtocolMap());
    assertEquals(doLog, config.getDoLog());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testDuplicateProtocolMappingListenerName(boolean doLog) {
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "INTERNAL:http,INTERNAL:https");
    RestConfig config = new RestConfig(RestConfig.baseConfigDef(), props, doLog);
    assertThrows(ConfigException.class,
        () -> config.getListenerProtocolMap());
    assertEquals(doLog, config.getDoLog());
  }

  // getInstanceConfig tests

  public static final String CONFIG_PREFIX = "my.config.prefix.";
  public static final Set<String> EMPTY_LISTENER_NAME_SET = new HashSet<>();
  public static final Set<String> THREE_LISTENER_NAMES_SET =
      new HashSet<>(Arrays.asList("a", "b", "c"));

  @Test
  public void testEmptyProtocolMapNamedInstanceConfig() {
    Map<String, Object> original = new HashMap<>();
    original.put(CONFIG_PREFIX + "foo", "1");
    // "a." should not be recognised as a listener name prefix, since there are no named listeners
    original.put(CONFIG_PREFIX + "a.bar", "1");
    Map<String, Map<String, Object>> conf =
        RestConfig.getInstanceConfig(CONFIG_PREFIX, EMPTY_LISTENER_NAME_SET, original);
    assertEquals(1, conf.size());
    // application should bind to all listeners.
    assertTrue(conf.containsKey(""));
    assertEquals(2, conf.get("").size());
    assertTrue(conf.get("").containsKey("foo"));
    assertTrue(conf.get("").containsKey("a.bar"));
  }

  @Test
  public void testMultiNamedListenerInstanceConfig() {
    Map<String, Object> original = new HashMap<>();
    original.put(CONFIG_PREFIX + "a.foo", "1");
    original.put(CONFIG_PREFIX + "A.bar", "2"); // test case insensitivity
    original.put(CONFIG_PREFIX + "b.bar", "3");
    original.put(CONFIG_PREFIX + "c.baz", "4");

    Map<String, Map<String, Object>> conf =
        RestConfig.getInstanceConfig(CONFIG_PREFIX, THREE_LISTENER_NAMES_SET, original);

    assertEquals(3, conf.size());
    assertFalse(conf.containsKey(""));

    assertEquals(2, conf.get("a").size());
    assertTrue(conf.get("a").containsKey("foo"));
    assertTrue(conf.get("a").containsKey("bar"));
    assertFalse(conf.get("a").containsKey("baz"));
    assertEquals("1", conf.get("a").get("foo"));
    assertEquals("2", conf.get("a").get("bar"));

    assertEquals(1, conf.get("b").size());
    assertFalse(conf.get("b").containsKey("foo"));
    assertTrue(conf.get("b").containsKey("bar"));
    assertFalse(conf.get("b").containsKey("baz"));
    assertEquals("3", conf.get("b").get("bar"));

    assertEquals(1, conf.get("c").size());
    assertFalse(conf.get("c").containsKey("foo"));
    assertFalse(conf.get("c").containsKey("bar"));
    assertTrue(conf.get("c").containsKey("baz"));
    assertEquals("4", conf.get("c").get("baz"));
  }

  @Test
  public void testSingleNamedListenerInstanceConfig() {
    Map<String, Object> original = new HashMap<>();
    original.put(CONFIG_PREFIX + "a.foo", "1");
    original.put(CONFIG_PREFIX + "a.bar", "2");

    Map<String, Map<String, Object>> conf =
        RestConfig.getInstanceConfig(CONFIG_PREFIX, THREE_LISTENER_NAMES_SET, original);

    assertEquals(1, conf.size());
    assertFalse(conf.containsKey(""));
    assertFalse(conf.containsKey("b"));
    assertFalse(conf.containsKey("c"));

    assertEquals(2, conf.get("a").size());
    assertTrue(conf.get("a").containsKey("foo"));
    assertTrue(conf.get("a").containsKey("bar"));
    assertFalse(conf.get("a").containsKey("baz"));
    assertEquals("1", conf.get("a").get("foo"));
    assertEquals("2", conf.get("a").get("bar"));
  }

  @Test
  public void testNamedInstanceConfigWhenNoPropertiesPrefixed() {
    Map<String, Object> original = new HashMap<>();

    Map<String, Map<String, Object>> conf =
        RestConfig.getInstanceConfig(CONFIG_PREFIX, THREE_LISTENER_NAMES_SET, original);

    assertEquals(1, conf.size());
    assertTrue(conf.containsKey(""));
    assertEquals(0, conf.get("").size());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testPerListenerSslConfig(boolean doLog) throws URISyntaxException {
    RestConfig restConfig =
        new RestConfig(
            RestConfig.baseConfigDef(),
            ImmutableMap.<String, String>builder()
                .put("listeners", "A://1.1.1.1:1,B://2.2.2.2:2")
                .put("listener.protocol.map", "A:https,B:https")
                .put("ssl.keystore.location", "default.jks")
                .put("listener.name.A.ssl.keystore.location", "a.jks")
                .build(),
            doLog);

    Map<NamedURI, SslConfig> sslConfigs = restConfig.getSslConfigs();

    SslConfig baseSslConfig = restConfig.getBaseSslConfig();
    SslConfig aSslConfig = sslConfigs.get(new NamedURI(new URI("https://1.1.1.1:1"), "A"));
    SslConfig bSslConfig = sslConfigs.get(new NamedURI(new URI("https://2.2.2.2:2"), "B"));

    assertEquals("default.jks", baseSslConfig.getKeyStorePath()); // default config
    assertEquals("a.jks", aSslConfig.getKeyStorePath()); // listener override
    assertEquals("default.jks", bSslConfig.getKeyStorePath()); // no listener override
    assertEquals(doLog, restConfig.getDoLog());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testPerListenerSslConfigHttpsName(boolean doLog) throws URISyntaxException {
    RestConfig restConfig =
        new RestConfig(
            RestConfig.baseConfigDef(),
            ImmutableMap.<String, String>builder()
                .put("listeners", "https://1.1.1.1:1,https://2.2.2.2:2,A://3.3.3.3:3")
                .put("listener.protocol.map", "A:https")
                .put("ssl.keystore.location", "default.jks")
                .put("listener.name.https.ssl.keystore.location", "https.jks")
                .put("listener.name.A.ssl.keystore.location", "a.jks")
                .build(),
            doLog);

    Map<NamedURI, SslConfig> sslConfigs = restConfig.getSslConfigs();

    SslConfig baseSslConfig = restConfig.getBaseSslConfig();
    SslConfig https1SslConfig = sslConfigs.get(new NamedURI(new URI("https://1.1.1.1:1"), null));
    SslConfig https2SslConfig = sslConfigs.get(new NamedURI(new URI("https://2.2.2.2:2"), null));
    SslConfig aSslConfig = sslConfigs.get(new NamedURI(new URI("https://3.3.3.3:3"), "A"));

    assertEquals("default.jks", baseSslConfig.getKeyStorePath()); // default config
    // https is a strange case because you could have multiple listeners "named" https.
    // If you override SSL configs for the https "name", we will apply the override to all the
    // https "named" listeners.
    assertEquals("https.jks", https1SslConfig.getKeyStorePath()); // listener override
    assertEquals("https.jks", https2SslConfig.getKeyStorePath()); // listener override
    assertEquals("a.jks", aSslConfig.getKeyStorePath()); // listener override
    assertEquals(doLog, restConfig.getDoLog());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testPerListenerSslConfigRepeatedConfig(boolean doLog) throws URISyntaxException {
    RestConfig restConfig =
        new RestConfig(
            RestConfig.baseConfigDef(),
            ImmutableMap.<String, String>builder()
                .put("listeners", "A://1.1.1.1:1")
                .put("listener.protocol.map", "A:https")
                .put("ssl.keystore.location", "default.jks")
                .put("listener.name.a.ssl.keystore.location", "a1.jks")
                .put("listener.name.A.ssl.keystore.location", "a2.jks")
                .build(),
            doLog);

    Map<NamedURI, SslConfig> sslConfigs = restConfig.getSslConfigs();

    SslConfig baseSslConfig = restConfig.getBaseSslConfig();
    SslConfig aSslConfig = sslConfigs.get(new NamedURI(new URI("https://1.1.1.1:1"), "A"));

    assertEquals("default.jks", baseSslConfig.getKeyStorePath()); // default config
    assertTrue(aSslConfig.getKeyStorePath().matches("a[12]\\.jks")); // any listener override
    assertEquals(doLog, restConfig.getDoLog());
  }

  @Test
  public void test_getBooleanOrDefault_FromMap_Default() {
    assertFalse(getBooleanOrDefault(ImmutableMap.of(), PROPERTY_KEY, false));
    assertTrue(getBooleanOrDefault(ImmutableMap.of(), PROPERTY_KEY, true));
  }

  @Test
  public void test_getBooleanOrDefault_FromMap_InvalidValue_DefaultToFalse() {
    Map<String, Object> config = new HashMap<>();
    config.put(PROPERTY_KEY, "invalid");
    assertFalse(getBooleanOrDefault(config, PROPERTY_KEY, false));
    assertFalse(getBooleanOrDefault(config, PROPERTY_KEY, true));
  }

  @Test
  public void test_getBooleanOrDefault_FromMap_EnabledByBoolean() {
    Map<String, Object> config = new HashMap<>();
    config.put(PROPERTY_KEY, true);
    assertTrue(getBooleanOrDefault(config, PROPERTY_KEY, false));
  }

  @Test
  public void test_getBooleanOrDefault_FromMap_EnabledByString() {
    Map<String, Object> config = new HashMap<>();
    config.put(PROPERTY_KEY, "true");
    assertTrue(getBooleanOrDefault(config, PROPERTY_KEY, false));

    config = new HashMap<>();
    config.put(PROPERTY_KEY, "TRUE");
    assertTrue(getBooleanOrDefault(config, PROPERTY_KEY, false));

    config = new HashMap<>();
    config.put(PROPERTY_KEY, "True");
    assertTrue(getBooleanOrDefault(config, PROPERTY_KEY, false));

    config = new HashMap<>();
    config.put(PROPERTY_KEY, "TrUe");
    assertTrue(getBooleanOrDefault(config, PROPERTY_KEY, false));
  }

  @Test
  public void test_getBooleanOrDefault_FromMap_DisabledByBoolean() {
    Map<String, Object> config = new HashMap<>();
    config.put(PROPERTY_KEY, false);
    assertFalse(getBooleanOrDefault(config, PROPERTY_KEY, false));
  }

  @Test
  public void test_getBooleanOrDefault_FromMap_DisabledByString() {
    Map<String, Object> config = new HashMap<>();
    config.put(PROPERTY_KEY, "false");
    assertFalse(getBooleanOrDefault(config, PROPERTY_KEY, true));

    config = new HashMap<>();
    config.put(PROPERTY_KEY, "FALSE");
    assertFalse(getBooleanOrDefault(config, PROPERTY_KEY, true));

    config = new HashMap<>();
    config.put(PROPERTY_KEY, "False");
    assertFalse(getBooleanOrDefault(config, PROPERTY_KEY, true));

    config = new HashMap<>();
    config.put(PROPERTY_KEY, "FaLse");
    assertFalse(getBooleanOrDefault(config, PROPERTY_KEY, true));
  }

  @Test
  public void test_getBooleanOrDefault_FromProps_Default() {
    assertFalse(getBooleanOrDefault(new Properties(), PROPERTY_KEY, false));
    assertTrue(getBooleanOrDefault(new Properties(), PROPERTY_KEY, true));
  }

  @Test
  public void test_getBooleanOrDefault_FromProps_InvalidValue_DefaultToFalse() {
    Properties props = new Properties();
    props.put(PROPERTY_KEY, "invalid");
    assertFalse(getBooleanOrDefault(props, PROPERTY_KEY, false));
    assertFalse(getBooleanOrDefault(props, PROPERTY_KEY, true));
  }

  @Test
  public void test_getBooleanOrDefault_FromProps_EnabledByBoolean() {
    Properties props = new Properties();
    props.put(PROPERTY_KEY, true);
    assertTrue(getBooleanOrDefault(props, PROPERTY_KEY, false));
  }

  @Test
  public void test_getBooleanOrDefault_FromProps_EnabledByString() {
    Properties props = new Properties();
    props.put(PROPERTY_KEY, "true");
    assertTrue(getBooleanOrDefault(props, PROPERTY_KEY, false));

    props = new Properties();
    props.put(PROPERTY_KEY, "TRUE");
    assertTrue(getBooleanOrDefault(props, PROPERTY_KEY, false));

    props = new Properties();
    props.put(PROPERTY_KEY, "True");
    assertTrue(getBooleanOrDefault(props, PROPERTY_KEY, false));

    props = new Properties();
    props.put(PROPERTY_KEY, "TrUe");
    assertTrue(getBooleanOrDefault(props, PROPERTY_KEY, false));
  }

  @Test
  public void test_getBooleanOrDefault_FromProps_DisabledByBoolean() {
    Properties props = new Properties();
    props.put(PROPERTY_KEY, false);
    assertFalse(getBooleanOrDefault(props, PROPERTY_KEY, false));
  }

  @Test
  public void test_getBooleanOrDefault_FromProps_DisabledByString() {
    Properties props = new Properties();
    props.put(PROPERTY_KEY, "false");
    assertFalse(getBooleanOrDefault(props, PROPERTY_KEY, true));

    props = new Properties();
    props.put(PROPERTY_KEY, "FALSE");
    assertFalse(getBooleanOrDefault(props, PROPERTY_KEY, true));

    props = new Properties();
    props.put(PROPERTY_KEY, "False");
    assertFalse(getBooleanOrDefault(props, PROPERTY_KEY, true));

    props = new Properties();
    props.put(PROPERTY_KEY, "FaLse");
    assertFalse(getBooleanOrDefault(props, PROPERTY_KEY, true));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void getListenerScopedConfigNamedListenerReturnsConfigWithOverriddenProperties(
      boolean doLog) throws Exception {
    RestConfig restConfig =
        new RestConfig(
            RestConfig.baseConfigDef(),
            ImmutableMap.<String, String>builder()
                .put("listeners", "A://1.1.1.1:1")
                .put("listener.protocol.map", "A:https")
                .put(RestConfig.SNI_HOST_CHECK_ENABLED_CONFIG, "true")
                .put(RestConfig.SNI_CHECK_ENABLED_CONFIG, "true")
                .put("listener.name.A." + RestConfig.SNI_HOST_CHECK_ENABLED_CONFIG, "false")
                .build(),
            doLog);
    RestConfig listenerConfig =
        restConfig.getListenerScopedConfig(new NamedURI(new URI("https://1.1.1.1:1"), "A"));
    // check that the listener config has overridden the base config
    assertFalse(listenerConfig.getSniHostCheckEnable());
    // check that the listener config inherits the base config
    assertTrue(listenerConfig.getSniCheckEnable());
    assertEquals(doLog, listenerConfig.getDoLog());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void getListenerScopedConfigUnnamedHttpsListenerReturnsConfigWithOverriddenProperties(
      boolean doLog) throws Exception {
    RestConfig restConfig =
        new RestConfig(
            RestConfig.baseConfigDef(),
            ImmutableMap.<String, String>builder()
                .put("listeners", "https://1.1.1.1:1,A://3.3.3.3:3")
                .put("listener.protocol.map", "A:https")
                .put(RestConfig.SNI_HOST_CHECK_ENABLED_CONFIG, "false")
                .put(RestConfig.SNI_CHECK_ENABLED_CONFIG, "true")
                .put("listener.name.A." + RestConfig.SNI_HOST_CHECK_ENABLED_CONFIG, "true")
                .put("listener.name.https." + RestConfig.SNI_HOST_CHECK_ENABLED_CONFIG, "true")
                .build(),
            doLog);
    RestConfig unnamedListenerConfig =
        restConfig.getListenerScopedConfig(new NamedURI(new URI("https://1.1.1.1:1"), null));
    // unnamed https listener uses https listener config
    assertTrue(unnamedListenerConfig.getSniHostCheckEnable());
    assertTrue(unnamedListenerConfig.getSniCheckEnable());

    RestConfig namedListenerConfig =
        restConfig.getListenerScopedConfig(new NamedURI(new URI("https://3.3.3.3:3"), "A"));
    assertTrue(namedListenerConfig.getSniHostCheckEnable());
    assertTrue(namedListenerConfig.getSniCheckEnable());
    assertEquals(doLog, namedListenerConfig.getDoLog());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void getListenerScopedConfigUnnamedHttpListenerReturnsSameConfig(
      boolean doLog) throws Exception {
    RestConfig restConfig =
        new RestConfig(
            RestConfig.baseConfigDef(),
            ImmutableMap.<String, String>builder()
                .put("listeners", "http://1.1.1.1:1,A://3.3.3.3:3")
                .put("listener.protocol.map", "A:https")
                .put(RestConfig.SNI_HOST_CHECK_ENABLED_CONFIG, "false")
                .put(RestConfig.SNI_CHECK_ENABLED_CONFIG, "true")
                .put("listener.name.A." + RestConfig.SNI_HOST_CHECK_ENABLED_CONFIG, "true")
                .put("listener.name.https." + RestConfig.SNI_HOST_CHECK_ENABLED_CONFIG, "true")
                .build(),
            doLog);
    RestConfig unnamedListenerConfig =
        restConfig.getListenerScopedConfig(new NamedURI(new URI("http://1.1.1.1:1"), null));
    // unnamed http return the same config
    assertSame(unnamedListenerConfig, restConfig);

    RestConfig namedListenerConfig =
        restConfig.getListenerScopedConfig(new NamedURI(new URI("https://3.3.3.3:3"), "A"));
    assertTrue(namedListenerConfig.getSniHostCheckEnable());
    assertTrue(namedListenerConfig.getSniCheckEnable());
    assertEquals(doLog, namedListenerConfig.getDoLog());
  }
}
