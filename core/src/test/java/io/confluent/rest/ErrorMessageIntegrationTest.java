/*
 * Copyright 2014 - 2023 Confluent Inc.
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

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParseException;
import io.confluent.rest.entities.ErrorMessage;
import io.confluent.rest.exceptions.JsonParseExceptionMapper;
import java.util.Properties;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

@Tag("IntegrationTest")
public class ErrorMessageIntegrationTest {

  private Server server;
  private Client client;

  @BeforeEach
  public void setUp(TestInfo testInfo) throws Exception {
    Properties props = new Properties();
    TestRestConfig config = new TestRestConfig(props);
    TestApplication app = new TestApplication(config,
        testInfo.getDisplayName().contains("WithJsonMediaType"));
    app.createServer();
    server = app.createServer();
    server.start();
    client = ClientBuilder.newClient(app.resourceConfig.getConfiguration());
  }

  @AfterEach
  public void tearDown() throws Exception {
    server.stop();
    server.join();
    client.close();
  }

  /**
   * This test is purely a demonstration that if we don't explicitly set MediaType, we will get
   * 500 response on option request
   */
  @Test
  @DisplayName("testOPTIONS_WithoutMediaType_return500")
  public void testOPTIONS_WithoutMediaType_return500() {
    Response response = client.target(server.getURI())
        .path("unauthorized/path")
        .request("*/*")
        .options();

    assertEquals(Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
  }

  @Test
  public void testGET_WithoutMediaType_return401() {
    Response response = client.target(server.getURI())
        .path("unauthorized/path")
        .request("*/*")
        .get();

    assertEquals(Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
    assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
  }

  @Test
  @DisplayName("testOPTIONS_WithJsonMediaType_return401")
  public void testOPTIONS_WithJsonMediaType_return401() {
    Response response = client.target(server.getURI())
        .path("unauthorized/path")
        .request("*/*")
        .options();

    assertEquals(Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
    assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
  }

  @Test
  public void testJsonParseExceptionMapper_return400() {
    Response response = client.target(server.getURI())
        .path("pass/json")
        .request("*/*")
        .get();

    assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
  }

  private static class UnauthorizedFilter implements ContainerRequestFilter {

    private final boolean withJsonMediaType;

    UnauthorizedFilter(boolean withJsonMediaType) {
      this.withJsonMediaType = withJsonMediaType;
    }

    @Override
    public void filter(ContainerRequestContext context) {
      if (context.getUriInfo().getPath().startsWith("unauthorized/")) {
        context.abortWith(
            errorResponseWithEntity(Status.UNAUTHORIZED.getStatusCode(), "Unauthorized")
        );
      }
    }

    private Response errorResponseWithEntity(int statusCode, String message) {
      ErrorMessage errorMessage = new ErrorMessage(statusCode, message);
      Response.ResponseBuilder builder = Response.status(statusCode)
          .entity(errorMessage);
      if (withJsonMediaType) {
        builder.type(MediaType.APPLICATION_JSON_TYPE);
      }
      return builder.build();
    }
  }

  private static class TestApplication extends Application<TestRestConfig> {

    Configurable<?> resourceConfig;
    private boolean withJsonMediaType = false;

    TestApplication(TestRestConfig restConfig, boolean withJsonMediaType) {
      super(restConfig);
      this.withJsonMediaType = withJsonMediaType;
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      resourceConfig = config;
      config.register(TestResource.class);
      config.register(JsonResource.class);
      config.register(new UnauthorizedFilter(withJsonMediaType));
      config.register(JsonParseExceptionMapper.class);
    }
  }

  @Produces(MediaType.APPLICATION_JSON)
  @Path("/unauthorized")
  public static class TestResource {

    @GET
    @Path("/path")
    public String path() {
      return "Ok";
    }
  }

  @Produces(MediaType.APPLICATION_JSON)
  @Path("/pass")
  public static class JsonResource {

    @GET
    @Path("/json")
    public String path() throws JsonParseException {
      throw new JsonParseException("Json parse error", JsonLocation.NA);
    }
  }
}
