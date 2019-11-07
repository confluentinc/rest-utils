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

import org.apache.kafka.common.config.ConfigException;
import org.eclipse.jetty.jaas.JAASLoginService;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.DefaultIdentityService;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Slf4jRequestLog;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.jsr356.server.ServerContainer;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;
import org.glassfish.jersey.server.validation.ValidationFeature;
import org.glassfish.jersey.servlet.ServletContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.ws.rs.core.Configurable;

import io.confluent.common.metrics.JmxReporter;
import io.confluent.common.metrics.MetricConfig;
import io.confluent.common.metrics.Metrics;
import io.confluent.common.metrics.MetricsReporter;
import io.confluent.rest.auth.AuthUtil;
import io.confluent.rest.exceptions.ConstraintViolationExceptionMapper;
import io.confluent.rest.exceptions.GenericExceptionMapper;
import io.confluent.rest.exceptions.WebApplicationExceptionMapper;
import io.confluent.rest.extension.ResourceExtension;
import io.confluent.rest.metrics.MetricsResourceMethodApplicationListener;
import io.confluent.rest.validation.JacksonMessageBodyProvider;

import static io.confluent.rest.RestConfig.REST_SERVLET_INITIALIZERS_CLASSES_CONFIG;
import static io.confluent.rest.RestConfig.WEBSOCKET_SERVLET_INITIALIZERS_CLASSES_CONFIG;

/**
 * A REST application. Extend this class and implement setupResources() to register REST
 * resources with the JAX-RS server. Use createServer() to get a fully-configured, ready to run
 * Jetty server.
 */
// CHECKSTYLE_RULES.OFF: ClassDataAbstractionCoupling
public abstract class Application<T extends RestConfig> {
  // CHECKSTYLE_RULES.ON: ClassDataAbstractionCoupling
  protected T config;
  private final String path;

  protected ApplicationServer server;
  protected Metrics metrics;
  protected final Slf4jRequestLog requestLog;

  protected CountDownLatch shutdownLatch = new CountDownLatch(1);
  protected final List<ResourceExtension> resourceExtensions = new ArrayList<>();

  private static final Logger log = LoggerFactory.getLogger(Application.class);

  public Application(T config) {
    this(config, "/");
  }

  public Application(T config, String path) {
    this.config = config;
    this.path = Objects.requireNonNull(path);;

    MetricConfig metricConfig = new MetricConfig()
        .samples(config.getInt(RestConfig.METRICS_NUM_SAMPLES_CONFIG))
        .timeWindow(config.getLong(RestConfig.METRICS_SAMPLE_WINDOW_MS_CONFIG),
                    TimeUnit.MILLISECONDS);
    List<MetricsReporter> reporters =
        config.getConfiguredInstances(RestConfig.METRICS_REPORTER_CLASSES_CONFIG,
                                      MetricsReporter.class);
    reporters.add(new JmxReporter(config.getString(RestConfig.METRICS_JMX_PREFIX_CONFIG)));
    this.metrics = new Metrics(metricConfig, reporters, config.getTime());

    this.getMetricsTags().putAll(
            parseListToMap(config.getList(RestConfig.METRICS_TAGS_CONFIG)));

    this.requestLog = new Slf4jRequestLog();
    this.requestLog.setLoggerName(config.getString(RestConfig.REQUEST_LOGGER_NAME_CONFIG));
    this.requestLog.setLogLatency(true);
  }

  /**
   * Register resources or additional Providers, ExceptionMappers, and other JAX-RS components with
   * the Jersey application. This, combined with your Configuration class, is where you can
   * customize the behavior of the application.
   */
  public abstract void setupResources(Configurable<?> config, T appConfig);

  /**
   * Returns a list of static resources to serve using the default servlet.
   *
   * <p>For example, static files can be served from class loader resources by returning
   * {@code
   * new ResourceCollection(Resource.newClassPathResource("static"));
   * }
   *
   * <p>For those resources to get served, it is necessary to add a static resources property to the
   * config in @link{{@link #setupResources(Configurable, RestConfig)}}, e.g. using something like
   * {@code
   * config.property(ServletProperties.FILTER_STATIC_CONTENT_REGEX, "/(static/.*|.*\\.html|)");
   * }
   *
   * @return static resource collection
   */
  protected ResourceCollection getStaticResources() {
    return null;
  }

  /**
   * add any servlet filters that should be called before resource handling
   */
  protected void configurePreResourceHandling(ServletContextHandler context) {}

  /**
   * expose SslContextFactory
   * @deprecated Use {@link ApplicationServer#getSslContextFactory} instead.
   */
  @Deprecated
  protected SslContextFactory getSslContextFactory() {
    return server.getSslContextFactory();
  }

  /**
   * add any servlet filters that should be called after resource
   * handling but before falling back to the default servlet
   */
  protected void configurePostResourceHandling(ServletContextHandler context) {}

  /**
   * add any servlet filters that should be called after resource
   * handling but before falling back to the default servlet
   */
  protected void configureWebSocketPostResourceHandling(ServletContextHandler context) {}

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
   *
   * @deprecated Use {@link ApplicationServer#registerApplication(Application)} instead.
   */
  // CHECKSTYLE_RULES.OFF: MethodLength|CyclomaticComplexity|JavaNCSS|NPathComplexity
  @Deprecated
  public Server createServer() throws ServletException {
    // CHECKSTYLE_RULES.ON: MethodLength|CyclomaticComplexity|JavaNCSS|NPathComplexity
    if (server == null) {
      server = new ApplicationServer(config);
      server.registerApplication(this);
    }
    return server;
  }

  final void setServer(ApplicationServer server) {
    this.server = Objects.requireNonNull(server);
  }

  final ApplicationServer getServer() {
    return server;
  }

  final Handler configureHandler() {
    ResourceConfig resourceConfig = new ResourceConfig();
    configureBaseApplication(resourceConfig, getMetricsTags());
    configureResourceExtensions(resourceConfig);
    setupResources(resourceConfig, getConfiguration());

    // Configure the servlet container
    ServletContainer servletContainer = new ServletContainer(resourceConfig);
    final FilterHolder servletHolder = new FilterHolder(servletContainer);

    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.setContextPath(path);

    ServletHolder defaultHolder = new ServletHolder("default", DefaultServlet.class);
    defaultHolder.setInitParameter("dirAllowed", "false");

    ResourceCollection staticResources = getStaticResources();
    if (staticResources != null) {
      context.setBaseResource(staticResources);
    }

    configureSecurityHandler(context);

    if (isCorsEnabled()) {
      String allowedOrigins = config.getString(RestConfig.ACCESS_CONTROL_ALLOW_ORIGIN_CONFIG);
      FilterHolder filterHolder = new FilterHolder(CrossOriginFilter.class);
      filterHolder.setName("cross-origin");
      filterHolder.setInitParameter(
              CrossOriginFilter.ALLOWED_ORIGINS_PARAM, allowedOrigins

      );
      String allowedMethods = config.getString(RestConfig.ACCESS_CONTROL_ALLOW_METHODS);
      String allowedHeaders = config.getString(RestConfig.ACCESS_CONTROL_ALLOW_HEADERS);
      if (allowedMethods != null && !allowedMethods.trim().isEmpty()) {
        filterHolder.setInitParameter(CrossOriginFilter.ALLOWED_METHODS_PARAM, allowedMethods);
      }
      if (allowedHeaders != null && !allowedHeaders.trim().isEmpty()) {
        filterHolder.setInitParameter(CrossOriginFilter.ALLOWED_HEADERS_PARAM, allowedHeaders);
      }
      // handle preflight cors requests at the filter level, do not forward down the filter chain
      filterHolder.setInitParameter(CrossOriginFilter.CHAIN_PREFLIGHT_PARAM, "false");
      context.addFilter(filterHolder, "/*", EnumSet.of(DispatcherType.REQUEST));
    }

    configurePreResourceHandling(context);
    context.addFilter(servletHolder, "/*", null);
    configurePostResourceHandling(context);
    context.addServlet(defaultHolder, "/*");

    applyCustomConfiguration(context, REST_SERVLET_INITIALIZERS_CLASSES_CONFIG);

    RequestLogHandler requestLogHandler = new RequestLogHandler();
    requestLogHandler.setRequestLog(requestLog);

    HandlerCollection handlers = new HandlerCollection();
    handlers.setHandlers(new Handler[]{context, requestLogHandler});

    return handlers;
  }

  final Handler configureWebSocketHandler() throws ServletException {
    final ServletContextHandler webSocketContext =
            new ServletContextHandler(ServletContextHandler.SESSIONS);
    webSocketContext.setContextPath(
            config.getString(RestConfig.WEBSOCKET_PATH_PREFIX_CONFIG)
    );

    configureSecurityHandler(webSocketContext);

    ServerContainer container =
            WebSocketServerContainerInitializer.configureContext(webSocketContext);
    registerWebSocketEndpoints(container);

    configureWebSocketPostResourceHandling(webSocketContext);
    applyCustomConfiguration(webSocketContext, WEBSOCKET_SERVLET_INITIALIZERS_CLASSES_CONFIG);

    return webSocketContext;
  }

  // This is copied from the old MAP implementation from cp ConfigDef.Type.MAP
  public static Map<String, String> parseListToMap(List<String> list) {
    Map<String, String> configuredTags = new HashMap<>();
    for (String entry : list) {
      String[] keyValue = entry.split("\\s*:\\s*", -1);
      if (keyValue.length != 2) {
        throw new ConfigException("Map entry should be of form <key>:<value");
      }
      configuredTags.put(keyValue[0], keyValue[1]);
    }
    return configuredTags;
  }

  private boolean isCorsEnabled() {
    return AuthUtil.isCorsEnabled(config);
  }

  @SuppressWarnings("unchecked")
  private void configureResourceExtensions(final ResourceConfig resourceConfig) {
    resourceExtensions.addAll(getConfiguration().getConfiguredInstances(
        RestConfig.RESOURCE_EXTENSION_CLASSES_CONFIG, ResourceExtension.class));

    resourceExtensions.forEach(ext -> {
      try {
        ext.register(resourceConfig, this);
      } catch (Exception e) {
        throw new RuntimeException("Exception throw by resource extension. ext:" + ext, e);
      }
    });
  }

  @SuppressWarnings("unchecked")
  private void applyCustomConfiguration(
      ServletContextHandler context,
      String initializerConfigName) {
    getConfiguration()
        .getConfiguredInstances(initializerConfigName, Consumer.class)
        .forEach(initializer -> {
          try {
            initializer.accept(context);
          } catch (final Exception e) {
            throw new RuntimeException("Exception from custom initializer. "
                + "config:" + initializerConfigName + ", initializer" + initializer, e);
          }
        });
  }

  protected void configureSecurityHandler(ServletContextHandler context) {
    String authMethod = config.getString(RestConfig.AUTHENTICATION_METHOD_CONFIG);
    if (enableBasicAuth(authMethod)) {
      context.setSecurityHandler(createBasicSecurityHandler());
    } else if (enableBearerAuth(authMethod)) {
      context.setSecurityHandler(createBearerSecurityHandler());
    }
  }

  public Handler wrapWithGzipHandler(Handler handler) {
    return ApplicationServer.wrapWithGzipHandler(config, handler);
  }

  /**
   * TODO: delete deprecatedPort parameter when `PORT_CONFIG` is deprecated.
   * Helper function used to support the deprecated configuration.
   *
   * @deprecated This function will be removed with {@link RestConfig#PORT_CONFIG}
   */
  @Deprecated
  public static List<URI> parseListeners(
          List<String> listenersConfig,
          int deprecatedPort,
          List<String> supportedSchemes,
          String defaultScheme
  ) {
    return ApplicationServer.parseListeners(
            listenersConfig, deprecatedPort, supportedSchemes, defaultScheme);
  }

  /**
   * Used to register any websocket endpoints that will live under the path configured via
   * {@link io.confluent.rest.RestConfig#WEBSOCKET_PATH_PREFIX_CONFIG}
   */
  protected void registerWebSocketEndpoints(ServerContainer container) { }

  static boolean enableBasicAuth(String authMethod) {
    return RestConfig.AUTHENTICATION_METHOD_BASIC.equals(authMethod);
  }

  static boolean enableBearerAuth(String authMethod) {
    return RestConfig.AUTHENTICATION_METHOD_BEARER.equals(authMethod);
  }

  protected LoginAuthenticator createAuthenticator() {
    final String method = config.getString(RestConfig.AUTHENTICATION_METHOD_CONFIG);
    if (enableBasicAuth(method)) {
      return new BasicAuthenticator();
    } else if (enableBearerAuth(method)) {
      throw new UnsupportedOperationException(
              "Must implement Application.createAuthenticator() when using '"
                      + RestConfig.AUTHENTICATION_METHOD_CONFIG + "="
                      + RestConfig.AUTHENTICATION_METHOD_BEARER + "'."
      );
    }
    return null;
  }

  protected LoginService createLoginService() {
    final String realm = config.getString(RestConfig.AUTHENTICATION_REALM_CONFIG);
    final String method = config.getString(RestConfig.AUTHENTICATION_METHOD_CONFIG);
    if (enableBasicAuth(method)) {
      return new JAASLoginService(realm);
    } else if (enableBearerAuth(method)) {
      throw new UnsupportedOperationException(
              "Must implement Application.createLoginService() when using '"
                      + RestConfig.AUTHENTICATION_METHOD_CONFIG + "="
                      + RestConfig.AUTHENTICATION_METHOD_BEARER + "'."
      );
    }
    return null;
  }

  protected IdentityService createIdentityService() {
    final String method = config.getString(RestConfig.AUTHENTICATION_METHOD_CONFIG);
    if (enableBasicAuth(method) || enableBearerAuth(method)) {
      return new DefaultIdentityService();
    }
    return null;
  }

  protected ConstraintSecurityHandler createBasicSecurityHandler() {
    return createSecurityHandler();
  }

  protected ConstraintSecurityHandler createBearerSecurityHandler() {
    return createSecurityHandler();
  }

  protected ConstraintSecurityHandler createSecurityHandler() {
    final String realm = config.getString(RestConfig.AUTHENTICATION_REALM_CONFIG);

    final ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
    securityHandler.addConstraintMapping(createGlobalAuthConstraint());
    securityHandler.setAuthenticator(createAuthenticator());
    securityHandler.setLoginService(createLoginService());
    securityHandler.setIdentityService(createIdentityService());
    securityHandler.setRealmName(realm);
    AuthUtil.createUnsecuredConstraints(config)
            .forEach(securityHandler::addConstraintMapping);

    return securityHandler;
  }

  protected ConstraintMapping createGlobalAuthConstraint() {
    return AuthUtil.createGlobalAuthConstraint(config);
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
    T restConfig = getConfiguration();

    registerJsonProvider(config, restConfig, true);
    registerFeatures(config, restConfig);
    registerExceptionMappers(config, restConfig);

    config.register(new MetricsResourceMethodApplicationListener(metrics, "jersey",
                                                                 metricTags, restConfig.getTime()));

    config.property(ServerProperties.BV_SEND_ERROR_IN_RESPONSE, true);
    config.property(ServerProperties.WADL_FEATURE_DISABLE, true);
  }

  /**
   * Register a body provider and optional exception mapper for (de)serializing JSON in
   * request/response entities.
   * @param config The config to register the provider with
   * @param restConfig The application's configuration
   * @param registerExceptionMapper Whether or not to register an additional exception mapper for
   *                                handling errors in (de)serialization
   */
  protected void registerJsonProvider(
      Configurable<?> config,
      T restConfig,
      boolean registerExceptionMapper
  ) {
    ObjectMapper jsonMapper = getJsonMapper();
    JacksonMessageBodyProvider jsonProvider = new JacksonMessageBodyProvider(jsonMapper);
    config.register(jsonProvider);
    if (registerExceptionMapper) {
      config.register(JsonParseExceptionMapper.class);
    }
  }

  /**
   * Register server features
   * @param config The config to register the features with
   * @param restConfig The application's configuration
   */
  protected void registerFeatures(Configurable<?> config, T restConfig) {
    config.register(ValidationFeature.class);
  }

  /**
   * Register handlers for translating exceptions into responses.
   * @param config The config to register the mappers with
   * @param restConfig The application's configuration
   */
  protected void registerExceptionMappers(Configurable<?> config, T restConfig) {
    config.register(ConstraintViolationExceptionMapper.class);
    config.register(new WebApplicationExceptionMapper(restConfig));
    config.register(new GenericExceptionMapper(restConfig));
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
   * @throws Exception If the application fails to start
   *
   * @deprecated Use {@link ApplicationServer#start()} instead.
   */
  @Deprecated
  public void start() throws Exception {
    createServer();
    server.start();
  }

  /**
   * Wait for the server to exit, allowing existing requests to complete if graceful shutdown is
   * enabled and invoking the shutdown hook before returning.
   * @throws InterruptedException If the internal threadpool fails to stop
   *
   * @deprecated Use {@link ApplicationServer#join()} instead.
   */
  @Deprecated
  public void join() throws InterruptedException {
    server.join();
    shutdownLatch.await();
  }

  /**
   * Request that the server shutdown.
   * @throws Exception If the application fails to stop
   *
   * @deprecated Use {@link ApplicationServer#stop()} instead.
   */
  @Deprecated
  public void stop() throws Exception {
    server.stop();
  }

  final void doShutdown() {
    resourceExtensions.forEach(ext -> {
      try {
        ext.close();
      } catch (final IOException e) {
        log.error("Error closing the extension resource. ext:" + ext, e);
      }
    });

    shutdownLatch.countDown();
    onShutdown();
  }

  /**
   * Shutdown hook that is invoked after the Jetty server has processed the shutdown request,
   * stopped accepting new connections, and tried to gracefully finish existing requests. At this
   * point it should be safe to clean up any resources used while processing requests.
   */
  public void onShutdown() {}
}
