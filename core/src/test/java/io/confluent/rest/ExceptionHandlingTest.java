/**
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
 **/

package io.confluent.rest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Properties;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.Response;

import io.confluent.rest.entities.ErrorMessage;
import io.confluent.rest.exceptions.RestNotFoundException;

import static org.junit.Assert.assertEquals;

/**
 * Tests that a demo app catches exceptions correctly and returns errors in the expected format.
 */
public class ExceptionHandlingTest {

  TestRestConfig config;
  ExceptionApplication app;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.setProperty("debug", "false");
    config = new TestRestConfig(props);
    app = new ExceptionApplication(config);
    app.start();
  }

  @After
  public void tearDown() throws Exception {
    app.stop();
    app.join();
  }

  private void testAppException(String path, int expectedStatus, int expectedErrorCode,
                                String expectedMessage) {
    Response response = ClientBuilder.newClient()
        .target("http://localhost:" + config.getInt(RestConfig.PORT_CONFIG))
        .path(path)
        .request()
        .get();
    assertEquals(expectedStatus, response.getStatus());

    ErrorMessage msg = response.readEntity(ErrorMessage.class);
    assertEquals(expectedErrorCode, msg.getErrorCode());
    assertEquals(expectedMessage, msg.getMessage());
  }

  @Test
  public void testRestException() {
    testAppException("/restnotfound", 404, 4040, "Rest Not Found");
  }

  @Test
  public void testNonRestException() {
    // These just duplicate the HTTP status code but should carry the custom message through
    testAppException("/notfound", 404, 404, "Generic Not Found");
  }

  @Test
  public void testUnexpectedException() {
    // Under non-debug mode, this uses a completely generic message since unexpected errors
    // is the one case we want to be certain we don't leak extra info
    testAppException("/unexpected", 500, 500,
                     Response.Status.INTERNAL_SERVER_ERROR.getReasonPhrase());
  }

  // Test app just has endpoints that trigger different types of exceptions.
  private static class ExceptionApplication extends Application<TestRestConfig> {

    ExceptionApplication(TestRestConfig props) {
      super(props);
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
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
      throw new RuntimeException("Internal server error.");
    }
  }

}
