/**
 * Copyright 2014 Confluent Inc.
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.jaxrs.base.JsonParseExceptionMapper;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.NetworkTrafficServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;
import org.glassfish.jersey.server.validation.ValidationFeature;
import org.glassfish.jersey.servlet.ServletContainer;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.servlet.DispatcherType;
import javax.ws.rs.core.Configurable;

import io.confluent.common.metrics.JmxReporter;
import io.confluent.common.metrics.MetricConfig;
import io.confluent.common.metrics.Metrics;
import io.confluent.common.metrics.MetricsReporter;
import io.confluent.rest.exceptions.ConstraintViolationExceptionMapper;
import io.confluent.rest.exceptions.GenericExceptionMapper;
import io.confluent.rest.exceptions.WebApplicationExceptionMapper;
import io.confluent.rest.logging.Slf4jRequestLog;
import io.confluent.rest.metrics.MetricsResourceMethodApplicationListener;
import io.confluent.rest.validation.JacksonMessageBodyProvider;

/**
 * A REST application. Extend this class and implement setupResources() to register REST
 * resources with the JAX-RS server. Use createServer() to get a fully-configured, ready to run
 * Jetty server.
 */
public abstract class Application<T extends RestConfig> {
  protected T config;
  protected Server server = null;
  protected CountDownLatch shutdownLatch = new CountDownLatch(1);
  protected Metrics metrics;

  public Application(T config) {
    this.config = config;
    MetricConfig metricConfig = new MetricConfig()
        .samples(config.getInt(RestConfig.METRICS_NUM_SAMPLES_CONFIG))
        .timeWindow(config.getLong(RestConfig.METRICS_SAMPLE_WINDOW_MS_CONFIG),
                    TimeUnit.MILLISECONDS);
    List<MetricsReporter> reporters =
        config.getConfiguredInstances(RestConfig.METRICS_REPORTER_CLASSES_CONFIG,
                                      MetricsReporter.class);
    reporters.add(new JmxReporter(config.getString(RestConfig.METRICS_JMX_PREFIX_CONFIG)));
    this.metrics = new Metrics(metricConfig, reporters, config.getTime());
  }

  /**
   * Register resources or additional Providers, ExceptionMappers, and other JAX-RS components with
   * the Jersey application. This, combined with your Configuration class, is where you can
   * customize the behavior of the application.
   */
  public abstract void setupResources(Configurable<?> config, T appConfig);

  /**
   * Returns a map of tag names to tag values to apply to metrics for this application.
   *
   * @return a Map of tags and values
   */
  public Map<String,String> getMetricsTags() {
    return new LinkedHashMap<String, String>();
  }

  /**
   * Configure and create the server.
   */
  public Server createServer() throws RestConfigException {
    // The configuration for the JAX-RS REST service
    ResourceConfig resourceConfig = new ResourceConfig();

    Map<String, String> metricTags = getMetricsTags();

    configureBaseApplication(resourceConfig, metricTags);
    setupResources(resourceConfig, getConfiguration());

    // Configure the servlet container
    ServletContainer servletContainer = new ServletContainer(resourceConfig);
    ServletHolder servletHolder = new ServletHolder(servletContainer);
    server = new Server() {
      @Override
      protected void doStop() throws Exception {
        super.doStop();
        Application.this.metrics.close();
        Application.this.onShutdown();
        Application.this.shutdownLatch.countDown();
      }
    };

    NetworkTrafficServerConnector connector = new NetworkTrafficServerConnector(server);
    connector.addNetworkTrafficListener(new MetricsListener(metrics, "jetty", metricTags));
    connector.setPort(getConfiguration().getInt(RestConfig.PORT_CONFIG));
    server.setConnectors(new Connector[]{connector});

    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.setContextPath("/");
    context.addServlet(servletHolder, "/*");

    String allowedOrigins = getConfiguration().getString(RestConfig.ACCESS_CONTROL_ALLOW_ORIGIN_CONFIG);
    if (allowedOrigins != null && !allowedOrigins.trim().isEmpty()) {
      FilterHolder filterHolder = new FilterHolder(CrossOriginFilter.class);
      filterHolder.setName("cross-origin");
      filterHolder.setInitParameter("allowedOrigins", allowedOrigins);
      context.addFilter(filterHolder, "/*", EnumSet.of(DispatcherType.REQUEST));
    }

    RequestLogHandler requestLogHandler = new RequestLogHandler();
    Slf4jRequestLog requestLog = new Slf4jRequestLog();
    requestLog.setLoggerName(config.getString(RestConfig.REQUEST_LOGGER_NAME_CONFIG));
    requestLog.setLogLatency(true);
    requestLogHandler.setRequestLog(requestLog);

    HandlerCollection handlers = new HandlerCollection();
    handlers.setHandlers(new Handler[]{context, new DefaultHandler(), requestLogHandler});

    /* Needed for graceful shutdown as per `setStopTimeout` documentation */
    StatisticsHandler statsHandler = new StatisticsHandler();
    statsHandler.setHandler(handlers);

    server.setHandler(statsHandler);

    int gracefulShutdownMs = getConfiguration().getInt(RestConfig.SHUTDOWN_GRACEFUL_MS_CONFIG);
    if (gracefulShutdownMs > 0) {
      server.setStopTimeout(gracefulShutdownMs);
    }
    server.setStopAtShutdown(true);

    return server;
  }

  public void configureBaseApplication(Configurable<?> config) {
    configureBaseApplication(config, null);
  }

  /**
   * Register standard components for a JSON REST application on the given JAX-RS configurable,
   * which can be either an ResourceConfig for a server or a ClientConfig for a Jersey-based REST
   * client.
   */
  public void configureBaseApplication(Configurable<?> config, Map<String, String> metricTags) {
    RestConfig restConfig = getConfiguration();

    ObjectMapper jsonMapper = getJsonMapper();
    jsonMapper.enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    JacksonMessageBodyProvider jsonProvider = new JacksonMessageBodyProvider(jsonMapper);
    config.register(jsonProvider);
    config.register(JsonParseExceptionMapper.class);

    config.register(ValidationFeature.class);
    config.register(ConstraintViolationExceptionMapper.class);
    config.register(new WebApplicationExceptionMapper(restConfig));
    config.register(new GenericExceptionMapper(restConfig));

    config.register(new MetricsResourceMethodApplicationListener(metrics, "jersey",
                                                                 metricTags, restConfig.getTime()));

    config.property(ServerProperties.BV_SEND_ERROR_IN_RESPONSE, true);
  }

  public T getConfiguration() {
    return this.config;
  }

  /**
   * Gets a JSON ObjectMapper to use for (de)serialization of request/response entities. Override
   * this to configure the behavior of the serializer. One simple example of customization is to
   * set the INDENT_OUTPUT flag to make the output more readable. The default is a default
   * Jackson ObjectMapper.
   */
  protected ObjectMapper getJsonMapper() {
    return new ObjectMapper();
  }

  /**
   * Start the server (creating it if necessary).
   * @throws Exception
   */
  public void start() throws Exception {
    if (server == null) {
      createServer();
    }
    server.start();
  }

  /**
   * Wait for the server to exit, allowing existing requests to complete if graceful shutdown is
   * enabled and invoking the shutdown hook before returning.
   * @throws InterruptedException
   */
  public void join() throws InterruptedException {
    server.join();
    shutdownLatch.await();
  }

  /**
   * Request that the server shutdown.
   * @throws Exception
   */
  public void stop() throws Exception {
    server.stop();
  }

  /**
   * Shutdown hook that is invoked after the Jetty server has processed the shutdown request,
   * stopped accepting new connections, and tried to gracefully finish existing requests. At this
   * point it should be safe to clean up any resources used while processing requests.
   */
  public void onShutdown() {
  }
}

