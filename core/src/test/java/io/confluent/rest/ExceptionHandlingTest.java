/*
 * Copyright 2015 Confluent Inc.
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

import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.Response;

import io.confluent.rest.exceptions.RestTimeoutException;
import org.eclipse.jetty.server.Server;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.confluent.rest.entities.ErrorMessage;
import io.confluent.rest.exceptions.RestNotFoundException;
import io.confluent.rest.exceptions.RestServerErrorException;

/**
 * Tests that a demo app catches exceptions correctly and returns errors in the expected format.
 */
public class ExceptionHandlingTest {

  private TestRestConfig config;
  private Server server;
  private ExceptionApplication application;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.setProperty("debug", "false");
    props.setProperty("listeners", "http://localhost:0");
    config = new TestRestConfig(props);
    application = new ExceptionApplication(config);
    server = application.createServer();
    server.start();
  }

  @After
  public void tearDown() throws Exception {
    server.stop();
    server.join();
  }

  private void testGetException(String path, int expectedStatus, int expectedErrorCode,
      String expectedMessage) {
    Response response = ClientBuilder.newClient(application.resourceConfig.getConfiguration())
        .target(server.getURI())
        .path(path)
        .request()
        .get();
    assertEquals(expectedStatus, response.getStatus());

    ErrorMessage msg = response.readEntity(ErrorMessage.class);
    assertEquals(expectedErrorCode, msg.getErrorCode());
    assertEquals(expectedMessage, msg.getMessage());
  }

  private void testPostException(
      String path,
      Entity entity,
      int expectedStatus,
      int expectedErrorCode,
      String expectedMessage) {
    Response response = ClientBuilder.newClient(application.resourceConfig.getConfiguration())
      .target(server.getURI())
      .path(path)
      .request()
      .post(entity);
    assertEquals(expectedStatus, response.getStatus());

    ErrorMessage msg = response.readEntity(ErrorMessage.class);
    assertEquals(expectedErrorCode, msg.getErrorCode());
    assertEquals(expectedMessage, msg.getMessage());
  }

  @Test
  public void testRestException() {
    testGetException("/restnotfound", 404, 4040, "Rest Not Found");
  }

  @Test
  public void testNonRestException() {
    // These just duplicate the HTTP status code but should carry the custom message through
    testGetException("/notfound", 404, 404, "Generic Not Found");
  }

  @Test
  public void testUnexpectedException() {
    // Under non-debug mode, this uses a completely generic message since unexpected errors
    // is the one case we want to be certain we don't leak extra info
    testGetException("/unexpected", 500, 50001,
                     Response.Status.INTERNAL_SERVER_ERROR.getReasonPhrase());
  }

  @Test
  public void testRestTimeoutException() {
    Map<String, String> m = new HashMap<>();
    m.put("something", "something");
    m.put("something-else", "something-else");
    testPostException("readTimeout",  Entity.json(m), 408, 408, "Idle timeout expired" );
  }

  public static class FakeType {
    public String something;
  }

  @Test
  public void testUnrecognizedField() {
    Map<String, String> m = new HashMap<>();
    m.put("something", "something");
    m.put("something-else", "something-else");
    testPostException(
        "/unrecognizedfield", Entity.json(m), 422, 422, "Unrecognized field: something-else");
  }

  // Test app just has endpoints that trigger different types of exceptions.
  private static class ExceptionApplication extends Application<TestRestConfig> {

    Configurable<?> resourceConfig;

    ExceptionApplication(TestRestConfig props) {
      super(props);
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      resourceConfig = config;
      config.register(ExceptionResource.class);
    }
  }

  @Produces("application/json")
  @Path("/")
  public static class ExceptionResource {

    @GET
    @Path("/restnotfound")
    public String restNotFound() {
      throw new RestNotFoundException("Rest Not Found", 4040);
    }

    @GET
    @Path("/notfound")
    public String notFound() {
      throw new javax.ws.rs.NotFoundException("Generic Not Found");
    }

    @GET
    @Path("/unexpected")
    public String unexpected() {
      throw new RestServerErrorException("Internal Server Error", 50001);
    }

    @POST
    @Path("/unrecognizedfield")
    public String blah(FakeType ft) {
      return ft.something;
    }

    @POST
    @Path("/readTimeout")
    public String restTimeout() {
      throw new RestTimeoutException("Idle timeout expired", 408, 408);
    }
  }

}
