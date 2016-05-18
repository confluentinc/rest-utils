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

import io.confluent.common.config.AbstractConfig;
import io.confluent.common.config.ConfigDef;
import io.confluent.common.config.ConfigDef.Type;
import io.confluent.common.config.ConfigDef.Importance;
import io.confluent.common.utils.SystemTime;
import io.confluent.common.utils.Time;

import java.util.Map;
import java.util.TreeMap;

public class RestConfig extends AbstractConfig {
  public static final String DEBUG_CONFIG = "debug";
  protected static final String DEBUG_CONFIG_DOC =
      "Boolean indicating whether extra debugging information is generated in some " +
      "error response entities.";
  protected static final boolean DEBUG_CONFIG_DEFAULT = false;

  public static final String PORT_CONFIG = "port";
  protected static final String PORT_CONFIG_DOC = "DEPRECATED: port to listen on for new HTTP connections. Use " +
      "listeners instead.";
  protected static final int PORT_CONFIG_DEFAULT = 8080;

  public static final String LISTENERS_CONFIG = "listeners";
  protected static final String LISTENERS_DOC = "List of listeners. http and https are supported. Each " +
      "listener must include the protocol, hostname, and port. For example: http://myhost:8080," +
      "https://0.0.0.0:8081";
  protected static final String LISTENERS_DEFAULT = ""; // TODO: add a default value when `PORT_CONFIG` is deleted.

  public static final String RESPONSE_MEDIATYPE_PREFERRED_CONFIG = "response.mediatype.preferred";
  protected static final String RESPONSE_MEDIATYPE_PREFERRED_CONFIG_DOC =
      "An ordered list of the server's preferred media types used for responses, " +
      "from most preferred to least.";
  protected static final String RESPONSE_MEDIATYPE_PREFERRED_CONFIG_DEFAULT = "application/json";

  public static final String RESPONSE_MEDIATYPE_DEFAULT_CONFIG = "response.mediatype.default";
  protected static final String RESPONSE_MEDIATYPE_DEFAULT_CONFIG_DOC =
      "The default response media type that should be used if no specify types are requested in " +
      "an Accept header.";
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
      "The metrics system maintains a configurable number of samples over a fixed window size. " +
      "This configuration controls the size of the window. For example we might maintain two " +
      "samples each measured over a 30 second period. When a window expires we erase and " +
      "overwrite the oldest window.";
  protected static final long METRICS_SAMPLE_WINDOW_MS_DEFAULT = 30000;

  public static final String METRICS_NUM_SAMPLES_CONFIG = "metrics.num.samples";
  protected static final String METRICS_NUM_SAMPLES_DOC =
      "The number of samples maintained to compute metrics.";
  protected static final int METRICS_NUM_SAMPLES_DEFAULT = 2;

  public static final String METRICS_REPORTER_CLASSES_CONFIG = "metric.reporters";
  protected static final String METRICS_REPORTER_CLASSES_DOC =
      "A list of classes to use as metrics reporters. Implementing the " +
      "<code>MetricReporter</code> interface allows plugging in classes that will be notified " +
      "of new metric creation. The JmxReporter is always included to register JMX statistics.";
  protected static final String METRICS_REPORTER_CLASSES_DEFAULT = "";
  public static final String SSL_KEYSTORE_LOCATION_CONFIG = "ssl.keystore.location";
  protected static final String SSL_KEYSTORE_LOCATION_DOC =
      "Location of the keystore file to use for SSL. This is required for HTTPS.";
  protected static final String SSL_KEYSTORE_LOCATION_DEFAULT = "";
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
      "The algorithm used by the key manager factory for SSL connections. Leave blank to use Jetty's default.";
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
      "The algorithm used by the trust manager factory for SSL connections. Leave blank to use Jetty's default.";
  protected static final String SSL_TRUSTMANAGER_ALGORITHM_DEFAULT = "";
  public static final String SSL_PROTOCOL_CONFIG = "ssl.protocol";
  protected static final String SSL_PROTOCOL_DOC =
      "The SSL protocol used to generate the SslContextFactory.";
  protected static final String SSL_PROTOCOL_DEFAULT = "TLS";
  public static final String SSL_PROVIDER_CONFIG = "ssl.provider";
  protected static final String SSL_PROVIDER_DOC =
      "The SSL security provider name. Leave blank to use Jetty's default.";
  protected static final String SSL_PROVIDER_DEFAULT = "";
  public static final String SSL_CLIENT_AUTH_CONFIG = "ssl.client.auth";
  protected static final String SSL_CLIENT_AUTH_DOC =
      "Whether or not to require the https client to authenticate via the server's trust store.";
  protected static final boolean SSL_CLIENT_AUTH_DEFAULT = false;
  public static final String SSL_ENABLED_PROTOCOLS_CONFIG = "ssl.enabled.protocols";
  protected static final String SSL_ENABLED_PROTOCOLS_DOC =
      "The list of protocols enabled for SSL connections. Comma-separated list. " +
      "Leave blank to use Jetty's defaults.";
  protected static final String SSL_ENABLED_PROTOCOLS_DEFAULT = "";
  public static final String SSL_CIPHER_SUITES_CONFIG = "ssl.cipher.suites";
  protected static final String SSL_CIPHER_SUITES_DOC =
      "A list of SSL cipher suites. Leave blank to use Jetty's defaults.";
  protected static final String SSL_CIPHER_SUITES_DEFAULT = "";
  public static final String SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG = "ssl.endpoint.identification.algorithm";
  protected static final String SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_DOC =
      "The endpoint identification algorithm to validate the server hostname using the server certificate. " +
      "Leave blank to use Jetty's default.";
  protected static final String SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_DEFAULT = "";

  public static ConfigDef baseConfigDef() {
    return new ConfigDef()
        .define(DEBUG_CONFIG, Type.BOOLEAN,
                DEBUG_CONFIG_DEFAULT, Importance.LOW, DEBUG_CONFIG_DOC)
        .define(PORT_CONFIG, Type.INT, PORT_CONFIG_DEFAULT, Importance.LOW,
                PORT_CONFIG_DOC)
        .define(LISTENERS_CONFIG, Type.LIST, LISTENERS_DEFAULT, Importance.HIGH,
                LISTENERS_DOC)
        .define(RESPONSE_MEDIATYPE_PREFERRED_CONFIG, Type.LIST,
                RESPONSE_MEDIATYPE_PREFERRED_CONFIG_DEFAULT, Importance.LOW,
                RESPONSE_MEDIATYPE_PREFERRED_CONFIG_DOC)
        .define(RESPONSE_MEDIATYPE_DEFAULT_CONFIG, Type.STRING,
                RESPONSE_MEDIATYPE_DEFAULT_CONFIG_DEFAULT, Importance.LOW,
                RESPONSE_MEDIATYPE_DEFAULT_CONFIG_DOC)
        .define(SHUTDOWN_GRACEFUL_MS_CONFIG, Type.INT,
                SHUTDOWN_GRACEFUL_MS_DEFAULT, Importance.LOW,
                SHUTDOWN_GRACEFUL_MS_DOC)
        .define(ACCESS_CONTROL_ALLOW_ORIGIN_CONFIG, Type.STRING,
                ACCESS_CONTROL_ALLOW_ORIGIN_DEFAULT, Importance.LOW,
                ACCESS_CONTROL_ALLOW_ORIGIN_DOC)
        .define(ACCESS_CONTROL_ALLOW_METHODS, Type.STRING,
                ACCESS_CONTROL_ALLOW_METHODS_DEFAULT, Importance.LOW,
                ACCESS_CONTROL_ALLOW_METHODS_DOC)
        .define(REQUEST_LOGGER_NAME_CONFIG, Type.STRING,
                REQUEST_LOGGER_NAME_DEFAULT, Importance.LOW,
                REQUEST_LOGGER_NAME_DOC)
        .define(METRICS_JMX_PREFIX_CONFIG, Type.STRING,
                METRICS_JMX_PREFIX_DEFAULT, Importance.LOW, METRICS_JMX_PREFIX_DOC)
        .define(METRICS_REPORTER_CLASSES_CONFIG, Type.LIST,
                METRICS_REPORTER_CLASSES_DEFAULT, Importance.LOW, METRICS_REPORTER_CLASSES_DOC)
        .define(METRICS_SAMPLE_WINDOW_MS_CONFIG,
                Type.LONG,
                METRICS_SAMPLE_WINDOW_MS_DEFAULT,
                ConfigDef.Range.atLeast(0),
                Importance.LOW,
                METRICS_SAMPLE_WINDOW_MS_DOC)
        .define(METRICS_NUM_SAMPLES_CONFIG, Type.INT,
                METRICS_NUM_SAMPLES_DEFAULT, ConfigDef.Range.atLeast(1),
                Importance.LOW, METRICS_NUM_SAMPLES_DOC)
        .define(SSL_KEYSTORE_LOCATION_CONFIG, Type.STRING,
                SSL_KEYSTORE_LOCATION_DEFAULT, Importance.HIGH,
                SSL_KEYSTORE_LOCATION_DOC)
        .define(SSL_KEYSTORE_PASSWORD_CONFIG, Type.STRING,
                SSL_KEYSTORE_PASSWORD_DEFAULT, Importance.HIGH,
                SSL_KEYSTORE_PASSWORD_DOC)
        .define(SSL_KEY_PASSWORD_CONFIG, Type.STRING,
                SSL_KEY_PASSWORD_DEFAULT, Importance.HIGH,
                SSL_KEY_PASSWORD_DOC)
        .define(SSL_KEYSTORE_TYPE_CONFIG, Type.STRING,
                SSL_KEYSTORE_TYPE_DEFAULT, Importance.MEDIUM,
                SSL_KEYSTORE_TYPE_DOC)
        .define(SSL_KEYMANAGER_ALGORITHM_CONFIG, Type.STRING,
                SSL_KEYMANAGER_ALGORITHM_DEFAULT, Importance.LOW,
                SSL_KEYMANAGER_ALGORITHM_DOC)
        .define(SSL_TRUSTSTORE_LOCATION_CONFIG, Type.STRING,
                SSL_TRUSTSTORE_LOCATION_DEFAULT, Importance.HIGH,
                SSL_TRUSTSTORE_LOCATION_DOC)
        .define(SSL_TRUSTSTORE_PASSWORD_CONFIG, Type.STRING,
                SSL_TRUSTSTORE_PASSWORD_DEFAULT, Importance.HIGH,
                SSL_TRUSTSTORE_PASSWORD_DOC)
        .define(SSL_TRUSTSTORE_TYPE_CONFIG, Type.STRING,
                SSL_TRUSTSTORE_TYPE_DEFAULT, Importance.MEDIUM,
                SSL_TRUSTSTORE_TYPE_DOC)
        .define(SSL_TRUSTMANAGER_ALGORITHM_CONFIG, Type.STRING,
                SSL_TRUSTMANAGER_ALGORITHM_DEFAULT, Importance.LOW,
                SSL_TRUSTMANAGER_ALGORITHM_DOC)
        .define(SSL_PROTOCOL_CONFIG, Type.STRING,
                SSL_PROTOCOL_DEFAULT, Importance.MEDIUM,
                SSL_PROTOCOL_DOC)
        .define(SSL_PROVIDER_CONFIG, Type.STRING,
                SSL_PROVIDER_DEFAULT, Importance.MEDIUM,
                SSL_PROVIDER_DOC)
        .define(SSL_CLIENT_AUTH_CONFIG, Type.BOOLEAN,
                SSL_CLIENT_AUTH_DEFAULT, Importance.MEDIUM,
                SSL_CLIENT_AUTH_DOC)
        .define(SSL_ENABLED_PROTOCOLS_CONFIG, Type.LIST,
                SSL_ENABLED_PROTOCOLS_DEFAULT, Importance.MEDIUM,
                SSL_ENABLED_PROTOCOLS_DOC)
        .define(SSL_CIPHER_SUITES_CONFIG, Type.LIST,
                SSL_CIPHER_SUITES_DEFAULT, Importance.LOW,
                SSL_CIPHER_SUITES_DOC)
        .define(SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, Type.STRING,
                SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_DEFAULT, Importance.LOW,
                SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_DOC);
  }

  private static Time defaultTime = new SystemTime();

  public RestConfig(ConfigDef definition, Map<?, ?> originals) {
    super(definition, originals);
  }

  public RestConfig(ConfigDef definition) {
    super(definition, new TreeMap<Object,Object>());
  }

  public Time getTime() {
    return defaultTime;
  }
}
