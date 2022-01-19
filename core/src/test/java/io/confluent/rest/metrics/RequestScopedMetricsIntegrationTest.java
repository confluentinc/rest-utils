package io.confluent.rest.metrics;

import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import io.confluent.rest.Application;
import io.confluent.rest.TestRestConfig;
import io.confluent.rest.annotations.PerformanceMetric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RequestScopedMetricsIntegrationTest {

  TestRestConfig config;
  SimpleApplication app;
  private Server server;

  @BeforeEach
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.setProperty("debug", "false");
    config = new TestRestConfig(props);
    app = new SimpleApplication(config);
    server = app.createServer();
    server.start();
  }

  @AfterEach
  public void tearDown() throws Exception {
    server.stop();
    server.join();
  }

  @Test
  @Tag("IntegrationTest")
  public void testRequestScopedMetricsCreateAndLookup() throws InterruptedException {
    int numMetrics = app.numMetrics();

    // this request should create a new metric with runtime tags
    Response response = ClientBuilder.newClient(app.resourceConfig.getConfiguration())
        .target(server.getURI())
        .path("/public/ts")
        .request(MediaType.APPLICATION_JSON_TYPE)
        .get();
    assertEquals(200, response.getStatus());

    // metrics may be updated concurrently, sleep here to wait for those background threads to
    // finish.
    Thread.sleep(500);

    assertTrue(
        numMetrics < app.numMetrics(),
        "numMetrics=" + numMetrics + ", app.numMetrics=" + app.numMetrics());
    numMetrics = app.numMetrics();

    // this request should reuse the previously created metrics
    response = ClientBuilder.newClient(app.resourceConfig.getConfiguration())
        .target(server.getURI())
        .path("/public/ts")
        .request(MediaType.APPLICATION_JSON_TYPE)
        .get();
    assertEquals(200, response.getStatus());
    assertEquals(numMetrics,
        app.numMetrics(),
        "numMetrics=" + numMetrics + ", app.numMetrics=" + app.numMetrics());
  }

  public class SimpleApplication extends Application<TestRestConfig> {

    Configurable resourceConfig;

    public SimpleApplication(TestRestConfig config) {
      super(config);
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      resourceConfig = config;
      config.register(TimestampResource.class);
      config.register(new Filter());
    }

    public int numMetrics() {
      return getMetrics().metrics().size();
    }
  }

  @Produces(MediaType.APPLICATION_JSON)
  @Path("/public")
  public static class TimestampResource {
    @GET
    @Path("/ts")
    @PerformanceMetric("public.ts")
    public Long ts() {
      return System.currentTimeMillis();
    }
  }

  public class Filter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext context) {
      Map<String, String> maps = new HashMap<>();
      maps.put("runtime_tag-1", "runtime_value-1");
      context.setProperty(MetricsResourceMethodApplicationListener.REQUEST_TAGS_PROP_KEY, maps);
    }
  }

}
