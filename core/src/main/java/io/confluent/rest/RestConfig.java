/*
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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Collections.unmodifiableList;
import static org.apache.kafka.clients.CommonClientConfigs.METRICS_CONTEXT_PREFIX;

import io.confluent.rest.extension.ResourceExtension;
import io.confluent.rest.metrics.RestMetricsContext;
import io.confluent.rest.ratelimit.NetworkTrafficRateLimitBackend;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriBuilderException;
import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigDef.Type;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.utils.Time;

public class RestConfig extends AbstractConfig {

  private final RestMetricsContext metricsContext;

  public static final String METRICS_REPORTER_CONFIG_PREFIX = "metric.reporters.";

  public static final String DEBUG_CONFIG = "debug";
  protected static final String DEBUG_CONFIG_DOC =
      "Boolean indicating whether extra debugging information is generated in some "
      + "error response entities.";
  protected static final boolean DEBUG_CONFIG_DEFAULT = false;

  @Deprecated
  public static final String PORT_CONFIG = "port";
  protected static final String PORT_CONFIG_DOC =
      "DEPRECATED: port to listen on for new HTTP connections. Use listeners instead.";
  protected static final int PORT_CONFIG_DEFAULT = 8080;

  public static final String LISTENERS_CONFIG = "listeners";
  protected static final String LISTENERS_DOC =
      "Comma separated list of listeners in the form NAME://HOST:PORT. For example: "
      + "\"https://myhost:8080,INTERNAL://0.0.0.0:8081\". If name is not a supported protocol "
      + "(http or https), this must be specified via the listener.protocol.map property. "
      + "NAME is case insensitive.";
  protected static final String LISTENERS_DEFAULT = "";

  public static final String LISTENER_PROTOCOL_MAP_CONFIG =
      "listener.protocol.map";
  protected static final String LISTENER_PROTOCOL_MAP_DOC =
      "Map between listener names (case insensitive) and URI scheme (http or https) specified "
      + "as a comma separated list of NAME:SCHEME. For example: INTERNAL:http,EXTERNAL:https.";
  protected static final String LISTENER_PROTOCOL_MAP_DEFAULT = "";

  public static final String RESPONSE_MEDIATYPE_PREFERRED_CONFIG = "response.mediatype.preferred";
  protected static final String RESPONSE_MEDIATYPE_PREFERRED_CONFIG_DOC =
      "An ordered list of the server's preferred media types used for responses, "
      + "from most preferred to least.";
  protected static final String RESPONSE_MEDIATYPE_PREFERRED_CONFIG_DEFAULT = "application/json";

  public static final String RESPONSE_MEDIATYPE_DEFAULT_CONFIG = "response.mediatype.default";
  protected static final String RESPONSE_MEDIATYPE_DEFAULT_CONFIG_DOC =
      "The default response media type that should be used if no specify types are requested in "
      + "an Accept header.";
  protected static final String RESPONSE_MEDIATYPE_DEFAULT_CONFIG_DEFAULT = "application/json";

  public static final String SHUTDOWN_GRACEFUL_MS_CONFIG = "shutdown.graceful.ms";
  protected static final String SHUTDOWN_GRACEFUL_MS_DOC =
      "Amount of time to wait after a shutdown request for outstanding requests to complete.";
  protected static final String SHUTDOWN_GRACEFUL_MS_DEFAULT = "1000";

  public static final String ACCESS_CONTROL_ALLOW_ORIGIN_CONFIG = "access.control.allow.origin";
  protected static final String ACCESS_CONTROL_ALLOW_ORIGIN_DOC =
      "Set value for Jetty Access-Control-Allow-Origin header";
  protected static final String ACCESS_CONTROL_ALLOW_ORIGIN_DEFAULT = "";

  public static final String ACCESS_CONTROL_SKIP_OPTIONS = "access.control.skip.options";
  protected static final String ACCESS_CONTROL_SKIP_OPTIONS_DOC =
          "Whether to skip authentication for OPTIONS requests";
  protected static final boolean ACCESS_CONTROL_SKIP_OPTIONS_DEFAULT = true;

  public static final String REJECT_OPTIONS_REQUEST = "reject.options.request";
  protected static final String REJECT_OPTIONS_REQUEST_DOC =
      "Whether to reject OPTIONS requests";
  protected static final boolean REJECT_OPTIONS_REQUEST_DEFAULT = false;


  public static final String ACCESS_CONTROL_ALLOW_METHODS = "access.control.allow.methods";
  protected static final String ACCESS_CONTROL_ALLOW_METHODS_DOC =
      "Set value to Jetty Access-Control-Allow-Origin header for specified methods";
  protected static final String ACCESS_CONTROL_ALLOW_METHODS_DEFAULT = "";

  public static final String ACCESS_CONTROL_ALLOW_HEADERS = "access.control.allow.headers";
  protected static final String ACCESS_CONTROL_ALLOW_HEADERS_DOC =
      "Set value to Jetty Access-Control-Allow-Origin header for specified headers. "
      + "Leave blank to use Jetty's default.";
  protected static final String ACCESS_CONTROL_ALLOW_HEADERS_DEFAULT = "";

  public static final String NOSNIFF_PROTECTION_ENABLED = "nosniff.prevention.enable";
  public static final boolean NOSNIFF_PROTECTION_ENABLED_DEFAULT = false;
  protected static final String NOSNIFF_PROTECTION_ENABLED_DOC =
      "Enable response to request be blocked due to nosniff. The header allows you to avoid "
      + "MIME type sniffing by saying that the MIME types are deliberately configured.";

  public static final String REQUEST_LOGGER_NAME_CONFIG = "request.logger.name";
  protected static final String REQUEST_LOGGER_NAME_DOC =
      "Name of the SLF4J logger to write the NCSA Common Log Format request log.";
  protected static final String REQUEST_LOGGER_NAME_DEFAULT = "io.confluent.rest-utils.requests";

  public static final String METRICS_JMX_PREFIX_CONFIG = "metrics.jmx.prefix";
  protected static final String METRICS_JMX_PREFIX_DOC =
      "Prefix to apply to metric names for the default JMX reporter.";
  protected static final String METRICS_JMX_PREFIX_DEFAULT = "rest-utils";

  public static final String METRICS_SAMPLE_WINDOW_MS_CONFIG = "metrics.sample.window.ms";
  protected static final String METRICS_SAMPLE_WINDOW_MS_DOC =
      "The metrics system maintains a configurable number of samples over a fixed window size. "
      + "This configuration controls the size of the window. For example we might maintain two "
      + "samples each measured over a 30 second period. When a window expires we erase and "
      + "overwrite the oldest window.";
  protected static final long METRICS_SAMPLE_WINDOW_MS_DEFAULT = 30000;

  public static final String METRICS_NUM_SAMPLES_CONFIG = "metrics.num.samples";
  protected static final String METRICS_NUM_SAMPLES_DOC =
      "The number of samples maintained to compute metrics.";
  protected static final int METRICS_NUM_SAMPLES_DEFAULT = 2;

  public static final String METRICS_REPORTER_CLASSES_CONFIG = "metric.reporters";
  protected static final String METRICS_REPORTER_CLASSES_DOC =
      "A list of classes to use as metrics reporters. Implementing the "
      + "<code>MetricReporter</code> interface allows plugging in classes that will be notified "
      + "of new metric creation. The JmxReporter is always included to register JMX statistics.";
  protected static final String METRICS_REPORTER_CLASSES_DEFAULT = "";

  public static final String METRICS_TAGS_CONFIG = "metrics.tag.map";
  protected static final String METRICS_TAGS_DOC =
      "A comma separated list of metrics tag entries of the form <tag_name>:<tag_value>. This can"
      + " be used to specify additional tags during deployment like data center, instance "
      + "details, etc.";
  protected static final String METRICS_TAGS_DEFAULT = "";
  public static final String METRICS_LATENCY_SLO_SLA_ENABLE_CONFIG =
      "metrics.latency.slo.sla.enable";
  protected static final String METRICS_LATENCY_SLO_SLA_ENABLE_DOC = "Whether to enable metrics"
      + " about the count of requests that meet or violate latency SLO/SLA"
      + " in the Performance annotation";
  protected static final boolean METRICS_LATENCY_SLO_SLA_ENABLE_DEFAULT = false;
  public static final String METRICS_LATENCY_SLO_MS_CONFIG = "metrics.latency.slo.ms";
  protected static final String METRICS_LATENCY_SLO_MS_DOC = "The threshold (in ms) of whether"
      + " request latency meets or violates SLO";
  protected static final long METRICS_LATENCY_SLO_MS_DEFAULT = 5;
  public static final String METRICS_LATENCY_SLA_MS_CONFIG = "metrics.latency.sla.ms";
  protected static final String METRICS_LATENCY_SLA_MS_DOC = "The threshold (in ms) of whether"
      + " request latency meets or violates SLA";
  protected static final long METRICS_LATENCY_SLA_MS_DEFAULT = 50;
  public static final String METRICS_GLOBAL_STATS_REQUEST_TAGS_ENABLE_CONFIG =
      "metrics.global.stats.request.tags.enable";
  protected static final String METRICS_GLOBAL_STATS_REQUEST_TAGS_ENABLE_DOC = "Whether to use "
      + " runtime request tags in global stats.";
  protected static final boolean METRICS_GLOBAL_STATS_REQUEST_TAGS_ENABLE_DEFAULT = false;

  public static final String SSL_KEYSTORE_RELOAD_CONFIG = "ssl.keystore.reload";
  protected static final String SSL_KEYSTORE_RELOAD_DOC =
      "Enable auto reload of ssl keystore";
  protected static final boolean SSL_KEYSTORE_RELOAD_DEFAULT = false;
  public static final String SSL_KEYSTORE_LOCATION_CONFIG = "ssl.keystore.location";
  protected static final String SSL_KEYSTORE_LOCATION_DOC =
      "Location of the keystore file to use for SSL. This is required for HTTPS.";
  protected static final String SSL_KEYSTORE_LOCATION_DEFAULT = "";
  public static final String SSL_KEYSTORE_WATCH_LOCATION_CONFIG = "ssl.keystore.watch.location";
  protected static final String SSL_KEYSTORE_WATCH_LOCATION_DOC =
      "Location to watch keystore file change if it is different from keystore location ";
  protected static final String SSL_KEYSTORE_WATCH_LOCATION_DEFAULT = "";
  public static final String SSL_KEYSTORE_PASSWORD_CONFIG = "ssl.keystore.password";
  protected static final String SSL_KEYSTORE_PASSWORD_DOC =
      "The store password for the keystore file.";
  protected static final String SSL_KEYSTORE_PASSWORD_DEFAULT = "";
  public static final String SSL_KEY_PASSWORD_CONFIG = "ssl.key.password";
  protected static final String SSL_KEY_PASSWORD_DOC =
      "The password of the private key in the keystore file.";
  protected static final String SSL_KEY_PASSWORD_DEFAULT = "";
  public static final String SSL_KEYSTORE_TYPE_CONFIG = "ssl.keystore.type";
  protected static final String SSL_KEYSTORE_TYPE_DOC =
      "The type of keystore file.";
  protected static final String SSL_KEYSTORE_TYPE_DEFAULT = "JKS";
  public static final String SSL_KEYMANAGER_ALGORITHM_CONFIG = "ssl.keymanager.algorithm";
  protected static final String SSL_KEYMANAGER_ALGORITHM_DOC =
      "The algorithm used by the key manager factory for SSL connections. "
      + "Leave blank to use Jetty's default.";
  protected static final String SSL_KEYMANAGER_ALGORITHM_DEFAULT = "";
  public static final String SSL_TRUSTSTORE_LOCATION_CONFIG = "ssl.truststore.location";
  protected static final String SSL_TRUSTSTORE_LOCATION_DOC =
      "Location of the trust store. Required only to authenticate HTTPS clients.";
  protected static final String SSL_TRUSTSTORE_LOCATION_DEFAULT = "";
  public static final String SSL_TRUSTSTORE_PASSWORD_CONFIG = "ssl.truststore.password";
  protected static final String SSL_TRUSTSTORE_PASSWORD_DOC =
      "The store password for the trust store file.";
  protected static final String SSL_TRUSTSTORE_PASSWORD_DEFAULT = "";
  public static final String SSL_TRUSTSTORE_TYPE_CONFIG = "ssl.truststore.type";
  protected static final String SSL_TRUSTSTORE_TYPE_DOC =
      "The type of trust store file.";
  protected static final String SSL_TRUSTSTORE_TYPE_DEFAULT = "JKS";
  public static final String SSL_TRUSTMANAGER_ALGORITHM_CONFIG = "ssl.trustmanager.algorithm";
  protected static final String SSL_TRUSTMANAGER_ALGORITHM_DOC =
      "The algorithm used by the trust manager factory for SSL connections. "
      + "Leave blank to use Jetty's default.";
  protected static final String SSL_TRUSTMANAGER_ALGORITHM_DEFAULT = "";
  public static final String SSL_PROTOCOL_CONFIG = "ssl.protocol";
  protected static final String SSL_PROTOCOL_DOC =
      "The SSL protocol used to generate the SslContextFactory.";
  protected static final String SSL_PROTOCOL_DEFAULT = "TLS";
  public static final String SSL_PROVIDER_CONFIG = "ssl.provider";
  protected static final String SSL_PROVIDER_DOC =
      "The SSL security provider name. Leave blank to use Jetty's default.";
  protected static final String SSL_PROVIDER_DEFAULT = "";
  public static final String SSL_CLIENT_AUTHENTICATION_CONFIG = "ssl.client.authentication";
  public static final String SSL_CLIENT_AUTHENTICATION_NONE = "NONE";
  public static final String SSL_CLIENT_AUTHENTICATION_REQUESTED = "REQUESTED";
  public static final String SSL_CLIENT_AUTHENTICATION_REQUIRED = "REQUIRED";
  protected static final String SSL_CLIENT_AUTHENTICATION_DOC =
      "SSL mutual auth. Set to NONE to disable SSL client authentication, set to REQUESTED to "
          + "request but not require SSL client authentication, and set to REQUIRED to require SSL "
          + "client authentication.";
  public static final ConfigDef.ValidString SSL_CLIENT_AUTHENTICATION_VALIDATOR =
      ConfigDef.ValidString.in(
          SSL_CLIENT_AUTHENTICATION_NONE,
          SSL_CLIENT_AUTHENTICATION_REQUESTED,
          SSL_CLIENT_AUTHENTICATION_REQUIRED
      );
  @Deprecated
  public static final String SSL_CLIENT_AUTH_CONFIG = "ssl.client.auth";
  protected static final String SSL_CLIENT_AUTH_DOC =
      "Whether or not to require the https client to authenticate via the server's trust store. "
          + "Deprecated; please use " + SSL_CLIENT_AUTHENTICATION_CONFIG + " instead.";
  protected static final boolean SSL_CLIENT_AUTH_DEFAULT = false;
  public static final String SSL_ENABLED_PROTOCOLS_CONFIG = "ssl.enabled.protocols";
  protected static final String SSL_ENABLED_PROTOCOLS_DOC =
      "The list of protocols enabled for SSL connections. Comma-separated list. "
      + "Leave blank to use Jetty's defaults.";
  protected static final String SSL_ENABLED_PROTOCOLS_DEFAULT = "";
  public static final String SSL_CIPHER_SUITES_CONFIG = "ssl.cipher.suites";
  protected static final String SSL_CIPHER_SUITES_DOC =
      "A list of SSL cipher suites. Leave blank to use Jetty's defaults.";
  protected static final String SSL_CIPHER_SUITES_DEFAULT = "";
  public static final String SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG =
      "ssl.endpoint.identification.algorithm";
  protected static final String SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_DOC =
      "The endpoint identification algorithm to validate the server hostname using the "
      + "server certificate.";
  protected static final String SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_DEFAULT = null;

  public static final String AUTHENTICATION_METHOD_CONFIG = "authentication.method";
  public static final String AUTHENTICATION_METHOD_NONE = "NONE";
  public static final String AUTHENTICATION_METHOD_BASIC = "BASIC";
  public static final String AUTHENTICATION_METHOD_BEARER = "BEARER";
  public static final String AUTHENTICATION_METHOD_DOC =
      "Method of authentication. Must be BASIC or BEARER to enable authentication. "
      + "For BASIC, you must supply a valid JAAS config file for the "
      + "'java.security.auth.login.config' system property for the appropriate authentication "
      + "provider. For BEARER, you must implement your own Application.createAuthenticator() "
      + "& Application.createLoginService() methods.";
  public static final ConfigDef.ValidString AUTHENTICATION_METHOD_VALIDATOR =
      ConfigDef.ValidString.in(
          AUTHENTICATION_METHOD_NONE,
          AUTHENTICATION_METHOD_BASIC,
          AUTHENTICATION_METHOD_BEARER
      );
  public static final String AUTHENTICATION_REALM_CONFIG = "authentication.realm";
  public static final String AUTHENTICATION_REALM_DOC =
      "Security realm to be used in authentication.";
  public static final String AUTHENTICATION_ROLES_CONFIG = "authentication.roles";
  public static final String AUTHENTICATION_ROLES_DOC = "Valid roles to authenticate against.";
  public static final List<String> AUTHENTICATION_ROLES_DEFAULT =
      unmodifiableList(Arrays.asList("*"));

  public static final String AUTHENTICATION_SKIP_PATHS = "authentication.skip.paths";
  public static final String AUTHENTICATION_SKIP_PATHS_DOC = "Comma separated list of paths that "
                                                             + "can be "
                                                             + "accessed without authentication";
  public static final String AUTHENTICATION_SKIP_PATHS_DEFAULT = "";

  public static final String WEBSOCKET_PATH_PREFIX_CONFIG = "websocket.path.prefix";
  public static final String WEBSOCKET_PATH_PREFIX_DOC =
      "Path under which this app can register websocket endpoints.";

  public static final String ENABLE_GZIP_COMPRESSION_CONFIG = "compression.enable";
  protected static final String ENABLE_GZIP_COMPRESSION_DOC = "Enable gzip compression";
  private static final boolean ENABLE_GZIP_COMPRESSION_DEFAULT = true;

  public static final String RESOURCE_EXTENSION_CLASSES_CONFIG = "resource.extension.classes";
  private static final String RESOURCE_EXTENSION_CLASSES_DOC = ""
      + "Zero or more classes that implement '" + ResourceExtension.class.getName()
      + "'. Each extension type will be called to add extensions to the rest server on start up.";

  public static final String REST_SERVLET_INITIALIZERS_CLASSES_CONFIG =
      "rest.servlet.initializor.classes";
  public static final String REST_SERVLET_INITIALIZERS_CLASSES_DOC =
      "Defines one or more initializer for the rest endpoint's ServletContextHandler. "
          + "Each initializer must implement Consumer<ServletContextHandler>. "
          + "It will be called to perform initialization of the handler, in order. "
          + "This is an internal feature and subject to change, "
          + "including changes to the Jetty version";

  public static final String WEBSOCKET_SERVLET_INITIALIZERS_CLASSES_CONFIG =
      "websocket.servlet.initializor.classes";
  public static final String WEBSOCKET_SERVLET_INITIALIZERS_CLASSES_DOC =
      "Defines one or more initializer for the websocket endpoint's ServletContextHandler. "
          + "Each initializer must implement Consumer<ServletContextHandler>. "
          + "It will be called to perform custom initialization of the handler, in order. "
          + "This is an internal feature and subject to change, "
          + "including changes to the Jetty version";

  public static final String IDLE_TIMEOUT_MS_CONFIG = "idle.timeout.ms";
  public static final String IDLE_TIMEOUT_MS_DOC =
          "The number of milliseconds to hold an idle session open for.";
  public static final long IDLE_TIMEOUT_MS_DEFAULT = 30_000;

  public static final String THREAD_POOL_MIN_CONFIG = "thread.pool.min";
  public static final String THREAD_POOL_MIN_DOC =
          "The minimum number of threads will be started for HTTP Servlet server.";
  public static final int THREAD_POOL_MIN_DEFAULT = 8;

  public static final String THREAD_POOL_MAX_CONFIG = "thread.pool.max";
  public static final String THREAD_POOL_MAX_DOC =
          "The maximum number of threads will be started for HTTP Servlet server.";
  public static final int THREAD_POOL_MAX_DEFAULT = 200;

  public static final String REQUEST_QUEUE_CAPACITY_CONFIG = "request.queue.capacity";
  public static final String REQUEST_QUEUE_CAPACITY_DOC =
          "The capacity of request queue for each thread pool.";
  public static final int REQUEST_QUEUE_CAPACITY_DEFAULT = Integer.MAX_VALUE;

  public static final String REQUEST_QUEUE_CAPACITY_INITIAL_CONFIG = "request.queue.capacity.init";
  public static final String REQUEST_QUEUE_CAPACITY_INITIAL_DOC =
          "The initial capacity of request queue for each thread pool.";
  public static final int REQUEST_QUEUE_CAPACITY_INITIAL_DEFAULT = 128;

  public static final String REQUEST_QUEUE_CAPACITY_GROWBY_CONFIG = "request.queue.capacity.growby";
  public static final String REQUEST_QUEUE_CAPACITY_GROWBY_DOC =
          "The size of request queue will be increased by.";
  public static final int REQUEST_QUEUE_CAPACITY_GROWBY_DEFAULT = 64;

  /**
   * @link "https://www.eclipse.org/jetty/documentation/current/header-filter.html"
   * @link "https://www.eclipse.org/jetty/javadoc/9.4.28.v20200408/org/eclipse/jetty/servlets/HeaderFilter.html"
   **/
  public static final String RESPONSE_HTTP_HEADERS_CONFIG = "response.http.headers.config";
  public static final String RESPONSE_HTTP_HEADERS_DOC =
          "Set values for Jetty HTTP response headers";
  public static final String RESPONSE_HTTP_HEADERS_DEFAULT = "";

  public static final String CSRF_PREVENTION_ENABLED = "csrf.prevention.enable";
  public static final boolean CSRF_PREVENTION_ENABLED_DEFAULT = false;
  protected static final String CSRF_PREVENTION_ENABLED_DOC = "Enable token based CSRF prevention";

  public static final String CSRF_PREVENTION_TOKEN_FETCH_ENDPOINT =
      "csrf.prevention.token.endpoint";
  public static final String CSRF_PREVENTION_TOKEN_FETCH_ENDPOINT_DEFAULT = "/csrf";
  protected static final String CSRF_PREVENTION_TOKEN_FETCH_ENDPOINT_DOC =
      "Endpoint to fetch the CSRF token. Token will be set in the Response header";

  public static final String CSRF_PREVENTION_TOKEN_EXPIRATION_MINUTES =
      "csrf.prevention.token.expiration.minutes";
  public static final int CSRF_PREVENTION_TOKEN_EXPIRATION_MINUTES_DEFAULT = 30;
  protected static final String CSRF_PREVENTION_TOKEN_EXPIRATION_MINUTES_DOC =
      "CSRF Token expiration period in minutes";

  public static final String CSRF_PREVENTION_TOKEN_MAX_ENTRIES =
      "csrf.prevention.token.max.entries";
  public static final int CSRF_PREVENTION_TOKEN_MAX_ENTRIES_DEFAULT = 10000;
  protected static final String CSRF_PREVENTION_TOKEN_MAX_ENTRIES_DOC =
      "Specifies the maximum number of entries the token cache may contain";

  private static final String DOS_FILTER_ENABLED_CONFIG = "dos.filter.enabled";
  private static final String DOS_FILTER_ENABLED_DOC =
      "Whether to enable DosFilter for the application. Default is false.";
  private static final boolean DOS_FILTER_ENABLED_DEFAULT = false;

  private static final String DOS_FILTER_MAX_REQUESTS_PER_SEC_CONFIG =
      "dos.filter.max.requests.per.sec";
  private static final String DOS_FILTER_MAX_REQUESTS_PER_SEC_DOC =
      "Maximum number of requests per second for the REST instance. Requests in excess of this "
          + "are first delayed, then throttled. Default is 25.";
  private static final int DOS_FILTER_MAX_REQUESTS_PER_SEC_DEFAULT = 25;

  private static final String DOS_FILTER_MAX_REQUESTS_PER_CONNECTION_PER_SEC_CONFIG =
      "dos.filter.max.requests.per.connection.per.sec";
  private static final String DOS_FILTER_MAX_REQUESTS_PER_CONNECTION_PER_SEC_DOC =
      "Maximum number of requests per second per ipaddress for the rest instance. "
          + "Requests in excess of this are first delayed, then throttled.";
  private static final int DOS_FILTER_MAX_REQUESTS_PER_CONNECTION_PER_SEC_DEFAULT = 25;

  private static final String DOS_FILTER_DELAY_MS_CONFIG = "dos.filter.delay.ms";
  private static final String DOS_FILTER_DELAY_MS_DOC =
      "Delay imposed on all requests over the rate limit, before they are considered at all: 100 "
          + "(ms) = Default, -1 = Reject request, 0 = No delay, any other value = Delay in ms";
  private static final Duration DOS_FILTER_DELAY_MS_DEFAULT = Duration.ofMillis(100);

  private static final String DOS_FILTER_MAX_WAIT_MS_CONFIG = "dos.filter.max.wait.ms";
  private static final String DOS_FILTER_MAX_WAIT_MS_DOC =
      "Length of time, in ms, to blocking wait for the throttle semaphore. Default is 50 ms.";
  private static final Duration DOS_FILTER_MAX_WAIT_MS_DEFAULT = Duration.ofMillis(50);

  private static final String DOS_FILTER_THROTTLED_REQUESTS_CONFIG =
      "dos.filter.throttled.requests";
  private static final String DOS_FILTER_THROTTLED_REQUESTS_DOC =
      "Number of requests over the rate limit able to be considered at once. Default is 5.";
  private static final int DOS_FILTER_THROTTLED_REQUESTS_DEFAULT = 5;

  private static final String DOS_FILTER_THROTTLE_MS_CONFIG = "dos.filter.throttle.ms";
  private static final String DOS_FILTER_THROTTLE_MS_DOC =
      "Length of time, in ms, to async wait for semaphore. Default is 30000L.";
  private static final Duration DOS_FILTER_THROTTLE_MS_DEFAULT = Duration.ofSeconds(30);

  private static final String DOS_FILTER_MAX_REQUEST_MS_CONFIG = "dos.filter.max.requests.ms";
  private static final String DOS_FILTER_MAX_REQUEST_MS_DOC =
      "Length of time, in ms, to allow the request to run. Default is 30000L.";
  private static final Duration DOS_FILTER_MAX_REQUEST_MS_DEFAULT = Duration.ofSeconds(30);

  private static final String DOS_FILTER_MAX_IDLE_TRACKER_MS_CONFIG =
      "dos.filter.max.idle.tracker.ms";
  private static final String DOS_FILTER_MAX_IDLE_TRACKER_MS_DOC =
      "Length of time, in ms, to keep track of request rates for a connection, before deciding "
          + "that the user has gone away, and discarding it. Default is 30000L.";
  private static final Duration DOS_FILTER_MAX_IDLE_TRACKER_MS_DEFAULT = Duration.ofSeconds(30);

  private static final String DOS_FILTER_INSERT_HEADERS_CONFIG = "dos.filter.insert.headers";
  private static final String DOS_FILTER_INSERT_HEADERS_DOC =
      "If true, insert the DoSFilter headers into the response. Defaults to true.";
  private static final boolean DOS_FILTER_INSERT_HEADERS_DEFAULT = true;

  private static final String DOS_FILTER_IP_WHITELIST_CONFIG = "dos.filter.ip.whitelist";
  private static final String DOS_FILTER_IP_WHITELIST_DOC =
      "A comma-separated list of IP addresses that will not be rate limited.";
  private static final List<String> DOS_FILTER_IP_WHITELIST_DEFAULT = emptyList();

  private static final String DOS_FILTER_MANAGED_ATTR_CONFIG = "dos.filter.managed.attr";
  private static final String DOS_FILTER_MANAGED_ATTR_DOC =
      "If set to true, then this servlet is set as a ServletContext attribute with the filter "
          + "name as the attribute name. This allows a context external mechanism (for example, "
          + "JMX via ContextHandler.MANAGED_ATTRIBUTES) to manage the configuration of the filter."
          + "Default is false.";
  private static final boolean DOS_FILTER_MANAGED_ATTR_DEFAULT = false;

  private static final String SERVER_CONNECTION_LIMIT = "server.connection.limit";
  private static final String SERVER_CONNECTION_LIMIT_DOC =
      "Limits the number of active connections on that server to the configured number. Once that "
          + "limit is reached further connections will not be accepted until the number of active "
          + "connections goes below that limit again. Active connections here means all already "
          + "opened connections plus all connections that are in the process of being accepted. "
          + "If the limit is set to a non-positive number, no limit is applied. Default is 0.";
  private static final int SERVER_CONNECTION_LIMIT_DEFAULT = 0;

  // For rest-utils applications connectors correspond to configured listeners. See
  // ApplicationServer#parseListeners for more details.
  private static final String CONNECTOR_CONNECTION_LIMIT = "connector.connection.limit";
  private static final String CONNECTOR_CONNECTION_LIMIT_DOC =
      "Limits the number of active connections per connector to the configured number. Once that "
          + "limit is reached further connections will not be accepted until the number of active "
          + "connections goes below that limit again. Active connections here means all already "
          + "opened connections plus all connections that are in the process of being accepted. "
          + "If the limit is set to a non-positive number, no limit is applied. Default is 0.";
  private static final int CONNECTOR_CONNECTION_LIMIT_DEFAULT = 0;

  public static final String HTTP2_ENABLED_CONFIG = "http2.enabled";
  protected static final String HTTP2_ENABLED_DOC =
      "If true, enable HTTP/2 connections. Connections will default to HTTP/2 not HTTP/1.1 "
          + "for clients that support it. Only takes effect if the server is running on a "
          + "Java 11 JVM or later. Default is true.";
  protected static final boolean HTTP2_ENABLED_DEFAULT = true;

  public static final String PROXY_PROTOCOL_ENABLED_CONFIG =
      "proxy.protocol.enabled";
  protected static final String PROXY_PROTOCOL_ENABLED_DOC =
      "If true, enable support for the PROXY protocol. When enabled, the server will "
          + "automatically detect whether PROXY protocol headers are present, and if they are, "
          + "whether V1 or V2 of the protocol is in use. When the headers are present, the actual "
          + "IP address and port of the client will be visible when the request is handled. If "
          + "the headers are not present, requests will be processed normally, but if the server "
          + "runs behind a load balancer, the request will appear to come from the the IP address "
          + "and port of the load balancer. See "
          + "https://www.haproxy.org/download/1.8/doc/proxy-protocol.txt for more information. "
          + "Default is false.";
  protected static final boolean PROXY_PROTOCOL_ENABLED_DEFAULT = false;

  public static final String SUPPRESS_STACK_TRACE_IN_RESPONSE = "suppress.stack.trace.response";

  protected static final String SUPPRESS_STACK_TRACE_IN_RESPONSE_DOC =
      "If true, enable overall error handling for any uncaught errors in handlers pipeline. "
          + "This ensures that no stack traces are included in responses to clients.";

  protected static final String MAX_RESPONSE_HEADER_SIZE_CONFIG =
      "max.response.header.size";
  protected static final String MAX_RESPONSE_HEADER_SIZE_DOC =
      "Maximum buffer size for jetty response headers in bytes";
  protected static final int MAX_RESPONSE_HEADER_SIZE_DEFAULT = 8192;

  protected static final String MAX_REQUEST_HEADER_SIZE_CONFIG =
      "max.request.header.size";
  protected static final String MAX_REQUEST_HEADER_SIZE_DOC =
      "Maximum buffer size for jetty request headers in bytes";
  protected static final int MAX_REQUEST_HEADER_SIZE_DEFAULT = 8192;

  protected static final String NETWORK_TRAFFIC_RATE_LIMIT_ENABLE_CONFIG =
      "network.traffic.rate.limit.enable";
  protected static final String NETWORK_TRAFFIC_RATE_LIMIT_ENABLE_DOC =
      "Whether to enable network traffic rate-limiting. Default is false";
  protected static final boolean NETWORK_TRAFFIC_RATE_LIMIT_ENABLE_DEFAULT = false;

  protected static final String NETWORK_TRAFFIC_RATE_LIMIT_BACKEND_CONFIG =
      "network.traffic.rate.limit.backend";
  protected static final String NETWORK_TRAFFIC_RATE_LIMIT_BACKEND_DOC =
      "The rate-limiting backend to use. The options are 'guava', and 'resilience4j'."
          + "Default is 'guava'.";
  protected static final String NETWORK_TRAFFIC_RATE_LIMIT_BACKEND_DEFAULT = "guava";

  protected static final String NETWORK_TRAFFIC_RATE_LIMIT_BYTES_PER_SEC_CONFIG =
      "network.traffic.rate.limit.bytes.per.sec";
  protected static final String NETWORK_TRAFFIC_RATE_LIMIT_BYTES_PER_SEC_DOC =
      "The maximum number of bytes to emit per second for the network traffic. Default is 20MiB.";
  protected static final Integer NETWORK_TRAFFIC_RATE_LIMIT_BYTES_PER_SEC_DEFAULT =
      20 * 1024 * 1024;
  protected static final ConfigDef.Range NETWORK_TRAFFIC_RATE_LIMIT_BYTES_PER_SEC_VALIDATOR =
      ConfigDef.Range.between(1, Integer.MAX_VALUE);

  protected static final boolean SUPPRESS_STACK_TRACE_IN_RESPONSE_DEFAULT = true;

  static final List<String> SUPPORTED_URI_SCHEMES =
      unmodifiableList(Arrays.asList("http", "https"));

  public static ConfigDef baseConfigDef() {
    return baseConfigDef(
        PORT_CONFIG_DEFAULT,
        LISTENERS_DEFAULT
    );
  }

  public static ConfigDef baseConfigDef(
      int port,
      String listeners
  ) {
    return baseConfigDef(
        port,
        listeners,
        RESPONSE_MEDIATYPE_PREFERRED_CONFIG_DEFAULT,
        RESPONSE_MEDIATYPE_DEFAULT_CONFIG_DEFAULT,
        METRICS_JMX_PREFIX_DEFAULT
    );
  }

  public static ConfigDef baseConfigDef(
      int port,
      String listeners,
      String responseMediatypePreferred,
      String responseMediatypeDefault
  ) {
    return baseConfigDef(
        port,
        listeners,
        responseMediatypePreferred,
        responseMediatypeDefault,
        METRICS_JMX_PREFIX_DEFAULT
    );
  }

  public static ConfigDef baseConfigDef(
      int port,
      String listeners,
      String responseMediatypePreferred,
      String responseMediatypeDefault,
      String metricsJmxPrefix
  ) {
    return incompleteBaseConfigDef()
        .define(
            PORT_CONFIG,
            Type.INT,
            port,
            Importance.LOW,
            PORT_CONFIG_DOC
        ).define(
            LISTENERS_CONFIG,
            Type.LIST,
            listeners,
            Importance.HIGH,
            LISTENERS_DOC
        ).define(
            RESPONSE_MEDIATYPE_PREFERRED_CONFIG,
            Type.LIST,
            responseMediatypePreferred,
            Importance.LOW,
            RESPONSE_MEDIATYPE_PREFERRED_CONFIG_DOC
        ).define(
            RESPONSE_MEDIATYPE_DEFAULT_CONFIG,
            Type.STRING,
            responseMediatypeDefault,
            Importance.LOW,
            RESPONSE_MEDIATYPE_DEFAULT_CONFIG_DOC
        ).define(
            METRICS_JMX_PREFIX_CONFIG,
            Type.STRING,
            metricsJmxPrefix,
            Importance.LOW,
            METRICS_JMX_PREFIX_DOC
        );
  }

  // CHECKSTYLE_RULES.OFF: MethodLength
  private static ConfigDef incompleteBaseConfigDef() {
    // CHECKSTYLE_RULES.ON: MethodLength
    return new ConfigDef()
        .define(
            DEBUG_CONFIG,
            Type.BOOLEAN,
            DEBUG_CONFIG_DEFAULT,
            Importance.LOW,
            DEBUG_CONFIG_DOC
        ).define(
            SHUTDOWN_GRACEFUL_MS_CONFIG,
            Type.INT,
            SHUTDOWN_GRACEFUL_MS_DEFAULT,
            Importance.LOW,
            SHUTDOWN_GRACEFUL_MS_DOC
        ).define(
            ACCESS_CONTROL_ALLOW_ORIGIN_CONFIG,
            Type.STRING,
            ACCESS_CONTROL_ALLOW_ORIGIN_DEFAULT,
            Importance.LOW,
            ACCESS_CONTROL_ALLOW_ORIGIN_DOC
        ).define(
            ACCESS_CONTROL_ALLOW_METHODS,
            Type.STRING,
            ACCESS_CONTROL_ALLOW_METHODS_DEFAULT,
            Importance.LOW,
            ACCESS_CONTROL_ALLOW_METHODS_DOC
        ).define(
            ACCESS_CONTROL_ALLOW_HEADERS,
            Type.STRING,
            ACCESS_CONTROL_ALLOW_HEADERS_DEFAULT,
            Importance.LOW,
            ACCESS_CONTROL_ALLOW_HEADERS_DOC
        ).define(
            ACCESS_CONTROL_SKIP_OPTIONS,
            Type.BOOLEAN,
            ACCESS_CONTROL_SKIP_OPTIONS_DEFAULT,
            Importance.LOW,
            ACCESS_CONTROL_SKIP_OPTIONS_DOC
        ).define(
            REJECT_OPTIONS_REQUEST,
            Type.BOOLEAN,
            REJECT_OPTIONS_REQUEST_DEFAULT,
            Importance.LOW,
            REJECT_OPTIONS_REQUEST_DOC
        )
        .define(
            REQUEST_LOGGER_NAME_CONFIG,
            Type.STRING,
            REQUEST_LOGGER_NAME_DEFAULT,
            Importance.LOW,
            REQUEST_LOGGER_NAME_DOC
        ).define(
            METRICS_REPORTER_CLASSES_CONFIG,
            Type.LIST,
            METRICS_REPORTER_CLASSES_DEFAULT,
            Importance.LOW,
            METRICS_REPORTER_CLASSES_DOC
        ).define(
            METRICS_SAMPLE_WINDOW_MS_CONFIG,
            Type.LONG,
            METRICS_SAMPLE_WINDOW_MS_DEFAULT,
            ConfigDef.Range.atLeast(0),
            Importance.LOW,
            METRICS_SAMPLE_WINDOW_MS_DOC
        ).define(
            METRICS_NUM_SAMPLES_CONFIG,
            Type.INT,
            METRICS_NUM_SAMPLES_DEFAULT,
            ConfigDef.Range.atLeast(1),
            Importance.LOW,
            METRICS_NUM_SAMPLES_DOC
        ).define(
            METRICS_TAGS_CONFIG,
            Type.LIST,
            METRICS_TAGS_DEFAULT,
            Importance.LOW,
            METRICS_TAGS_DOC
        ).define(
            METRICS_LATENCY_SLO_SLA_ENABLE_CONFIG,
            Type.BOOLEAN,
            METRICS_LATENCY_SLO_SLA_ENABLE_DEFAULT,
            Importance.LOW,
            METRICS_LATENCY_SLO_SLA_ENABLE_DOC
        ).define(
            METRICS_LATENCY_SLO_MS_CONFIG,
            Type.LONG,
            METRICS_LATENCY_SLO_MS_DEFAULT,
            Importance.LOW,
            METRICS_LATENCY_SLO_MS_DOC
        ).define(
            METRICS_LATENCY_SLA_MS_CONFIG,
            Type.LONG,
            METRICS_LATENCY_SLA_MS_DEFAULT,
            Importance.LOW,
            METRICS_LATENCY_SLA_MS_DOC
        ).define(
            METRICS_GLOBAL_STATS_REQUEST_TAGS_ENABLE_CONFIG,
            Type.BOOLEAN,
            METRICS_GLOBAL_STATS_REQUEST_TAGS_ENABLE_DEFAULT,
            Importance.LOW,
            METRICS_GLOBAL_STATS_REQUEST_TAGS_ENABLE_DOC
        ).define(
            SSL_KEYSTORE_RELOAD_CONFIG,
            Type.BOOLEAN,
            SSL_KEYSTORE_RELOAD_DEFAULT,
            Importance.LOW,
            SSL_KEYSTORE_RELOAD_DOC
        ).define(
            SSL_KEYSTORE_LOCATION_CONFIG,
            Type.STRING,
            SSL_KEYSTORE_LOCATION_DEFAULT,
            Importance.HIGH,
            SSL_KEYSTORE_LOCATION_DOC
        ).define(
            SSL_KEYSTORE_WATCH_LOCATION_CONFIG,
            Type.STRING,
            SSL_KEYSTORE_WATCH_LOCATION_DEFAULT,
            Importance.LOW,
            SSL_KEYSTORE_WATCH_LOCATION_DOC
        ).define(
            SSL_KEYSTORE_PASSWORD_CONFIG,
            Type.PASSWORD,
            SSL_KEYSTORE_PASSWORD_DEFAULT,
            Importance.HIGH,
            SSL_KEYSTORE_PASSWORD_DOC
        ).define(
            SSL_KEY_PASSWORD_CONFIG,
            Type.PASSWORD,
            SSL_KEY_PASSWORD_DEFAULT,
            Importance.HIGH,
            SSL_KEY_PASSWORD_DOC
        ).define(
            SSL_KEYSTORE_TYPE_CONFIG,
            Type.STRING,
            SSL_KEYSTORE_TYPE_DEFAULT,
            Importance.MEDIUM,
            SSL_KEYSTORE_TYPE_DOC
        ).define(
            SSL_KEYMANAGER_ALGORITHM_CONFIG,
            Type.STRING,
            SSL_KEYMANAGER_ALGORITHM_DEFAULT,
            Importance.LOW,
            SSL_KEYMANAGER_ALGORITHM_DOC
        ).define(
            SSL_TRUSTSTORE_LOCATION_CONFIG,
            Type.STRING,
            SSL_TRUSTSTORE_LOCATION_DEFAULT,
            Importance.HIGH,
            SSL_TRUSTSTORE_LOCATION_DOC
        ).define(
            SSL_TRUSTSTORE_PASSWORD_CONFIG,
            Type.PASSWORD,
            SSL_TRUSTSTORE_PASSWORD_DEFAULT,
            Importance.HIGH,
            SSL_TRUSTSTORE_PASSWORD_DOC)
        .define(
            SSL_TRUSTSTORE_TYPE_CONFIG,
            Type.STRING,
            SSL_TRUSTSTORE_TYPE_DEFAULT,
            Importance.MEDIUM,
            SSL_TRUSTSTORE_TYPE_DOC)
        .define(
            SSL_TRUSTMANAGER_ALGORITHM_CONFIG,
            Type.STRING,
            SSL_TRUSTMANAGER_ALGORITHM_DEFAULT,
            Importance.LOW,
            SSL_TRUSTMANAGER_ALGORITHM_DOC
        ).define(
            SSL_PROTOCOL_CONFIG,
            Type.STRING,
            SSL_PROTOCOL_DEFAULT,
            Importance.MEDIUM,
            SSL_PROTOCOL_DOC)
        .define(
            SSL_PROVIDER_CONFIG,
            Type.STRING,
            SSL_PROVIDER_DEFAULT,
            Importance.MEDIUM,
            SSL_PROVIDER_DOC
        ).define(
            SSL_CLIENT_AUTHENTICATION_CONFIG,
            Type.STRING,
            SSL_CLIENT_AUTHENTICATION_NONE,
            SSL_CLIENT_AUTHENTICATION_VALIDATOR,
            Importance.MEDIUM,
            SSL_CLIENT_AUTHENTICATION_DOC
        ).define(
            SSL_CLIENT_AUTH_CONFIG,
            Type.BOOLEAN,
            SSL_CLIENT_AUTH_DEFAULT,
            Importance.MEDIUM,
            SSL_CLIENT_AUTH_DOC
        ).define(
            SSL_ENABLED_PROTOCOLS_CONFIG,
            Type.LIST,
            SSL_ENABLED_PROTOCOLS_DEFAULT,
            Importance.MEDIUM,
            SSL_ENABLED_PROTOCOLS_DOC
        ).define(
            SSL_CIPHER_SUITES_CONFIG,
            Type.LIST,
            SSL_CIPHER_SUITES_DEFAULT,
            Importance.LOW,
            SSL_CIPHER_SUITES_DOC
        ).define(
            SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG,
            Type.STRING,
            SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_DEFAULT,
            Importance.LOW,
            SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_DOC
        ).define(
            AUTHENTICATION_METHOD_CONFIG,
            Type.STRING,
            AUTHENTICATION_METHOD_NONE,
            AUTHENTICATION_METHOD_VALIDATOR,
            Importance.LOW,
            AUTHENTICATION_METHOD_DOC
        ).define(
            AUTHENTICATION_REALM_CONFIG,
            Type.STRING,
            "",
            Importance.LOW,
            AUTHENTICATION_REALM_DOC
        ).define(
            AUTHENTICATION_ROLES_CONFIG,
            Type.LIST,
            AUTHENTICATION_ROLES_DEFAULT,
            Importance.LOW,
            AUTHENTICATION_ROLES_DOC
        ).define(
            AUTHENTICATION_SKIP_PATHS,
            Type.LIST,
            AUTHENTICATION_SKIP_PATHS_DEFAULT,
            Importance.LOW,
            AUTHENTICATION_SKIP_PATHS_DOC
        ).define(
            ENABLE_GZIP_COMPRESSION_CONFIG,
            Type.BOOLEAN,
            ENABLE_GZIP_COMPRESSION_DEFAULT,
            Importance.LOW,
            ENABLE_GZIP_COMPRESSION_DOC
        ).define(
            WEBSOCKET_PATH_PREFIX_CONFIG,
            Type.STRING,
            "/ws",
            Importance.LOW,
            WEBSOCKET_PATH_PREFIX_DOC
        ).define(
            RESOURCE_EXTENSION_CLASSES_CONFIG,
            Type.LIST,
            emptyList(),
            Importance.LOW,
            RESOURCE_EXTENSION_CLASSES_DOC
        ).define(
            REST_SERVLET_INITIALIZERS_CLASSES_CONFIG,
            Type.LIST,
            emptyList(),
            Importance.LOW,
            REST_SERVLET_INITIALIZERS_CLASSES_DOC
        ).define(
            WEBSOCKET_SERVLET_INITIALIZERS_CLASSES_CONFIG,
            Type.LIST,
            emptyList(),
            Importance.LOW,
            WEBSOCKET_SERVLET_INITIALIZERS_CLASSES_DOC
        ).define(
            IDLE_TIMEOUT_MS_CONFIG,
            Type.LONG,
            IDLE_TIMEOUT_MS_DEFAULT,
            Importance.LOW,
            IDLE_TIMEOUT_MS_DOC
        ).define(
            THREAD_POOL_MIN_CONFIG,
            Type.INT,
            THREAD_POOL_MIN_DEFAULT,
            Importance.LOW,
            THREAD_POOL_MIN_DOC
        ).define(
            THREAD_POOL_MAX_CONFIG,
            Type.INT,
            THREAD_POOL_MAX_DEFAULT,
            Importance.LOW,
            THREAD_POOL_MAX_DOC
        ).define(
            REQUEST_QUEUE_CAPACITY_INITIAL_CONFIG,
            Type.INT,
            REQUEST_QUEUE_CAPACITY_INITIAL_DEFAULT,
            Importance.LOW,
            REQUEST_QUEUE_CAPACITY_INITIAL_DOC
        ).define(
            REQUEST_QUEUE_CAPACITY_CONFIG,
            Type.INT,
            REQUEST_QUEUE_CAPACITY_DEFAULT,
            Importance.LOW,
            REQUEST_QUEUE_CAPACITY_DOC
        ).define(
            REQUEST_QUEUE_CAPACITY_GROWBY_CONFIG,
            Type.INT,
            REQUEST_QUEUE_CAPACITY_GROWBY_DEFAULT,
            Importance.LOW,
            REQUEST_QUEUE_CAPACITY_GROWBY_DOC
        ).define(
            RESPONSE_HTTP_HEADERS_CONFIG,
            Type.STRING,
            RESPONSE_HTTP_HEADERS_DEFAULT,
            Importance.LOW,
            RESPONSE_HTTP_HEADERS_DOC
        ).define(
            CSRF_PREVENTION_ENABLED,
            Type.BOOLEAN,
            CSRF_PREVENTION_ENABLED_DEFAULT,
            Importance.LOW,
            CSRF_PREVENTION_ENABLED_DOC
        ).define(
            CSRF_PREVENTION_TOKEN_FETCH_ENDPOINT,
            Type.STRING,
            CSRF_PREVENTION_TOKEN_FETCH_ENDPOINT_DEFAULT,
            Importance.LOW,
            CSRF_PREVENTION_TOKEN_FETCH_ENDPOINT_DOC
        ).define(
            CSRF_PREVENTION_TOKEN_EXPIRATION_MINUTES,
            Type.INT,
            CSRF_PREVENTION_TOKEN_EXPIRATION_MINUTES_DEFAULT,
            Importance.LOW,
            CSRF_PREVENTION_TOKEN_EXPIRATION_MINUTES_DOC
        ).define(
            CSRF_PREVENTION_TOKEN_MAX_ENTRIES,
            Type.INT,
            CSRF_PREVENTION_TOKEN_MAX_ENTRIES_DEFAULT,
            Importance.LOW,
            CSRF_PREVENTION_TOKEN_MAX_ENTRIES_DOC
        ).define(
            DOS_FILTER_ENABLED_CONFIG,
            Type.BOOLEAN,
            DOS_FILTER_ENABLED_DEFAULT,
            Importance.LOW,
            DOS_FILTER_ENABLED_DOC
        ).define(
            DOS_FILTER_MAX_REQUESTS_PER_SEC_CONFIG,
            Type.INT,
            DOS_FILTER_MAX_REQUESTS_PER_SEC_DEFAULT,
            Importance.LOW,
            DOS_FILTER_MAX_REQUESTS_PER_SEC_DOC
        ).define(
            DOS_FILTER_MAX_REQUESTS_PER_CONNECTION_PER_SEC_CONFIG,
            Type.INT,
            DOS_FILTER_MAX_REQUESTS_PER_CONNECTION_PER_SEC_DEFAULT,
            Importance.LOW,
            DOS_FILTER_MAX_REQUESTS_PER_CONNECTION_PER_SEC_DOC
        ).define(
            DOS_FILTER_DELAY_MS_CONFIG,
            Type.LONG,
            DOS_FILTER_DELAY_MS_DEFAULT.toMillis(),
            Importance.LOW,
            DOS_FILTER_DELAY_MS_DOC
        ).define(
            DOS_FILTER_MAX_WAIT_MS_CONFIG,
            Type.LONG,
            DOS_FILTER_MAX_WAIT_MS_DEFAULT.toMillis(),
            Importance.LOW,
            DOS_FILTER_MAX_WAIT_MS_DOC
        ).define(
            DOS_FILTER_THROTTLED_REQUESTS_CONFIG,
            Type.INT,
            DOS_FILTER_THROTTLED_REQUESTS_DEFAULT,
            Importance.LOW,
            DOS_FILTER_THROTTLED_REQUESTS_DOC
        ).define(
            DOS_FILTER_THROTTLE_MS_CONFIG,
            Type.LONG,
            DOS_FILTER_THROTTLE_MS_DEFAULT.toMillis(),
            Importance.LOW,
            DOS_FILTER_THROTTLE_MS_DOC
        ).define(
            DOS_FILTER_MAX_REQUEST_MS_CONFIG,
            Type.LONG,
            DOS_FILTER_MAX_REQUEST_MS_DEFAULT.toMillis(),
            Importance.LOW,
            DOS_FILTER_MAX_REQUEST_MS_DOC
        ).define(
            DOS_FILTER_MAX_IDLE_TRACKER_MS_CONFIG,
            Type.LONG,
            DOS_FILTER_MAX_IDLE_TRACKER_MS_DEFAULT.toMillis(),
            Importance.LOW,
            DOS_FILTER_MAX_IDLE_TRACKER_MS_DOC
        ).define(
            DOS_FILTER_INSERT_HEADERS_CONFIG,
            Type.BOOLEAN,
            DOS_FILTER_INSERT_HEADERS_DEFAULT,
            Importance.LOW,
            DOS_FILTER_INSERT_HEADERS_DOC
        ).define(
            DOS_FILTER_IP_WHITELIST_CONFIG,
            Type.LIST,
            DOS_FILTER_IP_WHITELIST_DEFAULT,
            Importance.LOW,
            DOS_FILTER_IP_WHITELIST_DOC
        ).define(
            DOS_FILTER_MANAGED_ATTR_CONFIG,
            Type.BOOLEAN,
            DOS_FILTER_MANAGED_ATTR_DEFAULT,
            Importance.LOW,
            DOS_FILTER_MANAGED_ATTR_DOC
        ).define(
            SERVER_CONNECTION_LIMIT,
            Type.INT,
            SERVER_CONNECTION_LIMIT_DEFAULT,
            Importance.LOW,
            SERVER_CONNECTION_LIMIT_DOC
        ).define(
            CONNECTOR_CONNECTION_LIMIT,
            Type.INT,
            CONNECTOR_CONNECTION_LIMIT_DEFAULT,
            Importance.LOW,
            CONNECTOR_CONNECTION_LIMIT_DOC
        ).define(
            HTTP2_ENABLED_CONFIG,
            Type.BOOLEAN,
            HTTP2_ENABLED_DEFAULT,
            Importance.LOW,
            HTTP2_ENABLED_DOC
        ).define(
            LISTENER_PROTOCOL_MAP_CONFIG,
            Type.LIST,
            LISTENER_PROTOCOL_MAP_DEFAULT,
            Importance.LOW,
            LISTENER_PROTOCOL_MAP_DOC
        ).define(
            PROXY_PROTOCOL_ENABLED_CONFIG,
            Type.BOOLEAN,
            PROXY_PROTOCOL_ENABLED_DEFAULT,
            Importance.LOW,
            PROXY_PROTOCOL_ENABLED_DOC
        ).define(
            NOSNIFF_PROTECTION_ENABLED,
            Type.BOOLEAN,
            NOSNIFF_PROTECTION_ENABLED_DEFAULT,
            Importance.LOW,
            NOSNIFF_PROTECTION_ENABLED_DOC
        ).define(
            SUPPRESS_STACK_TRACE_IN_RESPONSE,
            Type.BOOLEAN,
            SUPPRESS_STACK_TRACE_IN_RESPONSE_DEFAULT,
            Importance.LOW,
            SUPPRESS_STACK_TRACE_IN_RESPONSE_DOC
        ).define(
            MAX_RESPONSE_HEADER_SIZE_CONFIG,
            Type.INT,
            MAX_RESPONSE_HEADER_SIZE_DEFAULT,
            Importance.LOW,
            MAX_RESPONSE_HEADER_SIZE_DOC
        ).define(
            MAX_REQUEST_HEADER_SIZE_CONFIG,
            Type.INT,
            MAX_REQUEST_HEADER_SIZE_DEFAULT,
            Importance.LOW,
            MAX_REQUEST_HEADER_SIZE_DOC
        ).define(
            NETWORK_TRAFFIC_RATE_LIMIT_ENABLE_CONFIG,
            Type.BOOLEAN,
            NETWORK_TRAFFIC_RATE_LIMIT_ENABLE_DEFAULT,
            Importance.LOW,
            NETWORK_TRAFFIC_RATE_LIMIT_ENABLE_DOC
        ).define(
            NETWORK_TRAFFIC_RATE_LIMIT_BACKEND_CONFIG,
            Type.STRING,
            NETWORK_TRAFFIC_RATE_LIMIT_BACKEND_DEFAULT,
            Importance.LOW,
            NETWORK_TRAFFIC_RATE_LIMIT_BACKEND_DOC
        ).define(
            NETWORK_TRAFFIC_RATE_LIMIT_BYTES_PER_SEC_CONFIG,
            Type.INT,
            NETWORK_TRAFFIC_RATE_LIMIT_BYTES_PER_SEC_DEFAULT,
            NETWORK_TRAFFIC_RATE_LIMIT_BYTES_PER_SEC_VALIDATOR,
            Importance.LOW,
            NETWORK_TRAFFIC_RATE_LIMIT_BYTES_PER_SEC_DOC
        );
  }

  private static Time defaultTime = Time.SYSTEM;

  public RestConfig(ConfigDef definition, Map<?, ?> originals) {
    super(definition, originals);
    metricsContext = new RestMetricsContext(
            this.getString(METRICS_JMX_PREFIX_CONFIG),
            originalsWithPrefix(METRICS_CONTEXT_PREFIX));
  }

  public RestConfig(ConfigDef definition) {
    this(definition, new TreeMap<>());
  }

  public Time getTime() {
    return defaultTime;
  }

  public Map<String, Object> metricsReporterConfig() {
    return originalsWithPrefix(METRICS_REPORTER_CONFIG_PREFIX);
  }

  public RestMetricsContext getMetricsContext() {
    return metricsContext;
  }

  public static void validateHttpResponseHeaderConfig(String config) {
    try {
      // validate format
      String[] configTokens = config.trim().split("\\s+", 2);
      if (configTokens.length != 2) {
        throw new ConfigException(String.format("Invalid format of header config \"%s\". "
                + "Expected: \"[ation] [header name]:[header value]\"", config));
      }

      // validate action
      String method = configTokens[0].trim();
      validateHeaderConfigAction(method.toLowerCase());

      // validate header name and header value pair
      String header = configTokens[1];
      String[] headerTokens = header.trim().split(":");
      if (headerTokens.length > 2) {
        throw new ConfigException(
                String.format("Invalid format of header name and header value pair \"%s\". "
                + "Expected: \"[header name]:[header value]\"", header));
      }

      // validate header name
      String headerName = headerTokens[0].trim();
      if (headerName.contains(" ")) {
        throw new ConfigException(String.format("Invalid header name \"%s\". "
                + "The \"[header name]\" cannot contain whitespace", headerName));
      }
    } catch (ArrayIndexOutOfBoundsException e) {
      throw new ConfigException(String.format("Invalid header config \"%s\".", config), e);
    }
  }

  private static void validateHeaderConfigAction(String action) {
    /**
     * The following actions are defined following link.
     * {@link https://www.eclipse.org/jetty/documentation/current/header-filter.html}
     **/
    if (!Arrays.asList("set", "add", "setDate", "addDate")
            .stream()
            .anyMatch(action::equalsIgnoreCase)) {
      throw new ConfigException(String.format("Invalid header config action: \"%s\". "
              + "The action need be one of [\"set\", \"add\", \"setDate\", \"addDate\"]", action));
    }
  }

  public final boolean isDosFilterEnabled() {
    return getBoolean(DOS_FILTER_ENABLED_CONFIG);
  }

  public final int getDosFilterMaxRequestsPerConnectionPerSec() {
    return getInt(DOS_FILTER_MAX_REQUESTS_PER_CONNECTION_PER_SEC_CONFIG);
  }

  public final int getDosFilterMaxRequestsGlobalPerSec() {
    return getInt(DOS_FILTER_MAX_REQUESTS_PER_SEC_CONFIG);
  }

  public final Duration getDosFilterDelayMs() {
    return Duration.ofMillis(getLong(DOS_FILTER_DELAY_MS_CONFIG));
  }

  public final Duration getDosFilterMaxWaitMs() {
    return Duration.ofMillis(getLong(DOS_FILTER_MAX_WAIT_MS_CONFIG));
  }

  public final int getDosFilterThrottledRequests() {
    return getInt(DOS_FILTER_THROTTLED_REQUESTS_CONFIG);
  }

  public final Duration getDosFilterThrottleMs() {
    return Duration.ofMillis(getLong(DOS_FILTER_THROTTLE_MS_CONFIG));
  }

  public final Duration getDosFilterMaxRequestMs() {
    return Duration.ofMillis(getLong(DOS_FILTER_MAX_REQUEST_MS_CONFIG));
  }

  public final Duration getDosFilterMaxIdleTrackerMs() {
    return Duration.ofMillis(getLong(DOS_FILTER_MAX_IDLE_TRACKER_MS_CONFIG));
  }

  public final boolean getDosFilterInsertHeaders() {
    return getBoolean(DOS_FILTER_INSERT_HEADERS_CONFIG);
  }

  public final List<String> getDosFilterIpWhitelist() {
    return getList(DOS_FILTER_IP_WHITELIST_CONFIG);
  }

  public final boolean getDosFilterManagedAttr() {
    return getBoolean(DOS_FILTER_MANAGED_ATTR_CONFIG);
  }

  public final int getServerConnectionLimit() {
    return getInt(SERVER_CONNECTION_LIMIT);
  }

  public final int getConnectorConnectionLimit() {
    return getInt(CONNECTOR_CONNECTION_LIMIT);
  }

  public final boolean getSuppressStackTraceInResponse() {
    return getBoolean(SUPPRESS_STACK_TRACE_IN_RESPONSE);
  }

  public final List<NamedURI> getListeners() {
    return parseListeners(
        getList(RestConfig.LISTENERS_CONFIG),
        getListenerProtocolMap(),
        getInt(RestConfig.PORT_CONFIG),
        SUPPORTED_URI_SCHEMES,
        "http");
  }

  public final SslConfig getBaseSslConfig() {
    return new SslConfig(this);
  }

  private SslConfig getSslConfig(NamedURI listener) {
    String prefix =
        "listener.name." + Optional.ofNullable(listener.getName()).orElse("https") + ".";

    Map<String, Object> overridden = originals();
    overridden.putAll(filterByAndStripPrefix(originals(), prefix));

    return new SslConfig(new RestConfig(baseConfigDef(), overridden));
  }

  public final Map<NamedURI, SslConfig> getSslConfigs() {
    return getListeners().stream()
        .filter(listener -> listener.getUri().getScheme().equals("https"))
        .collect(toImmutableMap(Function.identity(), this::getSslConfig));
  }

  public final Map<String, String> getMap(String propertyName) {
    List<String> list = getList(propertyName);
    Map<String, String> map = new HashMap<>();
    for (String entry : list) {
      String[] keyValue = entry.split("\\s*:\\s*", -1);
      if (keyValue.length != 2) {
        throw new ConfigException("Map entry should have form <key>:<value>");
      }
      if (keyValue[0].isEmpty()) {
        throw new ConfigException(
            "Entry '" + entry + "' in " + propertyName + " does not specify a key");
      }
      if (map.containsKey(keyValue[0])) {
        throw new ConfigException(
            "Entry '" + keyValue[0] + "' was specified more than once in " + propertyName);
      }
      map.put(keyValue[0], keyValue[1]);
    }
    return map;
  }

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
      listeners = singletonList(defaultScheme + "://0.0.0.0:" + deprecatedPort);
    }

    List<NamedURI> uris = listeners.stream()
        .map(listener -> constructNamedURI(listener, listenerProtocolMap, supportedSchemes))
        .collect(Collectors.toList());
    List<NamedURI> namedUris =
        uris.stream().filter(uri -> uri.getName() != null).collect(Collectors.toList());

    if (namedUris.stream().map(NamedURI::getName).distinct().count() != namedUris.size()) {
      throw new ConfigException(
          "More than one listener was specified with same name. Listener names must be unique.");
    }

    return uris;
  }

  private static NamedURI constructNamedURI(
      String listener, Map<String,String> listenerProtocolMap, List<String> supportedSchemes) {
    URI uri;
    try {
      uri = new URI(listener);
    } catch (URISyntaxException e) {
      throw new ConfigException(
          "Listener '" + listener + "' is not a valid URI: " + e.getMessage());
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

  public final Map<String, String> getListenerProtocolMap() {
    Map<String, String> result = getMap(LISTENER_PROTOCOL_MAP_CONFIG)
        .entrySet()
        .stream()
        .collect(Collectors.toMap(
            e -> e.getKey().toLowerCase(),
            e -> e.getValue().toLowerCase()));
    for (Map.Entry<String, String> entry : result.entrySet()) {
      if (!SUPPORTED_URI_SCHEMES.contains(entry.getValue())) {
        throw new ConfigException(
            "Listener '" + entry.getKey()
            + "' specifies an unsupported protocol: " + entry.getValue());
      }
      if (SUPPORTED_URI_SCHEMES.contains(entry.getKey())) {
        // forbid http:https and https:http
        if (!entry.getKey().equals(entry.getValue())) {
          throw new ConfigException(
              "Listener name '" + entry.getKey() + "' is a supported protocol, so the "
              + "corresponding protocol specified in "
              + LISTENER_PROTOCOL_MAP_CONFIG + " must be '" + entry.getKey() + "'");
        }
      }
    }
    return result;
  }

  public final boolean getNetworkTrafficRateLimitEnable() {
    return getBoolean(NETWORK_TRAFFIC_RATE_LIMIT_ENABLE_CONFIG);
  }

  public final NetworkTrafficRateLimitBackend getNetworkTrafficRateLimitBackend() {
    return NetworkTrafficRateLimitBackend.valueOf(
        getString(NETWORK_TRAFFIC_RATE_LIMIT_BACKEND_CONFIG).toUpperCase());
  }

  public final int getNetworkTrafficRateLimitBytesPerSec() {
    return getInt(NETWORK_TRAFFIC_RATE_LIMIT_BYTES_PER_SEC_CONFIG);
  }

  /**
   * <p>A helper method for extracting multi-instance application configuration,
   * specified via property names of the form PREFIX[.LISTENER_NAME].PROPERTY.</p>
   *
   * <p>Returns either a single configuration instance with key "" which should be
   * used to construct an application instance bound to all listeners or one or
   * more named configuration instances which should be used to create
   * application instances bound to the corresponding named connector(s).
   * LISTENER_NAME is case insensitive.</p>
   *
   * <p>Examples:</p>
   *
   * <p><pre>
   * listeners=A://1.2.3.4:5000,B://6.7.8.9:4000,C://2.2.2.2:3000
   *  my.config.prefix.foo=1
   *  my.config.prefix.bar=2
   *  my.config.prefix.B.bar=3
   * Result:
   *  ConfigException: if any property is specified for a specific listener, it is not
   *  valid to also specify a property without the listener qualified.
   * </pre></p>
   *
   * <p><pre>
   * listeners=A://1.2.3.4:5000,B://6.7.8.9:0
   *  my.config.prefix.B.foo=1
   *  my.config.prefix.B.bar=2
   * Result:
   *  instance on B:
   *   foo=1
   *   bar=2
   * </pre></p>
   *
   * <p><pre>
   * listeners=A://1.2.3.4:5000,B://6.7.8.9:0
   *  my.config.prefix.foo=1
   *  my.config.prefix.bar=2
   * Result:
   *  instance on all interfaces (A + B):
   *   foo=1
   *   bar=2
   * </pre></p>
   *
   * <p><pre>
   * listeners=A://1.2.3.4:5000,B://6.7.8.9:0
   *  (no prefixed config)
   * Result:
   *  instance on all interfaces (A + B):
   *   (empty config)
   * </pre></p>
   */
  public static Map<String, Map<String, Object>> getInstanceConfig(
      String configPrefix,
      Set<String> listenerNames,
      Map<String, ?> originals) {

    Map<String, Map<String, Object>> grouped =
        getPrefixedConfigGroupedByListener(configPrefix, listenerNames, originals);

    if (grouped.size() == 1) {
      return grouped;
    }

    if (grouped.containsKey("")) {
      // In the future, we might choose to relax this constraint to allow more
      // convenient configuration in some scenarios. However, that will require
      // decisions on behavior, and for the user to consult the manual to understand
      // what these are.
      throw new ConfigException(
          "Some configuration properties prefixed by '" + configPrefix + "' specify a "
          + "listener name, others don't. Either all or none of these properties must "
          + "specify a listener name.");
    }

    return grouped;
  }

  static Map<String, Map<String, Object>> getPrefixedConfigGroupedByListener(
      String configPrefix,
      Set<String> listenerNames,
      Map<String, ?> originals) {
    Map<String, Map<String, Object>> grouped = new HashMap<>();
    for (String listenerName : listenerNames) {
      String prefix = configPrefix + listenerName.toLowerCase() + ".";
      Map<String, Object> prefixedConfigs = filterByAndStripPrefix(originals, prefix);
      if (!prefixedConfigs.isEmpty()) {
        grouped.put(listenerName, prefixedConfigs);
      }
    }
    if (grouped.isEmpty()) {
      grouped.put("", filterByAndStripPrefix(originals, configPrefix));
    }
    return grouped;
  }

  protected static HashMap<String, Object> filterByAndStripPrefix(
      Map<String, ?> configuration, String prefix) {
    HashMap<String, Object> stripped = new HashMap<>();
    for (Map.Entry<String, ?> entry : configuration.entrySet()) {
      if (entry.getKey().toLowerCase().startsWith(prefix)) {
        stripped.put(entry.getKey().substring(prefix.length()), entry.getValue());
      }
    }
    return stripped;
  }
}
