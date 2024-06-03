/*
 * Copyright 2014 - 2023 Confluent Inc.
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

import java.util.Map;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.MetricNameTemplate;
import org.apache.kafka.common.metrics.Metrics;

class MetricNameUtil {
  private MetricNameUtil() {
    // prevent instantiation
  }

  static MetricName getMetricName(Metrics metrics, String groupName, String name, String doc,
      Map<String, String> metricsTags) {
    return metrics.metricInstance(
        new MetricNameTemplate(name, groupName, doc, metricsTags.keySet()), metricsTags);
  }
}
