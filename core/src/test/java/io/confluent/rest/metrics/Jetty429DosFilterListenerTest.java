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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import javax.servlet.http.HttpServletRequest;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.MetricNameTemplate;
import org.apache.kafka.common.metrics.MeasurableStat;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.metrics.Sensor.RecordingLevel;
import org.apache.kafka.common.metrics.stats.CumulativeSum;
import org.apache.kafka.common.metrics.stats.Rate;
import org.apache.kafka.common.metrics.stats.SampledStat;
import org.eclipse.jetty.servlets.DoSFilter;
import org.eclipse.jetty.servlets.DoSFilter.Action;
import org.eclipse.jetty.servlets.DoSFilter.OverLimit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Jetty429DosFilterListenerTest {

  private static final String TEST_JMX_PREFIX = "test-kafka-rest";
  private final Metrics metrics = mock(Metrics.class);
  private final Sensor fourTwoNineSensor = mock(Sensor.class);
  private final DoSFilter mockDoSFiler = mock(DoSFilter.class);
  private Jetty429DosFilterListener jetty429DosFilterListener;
  private final Map<String, String> allTags = new HashMap<String, String>() {{
    put("http_status_code", "429");
  }};


  @ParameterizedTest
  @ValueSource(
      longs = {
          -1, // this means the action is REJECTED
          0L, // this means the action is THROTTLE
          1L // this means the action is DELAY
      }
  )
  public void test_onRequestOverLimit_nullMetrics(long delayMs) {
    // Prepare
    // mock the behaviour for returning Action
    when(mockDoSFiler.getDelayMs()).thenReturn(delayMs);

    // Act
    jetty429DosFilterListener = new Jetty429DosFilterListener(null, ImmutableMap.of(),
        TEST_JMX_PREFIX);
    Action action = jetty429DosFilterListener.onRequestOverLimit(mock(HttpServletRequest.class),
        mock(OverLimit.class), mockDoSFiler);

    // Check
    // fourTwoNineSensor is not created
    verify(fourTwoNineSensor, never()).add(any(MetricName.class), any());
    assertEquals(Action.fromDelay(delayMs), action);
    // null metrics therefore we don't record anything
    verify(fourTwoNineSensor, never()).record();
  }

  @ParameterizedTest
  @ValueSource(
      longs = {
          0L, // this means the action is THROTTLE
          1L // this means the action is DELAY
      }
  )
  public void test_onRequestOverLimit_metricsNotNull_requestNotRejected(long delayMs) {
    // Prepare
    setMockedMetricsObjectResponses(allTags);
    // mock the behaviour for returning Action
    when(mockDoSFiler.getDelayMs()).thenReturn(delayMs);

    // Act
    jetty429DosFilterListener = new Jetty429DosFilterListener(metrics, allTags,
        TEST_JMX_PREFIX);
    Action action = jetty429DosFilterListener.onRequestOverLimit(mock(HttpServletRequest.class),
        mock(OverLimit.class), mockDoSFiler);

    // Check
    assertNotEquals(Action.REJECT, action);
    assertEquals(Action.fromDelay(delayMs), action);
    // non REJECT actions don't trigger metric recording
    verifyFourTwoNineSensor(fourTwoNineSensor, allTags, 0);
  }

  @Test
  public void test_onRequestOverLimit_metricsNotNull_requestRejected() {
    // Prepare
    setMockedMetricsObjectResponses(allTags);
    // this means the action is REJECTED
    when(mockDoSFiler.getDelayMs()).thenReturn(-1L);

    // Act
    jetty429DosFilterListener = new Jetty429DosFilterListener(metrics, ImmutableMap.of(),
        TEST_JMX_PREFIX);
    Action action = jetty429DosFilterListener.onRequestOverLimit(mock(HttpServletRequest.class),
        mock(OverLimit.class), mockDoSFiler);

    // Check
    verify(fourTwoNineSensor, times(3)).add(any(MetricName.class), any(MeasurableStat.class));
    assertEquals(Action.REJECT, action);
    // REJECTED means 429, so the sensor records it
    verifyFourTwoNineSensor(fourTwoNineSensor, allTags, 1);
  }

  private void setMockedMetricsObjectResponses(Map<String, String> allTags) {
    MetricName rateName = new MetricName("request-error-rate", "jetty-metrics",
        "The average number of requests per second that resulted in 429 HTTP error "
            + "responses in Jetty layer", allTags);
    when(metrics.metricInstance(
        new MetricNameTemplate("request-error-rate", "jetty-metrics",
            "The average number of requests per second that resulted in 429 HTTP error "
                + "responses in Jetty layer",
            allTags.keySet()), allTags)).thenReturn(rateName);

    MetricName countName = new MetricName("request-error-count", "jetty-metrics",
        "A windowed count of requests that resulted in 429 HTTP error responses"
            + " in Jetty layer", allTags);
    when(metrics.metricInstance(
        new MetricNameTemplate("request-error-count", "jetty-metrics",
            "A windowed count of requests that resulted in 429 HTTP error responses"
                + " in Jetty layer",
            allTags.keySet()), allTags)).thenReturn(countName);

    MetricName cumulativeCountName = new MetricName("request-error-total", "jetty-metrics",
        "A cumulative count of requests that resulted in 429 HTTP error responses"
            + " in Jetty layer", allTags);
    when(metrics.metricInstance(
        new MetricNameTemplate("request-error-total", "jetty-metrics",
            "A cumulative count of requests that resulted in 429 HTTP error responses"
                + " in Jetty layer",
            allTags.keySet()), allTags)).thenReturn(cumulativeCountName);

    when(metrics.sensor(anyString(), any(), anyLong(), any(RecordingLevel.class),
        (Sensor[]) any())).thenReturn(fourTwoNineSensor);
  }

  private void verifyFourTwoNineSensor(Sensor fourTwoNineSensor, Map<String, String> allTags,
      int timesRecorded) {
    MetricName rateName = new MetricName(
        "request-error-rate",
        "jetty-metrics",
        "The average number of requests per second that resulted in 429 HTTP error "
            + "responses in Jetty layer",
        allTags);

    MetricName countName = new MetricName(
        "request-error-count",
        "jetty-metrics",
        "A windowed count of requests that resulted in 429 HTTP error responses"
            + " in Jetty layer",
        allTags);

    MetricName cumulativeCountName = new MetricName(
        "request-error-total",
        "jetty-metrics",
        "A cumulative count of requests that resulted in 429 HTTP error responses"
            + " in Jetty layer",
        allTags);

    verify(fourTwoNineSensor).add(eq(rateName), any(Rate.class));
    verify(fourTwoNineSensor).add(eq(countName), any(SampledStat.class));
    verify(fourTwoNineSensor).add(eq(cumulativeCountName), any(CumulativeSum.class));
    verify(fourTwoNineSensor, times(timesRecorded)).record();
  }
}
