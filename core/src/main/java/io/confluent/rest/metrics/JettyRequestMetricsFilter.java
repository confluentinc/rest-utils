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

import static io.confluent.rest.metrics.MetricNameUtil.getMetricName;

import java.io.IOException;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Response.Status;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.metrics.Sensor.RecordingLevel;
import org.apache.kafka.common.metrics.stats.CumulativeCount;
import org.apache.kafka.common.metrics.stats.Rate;
import org.apache.kafka.common.metrics.stats.WindowedCount;

public class JettyRequestMetricsFilter implements Filter {

  private static final long SENSOR_EXPIRY_SECONDS = TimeUnit.HOURS.toSeconds(1);
  private static final String GROUP_NAME = "jetty-metrics";

  private Sensor sensor = null;

  public JettyRequestMetricsFilter(Metrics metrics, Map<String, String> metricTags,
      String jmxPrefix) {
    if (metrics != null) {
      String sensorNamePrefix = jmxPrefix + ":" + GROUP_NAME;
      SortedMap<String, String> instanceMetricsTags = new TreeMap<>(metricTags);
      String sensorTags =
          instanceMetricsTags.keySet().stream()
              .map(key -> ":" + instanceMetricsTags.get(key))
              .collect(Collectors.joining());
      String sensorName = sensorNamePrefix + ":jetty-request" + sensorTags;
      sensor = metrics.sensor(sensorName,
          null, SENSOR_EXPIRY_SECONDS, RecordingLevel.INFO, (Sensor[]) null);

      sensor.add(getMetricName(metrics, GROUP_NAME, "request-rate",
          "The average number of requests per second in Jetty layer",
          instanceMetricsTags), new Rate());
      sensor.add(getMetricName(metrics, GROUP_NAME, "request-count",
          "A windowed count of requests in Jetty layer",
          instanceMetricsTags), new WindowedCount());
      sensor.add(getMetricName(metrics, GROUP_NAME, "request-total",
          "A cumulative count of requests in Jetty layer",
          instanceMetricsTags), new CumulativeCount());
    }
  }

  @Override
  public void init(final FilterConfig filterConfig) throws ServletException {
    // do nothing
  }

  @Override
  public void doFilter(final ServletRequest request, final ServletResponse response,
      final FilterChain chain)
      throws IOException, ServletException {
    if (sensor != null) {
      sensor.record();
    }

    long memory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    if (memory >= 1073741824L) {
      ((HttpServletResponse) response).sendError(Status.SERVICE_UNAVAILABLE.getStatusCode(),
          "Server is under high load, please try again later");
      return;
    }

    chain.doFilter(request, response);
  }

  @Override
  public void destroy() {
    // do nothing
  }
}
