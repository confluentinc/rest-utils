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

import static javax.ws.rs.core.Response.Status.FORBIDDEN;
import static javax.ws.rs.core.Response.Status.OK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.websocket.EndpointConfig;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.asynchttpclient.Dsl;
import org.asynchttpclient.ws.WebSocket;
import org.asynchttpclient.ws.WebSocketListener;
import org.asynchttpclient.ws.WebSocketUpgradeHandler;
import org.eclipse.jetty.security.AbstractLoginService;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Password;
import org.eclipse.jetty.websocket.jsr356.server.ServerContainer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CustomInitTest {

  private static final Logger log = LoggerFactory.getLogger(CustomInitTest.class);
  private static final String HTTP_URI = "http://localhost:8080";
  private static final String WS_URI = "ws://localhost:8080/ws";
  private static final String NEHAS_BASIC_AUTH = "bmVoYTpha2Zhaw==";
  private static final String JUNS_BASIC_AUTH = "anVuOmthZmthLQ==";

  private CustomInitTestApplication app;
  private CloseableHttpClient httpclient;

  @Before
  public void setUp() throws Exception {
    httpclient = HttpClients.createDefault();

    final Properties props = new Properties();
    props.put(RestConfig.LISTENERS_CONFIG, HTTP_URI);
    props.put(RestConfig.METRICS_REPORTER_CLASSES_CONFIG, "io.confluent.rest.TestMetricsReporter");
    props.put(RestConfig.AUTHENTICATION_ROLES_CONFIG, "SomeRequiredRole");
    props.put(RestConfig.REST_SERVLET_INITIALIZERS_CLASSES_CONFIG,
        Collections.singletonList(CustomRestInitializer.class.getName()));
    props.put(RestConfig.WEBSOCKET_SERVLET_INITIALIZERS_CLASSES_CONFIG,
        Collections.singletonList(CustomWsInitializer.class.getName()));

    app = new CustomInitTestApplication(new TestRestConfig(props));
    app.start();
  }

  @After
  public void cleanup() throws Exception {
    httpclient.close();
    app.stop();
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

  private CloseableHttpResponse makeRestGetRequest(String basicAuth) throws Exception {
    log.debug("Making GET " + HTTP_URI + "/test");
    HttpGet httpget = new HttpGet(HTTP_URI + "/test");
    if (basicAuth != null) {
      httpget.setHeader(HttpHeaders.AUTHORIZATION, "Basic " + basicAuth);
    }

    CloseableHttpResponse response = null;
    response = httpclient.execute(httpget);
    return response;
  }

  private void makeWsGetRequest() throws Exception {
    log.debug("Making WebSocket GET " + WS_URI + "/test");
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
        .prepareGet(WS_URI + "/test")
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
      implements Consumer<ServletContextHandler>, io.confluent.common.Configurable {

    private RestConfig config;

    @Override
    public void configure(final Map<String, ?> config) {
      this.config = new RestConfig(RestConfig.baseConfigDef(), config);
    }

    @Override
    public void accept(final ServletContextHandler context) {
      final List<String> roles = config.getList(RestConfig.AUTHENTICATION_ROLES_CONFIG);
      final Constraint constraint = new Constraint();
      constraint.setAuthenticate(true);
      constraint.setRoles(roles.toArray(new String[0]));

      final ConstraintMapping constraintMapping = new ConstraintMapping();
      constraintMapping.setConstraint(constraint);
      constraintMapping.setMethod("*");
      constraintMapping.setPathSpec("/*");

      final ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
      securityHandler.addConstraintMapping(constraintMapping);
      securityHandler.setAuthenticator(new BasicAuthenticator());
      securityHandler.setLoginService(new TestLoginService());
      securityHandler.setRealmName("TestRealm");

     context.setSecurityHandler(securityHandler);
    }
  }

  private static class TestLoginService extends AbstractLoginService {
    @Override
    protected String[] loadRoleInfo(final UserPrincipal user) {
      if (user.getName().equals("jun")) {
        return new String[] {"some-role"};
      }
      if (user.getName().equals("neha")) {
        return new String[] {"SomeRequiredRole","another"};
      }
      return new String[0];
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
        ServerContainer container = context.getBean(ServerContainer.class);

        container.addEndpoint(ServerEndpointConfig.Builder
            .create(
                WSEndpoint.class,
                WSEndpoint.class.getAnnotation(ServerEndpoint.class).value()
            ).build());
      } catch (Exception e) {
        fail("Invalid test");
      }
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
