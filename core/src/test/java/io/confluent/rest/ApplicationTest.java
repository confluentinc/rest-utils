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

import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.security.Constraint;
import org.junit.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ApplicationTest {

  public static final String REALM = "realm";

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

  @Test
  public void testAuthEnabledNONE() {
    assertFalse(Application.enableBasicAuth(RestConfig.AUTHENTICATION_METHOD_NONE));
  }

  @Test
  public void testAuthEnabledBASIC() {
    assertTrue(Application.enableBasicAuth(RestConfig.AUTHENTICATION_METHOD_BASIC));
  }

  @Test
  public void testCreateSecurityHandlerWithNoRoles() {
    ConstraintSecurityHandler securityHandler = Application.createSecurityHandler(REALM, Collections.emptyList());
    assertEquals(securityHandler.getRealmName(), REALM);
    assertTrue(securityHandler.getRoles().isEmpty());
    assertNotNull(securityHandler.getLoginService());
    assertNotNull(securityHandler.getAuthenticator());
    assertEquals(1, securityHandler.getConstraintMappings().size());
    assertFalse(securityHandler.getConstraintMappings().get(0).getConstraint().isAnyRole());
  }

  @Test
  public void testCreateSecurityHandlerWithAllRoles() {
    ConstraintSecurityHandler securityHandler = Application.createSecurityHandler(REALM, Arrays.asList("*"));
    assertEquals(securityHandler.getRealmName(), REALM);
    assertTrue(securityHandler.getRoles().isEmpty());
    assertNotNull(securityHandler.getLoginService());
    assertNotNull(securityHandler.getAuthenticator());
    assertEquals(1, securityHandler.getConstraintMappings().size());
    assertTrue(securityHandler.getConstraintMappings().get(0).getConstraint().isAnyRole());
  }

  @Test
  public void testCreateSecurityHandlerWithSpecificRoles() {
    final List<String> roles = Arrays.asList("roleA", "roleB");
    ConstraintSecurityHandler securityHandler = Application.createSecurityHandler(REALM, roles);
    assertEquals(securityHandler.getRealmName(), REALM);
    assertFalse(securityHandler.getRoles().isEmpty());
    assertNotNull(securityHandler.getLoginService());
    assertNotNull(securityHandler.getAuthenticator());
    assertEquals(1, securityHandler.getConstraintMappings().size());
    final Constraint constraint = securityHandler.getConstraintMappings().get(0).getConstraint();
    assertFalse(constraint.isAnyRole());
    assertEquals(constraint.getRoles().length, roles.size());
    assertArrayEquals(constraint.getRoles(), roles.toArray(new String[roles.size()]));
  }

  @Test
  public void testSetUnsecurePathConstraintsWithMultipleUnSecure(){
    ServletContextHandler servletContextHandler  = new ServletContextHandler();
    final List<String> roles = Arrays.asList("roleA", "roleB");
    ConstraintSecurityHandler securityHandler = Application.createSecurityHandler(REALM, roles);
    servletContextHandler.setSecurityHandler(securityHandler);
    setAndAssertUnsecureConstraints(servletContextHandler, securityHandler, 3);
  }

  @Test
  public void testSetUnsecurePathConstraintsWithSingleUnSecure(){
    ServletContextHandler servletContextHandler  = new ServletContextHandler();
    final List<String> roles = Arrays.asList("roleA", "roleB");
    ConstraintSecurityHandler securityHandler = Application.createSecurityHandler(REALM, roles);
    servletContextHandler.setSecurityHandler(securityHandler);
    setAndAssertUnsecureConstraints(servletContextHandler, securityHandler, 1);
  }

  @Test
  public void testSetUnsecurePathConstraintsWithNoUnSecure(){
    ServletContextHandler servletContextHandler  = new ServletContextHandler();
    final List<String> roles = Arrays.asList("roleA", "roleB");
    ConstraintSecurityHandler securityHandler = Application.createSecurityHandler(REALM, roles);
    servletContextHandler.setSecurityHandler(securityHandler);
    setAndAssertUnsecureConstraints(servletContextHandler, securityHandler, 0);
  }

  @Test
  public void testSetUnsecurePathConstraintsWithoutSecurityConstraints(){
    ServletContextHandler servletContextHandler  = new ServletContextHandler();
    Application.setUnsecurePathConstraints(servletContextHandler, Arrays.asList("/path1"));
    assertNull(servletContextHandler.getSecurityHandler());

  }

  private void setAndAssertUnsecureConstraints(
      ServletContextHandler servletContextHandler,
      ConstraintSecurityHandler securityHandler,
      int numPaths
  ) {
    final List<String> unsecurePaths = new ArrayList<>();

    for (int i=1;i<=numPaths;i++){
      unsecurePaths.add("/test"+i);
    }
    Application.setUnsecurePathConstraints(servletContextHandler, unsecurePaths);

    assertEquals(numPaths+1, securityHandler.getConstraintMappings().size());

    List<ConstraintMapping> unsecureMappings = securityHandler.getConstraintMappings().subList(1, numPaths+1);

    for (int i=0;i<unsecureMappings.size();i++){
      assertEquals(unsecurePaths.get(i), unsecureMappings.get(i).getPathSpec());
      assertNotNull(unsecureMappings.get(i).getConstraint());
      assertFalse(unsecureMappings.get(i).getConstraint().getAuthenticate());
    }
  }

  private void assertExpectedUri(URI uri, String scheme, String host, int port) {
    assertEquals("Scheme should be " + scheme, scheme, uri.getScheme());
    assertEquals("Host should be " + host, host, uri.getHost());
    assertEquals("Port should be " + port, port, uri.getPort());
  }
}
