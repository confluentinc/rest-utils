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
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.MetricNameTemplate;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.metrics.Sensor.RecordingLevel;
import org.apache.kafka.common.metrics.stats.CumulativeCount;
import org.apache.kafka.common.metrics.stats.Rate;
import org.apache.kafka.common.metrics.stats.WindowedCount;
import org.eclipse.jetty.servlets.DoSFilter;
import org.eclipse.jetty.servlets.DoSFilter.Action;
import org.eclipse.jetty.servlets.DoSFilter.OverLimit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jetty DosFilterListener that records 429 metrics on DoSFilter of Jetty layer.
 * Note: the metrics are independent of Jersey metrics in MetricsResourceMethodApplicationListener
 */
public class Jetty429MetricsDosFilterListener extends DoSFilter.Listener {
  private static final Logger log = LoggerFactory.getLogger(Jetty429MetricsDosFilterListener.class);

  private static final long SENSOR_EXPIRY_SECONDS = TimeUnit.HOURS.toSeconds(1);
  private static final String GROUP_NAME = "jetty-metrics";

  private Sensor fourTwoNineSensor = null;

  public Jetty429MetricsDosFilterListener(Metrics metrics, Map<String, String> metricTags,
      String jmxPrefix) {
    if (metrics != null) {
      String sensorNamePrefix = jmxPrefix + ":" + GROUP_NAME;
      SortedMap<String, String> instanceMetricsTags = new TreeMap<>(metricTags);
      instanceMetricsTags.put("http_status_code", "429");
      String sensorTags =
          instanceMetricsTags.keySet().stream()
              .map(key -> ":" + instanceMetricsTags.get(key))
              .collect(Collectors.joining());
      String sensorName = sensorNamePrefix + ":request-errors" + sensorTags;
      fourTwoNineSensor = metrics.sensor(sensorName,
          null, SENSOR_EXPIRY_SECONDS, RecordingLevel.INFO, (Sensor[]) null);

      fourTwoNineSensor.add(getMetricName(metrics,
          "request-error-rate",
          "The average number of requests per second that resulted in 429 HTTP error "
              + "responses in Jetty layer",
          instanceMetricsTags), new Rate());
      fourTwoNineSensor.add(getMetricName(metrics, "request-error-count",
          "A windowed count of requests that resulted in 429 HTTP error responses"
              + " in Jetty layer",
          instanceMetricsTags), new WindowedCount());
      fourTwoNineSensor.add(getMetricName(metrics, "request-error-total",
          "A cumulative count of requests that resulted in 429 HTTP error responses"
              + " in Jetty layer",
          instanceMetricsTags), new CumulativeCount());
    }
  }

  @Override
  public Action onRequestOverLimit(HttpServletRequest request, OverLimit overlimit,
      DoSFilter dosFilter) {
    // KREST-10418: we don't use super function to get action object because
    // it will log a WARN line, in order to reduce verbosity
    Action action = Action.fromDelay(dosFilter.getDelayMs());
    if (fourTwoNineSensor != null && action.equals(Action.REJECT)) {
      fourTwoNineSensor.record();
    }
    return action;
  }

  private MetricName getMetricName(Metrics metrics, String name, String doc,
      Map<String, String> metricsTags) {
    return metrics.metricInstance(
        new MetricNameTemplate(name, GROUP_NAME, doc, metricsTags.keySet()), metricsTags);
  }
}
