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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import io.confluent.rest.customizer.ProxyCustomizer;
import io.confluent.rest.errorhandlers.StackTraceErrorHandler;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.BlockingQueue;

import io.spiffe.workloadapi.X509Source;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.metrics.Gauge;
import org.apache.kafka.common.metrics.Metrics;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.NetworkTrafficListener;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.ConnectionLimit;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
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
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// CHECKSTYLE_RULES.OFF: ClassDataAbstractionCoupling
public final class ApplicationServer<T extends RestConfig> extends Server {
  // CHECKSTYLE_RULES.ON: ClassDataAbstractionCoupling
  private final T serverConfig;
  private final List<Application<?>> applications;
  private final ImmutableMap<NamedURI, SslContextFactory> sslContextFactories;

  private static volatile int threadPoolRequestQueueCapacity;

  private final List<NetworkTrafficServerConnector> connectors = new ArrayList<>();
  private final List<NamedURI> listeners;

  private static final Logger log = LoggerFactory.getLogger(ApplicationServer.class);
  // two years is the recommended value for HSTS max age, see https://hstspreload.org
  private static final long HSTS_MAX_AGE_SECONDS = 63072000L; // 2 years

  private final X509Source x509Source;

  @VisibleForTesting
  static boolean isHttp2Compatible(SslConfig sslConfig) {
    return isJava11Compatible() || SslConfig.TLS_CONSCRYPT.equals(sslConfig.getProvider());
  }

  @VisibleForTesting
  static boolean isJava11Compatible() {
    final String versionString = System.getProperty("java.specification.version");
  
    final StringTokenizer st = new StringTokenizer(versionString, ".");
    int majorVersion = Integer.parseInt(st.nextToken());
    return majorVersion >= 11;
  }

  public ApplicationServer(T config) {
    this(config, createThreadPool(config), null);
  }

  public ApplicationServer(T config, X509Source x509Source) {
    this(config, createThreadPool(config), x509Source);
  }

  public ApplicationServer(T config, ThreadPool threadPool) {
    this(config, threadPool, null);
  }

  public ApplicationServer(T config, ThreadPool threadPool, X509Source x509Source) {
    super(threadPool);

    this.serverConfig = config;
    this.applications = new ArrayList<>();

    int gracefulShutdownMs = config.getInt(RestConfig.SHUTDOWN_GRACEFUL_MS_CONFIG);
    if (gracefulShutdownMs > 0) {
      super.setStopTimeout(gracefulShutdownMs);
    }
    super.setStopAtShutdown(true);

    MBeanContainer mbContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
    super.addEventListener(mbContainer);
    super.addBean(mbContainer);

    listeners = config.getListeners();

    if (x509Source == null) {
      if (config.getBoolean(RestConfig.SSL_SPIRE_ENABLED_CONFIG)) {
        throw new RuntimeException("X509Source must be provided when SPIRE SSL is enabled");
      }
      this.x509Source = null;
    } else {
      this.x509Source = x509Source;
    }

    sslContextFactories = ImmutableMap.copyOf(
            Maps.transformValues(config.getSslConfigs(),
                    sslConfig -> SslFactory.createSslContextFactory(sslConfig, this.x509Source)));

    configureConnectors();
    configureConnectionLimits();
  }


  public void registerApplication(Application application) {
    application.setServer(this);
    applications.add(application);
  }

  public List<Application<?>> getApplications() {
    return Collections.unmodifiableList(applications);
  }

  private static boolean isHstsHeaderEnabled(RestConfig connectorConfig) {
    return connectorConfig.getBoolean(RestConfig.HSTS_HEADER_ENABLE_CONFIG);
  }

  private void attachNetworkTrafficListener(RestConfig appConfig,
                                            String appListenerName,
                                            Metrics metrics,
                                            Map<String, String> tags) {
    // if the application listener name is not specified (unnamed), attach NetworkTrafficListener
    // to all connectors of the application,
    // otherwise attach to the specified connector with the name
    // matching the application listener name
    for (NetworkTrafficServerConnector connector : connectors) {
      if (appListenerName == null || Objects.equals(connector.getName(), appListenerName)) {
        List<NetworkTrafficListener> listeners = new ArrayList<>();
        listeners.add(new MetricsListener(metrics, "jetty", tags));
        if (appConfig.getNetworkTrafficRateLimitEnable()) {
          listeners.add(new RateLimitNetworkTrafficListener(appConfig));
        }
        NetworkTrafficListener combinedListener = new CombinedNetworkTrafficListener(listeners);
        connector.setNetworkTrafficListener(combinedListener);
        log.info("Registered {} to network connector {} of listener: {}",
                 combinedListener.getClass().getSimpleName(),
                 connector.getName(),
                 appListenerName);
      }
    }
    if (connectors.isEmpty()) {
      log.warn("No network connector configured for listener: {}", appListenerName);
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

  private void finalizeHandlerCollection(Sequence handlers, Sequence wsHandlers) {
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
    this.setErrorHandler(new StackTraceErrorHandler(
        serverConfig.getSuppressStackTraceInResponse()));

    Sequence handlers = new Sequence();
    Sequence wsHandlers = new Sequence();
    for (Application<?> app : applications) {
      attachNetworkTrafficListener(app.getConfiguration(), app.getListenerName(),
                                   app.getMetrics(), app.getMetricsTags());
      addJettyThreadPoolMetrics(app.getMetrics(), app.getMetricsTags());
      handlers.addHandler(app.configureHandler());
      wsHandlers.addHandler(app.configureWebSocketHandler());
    }
    finalizeHandlerCollection(handlers, wsHandlers);
    // Call super.doStart last to ensure that handlers are ready for incoming requests
    super.doStart();
  }

  @Deprecated
  SslContextFactory getSslContextFactory() {
    return sslContextFactories.values()
        .stream()
        .findAny()
        .orElse(SslFactory.createSslContextFactory(SslConfig.defaultConfig(), this.x509Source));
  }

  public Map<NamedURI, SslContextFactory> getSslContextFactories() {
    return sslContextFactories;
  }

  private void configureConnectors() {
    for (NamedURI listener : listeners) {
      RestConfig connectorConfig = serverConfig.getListenerScopedConfig(listener);
      final boolean proxyProtocolEnabled =
          connectorConfig.getBoolean(RestConfig.PROXY_PROTOCOL_ENABLED_CONFIG);
      final HttpConfiguration httpConfiguration = new HttpConfiguration();
      httpConfiguration.setSendServerVersion(false);

      // Allow requests/responses with large URLs/token headers
      httpConfiguration.setRequestHeaderSize(
          connectorConfig.getInt(RestConfig.MAX_REQUEST_HEADER_SIZE_CONFIG));
      httpConfiguration.setResponseHeaderSize(
          connectorConfig.getInt(RestConfig.MAX_RESPONSE_HEADER_SIZE_CONFIG));

      if (proxyProtocolEnabled) {
        httpConfiguration.addCustomizer(new ProxyCustomizer());
      }

      // Use original IP in forwarded requests
      if (connectorConfig.getBoolean(RestConfig.NETWORK_FORWARDED_REQUEST_ENABLE_CONFIG)) {
        httpConfiguration.addCustomizer(new ForwardedRequestCustomizer());
      }

      // Refer to https://github.com/jetty/jetty.project/issues/11890#issuecomment-2156449534
      // In Jetty 12, using Servlet 6 and ee10+, ambiguous path separators are not allowed
      // We must set a URI compliance to allow for this violation so that client
      // requests are not automatically rejected
      httpConfiguration.setUriCompliance(UriCompliance.from(Set.of(
          UriCompliance.Violation.AMBIGUOUS_PATH_SEPARATOR,
          UriCompliance.Violation.AMBIGUOUS_PATH_ENCODING)));

      final HttpConnectionFactory httpConnectionFactory =
              new HttpConnectionFactory(httpConfiguration);

      // Default to supporting HTTP/2 for Java 11 and later
      final boolean http2Enabled = isHttp2Compatible(serverConfig.getBaseSslConfig())
                                && connectorConfig.getBoolean(RestConfig.HTTP2_ENABLED_CONFIG);

      if (listener.getUri().getScheme().equals("https")) {
        if (httpConfiguration.getCustomizer(SecureRequestCustomizer.class) == null) {
          SecureRequestCustomizer secureRequestCustomizer = new SecureRequestCustomizer();
          // SniHostCheckEnable is enabled by default
          if (!connectorConfig.getSniHostCheckEnable()) {
            secureRequestCustomizer.setSniHostCheck(false);
            log.info("Disabled SNI host check for listener: {}", listener);
          } else {
            // Explicitly making sure that SNI is checked against Host in HTTP request
            Preconditions.checkArgument(secureRequestCustomizer.isSniHostCheck(),
                "Host name matching SNI certificate check must be enabled.");
          }

          if (isHstsHeaderEnabled(connectorConfig)) {
            secureRequestCustomizer.setStsMaxAge(HSTS_MAX_AGE_SECONDS);
            secureRequestCustomizer.setStsIncludeSubDomains(true);
          }
          httpConfiguration.addCustomizer(secureRequestCustomizer);
        }
      }
      addConnectorForListener(connectorConfig, httpConfiguration, httpConnectionFactory, listener,
          http2Enabled, proxyProtocolEnabled);
    }
  }

  private void addConnectorForListener(RestConfig connectorConfig,
                                       HttpConfiguration httpConfiguration,
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
    connector.setIdleTimeout(connectorConfig.getLong(RestConfig.IDLE_TIMEOUT_MS_CONFIG));
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

        SslConnectionFactory sslConnectionFactory =
            new SslConnectionFactory(
                    (SslContextFactory.Server) sslContextFactories.get(listener),
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
      if (listener.getUri().getScheme().equals("http")) {
        if (proxyProtocolEnabled) {
          connectionFactories.add(new ProxyConnectionFactory(httpConnectionFactory.getProtocol()));
        }

      } else {
        SslConnectionFactory sslConnectionFactory =
            new SslConnectionFactory(
                    (SslContextFactory.Server) sslContextFactories.get(listener),
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

  private void configureConnectionLimits() {
    int serverConnectionLimit = serverConfig.getServerConnectionLimit();
    if (serverConnectionLimit > 0) {
      addBean(new ConnectionLimit(serverConnectionLimit, getServer()));
    }
    int connectorConnectionLimit = serverConfig.getConnectorConnectionLimit();
    if (connectorConnectionLimit > 0) {
      addBean(new ConnectionLimit(connectorConnectionLimit, connectors.toArray(new Connector[0])));
    }
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
    return serverConfig.getInt(RestConfig.THREAD_POOL_MAX_CONFIG);
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
    return wrapWithGzipHandler(serverConfig, handler);
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
}
