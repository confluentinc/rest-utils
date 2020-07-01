/**
 * Copyright 2020 Confluent Inc.
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

import static io.confluent.rest.metrics.TestRestMetricsContext.RESOURCE_LABEL_TYPE;
import static org.apache.kafka.common.metrics.MetricsContext.NAMESPACE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import io.confluent.rest.metrics.RestMetricsContext;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.ws.rs.core.Configurable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;


public class ApplicationMetricsContextTest {

  private static ApplicationServer server;

  @Before
  public void setup() throws Exception {
    Properties props = new Properties();
    props.setProperty(RestConfig.LISTENERS_CONFIG, "http://0.0.0.0:0");

    server = new ApplicationServer(new TestRestConfig(props));
  }

  @After
  public void tearDown() throws Exception {
    server.stop();
  }

  @Test
  public void testDefaultMetricsContext() throws Exception {
    TestApp testApp = new TestApp();
    server.registerApplication(testApp);
    server.start();

    assertEquals(testApp.metricsContext().getLabel(RESOURCE_LABEL_TYPE),
            RestConfig.METRICS_JMX_PREFIX_DEFAULT);
    assertEquals(testApp.metricsContext().getLabel(NAMESPACE),
            RestConfig.METRICS_JMX_PREFIX_DEFAULT);
  }

  @Test
  public void testMetricsContextResourceOverride() throws Exception {
    Map<String, Object> props = new HashMap<>();
    props.put("metrics.context.resource.type", "FooApp");

    TestApp testApp = new TestApp(props);
    server.registerApplication(testApp);
    server.start();

    assertEquals(testApp.metricsContext().getLabel(RESOURCE_LABEL_TYPE), "FooApp");
    assertEquals(testApp.metricsContext().getLabel(NAMESPACE), RestConfig.METRICS_JMX_PREFIX_DEFAULT);

    /* Only NameSpace should be propagated to JMX */
    String jmx_domain = RestConfig.METRICS_JMX_PREFIX_DEFAULT;
    String mbean_name = String.format("%s:type=kafka-metrics-count", jmx_domain);

    MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    assertNotNull(server.getObjectInstance(new ObjectName(mbean_name)));

  }

  @Test
  public void testMetricsContextJMXPrefixPropagation() throws Exception {
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.METRICS_JMX_PREFIX_CONFIG, "FooApp");

    TestApp testApp = new TestApp(props);
    server.registerApplication(testApp);
    server.start();

    assertEquals(testApp.metricsContext().getLabel(RESOURCE_LABEL_TYPE), "FooApp");
    assertEquals(testApp.metricsContext().getLabel(NAMESPACE), "FooApp");
  }

  @Test
  public void testMetricsReporterPrefixPropagation() throws Exception {
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.METRICS_REPORTER_CONFIG_PREFIX + "fooreporter.bootstrap.server",
            "bar:9092");

    TestApp testApp = new TestApp(props);

    Map<String, Object> reporterProps = testApp.getConfiguration().metricsReporterConfig();
    assertEquals(reporterProps.get("fooreporter.bootstrap.server"), "bar:9092");
  }

  @Test
  public void testMetricsContextJMXBeanRegistration() throws Exception {
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.METRICS_JMX_PREFIX_CONFIG, "FooApp");

    TestApp testApp = new TestApp(props);
    server.registerApplication(testApp);
    server.start();

    testApp.start();
    MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    assertNotNull(server.getObjectInstance(new ObjectName("FooApp:type=kafka-metrics-count")));
  }

  @Test
  public void testMetricsContextJMXBeanRegistration_ApplicationGroup() throws Exception {
    Map<String, Object> appProps1 = new HashMap<>();
    appProps1.put(RestConfig.METRICS_JMX_PREFIX_CONFIG, "FooApp1");

    Map<String, Object> appProps2 = new HashMap<>();
    appProps2.put(RestConfig.METRICS_JMX_PREFIX_CONFIG, "FooApp2");

    TestApp testApp1 = new TestApp(appProps1);
    TestApp testApp2 = new TestApp(appProps2);

    server.registerApplication(testApp1);
    server.registerApplication(testApp2);

    server.start();

    MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    assertNotNull(server.getObjectInstance(new ObjectName("FooApp1:type=kafka-metrics-count")));
    assertNotNull(server.getObjectInstance(new ObjectName("FooApp2:type=kafka-metrics-count")));
  }

  private static class TestApp extends Application<TestRestConfig> implements AutoCloseable {
    public TestApp() throws Exception {
      this(new HashMap<>());
    }

    public TestApp(final Map<String, Object> config) throws Exception {
      super(createConfig(config));
    }

    public RestMetricsContext metricsContext() {
      return getConfiguration().getMetricsContext();
    }

    @Override
    public void setupResources(final Configurable<?> config, final TestRestConfig appConfig) {
    }

    @Override
    public void close() throws Exception {
      stop();
    }

    private static TestRestConfig createConfig(final Map<String, Object> props) {
      final HashMap<String, Object> config = new HashMap<>(props);
      config.put(RestConfig.LISTENERS_CONFIG, "http://0.0.0.0:0");
      return new TestRestConfig(config);
    }
  }
}
