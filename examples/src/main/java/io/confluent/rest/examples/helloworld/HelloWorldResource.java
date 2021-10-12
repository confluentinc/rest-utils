/*
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

package io.confluent.rest.examples.helloworld;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.hibernate.validator.constraints.NotEmpty;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;

import io.confluent.rest.annotations.PerformanceMetric;

@Path("/hello")
@Produces("application/vnd.hello.v1+json")
public class HelloWorldResource {
  HelloWorldRestConfig config;

  public HelloWorldResource(HelloWorldRestConfig config) {
    this.config = config;
  }


  /**
   * A simple response entity with validation constraints.
   */
  public static class HelloResponse {
    @NotEmpty
    private String message;

    public HelloResponse() {
      /* Jackson deserialization */
    }

    public HelloResponse(String message) {
      this.message = message;
    }

    @JsonProperty
    public String getMessage() {
      return message;
    }
  }

  @GET
  @PerformanceMetric("hello-with-name")
  public HelloResponse hello(@QueryParam("name") String name) {
    // Use a configuration setting to control the message that's written. The name is extracted from
    // the query parameter "name", or defaults to "World". You can test this API with curl:
    // curl http://localhost:8080/hello
    //   -> {"message":"Hello, World!"}
    // curl http://localhost:8080/hello?name=Bob
    //   -> {"message":"Hello, Bob!"}
    return new HelloResponse(
        String.format(config.getString(HelloWorldRestConfig.GREETING_CONFIG),
                      (name == null ? "World" : name)));
  }
}
