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

public class ApplicationContextTest {

  private static final String REALM = "REALM";
  private static String staticContent;

  @Rule
  public final ExpectedException expectedException = ExpectedException.none();

  @Rule
  public final TemporaryFolder tmpFolder = new TemporaryFolder();


  @Before
  public void setUp() {
    TestApp.SHUTDOWN_CALLED.set(false);
  }

  private TestApp application;

  @Before
  public void setup() throws Exception {
    Properties props = new Properties();
    props.setProperty(RestConfig.LISTENERS_CONFIG, "http://0.0.0.0:0");

    TestRestConfig config = new TestRestConfig(props);
    application = new TestApp(config);
  }

  @After
  public void tearDown() throws Exception {
    application.stop();
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
    ApplicationContext context1 = new ApplicationContextTest.TestContext(configBasic(), "/context1");
    ApplicationContext context2 = new ApplicationContextTest.TestContext("/context2");

    application.registerContextHandler(context1);
    application.registerContextHandler(context2);

    application.start();
    assertThat(makeGetRequest(application, "/context2/resource"), is(Code.OK));
    assertThat(makeGetRequest(application, "/context1/resource"), is(Code.UNAUTHORIZED));

    application.stop();
  }


  /* Test Exception Mapper isolation */
  @Test
  public void testExceptionMapperIsolation() throws Exception {
    ApplicationContext context1 = new TestExceptionalContext("/context1");
    ApplicationContext context2 = new ApplicationContextTest.TestContext("/context2");

    application.registerContextHandler(context1);
    application.registerContextHandler(context2);

    application.start();
    assertThat(makeGetRequest(application, "/context2/exception"), is(Code.INTERNAL_SERVER_ERROR));
    assertThat(makeGetRequest(application, "/context1/exception"), is(Code.ENHANCE_YOUR_CALM));

    application.stop();
  }

  /* Test Static Resource Isolation */
  @Test
  public void testStaticResourceIsolation() throws Exception {
    ApplicationContext context1 = new TestStaticContext("/context1");
    ApplicationContext context2 = new ApplicationContextTest.TestContext("/context2");

    application.registerContextHandler(context1);
    application.registerContextHandler(context2);

    application.start();
    assertThat(makeGetRequest(application, "/context2"), is(Code.NOT_FOUND));
    assertThat(makeGetRequest(application, "/context1/"), is(Code.OK));

    application.stop();
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

    public TestApp(TestRestConfig config) {
      super(config);
    }

    @Override
    public void setupResources(final Configurable<?> config, final TestRestConfig appConfig) { }

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

  private static class TestContext extends ApplicationContext<TestRestConfig> {

    TestContext(String path) {
      this(new TestRestConfig(), path);
    }

    TestContext(TestRestConfig conf, String path) {
      super(conf, path);
    }

    public void setupResources(final Configurable<?> config, TestRestConfig appConfig) {
      config.register(new RestResource());
    }
  }

  private static class TestExceptionalContext extends TestContext {

    TestExceptionalContext(String path) {
      super(path);
    }

    public void setupResources(final Configurable<?> config, TestRestConfig appConfig) {
      config.register(new RestResource());
      config.register(TestExceptionMapper.class);
    }
  }

  private static class TestStaticContext extends TestContext {
    TestStaticContext(String path) {
      super(path);
    }


    public void setupResources(final Configurable<?> config, TestRestConfig appConfig) {
      config.register(new RestResource());
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