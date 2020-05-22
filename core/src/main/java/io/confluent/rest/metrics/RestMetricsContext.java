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

package io.confluent.rest.metrics;

import io.confluent.rest.RestConfig;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.common.metrics.MetricsContext;

public class RestMetricsContext<T> implements MetricsContext {
  /**
   * MetricsContext Label's for use by Confluent's TelemetryReporter
   */
  public static final String METRICS_CONTEXT_PREFIX = "metrics.context.";
  public static final String RESOURCE_LABEL_PREFIX = "resource.";
  public static final String RESOURCE_LABEL_TYPE = RESOURCE_LABEL_PREFIX + "type";

  /**
   * Client or Service's metadata map.
   */
  private final Map<String, String> metadata;

  /**
   * {@link io.confluent.rest.Application} {@link MetricsContext} configuration.
   */
  public RestMetricsContext(RestConfig config) {
    /* Copy all configuration properties prefixed into metadata instance. */
    this(config.originalsWithPrefix(METRICS_CONTEXT_PREFIX));

    /* JMX_PREFIX is synonymous with MetricsContext.NAMESPACE */
    this.putLabel(MetricsContext.NAMESPACE,
            config.getString(RestConfig.METRICS_JMX_PREFIX_CONFIG));

    /* Never overwrite preexisting resource labels */
    this.putResourceLabel(RESOURCE_LABEL_TYPE,
            config.getString(RestConfig.METRICS_JMX_PREFIX_CONFIG));
  }

  private RestMetricsContext(Map<String, Object> config) {
    this.metadata = new HashMap<>();
    config.forEach((key, value) -> metadata.put(key, value.toString()));
  }

  /**
   * Sets a {@link MetricsContext} key, value pair.
   */
  protected void putLabel(String labelKey, String labelValue) {
    /* Remove resource label if present */
    this.metadata.put(labelKey.replace(RESOURCE_LABEL_PREFIX, ""),
            labelValue);
  }

  /**
   * Sets {@link MetricsContext} resource label if not previously set.
   * Returns null if the resource value was not previously set else
   * returns the current value.
   */
  protected String putResourceLabel(String resource, String value) {
    return this.metadata.putIfAbsent(resource, value);
  }

  /**
   * Returns {@link MetricsContext} Resource type.
   */
  public String getResourceType() {
    return metadata.get(RESOURCE_LABEL_TYPE);
  }

  /**
   * Returns {@link MetricsContext} namespace.
   */
  public String getNamespace() {
    return metadata.get(MetricsContext.NAMESPACE);
  }

  /**
   * Returns {@link MetricsContext} as an immutable Map.
   */
  public Map<String, String> metadata() {
    return Collections.unmodifiableMap(metadata);
  }
}
