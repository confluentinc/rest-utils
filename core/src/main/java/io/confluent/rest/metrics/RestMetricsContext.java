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

package io.confluent.rest.metrics;

import static org.apache.kafka.clients.CommonClientConfigs.METRICS_CONTEXT_PREFIX;

import io.confluent.rest.RestConfig;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.common.metrics.MetricsContext;

public class RestMetricsContext implements MetricsContext {
  /**
   * Client or Service's metadata map.
   */
  protected final Map<String, String> contextLabels;

  /**
   * {@link io.confluent.rest.Application} {@link MetricsContext} configuration.
   */
  public RestMetricsContext(RestConfig config) {
    /* Copy all configuration properties prefixed into metadata instance. */
    this(config.originalsWithPrefix(METRICS_CONTEXT_PREFIX));

    /* JMX_PREFIX is synonymous with MetricsContext.NAMESPACE */
    this.setLabel(MetricsContext.NAMESPACE,
            config.getString(RestConfig.METRICS_JMX_PREFIX_CONFIG));
  }

  private RestMetricsContext(Map<String, Object> config) {
    contextLabels = new HashMap<>();
    config.forEach((key, value) -> contextLabels.put(key, value.toString()));
  }

  /**
   * Sets a {@link MetricsContext} key, value pair.
   */
  protected void setLabel(String labelKey, String labelValue) {
    this.contextLabels.put(labelKey, labelValue);
  }

  /**
   * Returns the value associated with  the specified label else
   * {@code null}.
   */
  public String getLabel(String label) {
    return contextLabels.get(label);
  }

  /**
   * Returns {@link MetricsContext} as an unmodifiable Map.
   */
  @Override
  public Map<String, String> contextLabels() {
    return Collections.unmodifiableMap(contextLabels);
  }
}
