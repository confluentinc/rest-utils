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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.common.metrics.MetricsContext;

public class RestMetricsContext implements MetricsContext {
  /**
   * Client or Service's metadata map.
   */
  protected final Map<String, String> contextLabels;

  public RestMetricsContext(String namespace,
                            Map<String, Object> config) {
    contextLabels = new HashMap<>();
    contextLabels.put(MetricsContext.NAMESPACE, namespace);
    config.forEach((key, value) -> contextLabels.put(key, value.toString()));
  }

  protected void setContextLabels(Map<String, String> labels) {
    labels.forEach(this::setLabel);
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
