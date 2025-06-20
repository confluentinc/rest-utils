/*
 * Copyright 2014 - 2022 Confluent Inc.
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

import static io.confluent.rest.RestConfig.REST_SERVLET_INITIALIZERS_CLASSES_CONFIG;
import static io.confluent.rest.RestConfig.WEBSOCKET_SERVLET_INITIALIZERS_CLASSES_CONFIG;
import static java.util.Collections.emptyMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.rest.auth.AuthUtil;
import io.confluent.rest.exceptions.ConstraintViolationExceptionMapper;
import io.confluent.rest.exceptions.GenericExceptionMapper;
import io.confluent.rest.exceptions.WebApplicationExceptionMapper;
import io.confluent.rest.exceptions.JsonMappingExceptionMapper;
import io.confluent.rest.exceptions.JsonParseExceptionMapper;
import io.confluent.rest.extension.ResourceExtension;
import io.confluent.rest.filters.CsrfTokenProtectionFilter;
import io.confluent.rest.handlers.ExpectedSniHandler;
import io.confluent.rest.handlers.SniHandler;
import io.confluent.rest.handlers.PrefixSniHandler;
import io.confluent.rest.jetty.DoSFilter;
import io.confluent.rest.metrics.Jetty429MetricsDosFilterListener;
import io.confluent.rest.metrics.JettyRequestMetricsFilter;
import io.confluent.rest.metrics.MetricsResourceMethodApplicationListener;
import io.confluent.rest.validation.JacksonMessageBodyProvider;
import io.spiffe.workloadapi.X509Source;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.ws.rs.core.Configurable;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.metrics.JmxReporter;
import org.apache.kafka.common.metrics.MetricConfig;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.MetricsReporter;
import org.eclipse.jetty.security.jaas.JAASLoginService;
import org.eclipse.jetty.ee10.servlet.security.ConstraintMapping;
import org.eclipse.jetty.ee10.servlet.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.DefaultIdentityService;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Slf4jRequestLogWriter;
import org.eclipse.jetty.server.Handler.Sequence;
import org.eclipse.jetty.ee10.servlet.DefaultServlet;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.servlets.CrossOriginFilter;
import org.eclipse.jetty.ee10.servlets.HeaderFilter;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.StringUtil;
import jakarta.websocket.server.ServerContainer;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;
import org.glassfish.jersey.server.validation.ValidationFeature;
import org.glassfish.jersey.servlet.ServletContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
  private final String listenerName;

  protected ApplicationServer<?> server;
  private final X509Source x509Source;

  protected Metrics metrics;
  protected final RequestLog requestLog;
  protected final DoSFilter.Listener jetty429MetricsListener;

  protected CountDownLatch shutdownLatch = new CountDownLatch(1);
  @SuppressWarnings("unchecked")
  protected final List<ResourceExtension> resourceExtensions = new ArrayList<>();

  private static final Logger log = LoggerFactory.getLogger(Application.class);

  private final List<DoSFilter.Listener> globalDosfilterListeners = new ArrayList<>();

  private final List<DoSFilter.Listener> nonGlobalDosfilterListeners = new ArrayList<>();

  public Application(T config) {
    this(config, "/", null, null, null);
  }

  public Application(T config,  X509Source x509Source) {
    this(config, "/", null, null, x509Source);
  }

  public Application(T config, String path) {
    this(config, path, null, null, null);
  }

  public Application(T config, String path, X509Source x509Source) {
    this(config, path, null, null, x509Source);
  }

  public Application(T config, String path, String listenerName) {
    this(config, path, listenerName, null, null);
  }

  public Application(T config, String path, String listenerName, X509Source x509Source) {
    this(config, path, listenerName, null, x509Source);
  }

  public Application(T config, String path, String listenerName, RequestLog customRequestLog) {
    this(config, path, listenerName, customRequestLog, null);
  }

  public Application(T config, String path, String listenerName,
                     RequestLog customRequestLog, X509Source x509Source) {
    this.config = config;
    this.path = Objects.requireNonNull(path);
    this.listenerName = listenerName;
    this.x509Source = x509Source;

    this.metrics = configureMetrics();
    this.getMetricsTags().putAll(config.getMap(RestConfig.METRICS_TAGS_CONFIG));
    jetty429MetricsListener = new Jetty429MetricsDosFilterListener(this.metrics,
        this.getMetricsTags(),
        config.getString(RestConfig.METRICS_JMX_PREFIX_CONFIG));

    if (customRequestLog == null) {
      Slf4jRequestLogWriter logWriter = new Slf4jRequestLogWriter();
      logWriter.setLoggerName(config.getString(RestConfig.REQUEST_LOGGER_NAME_CONFIG));
      // %{ms}T logs request time in milliseconds
      requestLog = new CustomRequestLog(logWriter, requestLogFormat());
    } else {
      requestLog = customRequestLog;
    }
  }

  /**
   * Add DosFilter.listener to be called with all other listeners for global-dosfilter. This should
   * be called before configureHandler() is called.
   */
  public void addGlobalDosfilterListener(
      DoSFilter.Listener listener) {
    this.globalDosfilterListeners.add(Objects.requireNonNull(listener));
  }

  /**
   * Add DosFilter.listener to be called with all other listeners for non-global-dosfilter.This
   * should be called before configureHandler() is called.
   */
  public void addNonGlobalDosfilterListener(
      DoSFilter.Listener listener) {
    this.nonGlobalDosfilterListeners.add(Objects.requireNonNull(listener));
  }

  protected String requestLogFormat() {
    return CustomRequestLog.EXTENDED_NCSA_FORMAT + " %{ms}T";
  }

  public final String getPath() {
    return path;
  }

  public final String getListenerName() {
    return listenerName;
  }

  /**
   * Configure Application MetricReport instances.
   */
  private List<MetricsReporter> configureMetricsReporters(T appConfig) {
    List<MetricsReporter> reporters =
            appConfig.getConfiguredInstances(
                    RestConfig.METRICS_REPORTER_CLASSES_CONFIG,
                    MetricsReporter.class);
    reporters.add(new JmxReporter());

    // Treat prefixed configs as overrides to originals
    Map<String, Object> reporterConfigs = appConfig.originals();
    reporterConfigs.putAll(appConfig.metricsReporterConfig());

    reporters.forEach(r -> r.configure(reporterConfigs));
    return reporters;
  }


  /**
   * Configure Application Metrics instance.
   */
  protected Metrics configureMetrics() {
    T appConfig = getConfiguration();

    MetricConfig metricConfig = new MetricConfig()
            .samples(appConfig.getInt(RestConfig.METRICS_NUM_SAMPLES_CONFIG))
            .timeWindow(appConfig.getLong(RestConfig.METRICS_SAMPLE_WINDOW_MS_CONFIG),
                    TimeUnit.MILLISECONDS);

    return new Metrics(metricConfig,
            configureMetricsReporters(appConfig),
            appConfig.getTime(),
            appConfig.getMetricsContext());
  }

  /**
   * Returns {@link Metrics} object
   */
  public final Metrics getMetrics() {
    return this.metrics;
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
  protected Collection<Resource> getStaticResources() {
    return null;
  }

  /**
   * add any servlet filters that should be called before resource handling
   */
  protected void configurePreResourceHandling(ServletContextHandler context) {

  }

  /**
   * expose SslContextFactory
   */
  @Deprecated
  protected SslContextFactory getSslContextFactory() {
    return server.getSslContextFactory();
  }

  /**
   * add any servlet filters that should be called after resource
   * handling but before falling back to the default servlet
   */
  protected void configurePostResourceHandling(ServletContextHandler context) {

  }

  /**
   * add any servlet filters that should be called after resource
   * handling but before falling back to the default servlet
   */
  protected void configureWebSocketPostResourceHandling(ServletContextHandler context) {

  }

  /**
   * Returns a map of tag names to tag values to apply to metrics for this application.
   *
   * @return a Map of tags and values
   */
  public Map<String,String> getMetricsTags() {
    return new LinkedHashMap<>();
  }

  /**
   * Configure and create the server.
   */
  // CHECKSTYLE_RULES.OFF: MethodLength|CyclomaticComplexity|JavaNCSS|NPathComplexity
  public Server createServer() throws ServletException {
    // CHECKSTYLE_RULES.ON: MethodLength|CyclomaticComplexity|JavaNCSS|NPathComplexity
    if (server == null) {
      server = new ApplicationServer<>(config, x509Source);
      server.registerApplication(this);
    }
    return server;
  }

  final void setServer(ApplicationServer<?> server) {
    this.server = Objects.requireNonNull(server);
  }

  final ApplicationServer<?> getServer() {
    return server;
  }

  /**
   * Configures the {@link Handler} for the current {@link Application}.
   *
   * @return {@link Handler} object
   */
  public Handler configureHandler() {
    ResourceConfig resourceConfig = new ResourceConfig();
    configureBaseApplication(resourceConfig, getMetricsTags());
    configureResourceExtensions(resourceConfig);
    setupResources(resourceConfig, getConfiguration());

    // Configure the servlet container
    ServletContainer servletContainer = new ServletContainer(resourceConfig);
    final FilterHolder servletHolder = new FilterHolder(servletContainer);


    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.setContextPath(path);

    // Allow Jetty 12 servlet to decode ambiguous URIs
    configureServletWithDecodeAmbiguousURIs(context);

    if (listenerName != null && !listenerName.isEmpty()) {
      log.info("Binding {} to listener {}.", this.getClass().getSimpleName(), listenerName);
      context.setVirtualHosts(List.of("@" + listenerName));
    } else {
      log.info("Binding {} to all listeners.", this.getClass().getSimpleName());
    }

    ServletHolder defaultHolder = new ServletHolder("default", DefaultServlet.class);
    defaultHolder.setInitParameter("dirAllowed", "false");

    Collection<Resource> staticResources = getStaticResources();
    if (staticResources != null && !staticResources.isEmpty()) {
      context.setBaseResource(staticResources.iterator().next());
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
      String exposedHeaders = config.getString(RestConfig.ACCESS_CONTROL_EXPOSE_HEADERS);
      if (allowedMethods != null && !allowedMethods.trim().isEmpty()) {
        filterHolder.setInitParameter(CrossOriginFilter.ALLOWED_METHODS_PARAM, allowedMethods);
      }
      if (allowedHeaders != null && !allowedHeaders.trim().isEmpty()) {
        filterHolder.setInitParameter(CrossOriginFilter.ALLOWED_HEADERS_PARAM, allowedHeaders);
      }
      if (exposedHeaders != null && !exposedHeaders.trim().isEmpty()) {
        filterHolder.setInitParameter(CrossOriginFilter.EXPOSED_HEADERS_PARAM, exposedHeaders);
      }
      // handle preflight cors requests at the filter level, do not forward down the filter chain
      filterHolder.setInitParameter(CrossOriginFilter.CHAIN_PREFLIGHT_PARAM, "false");
      context.addFilter(filterHolder, "/*", EnumSet.of(DispatcherType.REQUEST));
    }

    if (isNoSniffProtectionEnabled()) {
      FilterHolder filterHolder = new FilterHolder(new HeaderFilter());
      filterHolder.setInitParameter("headerConfig", "set X-Content-Type-Options: nosniff");
      context.addFilter(filterHolder, "/*", EnumSet.of(DispatcherType.REQUEST));
    }

    if (isCsrfProtectionEnabled()) {
      String csrfEndpoint = config.getString(RestConfig.CSRF_PREVENTION_TOKEN_FETCH_ENDPOINT);
      int csrfTokenExpiration =
          config.getInt(RestConfig.CSRF_PREVENTION_TOKEN_EXPIRATION_MINUTES);
      int csrfTokenMaxEntries =
          config.getInt(RestConfig.CSRF_PREVENTION_TOKEN_MAX_ENTRIES);

      FilterHolder filterHolder = new FilterHolder(CsrfTokenProtectionFilter.class);
      filterHolder.setName("cross-site-request-forgery-prevention");
      filterHolder.setInitParameter(RestConfig.CSRF_PREVENTION_TOKEN_FETCH_ENDPOINT, csrfEndpoint);
      filterHolder.setInitParameter(
          RestConfig.CSRF_PREVENTION_TOKEN_EXPIRATION_MINUTES, String.valueOf(csrfTokenExpiration));
      filterHolder.setInitParameter(
          RestConfig.CSRF_PREVENTION_TOKEN_MAX_ENTRIES, String.valueOf(csrfTokenMaxEntries));
      context.addFilter(filterHolder, "/*", EnumSet.of(DispatcherType.REQUEST));
    }

    if (config.getString(RestConfig.RESPONSE_HTTP_HEADERS_CONFIG) != null
            && !config.getString(RestConfig.RESPONSE_HTTP_HEADERS_CONFIG).isEmpty()) {
      configureHttpResponseHeaderFilter(context);
    }

    configureJettyRequestMetricsFilter(context);

    configureDosFilters(context);

    configurePreResourceHandling(context);

    applyCustomConfiguration(context, REST_SERVLET_INITIALIZERS_CLASSES_CONFIG);

    context.addFilter(servletHolder, "/*", null);
    configurePostResourceHandling(context);
    context.addServlet(defaultHolder, "/*");

    server.setRequestLog(requestLog);

    List<String> expectedSniHeaders = config.getExpectedSniHeaders();
    // Add SNI validation handler if enabled
    if (config.getPrefixSniCheckEnable()) {
      context.insertHandler(new PrefixSniHandler(config.getPrefixSniPrefix()));
    } else if (config.getSniCheckEnable()) {
      context.insertHandler(new SniHandler());
    } else if (!expectedSniHeaders.isEmpty()) {
      context.insertHandler(new ExpectedSniHandler(expectedSniHeaders));
    }

    Sequence handlers = new Sequence();
    handlers.setHandlers(Arrays.asList(new Handler[]{context}));

    return handlers;
  }

  /**
   * Configures web socket {@link Handler} for the current {@link Application}.
   *
   * @return {@link Handler} object
   */
  public Handler configureWebSocketHandler() throws ServletException {
    final ServletContextHandler webSocketContext =
            new ServletContextHandler(ServletContextHandler.SESSIONS);
    webSocketContext.setContextPath(
            config.getString(RestConfig.WEBSOCKET_PATH_PREFIX_CONFIG)
    );

    // Allow Jetty 12 servlet to decode ambiguous URIs
    configureServletWithDecodeAmbiguousURIs(webSocketContext);

    configureSecurityHandler(webSocketContext);

    JakartaWebSocketServletContainerInitializer.configure(
            webSocketContext, (servletContext, serverContainer) -> {
            registerWebSocketEndpoints(serverContainer);
            });

    configureWebSocketPostResourceHandling(webSocketContext);
    applyCustomConfiguration(webSocketContext, WEBSOCKET_SERVLET_INITIALIZERS_CLASSES_CONFIG);

    return webSocketContext;
  }

  // Refer to https://github.com/jetty/jetty.project/issues/11890#issuecomment-2156449534
  // In Jetty 12, using Servlet 6 and ee10+, ambiguous path separators are not allowed
  // We will set decodeAmbiguousURIs to true so that client requests are not automatically
  // rejected by the servlet
  private ServletContextHandler configureServletWithDecodeAmbiguousURIs(
      ServletContextHandler context) {
    context.getServletHandler().setDecodeAmbiguousURIs(true);
    return context;
  }

  // This is copied from the old MAP implementation from cp ConfigDef.Type.MAP
  // TODO: This method should be removed in favor of RestConfig.getMap(). Note
  // that it is no longer used by projects related to kafka-rest.
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

  private boolean isNoSniffProtectionEnabled() {
    return config.getBoolean(RestConfig.NOSNIFF_PROTECTION_ENABLED);
  }

  private boolean isCsrfProtectionEnabled() {
    return config.getBoolean(RestConfig.CSRF_PREVENTION_ENABLED);
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
    } else {
      AuthUtil.createDisableOptionsConstraint(config)
          .ifPresent(optionsConstraint -> {
            ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
            securityHandler.addConstraintMapping(optionsConstraint);
            context.setSecurityHandler(securityHandler);
          });
    }
  }

  public Handler wrapWithGzipHandler(Handler handler) {
    return ApplicationServer.wrapWithGzipHandler(config, handler);
  }

  /**
   * TODO: delete deprecatedPort parameter when `PORT_CONFIG` is deprecated.
   * Helper function used to support the deprecated configuration.
   */
  public static List<URI> parseListeners(
      List<String> listenersConfig,
      int deprecatedPort,
      List<String> supportedSchemes,
      String defaultScheme) {

    // Support for named listeners is only implemented for the case of Applications
    // managed by ApplicationServer (direct instantiation of Application is to be
    // deprecated).
    return RestConfig.parseListeners(
            listenersConfig, emptyMap(), deprecatedPort, supportedSchemes, defaultScheme)
        .stream()
        .map(NamedURI::getUri)
        .collect(Collectors.toList());
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
    AuthUtil.createDisableOptionsConstraint(config)
        .ifPresent(securityHandler::addConstraintMapping);

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

    config.register(new MetricsResourceMethodApplicationListener(getMetrics(), "jersey",
        metricTags, restConfig.getTime(),
        restConfig.getBoolean(RestConfig.METRICS_LATENCY_SLO_SLA_ENABLE_CONFIG),
        restConfig.getLong(RestConfig.METRICS_LATENCY_SLO_MS_CONFIG),
        restConfig.getLong(RestConfig.METRICS_LATENCY_SLA_MS_CONFIG),
        restConfig.getDouble(RestConfig.PERCENTILE_MAX_LATENCY_MS_CONFIG),
        restConfig.getBoolean(RestConfig.METRICS_GLOBAL_STATS_REQUEST_TAGS_ENABLE_CONFIG)));

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
      config.register(JsonMappingExceptionMapper.class);
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
    config.register(JsonMappingExceptionMapper.class);
    config.register(JsonParseExceptionMapper.class);
    config.register(new WebApplicationExceptionMapper(restConfig));
    config.register(new GenericExceptionMapper(restConfig));
  }

  /**
   * Register header filter to ServletContextHandler.
   * @param context The serverlet context handler
   */
  protected void configureHttpResponseHeaderFilter(ServletContextHandler context) {
    String headerConfig = config.getString(RestConfig.RESPONSE_HTTP_HEADERS_CONFIG);
    log.debug("headerConfig : " + headerConfig);
    String[] configs = StringUtil.csvSplit(headerConfig);
    Arrays.stream(configs)
            .forEach(RestConfig::validateHttpResponseHeaderConfig);
    FilterHolder headerFilterHolder = new FilterHolder(HeaderFilter.class);
    headerFilterHolder.setInitParameter("headerConfig", headerConfig);
    context.addFilter(headerFilterHolder, "/*", EnumSet.of(DispatcherType.REQUEST));
  }

  private void configureJettyRequestMetricsFilter(ServletContextHandler context) {
    Filter filter = new JettyRequestMetricsFilter(this.metrics, this.getMetricsTags(),
        config.getString(RestConfig.METRICS_JMX_PREFIX_CONFIG));
    FilterHolder filterHolder = new FilterHolder(filter);
    context.addFilter(filterHolder, "/*", EnumSet.of(DispatcherType.REQUEST));
  }

  private void configureDosFilters(ServletContextHandler context) {
    if (!config.isDosFilterEnabled()) {
      return;
    }

    // Ensure that the per connection limiter is first - KREST-8391
    configureNonGlobalDosFilter(context);
    configureGlobalDosFilter(context);
  }

  private void configureNonGlobalDosFilter(ServletContextHandler context) {
    DoSFilter dosFilter = new DoSFilter();
    nonGlobalDosfilterListeners.add(jetty429MetricsListener);
    JettyDosFilterMultiListener multiListener = new JettyDosFilterMultiListener(
        nonGlobalDosfilterListeners);
    dosFilter.setListener(multiListener);
    FilterHolder filterHolder = configureDosFilter(dosFilter,
        String.valueOf(config.getDosFilterMaxRequestsPerConnectionPerSec()));
    context.addFilter(filterHolder, "/*", EnumSet.of(DispatcherType.REQUEST));
  }

  private void configureGlobalDosFilter(ServletContextHandler context) {
    DoSFilter dosFilter = new GlobalDosFilter();
    globalDosfilterListeners.add(jetty429MetricsListener);
    JettyDosFilterMultiListener multiListener = new JettyDosFilterMultiListener(
        globalDosfilterListeners);
    dosFilter.setListener(multiListener);
    String globalLimit = String.valueOf(config.getDosFilterMaxRequestsGlobalPerSec());
    FilterHolder filterHolder = configureDosFilter(dosFilter, globalLimit);
    context.addFilter(filterHolder, "/*", EnumSet.of(DispatcherType.REQUEST));
  }

  private FilterHolder configureDosFilter(DoSFilter dosFilter, String rate) {

    FilterHolder filterHolder = new FilterHolder(dosFilter);
    filterHolder.setInitParameter(
        "maxRequestsPerSec", rate);
    filterHolder.setInitParameter(
        "delayMs", String.valueOf(config.getDosFilterDelayMs().toMillis()));
    filterHolder.setInitParameter(
        "maxWaitMs", String.valueOf(config.getDosFilterMaxWaitMs().toMillis()));
    filterHolder.setInitParameter(
        "throttledRequests", String.valueOf(config.getDosFilterThrottledRequests()));
    filterHolder.setInitParameter(
        "throttleMs", String.valueOf(config.getDosFilterThrottleMs().toMillis()));
    filterHolder.setInitParameter(
        "maxRequestMs", String.valueOf(config.getDosFilterMaxRequestMs().toMillis()));
    filterHolder.setInitParameter(
        "maxIdleTrackerMs", String.valueOf(config.getDosFilterMaxIdleTrackerMs().toMillis()));
    filterHolder.setInitParameter(
        "insertHeaders", String.valueOf(config.getDosFilterInsertHeaders()));
    filterHolder.setInitParameter("trackSessions", "false");
    filterHolder.setInitParameter(
        "remotePort", String.valueOf("false"));
    filterHolder.setInitParameter(
        "ipWhitelist", String.valueOf(config.getDosFilterIpWhitelist()));
    filterHolder.setInitParameter(
        "managedAttr", String.valueOf(config.getDosFilterManagedAttr()));
    return filterHolder;

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
    createServer();
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
  public void onShutdown() {

  }

  /**
   * A rate-limiter that applies a single limit to the entire server.
   */
  private static final class GlobalDosFilter extends DoSFilter {

    @Override
    protected String extractUserId(ServletRequest request) {
      return "GLOBAL";
    }
  }
}
