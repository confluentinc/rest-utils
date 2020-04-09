/**
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
 **/

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
import java.util.Properties;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.MediaType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@RunWith(Parameterized.class)
public class TestCustomizedHttpResponseHeaders {

  private static final Logger log = LoggerFactory.getLogger(TestCustomizeThreadPool.class);

  @Parameter
  public String headerConfig;

  @Parameters(name = "{0}")
  public static Object[] data() {
    return new Object[]{
            "set",
            "set add X-XSS-Protection:1",
            "addX-XSS-Protection",
            "add X-XSS-Protection:",
            "X-XSS-Protection:",
            "add set X-XSS-Protection:",
            "add X-XSS-Protection:1 X-XSS-Protection:1 ",
            "set X-Frame-Options:DENY, add  :no-cache, no-store, must-revalidate "
    };
  }

  @Test
  public void testNoCustomizedHeaderConfigs()throws Exception {
    Properties props = createNoCustomizedHeaderConfigProperties();
    TestApp app = new TestApp(props);
    CloseableHttpResponse response = null;
    try {
      app.start();
      response = makeGetRequest(app, "/custom/resource1");
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

  /**
   * Testing valid header config
   * 1) Value has to be in format: [action] [header name]:[header value]. The first part is [action],
   * the second part is [header name]:[header value] pair seperated by :. There is at least one space between [action]
   * and [header name]:[header value]
   * 2) Values for [action] should be in "set, add, setDate, or addDate"
   */
  @Test
  public void testValidHeaderConfigs()throws Exception {
    Properties props = createValidHeaderConfigProperties();
    TestApp app = new TestApp(props);
    CloseableHttpResponse response = null;
    try {
      app.start();
      response = makeGetRequest(app, "/custom/resource1");
      String headerValue = getResponseHeader(response, "X-Frame-Options");
      assertEquals("DENY", headerValue);
      headerValue = getResponseHeader(response, "Cache-Control");
      assertEquals("no-cache, no-store, must-revalidate", headerValue);
      headerValue = getResponseHeader(response, "X-Custom-Value");
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

  @Test(expected = ConfigException.class)
  public void testInvalidHeaderConfigFormat()throws Exception {
    Properties props = createInvalidHeaderConfigFormatProperties(headerConfig);
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

  @Test(expected = ConfigException.class)
  public void testInvalidHeaderConfigAction()throws Exception {
    Properties props = createInvalidHeaderConfigActionProperties();
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

  private static class TestApp extends Application<TestRestConfig> {
    static Properties props = null;

    public TestApp() {
      super(createConfig());
    }
    public TestApp(Properties props) {
      super(new TestRestConfig(props));
      this.props = props;
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
      String uri = "http://localhost:8080";
      return new TestRestConfig(props);
    }
  }

  @SuppressWarnings("SameParameterValue")
  private CloseableHttpResponse makeGetRequest(final TestApp app, final String path) throws Exception {
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
  private CloseableHttpResponse makePostRequest(final TestApp app, final String path) throws Exception {
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

  private String getResponseHeader(CloseableHttpResponse response, String name) {
    String value = null;
    Header[] header = response.getAllHeaders();
    if (header != null && header.length > 0) {
      for (int i = 0; i < header.length; i++) {
        log.info("header name: {}, header value: {}.", header[i].getValue(), header[i].getValue());
      }
    }

    header = response.getHeaders(name);
    if (header != null && header.length > 0) {
      value = header[0].getValue();
    }

    return value;
  }

  private Properties createNoCustomizedHeaderConfigProperties() {
    Properties props = new Properties();
    String uri = "http://localhost:8080";
    props.put(RestConfig.LISTENERS_CONFIG, uri);
    return props;
  }

  private Properties createValidHeaderConfigProperties() {
    Properties props = new Properties();
    String uri = "http://localhost:8080";
    props.put(RestConfig.LISTENERS_CONFIG, uri);
    props.put(RestConfig.RESPONSE_HTTP_HEADERS_CONFIG,
            "  set    X-Frame-Options: DENY,   ,\"  add     Cache-Control:   no-cache, no-store, must-revalidate\" ");
    return props;
  }

  private Properties createInvalidHeaderConfigFormatProperties(String config) {
    Properties props = new Properties();
    String uri = "http://localhost:8080";
    props.put(RestConfig.LISTENERS_CONFIG, uri);
    props.put(RestConfig.RESPONSE_HTTP_HEADERS_CONFIG, config);
    return props;
  }

  private Properties createInvalidHeaderConfigActionProperties() {
    Properties props = new Properties();
    String uri = "http://localhost:8080";
    props.put(RestConfig.LISTENERS_CONFIG, uri);
    props.put(RestConfig.RESPONSE_HTTP_HEADERS_CONFIG, "badaction X-Frame-Options:DENY");
    return props;
  }
}



