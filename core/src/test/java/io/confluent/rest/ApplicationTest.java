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

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpStatus.Code;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.security.Constraint;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.MediaType;

import io.confluent.common.config.ConfigException;
import io.confluent.rest.extention.ResourceExtension;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ApplicationTest {

  private static final String REALM = "realm";

  @Rule
  public final ExpectedException expectedException = ExpectedException.none();

  @Before
  public void setUp() {
    TestApp.SHUTDOWN_CALLED.set(false);
    TestRegistryExtension.CLOSE_CALLED.set(false);
  }

  private TestApp application;

  @Before
  public void setup() throws Exception {
    application = new TestApp();
  }

  @After
  public void tearDown() throws Exception {
    application.stop();
  }

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
    List<URI> listeners = application.parseListeners(listenersConfig, -1, Arrays.asList("http", "https"), "http");
    assertEquals("Should have two listeners.", 2, listeners.size());
    assertExpectedUri(listeners.get(0), "http", "localhost", 123);
    assertExpectedUri(listeners.get(1), "https", "localhost", 124);
  }

  @Test(expected = ConfigException.class)
  public void testParseListenersUnparseableUri() {
    List<String> listenersConfig = new ArrayList<String>();
    listenersConfig.add("!");
    Application.parseListeners(listenersConfig, -1, Arrays.asList("http", "https"), "http");
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
    Application.parseListeners(listenersConfig, -1, Arrays.asList("http", "https"), "http");
  }

  @Test(expected = ConfigException.class)
  public void testParseListenersNoPort() {
    List<String> listenersConfig = new ArrayList<String>();
    listenersConfig.add("http://localhost");
    Application.parseListeners(listenersConfig, -1, Arrays.asList("http", "https"), "http");
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
    ConstraintSecurityHandler securityHandler = application.createBasicSecurityHandler(REALM, Collections.emptyList());
    assertEquals(securityHandler.getRealmName(), REALM);
    assertTrue(securityHandler.getRoles().isEmpty());
    assertNotNull(securityHandler.getLoginService());
    assertNotNull(securityHandler.getAuthenticator());
    assertEquals(1, securityHandler.getConstraintMappings().size());
    assertFalse(securityHandler.getConstraintMappings().get(0).getConstraint().isAnyRole());
  }

  @Test
  public void testCreateSecurityHandlerWithAllRoles() {
    ConstraintSecurityHandler securityHandler = application.createBasicSecurityHandler(REALM, Arrays.asList("*"));
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
    ConstraintSecurityHandler securityHandler = application.createBasicSecurityHandler(REALM, roles);
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
    ConstraintSecurityHandler securityHandler = application.createBasicSecurityHandler(REALM, roles);
    servletContextHandler.setSecurityHandler(securityHandler);
    setAndAssertUnsecuredConstraints(servletContextHandler, securityHandler, 3);
  }

  @Test
  public void testSetUnsecurePathConstraintsWithSingleUnSecure(){
    ServletContextHandler servletContextHandler  = new ServletContextHandler();
    final List<String> roles = Arrays.asList("roleA", "roleB");
    ConstraintSecurityHandler securityHandler = application.createBasicSecurityHandler(REALM, roles);
    servletContextHandler.setSecurityHandler(securityHandler);
    setAndAssertUnsecuredConstraints(servletContextHandler, securityHandler, 1);
  }

  @Test
  public void testSetUnsecurePathConstraintsWithNoUnSecure(){
    ServletContextHandler servletContextHandler  = new ServletContextHandler();
    final List<String> roles = Arrays.asList("roleA", "roleB");
    ConstraintSecurityHandler securityHandler = application.createBasicSecurityHandler(REALM, roles);
    servletContextHandler.setSecurityHandler(securityHandler);
    setAndAssertUnsecuredConstraints(servletContextHandler, securityHandler, 0);
  }

  @Test
  public void testSetUnsecurePathConstraintsWithoutSecurityConstraints(){
    ServletContextHandler servletContextHandler  = new ServletContextHandler();
    Application.setUnsecurePathConstraints(servletContextHandler, Arrays.asList("/path1"));
    assertNull(servletContextHandler.getSecurityHandler());
  }

  @Test
  public void shouldInitializeResourceExtensions()throws Exception {
    try (TestApp testApp = new TestApp(TestRegistryExtension.class)) {
      assertThat(makeGetRequest(testApp, "/custom/resource"), is(Code.OK));
    }
  }

  @Test
  public void shouldThrowIfResourceExtensionThrows() throws Exception {
    expectedException.expectMessage(
        containsString("Exception throw by resource extension. ext:"));

    new TestApp(TestRegistryExtension.class, BadRegistryExtension.class);
  }

  @Test
  public void shouldCloseResourceExtensions() throws Exception{
    new TestApp(TestRegistryExtension.class).stop();
    assertThat("close called", TestRegistryExtension.CLOSE_CALLED.get(), is(true));
  }

  @Test
  public void shouldShutdownProperlyEvenIfResourceExtensionThrowsOnShutdown() throws Exception {
    final TestApp testApp = new TestApp(UnstoppableRegistryExtension.class);
    testApp.stop();
    testApp.join();
    assertThat("shutdown called", TestApp.SHUTDOWN_CALLED.get(), is(true));
  }

  private void setAndAssertUnsecuredConstraints(
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

  @SuppressWarnings("SameParameterValue")
  private HttpStatus.Code makeGetRequest(final TestApp app, final String path) throws Exception {
    final HttpGet httpget = new HttpGet(app.getListeners().get(0).toString() + path);

    try (CloseableHttpClient httpClient = HttpClients.createDefault();
        CloseableHttpResponse response = httpClient.execute(httpget)) {
      return HttpStatus.getCode(response.getStatusLine().getStatusCode());
    }
  }

  private static class TestApp extends Application<TestRestConfig> implements AutoCloseable {
    private static final AtomicBoolean SHUTDOWN_CALLED = new AtomicBoolean(true);

    @SafeVarargs
    private TestApp(final Class<? extends ResourceExtension>... extensions) throws Exception {
      super(createConfig(extensions));

      start();
    }

    @Override
    public void setupResources(final Configurable<?> config, final TestRestConfig appConfig) {
    }

    @Override
    public void close() throws Exception {
      stop();
    }

    @Override
    public void onShutdown() {
      SHUTDOWN_CALLED.set(true);
    }

    private List<URL> getListeners() {
      return Arrays.stream(server.getConnectors())
          .filter(connector -> connector instanceof ServerConnector)
          .map(ServerConnector.class::cast)
          .map(connector -> {
            try {
              final String protocol = new HashSet<>(connector.getProtocols())
                  .stream()
                  .map(String::toLowerCase)
                  .anyMatch(s -> s.equals("ssl")) ? "https" : "http";

              final int localPort = connector.getLocalPort();

              return new URL(protocol, "localhost", localPort, "");
            } catch (final Exception e) {
              throw new RuntimeException("Malformed listener", e);
            }
          })
          .collect(Collectors.toList());
    }

    @SafeVarargs
    private static TestRestConfig createConfig(final Class<? extends ResourceExtension>... exts) {
      final String extensionList = Arrays.stream(exts)
          .map(Class::getName)
          .collect(Collectors.joining(","));

      final HashMap<String, Object> props = new HashMap<>();
      props.put(RestConfig.LISTENERS_CONFIG, "http://0.0.0.0:0");
      props.put(RestConfig.RESOURCE_EXTENSION_CLASSES_CONFIG, extensionList);
      return new TestRestConfig(props);
    }
  }

  @Path("/custom")
  @Produces(MediaType.TEXT_PLAIN)
  public static class RestResource {
    @GET
    @Path("/resource")
    public String get() {
      return "Hello";
    }
  }

  public static class TestRegistryExtension implements ResourceExtension<TestApp> {
    private static final AtomicBoolean CLOSE_CALLED = new AtomicBoolean(true);

    @Override
    public void register(final Configurable<?> config, final TestApp app) {
      final TestRestConfig restConfig = app.getConfiguration();
      assertNotNull(restConfig);

      config.register(new RestResource());
    }

    @Override
    public void close() {
      CLOSE_CALLED.set(true);
    }
  }

  public static class BadRegistryExtension implements ResourceExtension<Application<?>> {
    @Override
    public void register(final Configurable<?> config, final Application<?> app) {
      throw new IllegalArgumentException("Boom");
    }
  }

  public static class UnstoppableRegistryExtension implements ResourceExtension<Application<?>> {

    @Override
    public void register(final Configurable<?> config, final Application<?> app) {
    }

    @Override
    public void close() throws IOException {
      throw new IOException("Boom");
    }
  }
}
