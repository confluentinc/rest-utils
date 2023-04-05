/*
 * Copyright 2023 Confluent Inc.
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

import static io.confluent.rest.TestUtils.getFreePort;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.MediaType;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;

@Tag("IntegrationTest")
public class RequestLogHandlerIntegrationTest {

  private final HttpClient httpClient = new HttpClient();

  @Captor
  private ArgumentCaptor<org.eclipse.jetty.server.Request> requestCaptor;

  @Captor
  private ArgumentCaptor<org.eclipse.jetty.server.Response> responseCaptor;

  @BeforeEach
  public void setUp() throws Exception {
    httpClient.start();
    requestCaptor = ArgumentCaptor.forClass(org.eclipse.jetty.server.Request.class);
    responseCaptor = ArgumentCaptor.forClass(org.eclipse.jetty.server.Response.class);
  }

  @AfterEach
  public void tearDown() throws Exception {
    httpClient.stop();
  }

  @Test
  public void test_CustomRequestLog_registeredToCorrectListener() throws Exception {
    int internalPort = getFreePort();
    int externalPort = getFreePort();
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.LISTENERS_CONFIG,
        "INTERNAL://127.0.0.1:" + internalPort + ",EXTERNAL://127.0.0.1:" + externalPort);
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "INTERNAL:http,EXTERNAL:http");
    TestRestConfig config = new TestRestConfig(props);

    // internal application
    CustomRequestLog mockLogInternal = mock(CustomRequestLog.class);
    TestApp internalApp = new TestApp(config, "/", "internal", mockLogInternal);
    Server server = internalApp.createServer();

    // external application
    CustomRequestLog mockLogExternal = mock(CustomRequestLog.class);
    TestApp externalApp = new TestApp(config, "/", "external", mockLogExternal);
    ((ApplicationServer<TestRestConfig>) server).registerApplication(externalApp);
    server.start();

    // send a request to internal application
    ContentResponse response = httpClient
        .newRequest("http://127.0.0.1:" + internalPort)
        .path("/custom/resource")
        .send();

    // check that only internal application logs the request
    verify(mockLogInternal, times(1)).log(requestCaptor.capture(), responseCaptor.capture());
    // check that external application never log the request
    verify(mockLogExternal, never()).log(any(), any());
    assertEquals("127.0.0.1", requestCaptor.getValue().getServerName());
    assertEquals(200, responseCaptor.getValue().getStatus());
    assertEquals(200, response.getStatus());

    // stop server
    server.stop();
  }

  private static class TestApp extends Application<TestRestConfig> implements AutoCloseable {

    private static final AtomicBoolean SHUTDOWN_CALLED = new AtomicBoolean(true);

    public TestApp(TestRestConfig config, String path, String listenerName,
        CustomRequestLog customRequestLog) {
      super(config, path, listenerName, customRequestLog);
    }

    @Override
    public void setupResources(final Configurable<?> config, final TestRestConfig appConfig) {
      config.register(RestResource.class);
    }

    @Override
    public void close() throws Exception {
      stop();
    }

    @Override
    public void onShutdown() {
      SHUTDOWN_CALLED.set(true);
    }
  }

  @Path("/custom")
  @Produces(MediaType.TEXT_PLAIN)
  public static class RestResource {

    @GET
    @Path("/resource")
    public String get() {
      return "Hello";
    }
  }
}
