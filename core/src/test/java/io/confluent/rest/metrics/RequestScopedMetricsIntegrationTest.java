package io.confluent.rest.metrics;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Configurable;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import io.confluent.common.utils.IntegrationTest;
import io.confluent.rest.Application;
import io.confluent.rest.RestConfig;
import io.confluent.rest.TestRestConfig;
import io.confluent.rest.annotations.PerformanceMetric;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RequestScopedMetricsIntegrationTest {

  TestRestConfig config;
  SimpleApplication app;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.setProperty("debug", "false");
    config = new TestRestConfig(props);
    app = new SimpleApplication(config);
    app.start();
  }

  @After
  public void tearDown() throws Exception {
    app.stop();
    app.join();
  }

  @Test
  @Category(IntegrationTest.class)
  public void testRequestScopedMetricsCreateAndLookup() throws InterruptedException {
    int numMetrics = app.numMetrics();

    // this request should create a new metric with runtime tags
    Response response = ClientBuilder.newClient(app.resourceConfig.getConfiguration())
        .target("http://localhost:" + config.getInt(RestConfig.PORT_CONFIG))
        .path("/public/ts")
        .request(MediaType.APPLICATION_JSON_TYPE)
        .get();
    assertEquals(200, response.getStatus());

    // metrics may be updated concurrently, sleep here to wait for those background threads to
    // finish.
    Thread.sleep(500);

    assertTrue("numMetrics=" + numMetrics + ", app.numMetrics=" + app.numMetrics(),
        numMetrics < app.numMetrics());
    numMetrics = app.numMetrics();

    // this request should reuse the previously created metrics
    response = ClientBuilder.newClient(app.resourceConfig.getConfiguration())
        .target("http://localhost:" + config.getInt(RestConfig.PORT_CONFIG))
        .path("/public/ts")
        .request(MediaType.APPLICATION_JSON_TYPE)
        .get();
    assertEquals(200, response.getStatus());
    assertEquals("numMetrics=" + numMetrics + ", app.numMetrics=" + app.numMetrics(), numMetrics,
        app.numMetrics());
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
      return metrics.metrics().size();
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
