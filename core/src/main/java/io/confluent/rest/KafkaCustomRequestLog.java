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

import static java.util.Collections.emptyMap;

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
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class KafkaCustomRequestLog extends CustomRequestLog {

  private static final Logger log = Log.getLogger(KafkaCustomRequestLog.class);
  private static final long SENSOR_EXPIRY_SECONDS = TimeUnit.HOURS.toSeconds(1);
  private static final String GROUP_NAME = "request-errors-429";

  private final Metrics metrics;
  private final Map<String, String> metricsTags;
  private final String sensorNamePrefix;

  public KafkaCustomRequestLog(final Writer writer, final String formatString, Metrics metrics,
      Map<String, String> metricTags, String jmxPrefix) {
    super(writer, formatString);

    if (metrics == null) {
      this.metrics = null;
      log.warn("Metrics object is null");
    } else {
      this.metrics = metrics;
    }
    this.metricsTags = (metricTags != null) ? metricTags : emptyMap();
    this.sensorNamePrefix = jmxPrefix + ":" + GROUP_NAME + "";
  }

  @Override
  public void log(Request request, Response response) {
    super.log(request, response);

    if (metrics != null && response != null && response.getStatus() == 429) {

      // Use the sorted tags to make a unique name for the Sensor, so that LKCs only record metrics
      // against their own sensor
      SortedMap<String, String> instanceMetricsTags = new TreeMap<>(metricsTags);

      // Split the URL case-insensitively on 'lkc' to add to the metrics tags and generate the
      // unique to this LKC metric name.
      // If 'lkc' is missing then this is not CC so just carry on
      String[] lkcSplit = request.getRequestURL().toString().split("(?i)lkc");
      if (lkcSplit.length > 1) {
        // Split on non-alphanumeric characters so we cope with hitting the LKC in the hostname
        // (v4 network) or in the path (v3 network).
        // The first array element should be - unless we are very unlucky with eg a v3 hostname
        // that happens to contain lkc.
        String[] lkcIdSplit = lkcSplit[1].split("\\W+");
        if (lkcIdSplit.length > 1) {
          String fullLkc = "lkc-" + lkcIdSplit[1];
          instanceMetricsTags.put("tenant", fullLkc);
        }
      }
      instanceMetricsTags.put("http_status_code", "429");

      String sensorTags =
          instanceMetricsTags.keySet().stream()
              .map(key -> ":" + instanceMetricsTags.get(key))
              .collect(Collectors.joining());

      String sensorName = sensorNamePrefix + sensorTags;

      Sensor fourTwoNineSensor = metrics.sensor(sensorName,
          null, SENSOR_EXPIRY_SECONDS, RecordingLevel.INFO, (Sensor[]) null);

      // Need to make the metrics each time unfortunately as at Request Log creation time we don't
      // have the right information. Kafka checks for duplicates before actually making the new
      // object so this is OK(ish)
      fourTwoNineSensor.add(getMetricName("request-error-rate-429",
          "The average number of requests per second that resulted in 429 error responses",
          instanceMetricsTags), new Rate());

      fourTwoNineSensor.add(getMetricName("request-error-count-429",
          "A windowed count of requests that resulted in 429 HTTP error responses",
          instanceMetricsTags), new WindowedCount());

      fourTwoNineSensor.add(getMetricName("request-error-total-429",
          "A cumulative count of requests that resulted in 429 HTTP error responses",
          instanceMetricsTags), new CumulativeCount());

      fourTwoNineSensor.record();

    }
  }

  private MetricName getMetricName(String name, String doc, Map<String, String> metricsTags) {
    return metrics.metricInstance(
        new MetricNameTemplate(name, GROUP_NAME, doc, metricsTags.keySet()), metricsTags);
  }

}
