package io.confluent.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.kafka.common.config.ConfigException;

public class RestConfigTest {

  // getListenerProtocolMap tests

  @Test
  public void testValidProtocolMapCases() {
    // empty LISTENER_PROTOCOL_MAP_CONFIG
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "");
    RestConfig config = new RestConfig(RestConfig.baseConfigDef(), props);
    Map<String, String> protocolMap = config.getListenerProtocolMap();
    assertEquals(0, protocolMap.size());

    // LISTENER_PROTOCOL_MAP_CONFIG not set
    props = new HashMap<>();
    config = new RestConfig(RestConfig.baseConfigDef(), props);
    protocolMap = config.getListenerProtocolMap();
    assertNotNull(protocolMap);
    assertEquals(0, protocolMap.size());

    // single mapping
    props = new HashMap<>();
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "INTERNAL:http");
    config = new RestConfig(RestConfig.baseConfigDef(), props);
    protocolMap = config.getListenerProtocolMap();
    assertEquals(1, protocolMap.size());
    assertTrue(protocolMap.containsKey("internal"));
    assertEquals("http", protocolMap.get("internal"));

    // multiple mappings
    props = new HashMap<>();
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "INTERNAL:http,EXTERNAL:https");
    config = new RestConfig(RestConfig.baseConfigDef(), props);
    protocolMap = config.getListenerProtocolMap();
    assertEquals(2, protocolMap.size());
    assertTrue(protocolMap.containsKey("internal"));
    assertEquals("http", protocolMap.get("internal"));
    assertTrue(protocolMap.containsKey("external"));
    assertEquals("https", protocolMap.get("external"));

    // listener name is a protocol
    props = new HashMap<>();
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "http:http,EXTERNAL:https");
    config = new RestConfig(RestConfig.baseConfigDef(), props);
    protocolMap = config.getListenerProtocolMap();
    assertEquals(2, protocolMap.size());
    assertTrue(protocolMap.containsKey("http"));
    assertEquals("http", protocolMap.get("http"));
    assertTrue(protocolMap.containsKey("external"));
    assertEquals("https", protocolMap.get("external"));
  }

  @Test
  public void testHttpSetToHttps() {
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "HTTP:https");
    RestConfig config = new RestConfig(RestConfig.baseConfigDef(), props);
    assertThrows(ConfigException.class,
        () -> config.getListenerProtocolMap());
  }

  @Test
  public void testInvalidProtocolMapping() {
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "internal");
    RestConfig config = new RestConfig(RestConfig.baseConfigDef(), props);
    assertThrows(ConfigException.class,
        () -> config.getListenerProtocolMap());
  }

  @Test
  public void testInvalidProtocolMappingList() {
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "INTERNAL:http;EXTERNAL:https");
    RestConfig config = new RestConfig(RestConfig.baseConfigDef(), props);
    assertThrows(ConfigException.class,
        () -> config.getListenerProtocolMap());
  }

  @Test
  public void testEmptyProtocolMappingListenerName() {
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "INTERNAL:http,:https");
    RestConfig config = new RestConfig(RestConfig.baseConfigDef(), props);
    assertThrows(ConfigException.class,
        () -> config.getListenerProtocolMap());
  }

  @Test
  public void testDuplicateProtocolMappingListenerName() {
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "INTERNAL:http,INTERNAL:https");
    RestConfig config = new RestConfig(RestConfig.baseConfigDef(), props);
    assertThrows(ConfigException.class,
        () -> config.getListenerProtocolMap());
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
}
