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

import java.net.URI;
import java.util.Map;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriBuilder;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.eclipse.jetty.server.Server;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class DosFilterTest {

  @Test
  public void dosFilterEnabled_defaultTracking_throttlesRequestsPerIp() throws Exception {
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

    HttpGet request = createRequest(server.getURI());

    CloseableHttpClient ephemeralClient = createEphemeralClient();

    // Request should succeed.
    CloseableHttpResponse response1 = ephemeralClient.execute(request);
    assertEquals(Status.OK.getStatusCode(), response1.getStatusLine().getStatusCode());
    response1.close();

    // Following requests should all be throttled.
    for (int i = 0; i < 100; i++) {
      CloseableHttpResponse response2 = ephemeralClient.execute(request);
      assertEquals(
          Status.TOO_MANY_REQUESTS.getStatusCode(), response2.getStatusLine().getStatusCode());
      response2.close();
    }

    Thread.sleep(1000);

    // Request should succeed again.
    CloseableHttpResponse response3 = ephemeralClient.execute(request);
    assertEquals(Status.OK.getStatusCode(), response3.getStatusLine().getStatusCode());
    response3.close();

    server.stop();
  }

  @Test
  public void dosFilterEnabled_remotePort_throttlesRequestsPerConnection() throws Exception {
    FooApplication application =
        new FooApplication(
            new FooConfig(
                ImmutableMap.<String, String>builder()
                    .put("listeners", "http://localhost:0")
                    .put("dos.filter.enabled", "true")
                    .put("dos.filter.max.requests.per.sec", "1")
                    .put("dos.filter.delay.ms", "-1")
                    .put("dos.filter.remote.port", "true")
                    .build()));
    Server server = application.createServer();
    server.start();

    HttpGet request = createRequest(server.getURI());

    // Following requests should not be throttled, since they use different connections.
    CloseableHttpClient ephemeralClient = createEphemeralClient();
    for (int i = 0; i < 100; i++) {
      CloseableHttpResponse response = ephemeralClient.execute(request);
      assertEquals(Status.OK.getStatusCode(), response.getStatusLine().getStatusCode());
      response.close();
    }

    CloseableHttpClient persistentClient = createPersistentClient();

    // Request should succeed.
    CloseableHttpResponse response1 = persistentClient.execute(request);
    assertEquals(Status.OK.getStatusCode(), response1.getStatusLine().getStatusCode());
    response1.getEntity().getContent().close();
    response1.close();

    // Following requests should all be throttled since they all reuse the same connection.
    for (int i = 0; i < 100; i++) {
      CloseableHttpResponse response2 = persistentClient.execute(request);
      assertEquals(
          Status.TOO_MANY_REQUESTS.getStatusCode(), response2.getStatusLine().getStatusCode());
      response2.getEntity().getContent().close();
      response2.close();
    }

    Thread.sleep(1000);

    // Request on the same connection should succeed again.
    CloseableHttpResponse response3 = persistentClient.execute(request);
    assertEquals(Status.OK.getStatusCode(), response3.getStatusLine().getStatusCode());
  }

  @Test
  public void dosFilterEnabled_trackGlobal_throttlesRequestsGlobally() throws Exception {
    FooApplication application =
        new FooApplication(
            new FooConfig(
                ImmutableMap.<String, String>builder()
                    .put("listeners", "http://localhost:0")
                    .put("dos.filter.enabled", "true")
                    .put("dos.filter.max.requests.per.sec", "1")
                    .put("dos.filter.delay.ms", "-1")
                    .put("dos.filter.track.global", "true")
                    .build()));
    Server server = application.createServer();
    server.start();

    HttpGet request = createRequest(server.getURI());

    // There's no way for us to actually check throttling is happening across different IPs. We
    // check here it behaves at least per-IP.

    CloseableHttpClient ephemeralClient = createEphemeralClient();

    // Request should succeed.
    CloseableHttpResponse response1 = ephemeralClient.execute(request);
    assertEquals(Status.OK.getStatusCode(), response1.getStatusLine().getStatusCode());
    response1.close();

    // Following requests should all be throttled.
    for (int i = 0; i < 100; i++) {
      CloseableHttpResponse response2 = ephemeralClient.execute(request);
      assertEquals(
          Status.TOO_MANY_REQUESTS.getStatusCode(), response2.getStatusLine().getStatusCode());
      response2.close();
    }

    Thread.sleep(1000);

    // Request should succeed again.
    CloseableHttpResponse response3 = ephemeralClient.execute(request);
    assertEquals(Status.OK.getStatusCode(), response3.getStatusLine().getStatusCode());
    response3.close();

    server.stop();
  }

  @Test
  public void dosFilterDisabled_doesNotThrottleRequests() throws Exception {
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

    HttpGet request = createRequest(server.getURI());

    CloseableHttpClient ephemeralClient = createEphemeralClient();

    // Request should succeed.
    CloseableHttpResponse response1 = ephemeralClient.execute(request);
    assertEquals(Status.OK.getStatusCode(), response1.getStatusLine().getStatusCode());
    response1.close();

    // Following requests should also all succeed.
    for (int i = 0; i < 100; i++) {
      CloseableHttpResponse response2 = ephemeralClient.execute(request);
      assertEquals(Status.OK.getStatusCode(), response2.getStatusLine().getStatusCode());
      response2.close();
    }

    server.stop();
  }

  @Test
  public void dosFilterEnabled_throttlesRequestsPerConnection_AndGlobally()
      throws Exception {
    FooApplication application =
        new FooApplication(
            new FooConfig(
                ImmutableMap.<String, String>builder()
                    .put("listeners", "http://localhost:0")
                    .put("dos.filter.enabled", "true")
                    .put("dos.filter.max.requests.global.per.sec", "1")
                    .put("dos.filter.delay.ms", "-1")
                    .put("dos.filter.track.global", "true")
                    .build()));
    Server server = application.createServer();
    server.start();

    HttpGet request = createRequest(server.getURI());

    CloseableHttpClient ephemeralClient = createEphemeralClient();
    CloseableHttpClient persistentClient = createPersistentClient();

    // First request succeeds, everything else withing 1s fails
    CloseableHttpResponse response1 = ephemeralClient.execute(request);
    assertEquals(Status.OK.getStatusCode(), response1.getStatusLine().getStatusCode());
    response1.close();

    CloseableHttpResponse responsePersistent = persistentClient.execute(request);
    assertEquals(
        Status.TOO_MANY_REQUESTS.getStatusCode(), responsePersistent.getStatusLine().getStatusCode());
    responsePersistent.getEntity().getContent().close();
    responsePersistent.close();

    CloseableHttpResponse responseEphemeral = ephemeralClient.execute(request);
    assertEquals(
        Status.TOO_MANY_REQUESTS.getStatusCode(), responseEphemeral.getStatusLine().getStatusCode());
    responseEphemeral.close();

    // Reset the rate
    Thread.sleep(1000);

    CloseableHttpResponse response2 = persistentClient.execute(request);
    assertEquals(Status.OK.getStatusCode(), response2.getStatusLine().getStatusCode());

    CloseableHttpResponse response3 = ephemeralClient.execute(request);
    assertEquals(
        Status.TOO_MANY_REQUESTS.getStatusCode(), response3.getStatusLine().getStatusCode());

    Thread.sleep(1000);

    CloseableHttpResponse response4 = persistentClient.execute(request);
    assertEquals(Status.OK.getStatusCode(), response4.getStatusLine().getStatusCode());

    server.stop();
  }


  private static HttpGet createRequest(URI serverUri) {
    return new HttpGet(UriBuilder.fromUri(serverUri).path("/foo").build());
  }

  private static CloseableHttpClient createEphemeralClient() {
    return HttpClients.custom()
        .setConnectionManager(new BasicHttpClientConnectionManager())
        .setKeepAliveStrategy((httpResponse, httpContext) -> -1)
        .build();
  }

  private static CloseableHttpClient createPersistentClient() {
    return HttpClients.custom()
        .setConnectionManager(new PoolingHttpClientConnectionManager())
        .setKeepAliveStrategy((httpResponse, httpContext) -> 5000)
        .build();
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
