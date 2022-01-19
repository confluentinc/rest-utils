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

import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class GzipHandlerIntegrationTest {
  private TestRestConfig config;
  private Server server;

  @BeforeEach
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.setProperty("debug", "false");
    props.setProperty("compression.enable", "true");
    props.setProperty("listeners", "http://localhost:0");
    config = new TestRestConfig(props);
    CompressibleApplication application = new CompressibleApplication(config);
    server = application.createServer();
    server.start();
  }

  @AfterEach
  public void tearDown() throws Exception {
    server.stop();
    server.join();
  }

  @Test
  public void testGzip() {
    Response response = ClientBuilder.newClient()
        .target(server.getURI())
        .path("/test/zeros")
        .request(MediaType.APPLICATION_OCTET_STREAM)
        .acceptEncoding("gzip")
        .get();
    assertEquals(200, response.getStatus());
    assertEquals("gzip", response.getHeaderString("Content-Encoding"));
  }

  private static class CompressibleApplication extends Application<TestRestConfig> {

    CompressibleApplication(TestRestConfig props) {
      super(props);
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      config.register(ZerosResource.class);
    }
  }

  @Produces(MediaType.APPLICATION_OCTET_STREAM)
  @Path("/test")
  public static class ZerosResource {
    @GET
    @Path("/zeros")
    public byte[] zeros() {
      return new byte[1 << 20];
    }
  }
}
