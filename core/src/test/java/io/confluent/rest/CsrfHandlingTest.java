/**
 * Copyright 2014 Confluent Inc.
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.confluent.rest.filters.CsrfTokenProtectionFilter;
import java.util.Properties;
import javax.ws.rs.GET;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.Response;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class CsrfHandlingTest {

  TestRestConfig config;
  CsrfApplication app;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.setProperty(RestConfig.CSRF_PREVENTION_ENABLED, "true");

    config = new TestRestConfig(props);
    app = new CsrfApplication(config);
    app.start();
  }

  @After
  public void tearDown() throws Exception {
    app.stop();
    app.join();
  }

  @Test
  public void testRequestWithValidToken() {
    String requestedBy = "user-session-1";
    String token = getToken(requestedBy);

    Response response =
        ClientBuilder.newClient(app.resourceConfig.getConfiguration())
            .target("http://localhost:" + config.getInt(RestConfig.PORT_CONFIG))
            .path("ping")
            .request()
            .header(CsrfTokenProtectionFilter.Headers.REQUESTED_BY, requestedBy)
            .header(CsrfTokenProtectionFilter.Headers.REQUESTED_WITH, token)
            .method(HttpMethod.POST);

    String content = response.readEntity(String.class);
    assertEquals(200, response.getStatus());
    assertEquals("pong", content);
  }

  @Test
  public void testRequestMissingCsrfTokenException() {
    String requestedBy = "user-session-1";

    Response response =
        ClientBuilder.newClient(app.resourceConfig.getConfiguration())
            .target("http://localhost:" + config.getInt(RestConfig.PORT_CONFIG))
            .path("ping")
            .request()
            .header(CsrfTokenProtectionFilter.Headers.REQUESTED_BY, requestedBy)
            .method(HttpMethod.POST);

    assertEquals(500, response.getStatus());
    assertTrue(
        response
            .readEntity(String.class)
            .contains(CsrfTokenProtectionFilter.MISSING_TOKEN_MESSAGE));
  }

  @Test
  public void testRequestMissingRequesterException() {
    String requestedBy = "user-session-1";
    String token = getToken(requestedBy);

    Response response =
        ClientBuilder.newClient(app.resourceConfig.getConfiguration())
            .target("http://localhost:" + config.getInt(RestConfig.PORT_CONFIG))
            .path("ping")
            .request()
            .header(CsrfTokenProtectionFilter.Headers.REQUESTED_WITH, token)
            .method(HttpMethod.POST);

    assertEquals(500, response.getStatus());
    assertTrue(
        response
            .readEntity(String.class)
            .contains(CsrfTokenProtectionFilter.MISSING_REQUESTER_MESSAGE));
  }

  @Test
  public void testRequestInvalidCsrfTokenException() {
    String requestedBy = "user-session-1";

    Response response =
        ClientBuilder.newClient(app.resourceConfig.getConfiguration())
            .target("http://localhost:" + config.getInt(RestConfig.PORT_CONFIG))
            .path("ping")
            .request()
            .header(CsrfTokenProtectionFilter.Headers.REQUESTED_BY, requestedBy)
            .header(CsrfTokenProtectionFilter.Headers.REQUESTED_WITH, "invalid token")
            .method(HttpMethod.POST);

    assertEquals(500, response.getStatus());
    assertTrue(
        response
            .readEntity(String.class)
            .contains(CsrfTokenProtectionFilter.INVALID_TOKEN_MESSAGE));
  }

  @Test
  public void testGetByPassesCheck() {
    Response response =
        ClientBuilder.newClient(app.resourceConfig.getConfiguration())
            .target("http://localhost:" + config.getInt(RestConfig.PORT_CONFIG))
            .path("ping")
            .request()
            .get();

    String content = response.readEntity(String.class);
    assertEquals(200, response.getStatus());
    assertEquals("pong", content);
  }

  @Test
  public void testCsrfTokenFetchRequest() {
    Response response =
        ClientBuilder.newClient(app.resourceConfig.getConfiguration())
            .target("http://localhost:" + config.getInt(RestConfig.PORT_CONFIG))
            .path(RestConfig.CSRF_PREVENTION_TOKEN_FETCH_ENDPOINT_DEFAULT)
            .request()
            .header(CsrfTokenProtectionFilter.Headers.REQUESTED_BY, "test-session")
            .get();

    String header = response.getHeaderString(CsrfTokenProtectionFilter.Headers.CSRF_TOKEN);
    assertEquals(200, response.getStatus());
    assertNotNull(header);
  }

  @Test
  public void testCsrfTokenFetchMissingRequester() {
    Response response =
        ClientBuilder.newClient(app.resourceConfig.getConfiguration())
            .target("http://localhost:" + config.getInt(RestConfig.PORT_CONFIG))
            .path(RestConfig.CSRF_PREVENTION_TOKEN_FETCH_ENDPOINT_DEFAULT)
            .request()
            .get();

    String header = response.getHeaderString(CsrfTokenProtectionFilter.Headers.CSRF_TOKEN);
    assertEquals(500, response.getStatus());
    assertNull(header);
    assertTrue(
        response
            .readEntity(String.class)
            .contains(CsrfTokenProtectionFilter.MISSING_REQUESTER_MESSAGE));
  }

  private String getToken(String requestedBy) {
    Response response =
        ClientBuilder.newClient(app.resourceConfig.getConfiguration())
            .target("http://localhost:" + config.getInt(RestConfig.PORT_CONFIG))
            .path(RestConfig.CSRF_PREVENTION_TOKEN_FETCH_ENDPOINT_DEFAULT)
            .request()
            .header(CsrfTokenProtectionFilter.Headers.REQUESTED_BY, requestedBy)
            .get();

    return response.getHeaderString(CsrfTokenProtectionFilter.Headers.CSRF_TOKEN);
  }

  // Test app just has endpoints that trigger different types of exceptions.
  private static class CsrfApplication extends Application<TestRestConfig> {

    Configurable resourceConfig;

    CsrfApplication(TestRestConfig props) {
      super(props);
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      resourceConfig = config;
      config.register(TestResource.class);
    }
  }

  @Path("/")
  public static class TestResource {

    @GET
    @Path("/ping")
    public String getPing() {
      return "pong";
    }

    @POST
    @Path("/ping")
    public String postPing() {
      return "pong";
    }
  }
}
