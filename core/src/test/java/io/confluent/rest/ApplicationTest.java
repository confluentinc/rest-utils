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

import com.google.common.collect.ImmutableMap;

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
import org.eclipse.jetty.util.security.Constraint;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
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
import java.util.Properties;
import java.util.concurrent.RejectedExecutionException;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.MediaType;

import io.confluent.rest.extension.ResourceExtension;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ApplicationTest {

  private static final Logger log = LoggerFactory.getLogger(ApplicationTest.class);

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
  public void testParseListToMap() {
    assertEquals(
        new HashMap(){
          {
            put("k1","v1");
            put("k2","v2");
          }
        },
        Application.parseListToMap(Arrays.asList("k1:v1", "k2:v2"))
    );
  }

  @Test
  public void testParseEmptyListToMap() {
    assertEquals(
        new HashMap(),
        Application.parseListToMap(new ArrayList<>())
    );
  }

  @Test(expected = ConfigException.class)
  public void testParseBadListToMap() {
    assertEquals(
        new HashMap(),
        Application.parseListToMap(Arrays.asList("k1:v1:what", "k2:v2"))
    );
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

  @Test(expected = UnsupportedOperationException.class)
  public void testBearerNoAuthenticator() {
    final Map<String, Object> config = ImmutableMap.of(
        RestConfig.AUTHENTICATION_METHOD_CONFIG, RestConfig.AUTHENTICATION_METHOD_BEARER);

    Application app = new TestApp(config) {
      @Override
      protected LoginService createLoginService() {
        return new JAASLoginService("realm");
      }
    };
    app.createBearerSecurityHandler();
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testBearerNoLoginService() {
    final Map<String, Object> config = ImmutableMap.of(
        RestConfig.AUTHENTICATION_METHOD_CONFIG, RestConfig.AUTHENTICATION_METHOD_BEARER);

    Application app = new TestApp(config) {
      @Override
      protected LoginAuthenticator createAuthenticator() {
        return new BasicAuthenticator();
      }
    };
    app.createBearerSecurityHandler();
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testBearerNoAuthenticatorNoLoginService() {
    final Map<String, Object> config = ImmutableMap.of(
        RestConfig.AUTHENTICATION_METHOD_CONFIG, RestConfig.AUTHENTICATION_METHOD_BEARER);

    Application app = new TestApp(config);
    app.createBearerSecurityHandler();
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

  @Test
  public void testThreadPoolLessThreshold()throws Exception {
    int numOfClients = 3;
    TestThreadPoolConfigApplication app = new TestThreadPoolConfigApplication();
    String uri = app.getUri();
    try {
      app.start();
      makeConcurrentGetRequests(uri + "/custom/resource", numOfClients);
    } catch (Exception e) {
    } finally {
      log.info("Current running thread {}, maximum thread {}.", app.getThreads(), app.getMaxThreads());
      assertTrue("Total number of running threads less than maximum number of threads " + app.getMaxThreads(),
              app.getThreads() - app.getMaxThreads() < 0);
      app.stop();
    }
  }

  /**
   * This testing will show the number of running threads will increase and reach the maximum threads, finally throw
   * exceptions in server.
   * The following similair exception will be seen on console when the number of running thread reach the maximum
   * threads allowed.
   * [2019-09-28 08:37:59,504] WARN QueuedThreadPool[qtp527464124]@1f7076bc{STARTED,2<=20<=20,i=0,r=2,q=2}
   * [ReservedThreadExecutor@1b1f5012{s=0/2,p=0}] rejected org.eclipse.jetty.io.ManagedSelector$Accept@26ac0324
   * (org.eclipse.jetty.util.thread.QueuedThreadPool:471)
   * ...
   * java.util.concurrent.RejectedExecutionException: CEP:NetworkTrafficSelectChannelEndPoint@3a0b8ca7{/127.0.0.1:64929
   * <->/127.0.0.1:8080,OPEN,fill=FI,flush=-,to=2/30000}{io=1/0,kio=1,kro=1}->HttpConnection@5b06a71d[p=HttpParser
   * {s=START,0 of -1},g=HttpGenerator@10ef51b0{s=START}]=>HttpChannelOverHttp@42bd59c7{r=0,c=false,c=false/false,
   * a=IDLE,uri=null,age=0}:runFillable:BLOCKING
   **/
  @Test
  public void testThreadPoolReachThreshold()throws Exception {
    int numOfClients = 20;
    TestThreadPoolConfigApplication app = new TestThreadPoolConfigApplication();
    String uri = app.getUri();
    try {
      app.start();
      makeConcurrentGetRequests(uri + "/custom/resource", numOfClients);
    } catch (Exception e) {
    } finally {
      log.info("Current running thread {}, maximum thread {}.", app.getThreads(), app.getMaxThreads());
      assertTrue("Total number of running threads reach maximum number of threads " + app.getMaxThreads(),
              app.getThreads() - app.getMaxThreads() == 0);
      app.stop();
    }
  }

  @SuppressWarnings("SameParameterValue")
  private void makeConcurrentGetRequests(String uri, int numThread) throws Exception {
    Thread[] threads = new Thread[numThread];
    for(int i = 0; i < numThread; i++) {
      threads[i] = new Thread() {
        public void run() {
          HttpGet httpget = new HttpGet(uri);
          CloseableHttpClient httpclient = HttpClients.createDefault();
          int statusCode = -1;
          CloseableHttpResponse response = null;
          try {
            response = httpclient.execute(httpget);
            statusCode = response.getStatusLine().getStatusCode();
          } catch (Exception e) {
          } finally {
            try {
              if (response != null) {
                response.close();
              }
              httpclient.close();
            } catch (Exception e) {
            }
          }
        }
      };

      threads[i].start();
    }

    for(int i = 0; i < numThread; i++) {
      threads[i].join();
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

      return createConfig(Collections.singletonMap(RestConfig.RESOURCE_EXTENSION_CLASSES_CONFIG, extensionList));
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

  private static class TestThreadPoolConfigApplication extends Application<TestRestConfig> {
    static Properties props = null;
    public TestThreadPoolConfigApplication() {
      super(createConfig());
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      config.register(new RestResource());
    }

    public String getUri() {
      return (String)props.get(RestConfig.LISTENERS_CONFIG);
    }

    private static TestRestConfig createConfig() {
      props = new Properties();
      String uri = "http://localhost:8080";
      props.put(RestConfig.LISTENERS_CONFIG, uri);
      props.put(RestConfig.THREAD_POOL_MIN_CONFIG, "2");
      props.put(RestConfig.THREAD_POOL_MAX_CONFIG, "20");
      props.put(RestConfig.REQUEST_QUEUE_CAPACITY_INITIAL_CONFIG, "2");
      props.put(RestConfig.REQUEST_QUEUE_CAPACITY_CONFIG, "4");
      props.put(RestConfig.REQUEST_QUEUE_CAPACITY_GROWBY_CONFIG, "1");
      return new TestRestConfig(props);
    }
  }
}
