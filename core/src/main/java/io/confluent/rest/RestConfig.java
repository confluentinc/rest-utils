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

import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigDef.Type;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.utils.Time;

import io.confluent.rest.extension.ResourceExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class RestConfig extends AbstractConfig {
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
      "List of listeners. http and https are supported. Each listener must include the protocol, "
      + "hostname, and port. For example: http://myhost:8080, https://0.0.0.0:8081";
  // TODO: add a default value when `PORT_CONFIG` is deleted.
  protected static final String LISTENERS_DEFAULT = "";

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

  public static final String ACCESS_CONTROL_ALLOW_METHODS = "access.control.allow.methods";
  protected static final String ACCESS_CONTROL_ALLOW_METHODS_DOC =
      "Set value to Jetty Access-Control-Allow-Origin header for specified methods";
  protected static final String ACCESS_CONTROL_ALLOW_METHODS_DEFAULT = "";

  public static final String ACCESS_CONTROL_ALLOW_HEADERS = "access.control.allow.headers";
  protected static final String ACCESS_CONTROL_ALLOW_HEADERS_DOC =
      "Set value to Jetty Access-Control-Allow-Origin header for specified headers. "
      + "Leave blank to use Jetty's default.";
  protected static final String ACCESS_CONTROL_ALLOW_HEADERS_DEFAULT = "";

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
      Collections.unmodifiableList(Arrays.asList("*"));

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
          "The minimum number of threads will be startred for HTTP Servlet server.";
  public static final int THREAD_POOL_MIN_DEFAULT = 8;

  public static final String THREAD_POOL_MAX_CONFIG = "thread.pool.max";
  public static final String THREAD_POOL_MAX_DOC =
          "The maxinum number of threads will be startred for HTTP Servlet server.";
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
  @SuppressWarnings("deprecation")
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
            Collections.emptyList(),
            Importance.LOW,
            RESOURCE_EXTENSION_CLASSES_DOC
        ).define(
            REST_SERVLET_INITIALIZERS_CLASSES_CONFIG,
            Type.LIST,
            Collections.emptyList(),
            Importance.LOW,
            REST_SERVLET_INITIALIZERS_CLASSES_DOC
        ).define(
            WEBSOCKET_SERVLET_INITIALIZERS_CLASSES_CONFIG,
            Type.LIST,
            Collections.emptyList(),
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
        );
  }

  private static Time defaultTime = Time.SYSTEM;

  public RestConfig(ConfigDef definition, Map<?, ?> originals) {
    super(definition, originals);
  }

  public RestConfig(ConfigDef definition) {
    super(definition, new TreeMap<>());
  }

  public Time getTime() {
    return defaultTime;
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
}
