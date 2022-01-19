/*
 * Copyright 2016 Confluent Inc.
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

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.glassfish.jersey.servlet.ServletProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.Response;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class StaticResourcesTest {

  TestRestConfig config;
  StaticResourcesTest.StaticApplication app;
  private Server server;

  String staticContent;

  @BeforeEach
  public void setUp() throws Exception {
    try (
        InputStreamReader isr = new InputStreamReader(ClassLoader.getSystemResourceAsStream("static/index.html"), StandardCharsets.UTF_8);
        BufferedReader br = new BufferedReader(isr)
    ) {
      staticContent = br.readLine() + System.lineSeparator();
    }

    Properties props = new Properties();
    props.setProperty("debug", "false");
    config = new TestRestConfig(props);
    app = new StaticResourcesTest.StaticApplication(config);
    server = app.createServer();
    server.start();
  }

  @AfterEach
  public void tearDown() throws Exception {
    server.stop();
    server.join();
  }

  @Test
  public void testStaticContent() throws Exception {
    testGet("/index.html", 200, staticContent.trim());
  }

  @Test
  public void testDefaultServletMapsToIndex() throws Exception {
    testGet("/", 200, staticContent.trim());
  }

  @Test
  public void testDynamic() throws Exception {
    testGet("/dynamic", 200, "it works");
  }

  private void testGet(String path, int expectedStatus, String expectedMessage) {
    Response response = ClientBuilder.newClient(app.resourceConfig.getConfiguration())
        .target(server.getURI())
        .path(path)
        .request()
        .get();
    assertEquals(expectedStatus, response.getStatus());
    final String entity = response.readEntity(String.class);
    assertEquals(expectedMessage, entity == null ? null : entity.trim());
  }

  private static class StaticApplication extends Application<TestRestConfig> {

    Configurable resourceConfig;

    StaticApplication(TestRestConfig props) {
      super(props);
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      this.resourceConfig = config;
      config.register(DynamicResource.class);
      config.property(ServletProperties.FILTER_STATIC_CONTENT_REGEX, "/(index\\.html|)");
    }

    @Override
    protected ResourceCollection getStaticResources() {
      return new ResourceCollection(Resource.newClassPathResource("static"));
    }
  }

  @Produces("application/json")
  @Path("/")
  public static class DynamicResource {

    @GET
    @Path("/dynamic")
    public String dynamic() {
      return "it works";
    }
  }
}
