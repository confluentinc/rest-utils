package io.confluent.rest.metrics;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.metrics.Sensor.RecordingLevel;
import org.apache.kafka.common.metrics.stats.CumulativeCount;
import org.apache.kafka.common.metrics.stats.WindowedCount;
import org.eclipse.jetty.servlets.DoSFilter;
import org.eclipse.jetty.servlets.DoSFilter.Action;
import org.eclipse.jetty.servlets.DoSFilter.OverLimit;

public class JettyAllRequestsListener {
  private Sensor allRequestsSensor = null;
  private static final long SENSOR_EXPIRY_SECONDS = TimeUnit.HOURS.toSeconds(1);
  private static final String GROUP_NAME = "jetty-metrics";
  public JettyAllRequestsListener(Metrics metrics, Map<String, String> metricTags,
      String jmxPrefix) {
    if (metrics != null) {
      String sensorNamePrefix = jmxPrefix + ":" + GROUP_NAME;
      SortedMap<String, String> instanceMetricsTags = new TreeMap<>(metricTags);

      // All requests
      instanceMetricsTags.put("http_status_code", "xxx");
      String sensorTags =
          instanceMetricsTags.keySet().stream()
              .map(key -> ":" + instanceMetricsTags.get(key))
              .collect(Collectors.joining());

      String sensorName = sensorNamePrefix + ":request-count" + sensorTags;
      allRequestsSensor = metrics.sensor(sensorName,
          null, SENSOR_EXPIRY_SECONDS, RecordingLevel.DEBUG, (Sensor[]) null);

      allRequestsSensor.add(Jetty429MetricsDosFilterListener.getMetricName(metrics, "request-total-count",
          "A windowed count of requests that resulted any of HTTP responses in Jetty layer",
          instanceMetricsTags), new CumulativeCount());
    }
  }

  public void onRequest(HttpServletRequest request) {
    if (allRequestsSensor != null) {
      allRequestsSensor.record();
    }
  }
}
