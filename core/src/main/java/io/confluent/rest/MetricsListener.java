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

package io.confluent.rest;

import java.net.Socket;
import java.util.Map;

import io.confluent.common.metrics.MetricName;
import io.confluent.common.metrics.Metrics;
import io.confluent.common.metrics.Sensor;
import io.confluent.common.metrics.stats.Rate;
import io.confluent.common.metrics.stats.Total;
import org.eclipse.jetty.io.NetworkTrafficListener;

public class MetricsListener extends NetworkTrafficListener.Adapter {

  /*
   * `NetworkTrafficListener` in Jetty 9.2 doesn't expose `accepted`
   * so we can't report this accurately. In an effort to be backwards
   * compatible, we temporarily approximate the value by using `opened`.
   * `accepts` will be removed in a future release.
   */
  @Deprecated
  private final Sensor accepts;
  private final Sensor connects;
  private final Sensor disconnects;
  private final Sensor connections;

  public MetricsListener(Metrics metrics, String metricGrpPrefix, Map<String, String> metricTags) {
    String metricGrpName = metricGrpPrefix + "-metrics";
    this.accepts = metrics.sensor("connections-accepted");
    MetricName metricName = new MetricName(
        "connections-accepted-rate",
        metricGrpName,
        "The average rate per second of accepted Jetty TCP connections",
        metricTags
    );
    this.accepts.add(metricName, new Rate());
    this.connects = metrics.sensor("connections-opened");
    metricName = new MetricName(
        "connections-opened-rate",
        metricGrpName,
       "The average rate per second of opened Jetty TCP connections",
        metricTags
    );
    this.connects.add(metricName, new Rate());
    this.disconnects = metrics.sensor("connections-closed");
    metricName = new MetricName(
        "connections-closed-rate",
        metricGrpName,
        "The average rate per second of closed Jetty TCP connections",
        metricTags
    );
    this.disconnects.add(metricName, new Rate());
    this.connections = metrics.sensor("connections");
    metricName = new MetricName(
        "connections-active",
        metricGrpName,
        "Total number of active Jetty TCP connections",
        metricTags
    );
    this.connections.add(metricName, new Total());
  }



  @Override
  public void opened(Socket socket) {
    this.connects.record();
    this.connections.record(1);
    this.accepts.record();
  }

  @Override
  public void closed(Socket socket) {
    this.disconnects.record();
    this.connections.record(-1);
  }

}
