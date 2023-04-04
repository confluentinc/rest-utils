/*
 * Copyright 2019 Confluent Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.confluent.rest;

import io.confluent.rest.errorhandlers.NoJettyDefaultStackTraceErrorHandler;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.metrics.Gauge;
import org.apache.kafka.common.metrics.Metrics;

import org.apache.kafka.common.config.ConfigException;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.NetworkTrafficServerConnector;
import org.eclipse.jetty.server.ProxyConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriBuilderException;

// CHECKSTYLE_RULES.OFF: ClassDataAbstractionCoupling
public final class ApplicationServer<T extends RestConfig> extends Server {
  // CHECKSTYLE_RULES.ON: ClassDataAbstractionCoupling
  private final T config;
  private final List<Application<?>> applications;
  private final SslContextFactory sslContextFactory;

  private static volatile int threadPoolRequestQueueCapacity;

  private List<NetworkTrafficServerConnector> connectors = new ArrayList<>();

  private static final Logger log = LoggerFactory.getLogger(ApplicationServer.class);

  static final List<String> SUPPORTED_URI_SCHEMES =
      Collections.unmodifiableList(Arrays.asList("http", "https"));

  // Package-visible for tests
  static boolean isJava11Compatible() {
    final String versionString = System.getProperty("java.specification.version");
  
    final StringTokenizer st = new StringTokenizer(versionString, ".");
    int majorVersion = Integer.parseInt(st.nextToken());

    return majorVersion >= 11;
  }

  public ApplicationServer(T config) {
    this(config, createThreadPool(config));
  }

  public ApplicationServer(T config, ThreadPool threadPool) {
    super(threadPool);

    this.config = config;
    this.applications = new ArrayList<>();

    int gracefulShutdownMs = config.getInt(RestConfig.SHUTDOWN_GRACEFUL_MS_CONFIG);
    if (gracefulShutdownMs > 0) {
      super.setStopTimeout(gracefulShutdownMs);
    }
    super.setStopAtShutdown(true);

    MBeanContainer mbContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
    super.addEventListener(mbContainer);
    super.addBean(mbContainer);

    this.sslContextFactory = createSslContextFactory(config);
    configureConnectors(sslContextFactory);
  }

  static NamedURI constructNamedURI(
          String listener,
          Map<String,String> listenerProtocolMap,
          List<String> supportedSchemes) {
    URI uri;
    try {
      uri = new URI(listener);
    } catch (URISyntaxException e) {
      throw new ConfigException(
          "Listener '" + listener + "' is not a valid URI.");
    }
    if (uri.getPort() == -1) {
      throw new ConfigException(
          "Listener '" + listener + "' must specify a port.");
    }
    if (supportedSchemes.contains(uri.getScheme())) {
      return new NamedURI(uri, null); // unnamed.
    }
    String uriName = uri.getScheme().toLowerCase();
    String protocol = listenerProtocolMap.get(uriName);
    if (protocol == null) {
      throw new ConfigException(
          "Listener '" + uri + "' has an unsupported scheme '" + uri.getScheme() + "'");
    }
    try {
      return new NamedURI(
          UriBuilder.fromUri(listener).scheme(protocol).build(),
          uriName);
    } catch (UriBuilderException e) {
      throw new ConfigException(
          "Listener '" + listener + "' with protocol '" + protocol + "' is not a valid URI.");
    }
  }

  /**
   * TODO: delete deprecatedPort parameter when `PORT_CONFIG` is deprecated.
   * It's only used to support the deprecated configuration.
   */
  static List<NamedURI> parseListeners(
          List<String> listeners,
          Map<String,String> listenerProtocolMap,
          int deprecatedPort,
          List<String> supportedSchemes,
          String defaultScheme) {

    // handle deprecated case, using PORT_CONFIG.
    // TODO: remove this when `PORT_CONFIG` is deprecated, because LISTENER_CONFIG
    // will have a default value which includes the default port.
    if (listeners.isEmpty() || listeners.get(0).isEmpty()) {
      log.warn(
          "DEPRECATION warning: `listeners` configuration is not configured. "
          + "Falling back to the deprecated `port` configuration.");
      listeners = new ArrayList<>(1);
      listeners.add(defaultScheme + "://0.0.0.0:" + deprecatedPort);
    }

    List<NamedURI> uris = listeners.stream()
        .map(listener -> constructNamedURI(listener, listenerProtocolMap, supportedSchemes))
        .collect(Collectors.toList());
    List<NamedURI> namedUris =
        uris.stream().filter(uri -> uri.getName() != null).collect(Collectors.toList());
    List<NamedURI> unnamedUris =
        uris.stream().filter(uri -> uri.getName() == null).collect(Collectors.toList());

    if (namedUris.stream().map(a -> a.getName()).distinct().count() != namedUris.size()) {
      throw new ConfigException(
          "More than one listener was specified with same name. Listener names must be unique.");
    }
    if (namedUris.isEmpty() && unnamedUris.isEmpty()) {
      throw new ConfigException(
          "No listeners are configured. At least one listener must be configured.");
    }

    return uris;
  }

  public void registerApplication(Application application) {
    application.setServer(this);
    applications.add(application);
  }

  public List<Application<?>> getApplications() {
    return Collections.unmodifiableList(applications);
  }

  private void attachMetricsListener(Metrics metrics, Map<String, String> tags) {
    MetricsListener metricsListener = new MetricsListener(metrics, "jetty", tags);
    for (NetworkTrafficServerConnector connector : connectors) {
      connector.addNetworkTrafficListener(metricsListener);
    }
  }

  private void addJettyThreadPoolMetrics(Metrics metrics, Map<String, String> tags) {
    //add metric for jetty thread pool queue size
    String requestQueueSizeName = "request-queue-size";
    String metricGroupName = "jetty-metrics";

    MetricName requestQueueSizeMetricName = metrics.metricName(requestQueueSizeName,
        metricGroupName, "The number of requests in the jetty thread pool queue.", tags);
    Gauge<Integer> queueSize = (config, now) ->  getQueueSize();
    metrics.addMetric(requestQueueSizeMetricName, queueSize);

    //add metric for thread pool busy thread count
    String busyThreadCountName = "busy-thread-count";
    MetricName busyThreadCountMetricName = metrics.metricName(busyThreadCountName,
        metricGroupName, "jetty thread pool busy thread count.",
        tags);
    Gauge<Integer> busyThreadCount = (config, now) -> getBusyThreads();
    metrics.addMetric(busyThreadCountMetricName, busyThreadCount);

    //add metric for thread pool usage
    String threadPoolUsageName = "thread-pool-usage";
    final MetricName threadPoolUsageMetricName = metrics.metricName(threadPoolUsageName,
        metricGroupName,  " jetty thread pool usage.",
        Collections.emptyMap());
    Gauge<Double> threadPoolUsage = (config, now) -> (getBusyThreads() / (double) getMaxThreads());
    metrics.addMetric(threadPoolUsageMetricName, threadPoolUsage);
  }

  private void finalizeHandlerCollection(HandlerCollection handlers, HandlerCollection wsHandlers) {
    /* DefaultHandler must come last to ensure all contexts
     * have a chance to handle a request first */
    handlers.addHandler(new DefaultHandler());
    /* Needed for graceful shutdown as per `setStopTimeout` documentation */
    StatisticsHandler statsHandler = new StatisticsHandler();
    statsHandler.setHandler(handlers);

    ContextHandlerCollection contexts = new ContextHandlerCollection();
    contexts.setHandlers(new Handler[]{
        statsHandler,
        wsHandlers
    });

    super.setHandler(wrapWithGzipHandler(contexts));
  }

  protected void doStop() throws Exception {
    super.doStop();
    for (Application<?> application : applications) {
      application.getMetrics().close();
      application.doShutdown();
    }
  }

  @Override
  protected final void doStart() throws Exception {
    // set the default error handler
    if (config.getSuppressStackTraceInResponse()) {
      this.setErrorHandler(new NoJettyDefaultStackTraceErrorHandler());
    }

    HandlerCollection handlers = new HandlerCollection();
    HandlerCollection wsHandlers = new HandlerCollection();
    for (Application<?> app : applications) {
      attachMetricsListener(app.getMetrics(), app.getMetricsTags());
      addJettyThreadPoolMetrics(app.getMetrics(), app.getMetricsTags());
      handlers.addHandler(app.configureHandler());
      wsHandlers.addHandler(app.configureWebSocketHandler());
    }
    finalizeHandlerCollection(handlers, wsHandlers);
    // Call super.doStart last to ensure that handlers are ready for incoming requests
    super.doStart();
  }

  @SuppressWarnings("deprecation")
  private void configureClientAuth(SslContextFactory sslContextFactory, RestConfig config) {
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

  private Path getWatchLocation(RestConfig config) {
    Path keystorePath = Paths.get(config.getString(RestConfig.SSL_KEYSTORE_LOCATION_CONFIG));
    String watchLocation = config.getString(RestConfig.SSL_KEYSTORE_WATCH_LOCATION_CONFIG);
    if (!watchLocation.isEmpty()) {
      keystorePath = Paths.get(watchLocation);
    }
    return keystorePath;
  }

  private SslContextFactory createSslContextFactory(RestConfig config) {
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

      if (config.getBoolean(RestConfig.SSL_KEYSTORE_RELOAD_CONFIG)) {
        Path watchLocation = getWatchLocation(config);
        try {
          FileWatcher.onFileChange(watchLocation, () -> {
                // Need to reset the key store path for symbolic link case
                sslContextFactory.setKeyStorePath(
                    config.getString(RestConfig.SSL_KEYSTORE_LOCATION_CONFIG)
                );
                sslContextFactory.reload(scf -> log.info("Reloaded SSL cert"));
              }
          );
          log.info("Enabled SSL cert auto reload for: " + watchLocation);
        } catch (java.io.IOException e) {
          log.error("Can not enabled SSL cert auto reload", e);
        }
      }
    }

    configureClientAuth(sslContextFactory, config);

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

  SslContextFactory getSslContextFactory() {
    return this.sslContextFactory;
  }

  private void configureConnectors(SslContextFactory sslContextFactory) {

    final HttpConfiguration httpConfiguration = new HttpConfiguration();
    httpConfiguration.setSendServerVersion(false);

    final HttpConnectionFactory httpConnectionFactory =
            new HttpConnectionFactory(httpConfiguration);

    // Default to supporting HTTP/2 for Java 11 and later
    final boolean http2Enabled = isJava11Compatible()
                              && config.getBoolean(RestConfig.HTTP2_ENABLED_CONFIG);

    final boolean proxyProtocolEnabled =
        config.getBoolean(RestConfig.PROXY_PROTOCOL_ENABLED_CONFIG);

    @SuppressWarnings("deprecation")
    List<NamedURI> listeners = parseListeners(
        config.getList(RestConfig.LISTENERS_CONFIG),
        config.getListenerProtocolMap(),
        config.getInt(RestConfig.PORT_CONFIG),
        SUPPORTED_URI_SCHEMES, "http");

    for (NamedURI listener : listeners) {
      if (listener.getUri().getScheme().equals("https")) {
        if (httpConfiguration.getCustomizer(SecureRequestCustomizer.class) == null) {
          httpConfiguration.addCustomizer(new SecureRequestCustomizer());
        }
      }
      addConnectorForListener(httpConfiguration, httpConnectionFactory, listener,
          http2Enabled, proxyProtocolEnabled);
    }
  }

  private void addConnectorForListener(HttpConfiguration httpConfiguration,
                                       HttpConnectionFactory httpConnectionFactory,
                                       NamedURI listener,
                                       boolean http2Enabled,
                                       boolean proxyProtocolEnabled) {
    ConnectionFactory[] connectionFactories = getConnectionFactories(httpConfiguration,
        httpConnectionFactory, listener, http2Enabled, proxyProtocolEnabled);
    NetworkTrafficServerConnector connector = new NetworkTrafficServerConnector(this, null, null,
        null, 0, 0, connectionFactories);
    if (http2Enabled) {
      // In Jetty 9.4.37, there was a change in behaviour to implement RFC 7230 more
      // rigorously and remove support for ambiguous URIs, such as escaping
      // . and / characters. While this is a good idea because it prevents clever
      // path-manipulation tricks in URIs, it breaks certain existing systems including
      // the Schema Registry. Jetty behaviour was then reverted specifying the compliance mode
      // in the HttpConnectionFactory class using the HttpCompliance.RFC7230 enum. This has
      // the problem that it applies only to HTTP/1.1. The following sets this compliance mode
      // explicitly when HTTP/2 is enabled.
      connector.addBean(HttpCompliance.RFC7230);
    }

    connector.setPort(listener.getUri().getPort());
    connector.setHost(listener.getUri().getHost());
    connector.setIdleTimeout(config.getLong(RestConfig.IDLE_TIMEOUT_MS_CONFIG));
    if (listener.getName() != null) {
      connector.setName(listener.getName());
    }

    connectors.add(connector);
    super.addConnector(connector);
  }

  private ConnectionFactory[] getConnectionFactories(HttpConfiguration httpConfiguration,
                                                     HttpConnectionFactory httpConnectionFactory,
                                                     NamedURI listener,
                                                     boolean http2Enabled,
                                                     boolean proxyProtocolEnabled) {
    ArrayList<ConnectionFactory> connectionFactories = new ArrayList<>();

    if (http2Enabled) {
      log.info("Adding listener with HTTP/2: " + listener);
      if (listener.getUri().getScheme().equals("http")) {
        // HTTP2C is HTTP/2 Clear text
        final HTTP2CServerConnectionFactory h2cConnectionFactory =
            new HTTP2CServerConnectionFactory(httpConfiguration);

        if (proxyProtocolEnabled) {
          connectionFactories.add(new ProxyConnectionFactory(httpConnectionFactory.getProtocol()));
        }

        // The order of HTTP and HTTP/2 is significant here but it's not clear why :)
        connectionFactories.add(httpConnectionFactory);
        connectionFactories.add(h2cConnectionFactory);
      } else {
        final HTTP2ServerConnectionFactory h2ConnectionFactory =
            new HTTP2ServerConnectionFactory(httpConfiguration);

        ALPNServerConnectionFactory alpnConnectionFactory = new ALPNServerConnectionFactory();
        alpnConnectionFactory.setDefaultProtocol(HttpVersion.HTTP_1_1.asString());

        SslConnectionFactory sslConnectionFactory = new SslConnectionFactory(sslContextFactory,
            alpnConnectionFactory.getProtocol());

        if (proxyProtocolEnabled) {
          connectionFactories.add(new ProxyConnectionFactory(sslConnectionFactory.getProtocol()));
        }

        connectionFactories.add(sslConnectionFactory);
        connectionFactories.add(alpnConnectionFactory);
        connectionFactories.add(h2ConnectionFactory);
        connectionFactories.add(httpConnectionFactory);
      }
    } else {
      log.info("Adding listener: " + listener);
      if (listener.uri.getScheme().equals("http")) {
        if (proxyProtocolEnabled) {
          connectionFactories.add(new ProxyConnectionFactory(httpConnectionFactory.getProtocol()));
        }

      } else {
        SslConnectionFactory sslConnectionFactory = new SslConnectionFactory(sslContextFactory,
            httpConnectionFactory.getProtocol());

        if (proxyProtocolEnabled) {
          connectionFactories.add(new ProxyConnectionFactory(sslConnectionFactory.getProtocol()));
        }

        connectionFactories.add(sslConnectionFactory);
      }
      connectionFactories.add(httpConnectionFactory);
    }

    return connectionFactories.toArray(new ConnectionFactory[0]);
  }

  /**
   * For unit testing.
   *
   * @return the total number of threads currently in the pool.
   */
  public int getThreads() {
    return getThreadPool().getThreads();
  }

  /**
   * @return number of busy threads in the pool.
   */
  public int getBusyThreads() {
    return ((QueuedThreadPool)getThreadPool()).getBusyThreads();
  }

  /**
   * For unit testing.
   *
   * @return the total number of maximum threads configured in the pool.
   */
  public int getMaxThreads() {
    return config.getInt(RestConfig.THREAD_POOL_MAX_CONFIG);
  }

  /**
   * @return the size of the queue in the pool.
   */
  public int getQueueSize() {
    return ((QueuedThreadPool)getThreadPool()).getQueueSize();
  }

  /**
   * For unit testing.
   *
   * @return the capacity of the queue in the pool.
   */
  public int getQueueCapacity() {
    return threadPoolRequestQueueCapacity;
  }

  static Handler wrapWithGzipHandler(RestConfig config, Handler handler) {
    if (config.getBoolean(RestConfig.ENABLE_GZIP_COMPRESSION_CONFIG)) {
      GzipHandler gzip = new GzipHandler();
      gzip.setIncludedMethods("GET", "POST");
      gzip.setHandler(handler);
      return gzip;
    }
    return handler;
  }

  private Handler wrapWithGzipHandler(Handler handler) {
    return wrapWithGzipHandler(config, handler);
  }

  /**
   * Create the thread pool with request queue.
   *
   * @return thread pool used by the server
   */
  private static ThreadPool createThreadPool(RestConfig config) {
    /* Create blocking queue for the thread pool. */
    int initialCapacity = config.getInt(RestConfig.REQUEST_QUEUE_CAPACITY_INITIAL_CONFIG);
    int growBy = config.getInt(RestConfig.REQUEST_QUEUE_CAPACITY_GROWBY_CONFIG);
    int maxCapacity = config.getInt(RestConfig.REQUEST_QUEUE_CAPACITY_CONFIG);
    log.info("Initial capacity {}, increased by {}, maximum capacity {}.",
            initialCapacity, growBy, maxCapacity);

    if (initialCapacity > maxCapacity) {
      threadPoolRequestQueueCapacity = initialCapacity;
      log.warn("request.queue.capacity is less than request.queue.capacity.init, invalid config. "
          + "Setting request.queue.capacity to request.queue.capacity.init.");
    } else {
      threadPoolRequestQueueCapacity = maxCapacity;
    }

    BlockingQueue<Runnable> requestQueue =
            new BlockingArrayQueue<>(initialCapacity, growBy, threadPoolRequestQueueCapacity);
    
    return new QueuedThreadPool(config.getInt(RestConfig.THREAD_POOL_MAX_CONFIG),
            config.getInt(RestConfig.THREAD_POOL_MIN_CONFIG),
            requestQueue);
  }

  static final class NamedURI {
    private final URI uri;
    private final String name;

    NamedURI(URI uri, String name) {
      this.uri = uri;
      this.name = name;
    }

    URI getUri() {
      return uri;
    }

    String getName() {
      return name;
    }

    @Override
    public String toString() {
      if (name == null) {
        return uri.toString();
      }
      return "'" + name + "' " + uri.toString();
    }
  }
}
