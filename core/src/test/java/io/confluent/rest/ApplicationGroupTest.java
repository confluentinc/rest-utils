package io.confluent.rest;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpStatus.Code;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.glassfish.jersey.servlet.ServletProperties;
import org.junit.*;
import org.junit.rules.ExpectedException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;


import org.junit.rules.TemporaryFolder;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class ApplicationGroupTest {

  private static final String REALM = "REALM";
  private static String staticContent;
  static TestRestConfig testConfig;

  @Rule
  public final ExpectedException expectedException = ExpectedException.none();

  @Rule
  public final TemporaryFolder tmpFolder = new TemporaryFolder();

  private ApplicationGroup applications;

  @Before
  public void setup() throws Exception {
    Properties props = new Properties();
    props.setProperty(RestConfig.LISTENERS_CONFIG, "http://0.0.0.0:0");

    testConfig = new TestRestConfig(props);
  }

  @BeforeClass
  public static void setupStatic() throws Exception {
    try (
            InputStreamReader isr = new InputStreamReader(ClassLoader.getSystemResourceAsStream("static/index.html"), StandardCharsets.UTF_8);
            BufferedReader br = new BufferedReader(isr)
    ) {
      staticContent = br.readLine() + System.lineSeparator();
    }
  }

  private TestRestConfig configBasic() {
    Properties props = new Properties();
    props.put(RestConfig.AUTHENTICATION_METHOD_CONFIG, RestConfig.AUTHENTICATION_METHOD_BASIC);
    props.put(RestConfig.AUTHENTICATION_REALM_CONFIG, "c3");
    props.put(RestConfig.AUTHENTICATION_ROLES_CONFIG, Collections.singletonList("Administrators"));

    return new TestRestConfig(props);
  }

  /* Ensure security handlers are confined to a single context */
  @Test
  public void testSecurityHandlerIsolation() throws Exception {
    TestAppGroup applications = new TestAppGroup(
            new TestApp("/app1"),
            new TestApp(configBasic(), "/app2"));

    applications.start();

    assertThat(makeGetRequest(applications, "/app1/resource"), is(Code.OK));
    assertThat(makeGetRequest(applications, "/app2/resource"), is(Code.UNAUTHORIZED));

    applications.stop();
  }

  /* Test Exception Mapper isolation */
  @Test
  public void testExceptionMapperIsolation() throws Exception {
    TestAppGroup applications = new TestAppGroup(
            new TestApp("/app1"),
            new TestExceptionalApp("/app2"));
    applications.start();

    assertThat(makeGetRequest(applications, "/app1/exception"), is(Code.INTERNAL_SERVER_ERROR));
    assertThat(makeGetRequest(applications, "/app2/exception"), is(Code.ENHANCE_YOUR_CALM));

    applications.stop();
  }

  /* Test Static Resource Isolation */
  @Test
  public void testStaticResourceIsolation() throws Exception {
    TestAppGroup applications = new TestAppGroup(
            new TestApp("/app1"),
            new TestStaticApp("/app2"));
    applications.start();

    assertThat(makeGetRequest(applications, "/app1/index.html"), is(Code.NOT_FOUND));
    assertThat(makeGetRequest(applications, "/app2/index.html"), is(Code.OK));

    applications.stop();
  }

  @SuppressWarnings("SameParameterValue")
  private HttpStatus.Code makeGetRequest(final TestAppGroup app, final String path) throws Exception {
    final HttpGet httpget = new HttpGet(app.getListeners().get(0).toString() + path);

    try (CloseableHttpClient httpClient = HttpClients.createDefault();
         CloseableHttpResponse response = httpClient.execute(httpget)) {
      return HttpStatus.getCode(response.getStatusLine().getStatusCode());
    }
  }

  private static class TestAppGroup extends ApplicationGroup<TestRestConfig> {
    public TestAppGroup(TestApp... apps) {
      super(testConfig, apps);
    }

    private List<URL> getListeners() {
      return Arrays.stream(this.getServer().getConnectors())
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
  }

  private static class TestApp extends Application<TestRestConfig> implements AutoCloseable {
    private static final AtomicBoolean SHUTDOWN_CALLED = new AtomicBoolean(true);

    public TestApp() {
      this(testConfig);
    }

    public TestApp(String path) {
      this(testConfig, path);
    }

    public TestApp(TestRestConfig config) {
      super(config);
    }

    public TestApp(TestRestConfig config, String path) {
      super(config, path);
    }

    @Override
    public void setupResources(final Configurable<?> config, final TestRestConfig appConfig) {
      config.register(RestResource.class);
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
  }

  private static class TestExceptionalApp extends TestApp {
    public TestExceptionalApp(TestRestConfig config) {
      super(config);
    }

    public TestExceptionalApp(String path) {
      super(path);
    }

    @Override
    public void setupResources(final Configurable<?> config, final TestRestConfig appConfig) {
      config.register(RestResource.class);
      config.register(TestExceptionMapper.class);
    }
  }

  private static class TestStaticApp extends TestApp {
    public TestStaticApp(TestRestConfig config) {
      super(config);
    }

    public TestStaticApp(String path) {
      super(path);
    }

    @Override
    public void setupResources(final Configurable<?> config, final TestRestConfig appConfig) {
      config.register(RestResource.class);
      config.property(ServletProperties.FILTER_STATIC_CONTENT_REGEX, "/(index\\.html|)");
    }

    @Override
    protected ResourceCollection getStaticResources() {
      return new ResourceCollection(Resource.newClassPathResource("static"));
    }
  }

  @Path("/")
  @Produces(MediaType.TEXT_PLAIN)
  public static class RestResource {
    @GET
    @Path("/resource")
    public String get() {
      return "Hello";
    }

    @GET
    @Path("/exception")
    public String throwException() throws Throwable {
      throw new Throwable("catch!");
    }
  }

  public static class TestExceptionMapper implements ExceptionMapper<Throwable> {
    @Override
    public Response toResponse(Throwable throwable) {

      return Response.status(420)
              .entity(throwable.getMessage())
              .type(MediaType.APPLICATION_JSON)
              .build();
    }
  }
}
