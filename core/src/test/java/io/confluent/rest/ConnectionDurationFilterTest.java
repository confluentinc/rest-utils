/*
 * Copyright 2024 Confluent Inc.
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

import com.google.common.collect.ImmutableMap;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriBuilder;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;


/*
 *  Note on client support --
 *  The Apache HttpClient library v4.x does not support HTTP/2.0, so we use
 *  OkHttp for HTTP/2.0 tests.
 */
public class ConnectionDurationFilterTest {

  static Server server;
  private static final long MAX_CONNECTION_DURATION_MS = 100;

  @BeforeAll
  public static void setUp() throws Exception {
    MyAddressApplication application =
        new MyAddressApplication(
            new MyAddressConfig(
                ImmutableMap.of(
                    "listeners", "http://localhost:8080",
                    "http2.enabled", "true",
                    "max.connection.duration.ms", String.valueOf(MAX_CONNECTION_DURATION_MS)))
        );
    server = application.createServer();
    server.start();
  }

  @AfterAll
  public static void tearDown() throws Exception {
    server.stop();
  }

  @Test
  public void connectionDurationTest_ApacheHttpClient_Ephemeral_HTTP1() throws Exception {

    CloseableHttpClient client = createPersistentClient();
    List<String> addressList = new ArrayList<>();

    // Send a few requests with a delay between consecutive requests that is longer
    // than the connection duration threshold.
    // Each request should succeed and the server should return a different address for each request.
    for (int i=0; i<5; i++) {

      HttpGet request = createRequest(server.getURI());
      assertEquals("HTTP/1.1", request.getProtocolVersion().toString());

      CloseableHttpResponse response = client.execute(request);
      assertEquals(Status.OK.getStatusCode(), response.getStatusLine().getStatusCode());

      String responseBody = EntityUtils.toString(response.getEntity());
      assertTrue(responseBody.startsWith("127.0.0.1:"));

      response.close();
      addressList.add(responseBody);
      Thread.sleep(MAX_CONNECTION_DURATION_MS + 10);
    }
    client.close();

    // check that we actually used 5 different addresses under the hood
    assertEquals(5, addressList.stream().distinct().count());
  }

  @Test
  public void connectionDurationTest_ApacheHttpClient_Persistent_HTTP1() throws Exception {

    CloseableHttpClient client = createEphemeralClient();
    List<String> addressList = new ArrayList<>();

    // Send a few requests with a delay between consecutive requests that is longer
    // than the connection duration threshold.
    // Each request should succeed and the server should return a different address for each request.
    for (int i=0; i<5; i++) {

      HttpGet request = createRequest(server.getURI());
      assertEquals("HTTP/1.1", request.getProtocolVersion().toString());

      CloseableHttpResponse response = client.execute(request);
      assertEquals(Status.OK.getStatusCode(), response.getStatusLine().getStatusCode());

      String responseBody = EntityUtils.toString(response.getEntity());
      assertTrue(responseBody.startsWith("127.0.0.1:"));

      response.close();
      addressList.add(responseBody);
      Thread.sleep(MAX_CONNECTION_DURATION_MS + 10);
    }
    client.close();

    // check that we actually used 5 different addresses under the hood
    assertEquals(5, addressList.stream().distinct().count());
  }

  @Test
  public void connectionDurationTest_OkHttpClient_HTTP2() throws Exception {

    // explicitly set HTTP 2.0 protocol
    OkHttpClient client = new OkHttpClient.Builder()
        .protocols(Collections.singletonList(okhttp3.Protocol.H2_PRIOR_KNOWLEDGE))
        .build();

    List<String> addressList = new ArrayList<>();

    // Send a few requests with a delay between consecutive requests that is longer
    // than the connection duration threshold.
    // Each request should succeed and the server should return a different address for each request.
    for (int i=0; i<5; i++) {
      Request request = new Request.Builder()
          .url("http://localhost:8080/whatsmyaddress")
          .build();

      Response response = client.newCall(request).execute();
      // assert that the request was indeed http 2
      assertEquals("h2_prior_knowledge", response.protocol().toString());

      assertEquals(Status.OK.getStatusCode(), response.code());

      String responseBody = response.body().string();
      assertTrue(responseBody.startsWith("127.0.0.1:"));

      response.close();
      addressList.add(responseBody);
      Thread.sleep(MAX_CONNECTION_DURATION_MS + 10);
    }

    // shutdown the client
    client.dispatcher().executorService().shutdown();

    // check that we actually used 5 different addresses under the hood
    assertEquals(5, addressList.stream().distinct().count());

  }

  private static HttpGet createRequest(URI serverUri) {
    return new HttpGet(UriBuilder.fromUri(serverUri).path("/whatsmyaddress").build());
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

  public static final class MyAddressApplication extends Application<MyAddressConfig> {

    public MyAddressApplication(MyAddressConfig config) {
      super(config);
    }

    @Override
    public void setupResources(Configurable<?> config, MyAddressConfig appConfig) {
      config.register(AddressResource.class);
    }
  }

  public static final class MyAddressConfig extends RestConfig {

    public MyAddressConfig(Map<String, String> configs) {
      super(baseConfigDef(), configs);
    }
  }

  @Path("/whatsmyaddress")
  public static final class AddressResource {

    @GET
    public String getAddress(@Context HttpServletRequest request) {
      return request.getRemoteAddr() + ":" + request.getRemotePort();
    }
  }
}
