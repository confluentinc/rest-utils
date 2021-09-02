package io.confluent.rest.metrics;

import io.confluent.rest.*;
import java.util.List;
import org.apache.kafka.common.metrics.KafkaMetric;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.glassfish.jersey.server.ServerProperties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Properties;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.Produces;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import static io.confluent.rest.metrics.MetricsResourceMethodApplicationListener.HTTP_STATUS_CODE_TAG;
import static org.junit.Assert.*;

public class MetricsResourceMethodApplicationListenerIntegrationTest {

  TestRestConfig config;
  ApplicationWithFilter app;
  private Server server;
  volatile Throwable handledException = null;

  @Before
  public void setUp() throws Exception {
    TestMetricsReporter.reset();
    Properties props = new Properties();
    props.setProperty("debug", "false");
    props.put(RestConfig.METRICS_REPORTER_CLASSES_CONFIG, "io.confluent.rest.TestMetricsReporter");
    config = new TestRestConfig(props);
    app = new ApplicationWithFilter(config);
    server = app.createServer();
    server.start();
  }

  @After
  public void tearDown() throws Exception {
    server.stop();
    server.join();
  }

  @Test
  public void testListenerHandlesDispatchErrorsGracefully() {
    // request events do not follow the typical order when an error is raised during dispatch
    // this test ensures we properly handle the case where we might encounter events in the
    // following order.
    //
    // MATCHING_START -> REQUEST_MATCHED -> REQUEST_FILTERED
    //   -> RESOURCE_METHOD_START -> RESOURCE_METHOD_FINISHED -> ON_EXCEPTION -> FINISHED

    // RequestEvent.Type.FINISHED before RequestEvent.Type.RESP_FILTERS_START
    Response response = ClientBuilder.newClient(app.resourceConfig.getConfiguration())
        .target(server.getURI())
        .path("/private/endpoint")
        .request(MediaType.APPLICATION_JSON_TYPE)
        .get();
    assertEquals(500, response.getStatus());
    // ensure the true cause actually bubble up to the error handler
    assertNotNull(handledException);
    Throwable cause = handledException;
    while (!(cause instanceof ProcessingException)) {
      if (cause == cause.getCause()) {
        break;
      }
      cause = cause.getCause();
    }
    assertTrue(cause instanceof ProcessingException);
    assertEquals("Resource Java method invocation error.", cause.getMessage());
  }

  @Test
  public void testSuccessMetrics() {
    Response response = ClientBuilder.newClient(app.resourceConfig.getConfiguration())
        .target(server.getURI())
        .path("/public/hello")
        .request(MediaType.APPLICATION_JSON_TYPE)
        .get();
    assertEquals(200, response.getStatus());

    ClientBuilder.newClient(app.resourceConfig.getConfiguration())
        .target(server.getURI())
        .path("/public/hello")
        .request(MediaType.APPLICATION_JSON_TYPE)
        .get();

    //checkpoint ensures that all the assertions are tested
    int metricsCheckpointWindow = 0;
    int metricsCheckpointCumulative = 0;

    for (KafkaMetric metric : TestMetricsReporter.getMetricTimeseries()) {
      if (metric.metricName().name().equals("request-count-windowed")) {
        assertTrue(metric.measurable().toString().toLowerCase().startsWith("sampledstat"));
        metricsCheckpointWindow++;
        Object metricValue = metric.metricValue();
        assertTrue("Metrics should be measurable", metricValue instanceof Double);
        double countValue = (double) metricValue;
        assertTrue("Actual: " + countValue, countValue == 2.0);
      }
      if (metric.metricName().name().equals("request-count-cumulative")) {
        assertTrue(metric.measurable().toString().toLowerCase().startsWith("cumulativesum"));
        metricsCheckpointCumulative++;
        Object metricValue = metric.metricValue();
        assertTrue("Metrics should be measurable", metricValue instanceof Double);
        double countValue = (double) metricValue;
        assertTrue("Actual: " + countValue, countValue == 2.0);
      }
    }
    assertEquals(1, metricsCheckpointWindow); //A single metric for the windowed count
    assertEquals(1, metricsCheckpointCumulative); //A single metric for the cumulative count
  }

  @Test
  public void testExceptionMetrics() {
    Response response = ClientBuilder.newClient(app.resourceConfig.getConfiguration())
        .target(server.getURI())
        .path("/private/fake")
        .request(MediaType.APPLICATION_JSON_TYPE)
        .get();
    assertEquals(404, response.getStatus());

    ClientBuilder.newClient(app.resourceConfig.getConfiguration())
        .target(server.getURI())
        .path("/private/fake")
        .request(MediaType.APPLICATION_JSON_TYPE)
        .get();

    //checkpoints ensure that all the assertions are tested
    int rateCheckpoint4xx = 0;
    int windowCheckpoint4xx = 0;
    int cumulativeCheckpoint4xx = 0;
    int rateCheckpointNot4xx = 0;
    int windowCheckpointNot4xx = 0;
    int cumulativeCheckpointNot4xx = 0;
    int anyErrorRateCheckpoint = 0;
    int anyErrorWindowCheckpoint = 0;
    int anyErrorCumulativeCheckpoint = 0;

    for (KafkaMetric metric : TestMetricsReporter.getMetricTimeseries()) {
      if (metric.metricName().name().equals("request-error-rate")) {
        assertTrue(metric.measurable().toString().toLowerCase().startsWith("rate"));
        Object metricValue = metric.metricValue();
        assertTrue("Error rate metrics should be measurable", metricValue instanceof Double);
        double errorRateValue = (double) metricValue;
        if (metric.metricName().tags().getOrDefault(HTTP_STATUS_CODE_TAG, "").equals("4xx")) {
          rateCheckpoint4xx++;
          assertTrue("Actual: " + errorRateValue, errorRateValue > 0);
        } else if (!metric.metricName().tags().isEmpty()) {
          rateCheckpointNot4xx++;
          assertTrue(String.format("Actual: %f (%s)", errorRateValue, metric.metricName()),
              errorRateValue == 0.0 || Double.isNaN(errorRateValue));
        } else {
          anyErrorRateCheckpoint++;
          //average rate is not consistently above 0 here, so not validating
        }
      }
      if (metric.metricName().name().equals("request-error-count-windowed")) {
        assertTrue(metric.measurable().toString().toLowerCase().startsWith("sampledstat"));
        Object metricValue = metric.metricValue();
        assertTrue("Error count metrics should be measurable", metricValue instanceof Double);
        double errorCountValue = (double) metricValue;
        if (metric.metricName().tags().getOrDefault(HTTP_STATUS_CODE_TAG, "").equals("4xx")) {
          windowCheckpoint4xx++;
          assertTrue("Actual: " + errorCountValue, errorCountValue == 2.0);
        } else if (!metric.metricName().tags().isEmpty()) {
          windowCheckpointNot4xx++;
          assertTrue(String.format("Actual: %f (%s)", errorCountValue, metric.metricName()),
              errorCountValue == 0.0 || Double.isNaN(errorCountValue));
        } else {
          anyErrorWindowCheckpoint++;
          assertTrue("Count for all errors actual: " + errorCountValue, errorCountValue == 2.0);
        }
      }
      if (metric.metricName().name().equals("request-error-count-cumulative")) {
          assertTrue(metric.measurable().toString().toLowerCase().startsWith("cumulativesum"));
          Object metricValue = metric.metricValue();
          assertTrue("Error count metrics should be measurable", metricValue instanceof Double);
          double errorCountValue = (double) metricValue;
          if (metric.metricName().tags().getOrDefault(HTTP_STATUS_CODE_TAG, "").equals("4xx")) {
            cumulativeCheckpoint4xx++;
            assertTrue("Actual: " + errorCountValue, errorCountValue == 2.0);
          } else if (!metric.metricName().tags().isEmpty()) {
            cumulativeCheckpointNot4xx++;
            assertTrue(String.format("Actual: %f (%s)", errorCountValue, metric.metricName()),
                errorCountValue == 0.0 || Double.isNaN(errorCountValue));
          } else {
            anyErrorCumulativeCheckpoint++;
            assertTrue("Count for all errors actual: " + errorCountValue, errorCountValue == 2.0);
          }
      }
    }

    assertEquals(1, anyErrorRateCheckpoint); //A Single rate metric for the two errors
    assertEquals(1, anyErrorWindowCheckpoint); //A single windowed metric for the two errors
    assertEquals(1, anyErrorCumulativeCheckpoint); //A single cumulative metric for the two errors
    assertEquals(1, rateCheckpoint4xx); //Single rate metric for the two 4xx errors
    assertEquals(1, windowCheckpoint4xx); ///A single windowed metric for the two 4xx errors
    assertEquals(1, cumulativeCheckpoint4xx); ///A single cumulative metric for the two 4xx errors,
    assertEquals(5, rateCheckpointNot4xx); //Metrics for each of unknown, 1xx, 2xx, 3xx, 5xx
    assertEquals(5, windowCheckpointNot4xx); //Metrics for each of unknown, 1xx, 2xx, 3xx, 5xx for windowed metrics
    assertEquals(5, cumulativeCheckpointNot4xx); //Metrics for each of unknown, 1xx, 2xx, 3xx, 5xx for cumulative metrics

  }

  @Test
  public void testMapped500sAreCounted() {
    Response response = ClientBuilder.newClient(app.resourceConfig.getConfiguration())
        .target(server.getURI())
        .path("/public/caught")
        .request(MediaType.APPLICATION_JSON_TYPE)
        .get();
    assertEquals(500, response.getStatus());

    for (KafkaMetric metric : TestMetricsReporter.getMetricTimeseries()) {
      if (metric.metricName().name().equals("request-error-rate")) {
        Object metricValue = metric.metricValue();
        assertTrue("Error rate metrics should be measurable", metricValue instanceof Double);
        double errorRateValue = (double) metricValue;
        if (metric.metricName().tags().getOrDefault(HTTP_STATUS_CODE_TAG, "").equals("5xx")) {
          assertTrue("Actual: " + errorRateValue, errorRateValue > 0);
        } else if (!metric.metricName().tags().isEmpty()) {
          assertTrue(String.format("Actual: %f (%s)", errorRateValue, metric.metricName()),
              errorRateValue == 0.0 || Double.isNaN(errorRateValue));
        }
      }
    }
  }

  @Test
  public void testMetricReporterConfiguration() {
    ApplicationWithFilter app;
    Properties props = new Properties();

    props.put(RestConfig.METRICS_REPORTER_CONFIG_PREFIX + "prop1", "val1");
    props.put(RestConfig.METRICS_REPORTER_CONFIG_PREFIX + "prop2", "val2");
    props.put(RestConfig.METRICS_REPORTER_CONFIG_PREFIX + "prop3", "override");
    props.put("prop3", "original");
    props.put(RestConfig.METRICS_REPORTER_CLASSES_CONFIG, "io.confluent.rest.TestMetricsReporter");
    props.put("not.prefixed.config", "val3");

    app = new ApplicationWithFilter(new TestRestConfig(props));
    TestMetricsReporter reporter = (TestMetricsReporter) app.getMetrics().reporters().get(0);

    assertTrue(reporter.getConfigs().containsKey("not.prefixed.config"));
    assertTrue(reporter.getConfigs().containsKey("prop1"));
    assertTrue(reporter.getConfigs().containsKey("prop2"));
    assertEquals(reporter.getConfigs().get("prop3"), "override");
  }


  private class ApplicationWithFilter extends Application<TestRestConfig> {

    Configurable resourceConfig;

    ApplicationWithFilter(TestRestConfig props) {
      super(props);
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      resourceConfig = config;
      config.register(PrivateResource.class);
      config.register(new PublicResource());

      // ensures the dispatch error message gets shown in the response
      // as opposed to a generic error page
      config.property(ServerProperties.RESPONSE_SET_STATUS_OVER_SEND_ERROR, true);
    }

    @Override
    protected void configurePostResourceHandling(ServletContextHandler context) {
      context.setErrorHandler(new ErrorHandler() {
        @Override
        public void handle(
            String target,
            Request baseRequest,
            HttpServletRequest request,
            HttpServletResponse response
        ) throws IOException, ServletException {
          handledException = (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
          super.handle(target, baseRequest, request, response);
        }
      });
    }

  }

  @Produces(MediaType.APPLICATION_JSON)
  @Path("/private")
  private static class PrivateResource {

    @GET
    @Path("/endpoint")
    public Void notAccessible() {
      return null;
    }
  }

  @Produces(MediaType.APPLICATION_JSON)
  @Path("/public/")
  public static class PublicResource {

    @GET
    @Path("/caught")
    public Void caught() {
      throw new RuntimeException("cyrus");
    }

    @GET
    @Path("/hello")
    public String hello() {
      return "hello";
    }
  }

}
