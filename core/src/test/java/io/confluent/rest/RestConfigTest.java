package io.confluent.rest;

import org.eclipse.jetty.server.Server;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.core.Configurable;

import org.apache.kafka.common.config.ConfigException;

public class RestConfigTest {

  // getListenerProtocolMap tests

  @Test
  public void testValidProtocolMapCases() {
    // empty LISTENER_PROTOCOL_MAP_CONFIG
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "");
    RestConfig config = new RestConfig(RestConfig.baseConfigDef(), props);
    Map<String,String> protocolMap = config.getListenerProtocolMap();
    Assert.assertEquals(0, protocolMap.size());

    // LISTENER_PROTOCOL_MAP_CONFIG not set
    props = new HashMap<>();
    config = new RestConfig(RestConfig.baseConfigDef(), props);
    protocolMap = config.getListenerProtocolMap();
    Assert.assertNotNull(protocolMap);
    Assert.assertEquals(0, protocolMap.size());

    // single mapping
    props = new HashMap<>();
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "INTERNAL:http");
    config = new RestConfig(RestConfig.baseConfigDef(), props);
    protocolMap = config.getListenerProtocolMap();
    Assert.assertEquals(1, protocolMap.size());
    Assert.assertTrue(protocolMap.containsKey("internal"));
    Assert.assertEquals("http", protocolMap.get("internal"));

    // multiple mappings
    props = new HashMap<>();
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "INTERNAL:http,EXTERNAL:https");
    config = new RestConfig(RestConfig.baseConfigDef(), props);
    protocolMap = config.getListenerProtocolMap();
    Assert.assertEquals(2, protocolMap.size());
    Assert.assertTrue(protocolMap.containsKey("internal"));
    Assert.assertEquals("http", protocolMap.get("internal"));
    Assert.assertTrue(protocolMap.containsKey("external"));
    Assert.assertEquals("https", protocolMap.get("external"));

    // listener name is a protocol
    props = new HashMap<>();
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "http:http,EXTERNAL:https");
    config = new RestConfig(RestConfig.baseConfigDef(), props);
    protocolMap = config.getListenerProtocolMap();
    Assert.assertEquals(2, protocolMap.size());
    Assert.assertTrue(protocolMap.containsKey("http"));
    Assert.assertEquals("http", protocolMap.get("http"));
    Assert.assertTrue(protocolMap.containsKey("external"));
    Assert.assertEquals("https", protocolMap.get("external"));
  }

  @Test(expected = ConfigException.class)
  public void testHttpSetToHttps() {
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "HTTP:https");
    RestConfig config = new RestConfig(RestConfig.baseConfigDef(), props);
    config.getListenerProtocolMap();
  }  

  @Test(expected = ConfigException.class)
  public void testInvalidProtocolMapping() {
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "internal");
    RestConfig config = new RestConfig(RestConfig.baseConfigDef(), props);
    config.getListenerProtocolMap();
  }

  @Test(expected = ConfigException.class)
  public void testInvalidProtocolMappingList() {
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "INTERNAL:http;EXTERNAL:https");
    RestConfig config = new RestConfig(RestConfig.baseConfigDef(), props);
    config.getListenerProtocolMap();
  }

  @Test(expected = ConfigException.class)
  public void testEmptyProtocolMappingListenerName() {
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "INTERNAL:http,:https");
    RestConfig config = new RestConfig(RestConfig.baseConfigDef(), props);
    config.getListenerProtocolMap();
  }

  @Test(expected = ConfigException.class)
  public void testDuplicateProtocolMappingListenerName() {
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "INTERNAL:http,INTERNAL:https");
    RestConfig config = new RestConfig(RestConfig.baseConfigDef(), props);
    config.getListenerProtocolMap();
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
    Assert.assertEquals(1, conf.size());
    // application should bind to all listeners.
    Assert.assertTrue(conf.containsKey(""));
    Assert.assertEquals(2, conf.get("").size());
    Assert.assertTrue(conf.get("").containsKey("foo"));
    Assert.assertTrue(conf.get("").containsKey("a.bar"));
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

    Assert.assertEquals(3, conf.size());
    Assert.assertFalse(conf.containsKey(""));

    Assert.assertEquals(2, conf.get("a").size());
    Assert.assertTrue(conf.get("a").containsKey("foo"));
    Assert.assertTrue(conf.get("a").containsKey("bar"));
    Assert.assertFalse(conf.get("a").containsKey("baz"));
    Assert.assertEquals("1", conf.get("a").get("foo"));
    Assert.assertEquals("2", conf.get("a").get("bar"));

    Assert.assertEquals(1, conf.get("b").size());
    Assert.assertFalse(conf.get("b").containsKey("foo"));
    Assert.assertTrue(conf.get("b").containsKey("bar"));
    Assert.assertFalse(conf.get("b").containsKey("baz"));
    Assert.assertEquals("3", conf.get("b").get("bar"));

    Assert.assertEquals(1, conf.get("c").size());
    Assert.assertFalse(conf.get("c").containsKey("foo"));
    Assert.assertFalse(conf.get("c").containsKey("bar"));
    Assert.assertTrue(conf.get("c").containsKey("baz"));
    Assert.assertEquals("4", conf.get("c").get("baz"));
  }

  @Test
  public void testSingleNamedListenerInstanceConfig() {
    Map<String, Object> original = new HashMap<>();
    original.put(CONFIG_PREFIX + "a.foo", "1");
    original.put(CONFIG_PREFIX + "a.bar", "2");

    Map<String, Map<String, Object>> conf =
        RestConfig.getInstanceConfig(CONFIG_PREFIX, THREE_LISTENER_NAMES_SET, original);

    Assert.assertEquals(1, conf.size());
    Assert.assertFalse(conf.containsKey(""));
    Assert.assertFalse(conf.containsKey("b"));
    Assert.assertFalse(conf.containsKey("c"));

    Assert.assertEquals(2, conf.get("a").size());
    Assert.assertTrue(conf.get("a").containsKey("foo"));
    Assert.assertTrue(conf.get("a").containsKey("bar"));
    Assert.assertFalse(conf.get("a").containsKey("baz"));
    Assert.assertEquals("1", conf.get("a").get("foo"));
    Assert.assertEquals("2", conf.get("a").get("bar"));
  }

  @Test
  public void testNamedInstanceConfigWhenNoPropertiesPrefixed() {
    Map<String, Object> original = new HashMap<>();

    Map<String, Map<String, Object>> conf =
        RestConfig.getInstanceConfig(CONFIG_PREFIX, THREE_LISTENER_NAMES_SET, original);

    Assert.assertEquals(1, conf.size());
    Assert.assertTrue(conf.containsKey(""));
    Assert.assertEquals(0, conf.get("").size());
  }
}
