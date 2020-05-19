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
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.common.metrics.MetricsContext;

public class RestMetricsContext  implements MetricsContext {
  public static final String METRICS_CONTEXT_PREFIX = "metrics.context.";
  public static final String METRICS_RESOURCE_NAME = "resource.type";

  /**
   * Client or Service's metadata map.
   */
  private final Map<String, String> metadata = new HashMap<>();

  /**
   * @param config RestConfig instance
   */
  public RestMetricsContext(RestConfig config) {
    /* Copy all configuration properties prefixed into metadata instance. */
    this(config.originalsWithPrefix(METRICS_CONTEXT_PREFIX));

    /* JMX_PREFIX is synonymous with MetricsContext.NAMESPACE */
    metadata.putIfAbsent(MetricsContext.NAMESPACE,
            config.getString(RestConfig.METRICS_JMX_PREFIX_CONFIG));

    /*
     * RestApplication are most likely to be top-level compositions.
     * Use JMX_PREFIX if METRICS_RESOURCE_NAME is not explicitly set.
     */
    metadata.putIfAbsent(METRICS_RESOURCE_NAME, metadata.get(MetricsContext.NAMESPACE));
  }

  public RestMetricsContext(Map<String, Object> config) {
    config.forEach((key, value) -> metadata.put(key, (String) value));
  }
  
  public String getResourceName() {
    return metadata.get(METRICS_RESOURCE_NAME);
  }

  public String getNameSpace() {
    return metadata.get(MetricsContext.NAMESPACE);
  }

  public MetricsContext newNamespace(String namespace) {
    Map<String, Object> child = new HashMap<>(this.metadata);
    child.put(MetricsContext.NAMESPACE, namespace);

    return new RestMetricsContext(child);
  }

  /**
   * Returns client's metadata map.
   *
   * @return metadata fields
   */
  public Map<String, String> metadata() {
    return metadata;
  }
}
