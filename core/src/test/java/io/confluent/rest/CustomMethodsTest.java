/*
 * Copyright 2018 Confluent Inc.
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

import java.util.Properties;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Purpose : To test rest-utils / java.ws.rs code's ability to support
 *  "custom methods" that use a colon.
 * I.e. can we have two endpoints like
 *  - /foo/v2/collection/{id}
 *  - /foo/v2/collection/{id}:myverb
 */
public class CustomMethodsTest {
  private TestRestConfig config;
  private Server server;

  @BeforeEach
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.setProperty("listeners", "http://localhost:0");
    config = new TestRestConfig(props);
    CustomMethodsApplication application = new CustomMethodsApplication(config);
    server = application.createServer();
    server.start();
  }

  @AfterEach
  public void tearDown() throws Exception {
    server.stop();
    server.join();
  }
  private static class CustomMethodsApplication extends Application<TestRestConfig> {

    CustomMethodsApplication(TestRestConfig props) {
      super(props);
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      config.register(CustomMethodsResource.class);
    }
  }

  @Produces(MediaType.APPLICATION_OCTET_STREAM)
  @Path("/foo")
  public static class CustomMethodsResource {

    @GET
    @Path("/v2/collection/{id}")
    public String baseEndpoint(@PathParam("id") final String id) {
      return "base " + id;
    }

    @GET
    @Path("/v2/collection/{id}:search")
    public String customMethodEndpoint(@PathParam("id") final String id) {
      return "custom " + id;
    }
  }

  @Test
  public void testBaseCall() {
    Response response = ClientBuilder.newClient()
        .target(server.getURI())
        .path("/foo/v2/collection/pants")
        .request().get();
    assertEquals(200, response.getStatus());
    String responseBody = response.readEntity(String.class);
    assertEquals("base pants", responseBody);
  }

  @Test
  public void testIncorrectBaseCall() {
    // namely that you if you have an id of `pants:search` then it will
    // find the custom-method
    Response response = ClientBuilder.newClient()
            .target(server.getURI())
            .path("/foo/v2/collection/pants:search")
            .request().get();
    assertEquals(200, response.getStatus());
    String responseBody = response.readEntity(String.class);
    assertEquals("custom pants", responseBody);
  }


  @Test
  public void testCustomMethodCall() {

    String[] tests = new String[]{
            "pants",
            "shoes",
            "pants-shoes", // test hyphen
            "pants:shoes", // test colon
            "pants:search", // test colon + string that matches custom method
            "pants:search:search", // why not
            ":::::search",  // why not
    };

    for(String test: tests) {
      Response response = ClientBuilder.newClient()
              .target(server.getURI())
              .path("/foo/v2/collection/" + test + ":search")
              .request().get();
      assertEquals(200, response.getStatus());
      String responseBody = response.readEntity(String.class);
      String expected = "custom " + test;

      System.out.println("Expected='" + expected + "', actual='" + responseBody + "'");
      assertEquals(expected, responseBody);
    }
  }

  @Test
  public void testCustomMethodCallWithUrlEncodedId() {

    String[][] tests = new String[][]{
            { "pants%3Ashoes",  "custom pants:shoes"  }, // url encoded colon
            { "pants%3Asearch", "custom pants:search" },  // url encoded colon with custom method
    };

    for(String[] testCase: tests) {
      String test = testCase[0];
      String expected = testCase[1];

      Response response = ClientBuilder.newClient()
              .target(server.getURI())
              .path("/foo/v2/collection/" + test + ":search")
              .request().get();
      assertEquals(200, response.getStatus());
      String responseBody = response.readEntity(String.class);

      System.out.println("Expected='" + expected + "', actual='" + responseBody + "'");
      assertEquals(expected, responseBody);
    }
  }
}
