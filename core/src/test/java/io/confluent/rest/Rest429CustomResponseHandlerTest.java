/*
 * Copyright 2022 Confluent Inc.
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.MetricNameTemplate;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.metrics.Sensor.RecordingLevel;
import org.apache.kafka.common.metrics.stats.CumulativeSum;
import org.apache.kafka.common.metrics.stats.Rate;
import org.apache.kafka.common.metrics.stats.SampledStat;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class Rest429CustomResponseHandlerTest {

  private Request request;
  private Response response;
  private Sensor fourTwoNineSensor;
  private org.eclipse.jetty.http.MetaData.Response metaDataResponse;
  private HttpChannel httpChannel;
  private Metrics metrics;
  private HttpServletRequest servletRequest;

  @BeforeEach
  public void setUp() {
    request = mock(Request.class);
    response = mock(Response.class);
    fourTwoNineSensor = mock(Sensor.class);
    metaDataResponse = mock(org.eclipse.jetty.http.MetaData.Response.class);
    httpChannel = mock(HttpChannel.class);
    metrics = mock(Metrics.class);
    servletRequest = mock(HttpServletRequest.class);
  }

  @Test
  public void response429_LogsMetric_LogLineWritten()
      throws IOException, ServletException {
    Map<String, String> allTags = new HashMap<>();
    allTags.put("http_status_code", "429");
    setMockedObjectResponses(429,
        "http://lkc-abc123.v4.network.address/clusters/lkc-efg456/topics");
    setMockedMetricsObjectResponses(allTags, "kafka-rest:jersey-metrics:request-errors:429");

    Rest429CustomResponseHandler customRequestLog = new Rest429CustomResponseHandler(metrics, Collections.emptyMap(),
        "kafka-rest");
    customRequestLog.handle("http://lkc-abc123.v4.network.address/clusters/lkc-efg456/topics", request, servletRequest, response);

    verifyFourTwoNineSensor(fourTwoNineSensor, allTags, 1);
  }

  @Test
  public void requestNot429_NoMetricLogged_LogLineWritten() throws IOException, ServletException {
    Map<String, String> allTags = new HashMap<>();
    allTags.put("http_status_code", "429");
    setMockedObjectResponses(200, "/a/b/c");
    setMockedMetricsObjectResponses(allTags, "kafka-rest:jersey-metrics:request-errors:429");

    Rest429CustomResponseHandler customRequestLog = new Rest429CustomResponseHandler(metrics, Collections.emptyMap(),
        "kafka-rest");
    customRequestLog.handle("/a/b/c", request, servletRequest, response);

    verify(fourTwoNineSensor, never()).record();
  }

  @Test
  public void nullMetricObject_NoErrorThrown_LogLineWritten() throws IOException, ServletException {

    Map<String, String> allTags = new HashMap<>();
    allTags.put("http_status_code", "429");
    setMockedObjectResponses(429, "/a/b/c");

    Rest429CustomResponseHandler customRequestLog = new Rest429CustomResponseHandler(null, Collections.emptyMap(),
        "kafka-rest");

    customRequestLog.handle("/a/b/c", request, servletRequest, response);
  }

  @Test
  public void existingMetricsHaveTags_MetricsLoggedWithAdditionalTags_LogLineWritten()
      throws IOException, ServletException {
    Map<String, String> allTags = new TreeMap<>();
    allTags.put("http_status_code", "429");
    allTags.put("my", "tag");
    setMockedObjectResponses(429, "/a/lkc-abc123/c");
    setMockedMetricsObjectResponses(allTags, "kafka-rest:jersey-metrics:request-errors:429:tag");

    Rest429CustomResponseHandler customRequestLog = new Rest429CustomResponseHandler(metrics,
        Collections.singletonMap("my", "tag"), "kafka-rest");
    customRequestLog.handle("/a/b/c", request, servletRequest, response);

    verifyFourTwoNineSensor(fourTwoNineSensor, allTags, 1);
  }

  @Test
  public void writingToTwoDifferentLKCs_sameSensorsUsed_LogLineWritten()
      throws IOException, ServletException {
    Map<String, String> allTags = new HashMap<>();
    allTags.put("http_status_code", "429");
    setMockedObjectResponses(429,
        "http://lkc-abc123.v4.network.address/clusters/lkc-efg456/topics");
    setMockedMetricsObjectResponses(allTags, "kafka-rest:jersey-metrics:request-errors:429");

    //second request
    Request request2 = mock(Request.class);
    Response response2 = mock(Response.class);
    HttpChannel httpChannel2 = mock(HttpChannel.class);
    org.eclipse.jetty.http.MetaData.Response metaDataResponse2 = mock(org.eclipse.jetty.http.MetaData.Response.class);

    when(response2.getStatus()).thenReturn(429);
    when(request2.getRequestURL()).thenReturn(new StringBuffer("http://lkc-efg567.v4.network.address/clusters/lkc-efg567/topics"));
    when(request2.getRemoteHost()).thenReturn("remoteHost");
    when(response2.getCommittedMetaData()).thenReturn(metaDataResponse2);
    when(metaDataResponse2.getStatus()).thenReturn(429);
    when(response2.getHttpChannel()).thenReturn(httpChannel2);
    when(httpChannel2.getBytesWritten()).thenReturn(12345L);

    //send calls
    Rest429CustomResponseHandler customRequestLog = new Rest429CustomResponseHandler(metrics, Collections.emptyMap(),
        "kafka-rest");
    customRequestLog.handle("/a/b/c", request, servletRequest, response);
    customRequestLog.handle("/a/b/c", request2, servletRequest, response2);

    //validate calls
    verifyFourTwoNineSensor(fourTwoNineSensor, allTags, 2);
  }

  private void setMockedObjectResponses(int returnCode, String url) {
    when(response.getStatus()).thenReturn(returnCode);
    when(request.getRequestURL()).thenReturn(new StringBuffer(url));
    when(request.getRemoteHost()).thenReturn("remoteHost");
    when(response.getCommittedMetaData()).thenReturn(metaDataResponse);
    when(metaDataResponse.getStatus()).thenReturn(returnCode);
    when(response.getHttpChannel()).thenReturn(httpChannel);
    when(httpChannel.getBytesWritten()).thenReturn(12345L);
  }

  private void setMockedMetricsObjectResponses(Map<String, String> allTags, String sensorName) {
    MetricName metricName = new MetricName("request-error-rate", "jersey-metrics",
        "The average number of requests per second that resulted in 429 error responses", allTags);
    when(metrics.metricInstance(
        new MetricNameTemplate("request-error-rate", "jersey-metrics",
            "The average number of requests per second that resulted in 429 error responses",
            allTags.keySet()), allTags)).thenReturn(metricName);

    MetricName metricName2 = new MetricName("request-error-count", "jersey-metrics",
        "A windowed count of requests that resulted in 429 HTTP error responses", allTags);
    when(metrics.metricInstance(
        new MetricNameTemplate("request-error-count", "jersey-metrics",
            "A windowed count of requests that resulted in 429 HTTP error responsess",
            allTags.keySet()), allTags)).thenReturn(metricName2);

    MetricName metricName3 = new MetricName("request-error-total", "jersey-metrics",
        "TA cumulative count of requests that resulted in 429 HTTP error responses", allTags);
    when(metrics.metricInstance(
        new MetricNameTemplate("request-error-total", "jersey-metrics",
            "A cumulative count of requests that resulted in 429 HTTP error responses",
            allTags.keySet()), allTags)).thenReturn(metricName3);

    when(metrics.sensor(sensorName, null, TimeUnit.HOURS.toSeconds(1), RecordingLevel.INFO,
        (Sensor[]) null)).thenReturn(fourTwoNineSensor);
  }


  private void verifyFourTwoNineSensor(Sensor fourTwoNineSensor, Map<String, String> allTags, int timesRecorded) {
    MetricName rateName = new MetricName(
        "request-error-rate",
        "jersey-metrics",
        "The average number of requests per second that resulted in 429 "
            + "error responses",
        allTags);

    MetricName countName = new MetricName(
        "request-error-count",
        "jersey-metrics",
        "A windowed count of requests that resulted in 429 HTTP error responses",
        allTags);

    MetricName cumulativeCountName = new MetricName(
        "request-error-total",
        "jersey-metrics",
        "A cumulative count of requests that resulted in 429 HTTP error responses",
        allTags);

    verify(fourTwoNineSensor).add(eq(rateName), any(Rate.class));
    verify(fourTwoNineSensor).add(eq(countName), any(SampledStat.class));
    verify(fourTwoNineSensor).add(eq(cumulativeCountName), any(CumulativeSum.class));
    verify(fourTwoNineSensor, times(timesRecorded)).record();
  }
}
