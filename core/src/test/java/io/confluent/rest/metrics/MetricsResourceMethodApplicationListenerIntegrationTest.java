package io.confluent.rest.metrics;

import jakarta.servlet.ServletException;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.glassfish.jersey.server.ServerProperties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Properties;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Configurable;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.confluent.rest.Application;
import io.confluent.rest.RestConfig;
import io.confluent.rest.TestRestConfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MetricsResourceMethodApplicationListenerIntegrationTest {

  TestRestConfig config;
  ApplicationWithFilter app;
  volatile Throwable handledException = null;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.setProperty("debug", "false");
    config = new TestRestConfig(props);
    app = new ApplicationWithFilter(config);
    app.start();
  }

  @After
  public void tearDown() throws Exception {
    app.stop();
    app.join();
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
        .target("http://localhost:" + config.getInt(RestConfig.PORT_CONFIG))
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

  private class ApplicationWithFilter extends Application<TestRestConfig> {

    Configurable resourceConfig;

    ApplicationWithFilter(TestRestConfig props) {
      super(props);
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      resourceConfig = config;
      config.register(PrivateResource.class);
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
}
