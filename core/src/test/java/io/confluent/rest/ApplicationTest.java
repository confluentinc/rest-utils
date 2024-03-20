/*
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
 */

package io.confluent.rest;

import static io.confluent.rest.metrics.TestRestMetricsContext.RESOURCE_LABEL_TYPE;
import static org.apache.kafka.common.metrics.MetricsContext.NAMESPACE;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableMap;
import io.confluent.rest.extension.ResourceExtension;
import io.confluent.rest.metrics.RestMetricsContext;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.MediaType;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.kafka.common.config.ConfigException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpStatus.Code;
import org.eclipse.jetty.jaas.JAASLoginService;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.security.Constraint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ApplicationTest {

  private static final String REALM = "realm";

  @BeforeEach
  public void setUp() {
    TestApp.SHUTDOWN_CALLED.set(false);
    TestRegistryExtension.CLOSE_CALLED.set(false);
  }

  private TestApp application;

  @BeforeEach
  public void setup() throws Exception {
    application = new TestApp();
  }

  @AfterEach
  public void tearDown() throws Exception {
    application.stop();
  }

  @Test
  public void testParseListenersDeprecated() {
    List<String> listenersConfig = new ArrayList<>();
    List<URI> listeners = Application.parseListeners(listenersConfig,
        RestConfig.PORT_CONFIG_DEFAULT,
        RestConfig.SUPPORTED_URI_SCHEMES, "http");
    assertEquals(1, listeners.size(), "Should have only one listener.");
    assertExpectedUri(listeners.get(0), "http", "0.0.0.0", RestConfig.PORT_CONFIG_DEFAULT);
  }

  @Test
  public void testParseListenersHttpAndHttps() {
    List<String> listenersConfig = new ArrayList<>();
    listenersConfig.add("http://localhost:123");
    listenersConfig.add("https://localhost:124");
    List<URI> listeners = Application.parseListeners(listenersConfig, -1,
        RestConfig.SUPPORTED_URI_SCHEMES, "http");
    assertEquals(2, listeners.size(), "Should have two listeners.");
    assertExpectedUri(listeners.get(0), "http", "localhost", 123);
    assertExpectedUri(listeners.get(1), "https", "localhost", 124);
  }

  @Test
  public void testParseListenersUnparseableUri() {
    List<String> listenersConfig = new ArrayList<>();
    listenersConfig.add("!");
    assertThrows(ConfigException.class,
        () ->
            Application.parseListeners(listenersConfig, -1, RestConfig.SUPPORTED_URI_SCHEMES,
                "http"));
  }

  @Test
  public void testParseListenersUnsupportedScheme() {
    List<String> listenersConfig = new ArrayList<>();
    listenersConfig.add("http://localhost:8080");
    listenersConfig.add("foo://localhost:8081");
    assertThrows(ConfigException.class,
        () ->
            Application.parseListeners(listenersConfig, -1, RestConfig.SUPPORTED_URI_SCHEMES,
                "http"));
  }

  public void testParseListenersNoSupportedListeners() {
    List<String> listenersConfig = new ArrayList<>();
    listenersConfig.add("foo://localhost:8080");
    listenersConfig.add("bar://localhost:8081");
    assertThrows(ConfigException.class,
        () ->
            Application.parseListeners(listenersConfig, -1, RestConfig.SUPPORTED_URI_SCHEMES,
                "http"));
  }

  @Test
  public void testParseListenersNoPort() {
    List<String> listenersConfig = new ArrayList<>();
    listenersConfig.add("http://localhost");
    assertThrows(ConfigException.class,
        () ->
            Application.parseListeners(listenersConfig, -1, RestConfig.SUPPORTED_URI_SCHEMES,
                "http"));
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
  public void testAuthEnabledBEARER() {
    assertTrue(Application.enableBearerAuth(RestConfig.AUTHENTICATION_METHOD_BEARER));
  }

  @Test
  public void testCreateSecurityHandlerWithNoRoles() {
    final Map<String, Object> config = ImmutableMap.of(
        RestConfig.AUTHENTICATION_METHOD_CONFIG, RestConfig.AUTHENTICATION_METHOD_BASIC,
        RestConfig.AUTHENTICATION_REALM_CONFIG, REALM,
        RestConfig.AUTHENTICATION_ROLES_CONFIG, "");

    ConstraintSecurityHandler securityHandler = new TestApp(config).createBasicSecurityHandler();
    assertEquals(securityHandler.getRealmName(), REALM);
    assertTrue(securityHandler.getRoles().isEmpty());
    assertNotNull(securityHandler.getLoginService());
    assertNotNull(securityHandler.getAuthenticator());
    assertEquals(1, securityHandler.getConstraintMappings().size());
    assertFalse(securityHandler.getConstraintMappings().get(0).getConstraint().isAnyRole());
  }

  @Test
  public void testCreateSecurityHandlerWithAllRoles() {
    final Map<String, Object> config = ImmutableMap.of(
        RestConfig.AUTHENTICATION_METHOD_CONFIG, RestConfig.AUTHENTICATION_METHOD_BASIC,
        RestConfig.AUTHENTICATION_REALM_CONFIG, REALM,
        RestConfig.AUTHENTICATION_ROLES_CONFIG, "*");

    ConstraintSecurityHandler securityHandler = new TestApp(config).createBasicSecurityHandler();
    assertEquals(securityHandler.getRealmName(), REALM);
    assertTrue(securityHandler.getRoles().isEmpty());
    assertNotNull(securityHandler.getLoginService());
    assertNotNull(securityHandler.getAuthenticator());
    assertEquals(1, securityHandler.getConstraintMappings().size());
    assertTrue(securityHandler.getConstraintMappings().get(0).getConstraint().isAnyRole());
  }

  @Test
  public void testCreateSecurityHandlerWithSpecificRoles() {
    final Map<String, Object> config = ImmutableMap.of(
        RestConfig.AUTHENTICATION_METHOD_CONFIG, RestConfig.AUTHENTICATION_METHOD_BASIC,
        RestConfig.AUTHENTICATION_REALM_CONFIG, REALM,
        RestConfig.AUTHENTICATION_ROLES_CONFIG, "roleA, roleB");

    ConstraintSecurityHandler securityHandler = new TestApp(config).createBasicSecurityHandler();
    assertEquals(securityHandler.getRealmName(), REALM);
    assertFalse(securityHandler.getRoles().isEmpty());
    assertNotNull(securityHandler.getLoginService());
    assertNotNull(securityHandler.getAuthenticator());
    assertEquals(1, securityHandler.getConstraintMappings().size());
    final Constraint constraint = securityHandler.getConstraintMappings().get(0).getConstraint();
    assertFalse(constraint.isAnyRole());
    assertEquals(constraint.getRoles().length, 2);
    assertArrayEquals(constraint.getRoles(), new String[]{"roleA", "roleB"});
  }

  @Test
  public void testSetUnsecurePathConstraintsWithUnSecure() {
    final Map<String, Object> config = ImmutableMap.of(
        RestConfig.AUTHENTICATION_SKIP_PATHS, "/path/1,/path/2");

    ConstraintSecurityHandler securityHandler = new TestApp(config).createBasicSecurityHandler();

    final List<ConstraintMapping> mappings = securityHandler.getConstraintMappings();
    assertThat(mappings.size(), is(3));
    assertThat(mappings.get(0).getPathSpec(), is("/*"));
    assertThat(mappings.get(0).getConstraint().getAuthenticate(), is(true));
    assertThat(mappings.get(1).getPathSpec(), is("/path/1"));
    assertThat(mappings.get(1).getConstraint().getAuthenticate(), is(false));
    assertThat(mappings.get(2).getPathSpec(), is("/path/2"));
    assertThat(mappings.get(2).getConstraint().getAuthenticate(), is(false));
  }

  @Test
  public void testConstraintsWhenOptionsRequestNotAllowedAndSecurityOn() {
    final Map<String, Object> config = ImmutableMap.of(
        RestConfig.REJECT_OPTIONS_REQUEST, true);

    ConstraintSecurityHandler securityHandler = new TestApp(config).createBasicSecurityHandler();

    final List<ConstraintMapping> mappings = securityHandler.getConstraintMappings();
    assertThat(mappings.size(), is(2));
    assertThat(mappings.get(0).getPathSpec(), is("/*"));
    assertThat(mappings.get(0).getMethodOmissions(), is(new String[]{"OPTIONS"}));
    assertThat(mappings.get(1).getPathSpec(), is("/*"));
    assertThat(mappings.get(1).getConstraint().getAuthenticate(), is(true));
    assertThat(mappings.get(1).getConstraint().isAnyRole(), is(false));
    assertThat(mappings.get(1).getMethod(), is("OPTIONS"));

  }

  @Test
  public void testConstraintsWhenOptionsRequestNotAllowedAndSecurityOff() {
    final Map<String, Object> config = ImmutableMap.of(
        RestConfig.REJECT_OPTIONS_REQUEST, true);

    Application<TestRestConfig> app = new TestApp(config);
    ServletContextHandler context = new ServletContextHandler();
    app.configureSecurityHandler(context);

    ConstraintSecurityHandler securityHandler = (ConstraintSecurityHandler) context.getSecurityHandler();

    final List<ConstraintMapping> mappings = securityHandler.getConstraintMappings();
    assertThat(mappings.size(), is(1));
    assertThat(mappings.get(0).getPathSpec(), is("/*"));
    assertThat(mappings.get(0).getConstraint().getAuthenticate(), is(true));
    assertThat(mappings.get(0).getConstraint().isAnyRole(), is(false));
    assertThat(mappings.get(0).getMethod(), is("OPTIONS"));

  }


  @Test
  public void testBearerNoAuthenticator() {
    final Map<String, Object> config = ImmutableMap.of(
        RestConfig.AUTHENTICATION_METHOD_CONFIG, RestConfig.AUTHENTICATION_METHOD_BEARER);

    Application<?> app = new TestApp(config) {
      @Override
      protected LoginService createLoginService() {
        return new JAASLoginService("realm");
      }
    };
    assertThrows(UnsupportedOperationException.class,
        () ->
            app.createBearerSecurityHandler());
  }

  @Test
  public void testBearerNoLoginService() {
    final Map<String, Object> config = ImmutableMap.of(
        RestConfig.AUTHENTICATION_METHOD_CONFIG, RestConfig.AUTHENTICATION_METHOD_BEARER);

    Application<?> app = new TestApp(config) {
      @Override
      protected LoginAuthenticator createAuthenticator() {
        return new BasicAuthenticator();
      }
    };
    assertThrows(UnsupportedOperationException.class,
        () ->
            app.createBearerSecurityHandler());
  }

  @Test
  public void testBearerNoAuthenticatorNoLoginService() {
    final Map<String, Object> config = ImmutableMap.of(
        RestConfig.AUTHENTICATION_METHOD_CONFIG, RestConfig.AUTHENTICATION_METHOD_BEARER);

    Application<?> app = new TestApp(config);
    assertThrows(UnsupportedOperationException.class,
        () ->
            app.createBearerSecurityHandler());
  }

  @Test
  public void shouldInitializeResourceExtensions() throws Exception {
    try (TestApp testApp = new TestApp(TestRegistryExtension.class)) {
      assertThat(makeGetRequest(testApp, "/custom/resource"), is(Code.OK));
    }
  }

  @Test
  public void shouldThrowIfResourceExtensionThrows() {
    Exception e = assertThrows(Exception.class,
        () -> new TestApp(TestRegistryExtension.class, BadRegistryExtension.class));
    assertThat(e.getMessage(), containsString("Exception throw by resource extension. ext:"));
  }

  @Test
  public void shouldCloseResourceExtensions() throws Exception {
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

  @Test
  public void testDefaultMetricsContext() throws Exception {
    TestApp testApp = new TestApp();

    assertEquals(testApp.metricsContext().getLabel(RESOURCE_LABEL_TYPE),
        RestConfig.METRICS_JMX_PREFIX_DEFAULT);
    assertEquals(testApp.metricsContext().getLabel(NAMESPACE),
        RestConfig.METRICS_JMX_PREFIX_DEFAULT);
  }

  @Test
  public void testMetricsContextResourceOverride() throws Exception {
    Map<String, Object> props = new HashMap<>();
    props.put("metrics.context.resource.type", "FooApp");

    TestApp testApp = new TestApp(props);

    assertEquals(testApp.metricsContext().getLabel(RESOURCE_LABEL_TYPE), "FooApp");
    assertEquals(testApp.metricsContext().getLabel(NAMESPACE),
        RestConfig.METRICS_JMX_PREFIX_DEFAULT);

    /* Only NameSpace should be propagated to JMX */
    String jmx_domain = RestConfig.METRICS_JMX_PREFIX_DEFAULT;
    String mbean_name = String.format("%s:type=kafka-metrics-count", jmx_domain);

    MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    assertNotNull(server.getObjectInstance(new ObjectName(mbean_name)));
  }

  @Test
  public void testMetricsContextJMXPrefixPropagation() {
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.METRICS_JMX_PREFIX_CONFIG, "FooApp");

    TestApp testApp = new TestApp(props);

    assertEquals(testApp.metricsContext().getLabel(RESOURCE_LABEL_TYPE), "FooApp");
    assertEquals(testApp.metricsContext().getLabel(NAMESPACE), "FooApp");
  }

  @Test
  public void testMetricsContextJMXBeanRegistration() throws Exception {
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.METRICS_JMX_PREFIX_CONFIG, "FooApp");

    new TestApp(props);

    MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    assertNotNull(server.getObjectInstance(new ObjectName("FooApp:type=kafka-metrics-count")));
  }

  private void assertExpectedUri(URI uri, String scheme, String host, int port) {
    assertEquals(scheme, uri.getScheme(), "Scheme should be " + scheme);
    assertEquals(host, uri.getHost(), "Host should be " + host);
    assertEquals(port, uri.getPort(), "Port should be " + port);
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

    public TestApp(final Map<String, Object> config) {
      super(createConfig(config));
    }

    public RestMetricsContext metricsContext() {
      return getConfiguration().getMetricsContext();
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
      return Arrays.stream(getServer().getConnectors())
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

      return createConfig(
          Collections.singletonMap(RestConfig.RESOURCE_EXTENSION_CLASSES_CONFIG, extensionList));
    }

    private static TestRestConfig createConfig(final Map<String, Object> props) {
      final HashMap<String, Object> config = new HashMap<>(props);
      config.put(RestConfig.LISTENERS_CONFIG, "http://0.0.0.0:0");
      return new TestRestConfig(config);
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
