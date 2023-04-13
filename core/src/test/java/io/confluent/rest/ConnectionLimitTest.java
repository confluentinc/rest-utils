/*
 * Copyright 2022 Confluent Inc.
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriBuilder;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.Test;

public class ConnectionLimitTest {

  @Test
  public void testServerConnectionLimitEnabled() throws Exception {
    int serverConnectionLimit = 5;
    FooApplication application =
        new FooApplication(
            new FooConfig(
                ImmutableMap.of(
                    "listeners", "http://localhost:0",
                    "server.connection.limit", Integer.toString(serverConnectionLimit))));
    Server server = application.createServer();
    server.start();

    Queue<CloseableHttpClient> activeClients = new LinkedList<>();
    // Reach the connection limit by sending a specific number requests without closing the
    // underlying clients.
    for (int i = 0; i < serverConnectionLimit; i++) {
      CloseableHttpResponse response = sendCloseableRequest(server.getURI(), activeClients);
      assertEquals(Status.OK.getStatusCode(), response.getStatusLine().getStatusCode());
    }

    ExecutorService executor = Executors.newCachedThreadPool();
    try {
      // The next request should time out as the connection limit should prevent establishing a new
      // connection.
      executor.submit(() ->
          sendCloseableRequest(server.getURI(), activeClients)).get(2, TimeUnit.SECONDS);
      fail();
    } catch (Exception e) {
      assertTrue(e instanceof TimeoutException);
    }

    // Make space for a new connection by closing the currently active ones.
    for (CloseableHttpClient client : activeClients) {
      client.close();
    }

    // The next request should succeed now.
    Future<CloseableHttpResponse> future =
        executor.submit(() -> sendCloseableRequest(server.getURI(), activeClients));
    CloseableHttpResponse response = future.get(2, TimeUnit.SECONDS);
    assertEquals(Status.OK.getStatusCode(), response.getStatusLine().getStatusCode());

    // Clean up.
    executor.shutdown();
    for (CloseableHttpClient client : activeClients) {
      client.close();
    }
    server.stop();
  }

  @Test
  public void testServerConnectionLimitDisabled() throws Exception {
    int serverConnectionLimit = 0;
    FooApplication application =
        new FooApplication(
            new FooConfig(
                ImmutableMap.of(
                    "listeners", "http://localhost:0",
                    "server.connection.limit", Integer.toString(serverConnectionLimit))));
    Server server = application.createServer();
    server.start();

    Queue<CloseableHttpClient> activeClients = new LinkedList<>();
    // No connection limit, so a lot of requests can be sent even without closing the underlying
    // clients.
    for (int i = 0; i < 100; i++) {
      CloseableHttpResponse response = sendCloseableRequest(server.getURI(), activeClients);
      assertEquals(Status.OK.getStatusCode(), response.getStatusLine().getStatusCode());
    }

    // Clean up.
    for (CloseableHttpClient client : activeClients) {
      client.close();
    }
    server.stop();
  }

  private static CloseableHttpResponse sendCloseableRequest(
      URI serverUri,
      Queue<CloseableHttpClient> activeConnectionClients) throws IOException {
    CloseableHttpClient client = HttpClients.custom()
        .setConnectionManager(new PoolingHttpClientConnectionManager())
        .setKeepAliveStrategy((httpResponse, httpContext) -> -1)
        .build();
    activeConnectionClients.offer(client);
    HttpGet get = new HttpGet(UriBuilder.fromUri(serverUri).path("/foo").build());
    return client.execute(get);
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
