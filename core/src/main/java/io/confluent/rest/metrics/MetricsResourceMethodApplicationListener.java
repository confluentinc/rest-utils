/**
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
 **/

package io.confluent.rest.metrics;

import org.glassfish.jersey.server.model.Resource;
import org.glassfish.jersey.server.model.ResourceMethod;
import org.glassfish.jersey.server.monitoring.ApplicationEvent;
import org.glassfish.jersey.server.monitoring.ApplicationEventListener;
import org.glassfish.jersey.server.monitoring.RequestEvent;
import org.glassfish.jersey.server.monitoring.RequestEventListener;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import io.confluent.common.metrics.MetricName;
import io.confluent.common.metrics.Metrics;
import io.confluent.common.metrics.Sensor;
import io.confluent.common.metrics.stats.Avg;
import io.confluent.common.metrics.stats.Count;
import io.confluent.common.metrics.stats.Rate;
import io.confluent.common.utils.Time;
import io.confluent.rest.annotations.PerformanceMetric;

/**
 * Jersey ResourceMethodApplicationListener that records metrics for each endpoint by listening
 * for method start and finish events. It reports some common metrics for each such as rate and
 * latency (average, 90th, 99th, etc).
 */
public class MetricsResourceMethodApplicationListener implements ApplicationEventListener {
  private final Metrics metrics;
  private final String metricGrpPrefix;
  private Map<String, String> metricTags;
  Time time;
  // This is immutable after it's initial construction
  private Map<Method, MethodMetrics> methodMetrics = new HashMap<Method, MethodMetrics>();

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
      methodMetrics.put(null,
                        new MethodMetrics(null, null, this.metrics, metricGrpPrefix, metricTags));

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
      methodMetrics.put(
          definitionMethod,
          new MethodMetrics(method, annotation, this.metrics, metricGrpPrefix, metricTags));
    }
  }

  @Override
  public RequestEventListener onRequest(final RequestEvent event) {
    return new MetricsRequestEventListener(methodMetrics, time);
  }

  private static class MethodMetrics {
    // This records request latencies, but we can also get basic requests stats (like rate of
    // HTTP requests) as well
    private Sensor requests;
    private Sensor exceptions;

    public MethodMetrics(ResourceMethod method, PerformanceMetric annotation, Metrics metrics,
                         String metricGrpPrefix, Map<String, String> metricTags) {
      String metricGrpName = metricGrpPrefix + "-metrics";

      this.requests = metrics.sensor(getName(method, annotation, "requests"));
      MetricName metricName = new MetricName(getName(method, annotation, "requests-rate"),
                                             metricGrpName, "Rate of HTTP requests", metricTags);
      this.requests.add(metricName, new Rate(new Count()));
      metricName = new MetricName(getName(method, annotation, "requests-latency-avg"),
                                  metricGrpName, "Average latency of HTTP requests requests",
                                  metricTags);
      this.requests.add(metricName, new Avg());

      this.exceptions = metrics.sensor(getName(method, annotation, "request-exceptions"));
      metricName = new MetricName(getName(method, annotation, "request-exceptions-rate"),
                                  metricGrpName, "Rate at which HTTP requests are being generated",
                                  metricTags);
      this.exceptions.add(metricName, new Rate());
    }

    /**
     * Indicate that a request has finished successfully.
     */
    public void finished(long latency) {
      requests.record();
    }

    /**
     * Indicate that a request has failed with an exception.
     */
    public void exception() {
      exceptions.record();
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
    private final Map<Method, MethodMetrics> metrics;
    private long started;

    public MetricsRequestEventListener(final Map<Method, MethodMetrics> metrics, Time time) {
      this.metrics = metrics;
      this.time = time;
    }

    @Override
    public void onEvent(RequestEvent event) {
      if (event.getType() == RequestEvent.Type.RESOURCE_METHOD_START) {
        started = time.milliseconds();
      } else if (event.getType() == RequestEvent.Type.RESOURCE_METHOD_FINISHED) {
        final long elapsed = time.milliseconds() - started;
        this.metrics.get(null).finished(elapsed);
        final MethodMetrics metrics = getMethodMetrics(event);
        if (metrics != null) {
          metrics.finished(elapsed);
        }
      } else if (event.getType() == RequestEvent.Type.ON_EXCEPTION) {
        this.metrics.get(null).exception();
        final MethodMetrics metrics = getMethodMetrics(event);
        if (metrics != null) {
          metrics.exception();
        }
      }
    }

    private MethodMetrics getMethodMetrics(RequestEvent event) {
      ResourceMethod method = event.getUriInfo().getMatchedResourceMethod();
      if (method == null) {
        return null;
      }
      return this.metrics.get(method.getInvocable().getDefinitionMethod());
    }
  }
}

