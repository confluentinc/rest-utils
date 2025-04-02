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

import static jakarta.ws.rs.core.Response.Status.FORBIDDEN;
import static jakarta.ws.rs.core.Response.Status.OK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Configurable;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import org.apache.http.Header;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.asynchttpclient.Dsl;
import org.asynchttpclient.ws.WebSocket;
import org.asynchttpclient.ws.WebSocketListener;
import org.asynchttpclient.ws.WebSocketUpgradeHandler;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.security.AbstractLoginService;
import org.eclipse.jetty.ee10.servlet.security.ConstraintMapping;
import org.eclipse.jetty.ee10.servlet.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.RolePrincipal;
import org.eclipse.jetty.security.UserPrincipal;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.util.security.Password;
import jakarta.websocket.server.ServerContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CustomInitTest {

  private static final Logger log = LoggerFactory.getLogger(CustomInitTest.class);
  private static final String NEHAS_BASIC_AUTH = "bmVoYTpha2Zhaw==";
  private static final String JUNS_BASIC_AUTH = "anVuOmthZmthLQ==";

  private Server server;
  private CloseableHttpClient httpclient;

  @BeforeEach
  public void setUp() throws Exception {
    httpclient = HttpClients.createDefault();

    final Properties props = new Properties();
    props.put(RestConfig.LISTENERS_CONFIG, "http://localhost:0");
    props.put(RestConfig.METRICS_REPORTER_CLASSES_CONFIG, "io.confluent.rest.TestMetricsReporter");
    props.put(RestConfig.AUTHENTICATION_ROLES_CONFIG, "SomeRequiredRole");
    props.put(RestConfig.REST_SERVLET_INITIALIZERS_CLASSES_CONFIG,
        Collections.singletonList(CustomRestInitializer.class.getName()));
    props.put(RestConfig.WEBSOCKET_SERVLET_INITIALIZERS_CLASSES_CONFIG,
        Collections.singletonList(CustomWsInitializer.class.getName()));

    CustomInitTestApplication application =
        new CustomInitTestApplication(new TestRestConfig(props));
    server = application.createServer();
    server.start();
  }

  @AfterEach
  public void cleanup() throws Exception {
    httpclient.close();
    server.stop();
  }

  @Test
  public void shouldBeAbleToInstallSecurityHandler() throws Exception {
    try (CloseableHttpResponse response = makeRestGetRequest(NEHAS_BASIC_AUTH)) {
      assertEquals(OK.getStatusCode(), response.getStatusLine().getStatusCode());
    }

    try (CloseableHttpResponse response = makeRestGetRequest(JUNS_BASIC_AUTH)) {
      assertEquals(FORBIDDEN.getStatusCode(), response.getStatusLine().getStatusCode());
    }
  }

  @Test
  public void shouldBeAbleToAddWebSocketEndPoint() throws Exception {
    makeWsGetRequest();
  }

  @Test
  public void shouldBeAbleToConfigureJettyFilter() throws Exception {
    try (CloseableHttpResponse response = makeRestGetRequest(NEHAS_BASIC_AUTH)) {
      Header header = response.getFirstHeader("this-filter");
      assertEquals("this-filter", header.getName());
      assertEquals("was-triggered", header.getValue());
      assertEquals(OK.getStatusCode(), response.getStatusLine().getStatusCode());
    }
  }

  private CloseableHttpResponse makeRestGetRequest(String basicAuth) throws Exception {
    log.debug("Making GET " + server.getURI() + "/test");
    HttpGet httpget = new HttpGet(server.getURI() + "/test");
    if (basicAuth != null) {
      httpget.setHeader(HttpHeaders.AUTHORIZATION, "Basic " + basicAuth);
    }

    CloseableHttpResponse response = null;
    response = httpclient.execute(httpget);
    return response;
  }

  private void makeWsGetRequest() throws Exception {
    String uri = server.getURI().toString().replace("http", "ws") + "ws";
    log.debug("Making WebSocket GET " + uri + "test");
    final AtomicReference<Throwable> error = new AtomicReference<>();
    WebSocketUpgradeHandler wsHandler = new WebSocketUpgradeHandler.Builder()
        .addWebSocketListener(new WebSocketListener() {
          public void onOpen(WebSocket websocket) {
            // WebSocket connection opened
          }
          public void onClose(WebSocket websocket, int code, String reason) {
            // WebSocket connection closed
          }
          public void onError(Throwable t) {
            log.info("Websocket failed", t);
            error.set(t);
          }
        }).build();

    WebSocket ws = Dsl.asyncHttpClient()
        .prepareGet(uri + "/test")
        .setRequestTimeout(10000)
        .execute(wsHandler)
        .get();

    if (error.get() != null) {
      throw new RuntimeException("Error connecting websocket", error.get());
    }

    ws.sendCloseFrame();
  }

  private static class CustomInitTestApplication extends Application<TestRestConfig> {
    private CustomInitTestApplication(TestRestConfig props) {
      super(props);
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      config.register(new CustomRestResource());
    }
  }

  public static class CustomRestInitializer
      implements Consumer<ServletContextHandler>, org.apache.kafka.common.Configurable {

    private RestConfig config;

    @Override
    public void configure(final Map<String, ?> config) {
      this.config = new RestConfig(RestConfig.baseConfigDef(), config);
    }

    @Override
    public void accept(final ServletContextHandler context) {
      final List<String> roles = config.getList(RestConfig.AUTHENTICATION_ROLES_CONFIG);
      final Constraint.Builder constraint = new Constraint.Builder();
      constraint.roles(roles.toArray(new String[0]));

      final ConstraintMapping constraintMapping = new ConstraintMapping();
      constraintMapping.setConstraint(constraint.build());
      constraintMapping.setMethod("*");
      constraintMapping.setPathSpec("/*");

      final ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
      securityHandler.addConstraintMapping(constraintMapping);
      securityHandler.setAuthenticator(new BasicAuthenticator());
      securityHandler.setLoginService(new TestLoginService());
      securityHandler.setRealmName("TestRealm");

     context.setSecurityHandler(securityHandler);
     context.addFilter(new FilterHolder(new CustomJettyFilter()), "/*", null);
    }
  }

  private static class TestLoginService extends AbstractLoginService {
    @Override
    protected List<RolePrincipal> loadRoleInfo(final UserPrincipal user) {
      if (user.getName().equals("jun")) {
        return List.of(new RolePrincipal("some-role"));
      }
      if (user.getName().equals("neha")) {
        return List.of(new RolePrincipal("SomeRequiredRole"), new RolePrincipal("another"));
      }
      return List.of();
    }

    @Override
    protected UserPrincipal loadUserInfo(final String username) {
      if (username.equals("jun")) {
        return new UserPrincipal(username, new Password("kafka-"));
      }
      if (username.equals("neha")) {
        return new UserPrincipal(username, new Password("akfak"));
      }
      return null;
    }
  }

  public static class CustomWsInitializer implements Consumer<ServletContextHandler> {
    @Override
    public void accept(final ServletContextHandler context) {
      try {
        JakartaWebSocketServletContainerInitializer.configure(context, (servletContext, serverContainer) -> {
          serverContainer.addEndpoint(ServerEndpointConfig.Builder
                  .create(
                          WSEndpoint.class,
                          WSEndpoint.class.getAnnotation(ServerEndpoint.class).value()
                  ).build());
        });
      } catch (Exception e) {
        fail("Invalid test");
      }
    }
  }

  public static class CustomJettyFilter implements Filter {

    @Override
    public void init(FilterConfig filterConfig)  {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        jakarta.servlet.http.HttpServletResponse httpResponse = (jakarta.servlet.http.HttpServletResponse) response;
      httpResponse.addHeader("this-filter", "was-triggered");

      chain.doFilter(request, httpResponse);
    }

    @Override
    public void destroy() {
    }
  }

  @Path("/test")
  @Produces(MediaType.TEXT_PLAIN)
  public static class CustomRestResource {
    @GET
    @Path("/")
    public String hello() {
      return "Hello";
    }
  }

  @ServerEndpoint(value = "/test")
  public static class WSEndpoint {
    @OnOpen
    public void onOpen(final Session session, final EndpointConfig endpointConfig) {
      session.getAsyncRemote().sendText("Test message",
          result -> {
            if (!result.isOK()) {
              log.warn(
                  "Error sending websocket message for session {}",
                  session.getId(),
                  result.getException()
              );
            }
          });
    }
  }
}
