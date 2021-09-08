/*
 * Copyright 2015 Confluent Inc.
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

import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ContainerResponse;
import org.glassfish.jersey.server.model.Resource;
import org.glassfish.jersey.server.model.ResourceMethod;
import org.glassfish.jersey.server.monitoring.ApplicationEvent;
import org.glassfish.jersey.server.monitoring.ApplicationEventListener;
import org.glassfish.jersey.server.monitoring.RequestEvent;
import org.glassfish.jersey.server.monitoring.RequestEventListener;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.confluent.rest.annotations.PerformanceMetric;

import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.stats.Avg;
import org.apache.kafka.common.metrics.stats.Max;
import org.apache.kafka.common.metrics.stats.Percentile;
import org.apache.kafka.common.metrics.stats.Percentiles;
import org.apache.kafka.common.metrics.stats.WindowedCount;
import org.apache.kafka.common.metrics.stats.Rate;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.utils.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jersey ResourceMethodApplicationListener that records metrics for each endpoint by listening
 * for method start and finish events. It reports some common metrics for each such as rate and
 * latency (average, 90th, 99th, etc).
 */
public class MetricsResourceMethodApplicationListener implements ApplicationEventListener {
  private static final Logger log = LoggerFactory.getLogger(
      MetricsResourceMethodApplicationListener.class);

  public static final String REQUEST_TAGS_PROP_KEY = "_request_tags";

  protected static final String HTTP_STATUS_CODE_TAG = "http_status_code";
  private static final String[] HTTP_STATUS_CODE_TEXT = {
      "unknown", "1xx", "2xx", "3xx", "4xx", "5xx"};
  private static final int PERCENTILE_NUM_BUCKETS = 200;
  private static final double PERCENTILE_MAX_LATENCY_IN_MS = TimeUnit.SECONDS.toMillis(10);

  private final Metrics metrics;
  private final String metricGrpPrefix;
  private Map<String, String> metricTags;
  Time time;
  private Map<Method, RequestScopedMetrics> methodMetrics = new HashMap<>();

  public MetricsResourceMethodApplicationListener(Metrics metrics, String metricGrpPrefix,
                                           Map<String,String> metricTags, Time time) {
    super();
    this.metrics = metrics;
    this.metricGrpPrefix = metricGrpPrefix;
    this.metricTags = (metricTags != null) ? metricTags : Collections.<String,String>emptyMap();
    this.time = time;
  }

  @Override
  public void onEvent(ApplicationEvent event) {
    if (event.getType() == ApplicationEvent.Type.INITIALIZATION_FINISHED) {
      // Special null key is used for global stats
      MethodMetrics m = new MethodMetrics(null, null, this.metrics, metricGrpPrefix, metricTags);
      methodMetrics.put(null, new RequestScopedMetrics(m, new ConstructionContext(this)));

      for (final Resource resource : event.getResourceModel().getResources()) {
        for (final ResourceMethod method : resource.getAllMethods()) {
          register(method);
        }

        for (final Resource childResource : resource.getChildResources()) {
          for (final ResourceMethod method : childResource.getAllMethods()) {
            register(method);
          }
        }
      }
    }
  }

  private void register(ResourceMethod method) {
    final Method definitionMethod = method.getInvocable().getDefinitionMethod();
    if (definitionMethod.isAnnotationPresent(PerformanceMetric.class)) {
      PerformanceMetric annotation = definitionMethod.getAnnotation(PerformanceMetric.class);

      MethodMetrics m = new MethodMetrics(method, annotation, metrics, metricGrpPrefix, metricTags);
      ConstructionContext context = new ConstructionContext(method, annotation, this);
      methodMetrics.put(definitionMethod, new RequestScopedMetrics(m, context));
    }
  }

  @Override
  public RequestEventListener onRequest(final RequestEvent event) {
    return new MetricsRequestEventListener(methodMetrics, time);
  }

  private static class RequestScopedMetrics {
    private final MethodMetrics methodMetrics;
    private final ConstructionContext context;
    private Map<SortedMap<String, String>, MethodMetrics> requestMetrics
        = new ConcurrentHashMap<>();

    public RequestScopedMetrics(MethodMetrics metrics, ConstructionContext context) {
      this.methodMetrics = metrics;
      this.context = context;
    }

    public MethodMetrics metrics() {
      return methodMetrics;
    }

    public MethodMetrics metrics(Map<String, String> requestTags) {
      TreeMap<String, String> key = new TreeMap<>(requestTags);
      return requestMetrics.compute(key, (k, v) -> v == null ? create(k) : v);
    }

    public MethodMetrics create(Map<String, String> requestTags) {
      Map<String, String> allTags = new HashMap<>();
      allTags.putAll(context.metricTags);
      allTags.putAll(requestTags);
      return new MethodMetrics(context.method, context.performanceMetric,
          context.metrics, context.metricGrpPrefix, allTags);
    }
  }

  private static class ConstructionContext {
    private ResourceMethod method;
    private PerformanceMetric performanceMetric;
    private Map<String, String> metricTags;
    private String metricGrpPrefix;
    private Metrics metrics;

    public ConstructionContext(MetricsResourceMethodApplicationListener methodAppListener) {
      this(null, null, methodAppListener);
    }

    public ConstructionContext(
        ResourceMethod method,
        PerformanceMetric performanceMetric,
        MetricsResourceMethodApplicationListener methodAppListener
    ) {
      this.method = method;
      this.performanceMetric = performanceMetric;
      this.metrics = methodAppListener.metrics;
      this.metricTags = methodAppListener.metricTags;
      this.metricGrpPrefix = methodAppListener.metricGrpPrefix;
    }
  }

  private static class MethodMetrics {
    private Sensor requestSizeSensor;
    private Sensor responseSizeSensor;
    private Sensor requestLatencySensor;
    private Sensor errorSensor;
    private Sensor[] errorSensorByStatus = new Sensor[6];

    public MethodMetrics(ResourceMethod method, PerformanceMetric annotation, Metrics metrics,
                         String metricGrpPrefix, Map<String, String> metricTags) {
      String metricGrpName = metricGrpPrefix + "-metrics";

      this.requestSizeSensor = metrics.sensor(getName(method, annotation, "request-size"));
      MetricName metricName = new MetricName(
          getName(method, annotation, "request-count-windowed"), metricGrpName,
          "The request count using a windowed counter", metricTags);
      this.requestSizeSensor.add(metricName, new WindowedCount());
      metricName = new MetricName(
          getName(method, annotation, "request-rate"), metricGrpName,
          "The average number of HTTP requests per second.", metricTags);
      this.requestSizeSensor.add(metricName, new Rate(new WindowedCount()));
      metricName = new MetricName(
          getName(method, annotation, "request-byte-rate"), metricGrpName,
          "Bytes/second of incoming requests", metricTags);
      this.requestSizeSensor.add(metricName, new Avg());
      metricName = new MetricName(
          getName(method, annotation, "request-size-avg"), metricGrpName,
          "The average request size in bytes", metricTags);
      this.requestSizeSensor.add(metricName, new Avg());
      metricName = new MetricName(
          getName(method, annotation, "request-size-max"), metricGrpName,
          "The maximum request size in bytes", metricTags);
      this.requestSizeSensor.add(metricName, new Max());

      this.responseSizeSensor = metrics.sensor(getName(method, annotation, "response-size"));
      metricName = new MetricName(
          getName(method, annotation, "response-rate"), metricGrpName,
          "The average number of HTTP responses per second.", metricTags);
      this.responseSizeSensor.add(metricName, new Rate(new WindowedCount()));
      metricName = new MetricName(
          getName(method, annotation, "response-byte-rate"), metricGrpName,
          "Bytes/second of outgoing responses", metricTags);
      this.responseSizeSensor.add(metricName, new Avg());
      metricName = new MetricName(
          getName(method, annotation, "response-size-avg"), metricGrpName,
          "The average response size in bytes", metricTags);
      this.responseSizeSensor.add(metricName, new Avg());
      metricName = new MetricName(
          getName(method, annotation, "response-size-max"), metricGrpName,
          "The maximum response size in bytes", metricTags);
      this.responseSizeSensor.add(metricName, new Max());

      this.requestLatencySensor = metrics.sensor(getName(method, annotation, "request-latency"));
      metricName = new MetricName(
          getName(method, annotation, "request-latency-avg"), metricGrpName,
          "The average request latency in ms", metricTags);
      this.requestLatencySensor.add(metricName, new Avg());
      metricName = new MetricName(
          getName(method, annotation, "request-latency-max"), metricGrpName,
          "The maximum request latency in ms", metricTags);
      this.requestLatencySensor.add(metricName, new Max());

      Percentiles percs = new Percentiles(Float.SIZE / 8 * PERCENTILE_NUM_BUCKETS,
          0.0,
          PERCENTILE_MAX_LATENCY_IN_MS,
          Percentiles.BucketSizing.CONSTANT,
          new Percentile(new MetricName(
              getName(method, annotation, "request-latency-95"), metricGrpName,
              "The 95th percentile request latency in ms", metricTags), 95),
          new Percentile(new MetricName(
              getName(method, annotation, "request-latency-99"), metricGrpName,
              "The 99th percentile request latency in ms", metricTags), 99));
      this.requestLatencySensor.add(percs);

      for (int i = 0; i < 6; i++) {
        errorSensorByStatus[i] = metrics.sensor(getName(method, annotation, "errors" + i));
        HashMap<String, String> tags = new HashMap<>(metricTags);
        tags.put(HTTP_STATUS_CODE_TAG, HTTP_STATUS_CODE_TEXT[i]);
        metricName = new MetricName(getName(method, annotation, "request-error-rate"),
            metricGrpName,
            "The average number of requests"
                + " per second that resulted in HTTP error responses with code "
                + HTTP_STATUS_CODE_TEXT[i],
            tags);
        errorSensorByStatus[i].add(metricName, new Rate());

        metricName = new MetricName(getName(method, annotation, "request-error-count-windowed"),
            metricGrpName,
            "A windowed count of requests that resulted in an HTTP error response with code - "
                + HTTP_STATUS_CODE_TEXT[i], tags);
        errorSensorByStatus[i].add(metricName, new WindowedCount());

      }

      this.errorSensor = metrics.sensor(getName(method, annotation, "errors"));
      metricName = new MetricName(
          getName(method, annotation, "request-error-rate"),
          metricGrpName,
          "The average number of requests per second that resulted in HTTP error responses",
          metricTags);
      this.errorSensor.add(metricName, new Rate());

      this.errorSensor = metrics.sensor(getName(method, annotation, "errors-count"));
      metricName = new MetricName(
          getName(method, annotation, "request-error-count-windowed"),
          metricGrpName,
          "A windowed count of requests that resulted in HTTP error responses",
          metricTags);
      this.errorSensor.add(metricName, new WindowedCount());
    }

    /**
     * Indicate that a request has finished successfully.
     */
    public void finished(long requestSize, long responseSize, long latencyMs) {
      requestSizeSensor.record(requestSize);
      responseSizeSensor.record(responseSize);
      requestLatencySensor.record(latencyMs);
    }

    /**
     * Indicate that a request has failed with an exception.
     */
    public void exception(final RequestEvent event) {
      //map the http status codes down to their classes (2xx, 4xx, 5xx)
      // use the containerResponse status as it has the http status after ExceptionMappers
      // are applied
      int idx = event.getContainerResponse() != null
          ? event.getContainerResponse().getStatus() / 100 : 5;

      // Index 0 means "unknown" status codes.
      if (idx <= 0 || idx >= 6) {
        log.error("Unidentified exception to record metrics against", event.getException());
        idx = 0;
      }

      errorSensorByStatus[idx].record();
      errorSensor.record();
    }

    private static String getName(final ResourceMethod method,
                                  final PerformanceMetric annotation, String metric) {
      StringBuilder builder = new StringBuilder();
      boolean prefixed = false;
      if (annotation != null && !annotation.value().equals(PerformanceMetric.DEFAULT_NAME)) {
        builder.append(annotation.value());
        builder.append('.');
        prefixed = true;
      }
      if (!prefixed && method != null) {
        String className = method.getInvocable().getDefinitionMethod()
            .getDeclaringClass().getSimpleName();
        String methodName = method.getInvocable().getDefinitionMethod().getName();
        builder.append(className);
        builder.append('.');
        builder.append(methodName);
        builder.append('.');
      }
      builder.append(metric);
      return builder.toString();
    }

  }

  private static class MetricsRequestEventListener implements RequestEventListener {
    private final Time time;
    private final Map<Method, RequestScopedMetrics> metrics;
    private long started;
    private CountingInputStream wrappedRequestStream;
    private CountingOutputStream wrappedResponseStream;

    public MetricsRequestEventListener(final Map<Method, RequestScopedMetrics> metrics, Time time) {
      this.metrics = metrics;
      this.time = time;
    }

    @Override
    public void onEvent(RequestEvent event) {
      if (event.getType() == RequestEvent.Type.MATCHING_START) {
        started = time.milliseconds();
        final ContainerRequest request = event.getContainerRequest();
        wrappedRequestStream = new CountingInputStream(request.getEntityStream());
        request.setEntityStream(wrappedRequestStream);
      } else if (event.getType() == RequestEvent.Type.RESP_FILTERS_START) {
        final ContainerResponse response = event.getContainerResponse();
        wrappedResponseStream = new CountingOutputStream(response.getEntityStream());
        response.setEntityStream(wrappedResponseStream);
      } else if (event.getType() == RequestEvent.Type.FINISHED) {
        final long elapsed = time.milliseconds() - started;
        final long requestSize;
        if (wrappedRequestStream != null) {
          requestSize = wrappedRequestStream.size();
        } else {
          requestSize = 0;
        }
        final long responseSize;
        // nothing guarantees we always encounter an event where getContainerResponse is not null
        // in the event of dispatch errors, the error response is delegated to the servlet container
        if (wrappedResponseStream != null) {
          responseSize = wrappedResponseStream.size();
        } else {
          responseSize = 0;
        }

        // Handle exceptions
        if (event.getException() != null) {
          this.metrics.get(null).metrics().exception(event);
          final MethodMetrics metrics = getMethodMetrics(event);
          if (metrics != null) {
            metrics.exception(event);
          }
        }

        this.metrics.get(null).metrics().finished(requestSize, responseSize, elapsed);
        final MethodMetrics metrics = getMethodMetrics(event);
        if (metrics != null) {
          metrics.finished(requestSize, responseSize, elapsed);
        }
      }
    }

    private MethodMetrics getMethodMetrics(RequestEvent event) {
      ResourceMethod method = event.getUriInfo().getMatchedResourceMethod();
      if (method == null) {
        return null;
      }

      RequestScopedMetrics metrics = this.metrics.get(method.getInvocable().getDefinitionMethod());
      if (metrics == null) {
        return null;
      }

      Object tagsObj = event.getContainerRequest().getProperty(REQUEST_TAGS_PROP_KEY);

      if (tagsObj == null) {
        return metrics.metrics();
      }
      if (!(tagsObj instanceof Map<?, ?>)) {
        throw new ClassCastException("Expected the value for property " + REQUEST_TAGS_PROP_KEY
            + " to be a " + Map.class + ", but it is " + tagsObj.getClass());
      }
      @SuppressWarnings("unchecked")
      Map<String, String> tags = (Map<String, String>) tagsObj;

      // we have additional tags, find the appropriate metrics holder
      return metrics.metrics(tags);
    }

    private static class CountingInputStream extends FilterInputStream {
      private long count = 0;
      private long mark = 0;

      public CountingInputStream(InputStream is) {
        super(is);
      }

      public long size() {
        return count;
      }

      @Override
      public int read() throws IOException {
        int b = super.read();
        count++;
        return b;
      }

      // Note that read(byte[]) for FilterInputStream calls this.read(b,0,b.length), NOT
      // underlying.read(b), so accounting for those calls is handled by read(byte[],int,int).

      @Override
      public int read(byte[] bytes, int off, int len) throws IOException {
        int nread = super.read(bytes, off, len);
        if (nread > 0) {
          count += nread;
        }
        return nread;
      }

      @Override
      public long skip(long l) throws IOException {
        long skipped = super.skip(l);
        count += skipped;
        return skipped;
      }

      @Override
      public synchronized void mark(int i) {
        super.mark(i);
        mark = count;
      }

      @Override
      public synchronized void reset() throws IOException {
        super.reset();
        count = mark;
      }
    }

    private static class CountingOutputStream extends FilterOutputStream {
      private long count = 0;

      public CountingOutputStream(OutputStream os) {
        super(os);
      }

      public long size() {
        return count;
      }

      // Note that we override all of these even though FilterOutputStream only requires
      // overriding the first to avoid doing byte-by-byte handling of the stream. Do NOT call
      // super.write() for these as they will convert everything into a series of write(int)
      // calls and wreck performance.

      @Override
      public void write(int b) throws IOException {
        count++;
        out.write(b);
      }

      @Override
      public void write(byte[] bytes) throws IOException {
        count += bytes.length;
        out.write(bytes);
      }

      @Override
      public void write(byte[] bytes, int off, int len) throws IOException {
        count += len;
        out.write(bytes, off, len);
      }
    }
  }
}

