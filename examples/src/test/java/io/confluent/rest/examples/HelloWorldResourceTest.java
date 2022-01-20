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
package io.confluent.rest.examples;

import org.junit.jupiter.api.Test;

import javax.ws.rs.core.Response;

import io.confluent.rest.RestConfigException;
import io.confluent.rest.EmbeddedServerTestHarness;
import io.confluent.rest.examples.helloworld.HelloWorldApplication;
import io.confluent.rest.examples.helloworld.HelloWorldRestConfig;
import io.confluent.rest.examples.helloworld.HelloWorldResource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This tests a single resource by setting up a Jersey-based test server embedded in the application.
 * This is intended to unit test a single resource class, not the entire collection of resources.
 * EmbeddedServerTestHarness does most of the heavy lifting so you only need to issue requests and
 * check responses.
 */
public class HelloWorldResourceTest extends EmbeddedServerTestHarness<HelloWorldRestConfig, HelloWorldApplication> {
  private final static String mediatype = "application/vnd.hello.v1+json";

  public HelloWorldResourceTest() throws RestConfigException {
    // We need to specify which resources we want available, i.e. the ones we need to test. If we need
    // access to the server Configuration, as HelloWorldResource does, a default config is available
    // in the 'config' field.
    addResource(new HelloWorldResource(config));
  }

  @Test
  public void testHello() {
    String acceptHeader = mediatype;
    Response response = request("/hello", acceptHeader).get();
    // The response should indicate success and have the expected content type
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    assertEquals(mediatype, response.getMediaType().toString());

    // We should also be able to parse it as the expected output format
    final HelloWorldResource.HelloResponse message = response.readEntity(HelloWorldResource.HelloResponse.class);
    // And it should contain the expected message
    assertEquals("Hello, World!", message.getMessage());
  }
}
