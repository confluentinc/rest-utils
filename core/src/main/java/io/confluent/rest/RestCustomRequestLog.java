/*
 * Copyright 2014 - 2022 Confluent Inc.
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

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.MetricNameTemplate;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.metrics.Sensor.RecordingLevel;
import org.apache.kafka.common.metrics.stats.CumulativeCount;
import org.apache.kafka.common.metrics.stats.Rate;
import org.apache.kafka.common.metrics.stats.WindowedCount;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

public class RestCustomRequestLog extends CustomRequestLog {

  private static final long SENSOR_EXPIRY_SECONDS = TimeUnit.HOURS.toSeconds(1);
  private static final String GROUP_NAME = "jersey-metrics";

  Sensor fourTwoNineSensor = null;

  public RestCustomRequestLog(final Writer writer, final String formatString, Metrics metrics,
      Map<String, String> metricTags, String jmxPrefix) {
    super(writer, formatString);

    if (metrics != null) {
      String sensorNamePrefix = jmxPrefix + ":" + GROUP_NAME + "";
      SortedMap<String, String> instanceMetricsTags = new TreeMap<>(metricTags);
      instanceMetricsTags.put("http_status_code", "429");
      String sensorTags =
          instanceMetricsTags.keySet().stream()
              .map(key -> ":" + instanceMetricsTags.get(key))
              .collect(Collectors.joining());
      String sensorName = sensorNamePrefix + ":request-errors" + sensorTags;
      fourTwoNineSensor = metrics.sensor(sensorName,
          null, SENSOR_EXPIRY_SECONDS, RecordingLevel.INFO, (Sensor[]) null);

      fourTwoNineSensor.add(getMetricName(metrics, "request-error-rate",
          "The average number of requests per second that resulted in 429 error responses",
          instanceMetricsTags), new Rate());
      fourTwoNineSensor.add(getMetricName(metrics, "request-error-count",
          "A windowed count of requests that resulted in 429 HTTP error responses",
          instanceMetricsTags), new WindowedCount());
      fourTwoNineSensor.add(getMetricName(metrics, "request-error-total",
          "A cumulative count of requests that resulted in 429 HTTP error responses",
          instanceMetricsTags), new CumulativeCount());
    }
  }

  @Override
  public void log(Request request, Response response) {
    super.log(request, response);
    if (fourTwoNineSensor != null && response != null && response.getStatus() == 429) {
      fourTwoNineSensor.record();
    }
  }

  private MetricName getMetricName(Metrics metrics, String name, String doc,
      Map<String, String> metricsTags) {
    return metrics.metricInstance(
        new MetricNameTemplate(name, GROUP_NAME, doc, metricsTags.keySet()), metricsTags);
  }

}
