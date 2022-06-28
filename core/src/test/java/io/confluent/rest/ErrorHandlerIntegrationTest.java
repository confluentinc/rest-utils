package io.confluent.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.confluent.rest.errorhandlers.NoJettyDefaultStackTraceErrorHandler;
import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class ErrorHandlerIntegrationTest {

  private static final String DUMMY_EXCEPTION = "dummy exception";
  private Server server;

  @AfterEach
  public void tearDown() throws Exception {
    server.stop();
    server.join();
  }

  @Test
  public void unhandledServerExceptionDisplaysStackTrace() throws Exception {
    TestApplication application = new TestApplication(new RestConfig(RestConfig.baseConfigDef()),
        false);
    server = application.createServer();
    server.start();

    Response response = ClientBuilder.newClient()
        .target(server.getURI())
        .path("/test/path")
        .request(MediaType.TEXT_HTML)
        .get();

    String responseValue = response.readEntity(String.class);

    assertEquals(500, response.getStatus());
    assertTrue(responseValue.toLowerCase().contains(DUMMY_EXCEPTION));
    assertTrue(responseValue.toLowerCase().contains("caused by"));
  }

  @Test
  public void handledServerExceptionDoesNotDisplayStackTrace() throws Exception {
    TestApplication application = new TestApplication(new RestConfig(RestConfig.baseConfigDef()),
        true);
    server = application.createServer();
    server.start();

    Response response = ClientBuilder.newClient()
        .target(server.getURI())
        .path("/test/path")
        .request(MediaType.TEXT_HTML)
        .get();

    String responseValue = response.readEntity(String.class).toLowerCase();

    assertEquals(500, response.getStatus());
    assertFalse(responseValue.contains(DUMMY_EXCEPTION));
    assertFalse(responseValue.contains("caused by"));
    assertTrue(responseValue.contains("server error"));
  }

  private static class TestApplication extends Application<RestConfig> {

    private final boolean handleError;

    TestApplication(RestConfig restConfig, boolean handleError) {
      super(restConfig);
      this.handleError = handleError;
    }

    @Override
    protected void configurePreResourceHandling(ServletContextHandler contextHandler) {
      contextHandler.setHandler(new DummyServerHandler());
      if (handleError) {
        contextHandler.setErrorHandler(new NoJettyDefaultStackTraceErrorHandler());
      }
    }

    @Override
    public void setupResources(Configurable<?> config, RestConfig appConfig) {
      config.register(TestResource.class);
    }
  }

  private static class DummyServerHandler extends ServletHandler {

    public void doHandle(String target, Request baseRequest, HttpServletRequest request,
        HttpServletResponse response) throws IOException, ServletException {
      throw new RuntimeException(DUMMY_EXCEPTION);
    }
  }

  @Path("/test")
  public static class TestResource {

    @GET
    @Path("/path")
    public String path() {
      return "ok";
    }
  }
}
