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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.MetricNameTemplate;
import org.apache.kafka.common.metrics.MeasurableStat;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.metrics.Sensor.RecordingLevel;
import org.apache.kafka.common.metrics.stats.CumulativeSum;
import org.apache.kafka.common.metrics.stats.Rate;
import org.apache.kafka.common.metrics.stats.SampledStat;
import org.junit.jupiter.api.Test;

class JettyRequestMetricsFilterTest {

  private static final String TEST_JMX_PREFIX = "test-kafka-rest";

  private final Metrics metrics = mock(Metrics.class);
  private final Sensor sensor = mock(Sensor.class);
  private final Map<String, String> allTags = new HashMap<>();
  private Filter jettyRequestMetricsFilter;

  @Test
  public void test_doFilter_nullMetrics() throws Exception {
    // Prepare and Act
    jettyRequestMetricsFilter = new JettyRequestMetricsFilter(null, ImmutableMap.of(),
        TEST_JMX_PREFIX);
    jettyRequestMetricsFilter.doFilter(
        mock(ServletRequest.class), mock(ServletResponse.class), mock(FilterChain.class));

    // Check
    // sensor is not created
    verify(sensor, never()).add(any(MetricName.class), any());
    // null metrics, therefore, we don't record anything
    verify(sensor, never()).record();
  }

  @Test
  public void test_doFilter_metricsNotNull() throws Exception {
    // Prepare
    setMockedMetricsObjectResponses(allTags);

    // Act
    jettyRequestMetricsFilter = new JettyRequestMetricsFilter(metrics, ImmutableMap.of(),
        TEST_JMX_PREFIX);
    jettyRequestMetricsFilter.doFilter(
        mock(ServletRequest.class), mock(ServletResponse.class), mock(FilterChain.class));

    // Check
    verify(sensor, times(3)).add(any(MetricName.class), any(MeasurableStat.class));
    // the sensor records it
    verifySensor(sensor, allTags, 1);
  }

  private void setMockedMetricsObjectResponses(Map<String, String> allTags) {
    MetricName rateName = new MetricName("request-rate", "jetty-metrics",
        "The average number of requests per second in Jetty layer", allTags);
    when(metrics.metricInstance(
        new MetricNameTemplate("request-rate", "jetty-metrics",
            "The average number of requests per second in Jetty layer",
            allTags.keySet()), allTags)).thenReturn(rateName);

    MetricName countName = new MetricName("request-count", "jetty-metrics",
        "A windowed count of requests in Jetty layer", allTags);
    when(metrics.metricInstance(
        new MetricNameTemplate("request-count", "jetty-metrics",
            "A windowed count of requests in Jetty layer",
            allTags.keySet()), allTags)).thenReturn(countName);

    MetricName cumulativeCountName = new MetricName("request-total", "jetty-metrics",
        "A cumulative count of requests in Jetty layer", allTags);
    when(metrics.metricInstance(
        new MetricNameTemplate("request-total", "jetty-metrics",
            "A cumulative count of requests in Jetty layer",
            allTags.keySet()), allTags)).thenReturn(cumulativeCountName);

    when(metrics.sensor(anyString(), any(), anyLong(), any(RecordingLevel.class),
        (Sensor[]) any())).thenReturn(sensor);
  }

  private void verifySensor(Sensor sensor, Map<String, String> allTags, int timesRecorded) {
    MetricName rateName = new MetricName(
        "request-rate",
        "jetty-metrics",
        "The average number of requests per second in Jetty layer",
        allTags);

    MetricName countName = new MetricName(
        "request-count",
        "jetty-metrics",
        "A windowed count of requests in Jetty layer",
        allTags);

    MetricName cumulativeCountName = new MetricName(
        "request-total",
        "jetty-metrics",
        "A cumulative count of requests in Jetty layer",
        allTags);

    verify(sensor).add(eq(rateName), any(Rate.class));
    verify(sensor).add(eq(countName), any(SampledStat.class));
    verify(sensor).add(eq(cumulativeCountName), any(CumulativeSum.class));
    verify(sensor, times(timesRecorded)).record();
  }
}
