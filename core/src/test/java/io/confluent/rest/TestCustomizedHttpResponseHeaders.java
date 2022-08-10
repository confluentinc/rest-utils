/*
 * Copyright 2019 Confluent Inc.
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

import org.apache.http.Header;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.kafka.common.config.ConfigException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.junit.runners.Parameterized.Parameter;
import org.junit.experimental.runners.Enclosed;
import java.util.Properties;
import java.util.Arrays;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.MediaType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@RunWith(Enclosed.class)
public class TestCustomizedHttpResponseHeaders {

  private static final Logger log = LoggerFactory.getLogger(TestCustomizeThreadPool.class);

  @RunWith(Parameterized.class)
  public static class TestInvalidHeaderConfig {
    @Parameter
    public String invalidHeaderConfig;

    @Parameters(name = "{0}")
    public static Object[] invalidData() {
      return new Object[]{
              "set",
              "badaction X-Frame-Options:DENY",
              "set add X-XSS-Protection:1",
              "addX-XSS-Protection",
              "X-XSS-Protection:",
              "add set X-XSS-Protection:",
              "add X-XSS-Protection:1 X-XSS-Protection:1 ",
              "add X-XSS-Protection:1,   ,",
              "set X-Frame-Options:DENY, add  :no-cache, no-store, must-revalidate "
      };
    }

    @Test(expected = ConfigException.class)
    public void testInvalidHeaderConfigFormat()throws Exception {
      Properties props = new Properties();
      props.put(RestConfig.LISTENERS_CONFIG, "http://localhost:8080");
      props.put(RestConfig.RESPONSE_HTTP_HEADERS_CONFIG, invalidHeaderConfig);

      TestApp app = new TestApp(props);
      CloseableHttpResponse response = null;
      try {
        app.start();
        response = makeGetRequest(app, "/custom/resource1");
      } finally {
        try {
          if (response != null) {
            response.close();
          }
        } catch (Exception e) {
        }
        app.stop();
      }
    }
  }

  public static class TestValidHeaderConfig {
    @Test
    public void testNoCustomizedHeaderConfigs()throws Exception {
      Properties props = new Properties();
      props.put(RestConfig.LISTENERS_CONFIG, "http://localhost:8080");

      TestApp app = new TestApp(props);
      CloseableHttpResponse response = null;
      try {
        app.start();
        response = makePostRequest(app, "/custom/resource2");
        String headerValue = getResponseHeader(response, "X-Frame-Options");
        assertNull(headerValue);
      } finally {
        try {
          if (response != null) {
            response.close();
          }
        } catch (Exception e) {
        }
        app.stop();
      }
    }

    @Test
    public void testValidHeaderConfigs()throws Exception {
      Properties props = new Properties();
      props.put(RestConfig.LISTENERS_CONFIG, "http://localhost:8080");
      props.put(RestConfig.RESPONSE_HTTP_HEADERS_CONFIG,
              "  set    X-Frame-Options: DENY, \"  add     Cache-Control:   no-cache, no-store, must-revalidate\" ");

      TestApp app = new TestApp(props);
      CloseableHttpResponse response = null;
      try {
        app.start();
        response = makeGetRequest(app, "/custom/resource1");
        assertEquals("DENY",
                getResponseHeader(response, "X-Frame-Options"));
        assertEquals("no-cache, no-store, must-revalidate",
                getResponseHeader(response, "Cache-Control"));
        assertNull(getResponseHeader(response, "X-Custom-Value"));
      } finally {
        try {
          if (response != null) {
            response.close();
          }
        } catch (Exception e) {
        }
        app.stop();
      }
    }
  }

  private static class TestApp extends Application<TestRestConfig> {
    static Properties props = null;

    public TestApp() {
      super(createConfig());
    }

    public TestApp(Properties props) {
      super(new TestRestConfig(props));
      this.props = props;
    }

    @Path("/custom")
    @Produces(MediaType.TEXT_PLAIN)
    public static class RestResource {
      @GET
      @Path("/resource1")
      public String get1() {
        return "testing resource1";
      }

      @POST
      @Path("/resource2")
      public String get2() {
        return "testing resource2";
      }
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      config.register(new RestResource());
    }

    public String getUri() {
      return (String)props.get(RestConfig.LISTENERS_CONFIG);
    }

    private static TestRestConfig createConfig() {
      props = new Properties();
      return new TestRestConfig(props);
    }
  }

  @SuppressWarnings("SameParameterValue")
  private static CloseableHttpResponse makeGetRequest(TestApp app, final String path) throws Exception {
    String uri = app.getUri();
    final HttpGet httpget = new HttpGet(uri + path);
    CloseableHttpClient httpClient = HttpClients.createDefault();
    CloseableHttpResponse response = httpClient.execute(httpget);
    try {
      if (httpClient != null) {
        httpClient.close();
      }
    } catch (Exception e) {
    }
    return response;
  }

  @SuppressWarnings("SameParameterValue")
  private static CloseableHttpResponse makePostRequest(TestApp app, String path) throws Exception {
    String uri = app.getUri();
    HttpPost httpPost = new HttpPost(uri + path);
    CloseableHttpClient httpClient = HttpClients.createDefault();
    CloseableHttpResponse response = httpClient.execute(httpPost);
    try {
      if (httpClient != null) {
        httpClient.close();
      }
    } catch (Exception e) {
    }
    return response;
  }

  private static String getResponseHeader(CloseableHttpResponse response, String name) {
    String value = null;
    Header[] headers = response.getAllHeaders();
    if (headers != null && headers.length > 0) {
      Arrays.stream(headers)
              .forEach(header -> log.debug("header name: {}, header value: {}.", header.getName(), header.getValue()));
    }

    headers = response.getHeaders(name);
    if (headers != null && headers.length > 0) {
      value = headers[0].getValue();
    }

    return value;
  }
}



