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
import static org.eclipse.jetty.server.Request.getServerName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Configurable;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.NetworkTrafficServerConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Slf4jRequestLogWriter;
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
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.LISTENERS_CONFIG,
        "INTERNAL://127.0.0.1:" + getFreePort() + ",EXTERNAL://127.0.0.1:" + getFreePort());
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "INTERNAL:http,EXTERNAL:http");
    TestRestConfig config = new TestRestConfig(props);

    // external application
    CustomRequestLog mockLogExternal = createSpiedCustomRequestLog(config);
    TestApp externalApp = new TestApp(config, "/", "external", mockLogExternal);
    Server server = externalApp.createServer();

    // internal application
    CustomRequestLog mockLogInternal = createSpiedCustomRequestLog(config);
    TestApp internalApp = new TestApp(config, "/", "internal", mockLogInternal);
    ((ApplicationServer<TestRestConfig>) server).registerApplication(internalApp);
    server.start();

    // get internal application port
    int internalPort =
    Arrays.stream(server.getConnectors())
        .filter(connector -> connector.getName().equals("internal"))
        .findAny()
        .map(NetworkTrafficServerConnector.class::cast)
        .map(NetworkTrafficServerConnector::getLocalPort)
        .orElse(0);
    assertTrue(internalPort > 0);

    // send a request to internal application
    ContentResponse response = httpClient
        .newRequest("http://127.0.0.1:" + internalPort)
        .path("/custom/resource")
        .send();

    // check that external application never logs the request
    verify(mockLogExternal, never()).log(any(), any());
    // check that only internal application logs the request
    verify(mockLogInternal, times(1)).log(requestCaptor.capture(), responseCaptor.capture());
    assertEquals("127.0.0.1", getServerName(requestCaptor.getValue()));
    assertEquals(200, responseCaptor.getValue().getStatus());
    assertEquals(200, response.getStatus());

    // stop server
    server.stop();
    server.join();
  }

  private CustomRequestLog createSpiedCustomRequestLog(RestConfig config) {
    Slf4jRequestLogWriter logWriter = new Slf4jRequestLogWriter();
    logWriter.setLoggerName(config.getString(RestConfig.REQUEST_LOGGER_NAME_CONFIG));
    return spy(new CustomRequestLog(logWriter, CustomRequestLog.EXTENDED_NCSA_FORMAT));
  }

  private static class TestApp extends Application<TestRestConfig> implements AutoCloseable {

    TestApp(TestRestConfig config, String path, String listenerName,
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
