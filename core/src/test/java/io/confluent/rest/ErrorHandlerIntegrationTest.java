package io.confluent.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.eclipse.jetty.security.AbstractLoginService;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.security.Constraint;
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
  public void unhandledServerExceptionDisplaysStackTraceForInvalidAuthentication()
      throws Exception {

    Properties props = new Properties();
    props.setProperty(RestConfig.SUPPRESS_STACK_TRACE_IN_RESPONSE, "false");
    TestRestConfig config = new TestRestConfig(props);

    TestApplication application = new TestApplication(config);
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
  public void handledServerExceptionDoesNotDisplayStackTraceForInvalidAuthentication()
      throws Exception {
    TestApplication application = new TestApplication(new TestRestConfig());
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

  private static class TestApplication extends Application<TestRestConfig> {

    TestApplication(TestRestConfig restConfig) {
      super(restConfig);
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      config.register(TestResource.class);
    }

    @Override
    protected void configureSecurityHandler(ServletContextHandler context) {
      final ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
      Constraint constraint = new Constraint();
      constraint.setAuthenticate(true);
      String[] roles = {"**"};
      constraint.setRoles(roles);
      ConstraintMapping mapping = new ConstraintMapping();
      mapping.setConstraint(constraint);
      mapping.setMethod("*");
      mapping.setPathSpec("/*");

      securityHandler.addConstraintMapping(mapping);
      securityHandler.setAuthenticator(new DummyAuthenticator());
      securityHandler.setLoginService(new DummyLoginService());

      context.setSecurityHandler(securityHandler);
    }
  }

  private static class DummyAuthenticator extends BasicAuthenticator {

    @Override
    public Authentication validateRequest(ServletRequest req, ServletResponse res,
        boolean mandatory) throws ServerAuthException {
      throw new RuntimeException(DUMMY_EXCEPTION);
    }
  }

  private static class DummyLoginService extends AbstractLoginService {

    @Override
    protected String[] loadRoleInfo(final UserPrincipal user) {
      return new String[0];
    }

    @Override
    protected UserPrincipal loadUserInfo(final String username) {
      return null;
    }
  }

  @Path("/test")
  public static class TestResource {

    @GET
    @Path("/path")
    public String path() {
      return "Ok";
    }
  }
}
