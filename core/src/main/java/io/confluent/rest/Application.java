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

import io.confluent.rest.auth.AuthUtil;

import org.apache.kafka.common.config.ConfigException;
import org.eclipse.jetty.jaas.JAASLoginService;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.DefaultIdentityService;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.NetworkTrafficServerConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Slf4jRequestLog;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.jsr356.server.ServerContainer;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;
import org.glassfish.jersey.server.validation.ValidationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.ws.rs.core.Configurable;

import io.confluent.common.metrics.JmxReporter;
import io.confluent.common.metrics.MetricConfig;
import io.confluent.common.metrics.Metrics;
import io.confluent.common.metrics.MetricsReporter;
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
  protected Server server = null;
  protected CountDownLatch shutdownLatch = new CountDownLatch(1);
  protected Metrics metrics;
  protected final Slf4jRequestLog requestLog;
  protected final List<ApplicationContext> contexts = new ArrayList<>();
  protected final List<ResourceExtension> resourceExtensions = new ArrayList<>();
  protected SslContextFactory sslContextFactory;

  private static final Logger log = LoggerFactory.getLogger(Application.class);

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
    this.requestLog = new Slf4jRequestLog();
    this.requestLog.setLoggerName(config.getString(RestConfig.REQUEST_LOGGER_NAME_CONFIG));
    this.requestLog.setLogLatency(true);
    this.sslContextFactory = createSslContextFactory();
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
   */
  protected SslContextFactory getSslContextFactory() {
    return this.sslContextFactory;
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

  public void registerContextHandler(ApplicationContext context) {
    this.contexts.add(Objects.requireNonNull(context));
  }

  /**
   * Configure and create the server.
   */
  // CHECKSTYLE_RULES.OFF: MethodLength|CyclomaticComplexity|JavaNCSS|NPathComplexity
  public Server createServer() throws Exception {
    // CHECKSTYLE_RULES.ON: MethodLength|CyclomaticComplexity|JavaNCSS|NPathComplexity

    Map<String, String> combinedMetricsTags = new HashMap<>();
    for (ApplicationContext context : contexts) {
      Map<String, String> metrics = context.getMetricsTags();
      metrics.forEach((key, value) -> {
        String found = combinedMetricsTags.put(key, value);
        if (found != null) {
          throw new IllegalArgumentException("A metric named %s=%s already exists, "
                  + "can't register another one.".format(key, value));
        }
      });
    }

    Map<String, String> configuredTags = parseListToMap(
            getConfiguration().getList(RestConfig.METRICS_TAGS_CONFIG)
    );

    combinedMetricsTags.putAll(configuredTags);


    server = new Server() {
      @Override
      protected void doStop() throws Exception {
        super.doStop();
        Application.this.metrics.close();
        Application.this.doShutdown();
        Application.this.shutdownLatch.countDown();
      }
    };

    MBeanContainer mbContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
    server.addEventListener(mbContainer);
    server.addBean(mbContainer);

    MetricsListener metricsListener = new MetricsListener(metrics, "jetty", combinedMetricsTags);

    List<URI> listeners = parseListeners(config.getList(RestConfig.LISTENERS_CONFIG),
            config.getInt(RestConfig.PORT_CONFIG), Arrays.asList("http", "https"), "http");

    final HttpConfiguration httpConfiguration = new HttpConfiguration();
    httpConfiguration.setSendServerVersion(false);

    final HttpConnectionFactory httpConnectionFactory =
        new HttpConnectionFactory(httpConfiguration);

    for (URI listener : listeners) {
      log.info("Adding listener: " + listener.toString());
      NetworkTrafficServerConnector connector;
      if (listener.getScheme().equals("http")) {
        connector = new NetworkTrafficServerConnector(server, httpConnectionFactory);
      } else {
        connector = new NetworkTrafficServerConnector(server, httpConnectionFactory,
            sslContextFactory);
      }

      connector.addNetworkTrafficListener(metricsListener);
      connector.setPort(listener.getPort());
      connector.setHost(listener.getHost());

      connector.setIdleTimeout(config.getLong(RestConfig.IDLE_TIMEOUT_MS_CONFIG));

      server.addConnector(connector);
    }

    // Support mono context setup
    if (contexts.size() == 0) {
      ApplicationContext defaultContext = new DefaultContext();
      contexts.add(defaultContext);
      configureResourceExtensions(defaultContext.getResourceConfig());
      applyCustomConfiguration(defaultContext, REST_SERVLET_INITIALIZERS_CLASSES_CONFIG);
    }

    FilterHolder corsFilterHolder = null;
    if (AuthUtil.isCorsEnabled(config)) {
      String allowedOrigins = config.getString(RestConfig.ACCESS_CONTROL_ALLOW_ORIGIN_CONFIG);
      corsFilterHolder = new FilterHolder(CrossOriginFilter.class);
      corsFilterHolder.setName("cross-origin");
      corsFilterHolder.setInitParameter(
              CrossOriginFilter.ALLOWED_ORIGINS_PARAM, allowedOrigins

      );
      String allowedMethods = config.getString(RestConfig.ACCESS_CONTROL_ALLOW_METHODS);
      String allowedHeaders = config.getString(RestConfig.ACCESS_CONTROL_ALLOW_HEADERS);
      if (allowedMethods != null && !allowedMethods.trim().isEmpty()) {
        corsFilterHolder.setInitParameter(CrossOriginFilter.ALLOWED_METHODS_PARAM, allowedMethods);
      }
      if (allowedHeaders != null && !allowedHeaders.trim().isEmpty()) {
        corsFilterHolder.setInitParameter(CrossOriginFilter.ALLOWED_HEADERS_PARAM, allowedHeaders);
      }
      // handle preflight cors requests at the filter level, do not forward down the filter chain
      corsFilterHolder.setInitParameter(CrossOriginFilter.CHAIN_PREFLIGHT_PARAM, "false");
    }

    HandlerCollection handlers = new HandlerCollection();
    for (ApplicationContext context : contexts) {
      configureBaseApplication(context.getResourceConfig(), context.getMetricsTags());
      handlers.addHandler(context.configure(corsFilterHolder));
    }

    RequestLogHandler requestLogHandler = new RequestLogHandler();
    requestLogHandler.setRequestLog(requestLog);
    handlers.addHandler(requestLogHandler);

    /* Needed for graceful shutdown as per `setStopTimeout` documentation */
    StatisticsHandler statsHandler = new StatisticsHandler();
    statsHandler.setHandler(handlers);

    final ServletContextHandler webSocketContext =
        new ServletContextHandler(ServletContextHandler.SESSIONS);
    webSocketContext.setContextPath(
        config.getString(RestConfig.WEBSOCKET_PATH_PREFIX_CONFIG)
    );

    configureSecurityHandler(webSocketContext);

    final ContextHandlerCollection contexts = new ContextHandlerCollection();
    contexts.setHandlers(new Handler[] {
        statsHandler,
        webSocketContext
    });

    server.setHandler(wrapWithGzipHandler(contexts));

    ServerContainer container =
        WebSocketServerContainerInitializer.configureContext(webSocketContext);
    registerWebSocketEndpoints(container);

    configureWebSocketPostResourceHandling(webSocketContext);

    applyCustomConfiguration(webSocketContext, WEBSOCKET_SERVLET_INITIALIZERS_CLASSES_CONFIG);

    int gracefulShutdownMs = getConfiguration().getInt(RestConfig.SHUTDOWN_GRACEFUL_MS_CONFIG);
    if (gracefulShutdownMs > 0) {
      server.setStopTimeout(gracefulShutdownMs);
    }
    server.setStopAtShutdown(true);

    return server;
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

  static void configureSecurityHandler(ServletContextHandler context, RestConfig config) {
    String authMethod = config.getString(RestConfig.AUTHENTICATION_METHOD_CONFIG);
    if (enableBasicAuth(authMethod)) {
      context.setSecurityHandler(createBasicSecurityHandler(config));
    } else if (enableBearerAuth(authMethod)) {
      context.setSecurityHandler(createBearerSecurityHandler(config));
    }
  }

  protected void configureSecurityHandler(ServletContextHandler context) {
    configureSecurityHandler(context, this.config);
  }

  private SslContextFactory createSslContextFactory() {
    SslContextFactory sslContextFactory = new SslContextFactory.Server();
    if (!config.getString(RestConfig.SSL_KEYSTORE_LOCATION_CONFIG).isEmpty()) {
      sslContextFactory.setKeyStorePath(
          config.getString(RestConfig.SSL_KEYSTORE_LOCATION_CONFIG)
      );
      sslContextFactory.setKeyStorePassword(
          config.getPassword(RestConfig.SSL_KEYSTORE_PASSWORD_CONFIG).value()
      );
      sslContextFactory.setKeyManagerPassword(
          config.getPassword(RestConfig.SSL_KEY_PASSWORD_CONFIG).value()
      );
      sslContextFactory.setKeyStoreType(
          config.getString(RestConfig.SSL_KEYSTORE_TYPE_CONFIG)
      );

      if (!config.getString(RestConfig.SSL_KEYMANAGER_ALGORITHM_CONFIG).isEmpty()) {
        sslContextFactory.setKeyManagerFactoryAlgorithm(
            config.getString(RestConfig.SSL_KEYMANAGER_ALGORITHM_CONFIG));
      }
    }

    configureClientAuth(sslContextFactory);

    List<String> enabledProtocols = config.getList(RestConfig.SSL_ENABLED_PROTOCOLS_CONFIG);
    if (!enabledProtocols.isEmpty()) {
      sslContextFactory.setIncludeProtocols(enabledProtocols.toArray(new String[0]));
    }

    List<String> cipherSuites = config.getList(RestConfig.SSL_CIPHER_SUITES_CONFIG);
    if (!cipherSuites.isEmpty()) {
      sslContextFactory.setIncludeCipherSuites(cipherSuites.toArray(new String[0]));
    }

    sslContextFactory.setEndpointIdentificationAlgorithm(
        config.getString(RestConfig.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG));

    if (!config.getString(RestConfig.SSL_TRUSTSTORE_LOCATION_CONFIG).isEmpty()) {
      sslContextFactory.setTrustStorePath(
          config.getString(RestConfig.SSL_TRUSTSTORE_LOCATION_CONFIG)
      );
      sslContextFactory.setTrustStorePassword(
          config.getPassword(RestConfig.SSL_TRUSTSTORE_PASSWORD_CONFIG).value()
      );
      sslContextFactory.setTrustStoreType(
          config.getString(RestConfig.SSL_TRUSTSTORE_TYPE_CONFIG)
      );

      if (!config.getString(RestConfig.SSL_TRUSTMANAGER_ALGORITHM_CONFIG).isEmpty()) {
        sslContextFactory.setTrustManagerFactoryAlgorithm(
            config.getString(RestConfig.SSL_TRUSTMANAGER_ALGORITHM_CONFIG)
        );
      }
    }

    sslContextFactory.setProtocol(config.getString(RestConfig.SSL_PROTOCOL_CONFIG));
    if (!config.getString(RestConfig.SSL_PROVIDER_CONFIG).isEmpty()) {
      sslContextFactory.setProtocol(config.getString(RestConfig.SSL_PROVIDER_CONFIG));
    }

    sslContextFactory.setRenegotiationAllowed(false);

    return sslContextFactory;
  }

  @SuppressWarnings("deprecation")
  private void configureClientAuth(SslContextFactory sslContextFactory) {
    String clientAuthentication = config.getString(RestConfig.SSL_CLIENT_AUTHENTICATION_CONFIG);

    if (config.originals().containsKey(RestConfig.SSL_CLIENT_AUTH_CONFIG)) {
      if (config.originals().containsKey(RestConfig.SSL_CLIENT_AUTHENTICATION_CONFIG)) {
        log.warn(
            "The {} configuration is deprecated. Since a value has been supplied for the {} "
                + "configuration, that will be used instead",
            RestConfig.SSL_CLIENT_AUTH_CONFIG,
            RestConfig.SSL_CLIENT_AUTHENTICATION_CONFIG
        );
      } else {
        log.warn(
            "The configuration {} is deprecated and should be replaced with {}",
            RestConfig.SSL_CLIENT_AUTH_CONFIG,
            RestConfig.SSL_CLIENT_AUTHENTICATION_CONFIG
        );
        clientAuthentication = config.getBoolean(RestConfig.SSL_CLIENT_AUTH_CONFIG)
            ? RestConfig.SSL_CLIENT_AUTHENTICATION_REQUIRED
            : RestConfig.SSL_CLIENT_AUTHENTICATION_NONE;
      }
    }

    switch (clientAuthentication) {
      case RestConfig.SSL_CLIENT_AUTHENTICATION_REQUIRED:
        sslContextFactory.setNeedClientAuth(true);
        break;
      case RestConfig.SSL_CLIENT_AUTHENTICATION_REQUESTED:
        sslContextFactory.setWantClientAuth(true);
        break;
      case RestConfig.SSL_CLIENT_AUTHENTICATION_NONE:
        break;
      default:
        throw new ConfigException(
            "Unexpected value for {} configuration: {}",
            RestConfig.SSL_CLIENT_AUTHENTICATION_CONFIG,
            clientAuthentication
        );
    }
  }

  public Handler wrapWithGzipHandler(Handler handler) {
    if (config.getBoolean(RestConfig.ENABLE_GZIP_COMPRESSION_CONFIG)) {
      GzipHandler gzip = new GzipHandler();
      gzip.setIncludedMethods("GET", "POST");
      gzip.setHandler(handler);
      return gzip;
    }
    return handler;
  }

  /**
   * Used to register any websocket endpoints that will live under the path configured via
   * {@link io.confluent.rest.RestConfig#WEBSOCKET_PATH_PREFIX_CONFIG}
   */
  protected void registerWebSocketEndpoints(ServerContainer container) {

  }

  static boolean enableBasicAuth(String authMethod) {
    return RestConfig.AUTHENTICATION_METHOD_BASIC.equals(authMethod);
  }

  static boolean enableBearerAuth(String authMethod) {
    return RestConfig.AUTHENTICATION_METHOD_BEARER.equals(authMethod);
  }

  static LoginAuthenticator createAuthenticator(RestConfig config) {
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

  protected LoginAuthenticator createAuthenticator() {
    return createAuthenticator(this.config);
  }

  static LoginService createLoginService(RestConfig config) {
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

  protected LoginService createLoginService() {
    return createLoginService(this.config);
  }

  static IdentityService createIdentityService(RestConfig config) {
    final String method = config.getString(RestConfig.AUTHENTICATION_METHOD_CONFIG);
    if (enableBasicAuth(method) || enableBearerAuth(method)) {
      return new DefaultIdentityService();
    }
    return null;
  }

  protected IdentityService createIdentityService() {
    return createIdentityService(this.config);
  }

  static ConstraintSecurityHandler createBasicSecurityHandler(RestConfig config) {
    return createSecurityHandler(config);
  }

  protected ConstraintSecurityHandler createBasicSecurityHandler() {
    return createBasicSecurityHandler(this.config);
  }

  static ConstraintSecurityHandler createBearerSecurityHandler(RestConfig config) {
    return createSecurityHandler(config);
  }

  protected ConstraintSecurityHandler createBearerSecurityHandler() {
    return createSecurityHandler(this.config);
  }

  static ConstraintSecurityHandler createSecurityHandler(RestConfig config) {
    final String realm = config.getString(RestConfig.AUTHENTICATION_REALM_CONFIG);

    final ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
    securityHandler.addConstraintMapping(createGlobalAuthConstraint(config));
    securityHandler.setAuthenticator(createAuthenticator(config));
    securityHandler.setLoginService(createLoginService(config));
    securityHandler.setIdentityService(createIdentityService(config));
    securityHandler.setRealmName(realm);
    AuthUtil.createUnsecuredConstraints(config)
            .forEach(securityHandler::addConstraintMapping);

    return securityHandler;
  }

  protected ConstraintSecurityHandler createSecurityHandler() {
    return createSecurityHandler(this.config);
  }

  static ConstraintMapping createGlobalAuthConstraint(RestConfig config) {
    return AuthUtil.createGlobalAuthConstraint(config);
  }

  protected ConstraintMapping createGlobalAuthConstraint() {
    return createGlobalAuthConstraint(this.config);
  }

  // TODO: delete deprecatedPort parameter when `PORT_CONFIG` is deprecated.
  // It's only used to support the deprecated configuration.
  public static List<URI> parseListeners(
      List<String> listenersConfig,
      int deprecatedPort,
      List<String> supportedSchemes,
      String defaultScheme
  ) {
    // handle deprecated case, using PORT_CONFIG.
    // TODO: remove this when `PORT_CONFIG` is deprecated, because LISTENER_CONFIG
    // will have a default value which includes the default port.
    if (listenersConfig.isEmpty() || listenersConfig.get(0).isEmpty()) {
      log.warn(
          "DEPRECATION warning: `listeners` configuration is not configured. "
          + "Falling back to the deprecated `port` configuration."
      );
      listenersConfig = new ArrayList<String>(1);
      listenersConfig.add(defaultScheme + "://0.0.0.0:" + deprecatedPort);
    }

    List<URI> listeners = new ArrayList<URI>(listenersConfig.size());
    for (String listenerStr : listenersConfig) {
      URI uri;
      try {
        uri = new URI(listenerStr);
      } catch (URISyntaxException use) {
        throw new ConfigException(
            "Could not parse a listener URI from the `listener` configuration option."
        );
      }
      String scheme = uri.getScheme();
      if (scheme == null) {
        throw new ConfigException(
            "Found a listener without a scheme. All listeners must have a scheme. The "
            + "listener without a scheme is: " + listenerStr
        );
      }
      if (uri.getPort() == -1) {
        throw new ConfigException(
            "Found a listener without a port. All listeners must have a port. The "
            + "listener without a port is: " + listenerStr
        );
      }
      if (!supportedSchemes.contains(scheme)) {
        log.warn(
            "Found a listener with an unsupported scheme (supported: {}). Ignoring listener '{}'",
            supportedSchemes,
            listenerStr
        );
      } else {
        listeners.add(uri);
      }
    }

    if (listeners.isEmpty()) {
      throw new ConfigException("No listeners are configured. Must have at least one listener.");
    }

    return listeners;
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
   * @throws InterruptedException If the internal threadpool fails to stop
   */
  public void join() throws InterruptedException {
    server.join();
    shutdownLatch.await();
  }

  /**
   * Request that the server shutdown.
   * @throws Exception If the application fails to stop
   */
  public void stop() throws Exception {
    server.stop();
  }

  private void doShutdown() {
    resourceExtensions.forEach(ext -> {
      try {
        ext.close();
      } catch (final IOException e) {
        log.error("Error closing the extension resource. ext:" + ext, e);
      }
    });

    onShutdown();
  }

  /**
   * Shutdown hook that is invoked after the Jetty server has processed the shutdown request,
   * stopped accepting new connections, and tried to gracefully finish existing requests. At this
   * point it should be safe to clean up any resources used while processing requests.
   */
  public void onShutdown() {
  }

  /**
   * By default the RestUtils will expose one context the DefaultContext
   */
  private final class DefaultContext extends ApplicationContext<T> {

    DefaultContext() {
      super(Application.this.config);
    }

    @Override
    public void setupResources(Configurable<?> config, T appConfig) {
      Application.this.setupResources(config, appConfig);
    }

    @Override
    protected ResourceCollection getStaticResources() {
      return Application.this.getStaticResources();
    }

    @Override
    protected void configureSecurityHandler() {
      Application.this.configureSecurityHandler(this);
    }

    @Override
    protected void configurePreResourceHandling() {
      Application.this.configurePreResourceHandling(this);
    }

    @Override
    protected void configurePostResourceHandling() {
      Application.this.configurePostResourceHandling(this);
    }

    @Override
    public Map<String,String> getMetricsTags() {
      return Application.this.getMetricsTags();
    }
  }
}

