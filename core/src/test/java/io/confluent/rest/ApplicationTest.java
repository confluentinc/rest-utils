/**
 * Copyright 2016 Confluent Inc.
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
 **/

package io.confluent.rest;

import io.confluent.common.config.ConfigException;
import org.junit.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ApplicationTest {
  @Test
  public void testParseListenersDeprecated() {
    List<String> listenersConfig = new ArrayList<String>();
    List<URI> listeners = Application.parseListeners(listenersConfig, RestConfig.PORT_CONFIG_DEFAULT,
            Arrays.asList("http", "https"), "http");
    assertEquals("Should have only one listener.", 1, listeners.size());
    assertExpectedUri(listeners.get(0), "http", "0.0.0.0", RestConfig.PORT_CONFIG_DEFAULT);
  }

  @Test
  public void testParseListenersHttpAndHttps() {
    List<String> listenersConfig = new ArrayList<String>();
    listenersConfig.add("http://localhost:123");
    listenersConfig.add("https://localhost:124");
    List<URI> listeners = Application.parseListeners(listenersConfig, -1, Arrays.asList("http", "https"), "http");
    assertEquals("Should have two listeners.", 2, listeners.size());
    assertExpectedUri(listeners.get(0), "http", "localhost", 123);
    assertExpectedUri(listeners.get(1), "https", "localhost", 124);
  }

  @Test(expected = ConfigException.class)
  public void testParseListenersUnparseableUri() {
    List<String> listenersConfig = new ArrayList<String>();
    listenersConfig.add("!");
    List<URI> listeners = Application.parseListeners(listenersConfig, -1, Arrays.asList("http", "https"), "http");
  }

  @Test
  public void testParseListenersUnsupportedScheme() {
    List<String> listenersConfig = new ArrayList<String>();
    listenersConfig.add("http://localhost:8080");
    listenersConfig.add("foo://localhost:8081");
    List<URI> listeners = Application.parseListeners(listenersConfig, -1, Arrays.asList("http", "https"), "http");
    assertEquals("Should have one listener.", 1, listeners.size());
    assertExpectedUri(listeners.get(0), "http", "localhost", 8080);
  }

  @Test(expected = ConfigException.class)
  public void testParseListenersNoSupportedListeners() {
    List<String> listenersConfig = new ArrayList<String>();
    listenersConfig.add("foo://localhost:8080");
    listenersConfig.add("bar://localhost:8081");
    List<URI> listeners = Application.parseListeners(listenersConfig, -1, Arrays.asList("http", "https"), "http");
  }

  @Test(expected = ConfigException.class)
  public void testParseListenersNoPort() {
    List<String> listenersConfig = new ArrayList<String>();
    listenersConfig.add("http://localhost");
    List<URI> listeners = Application.parseListeners(listenersConfig, -1, Arrays.asList("http", "https"), "http");
  }

  private void assertExpectedUri(URI uri, String scheme, String host, int port) {
    assertEquals("Scheme should be " + scheme, scheme, uri.getScheme());
    assertEquals("Host should be " + host, host, uri.getHost());
    assertEquals("Port should be " + port, port, uri.getPort());
  }
}
