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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
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
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.MetricNameTemplate;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.metrics.Sensor.RecordingLevel;
import org.apache.kafka.common.metrics.stats.CumulativeSum;
import org.apache.kafka.common.metrics.stats.Rate;
import org.apache.kafka.common.metrics.stats.SampledStat;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Slf4jRequestLogWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class KafkaCustomRequestLogTest {

  private Metrics metrics;
  private Request request;
  private Response response;
  private Sensor fourTwoNineSensor;
  private Slf4jRequestLogWriter logWriter;
  private org.eclipse.jetty.http.MetaData.Response metaDataResponse;
  private HttpChannel httpChannel;

  @BeforeEach
  public void setUp() {
    metrics = mock(Metrics.class);
    request = mock(Request.class);
    response = mock(Response.class);
    fourTwoNineSensor = mock(Sensor.class);
    logWriter = mock(Slf4jRequestLogWriter.class);
    metaDataResponse = mock(org.eclipse.jetty.http.MetaData.Response.class);
    httpChannel = mock(HttpChannel.class);
  }

  @Test
  public void response429_LowerCaseLKCInPath_LogsMetric_LogLineWritten()
      throws IOException {

    Map<String, String> allTags = new HashMap<>();
    allTags.put("http_status_code", "429");
    allTags.put("tenant", "lkc-abc123");
    setMockedObjectResponses(429,
        "http://lkc-abc123.v4.network.address/clusters/lkc-efg456/topics");
    setMockedMetricsObjectResponses(allTags, "kafka-rest:request-errors-429:429:lkc-abc123");

    KafkaCustomRequestLog customRequestLog = new KafkaCustomRequestLog(logWriter,
        CustomRequestLog.EXTENDED_NCSA_FORMAT + " %{ms}T", metrics, Collections.emptyMap(),
        "kafka-rest");
    customRequestLog.log(request, response);

    verifyFourTwoNineSensor(fourTwoNineSensor, allTags);
    verify(logWriter).write(
        startsWith("remoteHost - - [01/Jan/1970:00:00:00 +0000] \"- - -\" 429 12345 \"-\" \"-\" "));
  }


  @Test
  public void response429_UpperCaseLKCInPath_LogsMetric_LogLineWritten() throws IOException {

    Map<String, String> allTags = new HashMap<>();
    allTags.put("http_status_code", "429");
    allTags.put("tenant", "lkc-abc123");
    setMockedObjectResponses(429, "/a/LKC-abc123/c");
    setMockedMetricsObjectResponses(allTags, "kafka-rest:request-errors-429:429:lkc-abc123");

    KafkaCustomRequestLog customRequestLog = new KafkaCustomRequestLog(logWriter,
        CustomRequestLog.EXTENDED_NCSA_FORMAT + " %{ms}T", metrics, Collections.emptyMap(),
        "kafka-rest");
    customRequestLog.log(request, response);

    verifyFourTwoNineSensor(fourTwoNineSensor, allTags);
    verify(logWriter).write(
        startsWith("remoteHost - - [01/Jan/1970:00:00:00 +0000] \"- - -\" 429 12345 \"-\" \"-\" "));
  }

  @Test
  public void requestNot429_NoMetricLogged_LogLineWritten() throws IOException {

    setMockedObjectResponses(200, "/a/b/c");

    KafkaCustomRequestLog customRequestLog = new KafkaCustomRequestLog(logWriter,
        CustomRequestLog.EXTENDED_NCSA_FORMAT + " %{ms}T", metrics, Collections.emptyMap(),
        "kafka-rest");
    customRequestLog.log(request, response);

    verify(metrics, never()).sensor(anyString(),eq(null), anyLong(), eq(RecordingLevel.INFO), eq((Sensor[]) null));
    verify(logWriter).write(
        startsWith("remoteHost - - [01/Jan/1970:00:00:00 +0000] \"- - -\" 200 12345 \"-\" \"-\" "));
  }

  @Test
  public void nullMetricObject_NoErrorThrown_LogLineWritten() throws IOException {

    setMockedObjectResponses(429, "/a/b/c");

    KafkaCustomRequestLog customRequestLog = new KafkaCustomRequestLog(logWriter,
        CustomRequestLog.EXTENDED_NCSA_FORMAT + " %{ms}T", null, Collections.emptyMap(),
        "kafka-rest");

    customRequestLog.log(request, response);
    verify(logWriter).write(
        startsWith("remoteHost - - [01/Jan/1970:00:00:00 +0000] \"- - -\" 429 12345 \"-\" \"-\" "));
  }

  @Test
  public void urlHasNoLKC_MetricsLogged() throws IOException {

    Map<String, String> allTags = new HashMap<>();
    allTags.put("http_status_code", "429");
    setMockedObjectResponses(429, "/a/b/c");
    setMockedMetricsObjectResponses(allTags, "kafka-rest:request-errors-429:429");

    KafkaCustomRequestLog customRequestLog = new KafkaCustomRequestLog(logWriter,
        CustomRequestLog.EXTENDED_NCSA_FORMAT + " %{ms}T", metrics, Collections.emptyMap(),
        "kafka-rest");
    customRequestLog.log(request, response);

    verifyFourTwoNineSensor(fourTwoNineSensor, allTags);
    verify(logWriter).write(
        startsWith("remoteHost - - [01/Jan/1970:00:00:00 +0000] \"- - -\" 429 12345 \"-\" \"-\" "));
  }

  @Test
  public void existingMetricsHaveTags_MetricsLoggedWithAdditionalTags() throws IOException {

    Map<String, String> allTags = new TreeMap<>();
    allTags.put("http_status_code", "429");
    allTags.put("tenant", "lkc-abc123");
    allTags.put("my", "tag");
    setMockedObjectResponses(429, "/a/lkc-abc123/c");
    setMockedMetricsObjectResponses(allTags, "kafka-rest:request-errors-429:429:tag:lkc-abc123");

    KafkaCustomRequestLog customRequestLog = new KafkaCustomRequestLog(logWriter,
        CustomRequestLog.EXTENDED_NCSA_FORMAT + " %{ms}T", metrics,
        Collections.singletonMap("my", "tag"), "kafka-rest");
    customRequestLog.log(request, response);

    verifyFourTwoNineSensor(fourTwoNineSensor, allTags);
    verify(logWriter).write(
        startsWith("remoteHost - - [01/Jan/1970:00:00:00 +0000] \"- - -\" 429 12345 \"-\" \"-\" "));
  }

  @Test
  public void writingToTwoDifferentLKCs_TwoSensorsUsed() throws IOException {
    Map<String, String> allTags1 = new HashMap<>();
    allTags1.put("http_status_code", "429");
    allTags1.put("tenant", "lkc-abc123");
    setMockedObjectResponses(429,
        "http://lkc-abc123.v4.network.address/clusters/lkc-efg456/topics");
    setMockedMetricsObjectResponses(allTags1, "kafka-rest:request-errors-429:429:lkc-abc123");

    //second request
    Map<String, String> allTags2 = new HashMap<>();
    allTags2.put("http_status_code", "429");
    allTags2.put("tenant", "lkc-efg567");

    Request request2 = mock(Request.class);
    Response response2 = mock(Response.class);
    org.eclipse.jetty.http.MetaData.Response metaDataResponse2 = mock(org.eclipse.jetty.http.MetaData.Response.class);
    HttpChannel httpChannel2 = mock(HttpChannel.class);

    when(response2.getStatus()).thenReturn(429);
    when(request2.getRequestURL()).thenReturn(new StringBuffer("http://lkc-efg567.v4.network.address/clusters/lkc-efg567/topics"));
    when(request2.getRemoteHost()).thenReturn("remoteHost");
    when(response2.getCommittedMetaData()).thenReturn(metaDataResponse2);
    when(metaDataResponse2.getStatus()).thenReturn(429);
    when(response2.getHttpChannel()).thenReturn(httpChannel2);
    when(httpChannel2.getBytesWritten()).thenReturn(12345L);

    MetricName metricName = new MetricName("request-error-rate-429", "request-errors-429",
        "The average number of requests per second that resulted in 429 error responses", allTags2);
    when(metrics.metricInstance(
        new MetricNameTemplate("request-error-rate-429", "request-errors-429",
            "The average number of requests per second that resulted in 429 error responses",
            allTags2.keySet()), allTags2)).thenReturn(metricName);

    MetricName metricName2 = new MetricName("request-error-count-429", "request-errors-429",
        "A windowed count of requests that resulted in 429 HTTP error responses", allTags2);
    when(metrics.metricInstance(
        new MetricNameTemplate("request-error-count-429", "request-errors-429",
            "A windowed count of requests that resulted in 429 HTTP error responsess",
            allTags2.keySet()), allTags2)).thenReturn(metricName2);

    MetricName metricName3 = new MetricName("request-error-total-429", "request-errors-429",
        "TA cumulative count of requests that resulted in 429 HTTP error responses", allTags2);
    when(metrics.metricInstance(
        new MetricNameTemplate("request-error-total-429", "request-errors-429",
            "A cumulative count of requests that resulted in 429 HTTP error responses",
            allTags2.keySet()), allTags2)).thenReturn(metricName3);

    Sensor fourTwoNineSensor2 = mock(Sensor.class);
    when(metrics.sensor("kafka-rest:request-errors-429:429:lkc-efg567", null, TimeUnit.HOURS.toSeconds(1), RecordingLevel.INFO,
        (Sensor[]) null)).thenReturn(fourTwoNineSensor2);

    //send calls
    KafkaCustomRequestLog customRequestLog = new KafkaCustomRequestLog(logWriter,
        CustomRequestLog.EXTENDED_NCSA_FORMAT + " %{ms}T", metrics, Collections.emptyMap(),
        "kafka-rest");
    customRequestLog.log(request, response);
    customRequestLog.log(request2, response2);

    //validate calls
    verifyFourTwoNineSensor(fourTwoNineSensor, allTags1);
    verifyFourTwoNineSensor(fourTwoNineSensor2, allTags2);
    verify(logWriter, times(2)).write(
        startsWith("remoteHost - - [01/Jan/1970:00:00:00 +0000] \"- - -\" 429 12345 \"-\" \"-\" "));
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
    MetricName metricName = new MetricName("request-error-rate-429", "request-errors-429",
        "The average number of requests per second that resulted in 429 error responses", allTags);
    when(metrics.metricInstance(
        new MetricNameTemplate("request-error-rate-429", "request-errors-429",
            "The average number of requests per second that resulted in 429 error responses",
            allTags.keySet()), allTags)).thenReturn(metricName);

    MetricName metricName2 = new MetricName("request-error-count-429", "request-errors-429",
        "A windowed count of requests that resulted in 429 HTTP error responses", allTags);
    when(metrics.metricInstance(
        new MetricNameTemplate("request-error-count-429", "request-errors-429",
            "A windowed count of requests that resulted in 429 HTTP error responsess",
            allTags.keySet()), allTags)).thenReturn(metricName2);

    MetricName metricName3 = new MetricName("request-error-total-429", "request-errors-429",
        "TA cumulative count of requests that resulted in 429 HTTP error responses", allTags);
    when(metrics.metricInstance(
        new MetricNameTemplate("request-error-total-429", "request-errors-429",
            "A cumulative count of requests that resulted in 429 HTTP error responses",
            allTags.keySet()), allTags)).thenReturn(metricName3);

    when(metrics.sensor(sensorName, null, TimeUnit.HOURS.toSeconds(1), RecordingLevel.INFO,
        (Sensor[]) null)).thenReturn(fourTwoNineSensor);
  }


  private void verifyFourTwoNineSensor(Sensor fourTwoNineSensor, Map<String, String> allTags) {
    MetricName rateName = new MetricName(
        "request-error-rate-429",
        "request-errors-429",
        "The average number of requests per second that resulted in 429 "
            + "error responses",
        allTags);

    MetricName countName = new MetricName(
        "request-error-count-429",
        "request-errors-429",
        "A windowed count of requests that resulted in 429 HTTP error responses",
        allTags);

    MetricName cumulativeCountName = new MetricName(
        "request-error-total-429",
        "request-errors-429",
        "A cumulative count of requests that resulted in 429 HTTP error responses",
        allTags);

    verify(fourTwoNineSensor).add(eq(rateName), any(Rate.class));
    verify(fourTwoNineSensor).add(eq(countName), any(SampledStat.class));
    verify(fourTwoNineSensor).add(eq(cumulativeCountName), any(CumulativeSum.class));
    verify(fourTwoNineSensor).record();
  }
}
