/*
 * Copyright 2021 Confluent Inc.
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

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Configurable;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import org.eclipse.jetty.server.Server;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class DosFilterTest {

  @Test
  public void dosFilterEnabled_throttlesRequests() throws Exception {
    FooApplication application =
        new FooApplication(
            new FooConfig(
                ImmutableMap.of(
                    "listeners", "http://localhost:0",
                    "dos.filter.enabled", "true",
                    "dos.filter.max.requests.per.sec", "1",
                    "dos.filter.delay.ms", "-1")));
    Server server = application.createServer();
    server.start();

    Client client = ClientBuilder.newClient();

    // Request should succeed.
    Response response1 = client.target(server.getURI()).path("/foo").request().get();
    assertEquals(Status.OK.getStatusCode(), response1.getStatus());

    // Following requests should all be throttled.
    for (int i = 0; i < 100; i++) {
      Response response2 = client.target(server.getURI()).path("/foo").request().get();
      assertEquals(Status.TOO_MANY_REQUESTS.getStatusCode(), response2.getStatus());
    }

    Thread.sleep(1000);

    // Request should succeed again.
    Response response3 = client.target(server.getURI()).path("/foo").request().get();
    assertEquals(Status.OK.getStatusCode(), response3.getStatus());

    server.stop();
  }

  @Test
  public void dosFilterDisabled_DoesNotThrottleRequests() throws Exception {
    FooApplication application =
        new FooApplication(
            new FooConfig(
                ImmutableMap.of(
                    "listeners", "http://localhost:0",
                    "dos.filter.enabled", "false",
                    "dos.filter.max.requests.per.sec", "1",
                    "dos.filter.delay.ms", "-1")));
    Server server = application.createServer();
    server.start();

    Client client = ClientBuilder.newClient();

    // Request should succeed.
    Response response1 = client.target(server.getURI()).path("/foo").request().get();
    assertEquals(Status.OK.getStatusCode(), response1.getStatus());

    // Following requests should all also succeed.
    for (int i = 0; i < 100; i++) {
      Response response2 = client.target(server.getURI()).path("/foo").request().get();
      assertEquals(Status.OK.getStatusCode(), response2.getStatus());
    }

    server.stop();
  }

  public static final class FooApplication extends Application<FooConfig> {

    public FooApplication(FooConfig config) {
      super(config);
    }

    @Override
    public void setupResources(Configurable<?> config, FooConfig appConfig) {
      config.register(FooResource.class);
    }
  }

  public static final class FooConfig extends RestConfig {

    public FooConfig(Map<String, String> configs) {
      super(baseConfigDef(), configs);
    }
  }

  @Path("/foo")
  public static final class FooResource {

    @GET
    public String getFoo() {
      return "bar";
    }
  }
}
